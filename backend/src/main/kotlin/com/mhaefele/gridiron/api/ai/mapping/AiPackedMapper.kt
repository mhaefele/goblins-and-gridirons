package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiStepDto
import com.mhaefele.gridiron.api.ai.dto.AiStepPolicyDto
import com.mhaefele.gridiron.api.ai.dto.AiSubmitResultDto
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedStepDto
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedSubmitResultDto
import com.mhaefele.gridiron.api.ai.dto.packed.AiPackedObsDto
import com.mhaefele.gridiron.api.ai.dto.packed.AiPackedStepDto
import com.mhaefele.gridiron.api.ai.dto.packed.AiPackedSubmitResultDto
import org.springframework.stereotype.Component

/** Nested step/submit → packed — same harness tree, [AiPackedObsDto] on [policy.obs]. */
@Component
class AiPackedMapper(
    private val obsPacker: AiObsPacker,
) {
    fun pack(step: AiNestedStepDto): AiPackedStepDto =
        AiStepDto(
            schemaVersion = step.schemaVersion,
            policy = AiStepPolicyDto(
                obs = AiPackedObsDto(obsPacker.pack(step.policy.obs).toList()),
                legalMoves = step.policy.legalMoves,
            ),
            harness = step.harness,
        )

    fun pack(result: AiNestedSubmitResultDto): AiPackedSubmitResultDto =
        AiSubmitResultDto(
            schemaVersion = result.schemaVersion,
            chosenLegalActionId = result.chosenLegalActionId,
            scoring = result.scoring,
            afterSubmitRewards = result.afterSubmitRewards,
            next = pack(result.next),
        )
}
