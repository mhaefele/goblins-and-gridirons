package com.mhaefele.gridiron.game.action.dto

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.shared.ThrowRange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DiceRolledEventTest {

    @Test
    fun `throw dice rolled records threshold modifier and fail`() {
        val event = throwDiceRolled(
            naturalRoll = 2,
            throwStat = ThrowStat.Threshold(4),
            range = ThrowRange.SHORT,
            obstructed = false,
        )

        assertThat(event).isEqualTo(
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 2,
                threshold = 4,
                modifier = 0,
                checkResult = DiceCheckResult.FAIL,
            ),
        )
        assertThat(event.adjustedTotal()).isEqualTo(2)
    }

    @Test
    fun `throw dice rolled records fumble on natural 1`() {
        val event = throwDiceRolled(
            naturalRoll = 1,
            throwStat = ThrowStat.Threshold(4),
            range = ThrowRange.SHORT,
            obstructed = false,
        )

        assertThat(event.checkResult).isEqualTo(DiceCheckResult.FUMBLE)
        assertThat(event.adjustedTotal()).isNull()
    }

    @Test
    fun `armour dice rolled records flatten modifier`() {
        val event = armourDiceRolled(
            naturalRoll = 2,
            armour = ArmourStat.Threshold(5),
            flatten = true,
        )

        assertThat(event).isEqualTo(
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.ARMOUR,
                d6 = 2,
                threshold = 5,
                modifier = -1,
                checkResult = DiceCheckResult.FAIL,
            ),
        )
    }

    @Test
    fun `block dice rolled records winning face`() {
        val event = blockDiceRolled(listOf(BlockDieFace.MISS, BlockDieFace.FLATTEN))

        assertThat(event).isEqualTo(
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.BLOCK,
                blockFaces = listOf(BlockDieFace.MISS, BlockDieFace.FLATTEN),
                blockResult = BlockDieFace.FLATTEN,
            ),
        )
    }
}
