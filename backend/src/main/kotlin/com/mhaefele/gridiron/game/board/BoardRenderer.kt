package com.mhaefele.gridiron.game.board

import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.BlockedSquare
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.EndZoneSquare
import com.mhaefele.gridiron.model.board.FieldSquare
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.model.board.TrapdoorSquare

/**
 * Fixed-width ASCII snapshot of a [Board] for logs, tests, and debugging.
 *
 * **Glyphs:** `#` out of bounds, `=` / `-` empty end zones, `.` field, `X` blocked,
 * `+` trapdoor, `H`/`h` home standing/prone, `A`/`a` away standing/prone,
 * `@`/`%` home/away ball carrier, `o` loose ball.
 */
fun renderBoard(board: Board): String {
    val all = board.allSquares()
    val xRange = all.minOf { it.coordinate.x }..all.maxOf { it.coordinate.x }
    val yRange = all.minOf { it.coordinate.y }..all.maxOf { it.coordinate.y }
    return yRange.joinToString("\n") { y ->
        xRange.joinToString("") { x ->
            renderSquare(board, board.squareAt(Coordinate(x, y))).toString()
        }
    }
}

/** Portrait board with a compact glyph key — for playthrough / debug output. */
fun renderBoardForDisplay(board: Board): String =
    sideBySide(renderBoard(board), BOARD_LEGEND)

private val BOARD_LEGEND = """
    Hh HOME
    Aa AWAY
    @/% ball
    o loose
    . field
    X block
    + trap
    = - end
    # oob
""".trimIndent()

private fun sideBySide(left: String, right: String, gap: Int = 2): String {
    val leftLines = left.lines()
    val rightLines = right.lines()
    val leftWidth = leftLines.maxOfOrNull { it.length } ?: 0
    val height = maxOf(leftLines.size, rightLines.size)
    return (0 until height).joinToString("\n") { row ->
        val left = leftLines.getOrElse(row) { "" }.padEnd(leftWidth)
        val right = rightLines.getOrElse(row) { "" }
        if (right.isEmpty()) left else left + " ".repeat(gap) + right
    }
}

private fun renderSquare(board: Board, square: BoardSquare): Char {
    square.playerId?.let { playerId ->
        return playerGlyph(board.players.getValue(playerId))
    }
    return if (square.looseBallId != null) 'o' else terrainGlyph(square)
}

private fun playerGlyph(player: Player): Char {
    val carriesBall = player.holdsBall()
    return when (player.team) {
        TeamSide.HOME -> when (player.status) {
            PlayerStatus.STANDING -> if (carriesBall) '@' else 'H'
            PlayerStatus.PRONE    -> if (carriesBall) '@' else 'h'
            PlayerStatus.RESERVES -> error("Reserves player ${player.id} must not appear on the pitch")
        }
        TeamSide.AWAY -> when (player.status) {
            PlayerStatus.STANDING -> if (carriesBall) '%' else 'A'
            PlayerStatus.PRONE    -> if (carriesBall) '%' else 'a'
            PlayerStatus.RESERVES -> error("Reserves player ${player.id} must not appear on the pitch")
        }
    }
}

private fun terrainGlyph(square: BoardSquare): Char = when (square) {
    is OutOfBoundsSquare -> '#'
    is EndZoneSquare     -> if (square.team == TeamSide.HOME) '=' else '-'
    is BlockedSquare     -> 'X'
    is TrapdoorSquare    -> '+'
    is FieldSquare       -> '.'
}
