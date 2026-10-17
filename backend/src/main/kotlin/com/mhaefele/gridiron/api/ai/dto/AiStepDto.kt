package com.mhaefele.gridiron.api.ai.dto

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.mhaefele.gridiron.api.ai.dto.internal.AiStepEnvelopeValidation

/**
 * One gym decision step — [Obs] selects nested vs packed [policy.obs]; [harness] is shared.
 *
 * | Block | Fields | Consumer |
 * |-------|--------|----------|
 * | **Policy** | [policy.obs], [policy.legalMoves] | Model tensor + action mask |
 * | **Harness** | [harness] meta, [harness.rewards], [harness.legalRows] | Gym reward + submit sidecar |
 *
 * [@JsonUnwrapped] on [policy] and [harness] — JSON wire is flat (`obs`, `legalMoves`, `rewards`, …).
 */
data class AiStepDto<Obs>(
    val schemaVersion: Int = AiObsLayout.SCHEMA_VERSION,
    @field:JsonUnwrapped
    val policy: AiStepPolicyDto<Obs>,
    @field:JsonUnwrapped
    val harness: AiHarnessStepDto,
) {
    init {
        AiStepEnvelopeValidation.validate(schemaVersion, policy.legalMoves, harness)
    }

    fun isEpisodeEnded(): Boolean = harness.isEpisodeEnded()
}
