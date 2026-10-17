package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.addTouchdown
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.matchState
import com.mhaefele.gridiron.support.withTurnNumber
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpeningTurnDrillStatusTest {
    private val markedRun = MarkedRunTrainingScenario()
    private val tdRun = TdRunTrainingScenario()
    private val stripAndScore = StripAndScoreTrainingScenario()

    @Test
    fun `submit grading does not PASS on a touchdown after the opening coach turn`() {
        val episode = TrainingEpisodeConfig(
            scenario = TrainingScenarioEnum.MARKED_RUN,
            difficulty = 4,
            trainerSide = TeamSide.HOME,
            openingTurnNumber = 1,
        )
        val before = matchState(trainingEpisode = episode).withTurnNumber(2)
        val after = before.copy(progress = before.progress.addTouchdown(TeamSide.HOME))
        val action = LegalAction.Run(PlayerId("home-1"), Path(listOf(Coordinate(9, 14), Coordinate(9, 15))))

        assertThat(isTrainerOpeningTurn(before)).isFalse()
        assertThat(gradeDrillStatus(markedRun, before, after, action, episode)).isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)
        assertThat(gradeDrillStatus(tdRun, before, after, action, episode)).isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)
    }

    @Test
    fun `drillStatus PASSes on opening-turn touchdown`() {
        val episode = TrainingEpisodeConfig(
            scenario = TrainingScenarioEnum.TD_RUN,
            difficulty = 0,
            trainerSide = TeamSide.HOME,
            openingTurnNumber = 1,
        )
        val before = matchState(trainingEpisode = episode)
        val after = before.copy(progress = before.progress.addTouchdown(TeamSide.HOME))
        val action = LegalAction.Run(PlayerId("home-1"), Path(listOf(Coordinate(9, 14), Coordinate(9, 15))))

        assertThat(tdRun.drillStatus(before, after, action)).isEqualTo(TrainingDrillStatus.PASS)
    }

    @Test
    fun `submit grading does not PASS on expected opening move after the opening coach turn`() {
        val episode = TrainingEpisodeConfig(
            scenario = TrainingScenarioEnum.STRIP_AND_SCORE,
            difficulty = 0,
            trainerSide = TeamSide.HOME,
            openingTurnNumber = 1,
        )
        val before = matchState(trainingEpisode = episode).withTurnNumber(2)
        val action = LegalAction.Block(PlayerId("home-1"), Coordinate(3, 3))

        assertThat(isTrainerOpeningTurn(before)).isFalse()
        assertThat(gradeDrillStatus(stripAndScore, before, before, action, episode)).isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)
    }

    /** Mirrors [TrainingScenarios.updatedTrainingEpisode] drill-status gate. */
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
        val episode = state.trainingEpisode ?: return false
        return state.progress.turnNumber == episode.openingTurnNumber && state.currentTurnState.activeCoach == episode.trainerSide
    }
}
