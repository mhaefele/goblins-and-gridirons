import type { BlockDieFace, Coordinate, DomainEvent, LegalActionWire, PieceSnapshotDto, PlayerId } from "../api/client";

export type DiceBeatKind = "block" | "d6" | "d8";

export type ShownDie = {
    /** Face after the tumble lands. */
    result: string;
    /** Labels cycled while rolling. */
    tumble: string[];
};

export type BallFollow = {
    from: Coordinate;
    to: Coordinate;
    durationMs: number;
    /** Re-bounce off a player — park the ball on them until the next bounce die. */
    lingerOnOccupant: boolean;
};

export type DiceBeat = {
    purpose: string;
    /** Giant shout after the roll, e.g. FLATTEN! */
    outcome: string;
    detail: string;
    kind: DiceBeatKind;
    /** Pitch square the dice float beside. */
    anchor: Coordinate;
    dice: ShownDie[];
    /** Bounce: move the ball after this die reveals (die first, then flight). */
    followBall?: BallFollow;
};

export type BallPresentation = {
    at: Coordinate;
    /** True while the ball is on a player waiting for another bounce. */
    onOccupant: boolean;
};

/** Keep an injured player painted at [at] until the injury exit job runs. */
export type InjuryHold = {
    playerId: PlayerId;
    at: Coordinate;
    team: "HOME" | "AWAY";
};

/** Keep a mover painted at [at] until their slide job runs (board already folded to the tip). */
export type PieceHold = {
    playerId: PlayerId;
    at: Coordinate;
};

/** Folded `piece.at` vs presentation hold — prefer hold so shove/run doesn't flash ahead. */
export function displayPieceAt(foldedAt: Coordinate | null | undefined, hold: PieceHold | null, playerId: PlayerId): Coordinate | null {
    if (hold != null && hold.playerId === playerId) return hold.at;
    return foldedAt ?? null;
}

export const BOUNCE_HOP_MS = 480;
export const BOUNCE_LINGER_MS = 420;

/**
 * Loose-ball glyph while presentation overrides the folded board.
 * Prefer presentation so we never flash the folded landing square ahead of a hop/throw.
 */
export function displayLooseBallLocations(
    foldedLoose: Coordinate[],
    presentation: BallPresentation | null,
): Coordinate[] {
    if (presentation == null) return foldedLoose;
    if (presentation.onOccupant) return [];
    return [presentation.at];
}

/**
 * Bounce-die origin pin must not replace an in-flight throw/hop pin.
 * Later SSE frames often fold the ball to the throw destination before the throw job runs.
 */
export function mayReplaceBallPresentation(current: BallPresentation | null): boolean {
    return current == null;
}

export function ballPresentationAt(at: Coordinate, onOccupant: boolean): BallPresentation {
    return { at, onOccupant };
}

/** Armour check must be recorded before injury when both are in a batch (backend contract). */
export function armourPrecedesInjury(events: DomainEvent[]): boolean {
    const armour = events.findIndex((event) => event.kind === "diceRolled" && event.purpose === "ARMOUR");
    const injury = events.findIndex((event) => event.kind === "playerInjured");
    if (injury < 0 || armour < 0) return true;
    return armour < injury;
}

/**
 * How a domain event participates in the client presentation queue.
 * - `skip` — no FX; next job may start immediately
 * - `block` — FX must finish before the next blocking job
 * - `parallel` — start FX and continue the queue (animate without holding)
 */
export type PresentationMode = "skip" | "block" | "parallel";

/** Classify stream events for the presentation queue (board fold stays immediate). */
export function presentationModeFor(event: DomainEvent): PresentationMode {
    switch (event.kind) {
        case "playerMoved":
        case "diceRolled":
        case "ballBounced":
        case "ballThrown":
        case "ballThrowMissed":
        case "playerInjured":
        case "playerRemovedAfterTouchdown":
        case "touchdownScored":
        case "playerDeployedFromReserves":
            return "block";
        case "playerStatusChanged":
        case "ballPickedUp":
        case "ballDropped":
        case "ballPlacedLoose":
            return "parallel";
        default: {
            // Challenge events until OpenAPI regen.
            const kind = (event as { kind?: string }).kind;
            if (kind === "challengeCardClaimed" || kind === "cleanSweep") return "block";
            return "skip";
        }
    }
}

