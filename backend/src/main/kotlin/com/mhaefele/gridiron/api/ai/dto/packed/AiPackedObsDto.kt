package com.mhaefele.gridiron.api.ai.dto.packed

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout

/** Packed policy observation — flat float tensor ([AiObsLayout.OBSERVATION_VECTOR_LENGTH]). */
data class AiPackedObsDto(val values: List<Float>) {
    init {
        require(values.size == AiObsLayout.OBSERVATION_VECTOR_LENGTH) { "packed obs length ${values.size} != ${AiObsLayout.OBSERVATION_VECTOR_LENGTH}" }
    }

    @JsonValue
    fun toJson(): List<Float> = values

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromJson(values: List<Float>): AiPackedObsDto = AiPackedObsDto(values)
    }
}
