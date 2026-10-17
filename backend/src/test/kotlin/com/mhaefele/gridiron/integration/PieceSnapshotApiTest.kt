package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.assertPiece
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private val awayEndZone = Coordinate(5, 15)

class PieceSnapshotApiTest : BaseApiTest() {

    @Test
    fun `hand off updates holdsBall on pieces snapshot`() {
        lateinit var thrower: PlacedPlayer
        lateinit var receiver: PlacedPlayer
        val match = match {
            thrower = holdingBall(addPlayer(5, 6))
            receiver = addPlayer(6, 6, team = thrower.team)
        }

        match.play(match.legalActions<LegalAction.Throw>().single())

        val pieces = match.pieces()
        assertThat(pieces.looseBallLocations).isEmpty()
        assertThat(player(pieces, thrower.player.id))
            .returns(Coordinate(5, 6)) { it.at }
            .returns(false) { it.holdsBall }
        assertThat(player(pieces, receiver.player.id))
            .returns(Coordinate(6, 6)) { it.at }
            .returns(true) { it.holdsBall }
    }

    @Test
    fun `failed throw places loose ball on pieces snapshot`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = holdingBall(addPlayer(5, 6))
            addPlayer(5, 4, team = thrower.team)
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .single { it.target.destination == Coordinate(5, 4) }

        dice.queueD6(2)
        dice.queueD8(1)
        match.play(throwAction)

        val pieces = match.pieces()
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(5, 3))
        assertThat(pieces.players.none { it.holdsBall }).isTrue()
    }

    @Test
    fun `stand up updates status and position on pieces snapshot`() {
        lateinit var prone: PlacedPlayer
        val match = match { prone = addPlayer(5, 6, status = PlayerStatus.PRONE) }

        match.play(match.legalActionsForPlayer(prone, ActionType.STAND_UP).first())

        assertPiece(match.pieces(), prone.player.id, PlayerStatus.STANDING, x = 5, y = 6)
    }

    @Test
    fun `dugout player has reserves status without position on pieces snapshot`() {
        lateinit var reserve: PlacedPlayer
        val match = match { reserve = addReservesPlayer() }

        assertPiece(match.pieces(), reserve.player.id, PlayerStatus.RESERVES)
    }

    @Test
    fun `touchdown removes ball from pieces snapshot`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            runner = holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == awayEndZone }
        match.play(run)

        val pieces = match.pieces()
        assertThat(pieces.looseBallLocations).isEmpty()
        assertThat(pieces.players.none { it.holdsBall }).isTrue()
        assertPiece(pieces, runner.player.id, PlayerStatus.RESERVES)
    }

    @Test
    fun `shove updates target position on pieces snapshot`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6)
            target = addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SHOVE)
        match.play(match.legalActions<LegalAction.Block>().single())

        assertPiece(match.pieces(), target.player.id, PlayerStatus.STANDING, x = 7, y = 6)
    }
}
