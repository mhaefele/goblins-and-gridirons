package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.assertPiece
import com.mhaefele.gridiron.support.PlacedPlayer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Digest § Pitch and board, Run — HTTP smoke for run and mark. */
class MovementDrillApiTest : BaseApiTest() {

    @Test
    fun `create match legal run round trips on GET`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val run = api.legalActions<LegalAction.Run>(match)
            .first()

        val response = api.play(match, run)
        val pieces = api.pieces(match)

        assertThat(response.snapshot.gameState.actionsRemaining).isEqualTo(2)
        assertThat(api.gameState(match)).isEqualTo(response.snapshot.gameState)
        assertPiece(pieces, run.playerId, run.path.squares.last())
    }

    @Test
    fun `mark updates board on GET`() {
        lateinit var marker: PlacedPlayer
        val match = match {
            marker = addPlayer(4, 10)
            addPlayer(6, 10, team = marker.team.opposite())
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.path.squares.last() == Coordinate(5, 10) }

        match.play(mark)

        assertPiece(match.pieces(), marker.player.id, x = 5, y = 10)
    }
}
