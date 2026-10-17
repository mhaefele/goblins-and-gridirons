package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.HUDDLE_UP
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** One Run / Mark / Sidestep ends adjacent to two teammates. Soft-mask is default [claimsFocus]. */
@Component
class ClaimHuddleUpTrainingScenario : AbstractChallengeClaimTrainingScenario(null) {
    override val scenario = TrainingScenarioEnum.CLAIM_HUDDLE_UP
    override val focusCondition = HUDDLE_UP

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placePlayerWhoCanJoinTwoTeammates(setup, trainerSide)
    }
}
