package com.mhaefele.gridiron.api.testonly

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import jakarta.validation.constraints.NotEmpty

/** Body for `POST /api/test/dice/d6` or `.../d8` under the `scripted-dice` profile. */
data class QueueDiceValuesRequest(
    @field:NotEmpty
    val values: List<Int>,
)

/** Body for `POST /api/test/dice/block` under the `scripted-dice` profile. */
data class QueueBlockDiceRequest(
    @field:NotEmpty
    val values: List<BlockDieFace>,
)
