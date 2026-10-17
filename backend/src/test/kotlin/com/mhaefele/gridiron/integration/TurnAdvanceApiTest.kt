package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.MatchResultKind
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.MatchApi
import com.mhaefele.gridiron.model.MatchResult
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.foldHud
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Turn crank (hand-off, pre-turn, sudden death, Points Victory end stream, HUD fold) over HTTP.
 * Seed mid-game with one action left, [MatchApi.play] — that is takeAction + advance.
 */
class TurnAdvanceApiTest : BaseApiTest() {

    private fun playLastBudgetAction(match: MatchApi) {
        match.playAny<LegalAction.Run>()
    }

    @Test
    fun `pre-turn spawns and bounces ball when none on pitch`() {
        val match = match {
            bothTeamsDeployed()
            removeAllBalls()
            actionsRemaining(1)
        }
        dice.queueD8(1)
        playLastBudgetAction(match)

        val next = loadMatch(match)
        assertThat(next.board.balls).isNotEmpty()
        assertThat(match.gameState().activeCoach).isEqualTo(TeamSide.AWAY)
        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
        assertThat(match.gameState().turnNumber).isEqualTo(4)
    }

    @Test
    fun `standing player on trapdoor is injured when new ball emerges`() {
        val trapdoor = Coordinate(6, 8)
        lateinit var onTrapdoor: com.mhaefele.gridiron.support.PlacedPlayer
        val match = match {
            onTrapdoor = addPlayer(trapdoor)
            // Separate player spends the last budget action so the trapdoor occupant stays put.
            addPlayer(Coordinate(3, 8))
            addPlayer(Coordinate(8, 8), team = TeamSide.AWAY)
            removeAllBalls()
            actionsRemaining(1)
        }
        dice.queueD8(1)
        val mover = match.legalActions<LegalAction.Run>()
            .first { it.playerId != onTrapdoor.player.id }
        match.play(mover)
        resolveForcedChallengeReplaceIfPresent(match.matchId)

        val next = loadMatch(match)
        val injured = next.board.players.values.single { it.id == onTrapdoor.player.id }
        assertThat(injured.status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(next.board.squareAt(trapdoor).playerId).isNull()
    }

    @Test
    fun `turn number increments once per coach activation`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            actionsRemaining(1)
            turnNumber(5)
        }
        playLastBudgetAction(match)

        assertThat(match.gameState().turnNumber).isEqualTo(6)
    }

    @Test
    fun `sudden death ends game when next active coach leads by ten`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            activeCoach(TeamSide.AWAY)
            actionsRemaining(1)
            scores(14, 3)
        }
        playLastBudgetAction(match)

        assertThat(match.gameState().phase).isEqualTo(TurnPhase.GAME_OVER)
        assertThat(loadMatch(match).progress.result).isEqualTo(MatchResult.SuddenDeath(TeamSide.HOME))
        assertThat(match.gameState().result!!.kind).isEqualTo(MatchResultKind.suddenDeath)
        assertThat(match.gameState().result!!.winner).isEqualTo(TeamSide.HOME)
    }

    @Test
    fun `nine point lead does not trigger sudden death`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            activeCoach(TeamSide.AWAY)
            actionsRemaining(1)
            scores(13, 4)
        }
        playLastBudgetAction(match)
        resolveForcedChallengeReplaceIfPresent(match.matchId)

        assertThat(match.gameState().result).isNull()
        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(match.gameState().activeCoach).isEqualTo(TeamSide.HOME)
    }

    @Test
    fun `streamed turn advance events fold to the same HUD as snapshot`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            actionsRemaining(1)
            turnNumber(5)
        }
        val before = match.snapshot().gameState
        val run = match.legalActions<LegalAction.Run>().first()

        sse.subscribe(match.matchId).use { subscription ->
            Thread.sleep(100)
            val response = match.play(run)
            val streamed = subscription.awaitEvents(response.events.size, Duration.ofSeconds(5))
            val events = streamed.map { it.event }

            assertThat(events.filterIsInstance<DomainEvent.PhaseStarted>()).isNotEmpty()
            assertThat(events.filterIsInstance<DomainEvent.TeamActionConsumed>()).isNotEmpty()
            assertThat(foldHud(before, events)).isEqualTo(match.snapshot().gameState)
            assertThat(foldHud(before, events)).isEqualTo(response.snapshot.gameState)
        }
    }

    @Test
    fun `points-victory match end stream folds HUD to GAME_OVER`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            actionsRemaining(1)
            scores(8, 4)
            pointsVictoryTurnsRemaining(1)
        }
        val before = match.snapshot().gameState
        val run = match.legalActions<LegalAction.Run>().first()

        sse.subscribe(match.matchId).use { subscription ->
            Thread.sleep(100)
            val response = match.play(run)
            val streamed = subscription.awaitEvents(response.events.size, Duration.ofSeconds(5))
            val events = streamed.map { it.event }

            assertThat(events.filterIsInstance<DomainEvent.MatchEnded>()).isNotEmpty()
            assertThat(foldHud(before, events)).isEqualTo(match.snapshot().gameState)
            assertThat(match.gameState().phase).isEqualTo(TurnPhase.GAME_OVER)
            assertThat(match.gameState().result!!.kind).isEqualTo(MatchResultKind.pointsVictory)
        }
    }
}
