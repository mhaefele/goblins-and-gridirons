"""Unit tests for verbose PPO update evidence (sidecar)."""

from __future__ import annotations

import numpy as np

from gridiron_ai.ppo_update_evidence import (
    _unflatten_rollout_field,
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
        ]
    )
    assert text == "run(1,2->3,4)*[passing] → run(3,4->5,7)*![passing]"
    assert "reserves" not in text


def test_passed_drill_block_is_sequential() -> None:
    block = format_passed_drill_block(
        sequence="run(a)*[passing] → run(b)*![passing]",
        opening_label="run(a)*",
        opening_reward=0.25,
        scoring_reward=4.75,
        p_before=0.08,
        p_after=0.11,
        rank_before=12,
        rank_after=9,
        scenario="LOOSE_BALL_RUN",
        opening_buf_rew=0.12,
        opening_buf_adv=0.40,
        scoring_buf_rew=1.85,
        scoring_buf_adv=2.10,
    )
    lines = block.splitlines()
    assert lines[0] == "[gridiron] Passed the drill. [LOOSE_BALL_RUN]"
    assert lines[1].startswith("  steps:")
    assert "env=+0.25" in lines[2] and "buf(rew=+0.1200 adv=+0.4000)" in lines[2]
    assert "env=+4.75" in lines[3] and "buf(rew=+1.8500 adv=+2.1000)" in lines[3]
    assert "approx_kl" not in block


def test_failed_drill_block_includes_opening_and_last() -> None:
    block = format_failed_drill_block(
        sequence="run(4,8->6,15)[non-passing]",
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
    )
    lines = block.splitlines()
    assert lines[0].startswith("[gridiron] Failed the drill.")
    assert "env=+0.00" in lines[2]
    assert "env=-0.50" in lines[3]
    assert "non-passing" in lines[2]


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
