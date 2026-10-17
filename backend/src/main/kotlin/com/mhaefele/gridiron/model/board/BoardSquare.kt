package com.mhaefele.gridiron.model.board

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

sealed class BoardSquare {
    abstract val coordinate: Coordinate
    /** Null when no player occupies this square. */
    abstract val playerId: PlayerId?
    /** Null when no loose ball sits on this square. */
    abstract val looseBallId: BallId?

    /** Impassable terrain on the pitch. */
    open fun isBlocked(): Boolean = false

    open fun isTrapdoor(): Boolean = false

    open fun isEndZone(team: TeamSide): Boolean = false
}

/** Pitch edge — impassable like [BlockedSquare]; movement and bounce stop here. */
data class OutOfBoundsSquare(
    override val coordinate: Coordinate,
) : BoardSquare() {
    override val playerId: PlayerId? = null
    override val looseBallId: BallId? = null

    override fun isBlocked(): Boolean = true
}

data class EndZoneSquare(
    override val coordinate: Coordinate,
    val team: TeamSide,
    override val playerId: PlayerId? = null,
    override val looseBallId: BallId? = null,
) : BoardSquare() {
    override fun isEndZone(team: TeamSide): Boolean = this.team == team
}

data class FieldSquare(
    override val coordinate: Coordinate,
    override val playerId: PlayerId? = null,
    override val looseBallId: BallId? = null,
) : BoardSquare()

/** Impassable terrain — occupants allowed for snapshot state but movement rules forbid entry. */
data class BlockedSquare(
    override val coordinate: Coordinate,
    override val playerId: PlayerId? = null,
    override val looseBallId: BallId? = null,
) : BoardSquare() {
    override fun isBlocked(): Boolean = true
}

data class TrapdoorSquare(
    override val coordinate: Coordinate,
    override val playerId: PlayerId? = null,
    override val looseBallId: BallId? = null,
) : BoardSquare() {
    override fun isTrapdoor(): Boolean = true
}
