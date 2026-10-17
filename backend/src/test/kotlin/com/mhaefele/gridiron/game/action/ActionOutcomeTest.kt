package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.api.ActionOutcome
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ActionOutcomeTest {
    @Test
    fun `outcome carries immutable new state`() {
        val state = matchState()
        val outcome = ActionOutcome(newState = state.copy(id = MatchId("next")))

        assertThat(outcome.newState.id).isEqualTo(MatchId("next"))
        assertThat(outcome.events).isEmpty()
        assertThat(outcome.events).isEmpty()
    }
}
