package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

@Component
class LooseBallBounceTrainingScenario : AbstractLooseBallTrainingScenario(contested = true) {
    override val scenario = TrainingScenarioEnum.LOOSE_BALL_BOUNCE
    override val difficultyProxy = TrainingScenarioEnum.LOOSE_BALL_RUN
    override val gatesProgress = false
    override val expectedBudgetedActions = 3

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val board = state.board
        val ballCoord = board.allSquares().firstOrNull { it.looseBallId != null }?.coordinate ?: error("LOOSE_BALL_BOUNCE: no loose ball on board")
        val guardCoords = neighbors(board.squareAt(ballCoord), board).map { it.coordinate }.filter { coord ->
            val occupant = board.squareAt(coord).playerId?.let { board.players[it] }
            occupant != null && occupant.team != trainerSide
        }.toSet()
        require(guardCoords.isNotEmpty()) { "LOOSE_BALL_BOUNCE: no opponent adjacent to the loose ball" }
        val canBlockGuard = legals.filterIsInstance<LegalAction.Block>().any { block ->
            board.players[block.playerId]?.team == trainerSide && block.destination in guardCoords
        }
        val canScore = runsIntoEndZone(legals, board, trainerSide.opposite())
        require(canBlockGuard || canScore) { "LOOSE_BALL_BOUNCE: trainer has neither a block on the guard nor a run into the end zone" }
    }

    override fun isPassingOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        val scoringEndZone = trainerSide.opposite()
        if (action is LegalAction.Run && state.board.squareAt(action.path.squares.last()).isEndZone(scoringEndZone)) return true
        val guards = looseBallGuardSquares(state.board, trainerSide)
        return action is LegalAction.Block && action.destination in guards
    }
}
