package com.mhaefele.gridiron.game.action.utils

import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.Coordinate

fun isAssistedBlock(blocker: Player, target: Player, board: Board): Boolean {
    // Juggernaut / Lobber / Wild Ward: Block Actions cannot be Assisted.
    if (blocker.hasAbility(PlayerAbility.Juggernaut)) return false
    if (blocker.hasAbility(PlayerAbility.Lobber)) return false
    if (target.hasAbility(PlayerAbility.`Wild Ward`)) return false

    return board.players.values.any { teammate ->
        teammate.id != blocker.id &&
            teammate.team == blocker.team &&
            teammate.status == PlayerStatus.STANDING &&
            playerMarksOpponent(teammate, target, board) &&
            !isAdjacentToDefensiveOpposition(teammate, board)
    }
}

/**
 * Disruptor: Opposition Players adjacent to this Player can never Assist a Block Action.
 * So a would-be assister adjacent to any Disruptor opposition player cannot assist.
 */
private fun isAdjacentToDefensiveOpposition(assister: Player, board: Board): Boolean {
    val square = board.findSquare(assister.id)
    return neighbors(square, board).any { neighbor ->
        val id = neighbor.playerId ?: return@any false
        val occupant = board.players.getValue(id)
        occupant.team != assister.team && occupant.status == PlayerStatus.STANDING && occupant.hasAbility(PlayerAbility.Disruptor)
    }
}

fun pushSquareAwayFrom(blocker: Coordinate, target: Coordinate): Coordinate {
    val dx = (target.x - blocker.x).coerceIn(-1, 1)
    val dy = (target.y - blocker.y).coerceIn(-1, 1)
    require(dx != 0 || dy != 0) { "Blocker at $blocker must be adjacent to target at $target" }
    return Coordinate(target.x + dx, target.y + dy)
}
