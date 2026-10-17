package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.TurnPhase

/** Open player-action FSM. Nested-only — park under RegularPlayerAction.delegate. */
sealed class ActionContinuation : PendingDecision(), PendingDecisionSlot.NestedOnly {
    override val phase: TurnPhase = TurnPhase.PLAYER_ACTION
    override fun awaitsOpenActionComplete(): Boolean = isFinished()
}
