package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.dice.dto.bestBlockFace
import com.mhaefele.gridiron.game.dice.dto.bestEnforcerOrder
import com.mhaefele.gridiron.game.dice.dto.blockDiceCount
import com.mhaefele.gridiron.game.dice.dto.distinctFaceOrders
import com.mhaefele.gridiron.game.dice.dto.rollBlockPool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class BlockDieTest {
    @ParameterizedTest
    @CsvSource(
        "1, false, 1",
        "2, false, 2",
        "2, true, 3",
        "3, true, 3",
    )
    fun blockDiceCount(blockStat: Int, assisted: Boolean, expected: Int) {
        assertThat(blockDiceCount(blockStat, assisted)).isEqualTo(expected)
    }

    @Test
    fun `weightedFaces expands shove twice`() {
        assertThat(BlockDieFace.SHOVE.weight).isEqualTo(2)
        assertThat(BlockDieFace.SMASH.weight).isEqualTo(1)
        assertThat(BlockDieFace.weightedFaces()).containsExactly(
            BlockDieFace.SMASH,
            BlockDieFace.FLATTEN,
            BlockDieFace.SHOVE,
            BlockDieFace.SHOVE,
            BlockDieFace.TACKLE,
            BlockDieFace.MISS,
        )
    }

    @Test
    fun `best face prefers smash over miss`() {
        assertThat(bestBlockFace(listOf(BlockDieFace.MISS, BlockDieFace.SMASH)))
            .isEqualTo(BlockDieFace.SMASH)
    }

    @Test
    fun `best enforcer order puts smash before miss`() {
        assertThat(bestEnforcerOrder(listOf(BlockDieFace.MISS, BlockDieFace.SMASH)))
            .containsExactly(BlockDieFace.SMASH, BlockDieFace.MISS)
    }

    @Test
    fun `distinct face orders for miss smash`() {
        assertThat(distinctFaceOrders(listOf(BlockDieFace.MISS, BlockDieFace.SMASH)))
            .containsExactly(
                listOf(BlockDieFace.MISS, BlockDieFace.SMASH),
                listOf(BlockDieFace.SMASH, BlockDieFace.MISS),
            )
    }

    @Test
    fun `roll block pool picks best of queued dice`() {
        val roller = ScriptedDiceRoller()
        roller.queueBlockDice(BlockDieFace.MISS, BlockDieFace.FLATTEN, BlockDieFace.SHOVE)

        assertThat(rollBlockPool(roller, blockStat = 2, assisted = true))
            .isEqualTo(BlockDieFace.FLATTEN)
    }
}
