package com.mhaefele.gridiron.scenario

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.MatchEndKind

fun formatDomainEvents(events: List<DomainEvent>) =
    events.map { formatDomainEvent(it) }

fun formatDomainEvent(event: DomainEvent): String {
    val line = formatDomainEventBody(event)
    val clock = turnClockLabel(event) ?: return line
    return line.replaceFirst("  ", "  [$clock] ")
}

/** `PLAYER_ACTION·IN_ACTION`, or null when the event was never stamped. */
private fun turnClockLabel(event: DomainEvent): String? {
    val phase = event.turnPhase
    val step = event.turnStep
    if (phase == null && step == null) return null
    return buildString {
        append(phase ?: "?")
        if (step != null) append("·").append(step)
    }
}

private fun formatDomainEventBody(event: DomainEvent): String =
    when (event) {
        is DomainEvent.ActionStarted               -> "  → ${event.actionType} by ${event.playerId.value}${event.playerAt?.let { " at ${it.display()}" } ?: " (dugout)"}"
        is DomainEvent.ActionComplete              -> "  ✓ ${event.actionType} by ${event.playerId.value}${event.playerAt?.let { " at ${it.display()}" } ?: ""}"
        is DomainEvent.DiceRolled                  -> {
            val roll = when {
                event.blockFaces != null -> event.blockFaces.joinToString { it.name } +
                    (event.blockResult?.let { " → $it" } ?: "")
                event.d8 != null         -> "D8=${event.d8}"
                event.d6 != null         -> buildString {
                    append("D6=${event.d6}")
                    event.threshold?.let { append(" vs $it+") }
                    event.modifier?.takeIf { it != 0 }?.let { append(" (${if (it > 0) "+" else ""}$it)") }
                    event.checkResult?.let { append(" $it") }
                }
                else                     -> "?"
            }
            "  🎲 ${event.purpose} $roll"
        }
        is DomainEvent.PlayerMoved                 -> "  ${event.playerId.value} moved ${event.from.display()} → ${event.to.display()}"
        is DomainEvent.PlayerStatusChanged         -> "  ${event.playerId.value} ${event.from} → ${event.to} at ${event.playerAt.display()}"
        is DomainEvent.PlayerDeployedFromReserves  -> "  ${event.playerId.value} deployed to ${event.to.display()}"
        is DomainEvent.PlayerInjured               -> "  ${event.playerId.value} injured at ${event.from.display()} → dugout"
        is DomainEvent.PlayerRemovedAfterTouchdown -> "  ${event.playerId.value} to dugout after TD from ${event.from.display()}"
        is DomainEvent.PlayerPlacedOnPitch         -> "  ${event.playerId.value} placed at ${event.at.display()}"
        is DomainEvent.BallPickedUp                -> "  ${event.playerId.value} picked up ball at ${event.playerAt.display()}"
        is DomainEvent.BallDropped                 -> "  ${event.playerId.value} dropped ball at ${event.playerAt.display()}"
        is DomainEvent.BallThrown                  -> "  throw ${event.from.display()} → ${event.to.display()}"
        is DomainEvent.BallThrowMissed             -> "  throw missed ${event.from.display()} → ${event.to.display()}"
        is DomainEvent.BallBounced                 -> "  bounce ${event.from.display()} → ${event.to.display()}"
        is DomainEvent.BallPlacedLoose             -> "  ball placed loose at ${event.at.display()}"
        is DomainEvent.BallRemovedFromPlay         -> buildString {
            append("  ball removed")
            event.playerId?.let { append(" from ${it.value}") }
            event.at?.let { append(" at ${it.display()}") }
        }
        is DomainEvent.TouchdownScored             -> "  🏈 TOUCHDOWN ${event.team} by ${event.playerId.value} at ${event.at.display()} (HOME ${event.homeScore}–${event.awayScore} AWAY)"
        is DomainEvent.ChallengeTableauRefreshed   -> "  challenge refresh slots ${event.filledSlots} ← ${event.filledCards.map { it.challengeCondition }}"
        is DomainEvent.ChallengeCardReplaced       -> "  challenge replace slot ${event.slotIndex}: discarded ${event.discardedClaimCondition}, drew ${event.drawn?.challengeCondition}"
        is DomainEvent.ChallengeCardClaimed        -> "  challenge claimed ${event.challengeTitle} (+${event.pointsAwarded}) by ${event.team}"
        is DomainEvent.CleanSweep                  -> "  Clean Sweep +${event.pointsAwarded} for ${event.team}"
        is DomainEvent.ChallengeHandTrimmed        -> "  challenge hand trimmed ${event.team} discarded ${event.discardedBonusPlays} → ${event.handSize}"
        is DomainEvent.BonusPlayed                 -> "  bonus played ${event.bonusPlay} by ${event.team}"
        is DomainEvent.BonusReturned               -> "  bonus returned ${event.bonusPlay} to ${event.team} hand"
        is DomainEvent.PointsVictoryArmed          -> "  Points Victory armed — ${event.turnsRemaining} turns remaining"
        is DomainEvent.PointsVictoryCountdown      -> "  Points Victory countdown → ${event.turnsRemaining} turns remaining"
        is DomainEvent.PlayerTurnEnded             -> "  ${event.playerId.value} turn ended (no further actions this activation)"
        is DomainEvent.TeamActionConsumed          -> "  team action consumed → ${event.actionsRemaining} remaining"
        is DomainEvent.PhaseStarted                -> {
            val budget = event.actionsRemaining?.let { ", $it actions" }.orEmpty()
            "  phase ${event.phase} started (turn ${event.turnNumber}, ${event.activeCoach}$budget)"
        }
        is DomainEvent.MatchEnded                  -> {
            val how = when (event.endKind) {
                MatchEndKind.suddenDeath   -> "sudden death"
                MatchEndKind.pointsVictory -> "points victory"
            }
            val winner = event.winner?.name ?: "draw"
            "  🏁 match ended ($how, $winner, turn ${event.turnNumber}, ${event.phase}, coach ${event.activeCoach})"
        }
        is DomainEvent.InputRequired.PlayerAction  -> {
            val ability = event.ability?.let { " ($it)" }.orEmpty()
            val player = event.playerId?.let { " (${it.value})" }.orEmpty()
            val bonusPlay = event.bonusPlay?.let { " $it" }.orEmpty()
            "  ⏳ player action required from ${event.team}${player}${ability}${bonusPlay} — ${event.decisionType}"
        }
    }
