import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Coordinate, LegalActionWire, MatchRosterDto, PlayerCardDto, PlayerId } from "../api/client";
import { assetRegistry } from "../assets/registry";
import {
    abilityBannerDecline,
    actionsAtCoordinate,
    cancelThrowTeammateActionForPlayer,
    chooseBlockRerollActions,
    chooseThrowRerollActions,
    describeAction,
    describeActionTooltip,
    eligiblePlayerIds,
    formatPotentialChallengeClaims,
    formatPlayerDecisionType,
    remainingMoveSquaresFromLegal,
    skipActionForPlayer,
    squareHighlightsForActions,
    throwDifficultyForAction,
} from "../render/actionVisuals";
import BlockContest from "../render/BlockContest";
import BonusBadge from "../render/BonusBadge";
import EventLog from "../render/EventLog";
import Dugout from "../render/Dugout";
import ForcedDecisionDialog from "../render/ForcedDecisionDialog";
import { detectForcedDecision } from "../render/forcedDecision";
import Hud from "../render/Hud";
import BonusHandPanel from "../render/BonusHandPanel";
import Pitch from "../render/Pitch";
import {
    resolveSquareClick,
    resolveTokenClick,
    selectedPlayerActions,
    selectionLockedToSoleEligible,
} from "../render/pitchClicks";
import { coordKey, gridHeight, gridWidth, isOutOfBounds, isPlayableSquare } from "../render/pitchLayout";
import { BOARD_IMAGE_CONTENT } from "../render/boardImageLayout";
import PlayerCard from "../render/PlayerCard";
import AbilityBadge from "../render/AbilityBadge";
import ActionDice from "../render/ActionDice";
import Token, { InFlightBall, LooseBall } from "../render/Token";
import { formatMatchResult, type MatchResultSummary } from "../render/matchResultCopy";
import { isAwaitingInput, useMatchStore } from "../state/store";

type PlayProps = {
    matchId: string;
    onGameOver: (summary?: {
        homeScore: number;
        awayScore: number;
        turnNumber: number;
        result: MatchResultSummary | null;
        resultSummary: string;
    }) => void;
    onMatchUnavailable: () => void;
};

function rosterCardById(roster: MatchRosterDto): Map<PlayerId, PlayerCardDto> {
    return new Map(roster.players.map((entry) => [entry.playerId, entry.playerCard]));
}

