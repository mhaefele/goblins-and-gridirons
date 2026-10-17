package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.ai.training.claim.ChallengeClaimTableau
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.distance
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.GRAB_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PLAY_TO_THE_STANDS
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.FAN_OUT
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Loose ball + opposition runner adjacent (Grab It! active). Opening lesson: Block that
 * runner before their pickup ([ai/BLINDSPOTS.md] `left-claim-open`).
 *
 * Soft-mask trains the model; fat lookahead is usually right but not d0–d5 reliable yet
 * (scatter can invent a better Run) — exposure-only until the board is tighter.
 */
@Component
class DenyPickupTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.DENY_PICKUP
    override val gatesProgress = false
    override val lookaheadSolveEval = false

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        ClaimBoardPhrases.placeDenyPickupThreat(setup, trainerSide)
        val clearAround = setup.scatterAvoidZones()
        scatterDifficultyExtras(setup, trainerSide, difficulty, opponentAvoid = clearAround, trainerAvoid = clearAround)
    }

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState =
        state.copy(cards = ChallengeClaimTableau.withFocus(GRAB_IT, FAN_OUT, PLAY_TO_THE_STANDS))

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(state.cards.activeCards().any { it.challenge == GRAB_IT }) { "${scenario.name}: Grab It! must be active" }
        require(legals.any { isSoftMaskOpeningMove(it, state, trainerSide) }) { "${scenario.name}: no legal Block on the pickup threat" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Block) return false
        val targetId = state.board.squareAt(action.destination).playerId ?: return false
        val target = state.board.players[targetId] ?: return false
        if (target.team != trainerSide.opposite() || target.status != PlayerStatus.STANDING) return false
        val targetSq = state.board.findSquare(target.id)
        return state.board.allSquares().any { ball ->
            ball.looseBallId != null && distance(ball, targetSq, state.board) == 1
        }
    }
}
