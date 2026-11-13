"""Pitch trunk is Conv2d+GAP (not flatten-all-squares); join width stays 512."""

from __future__ import annotations

import torch as th

from gridiron_ai.constants import (
    NUMBERS_PER_BOARD_CELL,
    OBS_KEYS_GAME_STATE,
    PLAY_HEIGHT,
    PLAY_WIDTH,
)
from gridiron_ai.features import (
    _PITCH_CNN_OUT,
    _PROJECTED_FEATURES,
    _concat_width,
    GridironFeaturesExtractor,
)
from gridiron_ai.obs import dict_observation_space, empty_step_observation


def test_pitch_cnn_out_dim_and_board_features() -> None:
    obs_space = dict_observation_space()
    fe = GridironFeaturesExtractor(obs_space)
    assert fe.features_dim == _PROJECTED_FEATURES
    assert fe.proj.in_features == _concat_width()

    pitch = th.zeros(2, NUMBERS_PER_BOARD_CELL, PLAY_HEIGHT, PLAY_WIDTH)
    pitch_vec = fe._encode_pitch(pitch)
    assert pitch_vec.shape == (2, _PITCH_CNN_OUT)

    step = empty_step_observation()
    # Legal-menu keys are policy-side; extractor only needs game-state Dict boxes.
    out = fe({k: th.as_tensor(step[k]).unsqueeze(0) for k in OBS_KEYS_GAME_STATE})
    assert out.shape == (1, _PROJECTED_FEATURES)
