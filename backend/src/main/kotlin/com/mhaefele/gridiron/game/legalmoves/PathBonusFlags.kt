package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.model.TurnBonusModifiers

/**
 * Ephemeral pathfinding / allowance flags for one legal-move generate call.
 * Not turn gamestate — Sprint / Weave / Press come from hand expansion
 * ([ActionAttachedBonuses]); [holdTheLine] / [gangUp] are copied from [TurnBonusModifiers]
 * ([gangUp] feeds declare-time [com.mhaefele.gridiron.api.dto.response.LegalAction.Block.blockDiceCount]).
 */
data class PathBonusFlags(
    val sprint: Boolean = false,
    val weave: Boolean = false,
    val press: Boolean = false,
    val holdTheLine: Boolean = false,
    val gangUp: Boolean = false,
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
        gangUp = gangUp,
    )

/** Sprint adds 2 to Move for a Sprint-tagged opening Run or HANDLE_SPRINT commit (baked into budget). */
fun PathBonusFlags.sprintMoveBonus(): Int = if (sprint) 2 else 0
