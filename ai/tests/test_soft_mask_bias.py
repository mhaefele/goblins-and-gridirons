"""Eval must not feed soft-mask flags into the policy obs channel."""

from __future__ import annotations

from unittest.mock import MagicMock

from gridiron_ai.api.env import GridironEnv


def test_soft_mask_bias_off_zeros_obs_flags() -> None:
    env = GridironEnv(client=MagicMock(), soft_mask_bias=False)
    env._current_step_dto = {
        "legalRows": [
            {"action": {"id": "a"}, "softMaskLegal": True, "passingLegal": True},
            {"action": {"id": "b"}, "softMaskLegal": False, "passingLegal": False},
        ],
    }
    assert env._soft_mask_legal == [False, False]
    assert env._passing_legal == [True, False]


def test_soft_mask_bias_on_passes_server_flags() -> None:
    env = GridironEnv(client=MagicMock(), soft_mask_bias=True)
    env._current_step_dto = {
        "legalRows": [
            {"action": {"id": "a"}, "softMaskLegal": True, "passingLegal": True},
            {"action": {"id": "b"}, "softMaskLegal": False, "passingLegal": False},
        ],
    }
    assert env._soft_mask_legal == [True, False]
