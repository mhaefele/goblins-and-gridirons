"""Head-agreement rates: unmasked argmax vs played factors vs joint legal row."""

from __future__ import annotations

import numpy as np
import torch as th

from gridiron_ai.constants import ACTION_HEAD_SIZES, OBS_KEY_LEGAL_MOVE_COUNT, OBS_KEY_LEGAL_MOVES, OBS_KEY_SOFT_MASK_LEGAL
from gridiron_ai.eval_head_agreement import (
    diagnose_eval_actions,
    diagnose_factor_logits,
    format_head_agreement,
    head_agreement_level,
    merge_head_agreement,
    new_head_agreement,
    record_head_decision,
)


def _logits(**peaks: tuple[int, float]) -> list[np.ndarray]:
    columns = [np.full(size, -10.0, dtype=np.float64) for size in ACTION_HEAD_SIZES]
    for slot_name, (index, value) in peaks.items():
        slot = {"ak": 0, "target": 1, "challenge": 2}[slot_name]
        columns[slot][index] = value
    return columns


def _rows(*triples: tuple[int, int, int]) -> np.ndarray:
    return np.asarray(triples, dtype=np.int64)


def test_unmasked_argmax_matches_played_row() -> None:
    legal = _rows((5, 7, 3), (5, 8, 3))
    decision = diagnose_factor_logits(
        _logits(ak=(5, 3.0), target=(7, 2.0), challenge=(3, 1.0)),
        legal,
        2,
        [5, 7, 3],
    )
    assert decision is not None
    assert decision["triple_legal"] is True
    assert decision["actor_target_legal"] is True
    assert decision["played_target_ok"] is True
    assert decision["joint_matches_played"] is True
    assert decision["unmasked"] == [5, 7, 3]


def test_challenge_argmax_does_not_sink_actor_target_agreement() -> None:
    legal = _rows((5, 7, 3), (5, 8, 3))
    decision = diagnose_factor_logits(
        _logits(ak=(5, 3.0), target=(7, 4.0), challenge=(0, 9.0)),
        legal,
        2,
        [5, 7, 3],
    )
    assert decision is not None
    assert decision["triple_legal"] is False
    assert decision["actor_target_legal"] is True
    assert decision["played_target_ok"] is True
    assert decision["joint"] == [5, 7, 3]
    assert decision["joint_matches_played"] is True


def test_target_argmax_illegal_for_played_actor_and_joint_prefers_other_row() -> None:
    # Play locked actor 10, then a leftover target. The raw target peak sits on
    # actor 20, and that pair wins the sum of logits.
    legal = _rows((10, 1, 3), (10, 2, 3), (20, 5, 3))
    columns = _logits(ak=(10, 5.0), target=(5, 10.0), challenge=(3, 1.0))
    columns[0][20] = 4.0
    decision = diagnose_factor_logits(columns, legal, 3, [10, 1, 3])
    assert decision is not None
    assert decision["unmasked"] == [10, 5, 3]
    assert decision["triple_legal"] is False
    assert decision["actor_target_legal"] is False
    assert decision["played_target_ok"] is False
    assert decision["joint"] == [20, 5, 3]
    assert decision["joint_matches_played"] is False


def test_zero_legal_rows_are_skipped() -> None:
    assert diagnose_factor_logits(_logits(ak=(0, 1.0), target=(0, 1.0), challenge=(0, 1.0)), _rows((0, 0, 0)), 0, [0, 0, 0]) is None


def test_totals_and_format() -> None:
    legal = _rows((5, 7, 3))
    decision = diagnose_factor_logits(
        _logits(ak=(5, 1.0), target=(7, 1.0), challenge=(3, 1.0)),
        legal,
        1,
        [5, 7, 3],
        soft_bias=True,
    )
    assert decision is not None
    totals = new_head_agreement()
    record_head_decision(totals, decision)
    other = new_head_agreement()
    missed = diagnose_factor_logits(
        _logits(ak=(5, 1.0), target=(1, 4.0), challenge=(3, 1.0)),
        legal,
        1,
        [5, 7, 3],
    )
    assert missed is not None
    record_head_decision(other, missed)
    merge_head_agreement(totals, other)
    assert totals["decisions"] == 2
    assert totals["soft_bias"] == 1
    assert totals["played_target_ok"] == 1
    line = format_head_agreement(totals)
    assert line.startswith("heads n=2 ")
    assert "played_tgt=0.50 watch" in line
    assert "joint=1.00 ok" in line
    assert "sm=1/2" in line


def test_head_agreement_level_bands() -> None:
    assert head_agreement_level(0.80) == "ok"
    assert head_agreement_level(0.79) == "watch"
    assert head_agreement_level(0.50) == "watch"
    assert head_agreement_level(0.49) == "bad"


class _Head:
    def __init__(self, logits: th.Tensor) -> None:
        self.logits = logits


class _Dist:
    def __init__(self) -> None:
        self.distributions: list[_Head] = []


class _Policy:
    def __init__(self, columns: list[th.Tensor]) -> None:
        self.action_dist = _Dist()
        self._columns = columns
        self.training = True

    def set_training_mode(self, mode: bool) -> None:
        self.training = mode

    def obs_to_tensor(self, obs: dict) -> tuple[dict, bool]:
        return obs, True

    def extract_features(self, obs: dict) -> dict:
        return obs

    def mlp_extractor(self, features: dict) -> tuple[dict, dict]:
        return features, features

    def _get_action_dist_from_latent(self, latent: dict) -> None:
        del latent
        self.action_dist.distributions = [_Head(column) for column in self._columns]


def test_batch_reads_each_env_and_soft_mask_flag() -> None:
    env0 = _logits(ak=(5, 2.0), target=(7, 2.0), challenge=(3, 1.0))
    env1 = _logits(ak=(8, 2.0), target=(9, 2.0), challenge=(3, 1.0))
    stacked = [th.tensor(np.stack([env0[slot], env1[slot]]), dtype=th.float32) for slot in range(3)]
    legal = np.zeros((2, 2, 3), dtype=np.int64)
    legal[0, 0] = (5, 7, 3)
    legal[1, 0] = (8, 4, 3)
    legal[1, 1] = (8, 1, 3)
    soft = np.zeros((2, 2), dtype=np.int8)
    soft[1, 0] = 1
    obs = {
        OBS_KEY_LEGAL_MOVES: legal,
        OBS_KEY_LEGAL_MOVE_COUNT: np.array([[1], [2]], dtype=np.int32),
        OBS_KEY_SOFT_MASK_LEGAL: soft,
    }
    actions = np.array([[5, 7, 3], [8, 1, 3]], dtype=np.int64)
    policy = _Policy(stacked)
    rows = diagnose_eval_actions(policy, obs, actions)
    assert policy.training is False
    assert rows[0] is not None and rows[1] is not None
    assert rows[0]["played_target_ok"] is True
    assert rows[0]["soft_bias"] is False
    assert rows[1]["unmasked"] == [8, 9, 3]
    assert rows[1]["played_target_ok"] is False
    assert rows[1]["joint"] == [8, 4, 3]
    assert rows[1]["joint_matches_played"] is False
    assert rows[1]["soft_bias"] is True
