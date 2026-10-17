package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.shared.PlayerId
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Deployed roster — join `playerId` to `snapshot.pieces`.")
data class MatchRosterDto(
    @field:Schema(description = "One entry per miniature on the pitch or in dugouts.")
    val players: List<MatchRosterPlayerDto>,
)

@Schema(description = "Roster row linking a live piece to its frozen team-card stats.")
data class MatchRosterPlayerDto(
    @field:Schema(description = "Same id as `PlayerPieceDto.id` on the snapshot.")
    val playerId: PlayerId,
    @field:Schema(description = "Printed stats and ability from the team card.")
    val playerCard: PlayerCardDto,
)
