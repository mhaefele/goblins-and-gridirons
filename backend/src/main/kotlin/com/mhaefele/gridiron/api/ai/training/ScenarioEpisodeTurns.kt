package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.TrainingScenarioEnum

/**
 * Coach-turn window for scenario episodes (harness + expert export).
 * Difficulties 0–3: trainer's turn only (more first-move repetition).
 * Difficulties 4–5: six coach turns (opponent replies / board context).
 * Drill PASS is only evaluated on the opening coach turn ([AbstractTrainingScenario.drillStatus]).
 */
fun scenarioCoachTurnWindow(difficulty: Int): Int =
    if (difficulty <= 3) 1 else 6

/**
 * Experiment: [TrainingScenarioEnum.LOOSE_BALL_RUN] **d0–d1** close the gym episode after the
 * first opening budgeted action (PASS on pickup, THEM otherwise) — no continue-to-score.
 * Owned next to the coach window so [TrainingEpisodeEnd] stays Spring-free.
 */
fun scenarioEndsAfterFirstOpeningAction(episode: TrainingEpisodeConfig): Boolean =
    episode.scenario == TrainingScenarioEnum.LOOSE_BALL_RUN && episode.difficulty in 0..1
