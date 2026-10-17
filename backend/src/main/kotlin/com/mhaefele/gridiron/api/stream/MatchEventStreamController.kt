package com.mhaefele.gridiron.api.stream

import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.spring.InMemoryDomainEventRepository
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/matches")
class MatchEventStreamController(
    private val repository: InMemoryMatchRepository,
    private val eventHub: MatchEventHub,
    private val eventRepository: InMemoryDomainEventRepository,
) {
    @GetMapping("/{matchId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @PathVariable matchId: MatchId,
        @RequestHeader(value = LAST_EVENT_ID_HEADER, required = false) lastEventIdHeader: String?,
        response: HttpServletResponse,
    ): SseEmitter {
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Cache-Control", "no-cache")
        repository.findById(matchId)

        val sink = SseDomainEventSink.open()
        val afterDomainId = lastEventIdHeader?.toIntOrNull()
        if (afterDomainId != null) {
            for (stored in eventRepository.findByMatchIdAfter(matchId, afterDomainId)) {
                sink.publish(stored)
            }
        }
        eventHub.subscribe(matchId, sink)
        return sink.emitter
    }

    private companion object {
        const val LAST_EVENT_ID_HEADER = "Last-Event-ID"
    }
}
