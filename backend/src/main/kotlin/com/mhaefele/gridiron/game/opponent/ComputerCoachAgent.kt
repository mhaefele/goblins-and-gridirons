package com.mhaefele.gridiron.game.opponent

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.api.MatchNotFoundException
import com.mhaefele.gridiron.api.stream.MatchEventListener
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Team-scoped computer coach: wakes on in-process [DomainEvent.InputRequired.PlayerAction]
 * for [team], submits one legal action via [MatchGame], then waits for the next signal.
 *
 * Runs on its own thread — never plays synchronously inside event publish.
 */
class ComputerCoachAgent(
    private val team: TeamSide,
    private val matchId: MatchId,
    private val matchGame: MatchGame,
    private val repository: InMemoryMatchRepository,
    private val strategy: OpponentStrategy,
) : MatchEventListener, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val signals = LinkedBlockingQueue<Signal>()
    private var subscription: AutoCloseable? = null
    private var thread: Thread? = null

    fun start(hubSubscription: AutoCloseable) {
        check(thread == null) { "ComputerCoachAgent already started" }
        subscription = hubSubscription
        thread = Thread(
            {
                try {
                    runLoop()
                } catch (_: InterruptedException) {
                    // shutdown
                } catch (ex: Exception) {
                    log.error("Computer coach {} for match {} failed", team, matchId.value, ex)
                } finally {
                    closed.set(true)
                    subscription?.close()
                    subscription = null
                }
            },
            "computer-coach-${team.name.lowercase()}-${matchId.value}",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Opening [DomainEvent.InputRequired.PlayerAction] is published before agents subscribe —
     * call after [start] when this seat is already the active coach.
     */
    fun primeIfAlreadyActive() {
        if (closed.get()) return
        val state = try {
            repository.findById(matchId)
        } catch (_: MatchNotFoundException) {
            return
        }
        if (state.progress.result != null) return
        if (state.currentTurnState.phase != TurnPhase.PLAYER_ACTION) return
        if (state.actingSeat() != team) return
        signals.offer(Signal.Play)
    }

    override fun onEvents(events: List<StoredDomainEvent>) {
        if (closed.get()) return
        for (stored in events) {
            when (val event = stored.event) {
                is DomainEvent.MatchEnded                 -> {
                    signals.offer(Signal.Stop)
                    return
                }
                is DomainEvent.InputRequired.PlayerAction -> {
                    if (event.team == team) {
                        signals.offer(Signal.Play)
                    }
                }
                else                                      -> Unit
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        signals.offer(Signal.Stop)
        try {
            subscription?.close()
        } finally {
            subscription = null
        }
        val t = thread
        t?.interrupt()
    }

    private fun runLoop() {
        while (!closed.get() && !Thread.currentThread().isInterrupted) {
            when (signals.poll(POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) ?: continue) {
                Signal.Stop -> return
                Signal.Play -> playOnce()
            }
        }
    }

    private fun playOnce() {
        if (closed.get()) return
        try {
            val state = repository.findById(matchId)
            if (state.isGameEnded()) return
            if (state.actingSeat() != team) return
            val legal = matchGame.legalActions(state)
            matchGame.submitActionOutcome(matchId, strategy.chooseAction(state, legal))
        } catch (_: MatchNotFoundException) {
            // evict/delete won the race after our snapshot — stopForMatch will close us
        }
    }

    private enum class Signal { Play, Stop }

    private companion object {
        val POLL_TIMEOUT: Duration = Duration.ofMillis(250)
        private val log = LoggerFactory.getLogger(ComputerCoachAgent::class.java)
    }
}
