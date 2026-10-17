package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.THROW_RULER_SHORT_MAX
import com.mhaefele.gridiron.game.action.utils.ThrowRulerOffsets
import com.mhaefele.gridiron.game.action.utils.rulerDistance
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.TurnAction
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import org.springframework.stereotype.Component

@Component
class ThrowCloserRangeTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.THROW_CLOSER_RANGE

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val scoringEndZone = trainerSide.opposite()
        val thrower = setup.placeRandomPlayerWithBall(trainerSide, setup.withinRowsOf(scoringEndZone, 1..THROW_RULER_SHORT_MAX.toInt(), excludeEndZone = true))
        val closerSquares = setup.board.allSquares().filter { setup.inEndZone(scoringEndZone)(it) && rulerDistance(thrower.coordinate, it.coordinate) <= THROW_RULER_SHORT_MAX }
        check(closerSquares.isNotEmpty()) { "THROW_CLOSER_RANGE: no hand-off/short end-zone square from ${thrower.coordinate}" }
        val closer = setup.placeRandomReserve(trainerSide, closerSquares, playerFilter = setup::canHoldBall)
        val longSquares = setup.board.allSquares().filter { setup.inEndZone(scoringEndZone)(it) && ThrowRulerOffsets.long.any { o -> o.dx == it.coordinate.x - thrower.coordinate.x && o.dy == it.coordinate.y - thrower.coordinate.y } }
        check(longSquares.isNotEmpty()) { "THROW_CLOSER_RANGE: no long-range end-zone square from ${thrower.coordinate}" }
        val longReceiver = setup.placeRandomReserve(trainerSide, longSquares, playerFilter = setup::canHoldBall)
        val endZones = setup.board.allSquares().filter { it.isEndZone(scoringEndZone) || it.isEndZone(trainerSide) }
        val clearAround = thrower.adjacentSquares() + closer.adjacentSquares() + longReceiver.adjacentSquares() + endZones
        setup.scatterRemainingReserves(trainerSide.opposite(), maxPlayers = difficulty, avoid = clearAround)
    }

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState {
        val carrier = trainerBallCarrier(state, trainerSide)
        return state.copy(currentTurnState = state.currentTurnState.copy(actionsThisTurn = listOf(TurnAction(carrier.id, ActionType.RUN))))
    }

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val thrower = trainerBallCarrier(state, trainerSide)
        val throws = legals.filterIsInstance<LegalAction.Throw>().filter { it.playerId == thrower.id }
        require(throws.any { it.target.range != ThrowRange.LONG && isOpponentEndZone(state.board, it.target.destination, trainerSide) }) { "THROW_CLOSER_RANGE: no legal hand-off/short throw into the scoring end zone" }
        require(throws.any { it.target.range == ThrowRange.LONG && isOpponentEndZone(state.board, it.target.destination, trainerSide) }) { "THROW_CLOSER_RANGE: no legal long throw into the scoring end zone" }
        require(legals.filterIsInstance<LegalAction.Run>().none { it.playerId == thrower.id }) { "THROW_CLOSER_RANGE: thrower ${thrower.id} still has a legal Run" }
    }

    override fun isSoftMaskFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isTrainerBallCarrierThrow(action, state, trainerSide)

    override fun isPassingFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Throw) return false
        if (!isOpponentEndZone(state.board, action.target.destination, trainerSide)) return false
        return action.target.range == ThrowRange.HAND_OFF || action.target.range == ThrowRange.SHORT
    }
}
