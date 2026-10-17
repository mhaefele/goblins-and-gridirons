package com.mhaefele.gridiron.api.ai.dto.packed

import com.mhaefele.gridiron.api.ai.dto.AiStepDto
import com.mhaefele.gridiron.api.ai.dto.AiStepPolicyDto
import com.mhaefele.gridiron.api.ai.dto.AiSubmitResultDto

/** GET `/step/packed` and packed submit [next]. */
typealias AiPackedStepPolicyDto = AiStepPolicyDto<AiPackedObsDto>

typealias AiPackedStepDto = AiStepDto<AiPackedObsDto>

/** `POST /api/ai/matches/{id}/submit/packed`. */
typealias AiPackedSubmitResultDto = AiSubmitResultDto<AiPackedObsDto>
