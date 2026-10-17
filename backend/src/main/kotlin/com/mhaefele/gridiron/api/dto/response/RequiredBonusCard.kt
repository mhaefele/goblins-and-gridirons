package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.model.catalog.BonusPlay
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Bonus card that must be spent for this legal action (encoded in the action id).")
data class RequiredBonusCard(
    @field:Schema(description = "Bonus play type that the engine auto-plays on submit.")
    val bonusPlay: BonusPlay,
)
