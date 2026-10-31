package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.shared.TeamSide

/**
 * Fixed board sizes and non-enum pack codes for [AiNestedObsDto] / [AiGameStateMapper] (schema v1).
 *
 * ## Enums on the DTO
 *
 * Prefer domain / AI enums and optional ints on the intermediate DTO (`null` = absent).
 * When flattening to a numeric vector, use [com.mhaefele.gridiron.api.ai.dto.internal.AiObsEncoding].
 *
 * ## Board indices (pitch + benches)
 *
 * | Index range | Meaning |
 * |-------------|---------|
 * | `0 .. [PITCH_SQUARE_COUNT]-1` (165) | Playable pitch (perspective-relative when [perspectiveSeat] is AWAY) |
 * | `[HOME_BENCH_START] .. +9` (165..174) | Perspective “us” dugout slots |
 * | `[AWAY_BENCH_START] .. +9` (175..184) | Perspective “them” dugout slots |
 *
 * Pitch: `index = (y - 1) * [PLAY_WIDTH] + (x - 1)` with `x ∈ [1, W]`, `y ∈ [1, H]`.
 * Obs [AiNestedObsDto.cells] use the full board space. [AiFactor.Actor] is a **roster player index**
 * (same id on the cell that holds them). [AiFactor.BoardTarget] is **pitch only**.
 * Pathfinding / adjacency use pitch only.
 */
object AiObsLayout {
    /**
     * Learning-contract version (Kotlin DTO **and** Python gym / model packing).
     * Bump here and in `ai/gridiron_ai/constants.py` together when DTO fields, gym Dict
     * keys/shapes, or action-head sizes change — not only when a JSON field is added.
     * v6: [AiCellObs.hasBall] replaced separate looseBall / holdsBall flags.
     * v7: gym Dict splits cells into pitch `(C,H,W)` + benches table (same 0..184 index space).
     * v8: drop play-bonus `HandSlot` (identity = kind); `BoardTarget` head is pitch-only.
     * v9: free factors only + sugarFactors; declineOffer / reroll / adjustByDoOver merges;
     * drop Reroll factor head; omit CancelThrowTeammate from AI legals.
     * v10: Actor = roster playerIndex (1..MAX); same id on cell occupant; joint actor×kind
     * MultiDiscrete head in the harness; 0 = no-actor kinds.
     * v11: [AiHeldCheckObs] — held block faces / throw D6 / armour D6 for mid-check decisions.
     * v12: gym ``preferred_legal`` row flags for soft-mask sampling (DTO wire unchanged).
     * v13: per-player [AiPlayerAffordance] on [AiCellPlayerObs] (TD / claims / ball reach / reach bbox).
     * v14: training episode end + [AiHarnessStepDto.trainingOutcome] ([AiTrainingOutcome] drill grade).
     * v15: drop faceOrder MultiDiscrete head; AttachedBonus → sugar; omit chooseBlockFaceOrder
     * from AI kinds; add playSprint (RandomD6 Sprint mid-check).
     * v16: gym action = masked index into this step's legal rows (join-score policy);
     * legal-row pack = actor, kind, boardTarget, challengeSlot (separate actor/kind);
     * ONNX emits one score per padded legal-row slot.
     */
    const val SCHEMA_VERSION = 16

    const val PLAY_WIDTH = 11
    const val PLAY_HEIGHT = 15
    const val PITCH_SQUARE_COUNT = PLAY_WIDTH * PLAY_HEIGHT // 165

    /** Catalog max roster size per team — actor / cell playerIndex upper bound. */
    const val MAX_PLAYERS_PER_TEAM = 10
    const val BENCH_SLOTS = MAX_PLAYERS_PER_TEAM
    const val HOME_BENCH_START = PITCH_SQUARE_COUNT // 165
    const val AWAY_BENCH_START = HOME_BENCH_START + BENCH_SLOTS // 175
    const val BOARD_SLOT_COUNT = PITCH_SQUARE_COUNT + 2 * BENCH_SLOTS // 185

