package com.mhaefele.gridiron.model

/**
 * Curriculum drill grade for a training scenario episode (metrics / winrate).
 * Harness may apply a small coach-window terminal from the mapped US/THEM outcome.
 *
 * Stored on [TrainingEpisodeConfig]; only ask the scenario again while [STILL_IN_PROGRESS].
 * [PASS] sticks for the rest of the episode.
 */
enum class TrainingDrillStatus {
    /** Drill solved (metrics US). */
    PASS,

    /**
     * Opening coach turn missed the lesson but trainer score gained ≥ [AbstractTrainingScenario.expectedOpeningTurnPoints].
     * Metrics draw — e.g. claim drill banked a higher-value filler instead of focus.
     * Wire: [com.mhaefele.gridiron.api.ai.dto.AiTrainingOutcome.DISTRACTED].
     */
    DISTRACTED,

    /** Neither yet — keep evaluating after later submits. Window end without PASS → THEM. */
    STILL_IN_PROGRESS,
}
