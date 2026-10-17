package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.ai.dto.AiTrainingOutcome
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus

/** Pure training-episode end helpers — no side session store. */
object TrainingEpisodeEnd {
    fun isCoachWindowExceeded(state: MatchState): Boolean {
        val episode = state.trainingEpisode ?: return false
        return state.progress.turnNumber >= episode.openingTurnNumber + scenarioCoachTurnWindow(episode.difficulty)
    }

    /** Synthetic coach-window end while the match is still live. */
    fun trainingEpisodeEnded(state: MatchState): Boolean =
        state.trainingEpisode != null && !state.isGameEnded() && isCoachWindowExceeded(state)

    /** Set when the gym episode is over (curriculum PASS/DISTRACTED/THEM or match ended). */
    fun trainingOutcome(state: MatchState): AiTrainingOutcome? {
        val episode = state.trainingEpisode ?: return null
        if (!state.isGameEnded() && !isCoachWindowExceeded(state)) return null
        return when (episode.drillStatus) {
            TrainingDrillStatus.PASS              -> AiTrainingOutcome.US
            TrainingDrillStatus.DISTRACTED        -> AiTrainingOutcome.DISTRACTED
            TrainingDrillStatus.STILL_IN_PROGRESS -> AiTrainingOutcome.THEM
        }
    }
}
