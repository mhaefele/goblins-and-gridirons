package com.mhaefele.gridiron.api.ai.dto

/**
 * Drill grade on the harness — curriculum / reward only (not obs-packed).
 *
 * [US] may appear mid coach-window once [com.mhaefele.gridiron.model.TrainingDrillStatus.PASS]
 * is set; [THEM] / [DISTRACTED] only when the gym episode ends.
 */
enum class AiTrainingOutcome {
    /** Focus lesson solved ([com.mhaefele.gridiron.model.TrainingDrillStatus.PASS]). */
    US,

    /** Coach window closed without pass ([com.mhaefele.gridiron.model.TrainingDrillStatus.STILL_IN_PROGRESS]). */
    THEM,

    /** Opening turn scored but missed the focus ([com.mhaefele.gridiron.model.TrainingDrillStatus.DISTRACTED]). */
    DISTRACTED,
}
