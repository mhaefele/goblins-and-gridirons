package com.mhaefele.gridiron.game.opponent.onnx

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import org.springframework.stereotype.Component

/**
 * Argmax over join-scored legal rows (Python `LegalRowActorCriticPolicy` deterministic mode).
 * Soft-mask bias is omitted for product play.
 */
@Component
class LegalRowPicker {
    /**
     * @param legalMoves live packed rows (unpadded)
     * @param rowScores length [AiObsLayout.LEGAL_ROW_SCORE_WIDTH] (pad slots ignored)
     * @return index into [legalMoves]
     */
    fun pickDeterministic(legalMoves: List<IntArray>, rowScores: FloatArray): Int {
        require(legalMoves.isNotEmpty()) { "No legal moves for legal-row pick" }
        require(rowScores.size == AiObsLayout.LEGAL_ROW_SCORE_WIDTH) { "row score width ${rowScores.size} != ${AiObsLayout.LEGAL_ROW_SCORE_WIDTH}" }
        var bestIndex = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in legalMoves.indices) {
            val score = rowScores[i]
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        check(bestIndex >= 0) { "No legal-row score among ${legalMoves.size} moves" }
        return bestIndex
    }
}
