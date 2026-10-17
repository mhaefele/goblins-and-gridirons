package com.mhaefele.gridiron.model.board

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.Direction
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BoardTest {

    private val definition = BoardDefinition(
        playWidth = 10,
        playHeight = 12,
        trapdoors = setOf(Coordinate(6, 7)),
        blockedSquares = setOf(Coordinate(1, 6), Coordinate(10, 6)),
    )

    private val board = definition.emptyBoard()

    @Test
    fun `grid is play area plus one-square out of bounds border`() {
        assertThat(board.allSquares()).hasSize(12 * 14)
    }

    @Test
    fun `perimeter is out of bounds and interior uses pitch square types`() {
        assertThat(board.squareAt(Coordinate(0, 0))).isInstanceOf(OutOfBoundsSquare::class.java)
        assertThat(board.squareAt(Coordinate(0, 0)).isBlocked()).isTrue()
        assertThat(board.squareAt(Coordinate(1, 1))).isInstanceOf(EndZoneSquare::class.java)
        assertThat(board.squareAt(Coordinate(10, 12))).isInstanceOf(EndZoneSquare::class.java)
        assertThat(board.squareAt(Coordinate(0, 6))).isInstanceOf(OutOfBoundsSquare::class.java)
        assertThat(board.squareAt(Coordinate(6, 0))).isInstanceOf(OutOfBoundsSquare::class.java)
    }

    @Test
    fun `in-map neighbors resolve and off-grid coordinates throw`() {
        assertThat(board.squareAt(Coordinate(1, 1).neighbor(Direction.N)))
            .isInstanceOf(OutOfBoundsSquare::class.java)
        assertThatThrownBy { board.squareAt(Coordinate(0, -1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `end zones are one playable row per team`() {
        val homeEndZone = (1..10).map { board.squareAt(Coordinate(it, 1)) }
        val awayEndZone = (1..10).map { board.squareAt(Coordinate(it, 12)) }

        assertThat(homeEndZone).allMatch { it.isEndZone(TeamSide.HOME) }
        assertThat(awayEndZone).allMatch { it.isEndZone(TeamSide.AWAY) }
        assertThat(board.squareAt(Coordinate(4, 1)).isEndZone(TeamSide.HOME)).isTrue()
        assertThat(board.squareAt(Coordinate(4, 1)).isEndZone(TeamSide.AWAY)).isFalse()
        assertThat(board.squareAt(Coordinate(4, 12)).isEndZone(TeamSide.AWAY)).isTrue()
        assertThat(board.squareAt(Coordinate(4, 6)).isEndZone(TeamSide.HOME)).isFalse()
    }

    @Test
    fun `blocked and trapdoor squares report their traits`() {
        assertThat(board.squareAt(Coordinate(1, 6))).isInstanceOf(BlockedSquare::class.java)
        assertThat(board.squareAt(Coordinate(1, 6)).isBlocked()).isTrue()

        assertThat(board.squareAt(Coordinate(2, 6))).isInstanceOf(FieldSquare::class.java)
        assertThat(board.squareAt(Coordinate(2, 6)).isBlocked()).isFalse()

        assertThat(board.squareAt(Coordinate(6, 7))).isInstanceOf(TrapdoorSquare::class.java)
        assertThat(board.squareAt(Coordinate(6, 7)).isTrapdoor()).isTrue()
        assertThat(board.trapdoorCoordinates()).containsExactly(Coordinate(6, 7))
    }

    @Test
    fun `square may briefly hold both player and loose ball during resolution`() {
        val playerOnSquare = GameScenario.board(definition.emptyBoard()).addPlayer(2, 1).player.id
        val ball = Ball(BallId("ball-1"))

        val square = EndZoneSquare(
            coordinate = Coordinate(2, 1),
            team = TeamSide.HOME,
            playerId = playerOnSquare,
            looseBallId = ball.id,
        )

        assertThat(square.playerId).isEqualTo(playerOnSquare)
        assertThat(square.looseBallId).isEqualTo(ball.id)
    }

    @Test
    fun `player may hold a ball while standing on a square without a loose ball`() {
        val coord = Coordinate(3, 5)
        val scenario = GameScenario.board(definition.emptyBoard())
        val carrier = scenario.holdingBall(scenario.addPlayer(coord))

        assertThat(scenario.board.squareAt(coord).looseBallId).isNull()
        assertThat(carrier.player.heldBallId).isNotNull()
    }

    @Test
    fun `each team has its own reserves area for players only`() {
        val scenario = GameScenario.board(definition.emptyBoard())
        val home = scenario.addReservesPlayer()
        val away = scenario.addReservesPlayer(team = TeamSide.AWAY)

        assertThat(scenario.board.reserves.getValue(TeamSide.HOME).playerIds).containsExactly(home.player.id)
        assertThat(scenario.board.reserves.getValue(TeamSide.AWAY).playerIds).containsExactly(away.player.id)
        assertThatThrownBy { scenario.board.findSquare(home.player.id) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `playerAt returns player on that square`() {
        val coord = Coordinate(3, 5)
        val scenario = GameScenario.board(definition.emptyBoard())
        val placed = scenario.addPlayer(coord)

        assertThat(scenario.board.playerAt(coord)?.id).isEqualTo(placed.player.id)
        assertThat(scenario.board.playerAt(Coordinate(4, 5))).isNull()
    }

    @Test
    fun `rejects board shorter than two playable rows`() {
        assertThatThrownBy { BoardDefinition(playWidth = 6, playHeight = 1) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
