package com.mhaefele.gridiron.game.action.utils

import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Whether a standing player is Open or Marked from board adjacency.
 * Prone and reserves players do not have a standing status — callers must not ask.
 */
fun standingStatus(player: Player, board: Board): StandingStatus {
    require(player.status == PlayerStatus.STANDING) { "standingStatus applies only to standing players, was ${player.status} for ${player.id}" }
    return standingStatusAt(board.findSquare(player.id).coordinate, player.team, board)
}

fun standingStatus(coordinate: Coordinate, board: Board): StandingStatus =
    standingStatus(board.getPlayerAt(coordinate), board)

/** Whether [marker] is adjacent to standing opposition [target] (rulebook Mark / Block target). */
fun playerMarksOpponent(marker: Player, target: Player, board: Board): Boolean {
    if (marker.team == target.team) return false
    if (marker.status != PlayerStatus.STANDING || target.status != PlayerStatus.STANDING) return false
    return distance(board.findSquare(marker.id), board.findSquare(target.id), board) == 1
}

/** Whether [marker] is adjacent to standing opposition [target] (rulebook Mark / Block target). */
fun playerMarksSquare(marker: Player, coordinate: Coordinate, board: Board): Boolean {
    if (marker.status != PlayerStatus.STANDING) return false
    return distance(board.findSquare(marker.id), board.squareAt(coordinate), board) == 1
}

/** Open/Marked for a standing player of [team] at [coordinate] (digest § Marking and status). */
fun standingStatusAt(coordinate: Coordinate, team: TeamSide, board: Board): StandingStatus {
    val square = board.squareAt(coordinate)
    val adjacentStandingOpposition = neighbors(square, board)
        .mapNotNull { neighbor -> neighbor.playerId?.let { board.players.getValue(it) } }
        .any { occupant -> occupant.team != team && occupant.status == PlayerStatus.STANDING }

    return if (adjacentStandingOpposition) StandingStatus.MARKED else StandingStatus.OPEN
}
