package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.SEND_THEM_OFF
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** Block / Foul the target. Card needs an injury; drill PASS is the block attempt. Soft-mask is default [claimsFocus]. */
@Component
class ClaimSendThemOffTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Block::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_SEND_THEM_OFF
    override val focusCondition = SEND_THEM_OFF
    override val succeedsOnAttempt = true

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeBlockerBesideOpposition(setup, trainerSide)
    }
}
