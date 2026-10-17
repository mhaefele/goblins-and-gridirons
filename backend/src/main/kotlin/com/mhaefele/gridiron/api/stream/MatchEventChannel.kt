package com.mhaefele.gridiron.api.stream

import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import java.util.concurrent.CopyOnWriteArrayList

/** Live spectators for one match — fan out each event batch to SSE sinks and in-process listeners. */
class MatchEventChannel {
    private val sinks = CopyOnWriteArrayList<SseDomainEventSink>()
    private val listeners = CopyOnWriteArrayList<MatchEventListener>()

    fun add(sink: SseDomainEventSink) {
        sinks.add(sink)
    }

    fun remove(sink: SseDomainEventSink) {
        sinks.remove(sink)
    }

    fun addListener(listener: MatchEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: MatchEventListener) {
        listeners.remove(listener)
    }

    fun publishAll(events: List<StoredDomainEvent>) {
        for (stored in events) {
            for (sink in sinks) {
                sink.publish(stored)
            }
        }
        for (listener in listeners) {
            listener.onEvents(events)
        }
    }

    fun closeAll() {
        for (sink in sinks.toList()) {
            sink.close()
        }
        sinks.clear()
        listeners.clear()
    }
}
