package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private val awayEndZone = Coordinate(5, 15)

class TouchdownDetectionTest {
    @Test
    fun `open ball carrier in opponent end zone is touchdown scorer`() {
        val board = GameScenario.board()
        val carrier = board.holdingBall(board.addPlayer(awayEndZone))

        assertThat(touchdownScorer(board.board, TeamSide.HOME)).isEqualTo(carrier.player.id)
    }

    @Test
    fun `marked ball carrier in opponent end zone does not score`() {
        val board = GameScenario.board()
        val carrier = board.holdingBall(board.addPlayer(awayEndZone))
        board.addPlayer(5, 14, team = TeamSide.AWAY)

        assertThat(touchdownScorer(board.board, TeamSide.HOME)).isNull()
    }

    @Test
    fun `carrier in own end zone does not score`() {
        val board = GameScenario.board()
        board.holdingBall(board.addPlayer(5, 1))

        assertThat(touchdownScorer(board.board, TeamSide.HOME)).isNull()
    }
}
