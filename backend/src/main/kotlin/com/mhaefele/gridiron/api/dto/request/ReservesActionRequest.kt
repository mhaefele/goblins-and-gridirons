package com.mhaefele.gridiron.api.dto.request

import com.mhaefele.gridiron.shared.Coordinate

/** Body for `POST .../players/{playerId}/reserves` — deploy onto an empty end-zone square. */
data class ReservesActionRequest(
    val placement: Coordinate,
)
