package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator
import com.mhaefele.gridiron.game.turn.clearActionsUsedForPlayer
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TurnAction
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.support.PlacedPlayer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpServerErrorException

class TurnRulesApiTest : BaseApiTest() {

    @Autowired
    private lateinit var regularPlayerActionLegalMovesGenerator: RegularPlayerActionLegalMovesGenerator

    @Test
    fun `run decrements action budget and records action type`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            runner = addPlayer(5, 6)
            addPlayer(3, 6) // teammate keeps a non-empty menu after the runner uses RUN
        }

        val run = match.singleLegalAction<LegalAction.Run> { it.playerId == runner.player.id && it.path.squares.last() == Coordinate(6, 6) }
        val response = match.play(run)

        assertThat(response.snapshot.gameState.actionsRemaining).isEqualTo(2)
        assertThat(match.legalActions<LegalAction.Run>())
            .noneMatch { it.playerId == run.playerId }
    }

    @Test
    fun `action rejected when budget exhausted`() {
        lateinit var runner: PlacedPlayer
        val match = match { runner = addPlayer(5, 6) }
        val run = match.anyLegalAction<LegalAction.PlayerLegalAction> { it.playerId == runner.player.id && it.actionType == ActionType.RUN }

        // Keep pending parked; only burn the slot count — withActionsRemaining(0) also clears pending.
        seedMatch(
            loadMatch(match).let { state ->
                state.copy(
                    currentTurnState = state.currentTurnState.copy(regularActionSlotsRemaining = 0),
                )
            },
        )

        assertThatThrownBy { match.play(run) }
            .isInstanceOf(HttpServerErrorException::class.java)
            .hasMessageContaining("No regular action slot left")
    }

    @Test
    fun `second run by same player rejected`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            runner = addPlayer(5, 6)
            addPlayer(3, 6) // teammate keeps a non-empty menu so submit resolves the forged id as 400
            actionsUsed(mapOf(runner.player.id to setOf(ActionType.RUN)))
        }
        val run = regularPlayerActionLegalMovesGenerator.geometryMoves(runner.player, loadMatch(match).board)
            .filterIsInstance<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(6, 6) }

        assertBadRequest { match.play(run) }
    }

    @Test
    fun `touchdown scorer can run again after returning via reserves`() {
        lateinit var reserve: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            reserve = addReservesPlayer()
        }
        val withPriorRun = loadMatch(match).let { state ->
            state.copy(
                currentTurnState = clearActionsUsedForPlayer(
                    state.currentTurnState.copy(
                        actionsThisTurn = listOf(TurnAction(reserve.player.id, ActionType.RUN)),
                    ),
                    reserve.player.id,
                ),
            )
        }
        seedMatch(withPriorRun)

        match.playAny<LegalAction.Reserves>()

        assertThat(
            match.legalActions<LegalAction.Run>()
                .any { it.playerId == reserve.player.id },
        ).isTrue()
    }
}
