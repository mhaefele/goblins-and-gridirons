package com.mhaefele.gridiron.spring

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.shared.DomainEventId
import com.mhaefele.gridiron.shared.MatchId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
class InMemoryDomainEventRepository {
    private val logs = ConcurrentHashMap<MatchId, MatchEventLog>()

    fun append(matchId: MatchId, events: List<DomainEvent>): List<StoredDomainEvent> =
        logs.computeIfAbsent(matchId) { MatchEventLog() }.append(matchId, events)

    fun findByMatchId(matchId: MatchId): List<StoredDomainEvent> {
        val log = logs[matchId] ?: return emptyList()
        return log.allEvents()
    }

    /** Events with `domainId` strictly greater than [afterDomainId] — SSE reconnect catch-up. */
    fun findByMatchIdAfter(matchId: MatchId, afterDomainId: Int): List<StoredDomainEvent> {
        val log = logs[matchId] ?: return emptyList()
        return log.eventsAfter(afterDomainId)
    }

    fun delete(matchId: MatchId) {
        logs.remove(matchId)
    }

    private class MatchEventLog {
        private val nextDomainId = AtomicInteger(0)
        private val events = mutableListOf<StoredDomainEvent>()

        @Synchronized
        fun append(matchId: MatchId, batch: List<DomainEvent>): List<StoredDomainEvent> =
            batch.map { event ->
                val domainId = nextDomainId.incrementAndGet()
                StoredDomainEvent(DomainEventId(matchId, domainId), event).also { events.add(it) }
            }

        @Synchronized
        fun allEvents(): List<StoredDomainEvent> = events.toList()

        @Synchronized
        fun eventsAfter(afterDomainId: Int): List<StoredDomainEvent> =
            events.filter { it.id.domainId > afterDomainId }
    }
}
