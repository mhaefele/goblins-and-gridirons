"""Join-score only encodes through this batch's busiest legal menu."""

from __future__ import annotations

import torch as th
from gymnasium import spaces

from gridiron_ai.constants import (
    FACTORS_PER_LEGAL_MOVE,
    LEGAL_ROW_SCORE_WIDTH,
    MAX_LEGAL_MOVES,
    OBS_KEY_CHALLENGES,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_SOFT_MASK_LEGAL,
)
from gridiron_ai.features import GridironFeaturesExtractor
from gridiron_ai.legal_row_policy import LegalRowActorCriticPolicy
from gridiron_ai.obs import dict_observation_space


def test_score_legal_rows_encodes_only_batch_max_count() -> None:
    obs_space = dict_observation_space()
    policy = LegalRowActorCriticPolicy(
        obs_space,
        spaces.Discrete(LEGAL_ROW_SCORE_WIDTH),
        lr_schedule=lambda _: 1e-4,
        features_extractor_class=GridironFeaturesExtractor,
        net_arch=dict(pi=[8], vf=[8]),
    )
    fe = policy.features_extractor
    assert isinstance(fe, GridironFeaturesExtractor)
    seen: dict[str, int] = {}
    orig = fe.encode_legal_rows

    def _spy(legal_moves: th.Tensor, challenges: th.Tensor) -> th.Tensor:
        seen["rows"] = int(legal_moves.shape[1])
        return orig(legal_moves, challenges)

    fe.encode_legal_rows = _spy  # type: ignore[method-assign]

    batch = 2
    legal = th.full((batch, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=th.int64)
    legal[0, :80] = 0
    legal[1, :150] = 0
    counts = th.tensor([[80], [150]], dtype=th.int32)
    challenges = th.zeros(batch, 3, 2)
    soft = th.zeros(batch, MAX_LEGAL_MOVES, dtype=th.int8)
    obs = {
        OBS_KEY_LEGAL_MOVES: legal,
        OBS_KEY_LEGAL_MOVE_COUNT: counts,
        OBS_KEY_CHALLENGES: challenges,
        OBS_KEY_SOFT_MASK_LEGAL: soft,
    }
    logits = policy._score_legal_rows(obs)

    assert seen["rows"] == 150
    assert logits.shape == (batch, LEGAL_ROW_SCORE_WIDTH)
    assert th.all(logits[0, 80:] <= -1.0e7)
    assert th.all(logits[1, 150:] <= -1.0e7)
    assert float(logits[0, 0].detach()) > -1.0e7
    assert float(logits[1, 0].detach()) > -1.0e7
