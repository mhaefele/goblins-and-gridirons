package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.ai.dto.AiTrainingOutcome
import com.mhaefele.gridiron.api.ai.training.claim.ChallengeClaimTableau
import com.mhaefele.gridiron.model.Score
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.matchState
import com.mhaefele.gridiron.support.withTurnNumber
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TrainingEpisodeEndDistractedTest {
    private val pushForward = com.mhaefele.gridiron.api.ai.training.claim.scenario.ClaimPushForwardTrainingScenario()

    @Test
    fun `DISTRACTED maps to AiTrainingOutcome DISTRACTED`() {
        val state = scenarioState(
            drillStatus = TrainingDrillStatus.DISTRACTED,
            difficulty = 4,
        )
        // d4 window = 6 coach turns from OPENING_TURN (3) → ended at turn >= 9.
        val closed = state.copy(progress = state.progress.copy(turnNumber = 9))
        assertThat(TrainingEpisodeEnd.trainingOutcome(closed)).isEqualTo(AiTrainingOutcome.DISTRACTED)
        assertThat(TrainingEpisodeEnd.trainingEpisodeEnded(closed)).isTrue()
    }

    @Test
    fun `PASS maps to US while coach window is still open`() {
        val state = scenarioState(drillStatus = TrainingDrillStatus.PASS)
            .withTurnNumber(ChallengeClaimTableau.OPENING_TURN)
        assertThat(TrainingEpisodeEnd.trainingEpisodeEnded(state)).isFalse()
        assertThat(TrainingEpisodeEnd.trainingOutcome(state)).isEqualTo(AiTrainingOutcome.US)
    }

    @Test
    fun `STILL_IN_PROGRESS stays null until window closes`() {
        val open = scenarioState(drillStatus = TrainingDrillStatus.STILL_IN_PROGRESS)
            .withTurnNumber(ChallengeClaimTableau.OPENING_TURN)
        assertThat(TrainingEpisodeEnd.trainingOutcome(open)).isNull()
        assertThat(TrainingEpisodeEnd.trainingEpisodeEnded(open)).isFalse()
    }

    @Test
    fun `opening turn score gain at least focus points grades DISTRACTED`() {
        val focusPoints = ChallengeClaimCondition.PUSH_FORWARD.points
        val before = scenarioState(
            drillStatus = TrainingDrillStatus.STILL_IN_PROGRESS,
            openingTurnStartScoreUs = 0,
        ).withTurnNumber(ChallengeClaimTableau.OPENING_TURN)
        val after = before.copy(
            progress = before.progress.copy(
                turnNumber = ChallengeClaimTableau.OPENING_TURN + 1,
                homeScore = Score(points = focusPoints),
            ),
            trainingEpisode = before.trainingEpisode!!.copy(openingTurnEndScoreUs = focusPoints),
        )
        val episode = before.trainingEpisode!!
        val graded = TrainingScenarioGrading.afterDrillStatusUpdate(pushForward, after, episode)
        assertThat(graded.drillStatus).isEqualTo(TrainingDrillStatus.DISTRACTED)
        assertThat(TrainingEpisodeEnd.trainingOutcome(after.copy(trainingEpisode = graded))).isEqualTo(AiTrainingOutcome.DISTRACTED)
    }

    @Test
    fun `opening turn score gain below focus points stays in progress until window loss`() {
        val before = scenarioState(
            drillStatus = TrainingDrillStatus.STILL_IN_PROGRESS,
            openingTurnStartScoreUs = 0,
        ).withTurnNumber(ChallengeClaimTableau.OPENING_TURN)
        val after = before.copy(
            progress = before.progress.copy(
                turnNumber = ChallengeClaimTableau.OPENING_TURN + 1,
                homeScore = Score(points = 0),
            ),
            trainingEpisode = before.trainingEpisode!!.copy(openingTurnEndScoreUs = 0),
        )
        val episode = before.trainingEpisode!!
        val graded = TrainingScenarioGrading.afterDrillStatusUpdate(pushForward, after, episode)
        assertThat(graded.drillStatus).isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)
        val windowEnd = after.copy(trainingEpisode = graded).withTurnNumber(7)
        assertThat(TrainingEpisodeEnd.trainingOutcome(windowEnd)).isEqualTo(AiTrainingOutcome.THEM)
    }

    @Test
    fun `move the ball focus card points is one`() {
        val state = scenarioState(drillStatus = TrainingDrillStatus.STILL_IN_PROGRESS)
        assertThat(pushForward.expectedOpeningTurnPoints(state)).isEqualTo(ChallengeClaimCondition.PUSH_FORWARD.points)
    }

    private fun scenarioState(
        drillStatus: TrainingDrillStatus,
        difficulty: Int = 0,
        openingTurnStartScoreUs: Int = 0,
    ) = matchState(
        trainingEpisode = TrainingEpisodeConfig(
            scenario = TrainingScenarioEnum.CLAIM_PUSH_FORWARD,
            difficulty = difficulty,
            trainerSide = TeamSide.HOME,
            openingTurnNumber = ChallengeClaimTableau.OPENING_TURN,
            drillStatus = drillStatus,
            openingTurnStartScoreUs = openingTurnStartScoreUs,
        ),
        activeCoach = TeamSide.HOME,
    )
}
