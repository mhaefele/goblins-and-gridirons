package com.mhaefele.gridiron.integration.support

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.shared.MatchId
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads `text/event-stream` frames for API tests — no browser, no WebFlux.
 */
class SseTestClient(
    private val baseUri: String,
    private val jsonMapper: JsonMapper,
) {
    data class ReceivedEvent(
        val domainId: Int,
        val kind: String,
        val event: DomainEvent,
    )

    class Subscription internal constructor(
        private val queue: BlockingQueue<ReceivedEvent>,
        private val thread: Thread,
        private val closed: AtomicBoolean,
        private val body: AtomicReference<InputStream?>,
        private val httpClient: AtomicReference<HttpClient?>,
    ) : AutoCloseable {
        fun pollEvent(timeout: Duration): ReceivedEvent? =
            queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)

        fun awaitEvents(count: Int, timeout: Duration): List<ReceivedEvent> {
            val result = mutableListOf<ReceivedEvent>()
            val deadlineNanos = System.nanoTime() + timeout.toNanos()
            repeat(count) { index ->
                val remainingNanos = deadlineNanos - System.nanoTime()
                require(remainingNanos > 0) { "Timed out waiting for SSE event ${index + 1}/$count" }
                val event = queue.poll(remainingNanos, TimeUnit.NANOSECONDS) ?: error("Timed out waiting for SSE event ${index + 1}/$count")
                result.add(event)
            }
            return result
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            body.getAndSet(null)?.closeQuietly()
            httpClient.getAndSet(null)?.close()
            thread.interrupt()
        }
    }

    fun subscribe(matchId: MatchId, afterDomainId: Int? = null): Subscription {
        val queue = LinkedBlockingQueue<ReceivedEvent>()
        val closed = AtomicBoolean(false)
        val body = AtomicReference<InputStream?>(null)
        val httpClient = AtomicReference<HttpClient?>(null)
        val thread = Thread { readStream(matchId, afterDomainId, queue, closed, body, httpClient) }.apply {
            name = "sse-test-${matchId.value}"
            isDaemon = true
            start()
        }
        return Subscription(queue, thread, closed, body, httpClient)
    }

    private fun readStream(
        matchId: MatchId,
        afterDomainId: Int?,
        queue: BlockingQueue<ReceivedEvent>,
        closed: AtomicBoolean,
        body: AtomicReference<InputStream?>,
        httpClientRef: AtomicReference<HttpClient?>,
    ) {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        httpClientRef.set(client)
        if (closed.get()) {
            client.close()
            return
        }
        // No request timeout — stream stays open across long human think times; connectTimeout
        // above still bounds the handshake. Server emitter timeout / heartbeats own the idle budget.
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUri/api/matches/${matchId.value}/events"))
            .header("Accept", "text/event-stream")
            .GET()
        if (afterDomainId != null) {
            requestBuilder.header("Last-Event-ID", afterDomainId.toString())
        }
        try {
            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
            require(response.statusCode() == 200) { "Expected 200 for SSE subscribe, got ${response.statusCode()}" }
            val stream = response.body()
            body.set(stream)
            if (closed.get()) return
            stream.bufferedReader().use { reader ->
                var currentId: String? = null
                var currentEvent: String? = null
                val dataLines = mutableListOf<String>()
                while (!closed.get() && !Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        dispatchFrame(currentId, currentEvent, dataLines, queue)
                        currentId = null
                        currentEvent = null
                        dataLines.clear()
                        continue
                    }
                    if (line.startsWith(":")) continue
                    when {
                        line.startsWith("id:")    -> currentId = line.removePrefix("id:").trim()
                        line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                        line.startsWith("data:")  -> dataLines.add(line.removePrefix("data:").trim())
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (ex: IOException) {
            if (!closed.get()) throw ex
        } finally {
            body.getAndSet(null)?.closeQuietly()
            httpClientRef.getAndSet(null)?.close()
        }
    }

    private fun dispatchFrame(
        id: String?,
        kind: String?,
        dataLines: List<String>,
        queue: BlockingQueue<ReceivedEvent>,
    ) {
        if (id == null || kind == null || dataLines.isEmpty()) return
        val json = dataLines.joinToString("\n")
        val event = jsonMapper.readValue(json, domainEventTypeRef())
        queue.offer(ReceivedEvent(id.toInt(), kind, event))
    }

    private fun domainEventTypeRef(): TypeReference<DomainEvent> = object : TypeReference<DomainEvent>() {}
}

private fun InputStream.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
    }
}
