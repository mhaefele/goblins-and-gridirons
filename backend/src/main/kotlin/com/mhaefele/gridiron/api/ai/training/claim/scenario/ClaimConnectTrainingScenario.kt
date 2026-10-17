package com.mhaefele.gridiron.api.ai.training.claim.scenario

import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.CONNECT
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import org.springframework.stereotype.Component

/**
 * Throw (not a Hand-off). Card needs a completion; drill PASS is the throw attempt.
 * Soft-mask is default [claimsFocus] — validate enforces non-hand-off + no TD-run leak.
 */
@Component
class ClaimConnectTrainingScenario : AbstractChallengeClaimTrainingScenario(LegalAction.Throw::class) {
    override val scenario = TrainingScenarioEnum.CLAIM_CONNECT
    override val focusCondition = CONNECT
    override val succeedsOnAttempt = true

    override fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        ClaimBoardPhrases.placeThrowerWithShortReceiver(setup, trainerSide)
    }

    override fun validateClaimBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        super.validateClaimBoard(state, trainerSide, legals)
        require(
            legals.filterIsInstance<LegalAction.Throw>().any { it.target.range != ThrowRange.HAND_OFF && claimsFocus(it) },
        ) { "${scenario.name}: expected a non-hand-off Throw annotated for $focusCondition" }
        // If the carrier can score by running, Lookahead (and the model) prefer Play to the Stands over the throw.
        // Placement uses beyondMoveOf; this require catches regressions if scatter/geometry drifts.
        require(
            !runsIntoEndZone(
                legals.filterIsInstance<LegalAction.Run>().filter { it.playerId == trainerBallCarrier(state, trainerSide).id },
                state.board,
                trainerSide.opposite(),
            ),
        ) { "${scenario.name}: thrower must not be able to run into the scoring End Zone" }
    }
}