export default function Play({
    matchId,
    onGameOver,
    onMatchUnavailable,
}: PlayProps) {
    const joinMatch = useMatchStore((state) => state.joinMatch);
    const leaveMatch = useMatchStore((state) => state.leaveMatch);
    const connectionPhase = useMatchStore((state) => state.connectionPhase);
    const pitch = useMatchStore((state) => state.pitch);
    const roster = useMatchStore((state) => state.roster);
    const view = useMatchStore((state) => state.view);
    const legalActions = useMatchStore((state) => state.legalActions);
    const legalActionsLoading = useMatchStore((state) => state.legalActionsLoading);
    const bonusHand = useMatchStore((state) => state.bonusHand);
    const pendingDecisionType = useMatchStore((state) => state.pendingDecisionType);
    const pendingAbility = useMatchStore((state) => state.pendingAbility);
    const actionError = useMatchStore((state) => state.actionError);
    const eventLog = useMatchStore((state) => state.eventLog);
    const lastDiceSummary = useMatchStore((state) => state.lastDiceSummary);
    const diceTheatre = useMatchStore((state) => state.diceTheatre);
    const activeBlock = useMatchStore((state) => state.activeBlock);
    const ballMotion = useMatchStore((state) => state.ballMotion);
    const flashSquares = useMatchStore((state) => state.flashSquares);
    const celebration = useMatchStore((state) => state.celebration);
    const scorePulseTeam = useMatchStore((state) => state.scorePulseTeam);
    const abilityBanner = useMatchStore((state) => state.abilityBanner);
    const localCoach = useMatchStore((state) => state.localCoach);
    const preferredLocalCoach = useMatchStore((state) => state.preferredLocalCoach);
    const reservesExit = useMatchStore((state) => state.reservesExit);
    const submitAction = useMatchStore((state) => state.submitLegalAction);
    const clearActionError = useMatchStore((state) => state.clearActionError);
    const dismissDiceTheatre = useMatchStore((state) => state.dismissDiceTheatre);
    const skipDiceAnimations = useMatchStore((state) => state.skipDiceAnimations);
    const setSkipDiceAnimations = useMatchStore((state) => state.setSkipDiceAnimations);

    const [selectedPlayerId, setSelectedPlayerId] = useState<PlayerId | null>(null);
    const [hoveredPlayerId, setHoveredPlayerId] = useState<PlayerId | null>(null);
    const [hoverCoordinate, setHoverCoordinate] = useState<Coordinate | null>(null);
    const [pendingChoices, setPendingChoices] = useState<LegalActionWire[] | null>(null);
    const [bonusConfirm, setBonusConfirm] = useState<LegalActionWire | null>(null);
    const [zoom, setZoom] = useState(1);
    const [pan, setPan] = useState({ x: 0, y: 0 });
    /** Collapsed ability scroll so board targets stay clickable. */
    const [abilityScrollMinimized, setAbilityScrollMinimized] = useState(false);

    useEffect(() => {
        joinMatch(matchId);
        return () => leaveMatch();
    }, [joinMatch, leaveMatch, matchId]);

    useEffect(() => {
        if (connectionPhase === "unavailable") {
            onMatchUnavailable();
        }
    }, [connectionPhase, onMatchUnavailable]);

    // Wait for MatchEnded (sets result) — PhaseStarted(GAME_OVER) arrives first and must not navigate early.
    useEffect(() => {
        const result = view?.gameState.result;
        if (result == null) return;
        onGameOver({
            homeScore: view.gameState.homeScore,
            awayScore: view.gameState.awayScore,
            turnNumber: view.gameState.turnNumber,
            result,
            resultSummary: formatMatchResult(result),
        });
    }, [
        view?.gameState.result,
        view?.gameState.homeScore,
        view?.gameState.awayScore,
        view?.gameState.turnNumber,
        onGameOver,
    ]);

    useEffect(() => {
        if (selectedPlayerId == null) return;
        // Keep selection through submit / dice theatre (spectating + empty actions gap).
        if (legalActionsLoading || connectionPhase !== "awaitingInput") return;
        const stillEligible = legalActions.some(
            (action) => "playerId" in action && action.playerId === selectedPlayerId,
        );
        if (!stillEligible) setSelectedPlayerId(null);
    }, [legalActions, legalActionsLoading, selectedPlayerId, connectionPhase]);

    // When every legal move belongs to one player, select them automatically.
    useEffect(() => {
        if (connectionPhase !== "awaitingInput" || legalActionsLoading || legalActions.length === 0) return;
        const ids = eligiblePlayerIds(legalActions);
        if (ids.size !== 1) return;
        const onlyId = ids.values().next().value!;
        setSelectedPlayerId((current) => (current === onlyId ? current : onlyId));
    }, [legalActions, legalActionsLoading, connectionPhase]);

    // Fresh pick after a turn handoff or phase change (e.g. placement → player actions).
    const turnHandOffKey =
        view != null ? `${view.gameState.turnNumber}:${view.gameState.activeCoach}:${view.gameState.phase}` : null;
    const priorTurnKey = useRef<string | null>(null);
    useEffect(() => {
        if (turnHandOffKey == null) return;
        if (priorTurnKey.current != null && priorTurnKey.current !== turnHandOffKey) {
            setSelectedPlayerId(null);
            setPendingChoices(null);
            setHoverCoordinate(null);
        }
        priorTurnKey.current = turnHandOffKey;
    }, [turnHandOffKey]);

    const cards = useMemo(() => (roster ? rosterCardById(roster) : new Map<PlayerId, PlayerCardDto>()), [roster]);
    const interactive = isAwaitingInput({ connectionPhase });
    const remainingMoveSquares = useMemo(() => {
        if (pendingDecisionType !== "RUN_CONTINUE") return null;
        return remainingMoveSquaresFromLegal(legalActions);
    }, [pendingDecisionType, legalActions]);
    const decisionPrompt =
        interactive && pendingDecisionType != null
            ? formatPlayerDecisionType(pendingDecisionType, pendingAbility, remainingMoveSquares)
            : null;

    const eligible = useMemo(() => (interactive ? eligiblePlayerIds(legalActions) : new Set<PlayerId>()), [interactive, legalActions]);
    const selectionLocked = selectionLockedToSoleEligible(eligible, selectedPlayerId);

    useEffect(() => {
        function onKeyDown(event: KeyboardEvent) {
            if (event.key === "Escape") {
                if (pendingChoices) {
                    setPendingChoices(null);
                    return;
                }
                if (selectionLocked) {
                    setHoverCoordinate(null);
                    return;
                }
                setSelectedPlayerId(null);
                setHoverCoordinate(null);
            }
        }
        window.addEventListener("keydown", onKeyDown);
        return () => window.removeEventListener("keydown", onKeyDown);
    }, [pendingChoices, selectionLocked]);

    const selectedActions = useMemo(
        () => selectedPlayerActions(legalActions, selectedPlayerId),
        [legalActions, selectedPlayerId],
    );

    const bannerDecline = useMemo(() => {
        if (
            !interactive ||
            abilityBanner == null ||
            abilityBanner.team !== localCoach ||
            pendingAbility == null ||
            pendingAbility !== abilityBanner.ability ||
            legalActionsLoading ||
            legalActions.length === 0
        ) {
            return null;
        }
        return abilityBannerDecline(legalActions);
    }, [
        interactive,
        abilityBanner,
        localCoach,
        pendingAbility,
        legalActionsLoading,
        legalActions,
    ]);

    /** Still choosing the ability (not the post-resolve linger) — keep the scroll off the pitch. */
    const abilityScrollPromptActive =
        abilityBanner != null && pendingAbility != null && pendingAbility === abilityBanner.ability;

    useEffect(() => {
        setAbilityScrollMinimized(false);
    }, [abilityBanner?.ability, abilityBanner?.team]);

    const skipAction = useMemo(() => {
        if (bannerDecline?.action.kind === "skip") return null;
        return skipActionForPlayer(selectedActions, selectedPlayerId);
    }, [bannerDecline, selectedActions, selectedPlayerId]);

    const cancelThrowTeammateAction = useMemo(
        () => cancelThrowTeammateActionForPlayer(selectedActions, selectedPlayerId),
        [selectedActions, selectedPlayerId],
    );

    const blockRerollActions = useMemo(
        () => chooseBlockRerollActions(selectedActions, selectedPlayerId),
        [selectedActions, selectedPlayerId],
    );

    const throwRerollActions = useMemo(
        () => chooseThrowRerollActions(selectedActions, selectedPlayerId),
        [selectedActions, selectedPlayerId],
    );

    const abilityDescription = useMemo(() => {
        if (pendingAbility == null) return null;
        const playerId =
            selectedPlayerId ??
            legalActions.find((action): action is Extract<LegalActionWire, { playerId: PlayerId }> => "playerId" in action)
                ?.playerId ??
            null;
        if (playerId == null) return null;
        return cards.get(playerId)?.ability?.description ?? null;
    }, [pendingAbility, selectedPlayerId, legalActions, cards]);

    const forcedDecision = useMemo(() => {
        if (!interactive || legalActionsLoading || legalActions.length === 0) return null;
        return detectForcedDecision(legalActions, {
            pendingAbility,
            abilityDescription,
            lastDiceSummary,
        });
    }, [interactive, legalActionsLoading, legalActions, pendingAbility, abilityDescription, lastDiceSummary]);

    const highlights = useMemo(
        () => (interactive && selectedPlayerId != null ? squareHighlightsForActions(selectedActions) : new Map()),
        [interactive, selectedPlayerId, selectedActions],
    );

    const pathPreview = useMemo(() => {
        if (!interactive || selectedPlayerId == null || hoverCoordinate == null) return [];
        const at = actionsAtCoordinate(selectedActions, hoverCoordinate);
        const withPath = at.find(
            (action): action is Extract<LegalActionWire, { path: Coordinate[] }> =>
                "path" in action && action.path.length >= 2,
        );
        return withPath?.path ?? [];
    }, [interactive, selectedPlayerId, hoverCoordinate, selectedActions]);

    const throwArcPreview = useMemo(() => {
        if (!interactive || selectedPlayerId == null || hoverCoordinate == null || view == null) return null;
        const throwAction = actionsAtCoordinate(selectedActions, hoverCoordinate).find(
            (
                action,
            ): action is Extract<LegalActionWire, { kind: "throw" | "throwTeammate" | "quickReleaseThrow" }> =>
                action.kind === "throw" || action.kind === "throwTeammate" || action.kind === "quickReleaseThrow",
        );
        if (throwAction == null) return null;
        const thrower = view.pieces.players.find((piece) => piece.id === selectedPlayerId);
        if (thrower?.at == null) return null;
        return {
            from: thrower.at,
            to: throwAction.target.destination,
            obstructed: throwAction.target.obstructed,
            difficulty: throwDifficultyForAction(throwAction) ?? "short",
        };
    }, [interactive, selectedPlayerId, hoverCoordinate, selectedActions, view]);

    const squareTitles = useMemo(() => {
        const titles = new Map<string, string>();
        if (pitch == null || view == null) return titles;
        const width = gridWidth(pitch);
        const height = gridHeight(pitch);
        const blocked = new Set(pitch.blockedSquares.map(coordKey));
        const trapdoors = new Set(pitch.trapdoors.map(coordKey));
        const occupantBySquare = new Map<string, string>();
        for (const piece of view.pieces.players) {
            if (piece.at == null || piece.status === "RESERVES") continue;
            const card = cards.get(piece.id);
            const position = card?.positionLabel ?? piece.id;
            const bits = [
                `${position} (${piece.id})`,
                piece.team,
                piece.status === "PRONE" ? "prone" : "standing",
                piece.holdsBall ? "has ball" : null,
            ];
            occupantBySquare.set(coordKey(piece.at), bits.filter(Boolean).join(" · "));
        }
        const loose = new Set(view.pieces.looseBallLocations.map(coordKey));

        for (let y = 0; y < height; y++) {
            for (let x = 0; x < width; x++) {
                const key = coordKey({ x, y });
                const parts: string[] = [`Square (${x}, ${y})`];
                if (isOutOfBounds(pitch, x, y)) parts.push("out of bounds");
                else if (isPlayableSquare(pitch, x, y)) {
                    if (y === pitch.homeEndZoneRow) parts.push("Home end zone");
                    if (y === pitch.awayEndZoneRow) parts.push("Away end zone");
                    if (blocked.has(key)) parts.push(pitch.pitchDefinitionId === "default" ? "blocked pillar" : "blocked barricade");
                    if (trapdoors.has(key)) parts.push("trapdoor");
                    const occupant = occupantBySquare.get(key);
                    if (occupant) parts.push(`Occupied by ${occupant}`);
                    if (loose.has(key)) parts.push("Loose ball here");
                    if (selectedPlayerId != null) {
                        const at = actionsAtCoordinate(selectedActions, { x, y });
                        if (at.length > 0) parts.push(`Click: ${at.map(describeAction).join(" · ")}`);
                    } else if (interactive && eligible.size > 0) {
                        parts.push("Select a green-ringed player first");
                    }
                }
                titles.set(key, parts.join(" — "));
            }
        }
        return titles;
    }, [pitch, view, cards, selectedPlayerId, selectedActions, interactive, eligible]);

    const hoverHint = useMemo(() => {
        if (hoveredPlayerId != null) {
            const piece = view?.pieces.players.find((entry) => entry.id === hoveredPlayerId);
            const card = cards.get(hoveredPlayerId);
            if (piece != null) {
                const position = card?.positionLabel ?? piece.id;
                const bits = [
                    `${position} (${piece.id})`,
                    piece.team,
                    piece.at != null ? `at (${piece.at.x}, ${piece.at.y})` : "reserves",
                    piece.status === "PRONE" ? "prone" : piece.status === "RESERVES" ? "reserve" : "standing",
                    piece.holdsBall ? "holding ball" : null,
                    eligible.has(piece.id) ? "can act — click to select" : null,
                    selectedPlayerId === piece.id ? "selected" : null,
                ];
                if (selectedPlayerId != null && piece.at != null) {
                    const at = actionsAtCoordinate(selectedActions, piece.at);
                    if (at.length > 0) bits.push(`Target: ${at.map(describeAction).join(" · ")}`);
                }
                return bits.filter(Boolean).join(" · ");
            }
        }
        if (hoverCoordinate != null) {
            return squareTitles.get(coordKey(hoverCoordinate)) ?? `Square (${hoverCoordinate.x}, ${hoverCoordinate.y})`;
        }
        if (selectedPlayerId != null) {
            const declareRun = selectedActions.find((action) => action.kind === "declareRun");
            if (declareRun != null) {
                return `Selected ${selectedPlayerId} — click their square (or token) to roll Move`;
            }
            if (pendingDecisionType === "RUN_CONTINUE") {
                const budget =
                    remainingMoveSquares != null ? ` (up to ${remainingMoveSquares})` : "";
                return `Selected ${selectedPlayerId} — choose a run path${budget}, or Skip to end`;
            }
            return `Selected ${selectedPlayerId} — hover a highlighted square to preview the action`;
        }
        if (interactive && eligible.size > 0) {
            return "Hover players and squares for details · click a green-ringed player to act";
        }
        return null;
    }, [
        hoveredPlayerId,
        hoverCoordinate,
        view,
        cards,
        eligible,
        selectedPlayerId,
        selectedActions,
        squareTitles,
        interactive,
        pendingDecisionType,
        remainingMoveSquares,
    ]);

    // Prefer an explicit throw warning when hovering a pass target.
    const hoverHintWithThrow = useMemo(() => {
        if (throwArcPreview == null) return hoverHint;
        const lane = throwArcPreview.obstructed ? "obstructed lane" : "clear lane";
        return `Throw (${lane}) — arc shows destination · click to throw`;
    }, [hoverHint, throwArcPreview]);

    const requestSubmit = useCallback(
        (action: LegalActionWire) => {
            if (action.requiredBonusCard != null) {
                setBonusConfirm(action);
                return;
            }
            void submitAction(action);
        },
        [submitAction],
    );

    const handleSquareClick = useCallback(
        (coordinate: Coordinate) => {
            const intent = resolveSquareClick(selectedActions, selectedPlayerId, coordinate, selectionLocked);
            switch (intent.kind) {
                case "submit":
                    requestSubmit(intent.actions[0]!);
                    return;
                case "choose":
                    setPendingChoices(intent.actions);
                    return;
                case "deselect":
                    setSelectedPlayerId(null);
                    setPendingChoices(null);
                    return;
                case "ignore":
                    return;
            }
        },
        [selectedActions, selectedPlayerId, selectionLocked, requestSubmit],
    );

    const handleTokenSelect = useCallback(
        (playerId: PlayerId) => {
            const piece = view?.pieces.players.find((entry) => entry.id === playerId);
            const intent = resolveTokenClick({
                interactive,
                selectedPlayerId,
                eligible,
                selectedActions,
                clickedPlayerId: playerId,
                clickedAt: piece?.at ?? null,
            });
            switch (intent.kind) {
                case "submit":
                    requestSubmit(intent.actions[0]!);
                    return;
                case "choose":
                    setPendingChoices(intent.actions);
                    return;
                case "select":
                    setSelectedPlayerId(intent.playerId);
                    setPendingChoices(null);
                    return;
            }
        },
        [interactive, eligible, selectedPlayerId, selectedActions, view?.pieces.players, requestSubmit],
    );

    const activeCardPlayerId = hoveredPlayerId ?? selectedPlayerId;
    const activeCard = activeCardPlayerId ? (cards.get(activeCardPlayerId) ?? null) : null;
    const pitchBoardUrl = pitch ? assetRegistry.pitchBoard(pitch.pitchDefinitionId) : null;
    const pitchBoardContent = pitch ? (BOARD_IMAGE_CONTENT[pitch.pitchDefinitionId] ?? null) : null;
    const pitchTiles = pitch && pitchBoardUrl == null ? assetRegistry.pitchTiles(pitch.pitchDefinitionId) : null;

    if (connectionPhase === "unavailable") {
        return (
            <main className="screen play">
                <p>Match not found — returning to setup…</p>
            </main>
        );
    }

    if (connectionPhase === "connecting" || connectionPhase === "hydrating" || pitch == null || roster == null || view == null) {
        return (
            <main className="screen play">
                <p>{connectionPhase === "hydrating" ? `Loading match ${matchId}…` : `Connecting to match ${matchId}…`}</p>
            </main>
        );
    }

    const touchdown = celebration?.kind === "touchdown" ? celebration : null;
    const celebratingId = touchdown?.playerId ?? null;
    const exitingId = reservesExit?.playerId ?? null;
    const pinnedOffPitchId = celebratingId ?? exitingId;
    const looseOnSquare = new Set(view.pieces.looseBallLocations.map(coordKey));
    // Any bounce hop (flying or parked pre-fold) owns the glyph — don't paint a carrier badge
    // from the still-pre-fold loose ball at `from`.
    const bounceMotionActive = ballMotion != null && ballMotion.flight === "bounce";
    const onPitchPlayers = view.pieces.players
        .filter((piece) => {
            if (pinnedOffPitchId != null && piece.id === pinnedOffPitchId) return true;
            return piece.status !== "RESERVES" && piece.at != null;
        })
        .map((piece) => {
            if (touchdown != null && piece.id === touchdown.playerId) {
                return { ...piece, at: touchdown.at, status: "STANDING" as const, holdsBall: true };
            }
            if (reservesExit != null && piece.id === reservesExit.playerId) {
                const from = reservesExit.path[0]!;
                return { ...piece, at: from, status: "STANDING" as const, holdsBall: false };
            }
            // Re-bounce: loose ball sits on this square — show the carrier badge while the next die rolls.
            // While a bounce hop is in flight / parked, InFlightBall owns the glyph.
            if (piece.at != null && looseOnSquare.has(coordKey(piece.at))) {
                if (bounceMotionActive) {
                    return piece;
                }
                return { ...piece, holdsBall: true };
            }
            return piece;
        });
    const occupiedKeys = new Set(
        onPitchPlayers.filter((piece) => piece.at != null).map((piece) => coordKey(piece.at!)),
    );
    // Ball on a player square shows as the carrier badge (re-bounce), not a second loose glyph.
    const looseBallLocations = view.pieces.looseBallLocations.filter((at) => !occupiedKeys.has(coordKey(at)));
    const reserves = view.pieces.players.filter(
        (piece) => piece.status === "RESERVES" && piece.id !== pinnedOffPitchId,
    );

    const tdOrigin =
        touchdown != null && pitch != null
            ? `${((touchdown.at.x + 0.5) / gridWidth(pitch)) * 100}% ${((touchdown.at.y + 0.5) / gridHeight(pitch)) * 100}%`
            : "50% 50%";

    const claimCelebration = celebration?.kind === "challengeClaim" ? celebration : null;
    const sweepCelebration = celebration?.kind === "cleanSweep" ? celebration : null;
    const claimCardArt =
        claimCelebration != null ? assetRegistry.challengeCardFace(claimCelebration.challengeCondition) : null;

    const blockAttacker = activeBlock != null ? onPitchPlayers.find((piece) => piece.id === activeBlock.attackerId) : null;
    const blockDefender = activeBlock != null ? onPitchPlayers.find((piece) => piece.id === activeBlock.defenderId) : null;
    const diceBeside =
        diceTheatre != null &&
        diceTheatre.queue[diceTheatre.index]?.purpose === "BLOCK" &&
        blockAttacker?.at != null &&
        blockDefender?.at != null
            ? blockAttacker.at
            : null;

    return (
        <main className={`screen play${touchdown ? " play--celebrate" : ""}`} data-match-id={matchId}>
            {actionError ? (
                <p className="play__error" role="alert">
                    {actionError}{" "}
                    <button type="button" className="play__error-dismiss" title="Dismiss this error" onClick={clearActionError}>
                        Dismiss
                    </button>
                </p>
            ) : null}
            <div className="play-layout">
                <aside className="play-chrome">
                    <div className="play-chrome__top">
                        <Hud
                            gameState={view.gameState}
                            connectionPhase={connectionPhase}
                            interactive={interactive}
                            localCoach={localCoach}
                            preferredLocalCoach={preferredLocalCoach}
                            pendingDecisionType={pendingDecisionType}
                            pendingAbility={pendingAbility}
                            remainingMoveSquares={remainingMoveSquares}
                            scorePulseTeam={scorePulseTeam}
                            claimingSlotIndex={claimCelebration?.slotIndex ?? null}
                        />
                        <BonusHandPanel hand={bonusHand} legalActions={legalActions} />
                    </div>
                    <EventLog entries={eventLog} />
                </aside>
                <div className="pitch-container" style={touchdown ? { transformOrigin: tdOrigin } : undefined}>
                    <div className="pitch-zoom-controls" aria-label="Pitch zoom">
                        <button
                            type="button"
                            onClick={() => setZoom((z) => Math.min(2.5, z + 0.25))}
                            aria-label="Zoom in"
                            title="Zoom in (pitch view)"
                        >
                            +
                        </button>
                        <button
                            type="button"
                            onClick={() => setZoom((z) => Math.max(0.75, z - 0.25))}
                            aria-label="Zoom out"
                            title="Zoom out (pitch view)"
                        >
                            −
                        </button>
                        <button
                            type="button"
                            onClick={() => {
                                setZoom(1);
                                setPan({ x: 0, y: 0 });
                            }}
                            aria-label="Reset view"
                            title="Reset zoom and pan (Alt+drag or middle-mouse to pan)"
                        >
                            Reset
                        </button>
                        <label
                            className="pitch-pref"
                            title="Skip the tumble animation — still show landed faces and the outcome shout"
                        >
                            <input
                                type="checkbox"
                                checked={skipDiceAnimations}
                                onChange={(event) => setSkipDiceAnimations(event.target.checked)}
                                data-testid="skip-dice-animations"
                            />
                            <span>Fast dice</span>
                        </label>
                        <span
                            className={`pitch-decision${decisionPrompt == null ? " pitch-decision--empty" : ""}`}
                            role="status"
                            aria-live="polite"
                            aria-hidden={decisionPrompt == null}
                            title={decisionPrompt ?? undefined}
                            data-testid="pitch-decision"
                        >
                            {decisionPrompt ?? "Choose a player action"}
                        </span>
                    </div>
                    <div
                        className="pitch-zoom-pane"
                        style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }}
                        onPointerDown={(event) => {
                            if (event.button !== 1 && !(event.button === 0 && event.altKey)) return;
                            event.preventDefault();
                            const startX = event.clientX;
                            const startY = event.clientY;
                            const origin = { ...pan };
                            function onMove(move: PointerEvent) {
                                setPan({ x: origin.x + (move.clientX - startX), y: origin.y + (move.clientY - startY) });
                            }
                            function onUp() {
                                window.removeEventListener("pointermove", onMove);
                                window.removeEventListener("pointerup", onUp);
                            }
                            window.addEventListener("pointermove", onMove);
                            window.addEventListener("pointerup", onUp);
                        }}
                    >
                        <Pitch
                            layout={pitch}
                            highlights={highlights}
                            pathPreview={pathPreview}
                            throwArcPreview={throwArcPreview}
                            flashedSquares={flashSquares}
                            hoverHint={hoverHintWithThrow}
                            hoverCoordinate={hoverCoordinate}
                            squareTitles={squareTitles}
                            occupiedKeys={occupiedKeys}
                            tiles={pitchTiles}
                            boardImageUrl={pitchBoardUrl}
                            boardImageContent={pitchBoardContent}
                            onSquareClick={interactive ? handleSquareClick : undefined}
                            onSquareHover={setHoverCoordinate}
                        >
                            {onPitchPlayers.map((piece) => (
                                <Token
                                    key={piece.id}
                                    piece={piece}
                                    card={cards.get(piece.id)}
                                    selected={selectedPlayerId === piece.id}
                                    eligible={interactive && eligible.has(piece.id) && selectedPlayerId !== piece.id}
                                    celebrating={celebratingId === piece.id}
                                    exiting={exitingId === piece.id}
                                    onSelect={handleTokenSelect}
                                    onHover={setHoveredPlayerId}
                                />
                            ))}
                            {blockAttacker != null && blockDefender != null ? (
                                <BlockContest
                                    attacker={blockAttacker}
                                    defender={blockDefender}
                                    attackerCard={cards.get(blockAttacker.id)}
                                    defenderCard={cards.get(blockDefender.id)}
                                />
                            ) : null}
                            {looseBallLocations.map((at) => (
                                <LooseBall key={`${at.x},${at.y}`} at={at} />
                            ))}
                            {ballMotion != null ? <InFlightBall /> : null}
                            {diceTheatre ? (
                                <ActionDice theatre={diceTheatre} beside={diceBeside} onSkip={dismissDiceTheatre} />
                            ) : null}
                        </Pitch>
                    </div>
                </div>
                <aside className="play-sidebar">
                    <div className="play-sidebar__actions">
                        <p
                            className={`last-roll-chip${lastDiceSummary == null ? " last-roll-chip--empty" : ""}`}
                            role="status"
                            title={lastDiceSummary ?? undefined}
                            aria-hidden={lastDiceSummary == null}
                        >
                            Last roll: {lastDiceSummary ?? "—"}
                        </p>
                        <div className="play-sidebar__buttons">
                            {skipAction != null && forcedDecision == null ? (
                                <button
                                    type="button"
                                    className="skip-action"
                                    title={describeActionTooltip(skipAction)}
                                    onClick={() => void submitAction(skipAction)}
                                >
                                    Skip
                                </button>
                            ) : null}
                            {cancelThrowTeammateAction != null && forcedDecision == null ? (
                                <button
                                    type="button"
                                    className="skip-action"
                                    title={describeActionTooltip(cancelThrowTeammateAction)}
                                    onClick={() => void submitAction(cancelThrowTeammateAction)}
                                >
                                    Cancel throw
                                </button>
                            ) : null}
                            {forcedDecision == null
                                ? blockRerollActions.map((action) => (
                                      <button
                                          key={action.id}
                                          type="button"
                                          className="skip-action"
                                          title={describeActionTooltip(action)}
                                          onClick={() => void submitAction(action)}
                                      >
                                          {action.reroll ? "Re-roll block dice" : "Keep block dice"}
                                      </button>
                                  ))
                                : null}
                            {forcedDecision == null
                                ? throwRerollActions.map((action) => (
                                      <button
                                          key={action.id}
                                          type="button"
                                          className="skip-action"
                                          title={describeActionTooltip(action)}
                                          onClick={() => void submitAction(action)}
                                      >
                                          {action.reroll ? "Re-roll throw" : "Keep throw roll"}
                                      </button>
                                  ))
                                : null}
                        </div>
                    </div>
                    <Dugout
                        reserves={reserves}
                        allPlayers={view.pieces.players}
                        roster={roster}
                        selectedPlayerId={selectedPlayerId}
                        eligiblePlayerIds={eligible}
                        onSelectPlayer={handleTokenSelect}
                        onHoverPlayer={setHoveredPlayerId}
                    />
                    <PlayerCard card={activeCard} />
                </aside>
            </div>

            {pendingChoices ? (
                <div className="action-chooser" role="dialog" aria-label="Choose action">
                    <p>Multiple actions on this square</p>
                    <ul>
                        {pendingChoices.map((action, index) => {
                            const claims = action.potentialChallengeClaims ?? [];
                            const claimLabel =
                                claims.length > 0
                                    ? formatPotentialChallengeClaims(claims)
                                    : null;
                            const ballLabel =
                                action.ballEncounter === "PICK_UP"
                                    ? "Picks up ball"
                                    : action.ballEncounter === "BOUNCE"
                                      ? "Bounces ball"
                                      : null;
                            const optionClass = [
                                "action-chooser__option",
                                claimLabel != null ? "action-chooser__option--claim" : null,
                                ballLabel != null ? "action-chooser__option--ball" : null,
                            ]
                                .filter(Boolean)
                                .join(" ");
                            return (
                                <li key={`${action.kind}-${index}`}>
                                    <button
                                        type="button"
                                        className={optionClass}
                                        title={`Confirm: ${describeAction(action)}`}
                                        onClick={() => {
                                            setPendingChoices(null);
                                            requestSubmit(action);
                                        }}
                                    >
                                        <span className="action-chooser__option-label">{describeAction(action)}</span>
                                        {ballLabel != null ? (
                                            <span
                                                className={
                                                    action.ballEncounter === "PICK_UP"
                                                        ? "action-chooser__ball-badge action-chooser__ball-badge--pickup"
                                                        : "action-chooser__ball-badge action-chooser__ball-badge--bounce"
                                                }
                                                title={ballLabel}
                                                aria-label={ballLabel}
                                            >
                                                {action.ballEncounter === "PICK_UP" ? "Pickup" : "Bounce"}
                                            </span>
                                        ) : null}
                                        {claimLabel != null ? (
                                            <span
                                                className="action-chooser__claim-badge"
                                                title={claimLabel}
                                                aria-label={claimLabel}
                                            >
                                                Challenge
                                            </span>
                                        ) : null}
                                    </button>
                                </li>
                            );
                        })}
                    </ul>
                    <button type="button" title="Cancel and keep the current selection" onClick={() => setPendingChoices(null)}>
                        Cancel
                    </button>
                </div>
            ) : null}

            {bonusConfirm?.requiredBonusCard != null ? (
                <div className="action-chooser" role="dialog" aria-label="Confirm bonus card">
                    <p className="action-chooser__bonus-line">
                        <BonusBadge
                            bonusPlay={bonusConfirm.requiredBonusCard}
                            size="md"
                            className="action-chooser__bonus-badge"
                        />
                        <span>
                            This action uses{" "}
                            <strong>{bonusConfirm.requiredBonusCard}</strong>.
                        </span>
                    </p>
                    <p className="action-chooser__detail">{describeAction(bonusConfirm)}</p>
                    <ul>
                        <li>
                            <button
                                type="button"
                                title="Play the bonus card and take this action"
                                onClick={() => {
                                    const action = bonusConfirm;
                                    setBonusConfirm(null);
                                    void submitAction(action);
                                }}
                            >
                                Use {bonusConfirm.requiredBonusCard}
                            </button>
                        </li>
                    </ul>
                    <button type="button" title="Cancel" onClick={() => setBonusConfirm(null)}>
                        Cancel
                    </button>
                </div>
            ) : null}

            {forcedDecision != null ? (
                <ForcedDecisionDialog
                    decision={forcedDecision}
                    onChoose={(action) => void submitAction(action)}
                />
            ) : null}

            {touchdown != null ? (
                <div
                    className={`celebration celebration--td celebration--${touchdown.team.toLowerCase()}`}
                    role="status"
                    data-testid="touchdown-celebration"
                >
                    <p className="celebration__shout">TOUCHDOWN!</p>
                    <p className="celebration__score">
                        {touchdown.homeScore} – {touchdown.awayScore}
                    </p>
                </div>
            ) : null}

            {claimCelebration != null ? (
                <div
                    className={`celebration celebration--challenge celebration--${claimCelebration.team.toLowerCase()}`}
                    role="status"
                    data-testid="challenge-celebration"
                >
                    {claimCardArt != null ? (
                        <img className="celebration__card" src={claimCardArt} alt="" draggable={false} />
                    ) : null}
                    <p className="celebration__eyebrow">Challenge claimed</p>
                    <p className="celebration__shout">{claimCelebration.title}</p>
                    <p className="celebration__points">+{claimCelebration.pointsAwarded}</p>
                    <p className="celebration__score celebration__score--tick" key={`${claimCelebration.homeScore}-${claimCelebration.awayScore}`}>
                        <span className="celebration__score-from">
                            {claimCelebration.fromHomeScore} – {claimCelebration.fromAwayScore}
                        </span>
                        <span className="celebration__score-to">
                            {claimCelebration.homeScore} – {claimCelebration.awayScore}
                        </span>
                    </p>
                </div>
            ) : null}

            {sweepCelebration != null ? (
                <div
                    className={`celebration celebration--sweep celebration--${sweepCelebration.team.toLowerCase()}`}
                    role="status"
                    data-testid="clean-sweep-celebration"
                >
                    <p className="celebration__eyebrow">Bonus</p>
                    <p className="celebration__shout">CLEAN SWEEP!</p>
                    <p className="celebration__points">+{sweepCelebration.pointsAwarded}</p>
                    <p className="celebration__score celebration__score--tick" key={`${sweepCelebration.homeScore}-${sweepCelebration.awayScore}`}>
                        <span className="celebration__score-from">
                            {sweepCelebration.fromHomeScore} – {sweepCelebration.fromAwayScore}
                        </span>
                        <span className="celebration__score-to">
                            {sweepCelebration.homeScore} – {sweepCelebration.awayScore}
                        </span>
                    </p>
                </div>
            ) : null}

            {abilityBanner != null ? (
                <div
                    className={[
                        "ability-scroll",
                        `ability-scroll--${abilityBanner.team.toLowerCase()}`,
                        abilityScrollMinimized
                            ? "ability-scroll--minimized"
                            : abilityScrollPromptActive
                              ? "ability-scroll--docked"
                              : "",
                    ]
                        .filter(Boolean)
                        .join(" ")}
                    role="status"
                    data-testid="ability-banner"
                >
                    {abilityScrollMinimized ? (
                        <div className="ability-scroll__parchment ability-scroll__parchment--chip">
                            <AbilityBadge
                                ability={abilityBanner.ability}
                                size="sm"
                                className="ability-scroll__badge"
                            />
                            <span className="ability-scroll__chip-name">{abilityBanner.label}</span>
                            {bannerDecline != null ? (
                                <button
                                    type="button"
                                    className="ability-scroll__decline ability-scroll__decline--chip"
                                    title={describeActionTooltip(bannerDecline.action)}
                                    data-testid="ability-banner-decline"
                                    onClick={() => void submitAction(bannerDecline.action)}
                                >
                                    {bannerDecline.label}
                                </button>
                            ) : null}
                            <button
                                type="button"
                                className="ability-scroll__toggle"
                                title="Expand ability banner"
                                aria-label="Expand ability banner"
                                data-testid="ability-banner-expand"
                                onClick={() => setAbilityScrollMinimized(false)}
                            >
                                Expand
                            </button>
                        </div>
                    ) : (
                        <>
                            <div className="ability-scroll__rod ability-scroll__rod--top" aria-hidden="true" />
                            <div className="ability-scroll__parchment">
                                <button
                                    type="button"
                                    className="ability-scroll__toggle"
                                    title="Minimize ability banner"
                                    aria-label="Minimize ability banner"
                                    data-testid="ability-banner-minimize"
                                    onClick={() => setAbilityScrollMinimized(true)}
                                >
                                    Minimize
                                </button>
                                <p className="ability-scroll__eyebrow">Special ability</p>
                                <p className="ability-scroll__name">
                                    <AbilityBadge
                                        ability={abilityBanner.ability}
                                        size="md"
                                        className="ability-scroll__badge"
                                    />
                                    {abilityBanner.label}
                                </p>
                                {bannerDecline != null ? (
                                    <button
                                        type="button"
                                        className="ability-scroll__decline"
                                        title={describeActionTooltip(bannerDecline.action)}
                                        data-testid="ability-banner-decline"
                                        onClick={() => void submitAction(bannerDecline.action)}
                                    >
                                        {bannerDecline.label}
                                    </button>
                                ) : null}
                            </div>
                            <div className="ability-scroll__rod ability-scroll__rod--bottom" aria-hidden="true" />
                        </>
                    )}
                </div>
            ) : null}
        </main>
    );
}
