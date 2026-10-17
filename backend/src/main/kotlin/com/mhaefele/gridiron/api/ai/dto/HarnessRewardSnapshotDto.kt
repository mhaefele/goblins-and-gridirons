package com.mhaefele.gridiron.api.ai.dto

/**
 * Harness-only reward / metrics snapshot — JSON on the wire, never concatenated into policy [obs].
 *
 * Deliberately smaller than [AiNestedObsDto]: only what Python ``reward.py`` / metrics / eval_debug read.
 */
data class HarnessRewardSnapshotDto(
    val turnNumber: Int,
    val scoreUs: Int,
    val scoreThem: Int,
    val touchdownsUs: Int,
    val touchdownsThem: Int,
    val challengeCardsUs: Int,
    val challengeCardsThem: Int,
    /** Pitch + bench slots — ball location and occupant identity only. */
    val cells: List<HarnessRewardCellDto>,
) {
    init {
        require(cells.size == AiObsLayout.BOARD_SLOT_COUNT) { "cells size ${cells.size} != ${AiObsLayout.BOARD_SLOT_COUNT}" }
    }
}

/** One board slot for harness ball / dugout / carrier helpers — not a policy feature slice. */
data class HarnessRewardCellDto(
    val hasBall: Boolean = false,
    val player: HarnessRewardPlayerDto? = null,
)

/** Occupant identity when [HarnessRewardCellDto.player] is non-null. */
data class HarnessRewardPlayerDto(
    val playerIndex: Int,
    val teamRel: AiTeamRel,
) {
    init {
        require(playerIndex in 1..AiObsLayout.MAX_PLAYERS_PER_TEAM) { "playerIndex=$playerIndex out of range 1..${AiObsLayout.MAX_PLAYERS_PER_TEAM}" }
    }
}
