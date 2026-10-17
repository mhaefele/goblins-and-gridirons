package com.mhaefele.gridiron.game.action.dto

import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.scenario.formatDomainEvent
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerActionStep
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DomainEventStampTest {

    @Test
    fun `turnPhase and turnStep are ignored by data-class equals`() {
        val a = DomainEvent.ActionStarted(PlayerId("h0"), Coordinate(1, 1), ActionType.MARK)
        val b = DomainEvent.ActionStarted(PlayerId("h0"), Coordinate(1, 1), ActionType.MARK)
        a.turnPhase = TurnPhase.PLAYER_ACTION
        a.turnStep = PlayerActionStep.IN_ACTION
        b.turnPhase = TurnPhase.PRE_TURN
        b.turnStep = null

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a.turnPhase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(a.turnStep).isEqualTo(PlayerActionStep.IN_ACTION)
    }

    @Test
    fun `MatchMutator stamps turn clock on record`() {
        val scenario = GameScenario.board()
        val home = scenario.addPlayer(5, 6)
        val base = matchState(board = scenario.board, phase = TurnPhase.PLAYER_ACTION)
        val session = MatchMutator(
            base.copy(
                currentTurnState = base.currentTurnState.copy(step = PlayerActionStep.IN_ACTION),
            ),
        )
        session.board.actionStarted(home.player.id, ActionType.MARK)

        val event = session.events.single() as DomainEvent.ActionStarted
        assertThat(event.turnPhase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(event.turnStep).isEqualTo(PlayerActionStep.IN_ACTION)
        assertThat(formatDomainEvent(event)).contains("[PLAYER_ACTION·IN_ACTION]")
    }
}