export type DiceTheatreState = {
    queue: DiceBeat[];
    index: number;
    phase: "rolling" | "reveal";
};

export type PieceMotion = {
    playerId: PlayerId;
    path: Coordinate[];
    /** Total animation length in ms. */
    durationMs: number;
};

export type BallMotion = {
    path: Coordinate[];
    durationMs: number;
    /** Throws arc; bounce hops get a short hop; straight is reserved for rare linear moves. */
    flight: "straight" | "arc" | "bounce";
    /** Pitch-unit glyph radius at hop start (carrier badge is smaller than loose). */
    radiusFrom?: number;
    /** Pitch-unit glyph radius at hop end. */
    radiusTo?: number;
};

/** Loose-ball glyph size on an empty square. */
export const LOOSE_BALL_RADIUS = 0.3;
/** Carrier-badge ball size — bounce grows from this when leaving a player. */
export const CARRIER_BALL_RADIUS = 0.22;

export type TouchdownCelebration = {
    kind: "touchdown";
    playerId: PlayerId;
    team: "HOME" | "AWAY";
    at: Coordinate;
    homeScore: number;
    awayScore: number;
};

/** Challenge claimed into the bonus hand — overlay shout + score beat. */
export type ChallengeClaimCelebration = {
    kind: "challengeClaim";
    team: "HOME" | "AWAY";
    slotIndex: number;
    challengeCondition: string;
    title: string;
    pointsAwarded: number;
    /** Score before this claim (for the HUD tick animation). */
    fromHomeScore: number;
    fromAwayScore: number;
    homeScore: number;
    awayScore: number;
};

/** Claimed all three actives in one turn. */
export type CleanSweepCelebration = {
    kind: "cleanSweep";
    team: "HOME" | "AWAY";
    pointsAwarded: number;
    fromHomeScore: number;
    fromAwayScore: number;
    homeScore: number;
    awayScore: number;
};

export type ScoreCelebration = TouchdownCelebration | ChallengeClaimCelebration | CleanSweepCelebration;

/** Hold length so the scorer can pulse on the square through the shout. */
export const TOUCHDOWN_CELEBRATION_MS = 3200;

/** Challenge claim / clean-sweep overlay linger. */
export const CHALLENGE_CELEBRATION_MS = 2800;

export type ReservesExit = {
    playerId: PlayerId;
    team: "HOME" | "AWAY";
    reason: "injury" | "touchdown";
    path: Coordinate[];
    durationMs: number;
};

export const RESERVES_EXIT_MS = 900;

/** Keyed by the generated `BlockDieFace` so a backend rename fails typecheck here instead of drifting. */
const BLOCK_DIE_FACE_KEYS: Record<BlockDieFace, true> = { SMASH: true, FLATTEN: true, SHOVE: true, TACKLE: true, MISS: true };
export const BLOCK_FACES = Object.keys(BLOCK_DIE_FACE_KEYS) as BlockDieFace[];

export function isBlockDieFace(face: string): face is BlockDieFace {
    return face in BLOCK_DIE_FACE_KEYS;
}
const D6_FACES = ["1", "2", "3", "4", "5", "6"] as const;
const D8_FACES = ["1", "2", "3", "4", "5", "6", "7", "8"] as const;

export function formatDiceFaces(event: Extract<DomainEvent, { kind: "diceRolled" }>): string {
    if (event.purpose === "FOUL" && event.foulResult != null) return event.foulResult;
    if (event.blockResult != null) return event.blockResult;
    if (event.blockFaces != null) return event.blockFaces.join(", ");
    if (event.d8 != null) return `D8=${event.d8}`;
    if (event.d6 != null) return `D6=${event.d6}`;
    return "?";
}

