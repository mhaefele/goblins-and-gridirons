package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiFactor
import com.mhaefele.gridiron.api.ai.dto.AiLegalChoiceDto
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import org.springframework.stereotype.Component

/**
 * Policy legal-row int matrix from harness rows — matches Python gym ``legal_moves``.
 *
 * Identity columns: actor, kind, boardTarget, challengeSlot, attachedBonus, face0..2
 * (who-id = roster [AiFactor.Actor], not board coordinate).
 * Metadata tail: [AiFactor.SourceSquare] — current square for from→to encoding only.
 */
@Component
class AiLegalPacker {
    fun legalMoves(choices: List<AiLegalChoiceDto>): List<List<Int>> =
        choices.map { harnessFactorKey(it) }

    companion object {
        fun harnessFactorKey(choice: AiLegalChoiceDto): List<Int> {
            var actor = AiObsLayout.UNUSED_PLAYER_INDEX
            var boardTarget = AiObsLayout.PITCH_SQUARE_COUNT
            var challengeSlot = AiObsLayout.ACTIVE_CHALLENGE_SLOTS
            var attachedBonus = AiObsLayout.ATTACHED_BONUS_UNUSED
            var sourceSquare = AiObsLayout.SOURCE_SQUARE_UNUSED
            val faces = IntArray(AiObsLayout.MAX_HELD_BLOCK_FACES) { AiObsLayout.BLOCK_FACE_PACK_UNUSED }
            for (factor in choice.factors + choice.sugarFactors) {
                when (factor) {
                    is AiFactor.Actor         -> actor = factor.playerIndex
                    is AiFactor.BoardTarget   -> boardTarget = factor.boardIndex
                    is AiFactor.ChallengeSlot -> challengeSlot = factor.slot
                    is AiFactor.AttachedBonus -> attachedBonus = factor.index
                    is AiFactor.SourceSquare  -> sourceSquare = factor.boardIndex
                    is AiFactor.FaceOrder     -> {
                        require(factor.faces.size <= AiObsLayout.MAX_HELD_BLOCK_FACES) { "FaceOrder length ${factor.faces.size} > ${AiObsLayout.MAX_HELD_BLOCK_FACES}" }
                        for (i in factor.faces.indices) {
                            faces[i] = factor.faces[i]
                        }
                    }
                }
            }
            return listOf(
                actor,
                choice.kindIndex,
                boardTarget,
                challengeSlot,
                attachedBonus,
                faces[0],
                faces[1],
                faces[2],
                sourceSquare,
            )
        }
    }
}
