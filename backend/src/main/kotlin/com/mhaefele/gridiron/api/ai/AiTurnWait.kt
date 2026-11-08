package com.mhaefele.gridiron.api.ai

import com.mhaefele.gridiron.api.ai.training.TrainingEpisodeEnd
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Duration

/**
 * Block until [forSeat] must act, or the gym episode is over (match result or training window).
 *
 * Waits on [MatchStateWaitHub] signals from [com.mhaefele.gridiron.api.stream.MatchEventHub.publish]
 * — no poll sleep. The computer coach still commits via [com.mhaefele.gridiron.api.MatchGame].
 */
@Component
class AiTurnWait(
    private val repository: InMemoryMatchRepository,
    private val stateWaitHub: MatchStateWaitHub,
) {
    fun untilSeatOrTerminal(matchId: MatchId, forSeat: TeamSide): MatchState {
        val deadlineMs = System.currentTimeMillis() + TIMEOUT.toMillis()
        while (true) {
            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= 0L) {
                throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Timed out after ${TIMEOUT.toSeconds()}s waiting for $forSeat on match ${matchId.value}")
            }
            try {
                stateWaitHub.awaitNextChange(matchId, remainingMs) { ready(repository.findById(matchId), forSeat) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted waiting for $forSeat")
            }
            val state = repository.findById(matchId)
            if (ready(state, forSeat)) return state
        }
    }

    private fun ready(state: MatchState, forSeat: TeamSide): Boolean =
        state.isGameEnded() || TrainingEpisodeEnd.trainingEpisodeEnded(state) || state.actingSeat() == forSeat

    private companion object {
        val TIMEOUT: Duration = Duration.ofHours(1)
    }
}
