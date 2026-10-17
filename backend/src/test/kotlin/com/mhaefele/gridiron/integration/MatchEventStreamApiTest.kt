package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.pending.PlayerDecisionType.REGULAR_PLAYER_ACTION
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.bareEvents
import com.mhaefele.gridiron.support.foldHud
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class MatchEventStreamApiTest : BaseApiTest() {

    @Test
    fun `SSE stream receives ordered events after run action`() {
        lateinit var runner: PlacedPlayer
        val match = match { runner = addPlayer(4, 6, profile = TestProfiles.withMove(2)) }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(6, 6) }

        sse.subscribe(match.matchId).use { subscription ->
            Thread.sleep(100) // allow SSE handshake before POST
            val response = match.play(run)

            val streamed = subscription.awaitEvents(response.events.size, Duration.ofSeconds(5))

            assertThat(streamed.map { it.event }).containsExactlyElementsOf(response.events.bareEvents())
            assertThat(streamed.map { it.domainId }).isSortedAccordingTo(compareBy { it })
        }

        assertThat(match.pieces().players.first { it.id == runner.player.id }.at).isEqualTo(Coordinate(6, 6))
    }

    @Test
    fun `SSE reconnect replays missed events from Last-Event-ID`() {
        lateinit var firstRunner: PlacedPlayer
        lateinit var secondRunner: PlacedPlayer
        val match = match {
            firstRunner = addPlayer(4, 6, profile = TestProfiles.withMove(2))
            secondRunner = addPlayer(4, 8, profile = TestProfiles.withMove(2))
        }

        val firstRun = match.legalActions<LegalAction.Run>()
            .first { it.playerId == firstRunner.player.id && it.path.squares.last() == Coordinate(5, 6) }

        val lastDomainIdFromFirstAction: Int
        sse.subscribe(match.matchId).use { subscription ->
            Thread.sleep(100)
            val firstResponse = match.play(firstRun)
            val firstStreamed = subscription.awaitEvents(firstResponse.events.size, Duration.ofSeconds(5))
            lastDomainIdFromFirstAction = firstStreamed.last().domainId
        }

        val secondRun = match.legalActions<LegalAction.Run>()
            .first { it.playerId == secondRunner.player.id && it.path.squares.last() == Coordinate(5, 8) }
        val secondResponse = match.play(secondRun)

        sse.subscribe(match.matchId, afterDomainId = lastDomainIdFromFirstAction).use { subscription ->
            val replayed = subscription.awaitEvents(secondResponse.events.size, Duration.ofSeconds(5))
            assertThat(replayed.map { it.event }).containsExactlyElementsOf(secondResponse.events.bareEvents())
        }
    }

    @Test
    fun `two SSE subscribers receive the same events`() {
        val match = match { addPlayer(4, 6, profile = TestProfiles.withMove(2)) }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 6) }

        sse.subscribe(match.matchId).use { spectatorA ->
            sse.subscribe(match.matchId).use { spectatorB ->
                Thread.sleep(100)
                val response = match.play(run)

                val fromA = spectatorA.awaitEvents(response.events.size, Duration.ofSeconds(5))
                val fromB = spectatorB.awaitEvents(response.events.size, Duration.ofSeconds(5))

                assertThat(fromA.map { it.event }).containsExactlyElementsOf(response.events.bareEvents())
                assertThat(fromB.map { it.event }).containsExactlyElementsOf(response.events.bareEvents())
            }
        }
    }

    @Test
    fun `hydrate snapshot then SSE from lastEventId receives only later events`() {
        lateinit var firstRunner: PlacedPlayer
        lateinit var secondRunner: PlacedPlayer
        val match = match {
            firstRunner = addPlayer(4, 6, profile = TestProfiles.withMove(2))
            secondRunner = addPlayer(4, 8, profile = TestProfiles.withMove(2))
        }

        val firstRun = match.legalActions<LegalAction.Run>()
            .first { it.playerId == firstRunner.player.id && it.path.squares.last() == Coordinate(5, 6) }
        match.play(firstRun)

        val hydrate = match.snapshot()
        assertThat(hydrate.pieces.players.first { it.id == firstRunner.player.id }.at)
            .isEqualTo(Coordinate(5, 6))

        val secondRun = match.legalActions<LegalAction.Run>()
            .first { it.playerId == secondRunner.player.id && it.path.squares.last() == Coordinate(5, 8) }

        sse.subscribe(match.matchId, afterDomainId = hydrate.lastEventId).use { subscription ->
            Thread.sleep(100)
            val secondResponse = match.play(secondRun)
            val streamed = subscription.awaitEvents(secondResponse.events.size, Duration.ofSeconds(5))
            assertThat(streamed.map { it.event }).containsExactlyElementsOf(secondResponse.events.bareEvents())
            assertThat(streamed.map { it.domainId }.minOrNull()).isGreaterThan(hydrate.lastEventId)
        }
    }

    @Test
    fun `action response ends with player action required for active coach`() {
        val match = match { addPlayer(4, 6, profile = TestProfiles.withMove(2)) }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 6) }

        sse.subscribe(match.matchId).use { subscription ->
            Thread.sleep(100)
            val response = match.play(run)

            val streamed = subscription.awaitEvents(response.events.size, Duration.ofSeconds(5))
            assertThat(streamed.last().event).isEqualTo(
                DomainEvent.InputRequired.PlayerAction(TeamSide.HOME, REGULAR_PLAYER_ACTION, ),
            )
            assertThat(response.events.last().event).isEqualTo(streamed.last().event)
        }
    }

    @Test
    fun `streamed turn and budget events fold to the same HUD as snapshot`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            actionsRemaining(1)
        }
        val before = match.snapshot().gameState

        val run = match.legalActions<LegalAction.Run>().first()

        sse.subscribe(match.matchId).use { subscription ->
            Thread.sleep(100)
            val response = match.play(run)
            val streamed = subscription.awaitEvents(response.events.size, Duration.ofSeconds(5))
            val events = streamed.map { it.event }

            assertThat(events.filterIsInstance<DomainEvent.TeamActionConsumed>()).isNotEmpty()
            assertThat(events.filterIsInstance<DomainEvent.PhaseStarted>()).isNotEmpty()

            val folded = foldHud(before, events)
            assertThat(folded).isEqualTo(match.snapshot().gameState)
            assertThat(folded).isEqualTo(response.snapshot.gameState)
        }
    }

    @Test
    fun `dropped SSE subscriber leaves the match playable`() {
        val match = match { addPlayer(4, 6, profile = TestProfiles.withMove(2)) }

        sse.subscribe(match.matchId).use {
            Thread.sleep(100) // allow SSE handshake before the client drops
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 6) }

        sse.subscribe(match.matchId).use { subscription ->
            Thread.sleep(100)
            val response = match.play(run)
            val streamed = subscription.awaitEvents(response.events.size, Duration.ofSeconds(5))
            assertThat(streamed.map { it.event }).containsExactlyElementsOf(response.events.bareEvents())
        }
    }
}
