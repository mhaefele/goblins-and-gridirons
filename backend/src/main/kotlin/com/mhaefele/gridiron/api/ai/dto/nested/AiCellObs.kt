package com.mhaefele.gridiron.api.ai.dto.nested

import com.mhaefele.gridiron.api.ai.dto.internal.AiEnumOrdinal
import com.mhaefele.gridiron.api.ai.dto.AiFactor
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.AiTeamRel
import com.mhaefele.gridiron.api.ai.dto.AiTerrain
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.catalog.PlayerAbility

/**
 * Occupant of a board slot — only present when [AiCellObs.player] is non-null.
 *
 * Vectorize in **declaration order**; nested [AiCellPlayerObs.affordance] expands inline after stats.
 * When the cell’s `player` is `null`, emit the same number of values as this type’s fields, all absent:
 * enums → `0`, bools/ints → [AiObsLayout.SENTINEL].
 *
 * [move] / [throwStat] / [armour] are packed ints (domain types are sealed — [AiObsLayout]).
 * Always real values while this object exists; empty slot ⇒ [AiCellObs.player] is null (SENTINEL pack).
 */
data class AiCellPlayerObs(
    /**
     * Stable roster id for this occupant (`1 .. MAX_PLAYERS_PER_TEAM`).
     * Same value as [AiFactor.Actor.playerIndex] when this player is the actor.
     * Empty cells pack [AiObsLayout.UNUSED_PLAYER_INDEX] (0).
     */
    val playerIndex: Int,
    /** Relative to [com.mhaefele.gridiron.api.ai.dto.AiHarnessStepDto.perspectiveSeat]. Wire: 0-based ordinal. */
    @get:AiEnumOrdinal
    val teamRel: AiTeamRel,
    /** Dugout occupants typically [PlayerStatus.RESERVES]. Wire: 0-based ordinal. */
    @get:AiEnumOrdinal
    val status: PlayerStatus,
    val turnEnded: Boolean,
    /** 1..6 fixed Move, or [AiObsLayout.MOVE_RANDOM_D6]. */
    val move: Int,
    val blockDice: Int,
    /** [AiObsLayout.THROW_UNTHROWABLE] or threshold 2..6. */
    val throwStat: Int,
    /** [AiObsLayout.ARMOUR_NONE] or threshold 2..6. */
    val armour: Int,
    /** Wire: 0-based [PlayerAbility] ordinal, or JSON null. */
    @get:AiEnumOrdinal
    val ability: PlayerAbility?,
    val canThrowTeammate: Boolean,
    /** Legal-menu highlights for this occupant — vector pack expands inline after stats. */
    val affordance: AiPlayerAffordance = AiPlayerAffordance.NONE,
) {
    init {
        require(playerIndex in 1..AiObsLayout.MAX_PLAYERS_PER_TEAM) { "playerIndex=$playerIndex out of range 1..${AiObsLayout.MAX_PLAYERS_PER_TEAM}" }
    }
}

/**
 * One board slot (pitch square **or** dugout slot): square facts + optional occupant.
 *
 * Append in declaration order: [terrain], [hasBall], then [player] (expanded or absent codes).
 */
data class AiCellObs(
    /** Wire: 0-based [AiTerrain] ordinal. */
    @get:AiEnumOrdinal
    val terrain: AiTerrain,
    /**
     * True if this square has the ball — loose on turf **or** held by the occupant.
     * Always `false` on bench (reserves cannot hold a ball).
     */
    val hasBall: Boolean,
    /** `null` if the slot is empty — still packs a full [AiCellPlayerObs]-width block of absents. */
    val player: AiCellPlayerObs?,
) {
    companion object {
        fun emptyPitch(terrain: AiTerrain): AiCellObs =
            AiCellObs(terrain = terrain, hasBall = false, player = null)

        fun emptyBench(): AiCellObs = emptyPitch(AiTerrain.BENCH)
    }
}

/** One public active-challenge slot. */
data class AiChallengeSlotObs(
    /** Wire: 0-based [ChallengeClaimCondition] ordinal, or JSON null. */
    @get:AiEnumOrdinal
    val condition: ChallengeClaimCondition?,
    /** Points when [condition] is set; `0` if empty. */
    val points: Int,
)
