package com.mhaefele.gridiron.api.ai.dto.nested

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.internal.AiEnumOrdinal
import com.mhaefele.gridiron.api.ai.dto.internal.AiEnumOrdinalContent
import com.mhaefele.gridiron.api.ai.dto.internal.AiObsEncoding
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.TurnPhase

/**
 * Packable AI observation (match scalars + cards + board).
 *
 * Prefer `null` for absent optionals (enums and ints alike).
 *
 * **AI JSON wire:** packable enums use [@AiEnumOrdinal] / [@AiEnumOrdinalContent]
 * (0-based ordinal numbers, not names). Harness metadata on [com.mhaefele.gridiron.api.ai.dto.AiHarnessStepDto]
 * (`actingSeat`, `resultKind`, …) stay name strings.
 *
 * **Vector pack** ([AiObsEncoding] / [com.mhaefele.gridiron.api.ai.mapping.AiObsPacker]):
 * absent (`null`) → [AiObsLayout.SENTINEL]; present enums → raw `ordinal`; booleans → `0`/`1`.
 * A null [AiCellObs.player] still emits one [SENTINEL] per [AiCellPlayerObs] field — fixed width.
 */
data class AiNestedObsDto(
    val turnNumber: Int,
    /** Wire: 0-based [TurnPhase] ordinal. */
    @get:AiEnumOrdinal
    val phase: TurnPhase,
    /** Actions left in the active coach's budget this turn (0..3). */
    val actionsRemaining: Int,
    /** Score for [com.mhaefele.gridiron.api.ai.dto.AiHarnessStepDto.perspectiveSeat]. */
    val scoreUs: Int,
    /** Score for the opposite seat. */
    val scoreThem: Int,
    /** Touchdowns scored this match (perspective “us”). */
    val touchdownsUs: Int,
    /** Touchdowns scored this match (opponent). */
    val touchdownsThem: Int,
    /** Challenge cards claimed this match (perspective “us”). Clean Sweep is not a claim. */
    val challengeCardsUs: Int,
    /** Challenge cards claimed this match (opponent). */
    val challengeCardsThem: Int,
    /** `null` if Points Victory not armed. */
    val pointsVictoryTurnsRemaining: Int?,
    /**
     * Team Emergency Reserves value for perspective / opponent ([MatchConfig]).
     * Pre-turn free Reserves when dugout count ≥ this value.
     */
    val emergencyReservesUs: Int,
    val emergencyReservesThem: Int,
    val claimsThisTurn: Int,
    /** Opponent bonus-hand size only (contents hidden). */
    val oppHandSize: Int,

    /**
     * Turn-scoped modifiers that can still be true across later AI decision steps.
     * Sprint / Weave / Press are omitted — they attach to the next Run/Mark
     * (hand + tagged legal actions) and clear when that action completes, so they are
     * almost never informative on a post-crank observation.
     */
    val modHoldTheLine: Boolean,
    val modGangUp: Boolean,
    /** True between All-In Tackle grant and the free Block completing. */
    val modAllInTackleProneAfterBlock: Boolean,

    /** Length [AiObsLayout.ACTIVE_CHALLENGE_SLOTS]. */
    val challenges: List<AiChallengeSlotObs>,
    /** Wire: 0-based [ChallengeClaimCondition] ordinal, or JSON null. */
    @get:AiEnumOrdinal
    val deckTopCondition: ChallengeClaimCondition?,
    val deckSize: Int,
    val discardSize: Int,
    /**
     * Own hand only — length [AiObsLayout.BONUS_HAND_SLOTS]; `null` = empty slot.
     * Wire elements: 0-based [BonusPlay] ordinals (or null).
     */
    @get:AiEnumOrdinalContent
    val ownBonusPlay: List<BonusPlay?>,

    /**
     * Held mid-check roll(s) the coach is deciding on (OS / EP / Do-Over / Enforcer).
     * Empty when no dice are held — see [AiHeldCheckObs].
     */
    val heldCheck: AiHeldCheckObs,

    /**
     * Pitch + perspective-relative benches — length [AiObsLayout.BOARD_SLOT_COUNT].
     */
    val cells: List<AiCellObs>,
) {
    init {
        require(challenges.size == AiObsLayout.ACTIVE_CHALLENGE_SLOTS) { "challenges size ${challenges.size} != ${AiObsLayout.ACTIVE_CHALLENGE_SLOTS}" }
        require(ownBonusPlay.size == AiObsLayout.BONUS_HAND_SLOTS) { "ownBonusPlay size ${ownBonusPlay.size} != ${AiObsLayout.BONUS_HAND_SLOTS}" }
        require(cells.size == AiObsLayout.BOARD_SLOT_COUNT) { "cells size ${cells.size} != ${AiObsLayout.BOARD_SLOT_COUNT}" }
    }
}
