package com.mhaefele.gridiron.game.opponent.onnx.schema15

/** SCHEMA15_DELETE — delete this file with the schema15 package (see Schema15Delete; grep SCHEMA15_DELETE). */

/** Free / sugar factors for schema-15 legal rows (product ONNX only — not wire DTOs). */
sealed interface Schema15Factor {
    data class Actor(val playerIndex: Int) : Schema15Factor {
        init {
            require(playerIndex in 1..Schema15Layout.MAX_PLAYERS_PER_TEAM) { "Actor playerIndex=$playerIndex out of range 1..${Schema15Layout.MAX_PLAYERS_PER_TEAM}" }
        }
    }

    data class BoardTarget(val boardIndex: Int) : Schema15Factor {
        init {
            require(boardIndex in 0 until Schema15Layout.PITCH_SQUARE_COUNT) { "BoardTarget boardIndex=$boardIndex out of pitch range 0..${Schema15Layout.PITCH_SQUARE_COUNT - 1}" }
        }
    }

    data class ChallengeSlot(val slot: Int) : Schema15Factor

    /** Geometry AttachedBonus sugar — not MultiDiscrete-matched. */
    data class AttachedBonus(val index: Int) : Schema15Factor {
        init {
            require(index in 0..4) { "AttachedBonus index=$index out of range 0..4" }
        }
    }
}
