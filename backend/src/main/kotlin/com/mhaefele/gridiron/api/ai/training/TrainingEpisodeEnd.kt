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

    /**
     * Synthetic coach-window end while the match is still live, or LOOSE_BALL_RUN d0–d1 after the
     * first opening budgeted action ([scenarioEndsAfterFirstOpeningAction]).
     *
     * One-shot PASS/DISTRACTED ends on grade alone (actionsThisTurn / activeCoach can lag the grade
     * right after pickup and leave a live menu). Miss stays STILL_IN_PROGRESS until the first
     * opening action is on the turn log.
     */
    fun trainingEpisodeEnded(state: MatchState): Boolean {
        val episode = state.trainingEpisode ?: return false
        if (state.isGameEnded()) return false
        if (isCoachWindowExceeded(state)) return true
        if (!scenarioEndsAfterFirstOpeningAction(episode)) return false
        if (episode.drillStatus == TrainingDrillStatus.PASS || episode.drillStatus == TrainingDrillStatus.DISTRACTED) {
            return true
        }
        return state.progress.turnNumber == episode.openingTurnNumber && state.currentTurnState.actionsThisTurn.isNotEmpty()
    }

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
        if (!state.isGameEnded() && !trainingEpisodeEnded(state)) return null
        return when (episode.drillStatus) {
            TrainingDrillStatus.PASS              -> AiTrainingOutcome.US
            TrainingDrillStatus.DISTRACTED        -> AiTrainingOutcome.DISTRACTED
            TrainingDrillStatus.STILL_IN_PROGRESS -> AiTrainingOutcome.THEM
        }
    }
}
