package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.shared.Coordinate
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Static pitch terrain — playable grid without player occupancy.")
data class PitchLayoutDto(
    @field:Schema(description = "Catalog id for this pitch definition.")
    val pitchDefinitionId: PitchDefinitionId,
    @field:Schema(description = "Playable width in squares.")
    val playWidth: Int,
    @field:Schema(description = "Playable height in squares.")
    val playHeight: Int,
    @field:Schema(description = "Grid row (y) of the HOME end zone.")
    val homeEndZoneRow: Int,
    @field:Schema(description = "Grid row (y) of the AWAY end zone.")
    val awayEndZoneRow: Int,
    @field:Schema(description = "Trapdoor square coordinates.")
    val trapdoors: List<Coordinate>,
    @field:Schema(description = "Blocked (impassable) square coordinates.")
    val blockedSquares: List<Coordinate>,
)
