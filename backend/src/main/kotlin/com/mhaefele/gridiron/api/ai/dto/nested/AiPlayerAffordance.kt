package com.mhaefele.gridiron.api.ai.dto.nested

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout

/**
 * Per-player highlights derived from the current legal menu (not multi-turn planning).
 * Nested on [AiCellPlayerObs]; vector pack expands [AiPlayerAffordance] fields inline after stats.
 *
 * Reach bbox uses perspective pitch coordinates (`1..PLAY_WIDTH` / `1..PLAY_HEIGHT`).
 * [AiObsLayout.SENTINEL] when the player has no run/sidestep/mark/reserves destinations.
 */
data class AiPlayerAffordance(
    val canScoreTouchdown: Boolean = false,
    /** At least one [com.mhaefele.gridiron.api.dto.response.ClaimCertainty.POTENTIAL] claim on a legal. */
    val mightClaimChallenge: Boolean = false,
    /** At least one [com.mhaefele.gridiron.api.dto.response.ClaimCertainty.CERTAIN] claim on a legal. */
    val certainClaimChallenge: Boolean = false,
    /** A run/sidestep/mark/reserves legal ends on the loose-ball square (pickup or bounce). */
    val canReachBallSquare: Boolean = false,
    /** A legal is annotated [com.mhaefele.gridiron.api.dto.response.BallEncounter.PICK_UP]. */
    val canPickupBall: Boolean = false,
    val reachMinX: Int = AiObsLayout.SENTINEL,
    val reachMaxX: Int = AiObsLayout.SENTINEL,
    val reachMinY: Int = AiObsLayout.SENTINEL,
    val reachMaxY: Int = AiObsLayout.SENTINEL,
) {
    companion object {
        val NONE = AiPlayerAffordance()
    }
}
