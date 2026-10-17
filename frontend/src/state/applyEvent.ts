import type {
    BoardView,
} from "./boardView";
import type {
    ChallengeCardDto,
    Coordinate,
    DomainEvent,
    MatchGameStateDto,
    PieceSnapshotDto,
    PlayerId,
    PlayerPieceDto,
    StoredDomainEvent,
} from "../api/client";

function coordKey(at: Coordinate): string {
    return `${at.x},${at.y}`;
}

function sortedDistinct(coords: Coordinate[]): Coordinate[] {
    const seen = new Set<string>();
    const out: Coordinate[] = [];
    for (const at of coords) {
        const key = coordKey(at);
        if (seen.has(key)) continue;
        seen.add(key);
        out.push(at);
    }
    return out.sort((a, b) => a.x - b.x || a.y - b.y);
}

function updatePlayer(
    snapshot: PieceSnapshotDto,
    playerId: PlayerId,
    transform: (player: PlayerPieceDto) => PlayerPieceDto,
): PieceSnapshotDto {
    return {
        ...snapshot,
        players: snapshot.players.map((player) => (player.id === playerId ? transform(player) : player)),
    };
}

function withLooseBallAt(snapshot: PieceSnapshotDto, at: Coordinate): PieceSnapshotDto {
    return { ...snapshot, looseBallLocations: sortedDistinct([...snapshot.looseBallLocations, at]) };
}

function withoutLooseBallAt(snapshot: PieceSnapshotDto, at: Coordinate): PieceSnapshotDto {
    const key = coordKey(at);
    return {
        ...snapshot,
        looseBallLocations: snapshot.looseBallLocations.filter((coord) => coordKey(coord) !== key),
    };
}

function clearHoldsBallAt(snapshot: PieceSnapshotDto, at: Coordinate): PieceSnapshotDto {
    const key = coordKey(at);
    return {
        ...snapshot,
        players: snapshot.players.map((player) =>
            player.at != null && coordKey(player.at) === key ? { ...player, holdsBall: false } : player,
        ),
    };
}

function applyPiecePatch(pieces: PieceSnapshotDto, event: DomainEvent): PieceSnapshotDto {
    switch (event.kind) {
        case "actionStarted":
        case "actionComplete":
        case "diceRolled":
        case "touchdownScored":
        case "playerTurnEnded":
        case "teamActionConsumed":
        case "phaseStarted":
        case "matchEnded":
        case "playerActionRequired":
            return pieces;

        case "playerMoved":
            return updatePlayer(pieces, event.playerId, (player) => ({ ...player, at: event.to }));

        case "playerStatusChanged":
            return updatePlayer(pieces, event.playerId, (player) => ({ ...player, status: event.to }));

        case "playerDeployedFromReserves":
            return updatePlayer(pieces, event.playerId, (player) => ({
                ...player,
                status: "STANDING",
                at: event.to,
            }));

        case "playerInjured":
            return updatePlayer(pieces, event.playerId, (player) => ({
                ...player,
                status: "RESERVES",
                at: null,
            }));

        case "playerRemovedAfterTouchdown":
            return updatePlayer(pieces, event.playerId, (player) => ({
                ...player,
                status: "RESERVES",
                at: null,
            }));

        case "playerPlacedOnPitch":
            return updatePlayer(pieces, event.playerId, (player) => ({
                ...player,
                status: "STANDING",
                at: event.at,
            }));

        case "ballPickedUp":
            return withoutLooseBallAt(
                updatePlayer(pieces, event.playerId, (player) => ({ ...player, holdsBall: true })),
                event.playerAt,
            );

        case "ballDropped":
            return withLooseBallAt(
                updatePlayer(pieces, event.playerId, (player) => ({ ...player, holdsBall: false })),
                event.playerAt,
            );

        case "ballThrown":
            return {
                ...pieces,
                players: pieces.players.map((player) => {
                    if (player.at != null && coordKey(player.at) === coordKey(event.from)) {
                        return { ...player, holdsBall: false };
                    }
                    if (player.at != null && coordKey(player.at) === coordKey(event.to)) {
                        return { ...player, holdsBall: true };
                    }
                    return player;
                }),
            };

        case "ballThrowMissed":
            return withLooseBallAt(
                withoutLooseBallAt(clearHoldsBallAt(pieces, event.from), event.from),
                event.to,
            );

        case "ballBounced":
            if (coordKey(event.from) === coordKey(event.to)) return pieces;
            return withLooseBallAt(withoutLooseBallAt(pieces, event.from), event.to);

        case "ballPlacedLoose":
            return withLooseBallAt(pieces, event.at);

        case "ballRemovedFromPlay": {
            let patched = pieces;
            if (event.playerId != null) {
                patched = updatePlayer(patched, event.playerId, (player) => ({ ...player, holdsBall: false }));
            }
            if (event.at != null) {
                patched = withoutLooseBallAt(patched, event.at);
            }
            return patched;
        }

        default:
            // Unknown / future kinds (D1.B challenge events) — no piece patch.
            return pieces;
    }
}

