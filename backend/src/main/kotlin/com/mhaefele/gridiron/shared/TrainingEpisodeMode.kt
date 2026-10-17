package com.mhaefele.gridiron.shared

/**
 * Reserved episode mode on [com.mhaefele.gridiron.model.TrainingEpisodeConfig].
 * Currently unused for legal filtering (full engine legals always); kept for future train/eval split.
 */
enum class TrainingEpisodeMode {
    TRAIN,
    EVAL,
}
