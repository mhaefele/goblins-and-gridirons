"""Unit tests for verbose PPO update evidence (sidecar)."""

from __future__ import annotations

import numpy as np

from gridiron_ai.ppo_update_evidence import (
    _unflatten_rollout_field,
    expected_move_labels_from_snapshot,
    format_failed_drill_block,
    format_move_sequence,
    format_pass_edge_buffer_line,
    format_passed_drill_block,
    format_ppo_update_line,
    summarize_pass_step_advantages,
)


def test_format_move_sequence_stops_at_touchdown() -> None:
    text = format_move_sequence(
        [
            {"label": "run(1,2->3,4)*", "passing": True, "reward": 0.25},
            {"label": "run(3,4->5,7)*!", "passing": True, "reward": 4.75, "scored_td": True},
            {"label": "reserves(p3)", "passing": False, "reward": 0.0},
            {"label": "run(9,1->4,2)", "passing": False, "reward": 0.0},
        ],
        opening_score={"rank": 3, "p": 0.012},
    )
    assert text == (
        "run(1,2->3,4)* [rank=3 p=0.012 reward=+0.25] → "
        "run(3,4->5,7)*! [reward=+4.75]"
    )
    assert "reserves" not in text


def test_passed_drill_block_is_sequential() -> None:
    block = format_passed_drill_block(
        sequence="run(a)* [rank=12 p=0.080 reward=+0.25] → run(b)*! [reward=+4.75]",
        opening_label="run(a)*",
        opening_reward=0.25,
        p_before=0.08,
        p_after=0.11,
        rank_before=12,
        rank_after=9,
        scenario="LOOSE_BALL_RUN",
        opening_buf_rew=0.12,
        opening_buf_adv=0.40,
    )
    lines = block.splitlines()
    assert lines[0] == "[gridiron] Passed the drill. [LOOSE_BALL_RUN]"
    assert lines[1].startswith("  steps:")
    assert "run(a)* [rank=9 p=0.110]" in lines[2]
    assert "env=+0.25" in lines[2] and "buf(rew=+0.1200 adv=+0.4000)" in lines[2]
    assert "mass 0.080000→0.110000 (Δ=+0.030000, rank 12→9)" in lines[2]
    assert "scoring:" not in block
    assert "approx_kl" not in block


def test_expected_move_labels_accepts_numpy_passing_legal() -> None:
    """Opening snapshots store ``passing_legal`` as an ndarray — must not use ``x or []``."""
    labels = expected_move_labels_from_snapshot(
        {
            "passing_legal": np.asarray([0, 1, 0], dtype=np.int8),
            "legal_row_meta": [
                {"action": {"kind": "run", "id": "a"}},
                {"action": {"kind": "run", "id": "b", "playerId": "h0", "targetCoordinate": {"x": 5, "y": 9}}},
                {"action": {"kind": "block", "id": "c"}},
            ],
            "rewards": None,
        }
    )
    assert labels == "run(5,9)*"


def test_expected_move_labels_annotate_rank_and_p() -> None:
    labels = expected_move_labels_from_snapshot(
        {
            "passing_legal": np.asarray([0, 1], dtype=np.int8),
            "legal_row_meta": [
                {"action": {"kind": "run", "id": "a"}},
                {
                    "action": {
                        "kind": "run",
                        "id": "b",
                        "playerId": "h0",
                        "targetCoordinate": {"x": 10, "y": 14},
                    }
                },
            ],
            "rewards": None,
        },
        plain_by_index={1: (3, 0.0123)},
    )
    assert labels == "run(10,14)* [rank=3 p=0.012]"


