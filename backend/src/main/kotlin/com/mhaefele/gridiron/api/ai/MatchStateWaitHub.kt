package com.mhaefele.gridiron.api.ai

import com.mhaefele.gridiron.shared.MatchId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-match condition gates for harness threads blocked on opponent / engine progress.
 *
 * [signal] runs after each live [com.mhaefele.gridiron.api.stream.MatchEventHub.publish]
 * so [AiTurnWait] can park until repo state may have changed — no poll sleep.
 */
@Component
class MatchStateWaitHub {
    private data class Gate(val lock: ReentrantLock = ReentrantLock(), val changed: Condition = lock.newCondition())

    private val gates = ConcurrentHashMap<MatchId, Gate>()

    fun signal(matchId: MatchId) {
        val gate = gates[matchId] ?: return
        gate.lock.withLock { gate.changed.signalAll() }
    }

    /**
     * Park up to [timeoutMs] until [com.mhaefele.gridiron.api.stream.MatchEventHub] signals [matchId].
     * Re-checks [readyNow] under the gate lock before sleeping so a signal is not missed.
     */
    fun awaitNextChange(matchId: MatchId, timeoutMs: Long, readyNow: () -> Boolean) {
        if (timeoutMs <= 0L) return
        val gate = gates.computeIfAbsent(matchId) { Gate() }
        gate.lock.withLock {
            if (readyNow()) return
            gate.changed.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Wake any blocked harness waiters and drop the gate (match evicted). */
    fun drop(matchId: MatchId) {
        val gate = gates.remove(matchId) ?: return
        gate.lock.withLock { gate.changed.signalAll() }
    }
}
