package com.mhaefele.gridiron.api.stream

import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.game.action.dto.sseEventName
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One open SSE connection — writes [StoredDomainEvent] frames to a single client.
 *
 * Owns Spring [SseEmitter] mechanics: frame formatting, keepalive comments, and disconnect cleanup.
 * Callers treat this as "publish event to this spectator"; do not use [SseEmitter] directly.
 */
class SseDomainEventSink private constructor(
    val emitter: SseEmitter,
    private val heartbeatTask: ScheduledFuture<*>,
) {
    private val closed = AtomicBoolean(false)
    private var onClosed: (() -> Unit)? = null

    /** Invoked once when the client disconnects, times out, or [close] runs. */
    fun onClosed(listener: () -> Unit) { onClosed = listener }

    fun publish(stored: StoredDomainEvent) {
        if (closed.get()) return
        try {
            emitter.send(
                SseEmitter.event()
                    .id(stored.id.domainId.toString())
                    .name(stored.event.sseEventName())
                    .data(stored.event, MediaType.APPLICATION_JSON),
            )
        } catch (_: IOException) {
            close()
        } catch (_: IllegalStateException) {
            close()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeatTask.cancel(false)
        try {
            emitter.complete()
        } catch (_: IllegalStateException) {
        }
        onClosed?.invoke()
    }

    companion object {
        private const val SSE_TIMEOUT_MS = 3_600_000L
        private const val HEARTBEAT_INTERVAL_SECONDS = 30L

        private val heartbeatScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "sse-domain-event-heartbeat").apply { isDaemon = true }
        }

        fun open(): SseDomainEventSink {
            val emitter = SseEmitter(SSE_TIMEOUT_MS)
            val heartbeat = heartbeatScheduler.scheduleAtFixedRate(
                { sendHeartbeat(emitter) },
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )
            val sink = SseDomainEventSink(emitter, heartbeat)
            emitter.onCompletion { sink.close() }
            emitter.onTimeout { sink.close() }
            emitter.onError { sink.close() }
            // Flush the response immediately so clients awaiting fetch()/onOpen are not blocked
            // until the first domain event (e.g. waiting for /start while /start waits for open).
            try {
                emitter.send(SseEmitter.event().comment("connected"))
            } catch (_: IOException) {
            } catch (_: IllegalStateException) {
            }
            return sink
        }

        private fun sendHeartbeat(emitter: SseEmitter) {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"))
            } catch (_: IOException) {
            } catch (_: IllegalStateException) {
            }
        }
    }
}
