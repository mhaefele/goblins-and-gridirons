package com.mhaefele.gridiron.api.dto.request

import com.mhaefele.gridiron.shared.Coordinate

/**
 * Body for destination-based player actions: `POST .../players/{playerId}/throw`, `.../block`.
 *
 * [destination] is the square the ball lands on (throw) or the square adjacent to the blocker (block).
 */
data class TargetActionRequest(
    val destination: Coordinate,
)
