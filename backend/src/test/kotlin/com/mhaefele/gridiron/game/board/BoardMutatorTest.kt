package com.mhaefele.gridiron.game.board

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BoardMutatorTest {
    @Test
    fun `playerMoved vacates start and occupies end`() {
        val scenario = GameScenario.board()
        val player = scenario.addPlayer(5, 6)
        val resolution = BoardMutator(scenario.board)

        resolution.playerMoved(player.player.id, Coordinate(5, 6), Coordinate(6, 6))

        assertThat(resolution.board.squareAt(Coordinate(5, 6)).playerId).isNull()
        assertThat(resolution.board.findSquare(player.player.id).coordinate).isEqualTo(Coordinate(6, 6))
        assertThat(resolution.events).containsExactly(
            DomainEvent.PlayerMoved(player.player.id, Coordinate(5, 6), Coordinate(6, 6)),
        )
    }

    @Test
    fun `playerStoodUp updates player record only`() {
        val scenario = GameScenario.board()
        val player = scenario.addPlayer(5, 6, status = PlayerStatus.PRONE)
        val resolution = BoardMutator(scenario.board)

        resolution.playerStoodUp(player.player.id)

        assertThat(resolution.board.players.getValue(player.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(resolution.board.findSquare(player.player.id).coordinate).isEqualTo(Coordinate(5, 6))
    }

    @Test
    fun `playerInjured sends on-pitch player to dugout`() {
        val scenario = GameScenario.board()
        val player = scenario.addPlayer(5, 6)
        val resolution = BoardMutator(scenario.board)

        resolution.playerInjured(player.player.id)

        assertThat(resolution.board.players.getValue(player.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(resolution.board.reserves.getValue(resolution.board.players.getValue(player.player.id).team).playerIds)
            .contains(player.player.id)
        assertThatThrownBy { resolution.board.findSquare(player.player.id) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `playerInjured rejects ball carrier`() {
        val scenario = GameScenario.board()
        val carrier = scenario.holdingBall(scenario.addPlayer(5, 6))
        val resolution = BoardMutator(scenario.board)

        assertThatThrownBy { resolution.playerInjured(carrier.player.id) }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ball carrier")
    }

    @Test
    fun `playerDeployedFromReserves places player and updates dugout`() {
        val scenario = GameScenario.board()
        val reserve = scenario.addReservesPlayer()
        val placement = scenario.board.allSquares().first { it.isEndZone(reserve.team) }.coordinate
        val resolution = BoardMutator(scenario.board)

        resolution.playerDeployedFromReserves(reserve.player.id, placement)

        assertThat(resolution.board.players.getValue(reserve.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(resolution.board.findSquare(reserve.player.id).coordinate).isEqualTo(placement)
        assertThat(resolution.board.reserves.getValue(resolution.board.players.getValue(reserve.player.id).team).playerIds)
            .doesNotContain(reserve.player.id)
    }

    @Test
    fun `ball caught after landing loose on receiver square`() {
        val scenario = GameScenario.board()
        val receiver = scenario.addPlayer(5, 6)
        val resolution = BoardMutator(scenario.board)

        val ballId = resolution.ballSpawnedLooseAt(Coordinate(5, 6))
        resolution.ballPickedUpByPlayer(receiver.player.id, Coordinate(5, 6))

        assertThat(resolution.board.players.getValue(receiver.player.id).heldBallId).isEqualTo(ballId)
        assertThat(resolution.events).containsExactly(
            DomainEvent.BallPlacedLoose(Coordinate(5, 6)),
            DomainEvent.BallPickedUp(receiver.player.id, Coordinate(5, 6)),
        )
    }

    @Test
    fun `run pickup is enter square then pick up ball`() {
        val scenario = GameScenario.board()
        val runner = scenario.addPlayer(4, 6)
        val looseBall = scenario.addLooseBall(Coordinate(5, 6))
        val resolution = BoardMutator(scenario.board)

        resolution.playerMoved(runner.player.id, Coordinate(4, 6), Coordinate(5, 6))
        assertThat(resolution.board.squareAt(Coordinate(5, 6)).looseBallId).isEqualTo(looseBall.id)

        resolution.ballPickedUpByPlayer(runner.player.id, at = Coordinate(5, 6))

        assertThat(resolution.board.players.getValue(runner.player.id).heldBallId).isEqualTo(looseBall.id)
        assertThat(resolution.board.squareAt(Coordinate(5, 6)).looseBallId).isNull()
    }

    @Test
    fun `ballBounced moves ball from start to end in one event`() {
        val scenario = GameScenario.board()
        val looseBall = scenario.addLooseBall(Coordinate(5, 6))
        val resolution = BoardMutator(scenario.board)

        resolution.ballBounced(Coordinate(5, 6), Coordinate(5, 5))

        assertThat(resolution.board.squareAt(Coordinate(5, 6)).looseBallId).isNull()
        assertThat(resolution.board.squareAt(Coordinate(5, 5)).looseBallId).isEqualTo(looseBall.id)
        assertThat(resolution.events).containsExactly(
            DomainEvent.BallBounced(Coordinate(5, 6), Coordinate(5, 5)),
        )
    }

    @Test
    fun `ballSpawnedLooseAt registers ball and places it loose`() {
        val scenario = GameScenario.board()
        val resolution = BoardMutator(scenario.board)

        val ballId = resolution.ballSpawnedLooseAt(Coordinate(5, 6))

        assertThat(resolution.board.balls).containsKey(ballId)
        assertThat(resolution.board.squareAt(Coordinate(5, 6)).looseBallId).isEqualTo(ballId)
        assertThat(resolution.events).contains(DomainEvent.BallPlacedLoose(Coordinate(5, 6)))
    }

    @Test
    fun `playerPlacedOnPitch adds standing player to empty square`() {
        val board = GameScenario.emptyBoard()
        val player = Player(PlayerId("h0"), TeamSide.HOME, TestProfiles.lineman(), PlayerStatus.STANDING, heldBallId = null)
        val resolution = BoardMutator(board)

        resolution.playerPlacedOnPitch(player, Coordinate(3, 1))

        assertThat(resolution.board.findSquare(player.id).coordinate).isEqualTo(Coordinate(3, 1))
    }
}
