"""Fixed sizes for the Python training harness (must match Kotlin AI schema).

Owner: ``SCHEMA_VERSION`` + obs/action head widths shared by ``obs`` / ``actions`` /
``features``. Bump on both sides when packing, gym Dict, or heads change. Field
semantics: Kotlin DTOs. See ``ai/OWNERSHIP.md`` / ``DESIGN.md``.
"""

# v16: action = masked index into this step's legal rows (join-score policy); obs packing unchanged.
SCHEMA_VERSION = 16

PLAY_WIDTH = 11
PLAY_HEIGHT = 15
PITCH_SQUARE_COUNT = PLAY_WIDTH * PLAY_HEIGHT  # 165
MAX_PLAYERS_PER_TEAM = 10  # catalog max roster; actor / cell playerIndex upper bound
BENCH_SLOTS = MAX_PLAYERS_PER_TEAM
HOME_BENCH_START = PITCH_SQUARE_COUNT  # 165
BOARD_SLOT_COUNT = PITCH_SQUARE_COUNT + 2 * BENCH_SLOTS  # 185

ACTIVE_CHALLENGE_SLOTS = 3
# Mid-turn max: ≤3 after EOT trim + up to 3 claims (Clean Sweep); pad unused with null.
BONUS_HAND_SLOTS = 6
MISSING_OPTIONAL_FIELD = -1

LEGAL_ACTION_KIND_COUNT = 34  # AiLegalKind.entries.size (must match Kotlin)
# Pad legal row matrices in vec-env / rollout buffer. One route per destination (BFS), but
# bonus-card twins (Sprint/Weave/…) multiply rows; live self-play has exceeded 512.
MAX_LEGAL_MOVES = 1024

# Actor playerIndex: 0 = unused (kind-only rows); 1..MAX_PLAYERS_PER_TEAM = roster slot + 1.
ACTOR_PLAYER_INDEX_COUNT = MAX_PLAYERS_PER_TEAM + 1  # includes unused 0

# Joint first head: actorPlayerIndex * LEGAL_ACTION_KIND_COUNT + kindIndex
ACTOR_KIND_HEAD_SIZE = ACTOR_PLAYER_INDEX_COUNT * LEGAL_ACTION_KIND_COUNT  # 11 * 34 = 374

# Legal-row factor packing (each menu row is still actor×kind, boardTarget, challengeSlot).
# Pad values on unused axes: boardTarget=PITCH_SQUARE_COUNT, challenge=ACTIVE_CHALLENGE_SLOTS
# (same as Kotlin AiObsLayout). Gym action is Discrete(MAX_LEGAL_MOVES), not MultiDiscrete.
ACTION_HEAD_SIZES = [
    ACTOR_KIND_HEAD_SIZE,
    PITCH_SQUARE_COUNT + 1,
    ACTIVE_CHALLENGE_SLOTS + 1,
]
# Policy / ONNX output width: one score per padded legal-row slot.
LEGAL_ROW_SCORE_WIDTH = MAX_LEGAL_MOVES

# terrain, hasBall, + 19 player fields (stats + affordances + reach bbox)
NUMBERS_PER_BOARD_CELL = 21
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
assert OBSERVATION_VECTOR_LENGTH == 3922

# Embedding row counts = 1 (empty/absent code 0) + Kotlin enum size.
# Sync with TurnPhase / AiTerrain / AiTeamRel / PlayerStatus / ChallengeClaimCondition /
# BonusPlay / PlayerAbility / AiHeldCheckKind / BlockDieFace when those enums gain a value.
TURN_PHASE_VOCAB = 7 + 1
AI_TERRAIN_VOCAB = 6 + 1
AI_TEAM_REL_VOCAB = 2 + 1
PLAYER_STATUS_VOCAB = 3 + 1
CHALLENGE_CONDITION_VOCAB = 15 + 1
BONUS_PLAY_VOCAB = 20 + 1
PLAYER_ABILITY_VOCAB = 31 + 1
# playerIndex 0..MAX (0 = empty); embedding rows match ACTOR_PLAYER_INDEX_COUNT.
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
OBS_KEY_SOFT_MASK_LEGAL = "soft_mask_legal"
# Parallel to legal_moves rows: 1 when server flagged passingScenarioMove (pass-grade solve; not soft-mask).
OBS_KEY_PASSING_LEGAL = "passing_legal"
FACTORS_PER_LEGAL_MOVE = len(ACTION_HEAD_SIZES)  # 3
# Logit bonus on factor values that appear on soft-mask rows (soft preference, not hard mask).
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


def encode_actor_kind(actor_player_index: int, kind_index: int) -> int:
    """Pack free actor + kind into the joint MultiDiscrete index."""
    return int(actor_player_index) * LEGAL_ACTION_KIND_COUNT + int(kind_index)
