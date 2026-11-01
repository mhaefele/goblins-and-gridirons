package com.mhaefele.gridiron.api.ai.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * One decision factor after [AiLegalChoiceDto.kind], in **question order** for that kind.
 *
 * **No sentinel / NONE factor.** If a kind does not ask for a board target (or actor, slot, …),
 * that factor is simply **omitted** from [AiLegalChoiceDto.factors].
 *
 * Schema 16: free choices in [AiLegalChoiceDto.factors]; pending-fixed / debug extras in
 * [AiLegalChoiceDto.sugarFactors]. [AiLegalPacker] writes both into the legal-row int matrix
 * (actor, kind, target, challenge, attachedBonus, face0..2) for join-score encoding.
 *
 * **Index spaces differ by factor:**
 * - [Actor] — roster [playerIndex] `1 .. MAX_PLAYERS_PER_TEAM` (same id on [AiCellPlayerObs])
 * - [BoardTarget] — **pitch only** (`0 .. PITCH_SQUARE_COUNT-1`); dugouts are never targets
 * - [FaceOrder] — pack codes `1..BlockDieFace.entries` (ordinal+1); length ≤ 3
 *
 * Wire: `@JsonTypeInfo` `kind` — `actor` / `boardTarget` / `challengeSlot` / `attachedBonus` / `faceOrder`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = AiFactor.Actor::class, name = "actor"),
    JsonSubTypes.Type(value = AiFactor.BoardTarget::class, name = "boardTarget"),
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

    /** Destination / path end / block target / reserves placement / … — pitch only. */
    data class BoardTarget(val boardIndex: Int) : AiFactor {
        init {
            require(boardIndex in 0 until AiObsLayout.PITCH_SQUARE_COUNT) { "BoardTarget boardIndex=$boardIndex out of pitch range 0..${AiObsLayout.PITCH_SQUARE_COUNT - 1}" }
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
