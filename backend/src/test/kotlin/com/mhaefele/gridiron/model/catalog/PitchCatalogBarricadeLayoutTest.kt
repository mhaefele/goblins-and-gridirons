package com.mhaefele.gridiron.model.catalog

import com.mhaefele.gridiron.model.board.BlockedSquare
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PitchCatalogBarricadeLayoutTest {

    private val definition = PitchCatalog.barricade

    @Test
    fun `layout has two center-row trapdoors and seven barricades`() {
        val board = definition.emptyBoard()

        assertThat(board.trapdoorCoordinates()).containsExactlyInAnyOrder(
            Coordinate(3, 8),
            Coordinate(9, 8),
        )
        assertThat(board.squareAt(Coordinate(6, 8)).isBlocked()).isTrue()
        assertThat(board.squareAt(Coordinate(6, 3)).isBlocked()).isTrue()
        assertThat(board.squareAt(Coordinate(4, 5)).isBlocked()).isTrue()
        assertThat(board.squareAt(Coordinate(9, 11)).isBlocked()).isTrue()
        assertThat(
            board.allSquares()
                .filter { it is BlockedSquare }
                .map { it.coordinate },
        ).containsExactlyInAnyOrderElementsOf(definition.blockedSquares)
    }

    @Test
    fun `each team can deploy six players in its end zone without overlap`() {
        val board = definition.emptyBoard()
        val deployed = GameScenario.bothTeamsDeployed(from = board)

        assertThat(deployed.players.values.filter { it.team == TeamSide.HOME }).hasSize(6)
        assertThat(deployed.players.values.filter { it.team == TeamSide.AWAY }).hasSize(6)
    }
}
