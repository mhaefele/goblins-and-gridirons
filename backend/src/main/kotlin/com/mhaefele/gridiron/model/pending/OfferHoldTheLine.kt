package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase

/** PRE_TURN / BONUS_CARDS — Play Hold the Line or Decline. */
data class OfferHoldTheLine(
    override val localRespondingCoach: TeamSide,
    val resolved: Boolean = false,
) : PendingDecision(), PendingDecisionSlot.TopLevel {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.HOLD_THE_LINE_OFFER
    override val phase: TurnPhase = TurnPhase.PRE_TURN
    override val localAwaitsCoachInput: Boolean get() = !resolved
}