export function outcomeShout(event: Extract<DomainEvent, { kind: "diceRolled" }>): string {
    if (event.purpose === "FOUL" && event.foulResult != null) {
        switch (event.foulResult) {
            case "SPOTTED":
                return "SPOTTED!";
            case "TAKE_THAT":
                return "TAKE THAT!";
            case "SLIPPED":
                return "SLIPPED!";
            case "GOT_THEM":
                return "GOT THEM!";
        }
    }
    if (event.blockResult != null) return `${event.blockResult}!`;
    if (event.blockFaces != null && event.blockFaces.length > 0) return `${event.blockFaces[0]!}!`;

    switch (event.purpose) {
        case "THROW":
            if (event.checkResult === "FUMBLE") return "FUMBLE!";
            if (event.checkResult === "PASS") return "CATCH!";
            if (event.checkResult === "FAIL") return "WIDE!";
            break;
        case "ARMOUR":
            // PASS = armour stopped the injury (not "holding the ball").
            if (event.checkResult === "PASS") return "SAFE!";
            if (event.checkResult === "FAIL") return "BROKEN!";
            break;
        case "BOUNCE":
            if (event.d8 != null) return `BOUNCE ${event.d8}!`;
            return "BOUNCE!";
        case "VIOLENT":
            if (event.d8 === 8) return "INJURY!";
            if (event.d8 != null) return `${event.d8}!`;
            return "VIOLENT!";
        case "MOVE":
            if (event.d6 != null) return `MOVE ${event.d6}!`;
            return "MOVE!";
        default:
            break;
    }
    if (event.checkResult != null) return `${event.checkResult}!`;
    return formatDiceFaces(event);
}

function beatKind(event: Extract<DomainEvent, { kind: "diceRolled" }>): DiceBeatKind {
    if (event.blockFaces != null || event.blockResult != null) return "block";
    if (event.d8 != null) return "d8";
    return "d6";
}

export function diceAnchorFromEvents(
    events: DomainEvent[],
    submitted?: LegalActionWire,
    fallback: Coordinate | null = null,
): Coordinate {
    if (submitted?.kind === "block") return submitted.destination;
    if (submitted?.kind === "throw") return submitted.target.destination;
    if (submitted != null && "path" in submitted) {
        const end = submitted.path.at(-1);
        if (end != null) return end;
    }

    const statusChange = events.find((event) => event.kind === "playerStatusChanged");
    if (statusChange != null) return statusChange.playerAt;

    const bounced = events.find((event) => event.kind === "ballBounced");
    if (bounced != null) return bounced.to;

    const thrown = events.find((event) => event.kind === "ballThrown" || event.kind === "ballThrowMissed");
    if (thrown != null) return thrown.to;

    const started = events.find((event) => event.kind === "actionStarted");
    if (started?.playerAt != null) return started.playerAt;

    const moved = events.find((event) => event.kind === "playerMoved");
    if (moved != null) return moved.to;

    return fallback ?? { x: 6, y: 8 };
}

function shownDiceForEvent(event: Extract<DomainEvent, { kind: "diceRolled" }>): ShownDie[] {
    if (event.blockFaces != null && event.blockFaces.length > 0) {
        return event.blockFaces.map((face) => ({
            result: face,
            tumble: [...BLOCK_FACES],
        }));
    }
    if (event.blockResult != null) {
        return [{ result: event.blockResult, tumble: [...BLOCK_FACES] }];
    }
    if (event.d8 != null) {
        return [{ result: String(event.d8), tumble: [...D8_FACES] }];
    }
    if (event.d6 != null) {
        return [{ result: String(event.d6), tumble: [...D6_FACES] }];
    }
    return [{ result: "?", tumble: [...D6_FACES] }];
}

function nextBallBouncedAfter(
    events: DomainEvent[],
    afterIndex: number,
): Extract<DomainEvent, { kind: "ballBounced" }> | null {
    for (let j = afterIndex + 1; j < events.length; j++) {
        const event = events[j]!;
        if (event.kind === "diceRolled" && event.purpose === "BOUNCE") return null;
        if (event.kind === "ballBounced") return event;
        if (event.kind === "ballPickedUp") return null;
    }
    return null;
}

