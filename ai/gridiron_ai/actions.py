"""Packed legal rows ↔ policy MultiDiscrete factors.

Owner: pad / resolve / conditional + preferred factor masks. Server packs rows
(``AiLegalPacker``); inventory / soft-mask policy: ``ACTION_SAMPLING.md``.
Called by ``env`` submit and ``conditional_policy``. See ``ai/OWNERSHIP.md``.

Schema 15+: actorKind, boardTarget, challengeSlot (joint actor×kind first).

# DEVIATION (AI2.9/10): conditional sampling order matches canonical storage
# (actorKind → target → challenge).
"""

from __future__ import annotations

from typing import Any, NamedTuple, TypeAlias

import numpy as np

from gridiron_ai.constants import ACTION_HEAD_SIZES, MAX_LEGAL_MOVES

# Canonical index in the factor tuple.
FACTOR_ACTOR_KIND = 0
FACTOR_BOARD_TARGET = 1
FACTOR_CHALLENGE_SLOT = 2

# Conditional pick order = canonical (joint actor×kind first).
CONDITIONAL_SAMPLE_ORDER: tuple[int, ...] = (
    FACTOR_ACTOR_KIND,
    FACTOR_BOARD_TARGET,
    FACTOR_CHALLENGE_SLOT,
)

# One legal move: three ints in canonical slot order.
EncodedLegalMove: TypeAlias = tuple[int, int, int]

# Legal moves this step — shape (n, 3), dtype int64 (from packed HTTP / expert).
EncodedLegalMoves: TypeAlias = np.ndarray

# pad_legal_moves output — shape (MAX_LEGAL_MOVES, 3); only [:legal_move_count] rows are real.
PaddedLegalMoves: TypeAlias = np.ndarray


class ResolvedAction(NamedTuple):
    """Submit id plus wire kind name for metrics bucketing and the chosen legal row."""

    legal_action_id: str
    sampled_kind: str
    legal_choice: dict[str, Any]


def action_head_sizes() -> list[int]:
    """How many values exist on each MultiDiscrete axis (canonical tuple order)."""
    return list(ACTION_HEAD_SIZES)


def pad_legal_moves(legal_moves: EncodedLegalMoves) -> tuple[PaddedLegalMoves, int]:
    """Copy encoded legal moves into a fixed-size buffer SubprocVecEnv and PPO can stack.

    Legal move count changes every step (sometimes 2, sometimes dozens of path ends).
    Parallel workers and the rollout buffer need the **same array shape** on every step
    so numpy can ``stack`` worker results and store a dense ``(time, env, …)`` block.
    We pad with -1 rows and pass ``legal_move_count`` so masking ignores the tail.

    Returns ``(padded_legal_moves, legal_move_count)``.
    """
    legal_move_count = int(legal_moves.shape[0])
    if legal_move_count > MAX_LEGAL_MOVES:
        raise RuntimeError(
            f"Step has {legal_move_count} legal moves > MAX_LEGAL_MOVES={MAX_LEGAL_MOVES} "
            "— bump constants.py"
        )
    padded_legal_moves: PaddedLegalMoves = np.full((MAX_LEGAL_MOVES, len(ACTION_HEAD_SIZES)), -1, dtype=np.int64)
    if legal_move_count:
        padded_legal_moves[:legal_move_count] = legal_moves
    return padded_legal_moves, legal_move_count


def conditional_factor_mask(
    legal_moves: EncodedLegalMoves | PaddedLegalMoves,
    *,
    legal_move_count: int,
    chosen_factors: dict[int, int],
    factor_slot: int,
) -> np.ndarray:
    """Which values on ``factor_slot`` are still legal given ``chosen_factors`` so far?

    ``legal_moves[:legal_move_count]`` — encoded legal moves (see ``EncodedLegalMove``).
    """
    slot_size = ACTION_HEAD_SIZES[factor_slot]
    mask = np.zeros(slot_size, dtype=np.bool_)
    still_legal = legal_moves[:legal_move_count]
    for slot, value in chosen_factors.items():
        still_legal = still_legal[still_legal[:, slot] == value]
    if still_legal.size == 0:
        return mask
    for value in np.unique(still_legal[:, factor_slot]):
        if 0 <= int(value) < slot_size:
            mask[int(value)] = True
    return mask


