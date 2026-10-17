package com.mhaefele.gridiron.api.ai.dto.nested

import com.mhaefele.gridiron.api.ai.dto.AiStepDto
import com.mhaefele.gridiron.api.ai.dto.AiStepPolicyDto
import com.mhaefele.gridiron.api.ai.dto.AiSubmitResultDto

/** GET `/step` and nested submit [next]. */
typealias AiNestedStepPolicyDto = AiStepPolicyDto<AiNestedObsDto>

typealias AiNestedStepDto = AiStepDto<AiNestedObsDto>

/** `POST /api/ai/matches/{id}/submit`. */
typealias AiNestedSubmitResultDto = AiSubmitResultDto<AiNestedObsDto>
