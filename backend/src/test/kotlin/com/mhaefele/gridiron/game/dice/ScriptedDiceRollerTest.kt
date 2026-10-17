package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Random

class ScriptedDiceRollerTest {
    private val roller = ScriptedDiceRoller(fallbackToDefaultPattern = false)

    @BeforeEach
    fun reset() {
        roller.reset()
    }

    @Test
    fun `returns queued d6 values in order`() {
        roller.queueD6(4, 6, 1)

        assertThat(roller.d6()).isEqualTo(4)
        assertThat(roller.d6()).isEqualTo(6)
        assertThat(roller.d6()).isEqualTo(1)
    }

    @Test
    fun `returns queued d8 values in order`() {
        roller.queueD8(3, 7)

        assertThat(roller.d8()).isEqualTo(3)
        assertThat(roller.d8()).isEqualTo(7)
    }

    @Test
    fun `returns queued block dice in order`() {
        roller.queueBlockDice(BlockDieFace.SMASH, BlockDieFace.MISS)

        assertThat(roller.blockDice(2)).containsExactly(BlockDieFace.SMASH, BlockDieFace.MISS)
    }

    @Test
    fun `throws when queue is exhausted without fallback`() {
        roller.queueD6(4)

        assertThat(roller.d6()).isEqualTo(4)
        assertThatThrownBy { roller.d6() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("exhausted")
    }

    @Test
    fun `other die types remain queued while d6 is consumed`() {
        roller.queueD8(5)
        roller.queueD6(4)

        assertThat(roller.d6()).isEqualTo(4)
        assertThat(roller.d8()).isEqualTo(5)
    }

    @Test
    fun `throws when the requested die type has no queued value`() {
        roller.queueD8(5)

        assertThatThrownBy { roller.d6() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("D6")
    }

    @Test
    fun `pass through delegates to random roller`() {
        val random = RandomDiceRoller(Random(42))
        val expectedD6 = random.d6()
        val expectedD8 = random.d8()
        val expectedBlock = random.blockDice(2)

        val roller = ScriptedDiceRoller(RandomDiceRoller(Random(42)), fallbackToDefaultPattern = false)
        roller.usePassThrough()

        assertThat(roller.d6()).isEqualTo(expectedD6)
        assertThat(roller.d8()).isEqualTo(expectedD8)
        assertThat(roller.blockDice(2)).isEqualTo(expectedBlock)
    }

    @Test
    fun `reset leaves queued mode`() {
        roller.usePassThrough()
        roller.reset()
        roller.queueD6(3)

        assertThat(roller.d6()).isEqualTo(3)
    }

    @Test
    fun `fallback cycles the default d6 pattern`() {
        val cycling = ScriptedDiceRoller(fallbackToDefaultPattern = true)
        val expected = ScriptedDiceRoller.DEFAULT_D6 + ScriptedDiceRoller.DEFAULT_D6.take(2)

        assertThat((1..expected.size).map { cycling.d6() }).containsExactlyElementsOf(expected)
    }

    @Test
    fun `fallback cycles the default d8 pattern`() {
        val cycling = ScriptedDiceRoller(fallbackToDefaultPattern = true)
        val expected = ScriptedDiceRoller.DEFAULT_D8 + ScriptedDiceRoller.DEFAULT_D8.take(1)

        assertThat((1..expected.size).map { cycling.d8() }).containsExactlyElementsOf(expected)
    }

    @Test
    fun `fallback cycles the default block pattern`() {
        val cycling = ScriptedDiceRoller(fallbackToDefaultPattern = true)
        val expected = ScriptedDiceRoller.DEFAULT_BLOCK + ScriptedDiceRoller.DEFAULT_BLOCK.take(2)

        assertThat(cycling.blockDice(expected.size)).containsExactlyElementsOf(expected)
    }

    @Test
    fun `queued values are used before the default pattern resumes`() {
        val cycling = ScriptedDiceRoller(fallbackToDefaultPattern = true)
        cycling.queueD6(listOf(6, 1))

        assertThat(cycling.d6()).isEqualTo(6)
        assertThat(cycling.d6()).isEqualTo(1)
        assertThat(cycling.d6()).isEqualTo(ScriptedDiceRoller.DEFAULT_D6[0])
        assertThat(cycling.d6()).isEqualTo(ScriptedDiceRoller.DEFAULT_D6[1])
    }

    @Test
    fun `queued d8 and block values are independent of d6`() {
        val cycling = ScriptedDiceRoller(fallbackToDefaultPattern = true)
        cycling.queueD6(listOf(4))
        cycling.queueD8(listOf(8))
        cycling.queueBlockDice(listOf(BlockDieFace.MISS))

        assertThat(cycling.d8()).isEqualTo(8)
        assertThat(cycling.blockDice(1)).containsExactly(BlockDieFace.MISS)
        assertThat(cycling.d6()).isEqualTo(4)
        assertThat(cycling.d8()).isEqualTo(ScriptedDiceRoller.DEFAULT_D8[0])
        assertThat(cycling.blockDice(1)).containsExactly(ScriptedDiceRoller.DEFAULT_BLOCK[0])
    }

    @Test
    fun `queue appends to remaining overrides`() {
        val cycling = ScriptedDiceRoller(fallbackToDefaultPattern = true)
        cycling.queueD6(listOf(5))
        cycling.queueD6(listOf(6))

        assertThat(cycling.d6()).isEqualTo(5)
        assertThat(cycling.d6()).isEqualTo(6)
        assertThat(cycling.d6()).isEqualTo(ScriptedDiceRoller.DEFAULT_D6[0])
    }

    @Test
    fun `rejects out-of-range d6 values`() {
        assertThatThrownBy { roller.queueD6(listOf(7)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("1–6")
    }
}