function applyHudPatch(gameState: MatchGameStateDto, event: DomainEvent): MatchGameStateDto {
    switch (event.kind) {
        case "teamActionConsumed":
            return { ...gameState, actionsRemaining: event.actionsRemaining };

        case "phaseStarted": {
            const challenges = gameState.challenges;
            // End-of-turn cleanup clears claimsThisTurn before TURN_END / next TURN_START.
            const clearClaims = event.phase === "TURN_END" || event.phase === "TURN_START";
            return {
                ...gameState,
                turnNumber: event.turnNumber,
                phase: event.phase,
                activeCoach: event.activeCoach,
                ...(event.actionsRemaining != null ? { actionsRemaining: event.actionsRemaining } : {}),
                ...(challenges != null && clearClaims
                    ? { challenges: { ...challenges, claimsThisTurn: 0 } }
                    : {}),
            };
        }

        case "matchEnded": {
            const endKind =
                "endKind" in event
                    ? (event as { endKind: "suddenDeath" | "pointsVictory" }).endKind
                    : (event as { bySuddenDeath?: boolean }).bySuddenDeath
                      ? "suddenDeath"
                      : "pointsVictory";
            return {
                ...gameState,
                turnNumber: event.turnNumber,
                phase: event.phase,
                activeCoach: event.activeCoach,
                result: {
                    kind: endKind,
                    winner: event.winner ?? null,
                },
            };
        }

        case "touchdownScored":
            return {
                ...gameState,
                homeScore: event.homeScore,
                awayScore: event.awayScore,
            };

        default: {
            // Challenge / Points Victory events until OpenAPI regen — fold when fields are present.
            const e = event as {
                kind?: string;
                homeScore?: number;
                awayScore?: number;
                turnsRemaining?: number;
                slotIndex?: number;
                claimsThisTurn?: number;
                filledSlots?: number[];
                filledCards?: ChallengeCardDto[];
                deckTop?: ChallengeCardDto | null;
                deckRemaining?: number;
                drawn?: ChallengeCardDto | null;
                discardedBonusPlays?: string[];
            };
            if (
                e.kind === "challengeCardClaimed" &&
                typeof e.homeScore === "number" &&
                typeof e.awayScore === "number"
            ) {
                const challenges = gameState.challenges;
                if (challenges == null || typeof e.slotIndex !== "number") {
                    return { ...gameState, homeScore: e.homeScore, awayScore: e.awayScore };
                }
                const activeSlots = challenges.activeSlots.map((slot, index) =>
                    index === e.slotIndex ? null : slot,
                );
                return {
                    ...gameState,
                    homeScore: e.homeScore,
                    awayScore: e.awayScore,
                    challenges: {
                        ...challenges,
                        activeSlots,
                        claimsThisTurn:
                            typeof e.claimsThisTurn === "number" ? e.claimsThisTurn : challenges.claimsThisTurn,
                    },
                };
            }
            if (
                e.kind === "challengeTableauRefreshed" &&
                Array.isArray(e.filledSlots) &&
                Array.isArray(e.filledCards)
            ) {
                const challenges = gameState.challenges;
                if (challenges == null) return gameState;
                const activeSlots = [...challenges.activeSlots];
                for (let i = 0; i < e.filledSlots.length; i++) {
                    const slot = e.filledSlots[i];
                    const card = e.filledCards[i];
                    if (typeof slot === "number" && card != null) {
                        activeSlots[slot] = card;
                    }
                }
                return {
                    ...gameState,
                    challenges: {
                        ...challenges,
                        activeSlots,
                        deckTop: e.deckTop ?? null,
                        deckRemaining:
                            typeof e.deckRemaining === "number" ? e.deckRemaining : challenges.deckRemaining,
                    },
                };
            }
            if (e.kind === "challengeCardReplaced" && typeof e.slotIndex === "number") {
                const challenges = gameState.challenges;
                if (challenges == null) return gameState;
                const activeSlots = [...challenges.activeSlots];
                activeSlots[e.slotIndex] = e.drawn ?? null;
                return {
                    ...gameState,
                    challenges: {
                        ...challenges,
                        activeSlots,
                        deckTop: e.deckTop ?? null,
                        deckRemaining:
                            typeof e.deckRemaining === "number" ? e.deckRemaining : challenges.deckRemaining,
                        discardCount: challenges.discardCount + 1,
                    },
                };
            }
            if (e.kind === "challengeHandTrimmed" && Array.isArray(e.discardedBonusPlays)) {
                const challenges = gameState.challenges;
                if (challenges == null) return gameState;
                return {
                    ...gameState,
                    challenges: {
                        ...challenges,
                        discardCount: challenges.discardCount + e.discardedBonusPlays.length,
                    },
                };
            }
            if (
                e.kind === "cleanSweep" &&
                typeof e.homeScore === "number" &&
                typeof e.awayScore === "number"
            ) {
                return { ...gameState, homeScore: e.homeScore, awayScore: e.awayScore };
            }
            if (
                (e.kind === "pointsVictoryArmed" || e.kind === "pointsVictoryCountdown") &&
                typeof e.turnsRemaining === "number"
            ) {
                return { ...gameState, pointsVictoryTurnsRemaining: e.turnsRemaining };
            }
            return gameState;
        }
    }
}

export function applyEvent(view: BoardView, event: DomainEvent): BoardView {
    return {
        ...view,
        pieces: applyPiecePatch(view.pieces, event),
        gameState: applyHudPatch(view.gameState, event),
    };
}

export function applyStoredEvent(view: BoardView, stored: StoredDomainEvent): BoardView {
    return { ...applyEvent(view, stored.event), lastEventId: stored.id.domainId };
}

export function applyStreamEvent(view: BoardView, eventId: number, event: DomainEvent): BoardView {
    return { ...applyEvent(view, event), lastEventId: eventId };
}
