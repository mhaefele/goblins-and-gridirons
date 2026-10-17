package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.api.dto.response.ChallengeTableauDto
import com.mhaefele.gridiron.api.dto.response.MatchGameStateDto
import com.mhaefele.gridiron.api.dto.response.MatchResultDto
import com.mhaefele.gridiron.api.dto.response.MatchResultKind
import com.mhaefele.gridiron.game.action.dto.ChallengeCardDto
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.MatchEndKind

/**
 * Test oracle: fold HUD fields from turn/progress [DomainEvent]s the way a spectator client should —
 * each arm is `thisField = event.thisField` (no phase inferred from kind alone).
 */
fun foldHud(initial: MatchGameStateDto, events: List<DomainEvent>): MatchGameStateDto {
    var hud = initial
    for (event in events) {
        hud = applyHudEvent(hud, event)
    }
    return hud
}

fun applyHudEvent(hud: MatchGameStateDto, event: DomainEvent): MatchGameStateDto =
    when (event) {
        is DomainEvent.TeamActionConsumed -> hud.copy(actionsRemaining = event.actionsRemaining)
        is DomainEvent.PhaseStarted       -> hud.copy(
            turnNumber = event.turnNumber,
            phase = event.phase,
            activeCoach = event.activeCoach,
            actionsRemaining = event.actionsRemaining ?: hud.actionsRemaining,
            challenges = when (event.phase) {
                // End-of-turn cleanup clears claimsThisTurn before TURN_END / next TURN_START.
                com.mhaefele.gridiron.shared.TurnPhase.TURN_END,
                com.mhaefele.gridiron.shared.TurnPhase.TURN_START,
                     -> hud.challenges.copy(claimsThisTurn = 0)
                else -> hud.challenges
            },
        )
        is DomainEvent.MatchEnded -> hud.copy(
            turnNumber = event.turnNumber, phase = event.phase, activeCoach = event.activeCoach,
            result = MatchResultDto(
                kind = when (event.endKind) {
                    MatchEndKind.suddenDeath   -> MatchResultKind.suddenDeath
                    MatchEndKind.pointsVictory -> MatchResultKind.pointsVictory
                },
                winner = event.winner,
            ),
        )
        is DomainEvent.TouchdownScored      -> hud.copy(homeScore = event.homeScore, awayScore = event.awayScore)
        is DomainEvent.ChallengeCardClaimed -> hud.copy(
            homeScore = event.homeScore,
            awayScore = event.awayScore,
            challenges = hud.challenges.withSlot(event.slotIndex, null).copy(claimsThisTurn = event.claimsThisTurn),
        )
        is DomainEvent.CleanSweep                -> hud.copy(homeScore = event.homeScore, awayScore = event.awayScore)
        is DomainEvent.PointsVictoryArmed        -> hud.copy(pointsVictoryTurnsRemaining = event.turnsRemaining)
        is DomainEvent.PointsVictoryCountdown    -> hud.copy(pointsVictoryTurnsRemaining = event.turnsRemaining)
        is DomainEvent.ChallengeTableauRefreshed -> {
            var slots = hud.challenges.activeSlots.toMutableList()
            event.filledSlots.zip(event.filledCards).forEach { (slot, card) ->
                slots[slot] = card
            }
            hud.copy(
                challenges = hud.challenges.copy(
                    activeSlots = slots,
                    deckTop = event.deckTop,
                    deckRemaining = event.deckRemaining,
                ),
            )
        }
        is DomainEvent.ChallengeCardReplaced     -> {
            val slots = hud.challenges.activeSlots.toMutableList()
            slots[event.slotIndex] = event.drawn
            hud.copy(
                challenges = hud.challenges.copy(
                    activeSlots = slots,
                    deckTop = event.deckTop,
                    deckRemaining = event.deckRemaining,
                    discardCount = hud.challenges.discardCount + 1,
                ),
            )
        }
        is DomainEvent.ChallengeHandTrimmed      -> hud.copy(
            challenges = hud.challenges.copy(
                discardCount = hud.challenges.discardCount + event.discardedBonusPlays.size,
            ),
        )
        else -> hud
    }

private fun ChallengeTableauDto.withSlot(index: Int, value: ChallengeCardDto?): ChallengeTableauDto {
    val slots = activeSlots.toMutableList()
    slots[index] = value
    return copy(activeSlots = slots)
}
