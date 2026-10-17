package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.model.TrainingScenarioEnum

/** Curriculum family for phase gates (board drills vs challenge-claim drills). */
enum class TrainingScenarioFamily {
    BOARD,
    CLAIM,
}

/**
 * Wire shape for [GET /api/ai/training/scenarios].
 *
 * [difficultyProxy] — when set, harness stores/reads difficulty under that scenario's name and
 * skips scenario-specific after-eval; [gatesProgress] must be false.
 *
 * [gatesProgress] — when false, drill still trains/evals/climbs d0→d5 but does not block
 * curriculum unlock gates. See [AbstractTrainingScenario.gatesProgress].
 */
data class TrainingScenarioCatalogEntry(
    val name: TrainingScenarioEnum,
    val family: TrainingScenarioFamily,
    val difficultyProxy: TrainingScenarioEnum? = null,
    val gatesProgress: Boolean = true,
)
