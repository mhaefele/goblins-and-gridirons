"""Slice Kotlin-packed obs into the Dict observation the policy reads.

Owner: float vector → gym Dict keys/spaces. Kotlin owns field values and packing
(``AiObsPacker`` / expert dump). Used by ``env`` and ``expert`` import. See
``ai/OWNERSHIP.md`` / ``DESIGN.md``.
"""

from __future__ import annotations

import numpy as np
from gymnasium import spaces

from gridiron_ai.actions import pad_legal_moves
from gridiron_ai.constants import (
    ACTIVE_CHALLENGE_SLOTS,
    ACTOR_PLAYER_INDEX_COUNT,
    BENCH_SLOTS,
    BONUS_HAND_SLOTS,
    BOARD_SLOT_COUNT,
    DECK_FIELD_COUNT,
    FACTORS_PER_LEGAL_MOVE,
    HELD_CHECK_FIELD_COUNT,
    LEGAL_ACTION_KIND_COUNT,
    MAX_LEGAL_MOVES,
    MISSING_OPTIONAL_FIELD,
    NUMBERS_PER_BOARD_CELL,
    OBSERVATION_VECTOR_LENGTH,
    OBS_KEY_BENCHES,
    OBS_KEY_CHALLENGES,
    OBS_KEY_DECK,
    OBS_KEY_HAND,
    OBS_KEY_HELD_CHECK,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_SOFT_MASK_LEGAL,
    OBS_KEY_PITCH,
    OBS_KEY_SCALARS,
    PITCH_SQUARE_COUNT,
    PLAY_HEIGHT,
    PLAY_WIDTH,
    SCALAR_COUNT,
)


def dict_observation_space() -> spaces.Dict:
    """Same Dict spaces MaskablePPO / clone_expert use — no HTTP required to construct them.

    # DEVIATION / DO NOT: SB3 pickles this entire Dict into every model zip. Treat shapes/dtypes
    # as a frozen wire schema. Train knobs (soft-mask strength, curriculum scale, debug) belong
    # on the live policy or env config — never by widening a Box here.
    """
    cell_low = float(MISSING_OPTIONAL_FIELD)
    return spaces.Dict(
        {
            OBS_KEY_SCALARS: spaces.Box(low=cell_low, high=1.0e6, shape=(SCALAR_COUNT,), dtype=np.float32),
            OBS_KEY_CHALLENGES: spaces.Box(
                low=0.0, high=1.0e6, shape=(ACTIVE_CHALLENGE_SLOTS, 2), dtype=np.float32
            ),
            OBS_KEY_DECK: spaces.Box(low=0.0, high=1.0e6, shape=(DECK_FIELD_COUNT,), dtype=np.float32),
            OBS_KEY_HAND: spaces.Box(low=0.0, high=1.0e6, shape=(BONUS_HAND_SLOTS,), dtype=np.float32),
            OBS_KEY_HELD_CHECK: spaces.Box(
                low=0.0, high=1.0e6, shape=(HELD_CHECK_FIELD_COUNT,), dtype=np.float32
            ),
            OBS_KEY_PITCH: spaces.Box(
                low=cell_low,
                high=1.0e6,
                shape=(NUMBERS_PER_BOARD_CELL, PLAY_HEIGHT, PLAY_WIDTH),
                dtype=np.float32,
            ),
            OBS_KEY_BENCHES: spaces.Box(
                low=cell_low,
                high=1.0e6,
                shape=(2, BENCH_SLOTS, NUMBERS_PER_BOARD_CELL),
                dtype=np.float32,
            ),
            OBS_KEY_LEGAL_MOVES: spaces.Box(
                low=-1,
                high=max(ACTOR_PLAYER_INDEX_COUNT, LEGAL_ACTION_KIND_COUNT, PITCH_SQUARE_COUNT) + 200,
                shape=(MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE),
                dtype=np.int64,
            ),
            OBS_KEY_LEGAL_MOVE_COUNT: spaces.Box(low=0, high=MAX_LEGAL_MOVES, shape=(1,), dtype=np.int32),
            # Preferred-row flags only (0/1). SB3 pickles this Dict into every checkpoint zip —
            # never widen dtype/bounds for train juice (strength, curriculum, debug). Put knobs on
            # the live policy (``LegalRowActorCriticPolicy.soft_mask_strength``), not in this Box.
            OBS_KEY_SOFT_MASK_LEGAL: spaces.Box(low=0, high=1, shape=(MAX_LEGAL_MOVES,), dtype=np.int8),
        }
    )


def empty_step_observation() -> dict[str, np.ndarray]:
    """Zero / pad observation with the gym Dict shapes (dummy env, clone bootstrap)."""
    space = dict_observation_space()
    packed: dict[str, np.ndarray] = {}
    for key, box in space.spaces.items():
        assert isinstance(box, spaces.Box)
        if key == OBS_KEY_LEGAL_MOVES:
            packed[key] = np.full(box.shape, -1, dtype=np.int64)
        elif key == OBS_KEY_SOFT_MASK_LEGAL:
            packed[key] = np.zeros(box.shape, dtype=np.int8)
        else:
            packed[key] = np.zeros(box.shape, dtype=box.dtype)
    return packed


