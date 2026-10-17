package com.mhaefele.gridiron.api.ai.dto

/**
 * Scoring outcomes of one AI submit, from **forSeat**’s perspective (“us” / “them”).
 *
 * Harness reward / metrics only — not packed into the obs vector. Counts are deltas
 * for this submit (usually 0 or 1), not match career totals (those live on [AiNestedObsDto]).
 */
data class AiScoringDeltaDto(
    val touchdownsUs: Int = 0,
    val touchdownsThem: Int = 0,
    val challengeCardsUs: Int = 0,
    val challengeCardsThem: Int = 0,
) {
    init {
        require(touchdownsUs >= 0) { "touchdownsUs cannot be negative" }
        require(touchdownsThem >= 0) { "touchdownsThem cannot be negative" }
        require(challengeCardsUs >= 0) { "challengeCardsUs cannot be negative" }
        require(challengeCardsThem >= 0) { "challengeCardsThem cannot be negative" }
    }
}
