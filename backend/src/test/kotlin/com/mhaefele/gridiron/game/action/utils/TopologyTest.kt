package com.mhaefele.gridiron.game.action.utils

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TopologyTest {

    private val board = GameScenario.emptyBoard()

    @Test
    fun `playable corner has eight neighbors including border squares`() {
        val corner = board.squareAt(Coordinate(1, 1))
        assertThat(neighbors(corner, board)).containsExactlyInAnyOrder(
            board.squareAt(Coordinate(1, 0)),
            board.squareAt(Coordinate(2, 0)),
            board.squareAt(Coordinate(2, 1)),
            board.squareAt(Coordinate(2, 2)),
            board.squareAt(Coordinate(1, 2)),
            board.squareAt(Coordinate(0, 2)),
            board.squareAt(Coordinate(0, 1)),
            board.squareAt(Coordinate(0, 0)),
        )
    }

    @Test
    fun `playable center has eight neighbors`() {
        val center = board.squareAt(Coordinate(6, 6))
        assertThat(neighbors(center, board)).containsExactlyInAnyOrder(
            board.squareAt(Coordinate(6, 5)),
            board.squareAt(Coordinate(7, 5)),
            board.squareAt(Coordinate(7, 6)),
            board.squareAt(Coordinate(7, 7)),
            board.squareAt(Coordinate(6, 7)),
            board.squareAt(Coordinate(5, 7)),
            board.squareAt(Coordinate(5, 6)),
            board.squareAt(Coordinate(5, 5)),
        )
    }

    @Test
    fun `neighbors rejects out of bounds square`() {
        val outOfBounds = board.squareAt(Coordinate(0, 0))
        assertThat(outOfBounds).isInstanceOf(OutOfBoundsSquare::class.java)
        assertThatThrownBy { neighbors(outOfBounds, board) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `same square is within zero and has distance zero`() {
        val square = board.squareAt(Coordinate(5, 6))
        assertThat(distance(square, square, board)).isZero()
        assertThat(within(square, square, 0, board)).isTrue()
    }

    @Test
    fun `adjacent squares are within one`() {
        val from = board.squareAt(Coordinate(5, 6))
        val to = board.squareAt(Coordinate(6, 6))
        assertThat(distance(from, to, board)).isEqualTo(1)
        assertThat(within(from, to, 1, board)).isTrue()
        assertThat(within(from, to, 0, board)).isFalse()
    }

    @Test
    fun `distance counts through blocked square on shortest path`() {
        val from = board.squareAt(Coordinate(3, 3))
        val to = board.squareAt(Coordinate(3, 5))
        assertThat(board.squareAt(Coordinate(3, 4)).isBlocked()).isTrue()
        assertThat(distance(from, to, board)).isEqualTo(2)
        assertThat(within(from, to, 2, board)).isTrue()
        assertThat(within(from, to, 1, board)).isFalse()
    }

    @Test
    fun `distance counts through occupied square`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(5, 6)
        val occupied = scenario.board
        val from = occupied.squareAt(Coordinate(4, 6))
        val to = occupied.squareAt(Coordinate(6, 6))
        assertThat(distance(from, to, occupied)).isEqualTo(2)
    }

    @Test
    fun `shortest path is inclusive of both endpoints`() {
        val from = board.squareAt(Coordinate(5, 6))
        val to = board.squareAt(Coordinate(7, 6))
        val path = shortestPath(from, to, board)
        assertThat(path.squares.first()).isEqualTo(Coordinate(5, 6))
        assertThat(path.squares.last()).isEqualTo(Coordinate(7, 6))
        assertThat(path.squares).hasSize(3)
    }

    @Test
    fun `shortest path on same square is a single coordinate`() {
        val square = board.squareAt(Coordinate(5, 6))
        assertThat(shortestPath(square, square, board).squares).containsExactly(Coordinate(5, 6))
    }

    @Test
    fun `far distance across the pitch stays linear not exponential`() {
        // Default pitch playable interior is 11×15 (PitchCatalog.default).
        val from = board.squareAt(Coordinate(1, 1))
        val to = board.squareAt(Coordinate(11, 15))
        val started = System.nanoTime()
        assertThat(distance(from, to, board)).isEqualTo(14)
        // Pre-fix BFS duplicated frontier cells and took seconds on this span.
        assertThat(System.nanoTime() - started).isLessThan(50_000_000L)
    }
}
