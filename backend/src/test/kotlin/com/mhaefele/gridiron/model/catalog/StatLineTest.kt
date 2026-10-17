package com.mhaefele.gridiron.model.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatLineTest {
    @Test
    fun `unthrowable cannot hold the ball`() {
        assertThat(ThrowStat.Unthrowable.canHoldBall()).isFalse()
        assertThat(ThrowStat.Threshold(4).canHoldBall()).isTrue()
    }

    @Test
    fun `no armour auto fails checks`() {
        assertThat(ArmourStat.NoArmour.failsAutomatically()).isTrue()
        assertThat(ArmourStat.Threshold(3).failsAutomatically()).isFalse()
    }
}
