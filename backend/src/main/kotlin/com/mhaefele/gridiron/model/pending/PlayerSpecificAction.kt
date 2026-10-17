package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.PlayerId

sealed class PlayerSpecificAction(val playerId: PlayerId, val optional: Boolean) : PendingDecision() {
    override val localPromptPlayerId: PlayerId? get() = playerId
}
