package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Runtime board occupancy — players on the pitch or in dugouts plus loose balls.")
data class PieceSnapshotDto(
    @field:Schema(description = "Every deployed player and their current square or dugout status.")
    val players: List<PlayerPieceDto>,
    @field:Schema(description = "Pitch coordinates with a loose ball (not held by a player).")
    val looseBallLocations: List<Coordinate>,
)

@Schema(description = "One player piece on the board or in reserves.")
data class PlayerPieceDto(
    @field:Schema(description = "Roster id — join to `GET …/roster` for card stats.")
    val id: PlayerId,
    @field:Schema(description = "HOME or AWAY.")
    val team: TeamSide,
    @field:Schema(description = "Standing, prone, stunned, reserves, …")
    val status: PlayerStatus,
    @field:Schema(description = "Pitch coordinate when on the field; null in the dugout.", nullable = true)
    val at: Coordinate?,
    @field:Schema(description = "True when this player is holding the ball.")
    val holdsBall: Boolean,
)
