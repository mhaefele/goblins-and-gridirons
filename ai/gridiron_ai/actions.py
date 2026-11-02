"""Packed legal rows ↔ policy action (row index).

Owner: pad / resolve row index. Server packs rows (``AiLegalPacker``); sampling is
join-score over this step's legal menu (``legal_row_policy``). See
``ACTION_SAMPLING.md`` / ``ai/OWNERSHIP.md``.

Schema 19+: gym action is ``Discrete(MAX_LEGAL_MOVES)`` — an index into
``legal_moves[:legal_move_count]``. Each packed row is identity factors then
metadata (Kotlin ``AiLegalPacker``) — see factor-slot comments below.
"""

from __future__ import annotations

from typing import Any, NamedTuple, TypeAlias

import numpy as np

from gridiron_ai.constants import ACTION_FACTOR_WIDTHS, LEGAL_ROW_SCORE_WIDTH, MAX_LEGAL_MOVES

# Canonical index in the factor tuple packed on each legal row.
# Slots 0..8 = row identity (kind + free/sugar that distinguish the legal choice).
# Who-id is roster Actor (playerIndex), not board coordinate — same id as cell occupant.
FACTOR_ACTOR = 0
FACTOR_KIND = 1
FACTOR_TARGET_X = 2
FACTOR_TARGET_Y = 3
FACTOR_CHALLENGE_SLOT = 4
FACTOR_ATTACHED_BONUS = 5
FACTOR_FACE0 = 6
FACTOR_FACE1 = 7
FACTOR_FACE2 = 8
# Metadata only (always last): 0-based pitch x/y from Kotlin for from→to geometry.
# Not legal-choice identity — who = FACTOR_ACTOR.
FACTOR_SOURCE_X = 9
FACTOR_SOURCE_Y = 10

# One legal move: eleven ints in canonical slot order (identity…, then metadata).
EncodedLegalMove: TypeAlias = tuple[int, int, int, int, int, int, int, int, int, int, int]

# Legal moves this step — shape (n, 3), dtype int64 (from packed HTTP / expert).
EncodedLegalMoves: TypeAlias = np.ndarray

# pad_legal_moves output — shape (MAX_LEGAL_MOVES, 3); only [:legal_move_count] rows are real.
PaddedLegalMoves: TypeAlias = np.ndarray


class ResolvedAction(NamedTuple):
    """Submit id plus wire kind name for metrics bucketing and the chosen legal row."""

    legal_action_id: str
    sampled_kind: str
    legal_choice: dict[str, Any]


def legal_row_action_n() -> int:
    """Gym ``Discrete`` width — one slot per padded legal-row index."""
    return LEGAL_ROW_SCORE_WIDTH


def legal_row_action_mask(*, legal_move_count: int) -> np.ndarray:
    """True for indices ``0 .. legal_move_count-1`` (ActionMasker / MaskablePPO)."""
    mask = np.zeros(LEGAL_ROW_SCORE_WIDTH, dtype=np.bool_)
    n = max(0, min(int(legal_move_count), LEGAL_ROW_SCORE_WIDTH))
    if n:
        mask[:n] = True
    return mask


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
    padded_legal_moves: PaddedLegalMoves = np.full((MAX_LEGAL_MOVES, len(ACTION_FACTOR_WIDTHS)), -1, dtype=np.int64)
    if legal_move_count:
        padded_legal_moves[:legal_move_count] = legal_moves
    return padded_legal_moves, legal_move_count


def match_legal_row_index(
    factors: np.ndarray | list[int] | tuple[int, ...],
    legal_moves: np.ndarray | list[list[int]],
) -> int:
    """Index of the unique packed row equal to ``factors``. Raises if missing/duplicate."""
    moves = np.asarray(legal_moves, dtype=np.int64).reshape(-1, len(ACTION_FACTOR_WIDTHS))
    if moves.shape[0] == 0:
        raise RuntimeError("No legal moves to match")
    target = np.asarray(factors, dtype=np.int64).reshape(len(ACTION_FACTOR_WIDTHS))
    if target.size != len(ACTION_FACTOR_WIDTHS):
        raise RuntimeError(
            f"Expected {len(ACTION_FACTOR_WIDTHS)} action factors, got size={target.size} "
            f"value={factors!r}"
        )
    matches = np.flatnonzero(np.all(moves == target, axis=1))
    if matches.size == 0:
        raise RuntimeError(
            f"Factors {tuple(int(x) for x in target)} are not in the legal matrix "
            f"(legal_move_count={moves.shape[0]})"
        )
    if matches.size > 1:
        raise RuntimeError(
            f"Duplicate free-factor encoding {tuple(int(x) for x in target)} at rows {matches.tolist()}"
        )
    return int(matches[0])


def resolve_legal_row_index(
    row_index: int | np.ndarray | list[int] | tuple[int, ...],
    *,
    legal_moves: np.ndarray | list[list[int]],
    legal_rows: list[dict[str, Any]],
) -> ResolvedAction:
    """Map a legal-row index → legalActionId using harness rows."""
    moves = np.asarray(legal_moves, dtype=np.int64).reshape(-1, len(ACTION_FACTOR_WIDTHS))
    if moves.shape[0] != len(legal_rows):
        raise RuntimeError(
            f"legalMoves rows {moves.shape[0]} != legalRows length {len(legal_rows)}"
        )
    if moves.shape[0] == 0:
        raise RuntimeError(
            "No legal moves to resolve on a live packed step — empty legals with resultKind=null "
            "is a server/engine invariant break"
        )
    index = int(np.asarray(row_index, dtype=np.int64).reshape(-1)[0])
    if not 0 <= index < moves.shape[0]:
        raise RuntimeError(
            f"Sampled legal-row index {index} out of range (legal_move_count={moves.shape[0]})"
        )
    row = legal_rows[index]
    kind_name = str(row.get("kind") or "other")
    legal_choice = {
        "kind": kind_name,
        "legalActionId": str(row["legalActionId"]),
        "factors": list(row.get("factors") or []),
        "potentialChallengeClaims": list(row.get("potentialChallengeClaims") or []),
    }
    return ResolvedAction(str(row["legalActionId"]), kind_name, legal_choice)
