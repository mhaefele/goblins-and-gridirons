"""Pitch CNN keeps a spatial map; policy consumes map+non_pitch; value GAPs."""

from __future__ import annotations

import torch as th

from gridiron_ai.model.constants import (
    NUMBERS_PER_BOARD_CELL,
    OBS_KEYS_GAME_STATE,
    PLAY_HEIGHT,
    PLAY_WIDTH,
)
from gridiron_ai.model.features import (
    JOIN_CELL_FEAT,
    _PITCH_CNN_OUT,
    _PROJECTED_FEATURES,
    _concat_width,
    _non_pitch_width,
    GridironFeaturesExtractor,
)
from gridiron_ai.model.obs import dict_observation_space, empty_step_observation


def test_pitch_cnn_out_dim_and_board_features() -> None:
    obs_space = dict_observation_space()
    fe = GridironFeaturesExtractor(obs_space)
    assert fe.features_dim == _PROJECTED_FEATURES
    assert fe.proj.in_features == _concat_width()

    pitch = th.zeros(2, NUMBERS_PER_BOARD_CELL, PLAY_HEIGHT, PLAY_WIDTH)
    pitch_vec = fe._encode_pitch(pitch)
    assert pitch_vec.shape == (2, _PITCH_CNN_OUT)
    assert fe._pitch_feature_map is not None
    assert fe._pitch_feature_map.shape == (2, JOIN_CELL_FEAT, PLAY_HEIGHT, PLAY_WIDTH)

    step = empty_step_observation()
    out = fe({k: th.as_tensor(step[k]).unsqueeze(0) for k in OBS_KEYS_GAME_STATE})
    assert out.shape == (1, _PROJECTED_FEATURES)
    pitch_map, non_pitch = fe.consume_join_board()
    assert pitch_map.shape == (1, JOIN_CELL_FEAT, PLAY_HEIGHT, PLAY_WIDTH)
    assert non_pitch.shape == (1, _non_pitch_width())


def test_join_board_requires_forward() -> None:
    fe = GridironFeaturesExtractor(dict_observation_space())
    try:
        fe.consume_join_board()
    except RuntimeError as exc:
        assert "join board missing" in str(exc)
        return
    raise AssertionError("expected RuntimeError")
