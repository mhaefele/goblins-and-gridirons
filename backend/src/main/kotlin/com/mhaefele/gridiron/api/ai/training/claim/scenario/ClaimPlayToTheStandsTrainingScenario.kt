package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PLAY_TO_THE_STANDS
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** One Action scores a Touchdown. Soft-mask is default [claimsFocus]. */
@Component
class ClaimPlayToTheStandsTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Run::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_PLAY_TO_THE_STANDS
    override val focusCondition = PLAY_TO_THE_STANDS

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeCarrierWhoCanScore(setup, trainerSide)
    }
}
