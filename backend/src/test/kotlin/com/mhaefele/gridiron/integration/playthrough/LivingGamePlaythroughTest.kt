package com.mhaefele.gridiron.integration.playthrough

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.board.renderBoardForDisplay
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.scenario.formatDomainEvent
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.TurnPhase
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Living game script — both seats as [com.mhaefele.gridiron.shared.CoachControl.COMPUTER]; in-process agents play via the
 * create-match spawn path. Spectates over SSE for stdout.
 *
 * Excluded from default `mvn test` (slow, verbose). Run manually — `-Dtest` overrides the exclude:
 * `mvn test -Dtest=LivingGamePlaythroughTest -Dsurefire.useFile=false`
 */
class LivingGamePlaythroughTest : BaseApiTest() {

    @Test
    fun `living game playthrough`() {
        dice.usePassThrough()

        val create = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                homeControlledBy = CoachControl.COMPUTER,
                awayControlledBy = CoachControl.COMPUTER,
            ),
        )
        val matchId = create.matchId
        println()
        println("=== New match ${matchId.value} (both COMPUTER) ===")
        println(renderBoardForDisplay(loadMatch(matchId).board))

        sse.subscribe(matchId, afterDomainId = 0).use { subscription ->
            val deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos()
            while (System.nanoTime() < deadline) {
                val received = subscription.pollEvent(Duration.ofMillis(500)) ?: continue
                println(formatDomainEvent(received.event))
                if (received.event is DomainEvent.MatchEnded) break
            }
        }

        val final = loadMatch(matchId)
        println()
        println("=== Match over ===")
        println(
            "Turn ${final.progress.turnNumber} | phase=${final.currentTurnState.phase} | " + "score HOME ${final.progress.homeScore.points} — AWAY ${final.progress.awayScore.points}",)
        println(renderBoardForDisplay(final.board))

        Assertions.assertThat(api.gameState(matchId).phase).isEqualTo(TurnPhase.GAME_OVER)
        Assertions.assertThat(final.progress.result).isNotNull
    }
}