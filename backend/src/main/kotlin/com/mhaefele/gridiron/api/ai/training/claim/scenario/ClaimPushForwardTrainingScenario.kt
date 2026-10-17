package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PUSH_FORWARD
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * One Run moves team possession ≥ 4 squares further from own End Zone.
 * Placement keeps the carrier beyond scoring range so the advance cannot become a Touchdown / Play to the Stands.
 */
@Component
class ClaimPushForwardTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Run::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_PUSH_FORWARD
    override val focusCondition = PUSH_FORWARD

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeCarrierWhoCanAdvanceBallFour(setup, trainerSide)
    }
}
