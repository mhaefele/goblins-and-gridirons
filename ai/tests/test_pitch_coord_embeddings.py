"""Pitch x/y are categorical embeddings, not raw magnitudes."""

from __future__ import annotations

import torch as th
from gymnasium import spaces

from gridiron_ai.model.constants import (
    FACTORS_PER_LEGAL_MOVE,
    LEGAL_ROW_SCORE_WIDTH,
    MAX_LEGAL_MOVES,
    OBS_KEY_CHALLENGES,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_SOFT_MASK_LEGAL,
    PLAY_HEIGHT,
    PLAY_WIDTH,
)
from gridiron_ai.model.features import LEGAL_ROW_FEATURE_DIM, GridironFeaturesExtractor
from gridiron_ai.model.legal_row_policy import LegalRowActorCriticPolicy
from gridiron_ai.model.obs import dict_observation_space


def test_legal_row_feature_dim_matches_encode() -> None:
    obs_space = dict_observation_space()
    fe = GridironFeaturesExtractor(obs_space)
    legal = th.full((1, 4, FACTORS_PER_LEGAL_MOVE), -1, dtype=th.int64)
    # kind, actorHasBall, sx, sy, actor, targetHasBall, tx, ty, block, throw%, chal, bonus, f0..2
    legal[0, 0] = th.tensor([0, 0, 2, 5, 1, 1, 3, 7, -1, 0, -1, -1, -1, -1, -1])
    legal[0, 1] = th.tensor([0, 0, 2, 5, 1, 0, 3, 14, -1, 0, -1, -1, -1, -1, -1])
    challenges = th.zeros(1, 3, 2)
    out = fe.encode_legal_rows(legal, challenges)
    assert out.shape == (1, 4, LEGAL_ROW_FEATURE_DIM)


def test_unused_pitch_ids_use_pad_rows() -> None:
    obs_space = dict_observation_space()
    fe = GridironFeaturesExtractor(obs_space)
    xs = th.tensor([-1, PLAY_WIDTH, 0, PLAY_WIDTH - 1])
    ys = th.tensor([-1, PLAY_HEIGHT, 0, PLAY_HEIGHT - 1])
    ex = fe._embed_pitch_x(xs)
    ey = fe._embed_pitch_y(ys)
    assert ex.shape == (4, 8)
    assert ey.shape == (4, 8)
    # SENTINEL and Kotlin unused id share the pad embedding row.
    assert th.allclose(ex[0], ex[1])
    assert th.allclose(ey[0], ey[1])
    assert not th.allclose(ex[0], ex[2])


def test_target_y_magnitude_alone_does_not_order_plain_logits() -> None:
    """Same row except targetY: with fresh embs, higher Y must not systematically win."""
    obs_space = dict_observation_space()
    policy = LegalRowActorCriticPolicy(
        obs_space,
        spaces.Discrete(LEGAL_ROW_SCORE_WIDTH),
        lr_schedule=lambda _: 1e-4,
        features_extractor_class=GridironFeaturesExtractor,
        net_arch=dict(pi=[32], vf=[32]),
    )
    policy.soft_mask_strength = 0.0
    policy.eval()

    wins_high = 0
    trials = 40
    for seed in range(trials):
        th.manual_seed(seed)
        # Re-init pitch embs so each trial is a fresh random table (not trained weights).
        fe = policy.features_extractor
        assert isinstance(fe, GridironFeaturesExtractor)
        nn_init = th.nn.init
        nn_init.normal_(fe.pitch_x_emb.weight)
        nn_init.normal_(fe.pitch_y_emb.weight)

        legal = th.full((1, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=th.int64)
        low = th.tensor([0, 0, 5, 2, 1, 0, 5, 2, -1, 0, -1, -1, -1, -1, -1])
        high = th.tensor([0, 0, 5, 2, 1, 0, 5, 14, -1, 0, -1, -1, -1, -1, -1])
        legal[0, 0] = low
        legal[0, 1] = high
        challenges = th.zeros(1, 3, 2)
        soft = th.zeros(1, MAX_LEGAL_MOVES, dtype=th.int8)
        counts = th.tensor([[2]], dtype=th.int32)
        obs = {
            OBS_KEY_LEGAL_MOVES: legal,
            OBS_KEY_LEGAL_MOVE_COUNT: counts,
            OBS_KEY_CHALLENGES: challenges,
            OBS_KEY_SOFT_MASK_LEGAL: soft,
        }
        logits = policy._score_legal_rows(obs)
        if float(logits[0, 1].detach()) > float(logits[0, 0].detach()):
            wins_high += 1
    # Under random Y embeddings, high-Y should not win ~always (old raw-Y bias did).
    assert 5 <= wins_high <= 35, f"high-Y won {wins_high}/{trials} — magnitude leak?"
