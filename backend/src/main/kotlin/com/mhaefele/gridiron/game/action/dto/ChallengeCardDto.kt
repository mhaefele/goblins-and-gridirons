package com.mhaefele.gridiron.game.action.dto

import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Challenge card as shown on the public tableau or deck top.")
data class ChallengeCardDto(
    @field:Schema(description = "Claim condition enum (what you must do to earn it).")
    val challengeCondition: ChallengeClaimCondition,
    @field:Schema(description = "Printed card title.")
    val title: String,
    @field:Schema(description = "Points awarded when claimed.")
    val points: Int,
) {
    constructor(condition: ChallengeClaimCondition) : this(challengeCondition = condition, title = condition.displayName, points = condition.points,)

    @get:Schema(description = "Claim-condition card text.")
    val description: String get() = challengeCondition.description
}
