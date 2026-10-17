package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.THROW_RULER_MAX
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

@Component
class ThrowToEndzoneTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.THROW_TO_ENDZONE

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val scoringEndZone = trainerSide.opposite()
        val receiver = setup.placeRandomReserve(trainerSide, playerFilter = setup::canHoldBall, setup.inEndZone(scoringEndZone))
        val thrower = placeThrowerAimingAtReceiver(setup, trainerSide, scoringEndZone, receiver)
        setup.scatterRemainingReserves(trainerSide.opposite(), maxPlayers = difficulty, avoid = thrower.adjacentSquares())
    }

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val endZone = trainerSide.opposite()
        val board = state.board
        require(
            legals.filterIsInstance<LegalAction.Throw>().any { throwAction ->
                val square = board.squareAt(throwAction.target.destination)
                square.isEndZone(endZone) &&
                    square.playerId?.let { board.players[it]?.team } == trainerSide
            },
        ) { "THROW_TO_ENDZONE: no legal throw targets a trainer player in the $endZone end zone" }
        val thrower = trainerBallCarrier(state, trainerSide)
        val throwerRuns = legals.filterIsInstance<LegalAction.Run>().filter { it.playerId == thrower.id }
        require(!runsIntoEndZone(throwerRuns, board, endZone)) { "THROW_TO_ENDZONE: thrower can still run into the $endZone end zone" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isTrainerBallCarrierThrow(action, state, trainerSide)

    override fun isPassingOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isThrowToScoringEndZone(action, state.board, trainerSide)

    private fun placeThrowerAimingAtReceiver(setup: TrainingBoardBuilder, trainerSide: TeamSide, scoringEndZone: TeamSide, receiver: PlacedPlayer): PlacedPlayer {
        val receiverSquare = receiver.square
        val axisThrowRange = THROW_RULER_MAX.toInt()
        return setup.placeRandomPlayerWithBall(
            trainerSide,
            playerFilter = { setup.isFixedMove(it) && setup.fixedMove(it) < axisThrowRange },
            squareFilter = { player ->
                setup.allOf(
                    setup.withinRowsOf(scoringEndZone, (setup.fixedMove(player) + 1)..axisThrowRange, excludeEndZone = true),
                    { square -> square.coordinate.x == receiverSquare.coordinate.x },
                )
            },
        )
    }
}
