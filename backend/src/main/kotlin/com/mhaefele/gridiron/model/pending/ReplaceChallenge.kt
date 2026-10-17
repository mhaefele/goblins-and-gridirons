package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.TurnPhase

class ReplaceChallenge(val resolved: Boolean = false) : PendingDecision(), PendingDecisionSlot.TopLevel {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.REPLACE_CHALLENGE
    override val phase: TurnPhase = TurnPhase.PRE_TURN
    override val localAwaitsCoachInput: Boolean get() = !resolved
}
