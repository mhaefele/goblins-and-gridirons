package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.api.ai.dto.internal.AiStepEnvelopeValidation

/** Policy inputs for one step — [Obs] is route-specific ([nested.AiNestedObsDto] vs [packed.AiPackedObsDto]). */
data class AiStepPolicyDto<Obs>(
    val obs: Obs,
    val legalMoves: List<List<Int>>,
) {
    init {
        AiStepEnvelopeValidation.validateLegalMoves(legalMoves)
    }
}
