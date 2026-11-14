package com.mhaefele.gridiron.api.ai.dto.nested

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout

/**
 * Per-player highlights derived from the current legal menu (not multi-turn planning).
 * Nested on [AiCellPlayerObs] for tests / debug JSON — **not** in the flat obs pack (schema 25).
 * Prefer per-legal-row bits when the join needs “this choice scores / picks up / claims.”
 *
 * Reach bbox uses perspective **0-based** pitch coordinates (`0..PLAY_WIDTH-1` / `0..PLAY_HEIGHT-1`).
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
