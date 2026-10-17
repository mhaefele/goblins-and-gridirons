import type { DomainEvent, PlayerDecisionType } from "../api/client";
import { formatPlayerDecisionType, parsePlayerAbility } from "../render/actionVisuals";

function coord(at: { x: number; y: number }): string {
    return `(${at.x}, ${at.y})`;
}

function teamLabel(team: "HOME" | "AWAY"): string {
    return team === "HOME" ? "Home" : "Away";
}

function decisionTypeFromWire(raw: string | undefined): PlayerDecisionType | null {
    switch (raw) {
        case "REGULAR_PLAYER_ACTION":
        case "INITIAL_PLAYER_PLACEMENT":
        case "EMERGENCY_RESERVES":
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
            return null;
    }
}

export function formatDomainEvent(event: DomainEvent): string {
    switch (event.kind) {
        case "actionStarted":
            return event.playerAt
                ? `${event.playerId} started ${event.actionType} at ${coord(event.playerAt)}`
                : `${event.playerId} started ${event.actionType}`;
        case "actionComplete":
            return `${event.playerId} finished ${event.actionType}`;
        case "diceRolled": {
            if (event.purpose === "FOUL" && "foulResult" in event && event.foulResult != null) {
                const face = event.blockResult != null ? ` (${event.blockResult})` : "";
                return `Foul — ${event.foulResult}${face}`;
            }
            if (event.blockResult != null || event.blockFaces != null) {
                const result = event.blockResult ?? event.blockFaces!.join(", ");
                const pool = event.blockFaces != null && event.blockFaces.length > 1 ? ` [${event.blockFaces.join(", ")}]` : "";
                return `Block — ${result}${pool}`;
            }
            const roll = event.d8 != null ? `D8=${event.d8}` : event.d6 != null ? `D6=${event.d6}` : "?";
            const check = event.checkResult != null ? ` (${event.checkResult})` : "";
            return `${event.purpose}: ${roll}${check}`;
        }
        case "playerMoved":
            return `${event.playerId} moved ${coord(event.from)} → ${coord(event.to)}`;
        case "playerStatusChanged":
            return `${event.playerId} ${event.from} → ${event.to}`;
        case "playerDeployedFromReserves":
            return `${event.playerId} deployed to ${coord(event.to)}`;
        case "playerInjured":
            return `${event.playerId} injured at ${coord(event.from)}`;
        case "playerRemovedAfterTouchdown":
            return `${event.playerId} removed after touchdown`;
        case "playerPlacedOnPitch":
            return `${event.playerId} placed on pitch at ${coord(event.at)}`;
        case "ballPickedUp":
            return `${event.playerId} picked up ball at ${coord(event.playerAt)}`;
        case "ballDropped":
            return `${event.playerId} dropped ball at ${coord(event.playerAt)}`;
        case "ballThrown":
            return `Ball thrown ${coord(event.from)} → ${coord(event.to)}`;
        case "ballThrowMissed":
            return `Throw missed — ball at ${coord(event.to)}`;
        case "ballBounced":
            return `Ball bounced to ${coord(event.to)}`;
        case "ballPlacedLoose":
            return `Ball placed loose at ${coord(event.at)}`;
        case "ballRemovedFromPlay":
            return "Ball removed from play";
        case "touchdownScored":
            return `${teamLabel(event.team)} touchdown — score ${event.homeScore}–${event.awayScore}`;
        case "playerTurnEnded":
            return `${event.playerId} turn ended`;
        case "teamActionConsumed":
            return `Team action consumed (${event.actionsRemaining} left)`;
        case "phaseStarted": {
            const budget =
                event.actionsRemaining != null ? ` (${event.actionsRemaining} actions)` : "";
            return `Phase ${event.phase} — turn ${event.turnNumber}, ${teamLabel(event.activeCoach)}${budget}`;
        }
        case "matchEnded": {
            const outcome = event.winner ? `${teamLabel(event.winner)} wins` : "Draw";
            return `Match ended — ${outcome}`;
        }
        case "playerActionRequired": {
            const wire = event as { decisionType?: string; ability?: string | null };
            const decision = decisionTypeFromWire(wire.decisionType);
            const detail = decision
                ? formatPlayerDecisionType(decision, parsePlayerAbility(wire.ability))
                : "an action";
            return `${teamLabel(event.team)} — ${detail}`;
        }
        case "bonusPlayed":
            return `${teamLabel(event.team)} played ${(event as { bonusPlay: string }).bonusPlay}`;
        case "bonusReturned":
            return `${teamLabel(event.team)} returned ${(event as { bonusPlay: string }).bonusPlay} to hand`;
        case "challengeCardClaimed":
            return `${teamLabel(event.team)} claimed ${event.challengeTitle} (+${event.pointsAwarded}) — ${event.homeScore}–${event.awayScore}`;
        case "cleanSweep":
            return `${teamLabel(event.team)} clean sweep (+${event.pointsAwarded}) — ${event.homeScore}–${event.awayScore}`;
        case "challengeTableauRefreshed":
        case "challengeCardReplaced":
        case "challengeHandTrimmed":
            return event.kind;
        case "pointsVictoryArmed":
            return `Last Challenge Card dealt — ${event.turnsRemaining} coach turns left`;
        case "pointsVictoryCountdown":
            return `Points Victory countdown — ${event.turnsRemaining} coach turn${event.turnsRemaining === 1 ? "" : "s"} left`;
        default: {
            const kind = (event as { kind?: string }).kind;
            return kind ?? "event";
        }
    }
}
