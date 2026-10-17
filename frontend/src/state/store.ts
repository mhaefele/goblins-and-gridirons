import { create } from "zustand";
import {
    ApiError,
    getBonusHand,
    getLegalActions,
    getPitch,
    getRoster,
    getSnapshot,
    startMatch,
    type BonusHandDto,
    type Coordinate,
    type DomainEvent,
    type LegalActionWire,
    type MatchId,
    type MatchRosterDto,
    type PitchLayoutDto,
    type PlayerAbility,
    type PlayerDecisionType,
    type PlayerId,
} from "../api/client";
import { subscribeMatchEvents, type MatchEventSubscription, type StreamFrame } from "../api/stream";
import { applyStreamEvent } from "./applyEvent";
import { boardViewFromSnapshot, type BoardView } from "./boardView";
import type { EventLogEntry } from "./eventLog";
import { formatDomainEvent } from "./formatEvent";
import { dispatchLegalAction } from "./legalActionDispatch";
import { parsePlayerAbility, playerAbilityLabel } from "../render/actionVisuals";
import {
    ballMotionFromEvents,
    beatSummary,
    BOUNCE_HOP_MS,
    BOUNCE_LINGER_MS,
    buildDiceBeats,
    assertMotionOriginForEvent,
    CARRIER_BALL_RADIUS,
    challengeClaimCelebrationFromEvent,
    CHALLENGE_CELEBRATION_MS,
    cleanSweepCelebrationFromEvent,
    flashCoordsFromEvents,
    injuryReservesExit,
    LOOSE_BALL_RADIUS,
    pieceMotionFromEvents,
    presentationModeFor,
    RESERVES_EXIT_MS,
    reservesExitPath,
    sameCoord,
    touchdownCelebrationFromEvents,
    TOUCHDOWN_CELEBRATION_MS,
    type BallMotion,
    type DiceBeat,
    type DiceTheatreState,
    type PieceMotion,
    type PresentationMode,
    type ReservesExit,
    type ScoreCelebration,
} from "./presentEvents";
import {
    isHumanSeat as seatIsHuman,
    readLocalCoachFromUrl,
    resolveLocalCoach,
    thisBrowserActsFor,
    type CoachSeat,
} from "./seatIdentity";
import { readSkipDiceAnimations, writeSkipDiceAnimations } from "./uiPrefs";

type PresentationJob = {
    mode: Exclude<PresentationMode, "skip"> | "block";
    event: DomainEvent;
    start: () => void | Promise<void>;
};

function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

export type ConnectionPhase = "connecting" | "hydrating" | "spectating" | "awaitingInput" | "unavailable";

export type { DiceTheatreState, PieceMotion, BallMotion, ReservesExit, ScoreCelebration };

export type AbilityBanner = {
    ability: PlayerAbility;
    label: string;
    team: "HOME" | "AWAY";
};

export type MatchStoreState = {
    matchId: MatchId | null;
    connectionPhase: ConnectionPhase;
    pitch: PitchLayoutDto | null;
    roster: MatchRosterDto | null;
    view: BoardView | null;
    /**
     * This client's seat for HUD / bonus hand / ability banners.
     * Prefer `?coach=HOME|AWAY`, else sole HUMAN seat, else hotseat follows active coach.
     * See frontend ARCHITECTURE § Seat identity.
     */
    localCoach: CoachSeat;
    /** From URL at join; sticky for the session. */
    preferredLocalCoach: CoachSeat | null;
    legalActions: LegalActionWire[];
    legalActionsLoading: boolean;
    /** Seat-private bonus hand for [localCoach]. */
    bonusHand: BonusHandDto | null;
    /** From the latest human `playerActionRequired`; drives the decision banner. */
    pendingDecisionType: PlayerDecisionType | null;
    /** Ability enum when the pending prompt is ability-driven. */
    pendingAbility: PlayerAbility | null;
    /** Medieval scroll shout while an ability is prompted / resolving. */
    abilityBanner: AbilityBanner | null;
    actionError: string | null;
    eventLog: EventLogEntry[];
    lastDiceSummary: string | null;
    diceTheatre: DiceTheatreState | null;
    /** When true, skip tumble theatre and keep only the last-roll summary. */
    skipDiceAnimations: boolean;
    /** Attacker/defender while block dice are about to/currently resolve. */
    activeBlock: { attackerId: PlayerId; defenderId: PlayerId } | null;
    pieceMotion: PieceMotion | null;
    ballMotion: BallMotion | null;
    reservesExit: ReservesExit | null;
    flashSquares: Coordinate[];
    celebration: ScoreCelebration | null;
    /** Brief HUD score pulse after a challenge claim / clean sweep folds. */
    scorePulseTeam: "HOME" | "AWAY" | null;
    joinMatch: (matchId: MatchId) => void;
    leaveMatch: () => void;
    submitLegalAction: (action: LegalActionWire) => Promise<void>;
    clearActionError: () => void;
    /** Skip the current dice beat, or dismiss when on the last reveal. */
    dismissDiceTheatre: () => void;
    setSkipDiceAnimations: (skip: boolean) => void;
};

type MatchStoreInternal = MatchStoreState & {
    subscription: MatchEventSubscription | null;
    reconnecting: boolean;
    flashTimer: ReturnType<typeof setTimeout> | null;
    diceTimer: ReturnType<typeof setTimeout> | null;
    celebrationTimer: ReturnType<typeof setTimeout> | null;
    scorePulseTimer: ReturnType<typeof setTimeout> | null;
    abilityBannerTimer: ReturnType<typeof setTimeout> | null;
    motionTimer: ReturnType<typeof setTimeout> | null;
    ballMotionTimer: ReturnType<typeof setTimeout> | null;
    exitTimer: ReturnType<typeof setTimeout> | null;
    hopTimer: ReturnType<typeof setTimeout> | null;
    /** Last dice anchor so solo SSE rolls (armour) stay next to the action. */
    lastActionAnchor: Coordinate | null;
    /**
     * Command just POSTed — used for dice anchors while SSE presents the action.
     * Null means presentation uses stream context only.
     */
    pendingSubmittedAction: LegalActionWire | null;
    /**
     * BLOCK started but defender wasn't unique yet — filled when a knockdown/shove/injury lands.
     */
    pendingBlockAttackerId: PlayerId | null;
};

