package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.model.catalog.TeamCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeployTeamsForFixtureTest {
    @Test
    fun `deployTeamsForFixture uses stable player ids from createPlayers`() {
        val board = GameScenario.deployTeamsForFixture(
            GameScenario.emptyBoard(),
            TeamCatalog.dwarves(),
            TeamCatalog.orcs(),
        )

        assertThat(board.players.keys.map { it.value }).contains("h0", "a5")
    }
}
