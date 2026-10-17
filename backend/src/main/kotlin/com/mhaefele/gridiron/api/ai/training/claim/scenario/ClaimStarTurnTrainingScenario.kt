package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.STAR_TURN
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * One Player finishes three distinct budgeted Actions — claim on the third.
 *
 * Soft-mask and PASS both flag the tagged star's unused action types on every budgeted
 * step of the opening turn (any order among distinct types — not a fixed Run→Mark→Block script).
 *
 * Star is placed beyond scoring range so a Touchdown Run is not a competing "easy win."
 */
@Component
class ClaimStarTurnTrainingScenario : AbstractChallengeClaimTrainingScenario(null) {
    override val scenario = TrainingScenarioEnum.CLAIM_STAR_TURN
    override val expectedBudgetedActions = 3
    override val gatesProgress = false
    override val lookaheadSolveEval = false
    override val isolateTrainerScatter = true
    override val focusCondition = STAR_TURN
    override val requireFocusClaimOnOpening = false

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        // Curriculum currently pins IN_RUN_RANGE; other variants exist for experiments but starve Lookahead less when fixed.
        ClaimBoardPhrases.placeStarTurnCast(setup, trainerSide, ClaimBoardPhrases.StarTurnVariant.IN_RUN_RANGE)
    }

    override fun validateClaimBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        super.validateClaimBoard(state, trainerSide, legals)
        val starId = requireStarTurnStarId(state)
        // Do not ban every opening Run into the scoring End Zone: PASS is star-only, and a non-star
        // scoring Run (if scatter ever creates one) does not grade PASS. The star itself is placed
        // with beyondMoveOf so the lesson player cannot Play to the Stands instead of chaining three actions.
        val starHasOpening = legals.filterIsInstance<LegalAction.PlayerLegalAction>().any { it.playerId == starId }
        require(starHasOpening) { "${scenario.name}: star $starId has no legal opening action" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isStarUnusedTypeAction(action, state, typesDone = emptySet())

    override fun isPassingOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isSoftMaskOpeningMove(action, state, trainerSide)

    override fun isSoftMaskFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        val starId = requireStarTurnStarId(state)
        val typesDone = state.currentTurnState.actionsThisTurn
            .filter { it.playerId == starId && !it.isFree }
            .map { it.actionType }
            .toSet()
        if (typesDone.size !in 1..2) return false
        return isStarUnusedTypeAction(action, state, typesDone)
    }

    private fun isStarUnusedTypeAction(
        action: LegalAction,
        state: MatchState,
        typesDone: Set<ActionType>,
    ): Boolean {
        if (action !is LegalAction.PlayerLegalAction) return false
        val starId = requireStarTurnStarId(state)
        if (action.playerId != starId || action.actionType !in SHOW_THEM_PLAYER_ACTIONS) return false
        if (action.actionType in typesDone) return false
        // Trapdoor Runs remove the star — cannot chain the three distinct actions the card needs.
        if (action is LegalAction.PathMovement && state.board.squareAt(action.path.squares.last()).isTrapdoor()) return false
        return true
    }

    private fun requireStarTurnStarId(state: MatchState): PlayerId {
        val starId = requireTrainingEpisode(state).starTurnStarId
        require(starId != null) { "${scenario.name}: starTurnStarId required" }
        return starId
    }

    internal companion object {
        val SHOW_THEM_PLAYER_ACTIONS = setOf(
            ActionType.RUN,
            ActionType.MARK,
            ActionType.BLOCK,
            ActionType.STAND_UP,
            ActionType.THROW,
            ActionType.THROW_TEAMMATE,
            ActionType.SIDESTEP,
            ActionType.FOUL,
        )
    }
}
