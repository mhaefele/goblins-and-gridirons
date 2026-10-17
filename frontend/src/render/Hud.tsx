import type { CoachControl, MatchGameStateDto, PlayerAbility, PlayerDecisionType } from "../api/client";
import { formatPlayerDecisionType } from "./actionVisuals";
import ChallengeTableau from "./ChallengeTableau";
import { formatMatchResult } from "./matchResultCopy";
import { isSharedHotseat, seatIsYou, type CoachSeat } from "../state/seatIdentity";
import type { ConnectionPhase } from "../state/store";

type HudProps = {
    gameState: MatchGameStateDto;
    connectionPhase: ConnectionPhase;
    interactive: boolean;
    localCoach: CoachSeat;
    /** From `?coach=` — locks this browser to one seat even when both are HUMAN. */
    preferredLocalCoach?: CoachSeat | null;
    pendingDecisionType?: PlayerDecisionType | null | undefined;
    pendingAbility?: PlayerAbility | null | undefined;
    /** Remaining Move squares for RUN_CONTINUE (from legal Run paths). */
    remainingMoveSquares?: number | null | undefined;
    /** Pulse the scoring seat after a challenge claim folds. */
    scorePulseTeam?: "HOME" | "AWAY" | null;
    /** Tableau slot being claimed (highlighted during celebration FX). */
    claimingSlotIndex?: number | null;
};

function formatCoach(coach: MatchGameStateDto["activeCoach"]): string {
    return coach === "HOME" ? "Home" : "Away";
}

function seatControl(gameState: MatchGameStateDto, team: "HOME" | "AWAY"): CoachControl {
    return team === "HOME" ? gameState.homeControlledBy : gameState.awayControlledBy;
}

function formatResult(result: NonNullable<MatchGameStateDto["result"]>): string {
    return formatMatchResult(result);
}

function connectionLabel(phase: ConnectionPhase): string {
    switch (phase) {
        case "connecting":
            return "Connecting";
        case "hydrating":
            return "Loading";
        case "awaitingInput":
            return "Live";
        case "spectating":
            return "Live";
        case "unavailable":
            return "Unavailable";
        default: {
            const _exhaustive: never = phase;
            return _exhaustive;
        }
    }
}

function ActionPips({ remaining }: { remaining: number }) {
    const maxPips = Math.max(remaining, 3);
    return (
        <span
            className="hud__pips"
            aria-label={`${remaining} actions remaining`}
            title={`${remaining} team action${remaining === 1 ? "" : "s"} left this turn`}
        >
            {Array.from({ length: maxPips }, (_, index) => (
                <span
                    key={index}
                    className={`hud__pip${index < remaining ? " hud__pip--lit" : ""}`}
                    aria-hidden="true"
                />
            ))}
        </span>
    );
}

function SeatBadge({
    team,
    control,
    you,
    hotseat,
}: {
    team: "HOME" | "AWAY";
    control: CoachControl;
    you: boolean;
    hotseat: boolean;
}) {
    const label = team === "HOME" ? "Home" : "Away";
    const isCpu = control === "COMPUTER";
    const chipLabel = isCpu ? "CPU" : you ? "You" : hotseat ? "Hotseat" : "Human";
    const chipAria = isCpu ? "Computer" : you ? "You" : hotseat ? "Shared hotseat" : "Human";
    return (
        <span
            className={`hud__seat hud__seat--${team.toLowerCase()}${isCpu ? " hud__seat--cpu" : ""}${you ? " hud__seat--you" : ""}`}
            title={
                isCpu
                    ? `${label} controlled by computer`
                    : you
                      ? `${label} — your seat`
                      : hotseat
                        ? `${label} — shared hotseat on this browser`
                        : `${label} controlled by human`
            }
        >
            <span className="hud__seat-label">{label}</span>
            {isCpu ? (
                <span className="hud__seat-chip" aria-label={chipAria}>
                    <CpuGlyph />
                    {chipLabel}
                </span>
            ) : (
                <span className="hud__seat-chip" aria-label={chipAria}>
                    <HumanGlyph />
                    {chipLabel}
                </span>
            )}
        </span>
    );
}

function HumanGlyph() {
    return (
        <svg className="hud__seat-icon" viewBox="0 0 16 16" aria-hidden="true">
            <circle cx="8" cy="5" r="2.5" fill="currentColor" />
            <path d="M3.5 13.5c.8-2.6 2.4-3.8 4.5-3.8s3.7 1.2 4.5 3.8" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
    );
}

function CpuGlyph() {
    return (
        <svg className="hud__seat-icon" viewBox="0 0 16 16" aria-hidden="true">
            <rect x="4" y="4" width="8" height="8" rx="1.2" fill="none" stroke="currentColor" strokeWidth="1.5" />
            <rect x="6.5" y="6.5" width="3" height="3" fill="currentColor" />
            <path d="M6 2.5v1.5M10 2.5v1.5M6 12v1.5M10 12v1.5M2.5 6H4M2.5 10H4M12 6h1.5M12 10h1.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
    );
}

