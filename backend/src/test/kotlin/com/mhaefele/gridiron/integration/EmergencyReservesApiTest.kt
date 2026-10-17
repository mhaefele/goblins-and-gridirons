package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.pending.OfferUndying
import com.mhaefele.gridiron.model.pending.EmergencyReserves
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.PlacedPlayer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmergencyReservesApiTest : BaseApiTest() {

    private fun awayInjuredBoard(onPitchInjured: Int) =
        BoardMutator(GameScenario.bothTeamsDeployed()).also { mutator ->
            mutator.board.players.values
                .filter { it.team == TeamSide.AWAY && it.status == PlayerStatus.STANDING }
                .take(onPitchInjured)
                .forEach { mutator.playerInjured(it.id) }
        }.board.let { board ->
            GameScenario.board(board).also { it.addLooseBall(Coordinate(6, 8)) }.board
        }

    @Test
    fun `emergency reserves legal moves include every dugout player and do not spend actions`() {
        val board = awayInjuredBoard(4)
        val dugoutIds = board.reserves.getValue(TeamSide.AWAY).playerIds.toSet()
        assertThat(dugoutIds).hasSize(4)

        val match = match {
            fromBoard(board)
            activeCoach(TeamSide.AWAY)
            phase(TurnPhase.PRE_TURN)
            pendingDecision(EmergencyReserves())
        }

        val legal = match.legalActions()
        assertThat(legal).isNotEmpty
        assertThat(legal).allMatch { it is LegalAction.Reserves }
        assertThat(legal.filterIsInstance<LegalAction.Reserves>().map { it.playerId }.toSet()).isEqualTo(dugoutIds)

        val standingBefore = match.pieces().players.count { it.team == TeamSide.AWAY && it.status == PlayerStatus.STANDING }
        match.playAny<LegalAction.Reserves>()

        // dugout 4 → 3, under ER=4, so no second free placement — open the action window.
        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
        val standingAfter = match.pieces().players.count { it.team == TeamSide.AWAY && it.status == PlayerStatus.STANDING }
        assertThat(standingAfter).isEqualTo(standingBefore + 1)
    }

    @Test
    fun `emergency reserves offers only one free placement even when dugout stays at or above ER`() {
        val board = awayInjuredBoard(5)
        assertThat(board.reserves.getValue(TeamSide.AWAY).playerIds).hasSize(5)

        val match = match {
            fromBoard(board)
            activeCoach(TeamSide.AWAY)
            phase(TurnPhase.PRE_TURN)
            pendingDecision(EmergencyReserves())
        }

        match.playAny<LegalAction.Reserves>()

        // dugout 5 → 4, still ≥ ER=4, but only one free ER per pre-turn.
        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
        assertThat(
            match.pieces().players.count { it.team == TeamSide.AWAY && it.status == PlayerStatus.STANDING },
        ).isEqualTo(2)
        assertThat(loadMatch(match).board.reserves.getValue(TeamSide.AWAY).playerIds).hasSize(4)
    }

    @Test
    fun `exhausting a coach turn queues emergency reserves for the next coach`() {
        val board = awayInjuredBoard(4)
        val match = match {
            fromBoard(board)
            actionsRemaining(1)
        }
        match.playAny<LegalAction>()

        assertThat(match.gameState().activeCoach).isEqualTo(TeamSide.AWAY)
        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PRE_TURN)
        assertThat(match.legalActions()).isNotEmpty
        assertThat(match.legalActions()).allMatch { it is LegalAction.Reserves }
        assertThat(match.gameState().actionsRemaining).isEqualTo(0)
        assertThat(
            match.pieces().players.count { it.team == TeamSide.AWAY && it.status == PlayerStatus.STANDING },
        ).isEqualTo(2)

        match.playAny<LegalAction.Reserves>()

        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
        assertThat(
            match.pieces().players.count { it.team == TeamSide.AWAY && it.status == PlayerStatus.STANDING },
        ).isEqualTo(3)
    }

    @Test
    fun `undying stand up then emergency reserves does not leave a finished open-action continuation`() {
        val board = awayInjuredBoard(4)
        lateinit var skeleton: PlacedPlayer
        val match = match {
            fromBoard(board)
            activeCoach(TeamSide.AWAY)
            phase(TurnPhase.PRE_TURN)
            skeleton = addPlayer(
                3,
                3,
                team = TeamSide.AWAY,
                status = PlayerStatus.PRONE,
                profile = TeamCatalog.team("restless-dead").roster.first { it.name == "Skeleton" },
            )
            pendingDecision(
                OfferUndying(skeleton.player.id),
            )
        }

        match.play(match.legalActions<LegalAction.StandUp>().single { it.playerId == skeleton.player.id })

        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PRE_TURN)
        assertThat(match.legalActions()).isNotEmpty
        assertThat(match.legalActions()).allMatch { it is LegalAction.Reserves }
        match.playAny<LegalAction.Reserves>()
        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PLAYER_ACTION)
    }
}
