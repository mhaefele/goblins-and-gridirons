package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.TurnPhase

/**
 * Selecting budgeted action. While an open action is in flight, [delegate] is the
 * *Continuation — root stays RegularPlayerAction until COMPLETE / cancel.
 */
data class RegularPlayerAction(
    override val delegate: ActionContinuation? = null,
) : PendingDecision(), PendingDecisionSlot.TopLevel {
    override val playDecisionType: PlayerDecisionType
        get() = delegate?.playDecisionType ?: PlayerDecisionType.REGULAR_PLAYER_ACTION
    override val phase: TurnPhase = TurnPhase.PLAYER_ACTION
    override val countsAsPlayerAction: Boolean get() = true
}
