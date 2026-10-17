package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.game.action.utils.isAssistedBlock
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AssistedBlockTest {
    @Test
    fun `assisted when teammate also marks target`() {
        val board = GameScenario.board()
        val blocker = board.addPlayer(5, 6)
        board.addPlayer(5, 5, team = blocker.team)
        val target = board.addPlayer(6, 6, team = blocker.team.opposite())

        assertThat(isAssistedBlock(blocker.player, target.player, board.board)).isTrue()
    }

    @Test
    fun `juggernaut marked block is never assisted`() {
        val board = GameScenario.board()
        val pump = TeamCatalog.team("gremlin").roster.first { it.name == "Rattle Cart" }
        val blocker = board.addPlayer(5, 6, profile = pump)
        board.addPlayer(5, 5, team = blocker.team)
        val target = board.addPlayer(6, 6, team = blocker.team.opposite())

        assertThat(isAssistedBlock(blocker.player, target.player, board.board)).isFalse()
    }
}
