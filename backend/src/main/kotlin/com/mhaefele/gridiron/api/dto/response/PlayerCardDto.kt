package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Frozen team-card stats for one roster player.")
data class PlayerCardDto(
    @field:Schema(description = "Team catalog id (e.g. `human`, `orc`).")
    val teamId: TeamCatalogId,
    @field:Schema(description = "Printed position name on the card.")
    val positionLabel: String,
    @field:Schema(description = "Fixed move allowance in squares; null when move is rolled as D6 each run.", nullable = true)
    val move: Int?,
    @field:Schema(description = "Number of block dice when blocking.")
    val blockDiceCount: Int,
    @field:Schema(description = "Minimum natural D6 to pass a throw check (2–6); null when unthrowable.", nullable = true)
    val throwMinimumRoll: Int?,
    @field:Schema(description = "Minimum natural D6 to pass an armour check (2–6); null when no armour.", nullable = true)
    val armourMinimumRoll: Int?,
    @field:Schema(description = "Special ability; null when the position has none.", nullable = true)
    val ability: CardAbilityDto?,
    @field:Schema(description = "True when this position may throw a teammate (Ogre / Troll / Oakwalker).")
    val canThrowTeammate: Boolean = false,
)

@Schema(description = "Printed special ability on a player card.")
data class CardAbilityDto(
    @field:Schema(description = "Ability enum name (e.g. `BALL_GUARD`).")
    val key: String,
    @field:Schema(description = "Short rules text from the card.")
    val description: String,
)
