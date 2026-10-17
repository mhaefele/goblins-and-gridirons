"""Computer opponent kind + ONNX profile validation (env / reward catalog)."""

from __future__ import annotations

import pytest

from gridiron_ai.env import (
    COMPUTER_KIND_HEURISTIC,
    COMPUTER_KIND_LOOKAHEAD,
    COMPUTER_KIND_ONNX,
    opponent_metrics_label,
    validate_computer_opponent,
)
from gridiron_ai.reward import FULL_GAME_ONNX_OPPONENT_PROFILES, REWARD_PROFILES


def test_full_game_onnx_opponents_are_reward_profiles() -> None:
    assert FULL_GAME_ONNX_OPPONENT_PROFILES == ("general", "defense")
    assert set(FULL_GAME_ONNX_OPPONENT_PROFILES) <= set(REWARD_PROFILES)


def test_validate_built_in_kinds() -> None:
    validate_computer_opponent(COMPUTER_KIND_LOOKAHEAD, None)
    validate_computer_opponent(COMPUTER_KIND_HEURISTIC, None)


def test_validate_onnx_requires_profile() -> None:
    with pytest.raises(ValueError, match="onnx_profile"):
        validate_computer_opponent(COMPUTER_KIND_ONNX, None)
    validate_computer_opponent(COMPUTER_KIND_ONNX, "general")
    validate_computer_opponent(COMPUTER_KIND_ONNX, "defense")


def test_validate_rejects_profile_on_built_in() -> None:
    with pytest.raises(ValueError, match="only valid for ONNX"):
        validate_computer_opponent(COMPUTER_KIND_HEURISTIC, "general")


def test_opponent_metrics_label() -> None:
    assert opponent_metrics_label(COMPUTER_KIND_LOOKAHEAD, None) == "lookahead"
    assert opponent_metrics_label(COMPUTER_KIND_ONNX, "general") == "onnx-general"
    assert opponent_metrics_label(COMPUTER_KIND_ONNX, "defense") == "onnx-defense"
