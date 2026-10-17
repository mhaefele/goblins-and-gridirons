package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ActionAvailabilityTest {

    @ParameterizedTest
    @EnumSource(StandingStatus::class)
    fun `open and marked standing players get rulebook base actions`(standing: StandingStatus) {
        val expected = when (standing) {
            StandingStatus.OPEN   -> setOf(ActionType.RUN, ActionType.MARK)
            StandingStatus.MARKED -> setOf(ActionType.BLOCK, ActionType.SIDESTEP)
        }
        assertThat(allowedActionTypes(standing)).isEqualTo(expected)
    }

    @Test
    fun `open carrier may throw marked carrier may not`() {
        val board = GameScenario.board()
        val openCarrier = board.holdingBall(board.addPlayer(3, 6))
        val markedCarrier = board.holdingBall(board.addPlayer(7, 6))
        board.addPlayer(8, 6, team = markedCarrier.team.opposite())

        assertThat(allowedActionTypes(openCarrier.player, board.board))
            .isEqualTo(setOf(ActionType.RUN, ActionType.MARK, ActionType.THROW))
        assertThat(allowedActionTypes(markedCarrier.player, board.board))
            .isEqualTo(setOf(ActionType.BLOCK, ActionType.SIDESTEP))
    }

    @Test
    fun `marked juggernaut may run in addition to block and sidestep`() {
        val pump = TeamCatalog.team("gremlin").roster.first { it.name == "Rattle Cart" }
        val board = GameScenario.board()
        val wagon = board.addPlayer(5, 6, profile = pump)
        board.addPlayer(6, 6, team = TeamSide.AWAY)

        assertThat(allowedActionTypes(wagon.player, board.board))
            .isEqualTo(setOf(ActionType.BLOCK, ActionType.SIDESTEP, ActionType.RUN))
    }

    @ParameterizedTest
    @EnumSource(
        value = PlayerStatus::class,
        names = ["PRONE", "RESERVES"],
    )
    fun `prone and reserves players get single action type`(status: PlayerStatus) {
        val expected = when (status) {
            PlayerStatus.PRONE    -> setOf(ActionType.STAND_UP)
            PlayerStatus.RESERVES -> setOf(ActionType.RESERVES)
            else                  -> error("unexpected status $status")
        }
        val board = GameScenario.board()
        val placed = when (status) {
            PlayerStatus.PRONE    -> board.addPlayer(5, 6, status = PlayerStatus.PRONE)
            PlayerStatus.RESERVES -> board.addReservesPlayer()
            else                  -> error("unexpected status $status")
        }

        assertThat(allowedActionTypes(placed.player, board.board)).isEqualTo(expected)
    }

    @Test
    fun `open player adjacent to prone opposition may foul`() {
        val board = GameScenario.board()
        val open = board.addPlayer(5, 6)
        board.addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)

        assertThat(allowedActionTypes(open.player, board.board))
            .isEqualTo(setOf(ActionType.RUN, ActionType.MARK, ActionType.FOUL))
    }
}
