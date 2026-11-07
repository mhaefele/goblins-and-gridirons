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

    /**
     * Curriculum grade on the harness.
     *
     * - [AiTrainingOutcome.US] as soon as [TrainingDrillStatus.PASS] (mid-window OK — PPO credits
     *   +0.5 on that edge, not on leftover chaff at window close).
     * - [AiTrainingOutcome.THEM] / [AiTrainingOutcome.DISTRACTED] only once the gym episode is over.
     */
    fun trainingOutcome(state: MatchState): AiTrainingOutcome? {
        val episode = state.trainingEpisode ?: return null
        if (episode.drillStatus == TrainingDrillStatus.PASS) return AiTrainingOutcome.US
        if (!state.isGameEnded() && !isCoachWindowExceeded(state)) return null
        return when (episode.drillStatus) {
            TrainingDrillStatus.PASS              -> AiTrainingOutcome.US
            TrainingDrillStatus.DISTRACTED        -> AiTrainingOutcome.DISTRACTED
            TrainingDrillStatus.STILL_IN_PROGRESS -> AiTrainingOutcome.THEM
        }
    }
}
