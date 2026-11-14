package com.mhaefele.gridiron.game.opponent.onnx.schema15

/** SCHEMA15_DELETE — delete this file with the schema15 package (see Schema15Delete; grep SCHEMA15_DELETE). */

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout

/**
 * Frozen MultiDiscrete / factor-logit layout for classpath ONNX schemaVersion=15.
 *
 * Delete this whole `schema15` package once all opponents are re-exported at schema 16+.
 * Kind ordinals and head sizes must stay byte-stable — do not reorder [Schema15LegalKind].
 */
object Schema15Layout {
    const val SCHEMA_VERSION = 15

    const val PITCH_SQUARE_COUNT = AiObsLayout.PITCH_SQUARE_COUNT
    const val MAX_PLAYERS_PER_TEAM = AiObsLayout.MAX_PLAYERS_PER_TEAM
    const val UNUSED_PLAYER_INDEX = AiObsLayout.SENTINEL
    const val ACTIVE_CHALLENGE_SLOTS = AiObsLayout.ACTIVE_CHALLENGE_SLOTS

    /** Packed legal-row width: actor×kind, boardTarget, challengeSlot. */
    const val ACTION_FACTOR_COUNT = 3

    val ACTOR_KIND_HEAD_SIZE: Int = (MAX_PLAYERS_PER_TEAM + 1) * Schema15LegalKind.entries.size
    val BOARD_TARGET_HEAD_SIZE: Int = PITCH_SQUARE_COUNT + 1
    val CHALLENGE_SLOT_HEAD_SIZE: Int = ACTIVE_CHALLENGE_SLOTS + 1
    val ACTION_HEAD_SIZES: IntArray = intArrayOf(ACTOR_KIND_HEAD_SIZE, BOARD_TARGET_HEAD_SIZE, CHALLENGE_SLOT_HEAD_SIZE)
    val ACTION_LOGIT_WIDTH: Int = ACTION_HEAD_SIZES.sum()

    const val FACTOR_ACTOR_KIND = 0
    const val FACTOR_BOARD_TARGET = 1
    const val FACTOR_CHALLENGE_SLOT = 2
    val FACTOR_SAMPLE_ORDER: IntArray = intArrayOf(FACTOR_ACTOR_KIND, FACTOR_BOARD_TARGET, FACTOR_CHALLENGE_SLOT)

    /**
     * Same float-obs length as live [AiObsLayout] (schema 15→16 did not change the vector).
     * Product ONNX reuses live [com.mhaefele.gridiron.api.ai.mapping.AiObsPacker].
     */
    const val OBSERVATION_VECTOR_LENGTH = AiObsLayout.OBSERVATION_VECTOR_LENGTH

    init {
        check(OBSERVATION_VECTOR_LENGTH == AiObsLayout.OBSERVATION_VECTOR_LENGTH)
        check(ACTOR_KIND_HEAD_SIZE == 374) { "schema-15 actor×kind head must stay 374 (was $ACTOR_KIND_HEAD_SIZE)" }
        check(ACTION_LOGIT_WIDTH == 544) { "schema-15 logit width must stay 544 (was $ACTION_LOGIT_WIDTH)" }
    }
}
