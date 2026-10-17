package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.KEEP_RUNNING
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Three Run Actions in the turn — claim fires on the third.
 * Soft-mask flags any unused trainer Run (not [claimsFocus] — the card only annotates on the third Run).
 *
 * Lookahead PASS not required — search can wander off the three-Run line on crowded boards.
 */
@Component
class ClaimKeepRunningTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Run::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_KEEP_RUNNING
    override val expectedBudgetedActions = 3
    override val lookaheadSolveEval = false
    override val isolateTrainerScatter = true
    override val focusCondition = KEEP_RUNNING

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeThreeRunners(setup, trainerSide)
    }

    override val requireFocusClaimOnOpening = false

    override fun validateClaimBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        super.validateClaimBoard(state, trainerSide, legals)
        val runnersWithRuns = legals.filterIsInstance<LegalAction.Run>()
            .map { it.playerId }
            .distinct()
        require(runnersWithRuns.size >= 3) { "${scenario.name}: need Runs from ≥3 players to claim over three moves, had ${runnersWithRuns.size}" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isTrainerRun(action, state, trainerSide)

    override fun isSoftMaskFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isTrainerRun(action, state, trainerSide)

    private fun isTrainerRun(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Run) return false
        if (state.board.players[action.playerId]?.team != trainerSide) return false
        val used = state.currentTurnState.actionsThisTurn.map { it.playerId }.toSet()
        return action.playerId !in used
    }
}