def preferred_factor_values(
    legal_moves: EncodedLegalMoves | PaddedLegalMoves,
    soft_mask_legal: np.ndarray,
    *,
    legal_move_count: int,
    chosen_factors: dict[int, int],
    factor_slot: int,
) -> np.ndarray:
    """Factor values on this axis that still appear on soft-mask (``softMaskScenarioMove``) rows.

    Empty when no soft-mask rows remain among the still-legal set — caller skips soft bias.
    """
    still_legal = legal_moves[:legal_move_count]
    soft_mask = soft_mask_legal[:legal_move_count].astype(bool)
    if still_legal.size == 0 or not np.any(soft_mask):
        return np.empty(0, dtype=np.int64)
    for slot, value in chosen_factors.items():
        keep = still_legal[:, slot] == value
        still_legal = still_legal[keep]
        soft_mask = soft_mask[keep]
    if still_legal.size == 0 or not np.any(soft_mask):
        return np.empty(0, dtype=np.int64)
    values = np.unique(still_legal[soft_mask, factor_slot])
    slot_size = ACTION_HEAD_SIZES[factor_slot]
    return values[(values >= 0) & (values < slot_size)].astype(np.int64)


def resolve_packed_action(
    sampled_factors: np.ndarray | list[int] | tuple[int, ...],
    *,
    legal_moves: np.ndarray | list[list[int]],
    legal_rows: list[dict[str, Any]],
) -> ResolvedAction:
    """Map factor integers → legalActionId using the packed matrix + harness rows.

    ``legal_rows[i]`` is server-provided id, flags, kind/factors/claims — do not reverse-decode
    from the factor ints.
    """
    moves = np.asarray(legal_moves, dtype=np.int64).reshape(-1, len(ACTION_HEAD_SIZES))
    if moves.shape[0] != len(legal_rows):
        raise RuntimeError(
            f"legalMoves rows {moves.shape[0]} != legalRows length {len(legal_rows)}"
        )
    if moves.shape[0] == 0:
        raise RuntimeError(
            "No legal moves to resolve on a live packed step — empty legals with resultKind=null "
            "is a server/engine invariant break"
        )

    target = np.asarray(sampled_factors, dtype=np.int64).reshape(len(ACTION_HEAD_SIZES))
    if target.size != len(ACTION_HEAD_SIZES):
        raise RuntimeError(
            f"Expected {len(ACTION_HEAD_SIZES)} action factors, got size={target.size} "
            f"shape={getattr(sampled_factors, 'shape', None)} type={type(sampled_factors).__name__} "
            f"value={sampled_factors!r} — VecEnv needs (n_envs, {len(ACTION_HEAD_SIZES)}); "
            f"raw Gym needs ({len(ACTION_HEAD_SIZES)},)"
        )
    matches = np.flatnonzero(np.all(moves == target, axis=1))
    if matches.size == 0:
        raise RuntimeError(
            f"Sampled factors {tuple(int(x) for x in target)} are not a legal move — conditional policy bug "
            f"(legal_move_count={moves.shape[0]})"
        )
    if matches.size > 1:
        raise RuntimeError(
            f"Duplicate free-factor encoding {tuple(int(x) for x in target)} at rows {matches.tolist()}"
        )
    match_index = int(matches[0])
    row = legal_rows[match_index]
    kind_name = str(row.get("kind") or "other")
    legal_choice = {
        "kind": kind_name,
        "legalActionId": str(row["legalActionId"]),
        "factors": list(row.get("factors") or []),
        "potentialChallengeClaims": list(row.get("potentialChallengeClaims") or []),
    }
    return ResolvedAction(str(row["legalActionId"]), kind_name, legal_choice)
