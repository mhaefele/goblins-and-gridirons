package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.shared.TeamSide

/** Score and distracted-grade helpers for training scenario episodes. */
internal object TrainingScenarioGrading {
    fun afterDrillStatusUpdate(
        script: AbstractTrainingScenario,
        after: MatchState,
        episode: TrainingEpisodeConfig,
    ): TrainingEpisodeConfig {
        val withEndScore = recordOpeningTurnEndScore(after, episode)
        return maybeGradeDistracted(script, after, withEndScore)
    }

    private fun recordOpeningTurnEndScore(after: MatchState, episode: TrainingEpisodeConfig): TrainingEpisodeConfig {
        if (episode.openingTurnEndScoreUs != null) return episode
        if (after.progress.turnNumber <= episode.openingTurnNumber) return episode
        return episode.copy(openingTurnEndScoreUs = trainerScore(after, episode))
    }

    private fun maybeGradeDistracted(script: AbstractTrainingScenario, state: MatchState, episode: TrainingEpisodeConfig): TrainingEpisodeConfig {
        if (episode.drillStatus != TrainingDrillStatus.STILL_IN_PROGRESS) return episode
        if (!openingTurnScoreGainMeetsExpected(script, episode, state)) return episode
        return episode.copy(drillStatus = TrainingDrillStatus.DISTRACTED)
    }

    private fun openingTurnScoreGainMeetsExpected(
        script: AbstractTrainingScenario,
        episode: TrainingEpisodeConfig,
        state: MatchState,
    ): Boolean {
        val end = episode.openingTurnEndScoreUs ?: return false
        val expected = script.expectedOpeningTurnPoints(state) ?: return false
        return end - episode.openingTurnStartScoreUs >= expected
    }

    private fun trainerScore(state: MatchState, episode: TrainingEpisodeConfig): Int =
        when (episode.trainerSide) {
            TeamSide.HOME -> state.progress.homeScore.points
            TeamSide.AWAY -> state.progress.awayScore.points
        }
}
