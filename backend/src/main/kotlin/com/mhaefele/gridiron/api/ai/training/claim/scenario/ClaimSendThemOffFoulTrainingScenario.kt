package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.SEND_THEM_OFF
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Foul the only breakable (prone) opposition player. PASS is the Foul attempt.
 * Soft-mask is default [claimsFocus]. Lobbers stay off the pitch — ranged Open Blocks
 * would also annotate SEND_THEM_OFF and leak the Foul-only expected type.
 */
@Component
class ClaimSendThemOffFoulTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Foul::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_SEND_THEM_OFF_FOUL
    override val focusCondition = SEND_THEM_OFF
    override val succeedsOnAttempt = true

    override fun trainerScatterEligible(player: Player): Boolean = !player.hasAbility(PlayerAbility.Lobber)

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeFoulerBesideProneOpposition(setup, trainerSide)
    }
}
