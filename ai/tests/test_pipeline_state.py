"""Saved pipeline FSM round-trip + rewinds that mirror destructive wipes."""

from __future__ import annotations

from pathlib import Path

import pytest

from gridiron_ai.pipeline_state import (
    PipelineState,
    ProfileSplit,
    ProfileState,
    load_pipeline,
    pipeline_path,
    rewind_pipeline,
    save_pipeline,
)

_FORKED = PipelineState(
    stage="profiles",
    split=ProfileSplit(at=1.0, scenario_b_steps=5_000_000),
    profiles={"baseline": ProfileState(stage="games", forced=True), "td": ProfileState()},
)


@pytest.fixture(autouse=True)
def _root(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.setattr("gridiron_ai.pipeline_state.CHECKPOINT_DIR", tmp_path)


def test_missing_file_is_fresh_pipeline() -> None:
    assert load_pipeline() == PipelineState()
    assert not pipeline_path().is_file()


def test_save_load_round_trip() -> None:
    save_pipeline(_FORKED)
    assert load_pipeline() == _FORKED
    assert load_pipeline().plays_games("baseline")
    assert not load_pipeline().plays_games("td")
    assert not load_pipeline().plays_games("general")


def test_rewind_one_profile_keeps_split_and_others() -> None:
    save_pipeline(_FORKED)
    state = rewind_pipeline("profiles", profile="td")
    assert state.split == _FORKED.split
    assert list(state.profiles) == ["baseline"]
    assert load_pipeline() == state


def test_rewind_all_profiles_clears_split() -> None:
    save_pipeline(_FORKED)
    assert rewind_pipeline("profiles") == PipelineState(stage="profiles")


def test_rewind_scenario_b_keeps_scenario_a_passed() -> None:
    save_pipeline(_FORKED)
    assert rewind_pipeline("scenario_b") == PipelineState(stage="scenario_b")
    save_pipeline(PipelineState())
    assert rewind_pipeline("scenario_b") == PipelineState()


def test_rewind_scenario_a_is_fresh() -> None:
    save_pipeline(_FORKED)
    assert rewind_pipeline("scenario_a") == PipelineState()
