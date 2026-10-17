package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent

/** Response from player-action POSTs — mutable snapshot plus ordered resolution events. */
data class MatchActionResponse(
    val snapshot: MatchSnapshotDto,
    val events: List<StoredDomainEvent> = emptyList(),
)
