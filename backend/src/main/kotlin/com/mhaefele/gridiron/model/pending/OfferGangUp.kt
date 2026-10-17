package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase

/** TURN_START / GANG_UP — Play Gang Up or Decline. */
data class OfferGangUp(
    override val localRespondingCoach: TeamSide,
    val resolved: Boolean = false,
) : PendingDecision(), PendingDecisionSlot.TopLevel {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.GANG_UP_OFFER
    override val phase: TurnPhase = TurnPhase.TURN_START
    override val localAwaitsCoachInput: Boolean get() = !resolved
}
