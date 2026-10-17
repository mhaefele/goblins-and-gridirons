package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.board.BoardDefinition
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Bounce settle / obstruction — GameScenario setup, HTTP play. Digest § Bounce. */
class BounceApiTest : BaseApiTest() {

    private val origin = Coordinate(6, 8)

    @Test
    fun `roll 7 skips blocked west and northwest to land north`() {
        val match = match {
            emptyPitch(bounceObstructionPitch())
            addPlayer(6, 9, profile = TestProfiles.wraith())
            addLooseBall(origin)
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.targetCoordinate == origin }

        dice.queueD8(7) // obstruction skip — W and NW blocked, lands N at (6, 7)
        match.play(run)

        assertThat(match.pieces().looseBallLocations).containsExactly(Coordinate(6, 7))
    }

    @Test
    fun `bounce onto open standing player is picked up`() {
        lateinit var openTeammate: PlacedPlayer
        val match = match {
            val marker = addPlayer(4, 10)
            // Opponent adjacent to mark destination (5,10) but not to bounce landing (5,9).
            addPlayer(6, 11, team = marker.team.opposite())
            openTeammate = addPlayer(5, 9, team = marker.team)
            addLooseBall(5, 10)
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.targetCoordinate == Coordinate(5, 10) }

        dice.queueD8(1) // N → (5, 9)
        match.play(mark)

        val pieces = match.pieces()
        assertThat(pieces.looseBallLocations).isEmpty()
        assertThat(player(pieces, openTeammate.player.id).holdsBall).isTrue()
    }

    @Test
    fun `bounce onto marked player stays loose then continues bounce`() {
        lateinit var markedTeammate: PlacedPlayer
        val match = match {
            val marker = addPlayer(4, 10)
            addPlayer(6, 10, team = marker.team.opposite())
            markedTeammate = addPlayer(5, 9, team = marker.team)
            addPlayer(5, 8, team = marker.team.opposite()) // marks the landing teammate
            addLooseBall(5, 10)
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.targetCoordinate == Coordinate(5, 10) }

        dice.queueD8(1) // N → (5, 9) occupied/marked → settle then re-bounce
        dice.queueD8(3) // E → (6, 9)
        match.play(mark)

        val pieces = match.pieces()
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(6, 9))
        assertThat(player(pieces, markedTeammate.player.id).holdsBall).isFalse()
    }

    private fun bounceObstructionPitch(): BoardDefinition =
        BoardDefinition(
            playWidth = 11,
            playHeight = 15,
            trapdoors = setOf(origin),
            blockedSquares = setOf(
                Coordinate(5, 8),
                Coordinate(5, 7),
            ),
        )
}
