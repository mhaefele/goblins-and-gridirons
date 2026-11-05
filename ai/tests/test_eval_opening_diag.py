"""Tests for scenario opening-mispick policy diagnostics (join-score)."""

from __future__ import annotations

import numpy as np
import pytest

from gridiron_ai.eval_opening_diag import (
    aggregate_opening_move_scores,
    annotate_move_label,
    ball_carrier_player_index,
    format_opening_mispick_diag,
    format_opening_score_bits,
    opening_score_fields,
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


def test_annotate_move_label_appends_rank_and_p() -> None:
    assert annotate_move_label("run(1,2->3,4)", None) == "run(1,2->3,4)"
    assert annotate_move_label("run(1,2->3,4)", {"rank": 7, "p": 0.0123}) == "run(1,2->3,4) [rank=7 p=0.012]"
    assert (
        annotate_move_label("block(1,2->3,4)", {"rank": 1, "p": 0.5}, reward=0.25)
        == "block(1,2->3,4) [rank=1 p=0.500 reward=+0.25]"
    )
    assert annotate_move_label("reserves(p1)", reward=-1.0) == "reserves(p1) [reward=-1]"


def test_aggregate_opening_move_scores_means() -> None:
    stats = aggregate_opening_move_scores(
        [
            {
                "opening_move_scores": {
                    "chosen": {"rank": 1, "p": 0.20},
                    "expected": [
                        {"rank": 10, "p": 0.02},
                        {"rank": 4, "p": 0.08},
                    ],
                }
            },
            {
                "opening_move_scores": {
                    "chosen": {"rank": 2, "p": 0.10},
                    "expected": [
                        {"rank": 6, "p": 0.04},
                        {"rank": 8, "p": 0.06},
                    ],
                }
            },
            {"opening_move_scores": None},
        ]
    )
    assert stats["opening_chosen_p_mean"] == pytest.approx(0.15)
    assert stats["opening_expected_p_mean"] == pytest.approx(0.05)  # mean of (0.05, 0.05)
    assert stats["opening_expected_p_best_mean"] == pytest.approx(0.07)  # mean of (0.08, 0.06)
    assert stats["opening_expected_rank_best_mean"] == pytest.approx(5.0)  # mean of (4, 6)


def test_aggregate_opening_move_scores_empty() -> None:
    stats = aggregate_opening_move_scores([{}, {"opening_move_scores": {}}])
    assert stats["opening_chosen_p_mean"] is None
    assert stats["opening_expected_p_mean"] is None
    assert stats["opening_expected_p_best_mean"] is None
    assert stats["opening_expected_rank_best_mean"] is None


def test_format_opening_score_bits_and_episodes_raw_fallback() -> None:
    assert format_opening_score_bits({}) == ""
    assert format_opening_score_bits({"opening_chosen_p_mean": 0.631}) == ""
    assert (
        format_opening_score_bits(
            {
                "opening_expected_p_mean": 0.01,
                "opening_expected_p_best_mean": 0.02,
                "opening_chosen_p_mean": 0.1,
                "opening_expected_rank_best_mean": 12,
            }
        )
        == " expected_p=0.010 expected_best_p=0.020 first_p=0.100 expected_best_rank=12.0"
    )
    assert (
        format_opening_score_bits(
            {
                "opening_expected_p_mean": 0.006,
                "opening_expected_p_best_mean": 0.006,
                "opening_chosen_p_mean": 0.157,
                "opening_expected_rank_best_mean": 17.5,
            },
            prior={
                "opening_expected_p_mean": 0.05,
                "opening_expected_p_best_mean": 0.08,
                "opening_chosen_p_mean": 0.2,
                "opening_expected_rank_best_mean": 10.0,
            },
        )
        == " expected_p=0.050→0.006 expected_best_p=0.080→0.006 first_p=0.200→0.157 expected_best_rank=10.0→17.5"
    )
    fields = opening_score_fields(
        {
            "episodes_raw": [
                {
                    "opening_move_scores": {
                        "chosen": {"p": 0.2},
                        "expected": [{"rank": 3, "p": 0.05}],
                    }
                }
            ]
        }
    )
    assert fields["opening_chosen_p_mean"] == pytest.approx(0.2)
    assert fields["opening_expected_rank_best_mean"] == pytest.approx(3.0)


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
