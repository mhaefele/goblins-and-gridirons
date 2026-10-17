package com.mhaefele.gridiron.game.opponent.onnx

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import org.springframework.stereotype.Component

/**
 * Conditional pick → remask → pick over packed legal rows (Python `actions.conditional_factor_mask`
 * + deterministic mode of `ConditionalActorCriticPolicy`). Soft-mask bias is omitted for product play.
 */
@Component
class ConditionalFactorPicker {
    /**
     * @param legalMoves rows of [AiObsLayout.ACTION_FACTOR_COUNT] ints (unpadded live set)
     * @param factorLogits concatenated head logits, length [AiObsLayout.ACTION_LOGIT_WIDTH]
     * @return one value per factor head
     */
    fun pickDeterministic(legalMoves: List<IntArray>, factorLogits: FloatArray): IntArray {
        require(legalMoves.isNotEmpty()) { "No legal moves for conditional pick" }
        require(factorLogits.size == AiObsLayout.ACTION_LOGIT_WIDTH) { "logits width ${factorLogits.size} != ${AiObsLayout.ACTION_LOGIT_WIDTH}" }
        val headSizes = AiObsLayout.ACTION_HEAD_SIZES
        val chosen = IntArray(headSizes.size)
        val pickedSoFar = HashMap<Int, Int>(headSizes.size)
        val headOffsets = IntArray(headSizes.size)
        var offset = 0
        for (i in headSizes.indices) {
            headOffsets[i] = offset
            offset += headSizes[i]
        }
        for (factorSlot in AiObsLayout.FACTOR_SAMPLE_ORDER) {
            val mask = factorMask(legalMoves, pickedSoFar, factorSlot)
            val headSize = headSizes[factorSlot]
            val base = headOffsets[factorSlot]
            var bestIndex = -1
            var bestLogit = Float.NEGATIVE_INFINITY
            for (value in 0 until headSize) {
                if (!mask[value]) continue
                val logit = factorLogits[base + value]
                if (logit > bestLogit) {
                    bestLogit = logit
                    bestIndex = value
                }
            }
            check(bestIndex >= 0) { "No legal values on factor slot $factorSlot after picks $pickedSoFar" }
            chosen[factorSlot] = bestIndex
            pickedSoFar[factorSlot] = bestIndex
        }
        return chosen
    }

    fun factorMask(legalMoves: List<IntArray>, chosenFactors: Map<Int, Int>, factorSlot: Int): BooleanArray {
        val headSize = AiObsLayout.ACTION_HEAD_SIZES[factorSlot]
        val mask = BooleanArray(headSize)
        for (row in legalMoves) {
            require(row.size == AiObsLayout.ACTION_FACTOR_COUNT) { "legal row width ${row.size}" }
            if (chosenFactors.any { (slot, value) -> row[slot] != value }) continue
            val value = row[factorSlot]
            if (value in 0 until headSize) mask[value] = true
        }
        return mask
    }

    fun resolveRowIndex(legalMoves: List<IntArray>, factors: IntArray): Int {
        require(factors.size == AiObsLayout.ACTION_FACTOR_COUNT)
        for (i in legalMoves.indices) {
            if (legalMoves[i].contentEquals(factors)) return i
        }
        error("Sampled factors ${factors.toList()} are not a legal move (count=${legalMoves.size})")
    }
}
