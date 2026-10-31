package com.mhaefele.gridiron.game.opponent.onnx

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class LegalRowPickerTest : BaseApiTest() {
    @Autowired
    private lateinit var picker: LegalRowPicker

    @Test
    fun `deterministic pick takes the highest score among live rows`() {
        val legal = listOf(
            intArrayOf(10, 5, 3),
            intArrayOf(10, 7, 3),
            intArrayOf(20, 5, 3),
        )
        val scores = FloatArray(AiObsLayout.LEGAL_ROW_SCORE_WIDTH) { -10f }
        scores[0] = 1f
        scores[1] = 4f
        scores[2] = 2f
        assertThat(picker.pickDeterministic(legal, scores)).isEqualTo(1)
    }
}
