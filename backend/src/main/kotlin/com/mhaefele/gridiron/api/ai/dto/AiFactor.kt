package com.mhaefele.gridiron.api.ai.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Non-pitch decision factors after [AiLegalChoiceDto.kind].
 *
 * Pitch dest / source are [AiLegalChoiceDto.target] / [AiLegalChoiceDto.source] ([AiPitchSquare]),
 * not factors. [AiLegalPacker] writes factors + pitch fields into the legal-row int matrix.
 *
 * **No sentinel / NONE factor.** Omit unused questions from [AiLegalChoiceDto.factors] /
 * [AiLegalChoiceDto.sugarFactors].
 *
 * Wire: `@JsonTypeInfo` `kind` — `actor` / `challengeSlot` / `attachedBonus` / `faceOrder`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = AiFactor.Actor::class, name = "actor"),
    JsonSubTypes.Type(value = AiFactor.ChallengeSlot::class, name = "challengeSlot"),
    JsonSubTypes.Type(value = AiFactor.AttachedBonus::class, name = "attachedBonus"),
    JsonSubTypes.Type(value = AiFactor.FaceOrder::class, name = "faceOrder"),
)
sealed interface AiFactor {
    /**
     * Acting player’s roster index (`1 .. MAX_PLAYERS_PER_TEAM`).
     * Same value as [AiCellPlayerObs.playerIndex] on the square / bench slot that holds them.
     */
    data class Actor(val playerIndex: Int) : AiFactor {
        init {
            require(playerIndex in 1..AiObsLayout.MAX_PLAYERS_PER_TEAM) { "Actor playerIndex=$playerIndex out of range 1..${AiObsLayout.MAX_PLAYERS_PER_TEAM}" }
        }
    }

    /** Active challenge row slot `0 .. ACTIVE_CHALLENGE_SLOTS-1`. */
    data class ChallengeSlot(val slot: Int) : AiFactor

    /**
     * Required geometry bonus (Sprint / Weave / …) on a path or throw action.
     * Packed on the legal row (schema 16+); also rides in sugar for debug / id identity.
     */
    data class AttachedBonus(val index: Int) : AiFactor {
        init {
            require(index in 0 until AiObsLayout.ATTACHED_BONUS_COUNT) { "AttachedBonus index=$index out of range 0..${AiObsLayout.ATTACHED_BONUS_COUNT - 1}" }
        }
    }

    /**
     * Enforcer face resolve order — pack codes `1..faceCount` (ordinal+1), length 1..3.
     */
    data class FaceOrder(val faces: List<Int>) : AiFactor {
        init {
            require(faces.isNotEmpty()) { "FaceOrder must include at least one face" }
            require(faces.size <= AiObsLayout.MAX_HELD_BLOCK_FACES) { "FaceOrder length ${faces.size} > ${AiObsLayout.MAX_HELD_BLOCK_FACES}" }
            for (code in faces) {
                require(code in 1 until AiObsLayout.BLOCK_FACE_PACK_SIZE) { "FaceOrder code=$code out of range 1..${AiObsLayout.BLOCK_FACE_PACK_SIZE - 1}" }
            }
        }
    }
}
