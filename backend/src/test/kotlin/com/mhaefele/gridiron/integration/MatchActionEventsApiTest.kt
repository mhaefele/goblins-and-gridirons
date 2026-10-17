package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.mapping.toPieceSnapshotDto
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.assertPiece
import com.mhaefele.gridiron.model.pending.PlayerDecisionType.REGULAR_PLAYER_ACTION
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.assertEventIds
import com.mhaefele.gridiron.support.assertEventsExactly
import com.mhaefele.gridiron.support.bareEvents
import com.mhaefele.gridiron.support.replayPieceSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MatchActionEventsApiTest : BaseApiTest() {

    @Test
    fun `action response returns game state and events without board`() {
        val match = match { addPlayer(5, 6) }

        val run = match.legalActions<LegalAction.Run>().first()
        val response = match.play(run)

        val pieces = match.pieces()
        assertThat(response.snapshot.gameState.actionsRemaining).isEqualTo(2)
        assertThat(response.events).isNotEmpty
        assertThat(response.snapshot.lastEventId).isEqualTo(response.events.last().id.domainId)
        assertThat(match.snapshot().lastEventId).isEqualTo(response.snapshot.lastEventId)
        assertPiece(pieces, run.playerId, run.path.squares.last())
    }

    @Test
    fun `replaying action events reaches pieces snapshot`() {
        lateinit var marker: PlacedPlayer
        val match = match {
            marker = addPlayer(4, 10)
            addPlayer(6, 10, team = marker.team.opposite())
            addLooseBall(5, 10)
        }
        val before = toPieceSnapshotDto(loadMatch(match).board)

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.path.squares.last() == Coordinate(5, 10) }

        dice.queueD8(1)
        val response = match.play(mark)

        assertThat(replayPieceSnapshot(before, response.events.bareEvents())).isEqualTo(match.pieces())
    }

    @Test
    fun `mark onto ball returns ordered replay events`() {
        lateinit var marker: PlacedPlayer
        val match = match {
            marker = addPlayer(4, 10)
            addPlayer(6, 10, team = marker.team.opposite())
            addLooseBall(5, 10)
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.path.squares.last() == Coordinate(5, 10) }

        dice.queueD8(1) // bounce direction
        val response = match.play(mark)

        assertEventsExactly(
            response,
            DomainEvent.ActionStarted(marker.player.id, Coordinate(4, 10), ActionType.MARK),
            DomainEvent.PlayerMoved(marker.player.id, Coordinate(4, 10), Coordinate(5, 10)),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.BOUNCE, d8 = 1),
            DomainEvent.BallBounced(Coordinate(5, 10), Coordinate(5, 9)),
            DomainEvent.ActionComplete(marker.player.id, Coordinate(5, 10), ActionType.MARK),
            DomainEvent.TeamActionConsumed(actionsRemaining = 2),
            DomainEvent.InputRequired.PlayerAction(TeamSide.HOME, REGULAR_PLAYER_ACTION, ),
        )
    }

    @Test
    fun `stand up returns status transition events`() {
        lateinit var prone: PlacedPlayer
        val match = match { prone = addPlayer(5, 6, status = PlayerStatus.PRONE) }

        val response = match.play(match.legalActions<LegalAction.StandUp>().single())

        assertEventsExactly(
            response,
            DomainEvent.ActionStarted(prone.player.id, Coordinate(5, 6), ActionType.STAND_UP),
            DomainEvent.PlayerStatusChanged(
                prone.player.id,
                Coordinate(5, 6),
                PlayerStatus.PRONE,
                PlayerStatus.STANDING,
            ),
            DomainEvent.ActionComplete(prone.player.id, Coordinate(5, 6), ActionType.STAND_UP),
            DomainEvent.TeamActionConsumed(actionsRemaining = 2),
            DomainEvent.InputRequired.PlayerAction(TeamSide.HOME, REGULAR_PLAYER_ACTION, ),
        )
        assertEventIds(response, match.matchId, 1, 2, 3, 4, 5)
    }
}
