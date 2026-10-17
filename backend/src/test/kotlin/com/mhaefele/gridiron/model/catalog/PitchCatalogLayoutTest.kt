package com.mhaefele.gridiron.model.catalog

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PitchCatalogLayoutTest {

    private val definition = PitchCatalog.default

    @Test
    fun `layout has center trapdoor and corner pillar blocks`() {
        val board = definition.emptyBoard()

        assertThat(board.squareAt(Coordinate(6, 8)).isTrapdoor()).isTrue()
        assertThat(board.squareAt(Coordinate(3, 4)).isBlocked()).isTrue()
        assertThat(board.squareAt(Coordinate(9, 12)).isBlocked()).isTrue()
        assertThat(board.trapdoorCoordinates()).containsExactly(Coordinate(6, 8))
    }

    @Test
    fun `each team can deploy six players in its end zone without overlap`() {
        val board = definition.emptyBoard()
        val homeEndZone = board.allSquares().filter { it.isEndZone(TeamSide.HOME) }
        val awayEndZone = board.allSquares().filter { it.isEndZone(TeamSide.AWAY) }

        assertThat(homeEndZone).hasSizeGreaterThanOrEqualTo(6)
        assertThat(awayEndZone).hasSizeGreaterThanOrEqualTo(6)
        assertThat(homeEndZone.intersect(awayEndZone)).isEmpty()

        val deployed = GameScenario.bothTeamsDeployed(from = board)

        val homePlayers = deployed.players.values.filter { it.team == TeamSide.HOME }
        val awayPlayers = deployed.players.values.filter { it.team == TeamSide.AWAY }
        assertThat(homePlayers).hasSize(6)
        assertThat(awayPlayers).hasSize(6)
        deployed.players.values.forEach { player ->
            val square = deployed.findSquare(player.id)
            assertThat(square.isEndZone(player.team)).isTrue()
        }
    }
}
