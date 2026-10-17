package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.ai.dto.AiTrainingOutcome
import com.mhaefele.gridiron.api.ai.training.claim.ChallengeClaimTableau
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.matchState
import com.mhaefele.gridiron.support.withTurnNumber
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TrainingEpisodeEndApiTest : BaseApiTest() {

    @BeforeEach
    fun randomDice() {
        dice.usePassThrough()
    }

    @Test
    fun `TD_RUN expected opening grades US when training episode ends`() {
        withRandomDiceRetries {
            val created = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.TD_RUN))
            val opening = api.aiStep(created.matchId, forSeat = TeamSide.HOME)
            assertThat(opening.harness.trainingEpisodeEnded).isFalse()
            assertThat(opening.harness.trainingOutcome).isNull()

            val passing = opening.harness.legalRows.first { it.passingLegal }
            api.aiSubmit(created.matchId, passing.legalActionId, forSeat = TeamSide.HOME)

            // Coach window is one turn at difficulty 0 — keep acting until the server ends the episode.
            var guard = 0
            while (guard++ < 80) {
                val step = api.aiStep(created.matchId, forSeat = TeamSide.HOME)
                if (step.isEpisodeEnded()) {
                    assertThat(step.harness.legalRows).isEmpty()
                    assertThat(step.harness.trainingOutcome).isEqualTo(AiTrainingOutcome.US)
                    return@withRandomDiceRetries
                }
                val pick = step.harness.legalRows.first().legalActionId
                val result = api.aiSubmit(created.matchId, pick, forSeat = TeamSide.HOME)
                if (result.next.isEpisodeEnded()) {
                    assertThat(result.next.harness.legalRows).isEmpty()
                    assertThat(result.next.harness.trainingOutcome).isEqualTo(AiTrainingOutcome.US)
                    return@withRandomDiceRetries
                }
            }
            error("training episode did not end within $guard submits")
        }
    }

    @Test
    fun `DISTRACTED at coach-window end serves packed step without 400`() {
        val state = matchState(
            trainingEpisode = TrainingEpisodeConfig(
                scenario = TrainingScenarioEnum.CLAIM_PUSH_FORWARD,
                difficulty = 0,
                trainerSide = TeamSide.HOME,
                openingTurnNumber = ChallengeClaimTableau.OPENING_TURN,
                drillStatus = TrainingDrillStatus.DISTRACTED,
                openingTurnStartScoreUs = 0,
                openingTurnEndScoreUs = 1,
            ),
            activeCoach = TeamSide.HOME,
        ).withTurnNumber(ChallengeClaimTableau.OPENING_TURN + 1)
        matchId = seedMatch(state)
        val packed = api.aiStepPacked(matchId, forSeat = TeamSide.HOME)
        assertThat(packed.harness.trainingEpisodeEnded).isTrue()
        assertThat(packed.harness.trainingOutcome).isEqualTo(AiTrainingOutcome.DISTRACTED)
        assertThat(packed.policy.legalMoves).isEmpty()
    }
}
