package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.isAssistedBlock
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * "One blocker, two targets — only one is Assisted. Take the Assisted Block."
 *
 * Geometry: teammate marks T1 with the blocker; T2 is adjacent to the blocker only.
 */
@Component
class AssistedBlockTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.ASSISTED_BLOCK

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val targetAssisted = setup.placeRandomReserve(trainerSide.opposite(), midfield(setup, trainerSide.opposite()))
        val assister = setup.placeRandomReserve(trainerSide, setup.emptyAdjacentSquares(targetAssisted.square))
        val blocker = setup.placeRandomReserve(trainerSide, setup.emptyAdjacentSquares(targetAssisted.square))
        val targetUnassisted = setup.placeRandomReserve(
            trainerSide.opposite(),
            setup.emptyAdjacentSquares(blocker.square, excluding = listOf(targetAssisted.square)) { setup.manhattanDistance(it, assister.square) > 1 },
        )
        val clearAround = blocker.adjacentSquares() + assister.adjacentSquares() + targetAssisted.adjacentSquares() + targetUnassisted.adjacentSquares()
        scatterDifficultyExtras(setup, trainerSide, difficulty, opponentAvoid = clearAround, trainerAvoid = clearAround)
    }

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val blocks = legals.filterIsInstance<LegalAction.Block>().filter { state.board.players[it.playerId]?.team == trainerSide }
        require(blocks.any { isAssisted(it, state.board) }) { "ASSISTED_BLOCK: no assisted Block" }
        require(blocks.any { !isAssisted(it, state.board) }) { "ASSISTED_BLOCK: no unassisted Block" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        action is LegalAction.Block && isAssisted(action, state.board)

    private fun isAssisted(action: LegalAction.Block, board: Board): Boolean {
        val blocker = board.players[action.playerId] ?: return false
        val targetId = board.squareAt(action.destination).playerId ?: return false
        val target = board.players[targetId] ?: return false
        return isAssistedBlock(blocker, target, board)
    }

    private fun midfield(setup: TrainingBoardBuilder, team: TeamSide): (BoardSquare) -> Boolean = { square ->
        setup.emptyPlayable(square) && !square.isEndZone(team) && !square.isEndZone(team.opposite()) && !square.isTrapdoor()
    }
}
