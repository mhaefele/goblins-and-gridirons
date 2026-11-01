package com.mhaefele.gridiron.game.opponent.onnx.schema15

/** SCHEMA15_DELETE — delete this file with the schema15 package (see Schema15Delete; grep SCHEMA15_DELETE). */

/** 3-col factor matrix for schema-15 ConditionalFactorPicker / ONNX. */
object Schema15LegalPacker {
    fun legalMoves(choices: List<Schema15LegalChoice>): List<IntArray> =
        choices.map { harnessFactorKey(it).toIntArray() }

    fun harnessFactorKey(choice: Schema15LegalChoice): List<Int> {
        var actor = Schema15Layout.UNUSED_PLAYER_INDEX
        var boardTarget = Schema15Layout.PITCH_SQUARE_COUNT
        var challengeSlot = Schema15Layout.ACTIVE_CHALLENGE_SLOTS
        for (factor in choice.factors) {
            when (factor) {
                is Schema15Factor.Actor         -> actor = factor.playerIndex
                is Schema15Factor.BoardTarget   -> boardTarget = factor.boardIndex
                is Schema15Factor.ChallengeSlot -> challengeSlot = factor.slot
                is Schema15Factor.AttachedBonus -> error("AttachedBonus is sugar — must not appear in free factors")
            }
        }
        val actorKind = actor * Schema15LegalKind.entries.size + choice.kindIndex
        return listOf(actorKind, boardTarget, challengeSlot)
    }
}
