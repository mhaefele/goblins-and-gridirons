package com.mhaefele.gridiron.api.integration.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.mhaefele.gridiron.api.dto.response.BonusHandDto
import com.mhaefele.gridiron.api.dto.response.MatchGameStateDto
import com.mhaefele.gridiron.api.dto.response.MatchSnapshotDto
import com.mhaefele.gridiron.api.dto.response.PieceSnapshotDto
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Integration match frame — public board state plus your seat's hidden bonus hand.")
data class IntegrationMatchSnapshotDto(
    @field:JsonUnwrapped
    val snapshot: MatchSnapshotDto,
    @field:Schema(description = "Hidden bonus-play cards for the calling seat (`agentSeat` on the request).")
    val bonusHand: BonusHandDto,
) {
    @get:JsonIgnore
    val lastEventId: Int get() = snapshot.lastEventId

    @get:JsonIgnore
    val gameState: MatchGameStateDto get() = snapshot.gameState

    @get:JsonIgnore
    val pieces: PieceSnapshotDto get() = snapshot.pieces
}
