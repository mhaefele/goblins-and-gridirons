package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.THREAD_THE_NEEDLE
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import org.springframework.stereotype.Component

/**
 * Long Throw (negative throw-check). Card needs success; drill PASS is the throw attempt.
 * Soft-mask is default [claimsFocus] — validate enforces Long range + no TD-run leak.
 */
@Component
class ClaimThreadTheNeedleTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Throw::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_THREAD_THE_NEEDLE
    override val focusCondition = THREAD_THE_NEEDLE
    override val succeedsOnAttempt = true

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeThrowerWithLongReceiver(setup, trainerSide)
    }

    override fun validateClaimBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        super.validateClaimBoard(state, trainerSide, legals)
        require(
            legals.filterIsInstance<LegalAction.Throw>().any { it.target.range == ThrowRange.LONG && claimsFocus(it) },
        ) { "${scenario.name}: expected a Long Throw annotated for $focusCondition" }
        // Same curriculum isolation as Completion: a scoring Run steals the lesson from the Long Throw.
        require(
            !runsIntoEndZone(
                legals.filterIsInstance<LegalAction.Run>().filter { it.playerId == trainerBallCarrier(state, trainerSide).id },
                state.board,
                trainerSide.opposite(),
            ),
        ) { "${scenario.name}: thrower must not be able to run into the scoring End Zone" }
    }
}
