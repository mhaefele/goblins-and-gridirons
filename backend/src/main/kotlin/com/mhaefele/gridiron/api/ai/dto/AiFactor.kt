package com.mhaefele.gridiron.api.ai.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * One decision factor after [AiLegalChoiceDto.kind], in **question order** for that kind.
 *
 * **No sentinel / NONE factor.** If a kind does not ask for a board target (or actor, slot, …),
 * that factor is simply **omitted** from [AiLegalChoiceDto.factors].
 *
 * Schema 15: [AiLegalChoiceDto.factors] lists **free choices only**. Implied / pending-fixed
 * values and geometry [AttachedBonus] go in [AiLegalChoiceDto.sugarFactors] (not
 * MultiDiscrete-matched). The harness packs free [Actor] + [kindIndex] into one joint
 * MultiDiscrete head (`0` actor = kind-only rows).
 *
 * **Index spaces differ by factor:**
 * - [Actor] — roster [playerIndex] `1 .. MAX_PLAYERS_PER_TEAM` (same id on [AiCellPlayerObs])
 * - [BoardTarget] — **pitch only** (`0 .. PITCH_SQUARE_COUNT-1`); dugouts are never targets
 *
 * Training packs free factors into a fixed MultiDiscrete with unused-axis pads in the harness.
 *
 * Wire: `@JsonTypeInfo` `kind` — `actor` / `boardTarget` / `challengeSlot` / `attachedBonus`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = AiFactor.Actor::class, name = "actor"),
    JsonSubTypes.Type(value = AiFactor.BoardTarget::class, name = "boardTarget"),
    JsonSubTypes.Type(value = AiFactor.ChallengeSlot::class, name = "challengeSlot"),
    JsonSubTypes.Type(value = AiFactor.AttachedBonus::class, name = "attachedBonus"),
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
     * Sugar only (schema 15+) — carried for debug / id identity; not MultiDiscrete-matched.
     * When two cards unlock the same free key, the AI mapper keeps the lower index.
     */
    data class AttachedBonus(val index: Int) : AiFactor {
        init {
            require(index in 0..4) { "AttachedBonus index=$index out of range 0..4" }
        }
    }
}
