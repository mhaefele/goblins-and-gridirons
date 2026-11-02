package com.mhaefele.gridiron.api.ai.dto

/**
 * Perspective-relative **0-based** playable pitch square for AI pack / factors.
 *
 * Distinct from rulebook [com.mhaefele.gridiron.shared.Coordinate] (`1..W` / `1..H` playable).
 * Produced by [AiObsLayout.toPerspectivePitchXy0].
 */
data class AiPitchSquare(val x: Int, val y: Int) {
    init {
        require(x in 0 until AiObsLayout.PLAY_WIDTH) { "AiPitchSquare x=$x out of range 0..${AiObsLayout.PLAY_WIDTH - 1}" }
        require(y in 0 until AiObsLayout.PLAY_HEIGHT) { "AiPitchSquare y=$y out of range 0..${AiObsLayout.PLAY_HEIGHT - 1}" }
    }
}
