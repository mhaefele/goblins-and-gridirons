package com.mhaefele.gridiron.api.ai.dto.packed

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.shared.LegalActionId

/**
 * One dumped (board, chosen move) example for behavioral cloning.
 *
 * [policy] matches HTTP `…/step/packed` [AiPackedStepPolicyDto]; [@JsonUnwrapped] keeps JSONL
 * flat (`obs`, `legalMoves` at top level). Soft-mask flags are train-rollout only — not dumped
 * (clone must imitate Lookahead without the logit-bias crutch).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExpertStepRecord(
    val schemaVersion: Int = AiObsLayout.SCHEMA_VERSION,
    @field:JsonUnwrapped
    val policy: AiPackedStepPolicyDto,
    val chosenLegalActionId: LegalActionId,
    /** Factor row for [chosenLegalActionId] (length [AiObsLayout.ACTION_FACTOR_COUNT]) — clone action label. */
    val chosenFactors: List<Int>,
) {
    init {
        require(schemaVersion == AiObsLayout.SCHEMA_VERSION) { "Unsupported expert schemaVersion=$schemaVersion" }
        require(policy.legalMoves.isNotEmpty()) { "expert step must have non-empty legals" }
        require(chosenFactors.size == AiObsLayout.ACTION_FACTOR_COUNT) { "chosenFactors width ${chosenFactors.size} != ${AiObsLayout.ACTION_FACTOR_COUNT}" }
        val matches = policy.legalMoves.withIndex().filter { it.value == chosenFactors }
        require(matches.size == 1) { "chosenFactors must match exactly one legalMoves row, got ${matches.size}" }
    }
}
