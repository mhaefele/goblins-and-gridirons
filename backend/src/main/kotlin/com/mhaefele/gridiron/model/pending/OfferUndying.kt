package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TurnPhase

/**
 * Pre-turn Undying offer (PRE_TURN / PLAYER_ABILITIES).
 * [remainingPlayerIds] queues further eligible Undying players; pending stays until that list drains.
 */
class OfferUndying(
    playerId: PlayerId,
    val remainingPlayerIds: List<PlayerId> = emptyList(),
    val resolved: Boolean = false,
) : PlayerSpecificAction(playerId, optional = true), PendingDecisionSlot.TopLevel {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.UNDYING_OFFER
    override val phase: TurnPhase = TurnPhase.PRE_TURN
    override val localAwaitsCoachInput: Boolean get() = !resolved
}