function bounceLingersOnOccupant(events: DomainEvent[], bouncedIndex: number, landing: Coordinate): boolean {
    for (let j = bouncedIndex + 1; j < events.length; j++) {
        const event = events[j]!;
        if (event.kind === "ballPickedUp") return false;
        if (event.kind === "ballBounced") {
            return event.from.x === landing.x && event.from.y === landing.y;
        }
        if (event.kind === "diceRolled" && event.purpose === "BOUNCE") {
            // Next bounce die after this landing — hop that follows should start here.
            const next = nextBallBouncedAfter(events, j);
            return next != null && next.from.x === landing.x && next.from.y === landing.y;
        }
    }
    return false;
}

/** One presentation beat per diceRolled — block and armour stay separate. */
export function buildDiceBeats(
    events: DomainEvent[],
    submitted?: LegalActionWire,
    fallbackAnchor: Coordinate | null = null,
): DiceBeat[] {
    const actionStarted = events.find((event) => event.kind === "actionStarted");
    const statusChange = events.find((event) => event.kind === "playerStatusChanged");
    const injured = events.find((event) => event.kind === "playerInjured");
    const defaultAnchor = diceAnchorFromEvents(events, submitted, fallbackAnchor);
    const beats: DiceBeat[] = [];

    for (let i = 0; i < events.length; i++) {
        const event = events[i]!;
        if (event.kind !== "diceRolled") continue;

        const outcome = outcomeShout(event);
        const kind = beatKind(event);
        const dice = shownDiceForEvent(event);

        if (kind === "block") {
            const who = actionStarted?.actionType === "BLOCK" ? `${actionStarted.playerId} blocks` : "Block";
            const knockdown = statusChange
                ? `${statusChange.playerId} → ${statusChange.to}`
                : injured
                  ? `${injured.playerId} injured`
                  : null;
            beats.push({
                purpose: event.purpose,
                outcome,
                detail: [who, knockdown].filter(Boolean).join(" — "),
                kind,
                anchor: defaultAnchor,
                dice,
            });
            continue;
        }

        const label =
            event.purpose === "ARMOUR"
                ? "Armour"
                : event.purpose === "THROW"
                  ? "Throw"
                  : event.purpose === "BOUNCE"
                    ? "Bounce"
                    : event.purpose === "VIOLENT"
                      ? "Violent"
                      : event.purpose;
        const check =
            event.checkResult != null && event.threshold != null
                ? `need ${event.threshold}+`
                : event.checkResult != null
                  ? event.checkResult
                  : null;
        const who = actionStarted?.playerId ?? null;

        let anchor = defaultAnchor;
        let followBall: BallFollow | undefined;
        if (event.purpose === "BOUNCE") {
            const bounced = nextBallBouncedAfter(events, i);
            if (bounced != null) {
                anchor = bounced.from;
                const bouncedIndex = events.indexOf(bounced);
                followBall = {
                    from: bounced.from,
                    to: bounced.to,
                    durationMs: BOUNCE_HOP_MS,
                    lingerOnOccupant: bounceLingersOnOccupant(events, bouncedIndex, bounced.to),
                };
            }
        }

        beats.push({
            purpose: event.purpose,
            outcome,
            detail: [label, who, check].filter(Boolean).join(" — "),
            kind,
            anchor,
            dice,
            ...(followBall != null ? { followBall } : {}),
        });
    }

    return beats;
}

/** Per-square walk — kept snappy so multi-step runs don't drag. */
export function motionDurationMs(steps: number): number {
    return Math.min(480, Math.max(90, Math.max(1, steps) * 70));
}

/** Build token motion from `playerMoved` events (one square per event on the stream). */
export function pieceMotionFromEvents(events: DomainEvent[]): PieceMotion | null {
    const moves = events.filter((event): event is Extract<DomainEvent, { kind: "playerMoved" }> => event.kind === "playerMoved");
    if (moves.length === 0) return null;
    const playerId = moves[0]!.playerId;
    const forPlayer = moves.filter((move) => move.playerId === playerId);
    const path = [forPlayer[0]!.from, ...forPlayer.map((move) => move.to)];
    return { playerId, path, durationMs: motionDurationMs(path.length - 1) };
}

export function sameCoord(a: Coordinate, b: Coordinate): boolean {
    return a.x === b.x && a.y === b.y;
}

function formatCoord(at: Coordinate): string {
    return `(${at.x},${at.y})`;
}

