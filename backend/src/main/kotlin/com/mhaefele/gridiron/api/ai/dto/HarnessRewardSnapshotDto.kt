package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.shared.Coordinate

/**
 * Harness-only reward / metrics snapshot — JSON on the wire, never concatenated into policy [obs].
 *
 * Board facts: [players] (who / where) + [ballCoordinate] (where the ball is). Legal rows stay
 * [LegalAction] + curriculum flags — join by `playerId` / absolute coords.
 */
data class HarnessRewardSnapshotDto(
    val turnNumber: Int,
    val scoreUs: Int,
    val scoreThem: Int,
    val touchdownsUs: Int,
    val touchdownsThem: Int,
    val challengeCardsUs: Int,
    val challengeCardsThem: Int,
    /** Every roster player still in the match (pitch + dugout). */
    val players: List<HarnessPlayerDto>,
    /**
     * Absolute square of the **one** in-play ball (held or loose). Null when no ball is
     * on a playable square (e.g. removed after a TD). Mapper fails if multiple on-pitch
     * ball locations exist. Carrier = player whose [HarnessPlayerDto.coordinate] matches.
     */
    val ballCoordinate: Coordinate? = null,
) {
    init {
        require(players.size <= AiObsLayout.MAX_PLAYERS_PER_TEAM * 2) { "players size ${players.size} exceeds 2×${AiObsLayout.MAX_PLAYERS_PER_TEAM}" }
        val ids = players.map { it.playerId }.toSet()
        require(ids.size == players.size) { "duplicate playerId in harness players" }
    }
}

/**
 * One harness player — absolute geometry + perspective [teamRel].
 *
 * [coordinate] null = dugout. Ball possession is [HarnessRewardSnapshotDto.ballCoordinate], not here.
 */
data class HarnessPlayerDto(
    val playerId: String,
    val playerIndex: Int,
    val teamRel: AiTeamRel,
    val coordinate: Coordinate?,
) {
    init {
        require(playerId.isNotBlank()) { "playerId blank" }
        require(playerIndex in 1..AiObsLayout.MAX_PLAYERS_PER_TEAM) { "playerIndex=$playerIndex out of range 1..${AiObsLayout.MAX_PLAYERS_PER_TEAM}" }
    }
}
