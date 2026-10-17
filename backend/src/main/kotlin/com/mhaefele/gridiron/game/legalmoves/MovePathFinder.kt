package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.game.action.ability.isAdjacentToUnderfootOpposition
import com.mhaefele.gridiron.game.action.ability.mayRunAdjacentToOpponents
import com.mhaefele.gridiron.game.action.ability.mustEndRunOpen
import com.mhaefele.gridiron.game.action.ability.runMayEnterSquare
import com.mhaefele.gridiron.game.action.ability.runMayStepTo
import com.mhaefele.gridiron.game.action.ability.runPathEndsLegally
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.action.utils.standingStatusAt

/**
 * Run — up to [moveAllowance] steps. Normal runners cannot enter a square adjacent to standing
 * opposition (except Underfoot / Beneath Notice). Elusive / Nimble / Vault / Ethereal / Weave /
 * Hold the Line may enter adjacent but must end Open. Underfoot: entering adjacent ends the Run.
 *
 * One route per destination (shortest, then fewest diagonals) via BFS — not every simple path.
 */
fun runPaths(
    player: Player,
    board: Board,
    moveAllowance: Int,
    bonuses: PathBonusFlags = PathBonusFlags(),
    /** Strike and Fade: may finish adjacent even without Elusive. */
    allowEndAdjacent: Boolean = false,
): List<Path> {
    require(player.status == PlayerStatus.STANDING) { "Run paths apply only to standing players, was ${player.status} for ${player.id}" }
    val mayAdjacent = allowEndAdjacent || mayRunAdjacentToOpponents(player, bonuses.weave || bonuses.holdTheLine)
    val mustEndOpen = !allowEndAdjacent && mustEndRunOpen(player, bonuses.weave || bonuses.holdTheLine)
    return findBestPaths(
        player = player,
        board = board,
        moveAllowance = moveAllowance,
        canEnter = { square ->
            runMayEnterSquare(square, player, board) && runMayStepTo(square, player, board, mayAdjacent)
        },
        mustStopAt = { coord ->
            val square = board.squareAt(coord)
            square.looseBallId != null || isAdjacentToUnderfootOpposition(square, board, player.team)
        },
        isDestination = { coord ->
            runPathEndsLegally(coord, player, board, mayAdjacent, mustEndOpen)
        },
    )
}

/**
 * Mark — up to 2 steps (4 with Press); may enter opposition-adjacent squares; must end Marked.
 */
fun markPaths(player: Player, board: Board, bonuses: PathBonusFlags = PathBonusFlags()): List<Path> {
    require(player.status == PlayerStatus.STANDING) { "Mark paths apply only to standing players, was ${player.status} for ${player.id}" }
    val allowance = if (bonuses.press) 4 else 2
    return findBestPaths(
        player = player,
        board = board,
        moveAllowance = allowance,
        canEnter = ::isEnterable,
        mustStopAt = { coord ->
            val square = board.squareAt(coord)
            square.looseBallId != null || standingStatusAt(coord, player.team, board) == StandingStatus.MARKED
        },
        isDestination = { coord ->
            standingStatusAt(coord, player.team, board) == StandingStatus.MARKED
        },
    )
}

/**
 * Sidestep — exactly 1 step from Marked to Open (digest § Sidestep).
 * Loose-ball square ends the path (bounce).
 */
fun sidestepPaths(player: Player, board: Board): List<Path> {
    require(player.status == PlayerStatus.STANDING) { "Sidestep applies only to standing players, was ${player.status} for ${player.id}" }
    require(standingStatus(player, board) == StandingStatus.MARKED) { "Sidestep applies only to marked players, was ${standingStatus(player, board)} for ${player.id}" }
    return findBestPaths(
        player = player,
        board = board,
        moveAllowance = 1,
        canEnter = ::isEnterable,
        mustStopAt = { coord -> board.squareAt(coord).looseBallId != null },
        isDestination = { coord ->
            standingStatusAt(coord, player.team, board) == StandingStatus.OPEN
        },
    )
}

/**
 * BFS for the best simple path to each reachable square (min steps, then fewest diagonals).
 * [mustStopAt] prevents expanding further (loose ball, Underfoot adjacency, first Marked square, …).
 * [isDestination] gates which arrivals are offered as legal actions.
 */
private fun findBestPaths(
    player: Player,
    board: Board,
    moveAllowance: Int,
    canEnter: (BoardSquare)     -> Boolean,
    mustStopAt: (Coordinate)    -> Boolean,
    isDestination: (Coordinate) -> Boolean,
): List<Path> {
    require(moveAllowance >= 0) { "moveAllowance must be non-negative, was $moveAllowance" }
    require(player.status != PlayerStatus.RESERVES) { "Cannot pathfind for reserves player ${player.id}" }
    val startSquare = board.findSquare(player.id)
    require(startSquare !is OutOfBoundsSquare) { "Cannot pathfind from out of bounds square ${startSquare.coordinate}" }
    if (moveAllowance == 0) return emptyList()

    val start = startSquare.coordinate
    val startPath = Path(listOf(start))
    /** Best path found so far that *ends* on this coordinate (for expansion). */
    val bestArrival = hashMapOf(start to startPath)
    /** Best legal-action path per destination. */
    val destinations = hashMapOf<Coordinate, Path>()
    val queue = ArrayDeque<Path>()
    queue.add(startPath)

    while (queue.isNotEmpty()) {
        val path = queue.removeFirst()
        val at = path.squares.last()
        // Stale queue entry after a better arrival replaced this path.
        if (bestArrival[at] != path) continue
        if (path.steps >= moveAllowance) continue

        for (next in neighbors(board.squareAt(at), board)) {
            val dest = next.coordinate
            if (dest in path.squares || !canEnter(next)) continue
            val extended = Path(path.squares + dest)

            val previousArrival = bestArrival[dest]
            if (previousArrival != null && !isBetterPath(extended, previousArrival)) continue
            bestArrival[dest] = extended

            if (isDestination(dest)) {
                destinations[dest] = extended
            }

            if (next.looseBallId != null || mustStopAt(dest)) continue
            if (extended.steps < moveAllowance) {
                queue.add(extended)
            }
        }
    }

    return destinations.values.toList()
}

/** Prefer fewer steps; among equals, fewer diagonal steps. */
private fun isBetterPath(candidate: Path, incumbent: Path): Boolean =
    candidate.steps < incumbent.steps || (candidate.steps == incumbent.steps && candidate.diagonalSteps < incumbent.diagonalSteps)

private fun isEnterable(square: BoardSquare): Boolean =
    !square.isBlocked() && square.playerId == null
