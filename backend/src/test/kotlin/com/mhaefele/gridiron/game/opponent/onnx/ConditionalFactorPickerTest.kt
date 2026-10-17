package com.mhaefele.gridiron.game.opponent.onnx

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ConditionalFactorPickerTest : BaseApiTest() {
    @Autowired
    private lateinit var picker: ConditionalFactorPicker

    @Test
    fun `deterministic pick narrows actorKind then boardTarget`() {
        val legal = listOf(
            intArrayOf(10, 5, 3),
            intArrayOf(10, 7, 3),
            intArrayOf(20, 5, 3),
        )
        val logits = FloatArray(AiObsLayout.ACTION_LOGIT_WIDTH) { -10f }
        logits[10] = 5f
        logits[20] = 1f
        logits[AiObsLayout.ACTOR_KIND_HEAD_SIZE + 5] = 1f
        logits[AiObsLayout.ACTOR_KIND_HEAD_SIZE + 7] = 4f
        logits[AiObsLayout.ACTOR_KIND_HEAD_SIZE + AiObsLayout.BOARD_TARGET_HEAD_SIZE + 3] = 1f

        val picked = picker.pickDeterministic(legal, logits)
        assertThat(picked.toList()).containsExactly(10, 7, 3)
        assertThat(picker.resolveRowIndex(legal, picked)).isEqualTo(1)
    }

    @Test
    fun `factor mask ignores rows that disagree with prior picks`() {
        val legal = listOf(
            intArrayOf(1, 2, 0),
            intArrayOf(1, 3, 0),
            intArrayOf(9, 2, 0),
        )
        val mask = picker.factorMask(legal, mapOf(0 to 1), factorSlot = 1)
        assertThat(mask[2]).isTrue()
        assertThat(mask[3]).isTrue()
        assertThat(mask.count { it }).isEqualTo(2)
    }
}
