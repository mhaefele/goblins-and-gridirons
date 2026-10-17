package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.shared.ThrowRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DiceCheckTest {
    @ParameterizedTest
    @CsvSource(
        "1, 3, 0, false",
        "6, 6, 0, true",
        "4, 4, 0, true",
        "3, 4, 0, false",
        "2, 4, 1, false",
        "2, 3, 1, true",
        "5, 6, -1, false",
        "6, 6, -1, true",
        "5, 6, 2, true",
        "4, 3, 2, true",
    )
    fun passesCheck(roll: Int, threshold: Int, modifier: Int, expected: Boolean) {
        assertThat(passesCheck(roll, threshold, modifier)).isEqualTo(expected)
    }

    @Test
    fun `throw fumbles on natural 1 before modifiers`() {
        val outcome = throwCheck(
            naturalRoll = 1,
            throwStat = ThrowStat.Threshold(3),
            range = ThrowRange.SHORT,
            obstructed = false,
        )

        assertThat(outcome).isEqualTo(ThrowCheckOutcome.Fumble)
    }

    @Test
    fun `throw fumbles when modified result is 1`() {
        assertThat(
            throwCheck(2, ThrowStat.Threshold(3), ThrowRange.LONG, obstructed = false),
        ).isEqualTo(ThrowCheckOutcome.Fumble)
        assertThat(
            throwCheck(2, ThrowStat.Threshold(3), ThrowRange.SHORT, obstructed = true),
        ).isEqualTo(ThrowCheckOutcome.Fumble)
    }

    @Test
    fun `natural 2 short open is fail or success not fumble`() {
        assertThat(
            throwCheck(2, ThrowStat.Threshold(3), ThrowRange.SHORT, obstructed = false),
        ).isEqualTo(ThrowCheckOutcome.Fail(2, 3))
        assertThat(
            throwCheck(2, ThrowStat.Threshold(2), ThrowRange.SHORT, obstructed = false),
        ).isEqualTo(ThrowCheckOutcome.Success)
    }

    @Test
    fun `long and obstructed combine to minus one cap`() {
        assertThat(throwCheckModifier(ThrowRange.LONG, obstructed = true)).isEqualTo(-1)
    }

    @Test
    fun `unthrowable never rolls`() {
        assertThat(
            throwCheck(4, ThrowStat.Unthrowable, ThrowRange.SHORT, obstructed = false),
        ).isEqualTo(ThrowCheckOutcome.Unthrowable)
    }

    @Test
    fun `hand off range cannot enter throw check`() {
        assertThatThrownBy { throwCheck(4, ThrowStat.Threshold(3), ThrowRange.HAND_OFF, obstructed = false) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `armour no armour auto fails without rolling`() {
        assertThat(armourCheck(6, ArmourStat.NoArmour)).isEqualTo(ArmourCheckOutcome.NoArmour)
    }

    @Test
    fun `flatten applies minus one to the roll`() {
        assertThat(armourCheck(6, ArmourStat.Threshold(5), flatten = true))
            .isEqualTo(ArmourCheckOutcome.Success)
        assertThat(armourCheck(5, ArmourStat.Threshold(5), flatten = true))
            .isEqualTo(ArmourCheckOutcome.Fail(5, 5))
    }
}
