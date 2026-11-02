package com.mhaefele.gridiron.api.ai.dto

/**
 * Perspective-relative **0-based** playable pitch square for packed legal-row columns.
 *
 * Distinct from rulebook [com.mhaefele.gridiron.shared.Coordinate] (`1..W` / `1..H` playable).
 * Harness [com.mhaefele.gridiron.api.dto.response.LegalAction] keeps absolute coords;
 * [AiObsLayout.toPerspectivePitchXy0] is pack-private.
 */
data class AiPitchSquare(val x: Int, val y: Int) {
    init {
        require(x in 0 until AiObsLayout.PLAY_WIDTH) { "AiPitchSquare x=$x out of range 0..${AiObsLayout.PLAY_WIDTH - 1}" }
        require(y in 0 until AiObsLayout.PLAY_HEIGHT) { "AiPitchSquare y=$y out of range 0..${AiObsLayout.PLAY_HEIGHT - 1}" }
    }
}