    /**
     * Wire / MultiDiscrete actor pad: no free actor on this kind.
     * Occupied cells use `1 .. MAX_PLAYERS_PER_TEAM` (roster slot + 1).
     */
    const val UNUSED_PLAYER_INDEX = 0

    /** Cap on block-die faces in one pool (Underdog / assisted max). */
    const val MAX_HELD_BLOCK_FACES = 3
    /** Pack width: kind + d6 + [MAX_HELD_BLOCK_FACES] face slots. */
    const val HELD_CHECK_FIELD_COUNT = 1 + 1 + MAX_HELD_BLOCK_FACES

    /** Packed legal-row width: actor, kind, boardTarget, challengeSlot. */
    const val ACTION_FACTOR_COUNT = 4

    const val ACTIVE_CHALLENGE_SLOTS = 3

    /** Free actor playerIndex pad — `0` unused, `1..MAX_PLAYERS_PER_TEAM` roster. */
    val ACTOR_HEAD_SIZE: Int = MAX_PLAYERS_PER_TEAM + 1
    /** [AiLegalKind.entries] — packed as ordinal on each legal row. */
    val KIND_HEAD_SIZE: Int = AiLegalKind.entries.size
    /** Pitch squares + pad (unused axis). */
    val BOARD_TARGET_HEAD_SIZE: Int = PITCH_SQUARE_COUNT + 1
    /** Challenge slots + pad (unused axis). */
    val CHALLENGE_SLOT_HEAD_SIZE: Int = ACTIVE_CHALLENGE_SLOTS + 1
    /** Factor widths packed on each legal row — must match `ai/gridiron_ai/constants.py` ACTION_HEAD_SIZES. */
    val ACTION_HEAD_SIZES: IntArray = intArrayOf(ACTOR_HEAD_SIZE, KIND_HEAD_SIZE, BOARD_TARGET_HEAD_SIZE, CHALLENGE_SLOT_HEAD_SIZE)
    /** Pad width for the legal-row menu (Python ``MAX_LEGAL_MOVES``). */
    const val MAX_LEGAL_MOVES = 1024
    /** ONNX / policy output width — one score per padded legal-row slot. */
    const val LEGAL_ROW_SCORE_WIDTH: Int = MAX_LEGAL_MOVES

    const val FACTOR_ACTOR = 0
    const val FACTOR_KIND = 1
    const val FACTOR_BOARD_TARGET = 2
    const val FACTOR_CHALLENGE_SLOT = 3

    /**
     * Mid-turn hand capacity for obs: end-of-turn trim keeps 3, Clean Sweep allows 3 claims in one
     * turn → up to 6 before trim. Pad unused slots with null in obs. Play-bonus legal rows use
     * [AiLegalKind], not a hand-slot factor.
     */
    const val BONUS_HAND_SLOTS = 6
    /** Match scalars on [AiNestedObsDto] before challenges / deck / hand / heldCheck / cells. */
    const val SCALAR_COUNT = 17
    const val DECK_FIELD_COUNT = 3
    /** terrain + hasBall + 19 player fields (stats + affordances + reach bbox). */
    const val NUMBERS_PER_BOARD_CELL = 21

    /**
     * Packed learning obs length (declaration-order concat on [AiNestedObsDto]).
     * Must match `ai/gridiron_ai/constants.py` `OBSERVATION_VECTOR_LENGTH`.
     */
    const val OBSERVATION_VECTOR_LENGTH = SCALAR_COUNT + ACTIVE_CHALLENGE_SLOTS * 2 + DECK_FIELD_COUNT + BONUS_HAND_SLOTS + HELD_CHECK_FIELD_COUNT + BOARD_SLOT_COUNT * NUMBERS_PER_BOARD_CELL

    /** Vector pack for absent optional **ints** (`Int?` on the DTO → this code). Not used for enums. */
    const val SENTINEL = -1

    // --- move pack (sealed MoveValue): 1..6 fixed; 7 = RandomD6 ---
    const val MOVE_RANDOM_D6 = 7

