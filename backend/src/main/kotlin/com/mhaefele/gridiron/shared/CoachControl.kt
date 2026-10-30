package com.mhaefele.gridiron.shared

/**
 * Who submits actions for a coach seat.
 *
 * Three roles — wire names match the job:
 * - [HUMAN] — browser coach (GUI mid-check prompts, e.g. Enforcer order when meaningful).
 * - [HARNESS] — external HTTP chooser (PPO / expert dump / integration agent); no in-process agent.
 * - [COMPUTER] — server starts an in-process [com.mhaefele.gridiron.game.opponent.ComputerCoachAgent].
 *
 * Only [COMPUTER] spawns an opponent. Do not use [HUMAN] for bots or harness submitters.
 */
enum class CoachControl {
    HUMAN,
    COMPUTER,
    /** External HTTP submits — does not spawn an in-process opponent. */
    HARNESS,
}
