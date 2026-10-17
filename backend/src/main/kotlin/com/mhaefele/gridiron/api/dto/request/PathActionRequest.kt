package com.mhaefele.gridiron.api.dto.request

import com.mhaefele.gridiron.shared.Path

/**
 * Body for path-based player actions: `POST .../players/{playerId}/run`, `.../mark`, `.../sidestep`.
 *
 * [path] is the ordered squares the acting player moves through (including start).
 */
data class PathActionRequest(
    val path: Path,
)
