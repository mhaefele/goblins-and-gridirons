package com.mhaefele.gridiron.game.dice

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Random

class ContextualDiceRollerTest {
    private val context = DiceRollerContext()
    private val leaf = ScriptedDiceRoller(fallbackToDefaultPattern = false)
    private val bean = ContextualDiceRoller(context, RandomDiceRoller(Random(0)), leaf)

    @Test
    fun `thread overlay is used then previous roller is restored`() {
        leaf.queueD6(5)
        val overlay = ScriptedDiceRoller(fallbackToDefaultPattern = false).queueD6(4, 3)

        context.withRoller(overlay) {
            assertThat(bean.d6()).isEqualTo(4)
            context.withRoller(ScriptedDiceRoller(fallbackToDefaultPattern = false).queueD6(1)) { assertThat(bean.d6()).isEqualTo(1) }
            assertThat(bean.d6()).isEqualTo(3)
        }
        assertThat(bean.d6()).isEqualTo(5)
    }

    @Test
    fun `bound overlay does not recurse into itself`() {
        leaf.queueD6(6)
        context.withRoller(bean) { assertThat(bean.d6()).isEqualTo(6) }
    }

    @Test
    fun `overlay restore runs when the block throws`() {
        leaf.queueD6(5)
        assertThatThrownBy {
            context.withRoller(ScriptedDiceRoller(fallbackToDefaultPattern = false).queueD6(1)) { error("boom") }
        }.hasMessage("boom")
        assertThat(bean.d6()).isEqualTo(5)
    }
}
