package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.withinPercentage
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.Random

class RandomDiceRollerTest {
    @RepeatedTest(20)
    fun `d6 is in range`() {
        val roller = RandomDiceRoller()
        assertThat(roller.d6()).isBetween(1, 6)
    }

    @RepeatedTest(20)
    fun `d8 is in range`() {
        val roller = RandomDiceRoller()
        assertThat(roller.d8()).isBetween(1, 8)
    }

    @Test
    fun `blockDice returns requested count`() {
        val roller = RandomDiceRoller(Random(0))
        assertThat(roller.blockDice(3)).hasSize(3)
    }

    @Test
    fun `blockDice only rolls rulebook faces`() {
        val roller = RandomDiceRoller(Random(0))
        val faces = roller.blockDice(500)
        assertThat(faces).allMatch { it in BlockDieFace.weightedFaces() }
    }

    @Test
    fun `block die weights favor shove twice`() {
        val roller = RandomDiceRoller(Random(42))
        val counts = roller.blockDice(12_000).groupingBy { it }.eachCount()

        assertThat(counts.getValue(BlockDieFace.SHOVE).toDouble())
            .isCloseTo(4_000.0, withinPercentage(10.0))
        assertThat(counts.getValue(BlockDieFace.SMASH).toDouble())
            .isCloseTo(2_000.0, withinPercentage(10.0))
    }
}
