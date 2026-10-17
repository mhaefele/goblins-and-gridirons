package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.GRAB_IT
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * One Run picks up a ball the team did not hold at turn start.
 * Soft-mask is default [claimsFocus]; validate keeps the pickup geometry honest.
 */
@Component
class ClaimGrabItTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Run::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_GRAB_IT
    override val focusCondition = GRAB_IT

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeRunnerBesideLooseBall(setup, trainerSide)
    }

    override fun validateClaimBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        super.validateClaimBoard(state, trainerSide, legals)
        // Placement puts the ball beyond scoring range; still assert the annotated Run is a pickup
        // so a stray Play to the Stands / Move-the-Ball annotation cannot sneak through.
        require(
            legals.filterIsInstance<LegalAction.Run>().any { it.ballEncounter == BallEncounter.PICK_UP && claimsFocus(it) },
        ) { "${scenario.name}: expected a pickup Run annotated for $focusCondition" }
    }
}
