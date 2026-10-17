package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class BlockValidationTest : BaseApiTest() {

    @Autowired
    private lateinit var regularPlayerActionLegalMovesGenerator: RegularPlayerActionLegalMovesGenerator

    @Test
    fun `open player cannot block`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)

        assertThat(regularPlayerActionLegalMovesGenerator.geometryMoves(runner.player, board.board))
            .noneMatch { it.actionType == ActionType.BLOCK }
    }

    @Test
    fun `marked player cannot block non adjacent opposition`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(5, 6)
        board.addPlayer(6, 6, team = marker.team.opposite())
        board.addPlayer(8, 6, team = marker.team.opposite())

        val destinations = regularPlayerActionLegalMovesGenerator.geometryMoves(marker.player, board.board)
            .filterIsInstance<LegalAction.Block>()
            .map { it.destination }

        assertThat(destinations).containsExactly(Coordinate(6, 6))
    }
}
