package com.mhaefele.gridiron.game.action.ability

import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide

fun mayRunAdjacentToOpponents(player: Player, holdTheLineOrWeave: Boolean = false): Boolean =
    holdTheLineOrWeave ||
        player.hasAbility(PlayerAbility.Elusive) ||
        player.hasAbility(PlayerAbility.`Nimble Beast`) ||
        player.hasAbility(PlayerAbility.Ethereal) ||
        player.hasAbility(PlayerAbility.Vault) ||
        player.hasAbility(PlayerAbility.Juggernaut)

/** Elusive / Nimble / Vault / Ethereal / Weave / Hold the Line must finish Open. */
fun mustEndRunOpen(player: Player, weaveOrHoldTheLine: Boolean = false): Boolean =
    weaveOrHoldTheLine ||
        player.hasAbility(PlayerAbility.Elusive) ||
        player.hasAbility(PlayerAbility.`Nimble Beast`) ||
        player.hasAbility(PlayerAbility.Vault) ||
        player.hasAbility(PlayerAbility.Ethereal)

/** Whether [player] may path through opponent-occupied squares. */
fun mayRunThroughOpponents(player: Player): Boolean =
    player.hasAbility(PlayerAbility.Ethereal) || player.hasAbility(PlayerAbility.Vault)

/** Whether [player] may path through teammate-occupied squares. */
fun mayRunThroughTeammates(player: Player): Boolean =
    player.hasAbility(PlayerAbility.Ethereal) || player.hasAbility(PlayerAbility.Vault)

/** Whether [player] may path through obstruction squares (Ethereal only). */
fun mayRunThroughObstructions(player: Player): Boolean =
    player.hasAbility(PlayerAbility.Ethereal)

fun runMayEnterSquare(square: BoardSquare, player: Player, board: Board): Boolean {
    if (square is com.mhaefele.gridiron.model.board.OutOfBoundsSquare) return false
    if (!square.isBlocked() && square.playerId == null) return true
    if (square.isBlocked()) {
        return mayRunThroughObstructions(player)
    }
    val occupant = board.players.getValue(square.playerId!!)
    return when {
        occupant.team != player.team -> mayRunThroughOpponents(player)
        else                         -> mayRunThroughTeammates(player)
    }
}

fun runMayStepTo(
    square: BoardSquare,
    player: Player,
    board: Board,
    mayEnterAdjacent: Boolean = mayRunAdjacentToOpponents(player),
): Boolean {
    if (!runMayEnterSquare(square, player, board)) return false
    if (mayEnterAdjacent) return true
    // Underfoot / Beneath Notice: opposition may enter adjacent to them.
    return !isAdjacentToStandingOppositionThatBlocksEntry(square, board, player.team)
}

fun runPathEndsLegally(
    pathEnd: Coordinate,
    player: Player,
    board: Board,
    mayEnterAdjacent: Boolean = mayRunAdjacentToOpponents(player),
    mustEndOpen: Boolean = mustEndRunOpen(player),
): Boolean {
    val square = board.squareAt(pathEnd)
    if (square.playerId != null) return false
    if (square.isBlocked() && !mayRunThroughObstructions(player)) return false
    // Ethereal/Vault must not end on obstruction either (enter mid-path only).
    if (square.isBlocked()) return false
    if (mustEndOpen) {
        return !isAdjacentToStandingOpposition(square, board, player.team)
    }
    if (mayEnterAdjacent) return true
    return !isAdjacentToStandingOppositionThatBlocksEntry(square, board, player.team)
}

/** Block targeting — Nimble Beast and Disruptor no longer ban being targeted. */
fun isBlockTargetLegal(blocker: Player, target: Player, board: Board): Boolean = true

/**
 * Standing opposition whose adjacency forbids normal Run entry.
 * Underfoot / Beneath Notice are ignored here (card: opposition may move adjacent to them).
 */
fun isAdjacentToStandingOppositionThatBlocksEntry(
    square: BoardSquare,
    board: Board,
    runnerTeam: TeamSide,
): Boolean =
    standingOppositionAdjacent(square, board, runnerTeam).any { occupant ->
        !occupant.hasAbility(PlayerAbility.Underfoot) && !occupant.hasAbility(PlayerAbility.`Beneath Notice`)
    }

/** True when adjacent to a Underfoot opposition — entering such a square ends the Run. */
fun isAdjacentToUnderfootOpposition(
    square: BoardSquare,
    board: Board,
    runnerTeam: TeamSide,
): Boolean =
    standingOppositionAdjacent(square, board, runnerTeam).any { it.hasAbility(PlayerAbility.Underfoot) }

fun isAdjacentToStandingOpposition(
    square: BoardSquare,
    board: Board,
    runnerTeam: TeamSide,
): Boolean = standingOppositionAdjacent(square, board, runnerTeam).isNotEmpty()

private fun standingOppositionAdjacent(
    square: BoardSquare,
    board: Board,
    runnerTeam: TeamSide,
): List<Player> =
    neighbors(square, board)
        .mapNotNull { neighbor -> neighbor.playerId?.let { board.players.getValue(it) } }
        .filter { occupant -> occupant.team != runnerTeam && occupant.status == PlayerStatus.STANDING }
