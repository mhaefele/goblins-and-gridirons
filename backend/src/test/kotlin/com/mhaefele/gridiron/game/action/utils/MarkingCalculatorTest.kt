package com.mhaefele.gridiron.game.action.utils

import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MarkingCalculatorTest {

    @Test
    fun `adjacent standing opposition players are both marked`() {
        val board = GameScenario.board()
        val home = board.addPlayer(5, 6)
        val away = board.addPlayer(6, 6, team = home.team.opposite())

        assertThat(home.standingStatus()).isEqualTo(StandingStatus.MARKED)
        assertThat(away.standingStatus()).isEqualTo(StandingStatus.MARKED)
    }

    @Test
    fun `isolated standing player is open`() {
        val board = GameScenario.board()
        val home = board.addPlayer(5, 6)

        assertThat(home.standingStatus()).isEqualTo(StandingStatus.OPEN)
    }

    @Test
    fun `standing player adjacent to prone opposition is open`() {
        val board = GameScenario.board()
        val home = board.addPlayer(5, 6)
        board.addPlayer(6, 6, status = PlayerStatus.PRONE, team = home.team.opposite())

        assertThat(home.standingStatus()).isEqualTo(StandingStatus.OPEN)
    }

    @Test
    fun `standing status rejects non-standing players`() {
        val board = GameScenario.board()
        val prone = board.addPlayer(5, 6, status = PlayerStatus.PRONE)

        assertThatThrownBy { standingStatus(prone.player, board.board) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
