package com.mhaefele.gridiron.game.dice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReRollPolicyTest {
    private val roll = RollId("roll-1")

    @Test
    fun `allows one re-roll per eligible roll`() {
        val state = ReRollState().registerRoll(roll)

        val allowed = state.requestReRoll(roll)

        assertThat(allowed).isEqualTo(ReRollResult.Allowed(ReRollState(reRolledRollIds = setOf(roll))))
    }

    @Test
    fun `denies re-roll of a re-roll`() {
        val state = ReRollState(eligibleRollIds = setOf(roll), reRolledRollIds = setOf(roll))

        val denied = state.requestReRoll(roll)

        assertThat(denied).isEqualTo(ReRollResult.Denied("Cannot re-roll a re-roll"))
    }

    @Test
    fun `denies re-roll when roll is not eligible`() {
        val denied = ReRollState().requestReRoll(roll)

        assertThat(denied).isEqualTo(ReRollResult.Denied("Roll is not eligible for re-roll"))
    }
}
