package com.mhaefele.gridiron.api.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Mutable match frame — event cursor, HUD, and player positions.")
data class MatchSnapshotDto(
    @field:Schema(description = "Highest stored event domain id for this match (`0` when none yet).")
    val lastEventId: Int,
    @field:Schema(description = "Scores, turn clock, phase, and public challenge tableau.")
    val gameState: MatchGameStateDto,
    @field:Schema(description = "Player positions and loose-ball squares.")
    val pieces: PieceSnapshotDto,
)
