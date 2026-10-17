package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.assertPiece
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.pending.PlayerDecisionType
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Direction
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.bareEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MovementApiTest : BaseApiTest() {

    @Test
    fun `run moves player to path end`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            tinyBoard()
            runner = addPlayer(2, 2, profile = TestProfiles.withMove(2))
        }

        match.play(runner, ActionType.RUN, 4, 2)

        assertPiece(match.pieces(), runner.player.id, x = 4, y = 2)
    }

    @Test
    fun `run picks up loose ball on end square`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            tinyBoard()
            runner = addPlayer(2, 2, profile = TestProfiles.withMove(2))
            addLooseBall(3, 2)
        }

        match.play(runner, ActionType.RUN, 3, 2)

        val pieces = match.pieces()
        assertThat(player(pieces, runner.player.id).holdsBall).isTrue()
        assertThat(pieces.looseBallLocations).isEmpty()
    }

    @Test
    fun `mark onto ball square bounces the ball`() {
        lateinit var marker: PlacedPlayer
        val match = match {
            marker = addPlayer(4, 10)
            addPlayer(6, 10, team = marker.team.opposite())
            addLooseBall(5, 10)
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.path.squares.last() == Coordinate(5, 10) }

        dice.queueD8(1) // bounce direction
        match.play(mark)

        val pieces = match.pieces()
        assertPiece(pieces, marker.player.id, x = 5, y = 10)
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(5, 9))
        val persistedBoard = loadMatch(match).board
        assertThat(standingStatus(persistedBoard.players.getValue(marker.player.id), persistedBoard))
            .isEqualTo(StandingStatus.MARKED)
    }

    @Test
    fun `sidestep onto ball square bounces the ball`() {
        lateinit var marker: PlacedPlayer
        val match = match {
            marker = addPlayer(5, 10)
            addPlayer(6, 10, team = marker.team.opposite())
            addLooseBall(4, 10)
        }

        val sidestep = match.legalActions<LegalAction.Sidestep>()
            .first { it.path.squares.last() == Coordinate(4, 10) }

        dice.queueD8(1) // bounce direction
        match.play(sidestep)

        val pieces = match.pieces()
        assertPiece(pieces, marker.player.id, x = 4, y = 10)
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(4, 9))
    }

    @Test
    fun `stand up leaves player on same square`() {
        lateinit var prone: PlacedPlayer
        val match = match { prone = addPlayer(5, 6, status = PlayerStatus.PRONE) }

        match.play(match.legalActions<LegalAction.StandUp>().single())

        assertPiece(match.pieces(), prone.player.id, PlayerStatus.STANDING, x = 5, y = 6)
    }

    @Test
    fun `reserves deploys player to end zone`() {
        lateinit var reserve: PlacedPlayer
        val match = match { reserve = addReservesPlayer() }

        val deploy = match.legalActions<LegalAction.Reserves>().first()
        match.play(deploy)

        assertPiece(match.pieces(), reserve.player.id, PlayerStatus.STANDING, deploy.placement)
    }

