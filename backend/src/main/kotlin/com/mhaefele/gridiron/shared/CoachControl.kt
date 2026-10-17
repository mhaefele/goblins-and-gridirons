package com.mhaefele.gridiron.shared

/** Who submits actions for a coach seat.

 * Three roles (cleanup: ai/BACKLOG.md AI2.15):
 * - [HUMAN] — browser coach (GUI mid-check prompts, e.g. Enforcer order when meaningful).
 * - [TRAINER] — external HTTP harness (PPO / expert dump); no in-process agent.
 * - [COMPUTER] — server starts an in-process computer opponent agent.
 *
 * TODO(AI2.15): rename/reshape roles (e.g. HARNESS) so names match the three meanings above.
 */
enum class CoachControl {
    HUMAN,
    COMPUTER,
    /** External HTTP submits (RL harness) — does not spawn an in-process opponent. */
    TRAINER,
}
