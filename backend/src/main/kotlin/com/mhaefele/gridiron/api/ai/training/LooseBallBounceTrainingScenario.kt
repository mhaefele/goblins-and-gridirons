package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Contested loose ball (opponent adjacent → pickup bounces). Layout: runner on a TD path,
 * ball ≥2 away on that path, guard on the ball — **not** an opening adjacent block setup.
 *
 * No ``passingLegal`` / soft-mask / expected opening: exposure board only (proxy of
 * [LOOSE_BALL_RUN]); train uses score-only rewards. Do not grade empty-EZ runs or invent
 * a guard-block lesson the placement does not guarantee.
 */
@Component
class LooseBallBounceTrainingScenario : AbstractLooseBallTrainingScenario(contested = true) {
    override val scenario = TrainingScenarioEnum.LOOSE_BALL_BOUNCE
    override val difficultyProxy = TrainingScenarioEnum.LOOSE_BALL_RUN
    override val gatesProgress = false
    override val expectedBudgetedActions = 3

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val board = state.board
        val ballCoord = board.allSquares().firstOrNull { it.looseBallId != null }?.coordinate ?: error("LOOSE_BALL_BOUNCE: no loose ball on board")
        val hasGuard = neighbors(board.squareAt(ballCoord), board).any { square ->
            val occupant = square.playerId?.let { board.players[it] } ?: return@any false
            occupant.team != trainerSide
        }
        require(hasGuard) { "LOOSE_BALL_BOUNCE: no opponent adjacent to the loose ball" }
    }

    /** Exposure-only — no PASS-grade / expected opening rows. */
    override fun isPassingOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean = false
}