//    @Test
    // TODO:  This is a hard test to configure since by default you can't place a reserves player on the ball unless there are no other optinos.
    // Disabling for now.
    fun `reserves onto ball square bounces the ball`() {
        lateinit var reserve: PlacedPlayer

        lateinit var placement: Coordinate

        val match = match {
            placement = board.board.allSquares().filter { it.isEndZone(TeamSide.HOME) }
                .filter { !board.board.squareAt(it.coordinate.neighbor(Direction.E)).isBlocked() }
                .random().coordinate
            reserve = addReservesPlayer()
            addLooseBall(placement)
        }

        val deploy = match.singleLegalAction<LegalAction.Reserves>()
            { it.placement == placement }

        dice.queueBounce(Direction.E) // bounce direction
        match.play(deploy)

        val pieces = match.pieces()
        assertPiece(pieces, reserve.player.id, placement)
        assertThat(pieces.looseBallLocations).containsExactly(placement.neighbor(Direction.E))
    }

    @Test
    fun `unthrowable player run onto ball bounces instead of picking up`() {
        lateinit var lineman: PlacedPlayer
        val match = match {
            lineman = addPlayer(4, 6, profile = TestProfiles.wraith())
            addLooseBall(5, 6)
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 6) }

        dice.queueD8(1) // bounce direction
        match.play(run)

        val pieces = match.pieces()
        assertThat(player(pieces, lineman.player.id).holdsBall).isFalse()
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(5, 5))
    }

    @Test
    fun `run mid-path pickup offers continue with remaining move budget`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            runner = addPlayer(4, 6, profile = TestProfiles.withMove(3))
            addPlayer(3, 8) // teammate keeps a non-empty menu after the Run is spent
            addLooseBall(5, 6)
        }

        val runToBall = match.legalActions<LegalAction.Run>()
            .first { it.path.squares == listOf(Coordinate(4, 6), Coordinate(5, 6)) }
        val pickupResponse = match.play(runToBall)

        assertThat(pickupResponse.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(TeamSide.HOME, PlayerDecisionType.RUN_CONTINUE, playerId = runner.player.id, ),
        )
        assertThat(pickupResponse.events.bareEvents().filterIsInstance<DomainEvent.ActionComplete>()).isEmpty()
        assertThat(pickupResponse.events.bareEvents().filterIsInstance<DomainEvent.TeamActionConsumed>()).isEmpty()
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertThat(player(match.pieces(), runner.player.id).holdsBall).isTrue()

        assertThat(match.legalActions<LegalAction.Skip>()).hasSize(1)
        val continueRun = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(7, 6) }
        assertThat(continueRun.path.steps).isLessThanOrEqualTo(2)

        match.play(continueRun)

        assertPiece(match.pieces(), runner.player.id, x = 7, y = 6)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertThat(match.legalActions<LegalAction.Skip>()).isEmpty()
    }

    @Test
    fun `run continue skip ends action without second budget spend`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            runner = addPlayer(4, 6, profile = TestProfiles.withMove(3))
            addLooseBall(5, 6)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.path.squares.last() == Coordinate(5, 6) },
        )
        val skipResponse = match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(skipResponse.events.bareEvents()).contains(
            DomainEvent.ActionComplete(runner.player.id, Coordinate(5, 6), ActionType.RUN),
            DomainEvent.TeamActionConsumed(actionsRemaining = 2),
        )
        assertPiece(match.pieces(), runner.player.id, x = 5, y = 6)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
    }

    @Test
    fun `carrier run onto second ball bounces then may continue`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            runner = addPlayer(4, 6, profile = TestProfiles.withMove(3))
            holdingBall(runner)
            addLooseBall(5, 6)
        }

        dice.queueD8(1)
        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.path.squares.last() == Coordinate(5, 6) },
        )

        assertThat(match.legalActions()).anyMatch { it is LegalAction.Run || it is LegalAction.Skip }
        assertThat(player(match.pieces(), runner.player.id).holdsBall).isTrue()
        assertThat(match.pieces().looseBallLocations).containsExactly(Coordinate(5, 5))
    }

    @Test
    fun `mark onto marked ball square bounces and does not continue`() {
        lateinit var marker: PlacedPlayer
        val match = match {
            marker = addPlayer(4, 10)
            addPlayer(6, 10, team = marker.team.opposite())
            addLooseBall(5, 10)
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.path.squares.last() == Coordinate(5, 10) }
        dice.queueD8(1)
        match.play(mark)

        assertThat(match.legalActions<LegalAction.Mark>()).isEmpty()
        assertThat(match.legalActions<LegalAction.Skip>()).isEmpty()
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertPiece(match.pieces(), marker.player.id, x = 5, y = 10)
    }

    @Test
    fun `random d6 run rolls move then offers paths up to that many squares`() {
        lateinit var runner: PlacedPlayer
        val profile = TestProfiles.lineman().copy(
            stats = TestProfiles.lineman().stats.copy(move = MoveValue.RandomD6),
        )
        val match = match { runner = addPlayer(4, 6, profile = profile) }

        val declare = match.legalActions<LegalAction.DeclareRun>()
            .first { it.playerId == runner.player.id }
        dice.queueD6(3)
        val rollResponse = match.play(declare)

        assertThat(rollResponse.events.bareEvents()).contains(
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.MOVE, d6 = 3),
            DomainEvent.InputRequired.PlayerAction(TeamSide.HOME, PlayerDecisionType.RUN_CONTINUE, playerId = runner.player.id, ),
        )
        assertThat(rollResponse.events.bareEvents().filterIsInstance<DomainEvent.ActionComplete>()).isEmpty()
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        val runs = match.legalActions<LegalAction.Run>()
        assertThat(runs).isNotEmpty
        assertThat(runs).allMatch { it.path.steps <= 3 }
        assertThat(runs.map { it.path.steps }).contains(3)
        assertThat(match.legalActions<LegalAction.Skip>()).hasSize(1)

        val run = runs
            .first { it.path.squares.last() == Coordinate(6, 6) && it.path.steps == 2 }
        match.play(run)

        assertPiece(match.pieces(), runner.player.id, x = 6, y = 6)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
    }

    @Test
    fun `random d6 run rejects path longer than rolled move`() {
        lateinit var runner: PlacedPlayer
        val profile = TestProfiles.lineman().copy(
            stats = TestProfiles.lineman().stats.copy(move = MoveValue.RandomD6),
        )
        val match = match { runner = addPlayer(4, 6, profile = profile) }

        dice.queueD6(1)
        match.play(
            match.legalActions<LegalAction.DeclareRun>().first { it.playerId == runner.player.id },
        )

        val runs = match.legalActions<LegalAction.Run>()
        assertThat(runs).isNotEmpty
        assertThat(runs).allMatch { it.path.steps <= 1 }
        assertThat(runs.map { it.path.steps }).doesNotContain(2)
    }
}