    // --- throw pack (sealed ThrowStat): 1 = Unthrowable; 2..6 = threshold ---
    const val THROW_UNTHROWABLE = 1

    // --- armour pack (sealed ArmourStat): 1 = NoArmour; 2..6 = threshold ---
    const val ARMOUR_NONE = 1

    fun squareIndex(x: Int, y: Int): Int {
        require(x in 1..PLAY_WIDTH && y in 1..PLAY_HEIGHT) { "Playable coord out of range: ($x,$y)" }
        return (y - 1) * PLAY_WIDTH + (x - 1)
    }

    fun coordinate(pitchIndex: Int): Pair<Int, Int> {
        require(pitchIndex in 0 until PITCH_SQUARE_COUNT) { "Pitch index out of range: $pitchIndex" }
        val y = pitchIndex / PLAY_WIDTH + 1
        val x = pitchIndex % PLAY_WIDTH + 1
        return x to y
    }

    fun isPitchIndex(index: Int): Boolean = index in 0 until PITCH_SQUARE_COUNT

    fun isHomeBenchIndex(index: Int): Boolean = index in HOME_BENCH_START until (HOME_BENCH_START + BENCH_SLOTS)

    fun isAwayBenchIndex(index: Int): Boolean = index in AWAY_BENCH_START until (AWAY_BENCH_START + BENCH_SLOTS)

    fun isBenchIndex(index: Int): Boolean = isHomeBenchIndex(index) || isAwayBenchIndex(index)

    fun benchSlot(index: Int): Int =
        when {
            isHomeBenchIndex(index) -> index - HOME_BENCH_START
            isAwayBenchIndex(index) -> index - AWAY_BENCH_START
            else                    -> throw IllegalArgumentException("Not a bench index: $index")
        }

    fun homeBenchIndex(slot: Int): Int {
        require(slot in 0 until BENCH_SLOTS) { "Bench slot out of range: $slot" }
        return HOME_BENCH_START + slot
    }

    fun awayBenchIndex(slot: Int): Int {
        require(slot in 0 until BENCH_SLOTS) { "Bench slot out of range: $slot" }
        return AWAY_BENCH_START + slot
    }

    /**
     * Map an absolute pitch/bench index into the obs / legal-factor index for [perspectiveSeat].
     *
     * HOME → identity. AWAY → 180° pitch rotation plus bench regions swapped so `165..174` is always
     * “us” and `175..184` is always “them”.
     */
    fun toPerspectiveIndex(absoluteIndex: Int, perspectiveSeat: TeamSide): Int =
        when (perspectiveSeat) {
            TeamSide.HOME -> absoluteIndex
            TeamSide.AWAY ->
                when {
                    isPitchIndex(absoluteIndex)     -> {
                        val (x, y) = coordinate(absoluteIndex)
                        squareIndex(PLAY_WIDTH - x + 1, PLAY_HEIGHT - y + 1)
                    }
                    isHomeBenchIndex(absoluteIndex) -> awayBenchIndex(benchSlot(absoluteIndex))
                    isAwayBenchIndex(absoluteIndex) -> homeBenchIndex(benchSlot(absoluteIndex))
                    else                            -> throw IllegalArgumentException("Board index out of range: $absoluteIndex")
                }
        }
}

/** Relative to [AiHarnessStepDto.perspectiveSeat] — not absolute [TeamSide]. */
enum class AiTeamRel {
    US,
    THEM,
}

/**
 * Pitch / dugout terrain for [AiCellObs].
 *
 * Cell *index* is perspective-relative. End-zone codes are too: [END_ZONE_HOME] = perspective’s
 * own end, [END_ZONE_AWAY] = opponent’s (scoring) end — legacy wire names, not absolute [TeamSide].
 */
enum class AiTerrain {
    FIELD,
    END_ZONE_HOME,
    END_ZONE_AWAY,
    TRAPDOOR,
    BLOCKED,
    BENCH,
}

