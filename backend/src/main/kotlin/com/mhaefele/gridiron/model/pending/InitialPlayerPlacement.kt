package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TurnPhase

class InitialPlayerPlacement(playerId: PlayerId, val resolved: Boolean = false) :
    PlayerSpecificAction(playerId = playerId, optional = false), PendingDecisionSlot.TopLevel {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.INITIAL_PLAYER_PLACEMENT
    override val phase: TurnPhase = TurnPhase.PIECE_PLACEMENT
    override val localAwaitsCoachInput: Boolean get() = !resolved
}
