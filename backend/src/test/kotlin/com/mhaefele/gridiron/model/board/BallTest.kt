package com.mhaefele.gridiron.model.board

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BallTest {

    @Test
    fun `loose ball is on the square not in player heldBallId`() {
        val coord = Coordinate(3, 5)
        val scenario = GameScenario.board()
        scenario.addLooseBall(coord)
        val board = scenario.board

        assertThat(board.squareAt(coord).looseBallId).isNotNull()
        assertThat(board.playerAt(coord)).isNull()
    }

    @Test
    fun `held ball is on the player not as a loose square occupant`() {
        val coord = Coordinate(3, 5)
        val scenario = GameScenario.board()
        val carrier = scenario.holdingBall(scenario.addPlayer(coord))

        assertThat(carrier.player.heldBallId).isNotNull()
        assertThat(scenario.board.squareAt(coord).looseBallId).isNull()
        assertThat(scenario.board.playerAt(coord)?.heldBallId).isEqualTo(carrier.player.heldBallId)
    }

    @Test
    fun `ball cannot be both loose and held`() {
        val coord = Coordinate(3, 5)
        val otherCoord = Coordinate(4, 5)
        val ball = Ball(BallId("ball-1"))
        val scenario = GameScenario.board()
        scenario.holdingBall(scenario.addPlayer(coord), ball)

        assertThatThrownBy { scenario.addLooseBall(otherCoord, ball) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
