package com.mhaefele.gridiron.game.action.dto

import com.mhaefele.gridiron.shared.DomainEventId
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Persisted domain event with a stable per-match id.")
data class StoredDomainEvent(
    @field:Schema(description = "Match-scoped event id (`domainId` increments within the match).")
    val id: DomainEventId,
    @field:Schema(description = "Discriminated event payload (kind + fields).")
    val event: DomainEvent,
)
