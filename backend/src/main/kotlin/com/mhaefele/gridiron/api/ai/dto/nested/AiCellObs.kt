package com.mhaefele.gridiron.api.ai.dto.nested

import com.mhaefele.gridiron.api.ai.dto.internal.AiEnumOrdinal
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
 * When the cell’s `player` is `null`, emit the same number of values as this type’s fields, all
 * [AiObsLayout.SENTINEL].
 *
 * [move] / [throwStat] / [armour] are packed ints (domain types are sealed — [AiObsLayout]).
 * Always real values while this object exists; empty slot ⇒ [AiCellObs.player] is null (SENTINEL pack).
 */
data class AiCellPlayerObs(
    /**
     * Stable roster id for this occupant (`1 .. MAX_PLAYERS_PER_TEAM`).
     * Same value as the packed legal-row actor when this player is the actor.
     * Empty cells pack [AiObsLayout.SENTINEL].
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
 * Append in declaration order: [terrain], [hasBall], [player] (expanded or absent), then
 * [pitchX], [pitchY], [distToBall].
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
    /**
     * Perspective pitch column (`0 .. PLAY_WIDTH-1`). Bench slots use [AiObsLayout.SENTINEL].
     */
    val pitchX: Int = AiObsLayout.SENTINEL,
    /**
     * Perspective pitch row (`0 .. PLAY_HEIGHT-1`). Bench slots use [AiObsLayout.SENTINEL].
     */
    val pitchY: Int = AiObsLayout.SENTINEL,
    /**
     * Chebyshev steps to the single on-pitch ball (`0 .. max(H,W)`).
     * [AiObsLayout.SENTINEL] when no ball is on a playable square (and on benches).
     */
    val distToBall: Int = AiObsLayout.SENTINEL,
) {
    init {
        if (pitchX != AiObsLayout.SENTINEL) {
            require(pitchX in 0 until AiObsLayout.PLAY_WIDTH) { "pitchX=$pitchX out of range" }
        }
        if (pitchY != AiObsLayout.SENTINEL) {
            require(pitchY in 0 until AiObsLayout.PLAY_HEIGHT) { "pitchY=$pitchY out of range" }
        }
        val maxDist = maxOf(AiObsLayout.PLAY_HEIGHT, AiObsLayout.PLAY_WIDTH)
        require(distToBall == AiObsLayout.SENTINEL || distToBall in 0..maxDist) { "distToBall=$distToBall out of range" }
    }

    companion object {
        fun emptyPitch(
            terrain: AiTerrain,
            pitchX: Int = AiObsLayout.SENTINEL,
            pitchY: Int = AiObsLayout.SENTINEL,
            distToBall: Int = AiObsLayout.SENTINEL,
        ): AiCellObs =
            AiCellObs(
                terrain = terrain,
                hasBall = false,
                player = null,
                pitchX = pitchX,
                pitchY = pitchY,
                distToBall = distToBall,
            )

        fun emptyBench(): AiCellObs = emptyPitch(AiTerrain.BENCH)
    }
}

/** One public active-challenge slot. */
data class AiChallengeSlotObs(
    /** Wire: 0-based [ChallengeClaimCondition] ordinal, or JSON null. */
    @get:AiEnumOrdinal
    val condition: ChallengeClaimCondition?,
    /** Points when [condition] is set; [AiObsLayout.SENTINEL] if empty. */
    val points: Int,
)
