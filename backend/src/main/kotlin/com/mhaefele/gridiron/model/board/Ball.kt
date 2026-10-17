package com.mhaefele.gridiron.model.board

import com.mhaefele.gridiron.shared.DomainId
import com.mhaefele.gridiron.shared.requireDomainId

data class BallId(override val value: String) : DomainId {
    init {
        requireDomainId(value)
    }
}

/**
 * A ball in play or removed after a touchdown.
 *
 * **Placement lives on [Board]:** loose balls on [BoardSquare.looseBallId]; held balls on
 * [Player.heldBallId]. A square may temporarily have both a player and a loose ball; the ball
 * then bounces or is picked up in the next resolution beat.
 */
data class Ball(
    val id: BallId,
)
