package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.matchState
import com.mhaefele.gridiron.support.withTurnNumber
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StripAndScoreDrillStatusTest {
    private val scenario = StripAndScoreTrainingScenario()

    @Test
    fun `submit grading skips expected-opening check after the episode opening turn`() {
        val episode = TrainingEpisodeConfig(
            scenario = TrainingScenarioEnum.STRIP_AND_SCORE,
            difficulty = 0,
            trainerSide = TeamSide.HOME,
            openingTurnNumber = 1,
        )
        val before = matchState(trainingEpisode = episode).withTurnNumber(2)
        val action = LegalAction.Block(PlayerId("home-1"), Coordinate(3, 3))

        assertThat(isTrainerOpeningTurn(before)).isFalse()
        assertThat(gradeDrillStatus(scenario, before, before, action, episode))
            .isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)
    }

    private fun gradeDrillStatus(
        script: AbstractTrainingScenario,
        before: MatchState,
        after: MatchState,
        action: LegalAction,
        episode: TrainingEpisodeConfig,
    ): TrainingDrillStatus =
        if (episode.drillStatus == TrainingDrillStatus.STILL_IN_PROGRESS && isTrainerOpeningTurn(before)) {
            script.drillStatus(before, after, action)
        } else {
            episode.drillStatus
        }

    private fun isTrainerOpeningTurn(state: MatchState): Boolean {
        val ep = state.trainingEpisode ?: return false
        return state.progress.turnNumber == ep.openingTurnNumber && state.currentTurnState.activeCoach == ep.trainerSide
    }
}
