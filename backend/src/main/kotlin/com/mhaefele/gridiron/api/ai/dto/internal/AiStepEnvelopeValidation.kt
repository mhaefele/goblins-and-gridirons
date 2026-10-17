package com.mhaefele.gridiron.api.ai.dto.internal

import com.mhaefele.gridiron.api.ai.dto.AiHarnessStepDto
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout

/** Shared invariants for nested and packed step envelopes (harness + policy legals). */
internal object AiStepEnvelopeValidation {
    fun validate(schemaVersion: Int, legalMoves: List<List<Int>>, harness: AiHarnessStepDto) {
        require(schemaVersion == AiObsLayout.SCHEMA_VERSION) { "Unsupported AI step schemaVersion=$schemaVersion" }
        val turnNumber = harness.rewards.turnNumber
        val ended = harness.isEpisodeEnded()
        if (ended) {
            require(legalMoves.isEmpty()) { "terminal step must have empty policy legals(got ${legalMoves.size}) actingSeat=${harness.actingSeat} turn=$turnNumber trainingEnded=${harness.trainingEpisodeEnded}" }
        } else {
            require(legalMoves.isNotEmpty()) { "live step must have non-empty policy legals actingSeat=${harness.actingSeat} perspectiveSeat=${harness.perspectiveSeat} turn=$turnNumber trainingEnded=${harness.trainingEpisodeEnded}" }
        }
        require(legalMoves.size == harness.legalRows.size) { "policy legalMoves size ${legalMoves.size} != harness legalRows size ${harness.legalRows.size}" }
    }

    fun validateLegalMoves(legalMoves: List<List<Int>>) {
        for ((i, row) in legalMoves.withIndex()) {
            require(row.size == AiObsLayout.ACTION_FACTOR_COUNT) { "legalMoves[$i] width ${row.size} != ${AiObsLayout.ACTION_FACTOR_COUNT}" }
        }
    }
}
