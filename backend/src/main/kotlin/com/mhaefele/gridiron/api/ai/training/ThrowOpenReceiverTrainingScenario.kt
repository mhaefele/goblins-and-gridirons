package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.THROW_RULER_SHORT_MAX
import com.mhaefele.gridiron.game.action.utils.ThrowRulerOffsets
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.TurnAction
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import org.springframework.stereotype.Component

@Component
class ThrowOpenReceiverTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.THROW_OPEN_RECEIVER

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val scoringEndZone = trainerSide.opposite()
        val thrower = setup.placeRandomPlayerWithBall(trainerSide, setup.withinRowsOf(scoringEndZone, 1..THROW_RULER_SHORT_MAX.toInt(), excludeEndZone = true))
        val shortSquares = setup.board.allSquares().filter {
            setup.inEndZone(scoringEndZone)(it) &&
                ThrowRulerOffsets.short.any { o -> o.dx == it.coordinate.x - thrower.coordinate.x && o.dy == it.coordinate.y - thrower.coordinate.y } &&
                setup.manhattanDistance(thrower.square, it) >= 2
        }
        check(shortSquares.size >= 2) { "THROW_OPEN_RECEIVER: need ≥2 short non-hand-off end-zone squares from ${thrower.coordinate}, had ${shortSquares.size}" }
        val (openSquare, markedSquare) = shortSquares.shuffled().take(2).let { it[0] to it[1] }
        val openReceiver = setup.placeRandomReserve(trainerSide, listOf(openSquare), playerFilter = setup::canHoldBall)
        val markedReceiver = setup.placeRandomReserve(trainerSide, listOf(markedSquare), playerFilter = setup::canHoldBall)
        setup.placeRandomReserve(
            trainerSide.opposite(),
            setup.emptyAdjacentSquares(markedReceiver.square, excluding = listOf(thrower.square)) { !it.isEndZone(scoringEndZone) },
        )
        val endZones = setup.board.allSquares().filter { it.isEndZone(scoringEndZone) || it.isEndZone(trainerSide) }
        val clearAround = thrower.adjacentSquares() + openReceiver.adjacentSquares() + markedReceiver.adjacentSquares() + endZones
        setup.scatterRemainingReserves(trainerSide.opposite(), maxPlayers = difficulty, avoid = clearAround)
    }

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState {
        val carrier = trainerBallCarrier(state, trainerSide)
        return state.copy(currentTurnState = state.currentTurnState.copy(actionsThisTurn = listOf(TurnAction(carrier.id, ActionType.RUN))))
    }

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val thrower = trainerBallCarrier(state, trainerSide)
        val throws = legals.filterIsInstance<LegalAction.Throw>().filter { it.playerId == thrower.id && it.target.range == ThrowRange.SHORT && isOpponentEndZone(state.board, it.target.destination, trainerSide) }
        require(throws.any { !it.target.obstructed }) { "THROW_OPEN_RECEIVER: no clear short throw into the scoring end zone" }
        require(throws.any { it.target.obstructed }) { "THROW_OPEN_RECEIVER: no obstructed short throw into the scoring end zone" }
        require(legals.filterIsInstance<LegalAction.Run>().none { it.playerId == thrower.id }) { "THROW_OPEN_RECEIVER: thrower ${thrower.id} still has a legal Run" }
    }

    override fun isSoftMaskFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isTrainerBallCarrierThrow(action, state, trainerSide)

    override fun isPassingFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Throw) return false
        if (action.target.range != ThrowRange.SHORT) return false
        if (!isOpponentEndZone(state.board, action.target.destination, trainerSide)) return false
        return standingStatus(action.target.destination, state.board) == StandingStatus.OPEN
    }
}
