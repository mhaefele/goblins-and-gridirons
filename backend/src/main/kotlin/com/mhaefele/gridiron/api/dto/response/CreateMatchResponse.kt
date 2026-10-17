package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.shared.MatchId

/** Response from `POST /api/matches` — new match id plus GAME_START snapshot. */
data class CreateMatchResponse(
    val matchId: MatchId,
    val snapshot: MatchSnapshotDto,
)
