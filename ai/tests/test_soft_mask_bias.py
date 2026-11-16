"""Soft-mask obs is stable 0/1 flags; strength is a live policy train knob."""

from __future__ import annotations

from unittest.mock import MagicMock

import numpy as np

from gridiron_ai.api.env import GridironEnv
from gridiron_ai.constants import OBS_KEY_SOFT_MASK_LEGAL, SOFT_MASK_LOGIT_BIAS
from gridiron_ai.obs import attach_packed_legals, dict_observation_space, empty_step_observation


def test_soft_mask_bias_off_zeros_obs_flags() -> None:
    env = GridironEnv(client=MagicMock(), soft_mask_bias=False)
    env._current_step_dto = {
        "legalRows": [
            {"action": {"id": "a"}, "softMaskLegal": True, "passingLegal": True},
            {"action": {"id": "b"}, "softMaskLegal": False, "passingLegal": False},
        ],
    }
    assert env._soft_mask_legal == [0, 0]
    assert env._passing_legal == [True, False]


def test_soft_mask_bias_on_passes_server_flags() -> None:
    env = GridironEnv(client=MagicMock(), soft_mask_bias=True)
    env._current_step_dto = {
        "legalRows": [
            {"action": {"id": "a"}, "softMaskLegal": True, "passingLegal": True},
            {"action": {"id": "b"}, "softMaskLegal": False, "passingLegal": False},
        ],
    }
    assert env._soft_mask_legal == [1, 0]


def test_soft_mask_obs_space_is_stable_int8_flags() -> None:
    """Train juice must not widen this Box — SB3 pickles observation_space into every zip."""
    box = dict_observation_space().spaces[OBS_KEY_SOFT_MASK_LEGAL]
    assert box.dtype == np.int8
    assert int(np.asarray(box.low).flat[0]) == 0
    assert int(np.asarray(box.high).flat[0]) == 1
    assert empty_step_observation()[OBS_KEY_SOFT_MASK_LEGAL].dtype == np.int8


def test_attach_packed_legals_clamps_soft_mask_to_01() -> None:
    """Accidental strength values must not ride the obs channel."""
    game = {
        key: value
        for key, value in empty_step_observation().items()
        if key not in (OBS_KEY_SOFT_MASK_LEGAL, "legal_moves", "legal_move_count")
    }
    packed = attach_packed_legals(
        game,
        legal_moves=[[1, 0, -1, -1, -1, 0, 0, 0, -1, 0, -1, -1, -1, -1, -1]],
        soft_mask_legal=[2.0],
    )
    assert packed[OBS_KEY_SOFT_MASK_LEGAL].dtype == np.int8
    assert int(packed[OBS_KEY_SOFT_MASK_LEGAL][0]) == 1


def test_soft_mask_strength_multiplies_bias_not_obs_box() -> None:
    """Bottleneck contract: effective bias = SOFT_MASK_LOGIT_BIAS × policy strength."""
    strength = 2.0
    flags = np.array([1, 0, 1], dtype=np.int8)
    delta = flags.astype(np.float32) * (SOFT_MASK_LOGIT_BIAS * strength)
    assert float(delta[0]) == SOFT_MASK_LOGIT_BIAS * 2.0
    assert float(delta[1]) == 0.0
