package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiFactor
import com.mhaefele.gridiron.api.ai.dto.AiLegalChoiceDto
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import org.springframework.stereotype.Component

/**
 * Policy factor matrix from harness legal rows — matches Python gym ``legal_moves`` / [harnessFactorKey].
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
            for (factor in choice.factors) {
                when (factor) {
                    is AiFactor.Actor         -> actor = factor.playerIndex
                    is AiFactor.BoardTarget   -> boardTarget = factor.boardIndex
                    is AiFactor.ChallengeSlot -> challengeSlot = factor.slot
                    is AiFactor.AttachedBonus -> error("AttachedBonus is sugar — must not appear in free factors")
                }
            }
            return listOf(actor, choice.kindIndex, boardTarget, challengeSlot)
        }
    }
}