/**
 * Fail fast when FX is about to start but the pre-fold board no longer shows the piece at the path origin —
 * usually means a prior frame folded too early or event order drifted.
 */
export function assertPlayerAtMotionOrigin(
    pieces: PieceSnapshotDto,
    playerId: PlayerId,
    origin: Coordinate,
    context: string,
): void {
    const piece = pieces.players.find((entry) => entry.id === playerId);
    if (piece?.at != null && sameCoord(piece.at, origin)) return;
    const where =
        piece == null
            ? "missing from snapshot"
            : piece.at == null
              ? `off-pitch (status=${piece.status})`
              : formatCoord(piece.at);
    throw new Error(
        `Presentation drift (${context}): player ${playerId} should be at ${formatCoord(origin)} to start motion, but view has them ${where}`,
    );
}

/** Loose-ball hop/bounce origin must still show a loose ball on the pre-fold board. */
export function assertLooseBallAtMotionOrigin(pieces: PieceSnapshotDto, origin: Coordinate, context: string): void {
    if (pieces.looseBallLocations.some((at) => sameCoord(at, origin))) return;
    const held = pieces.players.find((player) => player.holdsBall && player.at != null && sameCoord(player.at, origin));
    const hint =
        held != null
            ? `carrier ${held.id} holds the ball there instead`
            : `loose balls at [${pieces.looseBallLocations.map(formatCoord).join(", ") || "none"}]`;
    throw new Error(
        `Presentation drift (${context}): loose ball should be at ${formatCoord(origin)} to start motion, but ${hint}`,
    );
}

/** Throw origin must still show a carrier on the pre-fold board. */
export function assertHeldBallAtMotionOrigin(pieces: PieceSnapshotDto, origin: Coordinate, context: string): void {
    if (pieces.players.some((player) => player.holdsBall && player.at != null && sameCoord(player.at, origin))) return;
    const loose = pieces.looseBallLocations.some((at) => sameCoord(at, origin));
    const hint = loose
        ? "a loose ball is there instead"
        : `no carrier or loose ball at ${formatCoord(origin)}`;
    throw new Error(`Presentation drift (${context}): held ball should be at ${formatCoord(origin)} to start throw, but ${hint}`);
}

/**
 * Pre-fold origin checks for events that animate from a square.
 * Call before FX (and before fold) so deferred-fold / event-order bugs fail tests and runtime loudly.
 */
export function assertMotionOriginForEvent(pieces: PieceSnapshotDto, event: DomainEvent, context: string): void {
    switch (event.kind) {
        case "playerMoved":
            assertPlayerAtMotionOrigin(pieces, event.playerId, event.from, context);
            return;
        case "ballBounced":
            if (sameCoord(event.from, event.to)) return;
            assertLooseBallAtMotionOrigin(pieces, event.from, context);
            return;
        case "ballThrown":
        case "ballThrowMissed":
            assertHeldBallAtMotionOrigin(pieces, event.from, context);
            return;
        case "touchdownScored":
            assertPlayerAtMotionOrigin(pieces, event.playerId, event.at, context);
            return;
        case "playerInjured":
        case "playerRemovedAfterTouchdown":
            assertPlayerAtMotionOrigin(pieces, event.playerId, event.from, context);
            return;
        default:
            return;
    }
}

/** Extend a coalesced run path when the next SSE step continues from the tip. */
export function appendMotionStep(current: PieceMotion, step: PieceMotion): PieceMotion | null {
    if (current.playerId !== step.playerId || step.path.length < 2) return null;
    const tip = current.path[current.path.length - 1]!;
    if (!sameCoord(tip, step.path[0]!)) return null;
    const path = [...current.path, ...step.path.slice(1)];
    return { playerId: current.playerId, path, durationMs: motionDurationMs(path.length - 1) };
}

/** Extra air time so throw arcs read clearly over long lanes. */
export function throwFlightDurationMs(from: Coordinate, to: Coordinate): number {
    const dx = Math.abs(to.x - from.x);
    const dy = Math.abs(to.y - from.y);
    const range = Math.max(dx, dy, 1);
    return Math.min(1100, Math.max(420, 280 + range * 140));
}