export default function Hud({
    gameState,
    connectionPhase,
    interactive,
    localCoach,
    preferredLocalCoach = null,
    pendingDecisionType,
    pendingAbility,
    remainingMoveSquares,
    scorePulseTeam = null,
    claimingSlotIndex = null,
}: HudProps) {
    const activeIsCpu = seatControl(gameState, gameState.activeCoach) === "COMPUTER";
    const hotseat = isSharedHotseat(gameState, preferredLocalCoach);
    const pvRemaining = gameState.pointsVictoryTurnsRemaining;
    const lastCoachTurn =
        gameState.result == null && pvRemaining != null && pvRemaining > 0;
    const lastTurnTitle =
        pvRemaining === 1
            ? "Final coach turn — last Challenge Card already dealt; Points Victory after this turn"
            : "Last Challenge Card dealt — this is the active coach's last turn (opponent gets one after)";
    const turnBanner =
        gameState.result != null
            ? formatResult(gameState.result)
            : interactive
              ? `${formatCoach(gameState.activeCoach)} to act`
              : activeIsCpu
                ? `${formatCoach(gameState.activeCoach)} (CPU) thinking…`
                : `Waiting for ${formatCoach(gameState.activeCoach)}`;
    const decisionHint =
        interactive && pendingDecisionType != null
            ? formatPlayerDecisionType(pendingDecisionType, pendingAbility, remainingMoveSquares)
            : null;

    return (
        <header className="hud" role="region" aria-label="Match HUD">
            <div className="hud__banner" role="status">
                <div className="hud__turn-row">
                    <span
                        className={`hud__turn${interactive ? " hud__turn--yours" : " hud__turn--waiting"}`}
                        title={
                            interactive
                                ? decisionHint ??
                                  (hotseat
                                      ? `${formatCoach(gameState.activeCoach)} to act — shared hotseat`
                                      : "Your turn — select a highlighted player and choose an action")
                                : activeIsCpu
                                  ? `${formatCoach(gameState.activeCoach)} computer coach is choosing`
                                  : `Waiting for ${formatCoach(gameState.activeCoach)} to finish their action`
                        }
                    >
                        {turnBanner}
                    </span>
                    {lastCoachTurn ? (
                        <span
                            className={`hud__last-turn${pvRemaining === 1 ? " hud__last-turn--final" : ""}`}
                            title={lastTurnTitle}
                            role="status"
                        >
                            {pvRemaining === 1 ? "Final turn" : "Last turn"}
                        </span>
                    ) : null}
                </div>
                <span className="hud__actions" title="Team actions remaining this coach turn">
                    Actions <ActionPips remaining={gameState.actionsRemaining} />
                </span>
                <span
                    className="hud__connection"
                    title={
                        interactive
                            ? `Connected (${connectionPhase}) — you may submit actions`
                            : `Connected (${connectionPhase}) — spectating until it is your input window`
                    }
                >
                    {connectionLabel(connectionPhase)}
                    {interactive ? " · interactive" : " · spectating"}
                </span>
            </div>
            <div className="hud__scores">
                <span
                    className={`hud__score hud__score--home${scorePulseTeam === "HOME" ? " hud__score--pulse" : ""}`}
                    title="Home team points"
                >
                    Home {gameState.homeScore}
                </span>
                <span className="hud__score-sep">—</span>
                <span
                    className={`hud__score hud__score--away${scorePulseTeam === "AWAY" ? " hud__score--pulse" : ""}`}
                    title="Away team points"
                >
                    Away {gameState.awayScore}
                </span>
            </div>
            <div className="hud__seats" aria-label="Seat control">
                <SeatBadge
                    team="HOME"
                    control={gameState.homeControlledBy}
                    you={seatIsYou(gameState, "HOME", localCoach, preferredLocalCoach)}
                    hotseat={hotseat}
                />
                <SeatBadge
                    team="AWAY"
                    control={gameState.awayControlledBy}
                    you={seatIsYou(gameState, "AWAY", localCoach, preferredLocalCoach)}
                    hotseat={hotseat}
                />
            </div>
            <div className="hud__meta">
                <span title="Current turn number (coach activations)">Turn {gameState.turnNumber}</span>
            </div>
            {gameState.challenges != null ? (
                <ChallengeTableau
                    challenges={gameState.challenges}
                    turnNumber={gameState.turnNumber}
                    pointsVictoryTurnsRemaining={gameState.pointsVictoryTurnsRemaining}
                    claimingSlotIndex={claimingSlotIndex}
                />
            ) : null}
            {gameState.result ? (
                <p className="hud__result" role="status">
                    {formatResult(gameState.result)}
                </p>
            ) : null}
        </header>
    );
}
