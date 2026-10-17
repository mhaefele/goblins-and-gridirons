package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.STRIP_IT
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** Block the opposition ball carrier. Card needs a strip; drill PASS is the block attempt. Soft-mask is default [claimsFocus]. */
@Component
class ClaimStripItTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Block::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_STRIP_IT
    override val focusCondition = STRIP_IT
    override val succeedsOnAttempt = true

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeBlockerBesideOppositionCarrier(setup, trainerSide)
    }
}
