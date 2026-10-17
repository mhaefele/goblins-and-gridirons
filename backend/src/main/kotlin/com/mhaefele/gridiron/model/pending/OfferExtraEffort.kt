package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase

/**
 * PLAYER_ACTION / OFFER_EXTRA_EFFORT — Play Extra Effort or Decline (decline ends the turn).
 * Accept parks a selecting [RegularPlayerAction] on [delegate].
 */
data class OfferExtraEffort(
    override val localRespondingCoach: TeamSide,
    val resolved: Boolean = false,
    override val delegate: RegularPlayerAction? = null,
) : PendingDecision(), PendingDecisionSlot.TopLevel {
    override val playDecisionType: PlayerDecisionType
        get() = delegate?.playDecisionType ?: PlayerDecisionType.EXTRA_EFFORT_OFFER
    override val phase: TurnPhase = TurnPhase.PLAYER_ACTION
    override val localAwaitsCoachInput: Boolean get() = !resolved
}
