package com.mhaefele.gridiron.api.stream

import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent

/** In-process spectator for one match's event batches — used by computer coaches (not SSE wire). */
fun interface MatchEventListener {
    fun onEvents(events: List<StoredDomainEvent>)
}
