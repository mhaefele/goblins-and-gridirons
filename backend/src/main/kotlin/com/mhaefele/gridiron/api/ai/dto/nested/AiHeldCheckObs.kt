package com.mhaefele.gridiron.api.ai.dto.nested

import com.mhaefele.gridiron.api.ai.dto.internal.AiEnumOrdinal
import com.mhaefele.gridiron.api.ai.dto.internal.AiEnumOrdinalContent
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace

/**
 * What the coach is looking at on a held mid-check step (OS / EP / Do-Over / Enforcer).
 *
 * Empty when no dice are held for a decision ([kind] null, [d6] null, [blockFaces] all null).
 * Length of [blockFaces] is always [AiObsLayout.MAX_HELD_BLOCK_FACES].
 */
data class AiHeldCheckObs(
    /** Wire: 0-based [AiHeldCheckKind] ordinal, or JSON null when nothing held. */
    @get:AiEnumOrdinal
    val kind: AiHeldCheckKind?,
    /** Natural D6 `1..6` for throw / armour holds; null when unused. */
    val d6: Int?,
    /**
     * Held block-die faces (OS / Enforcer / block Do-Over), pad with null.
     * Wire elements: 0-based [BlockDieFace] ordinals (or null).
     */
    @get:AiEnumOrdinalContent
    val blockFaces: List<BlockDieFace?>,
) {
    init {
        require(blockFaces.size == AiObsLayout.MAX_HELD_BLOCK_FACES) { "blockFaces size ${blockFaces.size} != ${AiObsLayout.MAX_HELD_BLOCK_FACES}" }
        when (kind) {
            null                                                -> {
                require(d6 == null) { "empty heldCheck must not set d6" }
                require(blockFaces.all { it == null }) { "empty heldCheck must pad blockFaces with null" }
            }
            AiHeldCheckKind.BLOCK_FACES                         -> {
                require(d6 == null) { "BLOCK_FACES must not set d6" }
                require(blockFaces.any { it != null }) { "BLOCK_FACES needs at least one face" }
            }
            AiHeldCheckKind.THROW_D6, AiHeldCheckKind.ARMOUR_D6 -> {
                require(d6 in 1..6) { "held D6 must be 1..6, was $d6" }
                require(blockFaces.all { it == null }) { "$kind must not set blockFaces" }
            }
        }
    }

    companion object {
        fun empty(): AiHeldCheckObs =
            AiHeldCheckObs(
                kind = null,
                d6 = null,
                blockFaces = List(AiObsLayout.MAX_HELD_BLOCK_FACES) { null },
            )

        fun blockFaces(faces: List<BlockDieFace>): AiHeldCheckObs {
            require(faces.isNotEmpty()) { "blockFaces needs ≥1 face" }
            require(faces.size <= AiObsLayout.MAX_HELD_BLOCK_FACES) { "blockFaces size ${faces.size} > ${AiObsLayout.MAX_HELD_BLOCK_FACES}" }
            return AiHeldCheckObs(
                kind = AiHeldCheckKind.BLOCK_FACES,
                d6 = null,
                blockFaces = List(AiObsLayout.MAX_HELD_BLOCK_FACES) { i -> faces.getOrNull(i) },
            )
        }

        fun throwD6(naturalRoll: Int): AiHeldCheckObs =
            AiHeldCheckObs(
                kind = AiHeldCheckKind.THROW_D6,
                d6 = naturalRoll,
                blockFaces = List(AiObsLayout.MAX_HELD_BLOCK_FACES) { null },
            )

        fun armourD6(naturalRoll: Int): AiHeldCheckObs =
            AiHeldCheckObs(
                kind = AiHeldCheckKind.ARMOUR_D6,
                d6 = naturalRoll,
                blockFaces = List(AiObsLayout.MAX_HELD_BLOCK_FACES) { null },
            )
    }
}

/** Discriminator for [AiHeldCheckObs] — which held roll the coach is deciding on. */
enum class AiHeldCheckKind {
    /** Block dice pool (OS keep/reroll, Enforcer order, block Do-Over). */
    BLOCK_FACES,
    /** Throw check D6 (Pinpoint Passer / throw Do-Over). */
    THROW_D6,
    /** Armour check D6 (armour Do-Over). */
    ARMOUR_D6,
}