/** Non-bounce ball flights (throws). Bounce hops use `playBallHop` on `ballBounced`. */
export function ballMotionFromEvents(events: DomainEvent[]): BallMotion | null {
    const hasBounceDice = events.some((event) => event.kind === "diceRolled" && event.purpose === "BOUNCE");
    const path: Coordinate[] = [];
    let flight: BallMotion["flight"] = "straight";
    for (const event of events) {
        switch (event.kind) {
            case "ballThrown":
            case "ballThrowMissed":
                if (path.length === 0) path.push(event.from);
                path.push(event.to);
                flight = "arc";
                break;
            case "ballBounced":
                // When bounce dice are in this batch, hops play after each die — don't pre-flight.
                if (hasBounceDice) break;
                if (path.length === 0) path.push(event.from);
                path.push(event.to);
                break;
            case "ballPlacedLoose":
                path.push(event.at);
                break;
            case "ballDropped":
            case "ballPickedUp":
                path.push(event.playerAt);
                break;
            default:
                break;
        }
    }
    if (path.length < 2) return null;
    const durationMs =
        flight === "arc"
            ? throwFlightDurationMs(path[0]!, path[path.length - 1]!)
            : motionDurationMs(path.length - 1);
    return { path, durationMs, flight };
}

/** Solo SSE `ballBounced` frame — fly once when the die already played. */
export function loneBounceHop(events: DomainEvent[], landingOccupied = false): BallFollow | null {
    if (events.some((event) => event.kind === "diceRolled" && event.purpose === "BOUNCE")) return null;
    const bounced = events.find((event): event is Extract<DomainEvent, { kind: "ballBounced" }> => event.kind === "ballBounced");
    if (bounced == null) return null;
    if (sameCoord(bounced.from, bounced.to)) return null;
    return {
        from: bounced.from,
        to: bounced.to,
        durationMs: BOUNCE_HOP_MS,
        lingerOnOccupant: landingOccupied,
    };
}

export function flashCoordsFromEvents(events: DomainEvent[], submitted?: LegalActionWire): Coordinate[] {
    const coords: Coordinate[] = [];
    for (const event of events) {
        switch (event.kind) {
            case "playerMoved":
                coords.push(event.from, event.to);
                break;
            case "ballBounced":
            case "ballThrown":
            case "ballThrowMissed":
                coords.push(event.to);
                break;
            case "ballPlacedLoose":
                coords.push(event.at);
                break;
            case "ballPickedUp":
            case "ballDropped":
                coords.push(event.playerAt);
                break;
            case "playerStatusChanged":
                coords.push(event.playerAt);
                break;
            case "playerInjured":
                coords.push(event.from);
                break;
            case "actionStarted":
                if (event.playerAt != null) coords.push(event.playerAt);
                break;
            case "playerDeployedFromReserves":
                coords.push(event.to);
                break;
            default:
                break;
        }
    }
    if (submitted?.kind === "block") coords.push(submitted.destination);
    if (submitted?.kind === "throw") coords.push(submitted.target.destination);
    if (submitted != null && "path" in submitted) {
        for (const step of submitted.path) coords.push(step);
    }
    return coords;
}

export function beatKey(beat: DiceBeat): string {
    return `${beat.purpose}|${beat.outcome}|${beat.dice.map((die) => die.result).join(",")}`;
}

export function beatSummary(beat: DiceBeat): string {
    return `${beat.outcome} (${beat.dice.map((die) => die.result).join(" · ")})`;
}

export function touchdownCelebrationFromEvents(events: DomainEvent[]): TouchdownCelebration | null {
    const scored = events.find((event): event is Extract<DomainEvent, { kind: "touchdownScored" }> => event.kind === "touchdownScored");
    if (scored == null) return null;
    return {
        kind: "touchdown",
        playerId: scored.playerId,
        team: scored.team,
        at: scored.at,
        homeScore: scored.homeScore,
        awayScore: scored.awayScore,
    };
}

