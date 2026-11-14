"""Fixed sizes for the Python training harness (must match Kotlin AI schema).

Owner: ``SCHEMA_VERSION`` + obs / legal-row pack widths shared by ``obs`` / ``actions`` /
``features``. Bump on both sides when packing, gym Dict, or pack columns change. Field
semantics: Kotlin DTOs. See ``ai/OWNERSHIP.md`` / ``DESIGN.md``.
"""

# v24: throwSuccessChancePercent (0..100; 0 = non-throw) + blockDiceCount on legal rows;
# categorical absent → SENTINEL (-1); enums pack raw ordinal. Folded before dump used 24.
# v23: legal-row actorHasBall / targetHasBall metadata; bonus column = full BonusPlay vocab
# for requiredBonusCard (geometry + Play*/Do-Over); per-play kinds unchanged.
# (No dump/regen used prior 23 shapes — fold bonus widen into 23, do not bump.)
# v22: cell pitchX / pitchY / distToBall on the wire (was Python feature synth).
# v21: harness rewards.players + ballCoordinate (absolute); legal rows = LegalAction + flags.
# Schema 15 is the only dual-path ONNX fork.
SCHEMA_VERSION = 24

PLAY_WIDTH = 11
PLAY_HEIGHT = 15
PITCH_SQUARE_COUNT = PLAY_WIDTH * PLAY_HEIGHT  # 165
MAX_PLAYERS_PER_TEAM = 10  # catalog max roster; actor / cell playerIndex upper bound
BENCH_SLOTS = MAX_PLAYERS_PER_TEAM
BOARD_SLOT_COUNT = PITCH_SQUARE_COUNT + 2 * BENCH_SLOTS  # 185

ACTIVE_CHALLENGE_SLOTS = 3
# Legal-row / hand BonusPlay: SENTINEL (-1) = none; 0..BONUS_PLAY_COUNT-1 = ordinal.
BONUS_PLAY_COUNT = 20
BLOCK_FACE_PACK_SIZE = 5  # live BlockDieFace ordinals; SENTINEL = unused
# Mid-turn max: ≤3 after EOT trim + up to 3 claims (Clean Sweep); pad unused with null.
BONUS_HAND_SLOTS = 6
MISSING_OPTIONAL_FIELD = -1  # = Kotlin AiObsLayout.SENTINEL

LEGAL_ACTION_KIND_COUNT = 41  # AiLegalKind.entries.size (must match Kotlin)
# Pad legal row matrices in vec-env / rollout buffer. One route per destination (BFS), but
# bonus-card twins (Sprint/Weave/…) multiply rows; live self-play has exceeded 512.
MAX_LEGAL_MOVES = 1024

# Actor playerIndex: SENTINEL = unused; 1..MAX_PLAYERS_PER_TEAM = roster slot + 1.
# Emb table still has a pad row at index 0 (wire -1 → emb 0).
ACTOR_PLAYER_INDEX_COUNT = MAX_PLAYERS_PER_TEAM + 1  # emb rows: pad + 1..MAX

# Legal-row factor packing (Kotlin AiLegalPacker) — live-value widths (SENTINEL outside).
# Gym action is Discrete(MAX_LEGAL_MOVES) row index.
ACTION_FACTOR_WIDTHS = [
    MAX_PLAYERS_PER_TEAM,  # actor 1..MAX (SENTINEL unused)
    LEGAL_ACTION_KIND_COUNT,
    PLAY_WIDTH,  # targetX 0-based (SENTINEL unused)
    PLAY_HEIGHT,  # targetY 0-based
    ACTIVE_CHALLENGE_SLOTS,  # 0..2 (SENTINEL unused)
    BONUS_PLAY_COUNT,  # BonusPlay ordinal (SENTINEL none)
    BLOCK_FACE_PACK_SIZE,
    BLOCK_FACE_PACK_SIZE,
    BLOCK_FACE_PACK_SIZE,
    PLAY_WIDTH,  # sourceX
    PLAY_HEIGHT,  # sourceY
    2,  # actorHasBall metadata (0/1)
    2,  # targetHasBall metadata (0/1)
    101,  # throwSuccessChancePercent 0 = non-throw; 1..100 = P(success)×100
    3,  # blockDiceCount 1..3 (SENTINEL non-block)
]
# Policy / ONNX output width: one score per padded legal-row slot.
LEGAL_ROW_SCORE_WIDTH = MAX_LEGAL_MOVES

