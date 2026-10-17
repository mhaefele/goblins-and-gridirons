package com.mhaefele.gridiron.game.action.utils

import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.shared.Direction
import com.mhaefele.gridiron.shared.Path

/**
 * Pitch geometry — adjacency, distance, and path topology. Pure functions over [Board];
 * no Spring.
 */

/** Eight-direction neighbors; throws if [square] is out of bounds (fail-fast — no sensible caller). */
fun neighbors(square: BoardSquare, board: Board): List<BoardSquare> {
    require(square !is OutOfBoundsSquare) { "Cannot compute neighbors for out-of-bounds square ${square.coordinate}" }
    return Direction.entries.map { board.squareAt(square.coordinate.neighbor(it)) }
}

/**
 * Shortest square-path distance (8-direction), counting through players and blocked terrain.
 * Digest § Player stats and team cards — Within.
 */
fun distance(from: BoardSquare, to: BoardSquare, board: Board): Int {
    requireOnPitch(from)
    requireOnPitch(to)
    if (from.coordinate == to.coordinate) return 0

    val visited = hashSetOf(from.coordinate)
    var frontier = listOf(from)
    var steps = 0

    while (frontier.isNotEmpty()) {
        steps++
        val next = ArrayList<BoardSquare>(frontier.size * 4)
        for (square in frontier) {
            for (neighbor in pitchStepNeighbors(square, board)) {
                val coord = neighbor.coordinate
                if (coord == to.coordinate) return steps
                if (visited.add(coord)) next.add(neighbor)
            }
        }
        frontier = next
    }

    throw IllegalArgumentException("No path from ${from.coordinate} to ${to.coordinate}")
}

/** Whether [to] is at most [n] squares from [from] by shortest path (rulebook Within). */
fun within(from: BoardSquare, to: BoardSquare, n: Int, board: Board): Boolean =
    distance(from, to, board) <= n

/** Shortest square path from [from] to [to], inclusive of both endpoints. */
fun shortestPath(from: BoardSquare, to: BoardSquare, board: Board): Path {
    requireOnPitch(from)
    requireOnPitch(to)
    if (from.coordinate == to.coordinate) return Path(listOf(from.coordinate))

    val parents = mutableMapOf<Coordinate, Coordinate>()
    val visited = hashSetOf(from.coordinate)
    var frontier = listOf(from.coordinate)

    while (frontier.isNotEmpty()) {
        val nextFrontier = ArrayList<Coordinate>(frontier.size * 4)
        for (current in frontier) {
            for (neighbor in pitchStepNeighbors(board.squareAt(current), board)) {
                val coord = neighbor.coordinate
                if (!visited.add(coord)) continue
                parents[coord] = current
                if (coord == to.coordinate) {
                    return Path(reconstructPath(parents, from.coordinate, to.coordinate))
                }
                nextFrontier.add(coord)
            }
        }
        frontier = nextFrontier
    }

    throw IllegalArgumentException("No path from ${from.coordinate} to ${to.coordinate}")
}

private fun reconstructPath(
    parents: Map<Coordinate, Coordinate>,
    start: Coordinate,
    end: Coordinate,
): List<Coordinate> {
    val path = mutableListOf(end)
    var current = end
    while (current != start) {
        current = parents.getValue(current)
        path.add(current)
    }
    return path.reversed()
}

private fun requireOnPitch(square: BoardSquare) {
    require(square !is OutOfBoundsSquare) { "Cannot measure distance from out-of-bounds square ${square.coordinate}" }
}

/** Neighbors used for Within measurement — stays on the pitch, not the padded border. */
private fun pitchStepNeighbors(square: BoardSquare, board: Board): List<BoardSquare> =
    neighbors(square, board).filter { it !is OutOfBoundsSquare }
