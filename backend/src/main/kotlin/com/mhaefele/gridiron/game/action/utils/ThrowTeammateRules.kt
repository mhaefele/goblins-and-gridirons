package com.mhaefele.gridiron.game.action.utils

import com.mhaefele.gridiron.api.dto.response.ThrowTarget
import com.mhaefele.gridiron.game.dice.throwCheckNeeded
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange

fun Player.canMakeThrowTeammateAction(): Boolean =
    profile.canThrowTeammate

/**
 * Adjacent standing teammate of a different position — Ogre cannot throw Ogre, etc.
 */
fun isThrowableTeammate(thrower: Player, teammate: Player): Boolean =
    teammate.team == thrower.team && teammate.id != thrower.id && teammate.status == PlayerStatus.STANDING && teammate.profile.name != thrower.profile.name

/**
 * Landing-square throw geometry — reuses ruler range / path obstruction from [ThrowGeometry],
 * but never treats adjacency as a hand-off (Throw Team-mate always rolls a Throw Check).
 * Occupant Marked does not force obstruction (that rule applies to ball-throw catchers).
 */
fun throwTeammateLandingTarget(thrower: Player, destination: Coordinate, board: Board): ThrowTarget {
    val from = board.findSquare(thrower.id)
    val to = board.squareAt(destination)
    require(to !is OutOfBoundsSquare) { "Throw Team-mate landing must be on the pitch, was $destination" }
    require(from.coordinate != destination) { "Cannot throw a team-mate onto the thrower's square" }
    val range = if (distance(from, to, board) == 1) ThrowRange.SHORT else throwRange(from, to)
    val obstructed = throwTeammatePathObstructed(thrower.team, from, to, board)
    return ThrowTarget(
        destination = destination,
        range = range,
        obstructed = obstructed,
        throwCheckNeeded = throwCheckNeeded(thrower.profile.stats.throwStat, range, obstructed),
    )
}

private fun throwTeammatePathObstructed(
    throwingTeam: TeamSide,
    from: BoardSquare,
    to: BoardSquare,
    board: Board,
): Boolean =
    throwPathInteriorSquares(from, to, board).any { coord ->
        val square = board.squareAt(coord)
        square.isBlocked() || squareHasOpenOpposition(square, throwingTeam, board)
    }

private fun squareHasOpenOpposition(square: BoardSquare, throwingTeam: TeamSide, board: Board): Boolean {
    val occupantId = square.playerId ?: return false
    val occupant = board.players.getValue(occupantId)
    return occupant.team != throwingTeam && occupant.status == PlayerStatus.STANDING && standingStatus(occupant, board) == StandingStatus.OPEN
}
