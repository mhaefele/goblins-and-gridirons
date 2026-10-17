package com.mhaefele.gridiron.model.board

import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.model.catalog.canHoldBall

/**
 * Persisted player status on the pitch or in the dugout.
 *
 * **Placement lives on [Board]:** pitch squares hold [BoardSquare.playerId]; dugout players
 * are listed on that team's [ReservesArea]. This type carries team, status, and held ball
 * only — not coordinates.
 *
 * **Open/Marked is not stored** — standing players are always [STANDING]; adjacency
 * derivation lives in Phase 2 (`standingStatus` / marking queries).
 */
enum class PlayerStatus {
    STANDING,
    PRONE,
    RESERVES,
}

/**
 * A player token — attributes without grid position.
 */
data class Player(
    val id: PlayerId,
    val team: TeamSide,
    val profile: PositionProfile,
    val status: PlayerStatus,
    /** Null when this player is not carrying a ball. */
    val heldBallId: BallId?,
) {
    init {
        require(profile.stats.throwStat.canHoldBall() || heldBallId == null) { "Unthrowable players cannot hold the ball.  This:  $this" }
    }

    fun holdsBall() = heldBallId != null
}