# terrain, hasBall, + 19 player fields, + pitchX / pitchY / distToBall
NUMBERS_PER_BOARD_CELL = 24
CELL_CH_TERRAIN = 0
CELL_CH_HAS_BALL = 1
CELL_CH_PLAYER_INDEX = 2
CELL_CH_TEAM_REL = 3
CELL_CH_STATUS = 4
CELL_CH_TURN_ENDED = 5
CELL_CH_MOVE = 6
CELL_CH_BLOCK_DICE = 7
CELL_CH_THROW_STAT = 8
CELL_CH_ARMOUR = 9
CELL_CH_ABILITY = 10
CELL_CH_CAN_THROW_TEAMMATE = 11
CELL_CH_CAN_SCORE_TD = 12
CELL_CH_MIGHT_CLAIM = 13
CELL_CH_CERTAIN_CLAIM = 14
CELL_CH_CAN_REACH_BALL = 15
CELL_CH_CAN_PICKUP = 16
CELL_CH_REACH_MIN_X = 17
CELL_CH_REACH_MAX_X = 18
CELL_CH_REACH_MIN_Y = 19
CELL_CH_REACH_MAX_Y = 20
CELL_CH_PITCH_X = 21
CELL_CH_PITCH_Y = 22
CELL_CH_DIST_TO_BALL = 23

# Match scalars before challenges/deck/hand/heldCheck/cells (AiObsDto declaration order).
SCALAR_COUNT = 17
SCALAR_CH_PHASE = 1  # embedded separately in the feature extractor
DECK_FIELD_COUNT = 3
MAX_HELD_BLOCK_FACES = 3
HELD_CHECK_FIELD_COUNT = 1 + 1 + MAX_HELD_BLOCK_FACES  # kind, d6, face0..2

# Packed ``obs`` concat (AiObsPacker / AiObsLayout) — same numbers as the gym Dict split.
OBSERVATION_VECTOR_LENGTH = (
    SCALAR_COUNT
    + ACTIVE_CHALLENGE_SLOTS * 2
    + DECK_FIELD_COUNT
    + BONUS_HAND_SLOTS
    + HELD_CHECK_FIELD_COUNT
    + BOARD_SLOT_COUNT * NUMBERS_PER_BOARD_CELL
)
assert OBSERVATION_VECTOR_LENGTH == 4477

# Embedding row counts = enum size + 1 pad row (wire -1 → last emb row).
# Sync with TurnPhase / AiTerrain / AiTeamRel / PlayerStatus / ChallengeClaimCondition /
# BonusPlay / PlayerAbility / AiHeldCheckKind / BlockDieFace when those enums gain a value.
TURN_PHASE_VOCAB = 7 + 1
AI_TERRAIN_VOCAB = 6 + 1
AI_TEAM_REL_VOCAB = 2 + 1
PLAYER_STATUS_VOCAB = 3 + 1
CHALLENGE_CONDITION_VOCAB = 15 + 1
BONUS_PLAY_VOCAB = BONUS_PLAY_COUNT + 1
PLAYER_ABILITY_VOCAB = 31 + 1
# playerIndex emb: row 0 = pad (wire -1); rows 1..MAX = roster.
PLAYER_INDEX_VOCAB = ACTOR_PLAYER_INDEX_COUNT
AI_HELD_CHECK_KIND_VOCAB = 3 + 1  # BLOCK_FACES / THROW_D6 / ARMOUR_D6
BLOCK_DIE_FACE_VOCAB = 5 + 1  # SMASH / FLATTEN / SHOVE / TACKLE / MISS

# Dict observation keys (env → policy). Legal moves ride in obs so stock MaskablePPO replay works.
OBS_KEY_SCALARS = "scalars"
OBS_KEY_CHALLENGES = "challenges"
OBS_KEY_DECK = "deck"
OBS_KEY_HAND = "hand"
OBS_KEY_HELD_CHECK = "held_check"
OBS_KEY_PITCH = "pitch"
OBS_KEY_BENCHES = "benches"
OBS_KEY_LEGAL_MOVES = "legal_moves"
OBS_KEY_LEGAL_MOVE_COUNT = "legal_move_count"
# Parallel to legal_moves rows: 1 when server flagged softMaskScenarioMove (soft-mask bias).
# Stable 0/1 flags only — do not encode train knobs here (SB3 saves observation_space in the zip).
OBS_KEY_SOFT_MASK_LEGAL = "soft_mask_legal"
# Parallel to legal_moves rows: 1 when server flagged passingScenarioMove (pass-grade solve; not soft-mask).
OBS_KEY_PASSING_LEGAL = "passing_legal"
FACTORS_PER_LEGAL_MOVE = len(ACTION_FACTOR_WIDTHS)  # 15
# Logit bonus on preferred legal rows (soft preference, not hard mask).
# Effective bias = SOFT_MASK_LOGIT_BIAS × policy.soft_mask_strength (train knob; not in obs).
SOFT_MASK_LOGIT_BIAS = 2.5

# Gym keys the feature extractor reads.
OBS_KEYS_GAME_STATE = (
    OBS_KEY_SCALARS,
    OBS_KEY_CHALLENGES,
    OBS_KEY_DECK,
    OBS_KEY_HAND,
    OBS_KEY_HELD_CHECK,
    OBS_KEY_PITCH,
    OBS_KEY_BENCHES,
)
