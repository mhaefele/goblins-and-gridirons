"""Legal-row Discrete action resolve + mask helpers."""

from __future__ import annotations

import numpy as np
import pytest

from gridiron_ai.actions import (
    legal_row_action_mask,
    match_legal_row_index,
    resolve_legal_row_index,
)
from gridiron_ai.constants import LEGAL_ROW_SCORE_WIDTH


def test_legal_row_action_mask_enables_prefix() -> None:
    mask = legal_row_action_mask(legal_move_count=3)
    assert mask.shape == (LEGAL_ROW_SCORE_WIDTH,)
    assert mask[:3].all()
    assert not mask[3:].any()


def test_resolve_legal_row_index_returns_harness_id() -> None:
    moves = [
        [1, 0, 10, 0, 3, 5, 0, 0, 0, 2, 3],
        [2, 1, 0, 1, 3, 5, 0, 0, 0, 4, 5],
    ]
    rows = [
        {
            "action": {"kind": "run", "id": "a"},
            "softMaskLegal": False,
            "passingLegal": False,
        },
        {
            "action": {"kind": "block", "id": "b"},
            "softMaskLegal": False,
            "passingLegal": False,
        },
    ]
    resolved = resolve_legal_row_index(1, legal_moves=moves, legal_rows=rows)
    assert resolved.legal_action_id == "b"
    assert resolved.sampled_kind == "block"
    assert resolved.legal_choice == {"kind": "block", "id": "b"}


def test_match_legal_row_index_finds_factors() -> None:
    moves = np.asarray(
        [
            [1, 0, 10, 0, 3, 5, 0, 0, 0, 2, 3],
            [2, 1, 0, 1, 3, 5, 0, 0, 0, 4, 5],
        ],
        dtype=np.int64,
    )
    assert match_legal_row_index([2, 1, 0, 1, 3, 5, 0, 0, 0, 4, 5], moves) == 1


def test_resolve_rejects_out_of_range_index() -> None:
    moves = [[1, 0, 10, 0, 3, 5, 0, 0, 0, 2, 3]]
    rows = [
        {
            "action": {"kind": "run", "id": "a"},
            "softMaskLegal": False,
            "passingLegal": False,
        }
    ]
    with pytest.raises(RuntimeError, match="out of range"):
        resolve_legal_row_index(1, legal_moves=moves, legal_rows=rows)
