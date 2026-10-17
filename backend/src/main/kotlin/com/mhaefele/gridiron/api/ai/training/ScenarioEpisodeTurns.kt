package com.mhaefele.gridiron.api.ai.training

/**
 * Coach-turn window for scenario episodes (harness + expert export).
 * Difficulties 0–3: trainer's turn only (more first-move repetition).
 * Difficulties 4–5: three coach turns (opponent replies / board context).
 * Drill PASS is only evaluated on the opening coach turn ([AbstractTrainingScenario.drillStatus]).
 */
fun scenarioCoachTurnWindow(difficulty: Int): Int =
    if (difficulty <= 3) 1 else 3
