package com.mhaefele.gridiron.api.stream

import com.mhaefele.gridiron.api.ai.MatchStateWaitHub
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.shared.MatchId
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes live events to the [MatchEventChannel] for each match.
 *
 * Does not own SSE wire details or reconnect replay — those live on [SseDomainEventSink]
 * and [MatchEventStreamController] respectively. In-process [MatchEventListener]s share the same
 * fan-out (computer coaches).
 */
@Component
class MatchEventHub(
    private val stateWaitHub: MatchStateWaitHub,
) {
    private val channels = ConcurrentHashMap<MatchId, MatchEventChannel>()

    fun subscribe(matchId: MatchId, sink: SseDomainEventSink) {
        val channel = channels.computeIfAbsent(matchId) { MatchEventChannel() }
        sink.onClosed { channel.remove(sink) }
        channel.add(sink)
    }

    /** Subscribe an in-process listener; close the handle to remove it. */
    fun subscribe(matchId: MatchId, listener: MatchEventListener): AutoCloseable {
        val channel = channels.computeIfAbsent(matchId) { MatchEventChannel() }
        channel.addListener(listener)
        return AutoCloseable { channel.removeListener(listener) }
    }

    fun publish(matchId: MatchId, events: List<StoredDomainEvent>) {
        channels[matchId]?.publishAll(events)
        stateWaitHub.signal(matchId)
    }

    /** Drop the live channel for a finished match (closes any remaining SSE sinks). */
    fun drop(matchId: MatchId) {
        channels.remove(matchId)?.closeAll()
        stateWaitHub.drop(matchId)
    }

    @PreDestroy
    fun shutdown() {
        channels.values.forEach { it.closeAll() }
        channels.clear()
    }
}
