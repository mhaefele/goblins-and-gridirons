package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.api.mapping.toPieceSnapshotDto
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PieceSnapshotReplayTest {

    @Test
    fun `playerMoved patch matches board snapshot`() {
        val scenario = GameScenario.board()
        val player = scenario.addPlayer(5, 6)
        val before = toPieceSnapshotDto(scenario.board)
        val resolution = BoardMutator(scenario.board)
        resolution.playerMoved(player.player.id, Coordinate(5, 6), Coordinate(6, 6))

        assertThat(applyPieceSnapshotPatch(before, resolution.events.single()))
            .isEqualTo(toPieceSnapshotDto(resolution.board))
    }

    @Test
    fun `playerStoodUp patch matches board snapshot`() {
        val scenario = GameScenario.board()
        val player = scenario.addPlayer(5, 6, status = PlayerStatus.PRONE)
        val before = toPieceSnapshotDto(scenario.board)
        val resolution = BoardMutator(scenario.board)
        resolution.playerStoodUp(player.player.id)

        assertThat(applyPieceSnapshotPatch(before, resolution.events.single()))
            .isEqualTo(toPieceSnapshotDto(resolution.board))
    }

    @Test
    fun `playerDeployedFromReserves patch matches board snapshot`() {
        val scenario = GameScenario.board()
        val reserve = scenario.addReservesPlayer()
        val placement = scenario.board.allSquares().first { it.isEndZone(reserve.team) }.coordinate
        val before = toPieceSnapshotDto(scenario.board)
        val resolution = BoardMutator(scenario.board)
        resolution.playerDeployedFromReserves(reserve.player.id, placement)

        assertThat(applyPieceSnapshotPatch(before, resolution.events.single()))
            .isEqualTo(toPieceSnapshotDto(resolution.board))
    }

    @Test
    fun `ball pickup and bounce patches match board snapshot`() {
        val scenario = GameScenario.board()
        val runner = scenario.addPlayer(4, 6)
        scenario.addLooseBall(5, 6)
        val before = toPieceSnapshotDto(scenario.board)
        val resolution = BoardMutator(scenario.board)
        resolution.playerMoved(runner.player.id, Coordinate(4, 6), Coordinate(5, 6))
        resolution.ballPickedUpByPlayer(runner.player.id, Coordinate(5, 6))

        val replayed = replayPieceSnapshot(before, resolution.events)
        assertThat(replayed).isEqualTo(toPieceSnapshotDto(resolution.board))
    }

    @Test
    fun `hand off patch matches board snapshot`() {
        val scenario = GameScenario.board()
        val thrower = scenario.holdingBall(scenario.addPlayer(5, 6))
        val receiver = scenario.addPlayer(6, 6, team = thrower.team)
        val before = toPieceSnapshotDto(scenario.board)
        val resolution = BoardMutator(scenario.board)
        resolution.ballThrown(thrower.player.id, receiver.player.id)

        assertThat(applyPieceSnapshotPatch(before, resolution.events.single()))
            .isEqualTo(toPieceSnapshotDto(resolution.board))
    }

    @Test
    fun `touchdown patches match board snapshot`() {
        val scenario = GameScenario.board()
        val carrier = scenario.holdingBall(
            scenario.addPlayer(Coordinate(5, 15), profile = TestProfiles.withMove(1)),
        )
        val before = toPieceSnapshotDto(scenario.board)
        val session = MatchMutator(matchState(board = scenario.board))
        session.scoreTouchdown(carrier.player.id)

        val replayed = replayPieceSnapshot(before, session.events)
        assertThat(replayed).isEqualTo(toPieceSnapshotDto(session.toMatchState().board))
    }

    @Test
    fun `bracket and dice events are no-ops on piece snapshot`() {
        val scenario = GameScenario.board()
        val player = scenario.addPlayer(5, 6)
        val snapshot = toPieceSnapshotDto(scenario.board)

        assertThat(
            applyPieceSnapshotPatch(
                snapshot,
                DomainEvent.ActionStarted(player.player.id, Coordinate(5, 6), ActionType.RUN),
            ),
        ).isEqualTo(snapshot)
        assertThat(
            applyPieceSnapshotPatch(
                snapshot,
                DomainEvent.DiceRolled(purpose = DiceRollPurpose.BOUNCE, d8 = 3),
            ),
        ).isEqualTo(snapshot)
    }
}
