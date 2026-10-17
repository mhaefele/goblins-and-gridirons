package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.ALL_HANDS
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Three different Players make three different Actions — claim on the third.
 * Soft-mask is progressive freshness (unused player + unused action type), not [claimsFocus].
 *
 * Lookahead PASS not required — fat LA can burn the turn on a line that never banks the card.
 */
@Component
class ClaimAllHandsTrainingScenario : AbstractChallengeClaimTrainingScenario(null) {
    override val scenario = TrainingScenarioEnum.CLAIM_ALL_HANDS
    override val expectedBudgetedActions = 3
    override val lookaheadSolveEval = false
    override val isolateTrainerScatter = true
    override val focusCondition = ALL_HANDS

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeAllHandsCast(setup, trainerSide)
    }

    override val requireFocusClaimOnOpening = false

    override fun validateClaimBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        super.validateClaimBoard(state, trainerSide, legals)
        val hasRun = legals.any { it is LegalAction.Run }
        val hasMark = legals.any { it is LegalAction.Mark }
        val hasBlock = legals.any { it is LegalAction.Block }
        require(hasRun && hasMark && hasBlock) { "${scenario.name}: opening cast must offer Run + Mark + Block(run=$hasRun mark=$hasMark block=$hasBlock)" }
        val actors = legals.filterIsInstance<LegalAction.PlayerLegalAction>()
            .filter { it is LegalAction.Run || it is LegalAction.Mark || it is LegalAction.Block }
            .map { it.playerId }
            .toSet()
        require(actors.size >= 3) { "${scenario.name}: Run/Mark/Block must come from three different players, had $actors" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isFreshTeamAction(action, state, trainerSide, typesDone = emptySet(), playersDone = emptySet())

    override fun isSoftMaskFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        val log = state.currentTurnState.actionsThisTurn
        return isFreshTeamAction(
            action,
            state,
            trainerSide,
            typesDone = log.map { it.actionType }.toSet(),
            playersDone = log.map { it.playerId }.toSet(),
        )
    }

    /** Any unused basic player action from a player who has not acted yet — order does not matter for the claim. */
    private fun isFreshTeamAction(
        action: LegalAction,
        state: MatchState,
        trainerSide: TeamSide,
        typesDone: Set<ActionType>,
        playersDone: Set<PlayerId>,
    ): Boolean {
        if (action !is LegalAction.BasicPlayerAction) return false
        if (state.board.players[action.playerId]?.team != trainerSide) return false
        if (action.playerId in playersDone) return false
        return action.actionType !in typesDone
    }
}
