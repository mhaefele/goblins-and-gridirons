package com.mhaefele.gridiron.model.board

import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class PlayerTest {

    @ParameterizedTest
    @EnumSource(PlayerStatus::class)
    fun `player is defined by team status and optional held ball without coordinates`(status: PlayerStatus) {
        val board = GameScenario.board()
        val placed = when (status) {
            PlayerStatus.STANDING -> board.holdingBall(board.addPlayer(5, 6))
            PlayerStatus.PRONE    -> board.addPlayer(5, 6, status = PlayerStatus.PRONE)
            PlayerStatus.RESERVES -> board.addReservesPlayer()
        }
        assertThatCode { placed.player }.doesNotThrowAnyException()
    }

    @Test
    fun `unthrowable player cannot hold the ball`() {
        val unthrowable = TeamCatalog.team("graveborn").roster.first { it.name == "Wraith" }

        assertThatThrownBy { Player(id = PlayerId("a0"), team = TeamSide.AWAY, profile = unthrowable, status = PlayerStatus.STANDING, heldBallId = BallId("ball0"),) }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unthrowable")
    }
}
