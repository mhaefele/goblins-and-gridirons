package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.LOCK_THEM_DOWN
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * One Mark uniquely marks two unmarked opposition Players.
 * Soft-mask is default [claimsFocus]. [isolateTrainerScatter] keeps extras from marking the flanks first.
 *
 * Do not add opening-menu "no trainer pickup / no EZ Run" requires here: at d5 the claim-episode
 * ball often has no square out of every hunter's reach, and midfield markers routinely have
 * ball-less EZ Runs (not Play to the Stands). Those checks made max-difficulty placement impossible
 * ([TrainingScenarioPlacementRetriesTest]). Ball reach is handled in [com.mhaefele.gridiron.api.ai.training.TrainingScenarios.ensureBallInPlayForClaimEpisode].
 */
@Component
class ClaimLockThemDownTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Mark::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_LOCK_THEM_DOWN
    override val focusCondition = LOCK_THEM_DOWN
    override val isolateTrainerScatter = true

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeMarkerWhoCanTieTwo(setup, trainerSide)
    }
}