function playerOccupies(view: BoardView, at: Coordinate): boolean {
    return view.pieces.players.some(
        (piece) => piece.status !== "RESERVES" && piece.at != null && sameCoord(piece.at, at),
    );
}

function chebyshev(a: Coordinate, b: Coordinate): number {
    return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
}

function pieceAt(view: BoardView, at: Coordinate) {
    return view.pieces.players.find((player) => player.at != null && sameCoord(player.at, at)) ?? null;
}

/**
 * Prefer submitted block destination; else a unique adjacent foe.
 * Standing opponents win over prone when several are adjacent (common melee clutter).
 */
function resolveBlockDefender(
    view: BoardView,
    attackerId: PlayerId,
    submitted: LegalActionWire | null,
): PlayerId | null {
    if (submitted?.kind === "block") {
        return pieceAt(view, submitted.destination)?.id ?? null;
    }
    const attacker = view.pieces.players.find((player) => player.id === attackerId);
    if (attacker?.at == null) return null;
    const adjacent = view.pieces.players.filter(
        (player) =>
            player.id !== attackerId &&
            player.team !== attacker.team &&
            player.status !== "RESERVES" &&
            player.at != null &&
            chebyshev(attacker.at!, player.at) === 1,
    );
    if (adjacent.length === 1) return adjacent[0]!.id;
    const standing = adjacent.filter((player) => player.status === "STANDING");
    if (standing.length === 1) return standing[0]!.id;
    return null;
}

/** When the unique-adjacent heuristic fails, infer defender from a knockdown / shove / injury. */
function inferBlockDefenderFromConsequence(
    view: BoardView,
    attackerId: PlayerId,
    victimId: PlayerId,
): PlayerId | null {
    if (victimId === attackerId) return null;
    const attacker = view.pieces.players.find((player) => player.id === attackerId);
    const victim = view.pieces.players.find((player) => player.id === victimId);
    if (attacker?.at == null || victim == null || victim.team === attacker.team || victim.status === "RESERVES") {
        return null;
    }
    // Still adjacent, or was shoved one square away.
    if (victim.at != null && chebyshev(attacker.at, victim.at) > 2) return null;
    return victim.id;
}

/** Match created but not yet kicked off — lifecycle only, not interaction gating. */
function isAwaitingKickoff(view: BoardView | null): boolean {
    return (view?.gameState.phase as string | undefined) === "GAME_START";
}

function isHumanSeat(view: BoardView, team: CoachSeat): boolean {
    return seatIsHuman(view.gameState, team);
}

function localCoachFor(view: BoardView, preferred: CoachSeat | null): CoachSeat {
    return resolveLocalCoach(view, preferred);
}

function isMatchOver(view: BoardView): boolean {
    return view.gameState.result != null || (view.gameState.phase as string) === "GAME_OVER";
}

/** `InputRequired.PlayerAction` on the wire — any phase; drives legal-action fetch. */
function isPlayerActionRequired(
    event: DomainEvent,
): event is Extract<DomainEvent, { kind: "playerActionRequired" }> {
    return event.kind === "playerActionRequired";
}

function decisionTypeFromEvent(event: Extract<DomainEvent, { kind: "playerActionRequired" }>): PlayerDecisionType {
    const raw = (event as { decisionType?: string }).decisionType;
    switch (raw) {
        case "INITIAL_PLAYER_PLACEMENT":
        case "EMERGENCY_RESERVES":
        case "REGULAR_PLAYER_ACTION":
        case "UNDYING_OFFER":
        case "THROW_TEAMMATE_CONTINUE":
        case "THROW_CONTINUE":
        case "BLOCK_CONTINUE":
        case "FOUL_CONTINUE":
        case "STAND_UP_CONTINUE":
        case "RESERVES_CONTINUE":
        case "RUN_CONTINUE":
        case "MARK_CONTINUE":
        case "SIDESTEP_CONTINUE":
        case "GANG_UP_OFFER":
        case "HOLD_THE_LINE_OFFER":
        case "EXTRA_EFFORT_OFFER":
        case "DANCE_STEP_OFFER":
        case "CLAIM_CHALLENGE":
        case "REPLACE_CHALLENGE":
            return raw;
        default:
            return "REGULAR_PLAYER_ACTION";
    }
}

function abilityFromEvent(event: Extract<DomainEvent, { kind: "playerActionRequired" }>): PlayerAbility | null {
    return parsePlayerAbility((event as { ability?: string | null }).ability);
}

function connectionPhaseAfterEvent(
    view: BoardView,
    event: DomainEvent,
    previous: ConnectionPhase,
    preferred: CoachSeat | null,
): ConnectionPhase {
    if (isMatchOver(view)) {
        return "spectating";
    }
    if (
        isPlayerActionRequired(event) &&
        thisBrowserActsFor(view.gameState, event.team, preferred)
    ) {
        return "awaitingInput";
    }
    if (previous === "awaitingInput" && event.kind === "actionStarted") {
        return "spectating";
    }
    return previous;
}

function appendEventLog(entries: EventLogEntry[], id: number, event: DomainEvent): EventLogEntry[] {
    return [...entries, { id, text: formatDomainEvent(event) }];
}

