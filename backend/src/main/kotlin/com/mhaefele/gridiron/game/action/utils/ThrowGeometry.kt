package com.mhaefele.gridiron.game.action.utils

import com.mhaefele.gridiron.api.dto.response.ThrowTarget
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.ThrowRange
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Throw range and obstruction — see [docs/throw-ruler.md](../../../../../../../../docs/throw-ruler.md).
 *
 * Range bands use centre-to-centre Euclidean length (ruler inches in square units).
 * Obstruction interiors use a centre-to-centre grid line (positive-length intersections), not shortest path.
 *
 * Cardboard Throw Ruler measures **8** squares centre-to-centre on axis; Short/Long split at half.
 */

/** Short (black) outer edge — Euclidean centre-to-centre ≤ this (half of [THROW_RULER_MAX]). */
const val THROW_RULER_SHORT_MAX = 4.0

/** Full ruler outer edge — Euclidean ≤ this (8 squares on axis, from the physical ruler). */
const val THROW_RULER_MAX = 8.0

/** Integer (dx, dy) from thrower square to a target square. */
data class GridOffset(val dx: Int, val dy: Int)

/**
 * Every relative square whose centre falls in Short or Long under [rulerDistance].
 * Computed once — same results as testing hypot for each angle.
 */
object ThrowRulerOffsets {
    val short: Set<GridOffset>
    val long: Set<GridOffset>
    /** Short ∪ Long (excludes the thrower's own square). */
    val inRange: Set<GridOffset>

    init {
        val shortOffsets = mutableSetOf<GridOffset>()
        val longOffsets = mutableSetOf<GridOffset>()
        val bound = kotlin.math.ceil(THROW_RULER_MAX).toInt()
        for (dx in -bound..bound) {
            for (dy in -bound..bound) {
                if (dx == 0 && dy == 0) continue
                val length = hypot(dx.toDouble(), dy.toDouble())
                when {
                    length <= THROW_RULER_SHORT_MAX -> shortOffsets.add(GridOffset(dx, dy))
                    length <= THROW_RULER_MAX       -> longOffsets.add(GridOffset(dx, dy))
                }
            }
        }
        short = shortOffsets
        long = longOffsets
        inRange = shortOffsets + longOffsets
    }
}

/** Straight-line distance between square centres (one square = 1.0). */
fun rulerDistance(from: Coordinate, to: Coordinate): Double =
    hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble())

fun inThrowRulerRange(from: BoardSquare, to: BoardSquare): Boolean =
    rulerDistance(from.coordinate, to.coordinate) <= THROW_RULER_MAX

fun throwRange(from: BoardSquare, to: BoardSquare): ThrowRange {
    require(from.coordinate != to.coordinate) { "Use ThrowRange.HAND_OFF for adjacent hand-off, not throwRange()" }
    val length = rulerDistance(from.coordinate, to.coordinate)
    require(length <= THROW_RULER_MAX) { "Throw target ${to.coordinate} is beyond Throw Ruler(≤ $THROW_RULER_MAX), was $length" }
    return if (length <= THROW_RULER_SHORT_MAX) ThrowRange.SHORT else ThrowRange.LONG
}

fun throwTarget(thrower: Player, catcher: Player, board: Board): ThrowTarget {
    val throwerSquare = board.findSquare(thrower.id)
    val catcherSquare = board.findSquare(catcher.id)
    val adjacent = distance(throwerSquare, catcherSquare, board) == 1
    return ThrowTarget(
        destination = catcherSquare.coordinate,
        range = if (adjacent) ThrowRange.HAND_OFF else throwRange(throwerSquare, catcherSquare),
        obstructed = if (adjacent) false else throwObstructed(thrower, catcher, board),
    )
}

fun throwObstructed(thrower: Player, catcher: Player, board: Board): Boolean {
    if (standingStatus(catcher, board) == StandingStatus.MARKED) return true
    val throwerSquare = board.findSquare(thrower.id)
    val catcherSquare = board.findSquare(catcher.id)
    return throwPathInteriorSquares(throwerSquare, catcherSquare, board).any { coord ->
        val square = board.squareAt(coord)
        square.isBlocked() || squareHasOpenOpposition(square, thrower.team, board)
    }
}

/**
 * Squares the Throw Ruler crosses between [from] and [to], excluding endpoints.
 * Uses the centre-to-centre grid line (squares entered with positive length).
 */
fun throwPathInteriorSquares(from: BoardSquare, to: BoardSquare, board: Board): List<Coordinate> {
    val path = rulerLineSquares(from.coordinate, to.coordinate)
    if (path.size <= 2) return emptyList()
    return path.drop(1).dropLast(1).filter { board.squareAt(it) !is com.mhaefele.gridiron.model.board.OutOfBoundsSquare }
}

/**
 * Grid squares a centre-to-centre segment from [from] to [to] enters with positive length.
 * Inclusive of both endpoints. Exact corner crossings take only the diagonal square — flanks that
 * share a single corner point are not on the ruler (they were falsely obstructing clear diagonals).
 *
 * @see <a href="http://www.redblobgames.com/grids/line-drawing/">grid line drawing</a>
 */
fun rulerLineSquares(from: Coordinate, to: Coordinate): List<Coordinate> {
    if (from == to) return listOf(from)
    val dx = abs(to.x - from.x)
    val dy = abs(to.y - from.y)
    val xInc = if (to.x >= from.x) 1 else -1
    val yInc = if (to.y >= from.y) 1 else -1
    var x = from.x
    var y = from.y
    val out = mutableListOf(Coordinate(x, y))

    // Distance along the line to the next vertical / horizontal grid boundary (param t in [0,1]).
    var tMaxX = if (dx == 0) Double.POSITIVE_INFINITY else 0.5 / dx
    var tMaxY = if (dy == 0) Double.POSITIVE_INFINITY else 0.5 / dy
    val tDeltaX = if (dx == 0) Double.POSITIVE_INFINITY else 1.0 / dx
    val tDeltaY = if (dy == 0) Double.POSITIVE_INFINITY else 1.0 / dy

    while (x != to.x || y != to.y) {
        when {
            tMaxX < tMaxY -> {
                x += xInc
                tMaxX += tDeltaX
                out.add(Coordinate(x, y))
            }
            tMaxY < tMaxX -> {
                y += yInc
                tMaxY += tDeltaY
                out.add(Coordinate(x, y))
            }
            else          -> {
                // Corner: only the diagonal cell has a positive-length intersection.
                x += xInc
                y += yInc
                tMaxX += tDeltaX
                tMaxY += tDeltaY
                out.add(Coordinate(x, y))
            }
        }
    }
    return out
}

private fun squareHasOpenOpposition(square: BoardSquare, throwingTeam: TeamSide, board: Board): Boolean {
    val occupantId = square.playerId ?: return false
    val occupant = board.players.getValue(occupantId)
    return occupant.team != throwingTeam && occupant.status == PlayerStatus.STANDING && standingStatus(occupant, board) == StandingStatus.OPEN
}
