package com.mhaefele.gridiron.api.ai.dto.packed

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.shared.LegalActionId

/**
 * One dumped (board, chosen move) example for behavioral cloning.
 *
 * [policy] matches HTTP `…/step/packed` [AiPackedStepPolicyDto]; [@JsonUnwrapped] keeps JSONL
 * flat (`obs`, `legalMoves` at top level). [softMaskLegal] is the only harness slice on the dump.
 */
data class ExpertStepRecord(
    val schemaVersion: Int = AiObsLayout.SCHEMA_VERSION,
    @field:JsonUnwrapped
    val policy: AiPackedStepPolicyDto,
    /** Parallel to [AiPackedStepPolicyDto.legalMoves] — logit-bias flags only. */
    val softMaskLegal: List<Boolean>,
    val chosenLegalActionId: LegalActionId,
    /** Factor row for [chosenLegalActionId] (length [AiObsLayout.ACTION_FACTOR_COUNT]) — clone action label. */
    val chosenFactors: List<Int>,
) {
    init {
        require(schemaVersion == AiObsLayout.SCHEMA_VERSION) { "Unsupported expert schemaVersion=$schemaVersion" }
        require(policy.legalMoves.isNotEmpty()) { "expert step must have non-empty legals" }
        require(softMaskLegal.size == policy.legalMoves.size) { "softMaskLegal size ${softMaskLegal.size} != legalMoves size ${policy.legalMoves.size}" }
        require(chosenFactors.size == AiObsLayout.ACTION_FACTOR_COUNT) { "chosenFactors width ${chosenFactors.size} != ${AiObsLayout.ACTION_FACTOR_COUNT}" }
        val matches = policy.legalMoves.withIndex().filter { it.value == chosenFactors }
        require(matches.size == 1) { "chosenFactors must match exactly one legalMoves row, got ${matches.size}" }
    }
}
