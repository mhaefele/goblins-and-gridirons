package com.mhaefele.gridiron.game.opponent.search

/**
 * Three-way leaf signal for [DeterministicActionSearch] — the **one** customization point for v1.
 *
 * Stock pattern (game-tree / AND-OR style): the search owns expansion + depth; the caller owns
 * “are we done?”. Do **not** fold ranking, pruning, or soft-mask policy into this enum.
 *
 * - [SUCCESS] — goal reached; stop and treat the root as solvable.
 * - [FAILURE] — this line is dead (wrong coach, terminal miss, illegal apply); do not expand.
 * - [CONTINUE] — keep searching (settle mid-action, or branch the next coach menu).
 */
enum class SearchOutcome {
    SUCCESS,
    FAILURE,
    CONTINUE,
}
