package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpStatusCodeException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI1.5 — concurrent submits against the same match must serialize under [com.mhaefele.gridiron.api.MatchGame]'s
 * per-match write lock (second writer sees post-first state, not a lost update).
 */
class MatchWriteLockApiTest : BaseApiTest() {

    @Test
    fun `concurrent submit of the same legal action applies once`() {
        dice.usePassThrough()
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)
        resolveForcedChallengeReplaceIfPresent(match.matchId)

        val action = api.legalActions(match.matchId).first { it.actionType == ActionType.RUN }
        val before = api.gameState(match)
        val beforeLastEventId = loadMatch(match).lastEventId

        val successes = AtomicInteger(0)
        val badRequests = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(
                Callable { submitOnce(match.matchId, action.id, successes, badRequests) },
                Callable { submitOnce(match.matchId, action.id, successes, badRequests) },
            )
            pool.invokeAll(tasks).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(successes.get()).isEqualTo(1)
        assertThat(badRequests.get()).isEqualTo(1)

        val after = api.gameState(match)
        assertThat(after.actionsRemaining).isLessThan(before.actionsRemaining)
        assertThat(loadMatch(match).lastEventId).isGreaterThan(beforeLastEventId)
    }

    private fun submitOnce(
        matchId: MatchId,
        legalActionId: LegalActionId,
        successes: AtomicInteger,
        badRequests: AtomicInteger,
    ) {
        try {
            api.play(matchId, legalActionId)
            successes.incrementAndGet()
        } catch (ex: HttpStatusCodeException) {
            assertThat(ex.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            badRequests.incrementAndGet()
        }
    }
}
