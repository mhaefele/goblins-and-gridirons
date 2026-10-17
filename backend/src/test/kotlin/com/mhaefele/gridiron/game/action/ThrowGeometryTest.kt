package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.game.action.utils.GridOffset
import com.mhaefele.gridiron.game.action.utils.ThrowRulerOffsets
import com.mhaefele.gridiron.game.action.utils.inThrowRulerRange
import com.mhaefele.gridiron.game.action.utils.rulerDistance
import com.mhaefele.gridiron.game.action.utils.rulerLineSquares
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.action.utils.throwObstructed
import com.mhaefele.gridiron.game.action.utils.throwPathInteriorSquares
import com.mhaefele.gridiron.game.action.utils.throwRange
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class ThrowGeometryTest {
    private val board = GameScenario.board()

    @Test
    fun `short throw is orthogonal within four`() {
        val thrower = board.addPlayer(5, 6)
        val catcher = board.addPlayer(5, 4, team = thrower.team)

        val range = throwRange(
            board.board.findSquare(thrower.player.id),
            board.board.findSquare(catcher.player.id),
        )

        assertThat(range).isEqualTo(ThrowRange.SHORT)
    }

    @Test
    fun `long throw is orthogonal five to eight`() {
        val thrower = board.addPlayer(5, 6)
        val catcher = board.addPlayer(5, 12, team = thrower.team)

        val range = throwRange(
            board.board.findSquare(thrower.player.id),
            board.board.findSquare(catcher.player.id),
        )

        assertThat(range).isEqualTo(ThrowRange.LONG)
    }

    @Test
    fun `orthogonal tip of ruler is still long`() {
        val thrower = board.addPlayer(1, 8)
        val catcher = board.addPlayer(9, 8, team = thrower.team) // dx=8

        assertThat(
            throwRange(
                board.board.findSquare(thrower.player.id),
                board.board.findSquare(catcher.player.id),
            ),
        ).isEqualTo(ThrowRange.LONG)
    }

    @Test
    fun `diagonal costs more than orthogonal step count`() {
        // 4 steps diagonally → Euclidean 4√2 ≈ 5.66 → Long (Within would have called this Short).
        assertThat(rulerDistance(Coordinate(5, 6), Coordinate(9, 10))).isEqualTo(4.0 * sqrt(2.0))
        val thrower = board.addPlayer(5, 6)
        val catcher = board.addPlayer(9, 10, team = thrower.team)
        assertThat(
            throwRange(
                board.board.findSquare(thrower.player.id),
                board.board.findSquare(catcher.player.id),
            ),
        ).isEqualTo(ThrowRange.LONG)
    }

    @Test
    fun `pure diagonal beyond ruler tip is out of range`() {
        // 6 steps at 45° → 6√2 ≈ 8.49 > 8
        assertThat(inThrowRulerRange(
            board.board.squareAt(Coordinate(2, 2)),
            board.board.squareAt(Coordinate(8, 8)),
        )).isFalse()
    }

    @Test
    fun `precomputed offsets match Euclidean short and long bands`() {
        assertThat(ThrowRulerOffsets.short).contains(GridOffset(4, 0), GridOffset(0, -3), GridOffset(2, 2))
        assertThat(ThrowRulerOffsets.short).doesNotContain(GridOffset(5, 0), GridOffset(4, 4))
        assertThat(ThrowRulerOffsets.long).contains(GridOffset(5, 0), GridOffset(8, 0), GridOffset(4, 4), GridOffset(5, 5))
        assertThat(ThrowRulerOffsets.long).doesNotContain(GridOffset(6, 6), GridOffset(0, 0))
        assertThat(ThrowRulerOffsets.inRange).isEqualTo(ThrowRulerOffsets.short + ThrowRulerOffsets.long)
        // Every offset agrees with hypot banding.
        for (offset in ThrowRulerOffsets.inRange) {
            val length = rulerDistance(Coordinate(0, 0), Coordinate(offset.dx, offset.dy))
            if (offset in ThrowRulerOffsets.short) {
                assertThat(length).isLessThanOrEqualTo(4.0)
            } else {
                assertThat(length).isGreaterThan(4.0).isLessThanOrEqualTo(8.0)
            }
        }
    }

    @Test
    fun `beyond throw ruler is out of range`() {
        val thrower = board.addPlayer(1, 1)
        val far = board.board.squareAt(Coordinate(11, 15))

        assertThat(inThrowRulerRange(board.board.findSquare(thrower.player.id), far)).isFalse()
    }

    @Test
    fun `marked catcher is obstructed`() {
        val thrower = board.holdingBall(board.addPlayer(4, 6))
        val catcher = board.addPlayer(6, 6, team = thrower.team)
        board.addPlayer(6, 5, team = thrower.team.opposite())

        assertThat(standingStatus(catcher.player, board.board)).isEqualTo(StandingStatus.MARKED)
        assertThat(throwObstructed(thrower.player, catcher.player, board.board)).isTrue()
    }

    @Test
    fun `open opposition under throw path is obstructed`() {
        val thrower = board.holdingBall(board.addPlayer(4, 6))
        val catcher = board.addPlayer(4, 4, team = thrower.team)
        board.addPlayer(4, 5, team = thrower.team.opposite())

        assertThat(throwObstructed(thrower.player, catcher.player, board.board)).isTrue()
    }

    @Test
    fun `ruler line takes only the diagonal square when crossing a grid corner`() {
        val line = rulerLineSquares(Coordinate(0, 0), Coordinate(2, 2))
        assertThat(line).containsExactly(Coordinate(0, 0), Coordinate(1, 1), Coordinate(2, 2))
    }

    @Test
    fun `throw path interiors exclude endpoints`() {
        val thrower = board.addPlayer(2, 2)
        val catcher = board.addPlayer(2, 5, team = thrower.team)
        val interiors = throwPathInteriorSquares(
            board.board.findSquare(thrower.player.id),
            board.board.findSquare(catcher.player.id),
            board.board,
        )
        assertThat(interiors).containsExactly(Coordinate(2, 3), Coordinate(2, 4))
    }

    @Test
    fun `blocked square on a corner-only flank does not obstruct`() {
        // Shallow diagonal: centre-to-centre enters (5,4) and (6,5), not flank (6,4).
        val board = GameScenario.tinyBoard(width = 10, height = 10, blocked = setOf(Coordinate(6, 4)))
        val thrower = board.holdingBall(board.addPlayer(4, 4))
        val catcher = board.addPlayer(7, 5, team = thrower.team)

        assertThat(throwRange(
            board.board.findSquare(thrower.player.id),
            board.board.findSquare(catcher.player.id),
        )).isEqualTo(ThrowRange.SHORT)
        assertThat(throwObstructed(thrower.player, catcher.player, board.board)).isFalse()
    }

    @Test
    fun `blocked square on the actual diagonal corridor does obstruct`() {
        val board = GameScenario.tinyBoard(width = 10, height = 10, blocked = setOf(Coordinate(6, 4)))
        val thrower = board.holdingBall(board.addPlayer(5, 5))
        val catcher = board.addPlayer(7, 3, team = thrower.team)

        assertThat(throwObstructed(thrower.player, catcher.player, board.board)).isTrue()
    }
}
