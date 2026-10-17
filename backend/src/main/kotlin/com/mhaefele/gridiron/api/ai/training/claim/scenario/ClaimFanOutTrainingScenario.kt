package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.FAN_OUT
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * One Run leaves no teammates adjacent.
 * Soft-mask is default [claimsFocus] — annotator tags the separating Run.
 * [isolateTrainerScatter] keeps difficulty extras from landing adjacent and undoing the lesson.
 */
@Component
class ClaimFanOutTrainingScenario : AbstractChallengeClaimTrainingScenario(null) {
    override val scenario = TrainingScenarioEnum.CLAIM_FAN_OUT
    override val focusCondition = FAN_OUT
    override val isolateTrainerScatter = true

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeRunnerWhoCanFanOut(setup, trainerSide)
    }
}
