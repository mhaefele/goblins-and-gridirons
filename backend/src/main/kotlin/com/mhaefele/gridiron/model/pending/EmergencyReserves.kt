package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.TurnPhase

/** Pre-turn free reserves placement when dugout size ≥ team Emergency Reserves. */
class EmergencyReserves(val resolved: Boolean = false) : PendingDecision(), PendingDecisionSlot.TopLevel {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.EMERGENCY_RESERVES
    override val phase: TurnPhase = TurnPhase.PRE_TURN
    override val localAwaitsCoachInput: Boolean get() = !resolved
}
