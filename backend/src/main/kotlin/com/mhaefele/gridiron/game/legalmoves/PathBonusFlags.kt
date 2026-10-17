package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.model.TurnBonusModifiers

/**
 * Ephemeral pathfinding / allowance flags for one legal-move generate call.
 * Not turn gamestate — Sprint / Weave / Press come from hand expansion
 * ([ActionAttachedBonuses]); [holdTheLine] is copied from [TurnBonusModifiers].
 */
data class PathBonusFlags(
    val sprint: Boolean = false,
    val weave: Boolean = false,
    val press: Boolean = false,
    val holdTheLine: Boolean = false,
)

fun TurnBonusModifiers.toPathFlags(
    sprint: Boolean = false,
    weave: Boolean = false,
    press: Boolean = false,
): PathBonusFlags =
    PathBonusFlags(
        sprint = sprint,
        weave = weave,
        press = press,
        holdTheLine = holdTheLine,
    )

/** Sprint adds 2 to Move for a Sprint-tagged opening Run or HANDLE_SPRINT commit (baked into budget). */
fun PathBonusFlags.sprintMoveBonus(): Int = if (sprint) 2 else 0
