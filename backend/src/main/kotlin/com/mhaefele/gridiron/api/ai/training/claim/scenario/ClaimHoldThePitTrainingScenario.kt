package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.HOLD_THE_PIT
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** One Action ends on a Trapdoor while opposition holds a ball (Run / Mark / Sidestep). Soft-mask is default [claimsFocus]. */
@Component
class ClaimHoldThePitTrainingScenario : AbstractChallengeClaimTrainingScenario(null) {
    override val scenario = TrainingScenarioEnum.CLAIM_HOLD_THE_PIT
    override val focusCondition = HOLD_THE_PIT

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeRunnerWhoCanEndOnTrapdoor(setup, trainerSide)
    }
}