def test_failed_drill_block_includes_opening_and_last() -> None:
    block = format_failed_drill_block(
        sequence="run(4,8->6,15) [rank=1 p=0.040 reward=+0]",
        opening_label="run(4,8->6,15)",
        opening_passing=False,
        opening_reward=0.0,
        last_reward=-0.5,
        p_before=0.04,
        p_after=0.03,
        rank_before=1,
        rank_after=2,
        scenario="LOOSE_BALL_RUN",
        opening_buf_rew=-0.01,
        opening_buf_adv=-0.20,
        last_buf_rew=-0.30,
        last_buf_adv=-0.50,
        expected_moves="run(5,8->5,9)* [rank=12 p=0.045]",
        opening_soft_mask=True,
    )
    lines = block.splitlines()
    assert lines[0].startswith("[gridiron] Failed the drill.")
    assert lines[2] == "  expected: run(5,8->5,9)* [rank=12 p=0.045]"
    assert "run(4,8->6,15) [rank=2 p=0.030]" in lines[3]
    assert "env=+0.00" in lines[3]
    assert "mass 0.040000→0.030000 (Δ=-0.010000, rank 1→2)" in lines[3]
    assert "env=-0.50" in lines[4]
    assert "non-passing, softmask" in lines[3]


def test_failed_drill_block_omits_last_when_same_as_opening() -> None:
    block = format_failed_drill_block(
        sequence="run(a) [rank=8 p=0.075 reward=-3]",
        opening_label="run(a)",
        opening_passing=False,
        opening_reward=-3.0,
        last_reward=-3.0,
        p_before=0.075,
        p_after=0.073,
        rank_before=8,
        rank_after=8,
        scenario="LOOSE_BALL_RUN",
        opening_buf_rew=-1.3353,
        opening_buf_adv=-1.1442,
        last_buf_rew=-1.3353,
        last_buf_adv=-1.1442,
        expected_moves="run(b)* [rank=1 p=0.105]",
        opening_soft_mask=False,
    )
    assert "last:" not in block
    assert "opening:" in block


def test_mass_delta_keeps_sub_milli_moves() -> None:
    block = format_failed_drill_block(
        sequence="run(a)",
        opening_label="run(a)",
        opening_passing=False,
        opening_reward=-3.0,
        last_reward=-3.0,
        p_before=0.008041,
        p_after=0.008029,
        rank_before=60,
        rank_after=62,
        scenario="LOOSE_BALL_RUN",
        expected_moves="run(b)* [rank=14 p=0.021]",
    )
    assert "Δ=-0.000012" in block
    assert "0.008041→0.008029" in block
    assert "Δ=+0.000" not in block.split("Δ=")[1][:8]


def test_ppo_update_line_once_per_update() -> None:
    line = format_ppo_update_line(
        theta_before=1.0,
        theta_after=1.0001,
        approx_kl=1.2e-4,
        scenario="LOOSE_BALL_RUN",
        n_passed=2,
        n_failed=3,
    )
    assert line.startswith("[gridiron] PPO update [LOOSE_BALL_RUN]:")
    assert "passed=2 failed=3" in line
    assert "approx_kl=" in line


def test_summarize_pass_step_advantages_score_and_prior() -> None:
    advantages = np.array([[1.0, 9.0], [2.0, 8.0], [3.0, 7.0]], dtype=np.float32)
    rewards = np.array([[0.0, 0.1], [0.0, 0.2], [4.5, 0.3]], dtype=np.float32)
    episode_starts = np.array([[True, True], [False, False], [False, False]])
    stats = summarize_pass_step_advantages(
        advantages=advantages,
        rewards=rewards,
        episode_starts=episode_starts,
        pass_edges=[(2, 0)],
    )
    assert stats["pass_n"] == 1
    assert stats["score_adv_mean"] == 3.0
    assert stats["score_rew_mean"] == 4.5
    assert stats["prior_n"] == 1
    assert stats["prior_adv_mean"] == 2.0
    line = format_pass_edge_buffer_line(stats=stats, scenario="LOOSE_BALL_RUN")
    assert line.startswith("[gridiron] PASS-edge buffer")


def test_unflatten_rollout_field_undoes_sb3_swap_and_flatten() -> None:
    original = np.arange(6, dtype=np.float32).reshape(3, 2)
    flattened = original.swapaxes(0, 1).reshape(2 * 3, 1)
    restored = _unflatten_rollout_field(flattened, buffer_size=3, n_envs=2)
    np.testing.assert_array_equal(restored, original)