def obs_flat_to_dict(obs_flat: np.ndarray | list[float]) -> dict[str, np.ndarray]:
    """Slice packed ``obs`` (length OBSERVATION_VECTOR_LENGTH) into gym Dict game-state keys.

    Concat order matches Kotlin ``AiObsPacker`` / ``constants.OBSERVATION_VECTOR_LENGTH``.
    Does not attach legal_moves — use [attach_packed_legals].
    """
    vector = np.asarray(obs_flat, dtype=np.float32).reshape(-1)
    if vector.size != OBSERVATION_VECTOR_LENGTH:
        raise ValueError(f"obs length {vector.size} != {OBSERVATION_VECTOR_LENGTH}")
    i = 0

    def take(n: int) -> np.ndarray:
        nonlocal i
        chunk = vector[i : i + n]
        i += n
        return chunk

    scalars = take(SCALAR_COUNT)
    challenges = take(ACTIVE_CHALLENGE_SLOTS * 2).reshape(ACTIVE_CHALLENGE_SLOTS, 2)
    deck = take(DECK_FIELD_COUNT)
    hand = take(BONUS_HAND_SLOTS)
    held_check = take(HELD_CHECK_FIELD_COUNT)
    cells = take(BOARD_SLOT_COUNT * NUMBERS_PER_BOARD_CELL).reshape(
        BOARD_SLOT_COUNT, NUMBERS_PER_BOARD_CELL
    )
    if i != OBSERVATION_VECTOR_LENGTH:
        raise RuntimeError(f"obs slice cursor {i} != {OBSERVATION_VECTOR_LENGTH}")

    # cells[0..164] → (C, H, W); cells[165..] → (2, bench, C)
    pitch = cells[:PITCH_SQUARE_COUNT].reshape(PLAY_HEIGHT, PLAY_WIDTH, NUMBERS_PER_BOARD_CELL).transpose(
        2, 0, 1
    )
    benches = cells[PITCH_SQUARE_COUNT:].reshape(2, BENCH_SLOTS, NUMBERS_PER_BOARD_CELL)

    packed = {
        OBS_KEY_SCALARS: np.asarray(scalars, dtype=np.float32),
        OBS_KEY_CHALLENGES: np.asarray(challenges, dtype=np.float32),
        OBS_KEY_DECK: np.asarray(deck, dtype=np.float32),
        OBS_KEY_HAND: np.asarray(hand, dtype=np.float32),
        OBS_KEY_HELD_CHECK: np.asarray(held_check, dtype=np.float32),
        OBS_KEY_PITCH: np.ascontiguousarray(pitch, dtype=np.float32),
        OBS_KEY_BENCHES: np.asarray(benches, dtype=np.float32),
    }
    _check_packed_field_count(packed)
    return packed


def attach_packed_legals(
    game_obs: dict[str, np.ndarray],
    *,
    legal_moves: np.ndarray | list[list[int]] | None,
    soft_mask_legal: np.ndarray | list[bool] | list[int] | None,
) -> dict[str, np.ndarray]:
    """Pad packed legal matrix + soft-mask flags onto a game-state Dict from [obs_flat_to_dict]."""
    if legal_moves is None or len(legal_moves) == 0:
        encoded = np.empty((0, FACTORS_PER_LEGAL_MOVE), dtype=np.int64)
        soft_mask_src = np.zeros(0, dtype=np.int8)
    else:
        encoded = np.asarray(legal_moves, dtype=np.int64).reshape(-1, FACTORS_PER_LEGAL_MOVE)
        soft_mask_src = np.asarray(soft_mask_legal or [], dtype=np.int8).reshape(-1)
    padded_legal_moves, legal_move_count = pad_legal_moves(encoded)
    soft_mask = np.zeros(MAX_LEGAL_MOVES, dtype=np.int8)
    n_mask = min(legal_move_count, soft_mask_src.size, MAX_LEGAL_MOVES)
    if n_mask:
        # Clamp to 0/1 — train strength must never ride this channel (breaks SB3 space equality).
        soft_mask[:n_mask] = (soft_mask_src[:n_mask] != 0).astype(np.int8)
    out = dict(game_obs)
    out[OBS_KEY_LEGAL_MOVES] = padded_legal_moves.astype(np.int64)
    out[OBS_KEY_LEGAL_MOVE_COUNT] = np.array([legal_move_count], dtype=np.int32)
    out[OBS_KEY_SOFT_MASK_LEGAL] = soft_mask
    return out


def slice_packed_step(
    *,
    obs_flat: np.ndarray | list[float],
    legal_moves: np.ndarray | list[list[int]] | None,
    soft_mask_legal: np.ndarray | list[bool] | list[int] | None,
) -> dict[str, np.ndarray]:
    """Packed step (HTTP or expert JSONL) → gym Dict (flat obs slice + padded legals)."""
    return attach_packed_legals(
        obs_flat_to_dict(obs_flat),
        legal_moves=legal_moves,
        soft_mask_legal=soft_mask_legal,
    )


def _check_packed_field_count(packed: dict[str, np.ndarray]) -> None:
    """Same numbers as the DTO concat checksum — split, not a second layout."""
    n = (
        packed[OBS_KEY_SCALARS].size
        + packed[OBS_KEY_CHALLENGES].size
        + packed[OBS_KEY_DECK].size
        + packed[OBS_KEY_HAND].size
        + packed[OBS_KEY_HELD_CHECK].size
        + packed[OBS_KEY_PITCH].size
        + packed[OBS_KEY_BENCHES].size
    )
    if n != OBSERVATION_VECTOR_LENGTH:
        raise RuntimeError(f"packed DTO field count {n} != expected {OBSERVATION_VECTOR_LENGTH}")
