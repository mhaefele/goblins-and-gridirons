package com.mhaefele.gridiron.api.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "What happens if this move enters a square with a loose ball.")
enum class BallEncounter {
    @field:Schema(description = "Mover attempts pickup.")
    PICK_UP,
    @field:Schema(description = "Loose ball bounces from the entered square.")
    BOUNCE,
}
