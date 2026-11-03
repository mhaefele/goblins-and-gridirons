"""Tests for scenario opening-mispick policy diagnostics (join-score)."""

from __future__ import annotations

import numpy as np

from gridiron_ai.eval_opening_diag import (
    ball_carrier_player_index,
    format_opening_mispick_diag,
    preferred_row_rank,
)


def test_preferred_row_rank_reports_best_expected() -> None:
    logits = np.array([1.0, 5.0, 3.0, 0.5], dtype=np.float64)
    preferred = np.array([True, False, True, False])
    stats = preferred_row_rank(logits=logits, legal_count=4, preferred=preferred)
    assert stats is not None
    assert stats["argmax_index"] == 1
    assert stats["best_index"] == 2  # logit 3.0 beats preferred 1.0
    assert stats["best_rank"] == 2
    assert stats["n_preferred"] == 2
    assert stats["logit_gap"] == 2.0


def test_preferred_row_rank_none_without_flags() -> None:
    assert preferred_row_rank(logits=np.ones(3), legal_count=3, preferred=np.zeros(3, dtype=bool)) is None


def test_ball_carrier_player_index_from_rewards() -> None:
    rewards = {
        "ballCoordinate": {"x": 3, "y": 4},
        "players": [
            {"playerId": "h0", "playerIndex": 1, "teamRel": "US", "coordinate": {"x": 1, "y": 1}},
            {"playerId": "h1", "playerIndex": 4, "teamRel": "US", "coordinate": {"x": 3, "y": 4}},
        ],
    }
    assert ball_carrier_player_index(rewards) == 4


def test_format_opening_mispick_diag_includes_ranks() -> None:
    line = format_opening_mispick_diag(
        {
            "chosen": "throw(8,7->2,5)",
            "chosen_row": {
                "index": 3,
                "kind": "throw",
                "rank": 1,
                "p_plain": 0.4,
                "p_biased": 0.35,
                "soft_mask": False,
                "passing": False,
            },
            "expected_rank": {
                "best_rank": 47,
                "legal_count": 311,
                "n_preferred": 10,
                "logit_gap": 2.15,
            },
            "soft_rank": {
                "best_rank": 47,
                "legal_count": 311,
                "n_preferred": 10,
                "logit_gap": 2.15,
            },
            "ball_carrier_player_index": 4,
            "carrier_kind_mass": {
                "sidestep": {"p_plain": 0.2, "p_biased": 0.45},
                "throw": {"p_plain": 0.4, "p_biased": 0.35},
            },
            "top_rows": [{"kind": "throw", "p_plain": 0.4}],
            "soft_mask_row_count": 10,
            "legal_count": 311,
        }
    )
    assert "policy_diag=" in line
    assert "expected_best=47/311" in line
    assert "gap=+2.15" in line
    assert "carrier_p4:" in line
    assert "rank=1/311" in line
    assert "policy_menu=" not in line


def test_format_opening_mispick_diag_super_verbose_menu() -> None:
    line = format_opening_mispick_diag(
        {
            "chosen": "throw(2,9->1,7)",
            "chosen_row": {"index": 3, "kind": "throw", "rank": 1, "p_plain": 0.5, "p_biased": 0.4},
            "acting_biased": False,
            "legal_count": 2,
            "ranked_rows": [
                {
                    "rank": 1,
                    "index": 3,
                    "label": "throw(2,9->1,7)",
                    "p_plain": 0.5,
                    "p_biased": 0.4,
                    "logit_acting": 1.2,
                    "passing": False,
                    "soft_mask": False,
                },
                {
                    "rank": 2,
                    "index": 7,
                    "label": "run(2,9->5,15)*",
                    "p_plain": 0.3,
                    "p_biased": 0.45,
                    "logit_acting": 0.8,
                    "passing": True,
                    "soft_mask": True,
                },
            ],
        }
    )
    assert "policy_menu=" in line
    assert "#1 idx=3 throw(2,9->1,7)" in line
    assert "<<<" in line
    assert "#2 idx=7 run(2,9->5,15)*" in line
    assert "sm" in line.split("#2", 1)[1]
