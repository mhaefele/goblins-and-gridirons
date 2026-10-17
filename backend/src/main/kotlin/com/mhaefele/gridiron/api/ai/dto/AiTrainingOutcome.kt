package com.mhaefele.gridiron.api.ai.dto

/** Drill grade when a training scenario gym episode ends — harness / curriculum only (not obs-packed). */
enum class AiTrainingOutcome {
    /** Focus lesson solved ([com.mhaefele.gridiron.model.TrainingDrillStatus.PASS]). */
    US,

    /** Coach window closed without pass ([com.mhaefele.gridiron.model.TrainingDrillStatus.STILL_IN_PROGRESS]). */
    THEM,

    /** Opening turn scored but missed the focus ([com.mhaefele.gridiron.model.TrainingDrillStatus.DISTRACTED]). */
    DISTRACTED,
}
