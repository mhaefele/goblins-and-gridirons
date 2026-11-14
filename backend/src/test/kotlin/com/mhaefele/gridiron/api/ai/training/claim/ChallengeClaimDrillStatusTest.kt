package com.mhaefele.gridiron.api.ai.training.claim

import com.mhaefele.gridiron.api.ai.training.claim.scenario.ClaimConnectTrainingScenario
import com.mhaefele.gridiron.api.dto.response.ClaimCertainty
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.PotentialChallengeClaim
import com.mhaefele.gridiron.api.dto.response.ThrowTarget
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.support.matchState
import com.mhaefele.gridiron.support.withTurnNumber
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * TODO(review): Suspicious stopgap — replaced a fixed-dice integration test for
 * [AbstractChallengeClaimTrainingScenario.succeedsOnAttempt] when scenario tests
 * moved to live dice. Revisit: either restore an API-level assertion without
 * queuing dice, or delete this if drillStatus is trivial enough not to warrant a test.
 */
class ChallengeClaimDrillStatusTest {
    private val scenario = ClaimConnectTrainingScenario()

    @Test
    fun `succeedsOnAttempt grades PASS on expected opening move without banking focus card`() {
        val cards = ChallengeClaimTableau.withFocus(
            ChallengeClaimCondition.CONNECT,
            ChallengeClaimCondition.GRAB_IT,
            ChallengeClaimCondition.DROP_THEM,
        )
        val before = matchState(
            trainingEpisode = TrainingEpisodeConfig(
                scenario = TrainingScenarioEnum.CLAIM_CONNECT,
                difficulty = 0,
                trainerSide = TeamSide.HOME,
                openingTurnNumber = ChallengeClaimTableau.OPENING_TURN,
            ),
            activeCoach = TeamSide.HOME,
            cards = cards,
        ).withTurnNumber(ChallengeClaimTableau.OPENING_TURN)
        val focusClaim = PotentialChallengeClaim(
            claimCondition = ChallengeClaimCondition.CONNECT,
            challengeTitle = "Connect",
            points = 1,
            certainty = ClaimCertainty.POTENTIAL,
        )
        val expectedThrow = LegalAction.Throw(
            playerId = PlayerId("home-1"),
            target = ThrowTarget(Coordinate(3, 3), ThrowRange.SHORT, obstructed = false, throwCheckNeeded = 3),
            potentialChallengeClaims = listOf(focusClaim),
        )
        val otherThrow = expectedThrow.copy(
            target = expectedThrow.target.copy(range = ThrowRange.HAND_OFF),
            potentialChallengeClaims = emptyList(),
        )

        assertThat(scenario.drillStatus(before, before, expectedThrow))
            .isEqualTo(TrainingDrillStatus.PASS)
        assertThat(scenario.drillStatus(before, before, otherThrow))
            .isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)
    }
}
