package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

@Component
class TdRunTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.TD_RUN

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val scoringEndZone = trainerSide.opposite()
        val carrier = setup.placeRandomPlayerWithBall(
            trainerSide,
            playerFilter = setup::isFixedMove,
            squareFilter = setup.withinMoveOf(scoringEndZone, excludeEndZone = true),
        )
        val tdSquares = setup.squaresAlongRunPaths(setup.runPaths(carrier.player, setup.runPathEndsInEndZone(scoringEndZone)))
        setup.markOppositionScatterAvoid(*tdSquares.toTypedArray())
        val avoid = carrier.adjacentSquares() + tdSquares
        scatterDifficultyExtras(setup, trainerSide, difficulty, opponentAvoid = avoid, trainerAvoid = avoid)
    }

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val endZone = trainerSide.opposite()
        require(runsIntoEndZone(legals, state.board, endZone)) { "TD_RUN: no legal run ends in the $endZone end zone" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        val carrier = trainerBallCarrierOrNull(state, trainerSide) ?: return false
        if (action !is LegalAction.Run || action.playerId != carrier.id) return false
        return state.board.squareAt(action.path.squares.last()).isEndZone(trainerSide.opposite())
    }
}
