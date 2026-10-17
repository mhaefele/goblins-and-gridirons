package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.DROP_THEM
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** Block the target. Card needs a knockdown; drill PASS is the block attempt. Soft-mask is default [claimsFocus]. */
@Component
class ClaimDropThemTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Block::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_DROP_THEM
    override val focusCondition = DROP_THEM
    override val succeedsOnAttempt = true

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeBlockerBesideOpposition(setup, trainerSide)
    }
}
