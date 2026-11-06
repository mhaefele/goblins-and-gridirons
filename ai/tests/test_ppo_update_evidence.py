"""Unit tests for PASS-step advantage summarization (PPO update evidence)."""

from __future__ import annotations

import numpy as np

from gridiron_ai.tb_callback import summarize_pass_step_advantages


def test_summarize_pass_step_advantages_score_and_prior() -> None:
    advantages = np.array([[1.0, 9.0], [2.0, 8.0], [3.0, 7.0]], dtype=np.float32)
    rewards = np.array([[0.0, 0.1], [0.0, 0.2], [4.5, 0.3]], dtype=np.float32)
    episode_starts = np.array([[True, True], [False, False], [False, False]])
    # Env 0: PASS at t=2 → prior t=1; env 1 ignored.
    stats = summarize_pass_step_advantages(
        advantages=advantages,
        rewards=rewards,
        episode_starts=episode_starts,
        pass_terminals=[(2, 0)],
    )
    assert stats["pass_n"] == 1
    assert stats["score_adv_mean"] == 3.0
    assert stats["score_rew_mean"] == 4.5
    assert stats["prior_n"] == 1
    assert stats["prior_adv_mean"] == 2.0
    assert stats["prior_rew_mean"] == 0.0


def test_summarize_pass_step_advantages_td_then_pickup_shape() -> None:
    """Typical LBR success: pickup rew≈0 then TD rew≫0 — prior is pickup, score is TD."""
    advantages = np.array([[0.5], [1.0]], dtype=np.float32)
    rewards = np.array([[0.0], [2.5]], dtype=np.float32)
    episode_starts = np.array([[True], [False]])
    stats = summarize_pass_step_advantages(
        advantages=advantages,
        rewards=rewards,
        episode_starts=episode_starts,
        pass_terminals=[(1, 0)],
    )
    assert stats["prior_rew_mean"] == 0.0
    assert stats["score_rew_mean"] == 2.5
    assert stats["prior_adv_mean"] == 0.5
    assert stats["score_adv_mean"] == 1.0


def test_summarize_pass_step_advantages_skips_prior_on_episode_start() -> None:
    advantages = np.array([[5.0]], dtype=np.float32)
    rewards = np.array([[4.5]], dtype=np.float32)
    episode_starts = np.array([[True]])
    stats = summarize_pass_step_advantages(
        advantages=advantages,
        rewards=rewards,
        episode_starts=episode_starts,
        pass_terminals=[(0, 0)],
    )
    assert stats["pass_n"] == 1
    assert stats["prior_n"] == 0
    assert stats["score_adv_mean"] == 5.0