async function fetchBonusHandForView(
    matchId: MatchId,
    view: BoardView,
    preferred: CoachSeat | null,
): Promise<BonusHandDto | null> {
    if (!isHumanSeat(view, "HOME") && !isHumanSeat(view, "AWAY")) return null;
    return getBonusHand(matchId, localCoachFor(view, preferred));
}

async function fetchLegalActionsForView(
    matchId: MatchId,
    view: BoardView,
    forTeam: "HOME" | "AWAY" = view.gameState.activeCoach,
): Promise<LegalActionWire[]> {
    if (!isHumanSeat(view, forTeam)) return [];
    const actions = await getLegalActions(matchId);
    return actions.filter((action) => {
        if (!("playerId" in action)) return true;
        const player = view.pieces.players.find((piece) => piece.id === action.playerId);
        return player?.team === forTeam;
    });
}

function reducedMotionPreferred(): boolean {
    return typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export const useMatchStore = create<MatchStoreInternal>((set, get) => {
    let subscription: MatchEventSubscription | null = null;
    /** FX then fold — one job per SSE frame, in order. */
    let presentationQueue: PresentationJob[] = [];
    let presentationPumping = false;
    let diceIdleWaiters: Array<() => void> = [];
    /**
     * Accepted stream cursor (gap detection). Advances when a frame is queued.
     * [view.lastEventId] advances only when that frame is folded after its FX.
     */
    let acceptedStreamId = 0;
    let streamChain: Promise<void> = Promise.resolve();

    function closeSubscription() {
        subscription?.close();
        subscription = null;
    }

    function clearPresentationQueue() {
        presentationQueue = [];
        presentationPumping = false;
        diceIdleWaiters = [];
    }

    function resetStreamChain() {
        streamChain = Promise.resolve();
    }

    function enqueueStreamFrame(frame: StreamFrame) {
        streamChain = streamChain.then(() => acceptStreamFrame(frame)).catch(() => {
            /* reconnect / fetch errors already update store state */
        });
    }

    function notifyDiceIdle() {
        const waiters = diceIdleWaiters;
        diceIdleWaiters = [];
        for (const waiter of waiters) waiter();
    }

    function enqueuePresentation(job: PresentationJob) {
        presentationQueue.push(job);
        void pumpPresentation();
    }

    /** Peek queued (not yet folded) frames for a block victim when adjacent foes aren't unique. */
    function peekBlockDefenderFromQueue(view: BoardView, attackerId: PlayerId): PlayerId | null {
        for (const job of presentationQueue) {
            const event = job.event;
            if (event.kind === "playerStatusChanged" || event.kind === "playerInjured" || event.kind === "playerMoved") {
                const defenderId = inferBlockDefenderFromConsequence(view, attackerId, event.playerId);
                if (defenderId != null) return defenderId;
            }
            if (event.kind === "actionComplete" || event.kind === "phaseStarted") break;
        }
        return null;
    }

    function tryResolvePendingBlock() {
        const attackerId = get().pendingBlockAttackerId;
        const view = get().view;
        if (attackerId == null || view == null || get().activeBlock != null) return;
        const defenderId =
            peekBlockDefenderFromQueue(view, attackerId) ??
            resolveBlockDefender(view, attackerId, get().pendingSubmittedAction);
        if (defenderId != null) {
            set({ activeBlock: { attackerId, defenderId }, pendingBlockAttackerId: null });
        }
    }

    async function pumpPresentation() {
        if (presentationPumping) return;
        presentationPumping = true;
        try {
            while (presentationQueue.length > 0) {
                const job = presentationQueue.shift()!;
                try {
                    const result = job.start();
                    if (job.mode === "parallel") {
                        void Promise.resolve(result).catch((error) => {
                            const message =
                                error instanceof Error ? error.message : "Presentation failed";
                            set({ actionError: message });
                            console.error(error);
                        });
                        continue;
                    }
                    await Promise.resolve(result);
                } catch (error) {
                    const message = error instanceof Error ? error.message : "Presentation failed";
                    set({ actionError: message });
                    console.error(error);
                }
            }
        } finally {
            presentationPumping = false;
            if (presentationQueue.length > 0) void pumpPresentation();
        }
    }

    function clearFlashTimer() {
        const timer = get().flashTimer;
        if (timer) clearTimeout(timer);
    }

    function clearDiceTimer() {
        const timer = get().diceTimer;
        if (timer) clearTimeout(timer);
        set({ diceTimer: null });
    }

    function clearCelebrationTimer() {
        const timer = get().celebrationTimer;
        if (timer) clearTimeout(timer);
    }

    function clearScorePulseTimer() {
        const timer = get().scorePulseTimer;
        if (timer) clearTimeout(timer);
        set({ scorePulseTimer: null });
    }

    function pulseScore(team: "HOME" | "AWAY") {
        clearScorePulseTimer();
        set({ scorePulseTeam: team });
        const timer = setTimeout(() => {
            set({ scorePulseTeam: null, scorePulseTimer: null });
        }, 900);
        set({ scorePulseTimer: timer });
    }

    function clearAbilityBannerTimer() {
        const timer = get().abilityBannerTimer;
        if (timer) clearTimeout(timer);
        set({ abilityBannerTimer: null });
    }

    /** True until the ability-driven action finishes — then we linger 4s. */
    let abilityBannerAwaitingAction = false;

    function showAbilityBanner(ability: PlayerAbility, team: "HOME" | "AWAY") {
        clearAbilityBannerTimer();
        abilityBannerAwaitingAction = true;
        set({
            abilityBanner: {
                ability,
                label: playerAbilityLabel(ability),
                team,
            },
        });
    }

    function scheduleAbilityBannerLinger() {
        if (!abilityBannerAwaitingAction) return;
        abilityBannerAwaitingAction = false;
        clearAbilityBannerTimer();
        const abilityBannerTimer = setTimeout(() => {
            set({ abilityBanner: null, abilityBannerTimer: null });
        }, 4000);
        set({ abilityBannerTimer });
    }

    function clearMotionTimers() {
        const motionTimer = get().motionTimer;
        const ballMotionTimer = get().ballMotionTimer;
        if (motionTimer) clearTimeout(motionTimer);
        if (ballMotionTimer) clearTimeout(ballMotionTimer);
        set({ motionTimer: null, ballMotionTimer: null });
    }

    function clearExitTimer() {
        const timer = get().exitTimer;
        if (timer) clearTimeout(timer);
        set({ exitTimer: null });
    }

    function clearHopTimer() {
        const timer = get().hopTimer;
        if (timer) clearTimeout(timer);
        set({ hopTimer: null });
    }

    function startPieceMotion(motion: PieceMotion) {
        const prior = get().motionTimer;
        if (prior) clearTimeout(prior);
        set({ pieceMotion: motion, motionTimer: null });
        const motionTimer = setTimeout(() => set({ pieceMotion: null, motionTimer: null }), motion.durationMs);
        set({ motionTimer });
    }

    function startBallMotion(motion: BallMotion) {
        const prior = get().ballMotionTimer;
        if (prior) clearTimeout(prior);
        set({ ballMotion: motion, ballMotionTimer: null });
        const ballMotionTimer = setTimeout(() => set({ ballMotion: null, ballMotionTimer: null }), motion.durationMs);
        set({ ballMotionTimer });
    }

    function startReservesExit(exit: ReservesExit, motionOff: boolean): Promise<void> {
        clearExitTimer();
        if (motionOff) {
            set({ reservesExit: null });
            return Promise.resolve();
        }
        set({ reservesExit: exit });
        return new Promise((resolve) => {
            const exitTimer = setTimeout(() => {
                set({ reservesExit: null, exitTimer: null });
                resolve();
            }, exit.durationMs);
            set({ exitTimer });
        });
    }

    function playBallHop(
        from: Coordinate,
        to: Coordinate,
        lingerOnOccupant: boolean,
        motionOff: boolean,
        fromOccupant: boolean,
    ): Promise<void> {
        clearHopTimer();
        const prior = get().ballMotionTimer;
        if (prior) clearTimeout(prior);
        if (motionOff || sameCoord(from, to)) {
            return Promise.resolve();
        }
        const durationMs = BOUNCE_HOP_MS;
        const radiusFrom = fromOccupant ? CARRIER_BALL_RADIUS : LOOSE_BALL_RADIUS;
        const radiusTo = lingerOnOccupant ? CARRIER_BALL_RADIUS : LOOSE_BALL_RADIUS;
        set({
            ballMotion: {
                path: [from, to],
                durationMs,
                flight: "bounce",
                radiusFrom,
                radiusTo,
            },
            ballMotionTimer: null,
        });
        return new Promise((resolve) => {
            const hopTimer = setTimeout(() => {
                // Keep `[from, to]` so Play can still suppress the origin carrier badge while
                // the board is pre-fold; durationMs 0 parks the glyph at `to` (usePathMotion).
                set({
                    ballMotion: {
                        path: [from, to],
                        durationMs: 0,
                        flight: "bounce",
                        radiusFrom: radiusTo,
                        radiusTo,
                    },
                    hopTimer: null,
                });
                const linger = lingerOnOccupant ? BOUNCE_LINGER_MS : 60;
                const lingerTimer = setTimeout(() => {
                    // Stay parked at `to` until foldFrame clears — avoids a one-frame snap back to `from`.
                    set({ hopTimer: null });
                    resolve();
                }, linger);
                set({ hopTimer: lingerTimer });
            }, durationMs);
            set({ hopTimer });
        });
    }

    function advanceTheatreQueue() {
        const current = get().diceTheatre;
        if (current == null) {
            notifyDiceIdle();
            return;
        }
        if (current.index + 1 < current.queue.length) {
            const phase = get().skipDiceAnimations ? "reveal" : "rolling";
            set({
                diceTheatre: { queue: current.queue, index: current.index + 1, phase },
                diceTimer: null,
            });
            scheduleDiceAdvance();
            return;
        }
        set({ diceTheatre: null, diceTimer: null });
        notifyDiceIdle();
    }

    function finishRevealedBeat() {
        const current = get().diceTheatre;
        if (current == null) {
            notifyDiceIdle();
            return;
        }
        clearDiceTimer();
        advanceTheatreQueue();
    }

    function scheduleDiceAdvance() {
        clearDiceTimer();
        const theatre = get().diceTheatre;
        if (theatre == null) return;

        if (theatre.phase === "rolling") {
            const diceTimer = setTimeout(() => {
                const current = get().diceTheatre;
                if (current == null) return;
                set({ diceTheatre: { ...current, phase: "reveal" }, diceTimer: null });
                scheduleDiceAdvance();
            }, 750);
            set({ diceTimer });
            return;
        }

        const beat = theatre.queue[theatre.index];
        const skipAnim = get().skipDiceAnimations;
        const holdMs = skipAnim
            ? beat?.kind === "block"
                ? 700
                : 500
            : beat?.kind === "block"
              ? 1600
              : beat?.purpose === "BOUNCE"
                ? 900
                : 1300;
        const diceTimer = setTimeout(() => {
            finishRevealedBeat();
        }, holdMs);
        set({ diceTimer });
    }

    function runDiceBeats(beats: DiceBeat[], motionOff: boolean): Promise<void> {
        if (beats.length === 0) return Promise.resolve();
        const summary = beats.map(beatSummary).join(" → ");
        set({ lastDiceSummary: summary, lastActionAnchor: beats[beats.length - 1]!.anchor });
        if (motionOff) return Promise.resolve();
        const phase = get().skipDiceAnimations ? "reveal" : "rolling";
        return new Promise((resolve) => {
            diceIdleWaiters.push(resolve);
            set({ diceTheatre: { queue: beats, index: 0, phase } });
            scheduleDiceAdvance();
        });
    }

    function runFlash(coords: Coordinate[], motionOff: boolean) {
        if (coords.length === 0) return;
        clearFlashTimer();
        set({ flashSquares: coords });
        const flashTimer = setTimeout(() => set({ flashSquares: [], flashTimer: null }), motionOff ? 200 : 900);
        set({ flashTimer });
    }

    /** Apply board + HUD after this frame's FX. Advances view.lastEventId. */
    function foldFrame(frame: StreamFrame) {
        const state = get();
        if (state.view == null || state.matchId == null) return;
        if (frame.id !== state.view.lastEventId + 1 && state.view.lastEventId > 0) {
            void reconnectFromSnapshot();
            return;
        }

        const nextView = applyStreamEvent(state.view, frame.id, frame.event);
        const localCoach = localCoachFor(nextView, state.preferredLocalCoach);
        const previousPhase = state.connectionPhase;
        const connectionPhase = connectionPhaseAfterEvent(
            nextView,
            frame.event,
            previousPhase,
            state.preferredLocalCoach,
        );

        set({
            view: nextView,
            localCoach,
            connectionPhase,
            eventLog: appendEventLog(state.eventLog, frame.id, frame.event),
        });

        const kind = (frame.event as { kind?: string }).kind;
        if (kind === "challengeCardClaimed" || kind === "cleanSweep") {
            const team = (frame.event as { team?: "HOME" | "AWAY" }).team;
            if (team === "HOME" || team === "AWAY") pulseScore(team);
        }

        // Bounce hop parks the in-flight glyph at `to` until this fold lands the board there.
        if (frame.event.kind === "ballBounced" && get().ballMotion != null) {
            set({ ballMotion: null });
        }
        if (frame.event.kind === "actionStarted") {
            if (frame.event.playerAt != null) {
                set({ lastActionAnchor: frame.event.playerAt });
            }
            if (frame.event.actionType === "BLOCK") {
                const attackerId = frame.event.playerId;
                let defenderId = resolveBlockDefender(nextView, attackerId, get().pendingSubmittedAction);
                if (defenderId == null) {
                    defenderId = peekBlockDefenderFromQueue(nextView, attackerId);
                }
                if (defenderId != null) {
                    set({ activeBlock: { attackerId, defenderId }, pendingBlockAttackerId: null });
                } else if (get().activeBlock == null) {
                    set({ pendingBlockAttackerId: attackerId });
                }
            }
        } else if (frame.event.kind === "actionComplete" || frame.event.kind === "phaseStarted") {
            if (get().diceTheatre == null) {
                set({ activeBlock: null, pendingBlockAttackerId: null });
            }
            if (frame.event.kind === "actionComplete") {
                scheduleAbilityBannerLinger();
            }
        }

        if (isPlayerActionRequired(frame.event)) {
            const ability = abilityFromEvent(frame.event);
            if (ability != null) {
                showAbilityBanner(ability, frame.event.team);
            }
        }

        // Late fill: unique-adjacent failed, but a consequence names the victim.
        const pendingAttackerId = get().pendingBlockAttackerId;
        if (
            pendingAttackerId != null &&
            get().activeBlock == null &&
            (frame.event.kind === "playerStatusChanged" ||
                frame.event.kind === "playerInjured" ||
                frame.event.kind === "playerMoved")
        ) {
            const defenderId = inferBlockDefenderFromConsequence(nextView, pendingAttackerId, frame.event.playerId);
            if (defenderId != null) {
                set({ activeBlock: { attackerId: pendingAttackerId, defenderId }, pendingBlockAttackerId: null });
            }
        }

        const matchId = state.matchId;
        if (connectionPhase === "awaitingInput" && previousPhase !== "awaitingInput") {
            const forTeam = isPlayerActionRequired(frame.event) ? frame.event.team : nextView.gameState.activeCoach;
            const pendingDecisionType = isPlayerActionRequired(frame.event)
                ? decisionTypeFromEvent(frame.event)
                : get().pendingDecisionType;
            const pendingAbility = isPlayerActionRequired(frame.event)
                ? abilityFromEvent(frame.event)
                : get().pendingAbility;
            set({
                legalActionsLoading: true,
                legalActions: [],
                pendingSubmittedAction: null,
                pendingBlockAttackerId: null,
                pendingDecisionType,
                pendingAbility,
            });
            void fetchLegalActionsForView(matchId, nextView, forTeam)
                .then(async (legalActions) => {
                    if (get().matchId !== matchId) return;
                    const bonusHand = await fetchBonusHandForView(matchId, nextView, get().preferredLocalCoach).catch(
                        () => get().bonusHand,
                    );
                    if (get().matchId !== matchId) return;
                    set({ legalActions, legalActionsLoading: false, bonusHand });
                })
                .catch((error) => {
                    if (get().matchId !== matchId) return;
                    const message =
                        error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Failed to load legal actions";
                    set({ actionError: message, legalActionsLoading: false, legalActions: [] });
                });
        } else if (connectionPhase !== "awaitingInput") {
            if (get().legalActions.length > 0 || get().legalActionsLoading || get().pendingDecisionType != null) {
                set({ legalActions: [], legalActionsLoading: false, pendingDecisionType: null, pendingAbility: null });
            }
            // Refresh hand after claims even while spectating (CPU turn / after own play).
            void fetchBonusHandForView(matchId, nextView, get().preferredLocalCoach)
                .then((bonusHand) => {
                    if (get().matchId !== matchId) return;
                    set({ bonusHand });
                })
                .catch(() => {
                    /* keep prior hand */
                });
        }
    }

    /** FX for one frame, using the still-previous board (pre-fold). */
    async function animateFrame(frame: StreamFrame, motionOff: boolean) {
        const event = frame.event;
        const view = get().view;
        if (view == null) return;
        const submitted = get().pendingSubmittedAction;
        const ctx = `${event.kind}#${frame.id}`;

        switch (event.kind) {
            case "playerMoved": {
                const step = pieceMotionFromEvents([event]);
                if (step == null) return;
                assertMotionOriginForEvent(view.pieces, event, ctx);
                if (motionOff) return;
                startPieceMotion(step);
                await sleep(step.durationMs);
                set({ pieceMotion: null });
                return;
            }
            case "diceRolled": {
                tryResolvePendingBlock();
                const beats = buildDiceBeats([event], submitted ?? undefined, get().lastActionAnchor);
                await runDiceBeats(beats, motionOff);
                return;
            }
            case "ballBounced": {
                if (sameCoord(event.from, event.to)) return;
                assertMotionOriginForEvent(view.pieces, event, ctx);
                const linger = playerOccupies(view, event.to);
                const fromOccupant = playerOccupies(view, event.from);
                await playBallHop(event.from, event.to, linger, motionOff, fromOccupant);
                return;
            }
            case "ballThrown":
            case "ballThrowMissed": {
                const ballMotion = ballMotionFromEvents([event]);
                if (ballMotion == null) return;
                assertMotionOriginForEvent(view.pieces, event, ctx);
                if (motionOff) return;
                startBallMotion(ballMotion);
                await sleep(ballMotion.durationMs);
                set({ ballMotion: null });
                return;
            }
            case "ballDropped":
            case "ballPlacedLoose": {
                const at = event.kind === "ballDropped" ? event.playerAt : event.at;
                runFlash([at], motionOff);
                await sleep(motionOff ? 40 : 160);
                return;
            }
            case "touchdownScored": {
                const touchdown = touchdownCelebrationFromEvents([event]);
                if (touchdown == null) return;
                assertMotionOriginForEvent(view.pieces, event, ctx);
                clearCelebrationTimer();
                set({ celebration: touchdown });
                await sleep(motionOff ? 500 : TOUCHDOWN_CELEBRATION_MS);
                set({ celebration: null, celebrationTimer: null });
                return;
            }
            case "playerInjured": {
                const playWidth = get().pitch?.playWidth ?? 11;
                const team = view.pieces.players.find((piece) => piece.id === event.playerId)?.team ?? "HOME";
                assertMotionOriginForEvent(view.pieces, event, ctx);
                const injuryExit = injuryReservesExit([event], new Map([[event.playerId, team]]), playWidth);
                if (injuryExit != null) await startReservesExit(injuryExit, motionOff);
                return;
            }
            case "playerRemovedAfterTouchdown": {
                const playWidth = get().pitch?.playWidth ?? 11;
                assertMotionOriginForEvent(view.pieces, event, ctx);
                const piece = view.pieces.players.find((entry) => entry.id === event.playerId)!;
                const team = piece.team;
                await startReservesExit(
                    {
                        playerId: event.playerId,
                        team,
                        reason: "touchdown",
                        path: reservesExitPath(event.from, team, playWidth),
                        durationMs: RESERVES_EXIT_MS,
                    },
                    motionOff,
                );
                return;
            }
            case "playerDeployedFromReserves": {
                runFlash([event.to], motionOff);
                await sleep(motionOff ? 80 : 280);
                return;
            }
            case "playerStatusChanged": {
                runFlash(flashCoordsFromEvents([event], submitted ?? undefined), motionOff);
                await sleep(motionOff ? 40 : 120);
                return;
            }
            default: {
                const kind = (event as { kind?: string }).kind;
                if (kind === "challengeCardClaimed") {
                    const claim = challengeClaimCelebrationFromEvent(
                        event,
                        view.gameState.homeScore,
                        view.gameState.awayScore,
                    );
                    if (claim == null) return;
                    clearCelebrationTimer();
                    set({ celebration: claim });
                    await sleep(motionOff ? 500 : CHALLENGE_CELEBRATION_MS);
                    set({ celebration: null, celebrationTimer: null });
                    return;
                }
                if (kind === "cleanSweep") {
                    const sweep = cleanSweepCelebrationFromEvent(
                        event,
                        view.gameState.homeScore,
                        view.gameState.awayScore,
                    );
                    if (sweep == null) return;
                    clearCelebrationTimer();
                    set({ celebration: sweep });
                    await sleep(motionOff ? 500 : CHALLENGE_CELEBRATION_MS);
                    set({ celebration: null, celebrationTimer: null });
                    return;
                }
                return;
            }
        }
    }

    function scheduleFrame(frame: StreamFrame) {
        const mode = presentationModeFor(frame.event);
        const motionOff = reducedMotionPreferred();

        if (mode === "skip") {
            enqueuePresentation({
                mode: "block",
                event: frame.event,
                start: async () => {
                    foldFrame(frame);
                },
            });
            return;
        }

        if (mode === "parallel") {
            enqueuePresentation({
                mode: "parallel",
                event: frame.event,
                start: () => {
                    void animateFrame(frame, motionOff);
                    foldFrame(frame);
                },
            });
            return;
        }

        enqueuePresentation({
            mode: "block",
            event: frame.event,
            start: async () => {
                await animateFrame(frame, motionOff);
                foldFrame(frame);
            },
        });
    }

    async function acceptStreamFrame(frame: StreamFrame) {
        const state = get();
        if (state.view == null || state.matchId == null) return;

        if (frame.id !== acceptedStreamId + 1 && acceptedStreamId > 0) {
            await reconnectFromSnapshot();
            return;
        }
        acceptedStreamId = frame.id;
        scheduleFrame(frame);
        tryResolvePendingBlock();
    }

    async function resubscribe(matchId: MatchId, lastEventId: number): Promise<void> {
        closeSubscription();
        acceptedStreamId = lastEventId;
        await new Promise<void>((resolve, reject) => {
            let settled = false;
            const settleOk = () => {
                if (settled) return;
                settled = true;
                resolve();
            };
            const settleErr = (error: unknown) => {
                if (settled) return;
                settled = true;
                reject(error instanceof Error ? error : new Error("SSE subscribe failed"));
            };
            const sub = subscribeMatchEvents(matchId, lastEventId, {
                onOpen: () => {
                    const state = get();
                    if (state.connectionPhase === "connecting" || state.connectionPhase === "hydrating") {
                        set({ connectionPhase: "spectating" });
                    }
                    settleOk();
                },
                onEvent: (frame) => enqueueStreamFrame(frame),
                onError: (error) => {
                    if (!settled) {
                        settleErr(error);
                        return;
                    }
                    void reconnectFromSnapshot();
                },
            });
            // If leave/join aborts before onOpen, settle so hydrate cannot hang forever.
            subscription = {
                close: () => {
                    sub.close();
                    settleErr(new DOMException("SSE subscription closed", "AbortError"));
                },
            };
            set({ subscription });
        });
    }

    /**
     * Hydrate/reconnect only — reconstruct a missed InputRequired.PlayerAction by probing
     * legal-actions. Live play waits for SSE `playerActionRequired` instead.
     */
    async function probeHumanPlayerActions(matchId: MatchId, view: BoardView) {
        const preferred = get().preferredLocalCoach;
        const toAct = view.gameState.activeCoach;
        if (isMatchOver(view) || !thisBrowserActsFor(view.gameState, toAct, preferred)) {
            set({ connectionPhase: "spectating", legalActions: [], legalActionsLoading: false, pendingDecisionType: null, pendingAbility: null });
            return;
        }

        set({ legalActionsLoading: true, connectionPhase: "awaitingInput" });
        try {
            const [legalActions, bonusHand] = await Promise.all([
                fetchLegalActionsForView(matchId, view),
                fetchBonusHandForView(matchId, view, preferred).catch(() => null),
            ]);
            set({
                legalActions,
                bonusHand,
                legalActionsLoading: false,
                // Missed the InputRequired cue — decision type unknown until the next event.
                pendingDecisionType: null, pendingAbility: null,
                connectionPhase: legalActions.length > 0 ? "awaitingInput" : "spectating",
            });
        } catch (error) {
            const message =
                error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Failed to load legal actions";
            set({
                actionError: message,
                legalActionsLoading: false,
                legalActions: [],
                pendingDecisionType: null, pendingAbility: null,
                connectionPhase: "spectating",
            });
        }
    }

    async function reconnectFromSnapshot() {
        const { matchId, reconnecting } = get();
        if (matchId == null || reconnecting) return;
        set({ reconnecting: true, connectionPhase: "hydrating", legalActions: [], legalActionsLoading: false });
        clearPresentationQueue();
        resetStreamChain();
        try {
            const snapshot = await getSnapshot(matchId);
            const view = boardViewFromSnapshot(snapshot);
            const localCoach = localCoachFor(view, get().preferredLocalCoach);
            acceptedStreamId = view.lastEventId;
            set({ view, localCoach, connectionPhase: "spectating", legalActions: [] });
            await resubscribe(matchId, view.lastEventId);
            await probeHumanPlayerActions(matchId, view);
        } catch (error) {
            const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Reconnect failed";
            const unavailable = error instanceof ApiError && error.status === 404;
            set({
                actionError: message,
                legalActionsLoading: false,
                connectionPhase: unavailable ? "unavailable" : "spectating",
                ...(unavailable ? { pitch: null, roster: null, view: null, legalActions: [], pendingDecisionType: null, pendingAbility: null } : {}),
            });
        } finally {
            set({ reconnecting: false });
        }
    }

    async function hydrateMatch(matchId: MatchId) {
        set({
            connectionPhase: "hydrating",
            matchId,
            actionError: null,
            legalActions: [],
            legalActionsLoading: false,
            bonusHand: null,
            pendingDecisionType: null, pendingAbility: null,
            abilityBanner: null,
            eventLog: [],
            lastDiceSummary: null,
            diceTheatre: null,
            activeBlock: null,
            pieceMotion: null,
            ballMotion: null,
            reservesExit: null,
            flashSquares: [],
            celebration: null,
            scorePulseTeam: null,
            lastActionAnchor: null,
            pendingSubmittedAction: null,
            pendingBlockAttackerId: null,
        });
        try {
            const [pitch, roster, snapshot] = await Promise.all([getPitch(matchId), getRoster(matchId), getSnapshot(matchId)]);
            const view = boardViewFromSnapshot(snapshot);
            const preferred = get().preferredLocalCoach;
            const localCoach = localCoachFor(view, preferred);
            acceptedStreamId = view.lastEventId;
            // Show the pitch immediately; wait for SSE open only before /start so kickoff
            // events are not missed (backend flushes a connected comment on subscribe).
            set({
                pitch,
                roster,
                view,
                localCoach,
                connectionPhase: "spectating",
                eventLog: [{ id: null, text: "Joined match — waiting for the next action." }],
            });
            await resubscribe(matchId, view.lastEventId);

            if (get().matchId !== matchId) return;

            if (isAwaitingKickoff(view)) {
                await startMatch(matchId);
                // SSE folds kickoff events (including playerActionRequired) — same as submit.
            } else {
                await probeHumanPlayerActions(matchId, view);
            }
        } catch (error) {
            if (get().matchId !== matchId) return;
            if (error instanceof DOMException && error.name === "AbortError") return;
            const message =
                error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Failed to join match";
            // No board to show — Play navigates home on `unavailable` (e.g. unknown match id).
            set({
                actionError: message,
                connectionPhase: "unavailable",
                pitch: null,
                roster: null,
                view: null,
                legalActions: [],
                legalActionsLoading: false,
                bonusHand: null,
                pendingDecisionType: null, pendingAbility: null,
            });
        }
    }

    function resetPresentationState() {
        clearPresentationQueue();
        resetStreamChain();
        clearFlashTimer();
        clearDiceTimer();
        clearCelebrationTimer();
        clearScorePulseTimer();
        clearAbilityBannerTimer();
        abilityBannerAwaitingAction = false;
        clearMotionTimers();
        clearExitTimer();
        clearHopTimer();
        acceptedStreamId = 0;
    }

    return {
        matchId: null,
        connectionPhase: "connecting",
        pitch: null,
        roster: null,
        view: null,
        localCoach: "HOME",
        preferredLocalCoach: null,
        legalActions: [],
        legalActionsLoading: false,
        bonusHand: null,
        pendingDecisionType: null, pendingAbility: null,
        abilityBanner: null,
        actionError: null,
        eventLog: [],
        lastDiceSummary: null,
        diceTheatre: null,
        skipDiceAnimations: readSkipDiceAnimations(),
        activeBlock: null,
        pieceMotion: null,
        ballMotion: null,
        reservesExit: null,
        flashSquares: [],
        celebration: null,
        scorePulseTeam: null,
        flashTimer: null,
        diceTimer: null,
        celebrationTimer: null,
        scorePulseTimer: null,
        abilityBannerTimer: null,
        motionTimer: null,
        ballMotionTimer: null,
        exitTimer: null,
        hopTimer: null,
        lastActionAnchor: null,
        pendingSubmittedAction: null,
        pendingBlockAttackerId: null,
        subscription: null,
        reconnecting: false,

        joinMatch: (matchId) => {
            closeSubscription();
            resetPresentationState();
            const preferredLocalCoach = readLocalCoachFromUrl();
            set({
                matchId: null,
                pitch: null,
                roster: null,
                view: null,
                preferredLocalCoach,
                localCoach: preferredLocalCoach ?? "HOME",
                legalActions: [],
                legalActionsLoading: false,
                bonusHand: null,
                pendingDecisionType: null, pendingAbility: null,
                abilityBanner: null,
                actionError: null,
                eventLog: [],
                lastDiceSummary: null,
                diceTheatre: null,
                activeBlock: null,
                pieceMotion: null,
                ballMotion: null,
                reservesExit: null,
                flashSquares: [],
                celebration: null,
                scorePulseTeam: null,
                lastActionAnchor: null,
                pendingSubmittedAction: null,
                pendingBlockAttackerId: null,
                connectionPhase: "connecting",
            });
            void hydrateMatch(matchId);
        },

        leaveMatch: () => {
            closeSubscription();
            resetPresentationState();
            set({
                matchId: null,
                pitch: null,
                roster: null,
                view: null,
                preferredLocalCoach: null,
                localCoach: "HOME",
                legalActions: [],
                legalActionsLoading: false,
                bonusHand: null,
                pendingDecisionType: null, pendingAbility: null,
                abilityBanner: null,
                actionError: null,
                eventLog: [],
                lastDiceSummary: null,
                diceTheatre: null,
                activeBlock: null,
                pieceMotion: null,
                ballMotion: null,
                reservesExit: null,
                flashSquares: [],
                celebration: null,
                scorePulseTeam: null,
                lastActionAnchor: null,
                pendingSubmittedAction: null,
                pendingBlockAttackerId: null,
                connectionPhase: "connecting",
                subscription: null,
            });
        },

        clearActionError: () => set({ actionError: null }),

        setSkipDiceAnimations: (skip) => {
            writeSkipDiceAnimations(skip);
            set({ skipDiceAnimations: skip });
            const theatre = get().diceTheatre;
            if (skip && theatre != null && theatre.phase === "rolling") {
                clearDiceTimer();
                set({ diceTheatre: { ...theatre, phase: "reveal" }, diceTimer: null });
                scheduleDiceAdvance();
            }
        },

        dismissDiceTheatre: () => {
            const theatre = get().diceTheatre;
            if (theatre == null) return;
            clearDiceTimer();
            if (theatre.phase === "rolling") {
                set({ diceTheatre: { ...theatre, phase: "reveal" }, diceTimer: null });
                scheduleDiceAdvance();
                return;
            }
            finishRevealedBeat();
        },

        submitLegalAction: async (action) => {
            const { matchId, view, connectionPhase } = get();
            if (matchId == null || view == null || connectionPhase !== "awaitingInput") return;

            const blockContest =
                action.kind === "block"
                    ? (() => {
                          const defenderId = resolveBlockDefender(view, action.playerId, action);
                          return defenderId != null ? { attackerId: action.playerId, defenderId } : null;
                      })()
                    : null;
            set({
                actionError: null,
                connectionPhase: "spectating",
                legalActionsLoading: true,
                legalActions: [],
                pendingDecisionType: null, pendingAbility: null,
                pendingSubmittedAction: action,
                activeBlock: blockContest,
                pendingBlockAttackerId: null,
            });
            try {
                await dispatchLegalAction(matchId, action);
            } catch (error) {
                const message =
                    error instanceof ApiError ? error.message : error instanceof Error ? error.message : "Action failed";
                set({
                    actionError: message,
                    connectionPhase: "awaitingInput",
                    pendingSubmittedAction: null,
                    pendingBlockAttackerId: null,
                    activeBlock: null,
                });
                try {
                    set({ legalActionsLoading: true });
                    const legalActions = await fetchLegalActionsForView(matchId, view);
                    set({ legalActions, legalActionsLoading: false });
                } catch {
                    set({ legalActionsLoading: false });
                }
            }
        },
    };
});

export function isAwaitingInput(state: Pick<MatchStoreState, "connectionPhase">): boolean {
    return state.connectionPhase === "awaitingInput";
}