/** Wire shape until OpenAPI includes challenge claim events. */
export type ChallengeCardClaimedWire = {
    kind: "challengeCardClaimed";
    team: "HOME" | "AWAY";
    slotIndex: number;
    claimCondition: string;
    challengeTitle: string;
    pointsAwarded: number;
    homeScore: number;
    awayScore: number;
    claimsThisTurn: number;
};

export type CleanSweepWire = {
    kind: "cleanSweep";
    team: "HOME" | "AWAY";
    pointsAwarded: number;
    homeScore: number;
    awayScore: number;
};

export function asChallengeCardClaimed(event: DomainEvent): ChallengeCardClaimedWire | null {
    const e = event as Partial<ChallengeCardClaimedWire>;
    if (
        e.kind !== "challengeCardClaimed" ||
        (e.team !== "HOME" && e.team !== "AWAY") ||
        typeof e.slotIndex !== "number" ||
        typeof e.claimCondition !== "string" ||
        typeof e.challengeTitle !== "string" ||
        typeof e.pointsAwarded !== "number" ||
        typeof e.homeScore !== "number" ||
        typeof e.awayScore !== "number" ||
        typeof e.claimsThisTurn !== "number"
    ) {
        return null;
    }
    return e as ChallengeCardClaimedWire;
}

export function asCleanSweep(event: DomainEvent): CleanSweepWire | null {
    const e = event as Partial<CleanSweepWire>;
    if (
        e.kind !== "cleanSweep" ||
        (e.team !== "HOME" && e.team !== "AWAY") ||
        typeof e.pointsAwarded !== "number" ||
        typeof e.homeScore !== "number" ||
        typeof e.awayScore !== "number"
    ) {
        return null;
    }
    return e as CleanSweepWire;
}

export function challengeClaimCelebrationFromEvent(
    event: DomainEvent,
    fromHomeScore: number,
    fromAwayScore: number,
): ChallengeClaimCelebration | null {
    const claimed = asChallengeCardClaimed(event);
    if (claimed == null) return null;
    return {
        kind: "challengeClaim",
        team: claimed.team,
        slotIndex: claimed.slotIndex,
        challengeCondition: claimed.claimCondition,
        title: claimed.challengeTitle,
        pointsAwarded: claimed.pointsAwarded,
        fromHomeScore,
        fromAwayScore,
        homeScore: claimed.homeScore,
        awayScore: claimed.awayScore,
    };
}

export function cleanSweepCelebrationFromEvent(
    event: DomainEvent,
    fromHomeScore: number,
    fromAwayScore: number,
): CleanSweepCelebration | null {
    const sweep = asCleanSweep(event);
    if (sweep == null) return null;
    return {
        kind: "cleanSweep",
        team: sweep.team,
        pointsAwarded: sweep.pointsAwarded,
        fromHomeScore,
        fromAwayScore,
        homeScore: sweep.homeScore,
        awayScore: sweep.awayScore,
    };
}

/** Flight path off the pitch toward the dugout (sidebar is to the right). */
export function reservesExitPath(from: Coordinate, team: "HOME" | "AWAY", playWidth: number): Coordinate[] {
    const bias = team === "HOME" ? -1.2 : 1.2;
    return [
        from,
        { x: playWidth + 0.4, y: from.y + bias * 0.35 },
        { x: playWidth + 2.4, y: from.y + bias },
    ];
}

export function injuryReservesExit(
    events: DomainEvent[],
    teamByPlayer: Map<PlayerId, "HOME" | "AWAY">,
    playWidth: number,
): ReservesExit | null {
    const injured = events.find((event): event is Extract<DomainEvent, { kind: "playerInjured" }> => event.kind === "playerInjured");
    if (injured == null) return null;
    const team = teamByPlayer.get(injured.playerId) ?? "HOME";
    return {
        playerId: injured.playerId,
        team,
        reason: "injury",
        path: reservesExitPath(injured.from, team, playWidth),
        durationMs: RESERVES_EXIT_MS,
    };
}

export function touchdownReservesExit(celebration: TouchdownCelebration, playWidth: number): ReservesExit {
    return {
        playerId: celebration.playerId,
        team: celebration.team,
        reason: "touchdown",
        path: reservesExitPath(celebration.at, celebration.team, playWidth),
        durationMs: RESERVES_EXIT_MS,
    };
}
