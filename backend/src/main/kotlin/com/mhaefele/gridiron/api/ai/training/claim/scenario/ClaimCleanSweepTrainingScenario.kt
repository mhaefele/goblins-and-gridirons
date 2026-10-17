package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ChallengeClaimTableau
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.claim.ClaimFillerSelector
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.GRAB_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.HUDDLE_UP
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.DROP_THEM
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Clean Sweep drill: claim three active challenges in one turn (+2 bonus).
 *
 * Board still casts the locked trio — Grab It!, Drop Them!, Huddle Up! — each
 * solvable in one independent action. Episode opens on forced replace: two of the trio + one
 * bait in the tableau, missing trio title on deck-top. Discard the bait → draw completes the
 * trio, then bank all three.
 *
 * Fits awkwardly under the single-focus base: [focusCondition] is only a required abstract
 * placeholder (GRAB_IT); real focus is [TRIO], with custom tableau / soft-mask / PASS.
 *
 * Exposure-only for lookahead PASS — fat LA often banks a filler (e.g. Push Forward) and
 * overruns the 3-action budget before the trio is clean.
 */
@Component
class ClaimCleanSweepTrainingScenario : AbstractChallengeClaimTrainingScenario(null) {
    override val scenario = TrainingScenarioEnum.CLAIM_CLEAN_SWEEP
    override val expectedBudgetedActions = 3
    override val gatesProgress = false
    override val lookaheadSolveEval = false
    override val isolateTrainerScatter = true
    /** Satisfies the abstract; tableau + soft-mask use [TRIO] instead. */
    override val focusCondition = GRAB_IT
    override val requireFocusClaimOnOpening = false

    override fun keepConditionsForReplace(): Set<ChallengeClaimCondition> = TRIO.toSet()

    override fun assembleReplaceKeepSet(state: MatchState, dice: DiceRoller, openingMoves: List<LegalAction>): MatchState =
        ClaimFillerSelector.assembleCleanSweepReplaceKeepSet(state, TRIO, dice, openingMoves)

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeRunnerBesideLooseBall(setup, trainerSide)
        // Clusters ≥4 apart so a SHOVE cannot park the block target beside the ball carrier /
        // Huddle Up cast (that used to steal the last action on a second Block).
        ClaimBoardPhrases.placeBlockerBesideOpposition(setup, trainerSide, minDistanceFromOnPitch = 4)
        ClaimBoardPhrases.placePlayerWhoCanJoinTwoTeammates(setup, trainerSide, minDistanceFromOnPitch = 4)
    }

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState {
        val episode = requireTrainingEpisode(state)
        return state.copy(
            progress = state.progress.copy(turnNumber = ChallengeClaimTableau.OPENING_TURN),
            cards = ChallengeClaimTableau.withFocusOnly(GRAB_IT),
            trainingEpisode = episode.copy(openingTurnNumber = ChallengeClaimTableau.OPENING_TURN),
        )
    }

    override fun requireActiveFocus(state: MatchState) {
        val titles = state.cards.activeChallengeClaims()
        require(state.cards.activeSlots.none { it == null }) { "${scenario.name}: slots must be filled before validate, was ${state.cards.activeSlots}" }
        require(titles.count { it in TRIO } == 2) { "${scenario.name}: expected 2 of $TRIO in tableau, was $titles" }
        require(titles.count { it !in TRIO } == 1) { "${scenario.name}: expected 1 bait in tableau, was $titles" }
    }

    override fun validateClaimBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val titles = state.cards.activeChallengeClaims()
        val keep = titles.filter { it in TRIO }.toSet()
        val bait = titles.single { it !in TRIO }
        for (condition in keep) {
            require(legals.any { action -> action.potentialChallengeClaims.any { it.claimCondition == condition } }) { "${scenario.name}: no opening legal annotated for keepable $condition" }
        }
        require(legals.none { action -> action.potentialChallengeClaims.any { it.claimCondition == bait } }) { "${scenario.name}: bait $bait leaked onto opening legals" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        claimsAnyActiveTrioCard(action, state)

    override fun isSoftMaskFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        claimsAnyActiveTrioCard(action, state)

    override fun isPassingOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        claimsAnyActiveTrioCard(action, state)

    override fun isPassingFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        claimsAnyActiveTrioCard(action, state)

    override fun isSoftMaskScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (state.currentTurnState.currentPlayerAction != null) return false
        if (isReplacePending(state)) return isSoftMaskReplaceDiscard(action, state)
        if (activeTrioConditions(state).isEmpty()) return false
        return if (state.currentTurnState.actionsThisTurn.isEmpty()) {
            isSoftMaskOpeningMove(action, state, trainerSide)
        } else {
            isSoftMaskFollowUpMove(action, state, trainerSide)
        }
    }

    override fun isPassingScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (state.currentTurnState.currentPlayerAction != null) return false
        if (isReplacePending(state)) return false
        if (activeTrioConditions(state).isEmpty()) return false
        return if (state.currentTurnState.actionsThisTurn.isEmpty()) {
            isPassingOpeningMove(action, state, trainerSide)
        } else {
            isPassingFollowUpMove(action, state, trainerSide)
        }
    }

    override fun focusStillActive(state: MatchState): Boolean = activeTrioConditions(state).isNotEmpty()

    override fun expectedOpeningTurnPoints(state: MatchState): Int = TRIO.sumOf { it.points }

    override fun drillStatus(before: MatchState, after: MatchState, action: LegalAction): TrainingDrillStatus =
        if (TRIO.all { trainerHolds(after, it) }) TrainingDrillStatus.PASS else TrainingDrillStatus.STILL_IN_PROGRESS

    private fun claimsAnyActiveTrioCard(action: LegalAction, state: MatchState): Boolean {
        val remaining = activeTrioConditions(state)
        val claimable = action.potentialChallengeClaims.map { it.claimCondition }.toSet()
        // Any remaining trio title, any order — clusters stay ≥4 apart so a SHOVE cannot steal
        // another cast's last action (see buildClaimBoard).
        return remaining.any { condition ->
            condition in claimable && when (condition) {
                GRAB_IT   -> action is LegalAction.Run && action.ballEncounter == BallEncounter.PICK_UP
                HUDDLE_UP   -> action is LegalAction.Run || action is LegalAction.Mark || action is LegalAction.Sidestep
                DROP_THEM -> action is LegalAction.Block
                else           -> false
            }
        }
    }

    private fun activeTrioConditions(state: MatchState): Set<ChallengeClaimCondition> =
        state.cards.activeChallengeClaims().filter { it in TRIO }.toSet()

    companion object {
        val TRIO = listOf(GRAB_IT, DROP_THEM, HUDDLE_UP)
    }
}
