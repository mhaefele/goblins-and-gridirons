"""``scenario_focus`` sidecar — train heartbeat for summarize row marker."""

from __future__ import annotations

import json
import os
from pathlib import Path

from gridiron_ai.train.persistence.scenario_focus import (
    clear_focus,
    load_live_focus,
    path_for_focus,
    write_focus,
)


def test_write_load_clear_roundtrip(tmp_path: Path) -> None:
    path = path_for_focus(tmp_path / "maskable_ppo_selfplay_final")
    assert path.name.endswith(".scenario_focus.json")
    write_focus(path, scenario="TD_RUN", difficulty=2, phase="train")
    focus = load_live_focus(path)
    assert focus is not None
    assert focus["scenario"] == "TD_RUN"
    assert focus["difficulty"] == 2
    assert focus["phase"] == "train"
    assert focus["pid"] == os.getpid()
    clear_focus(path)
    assert load_live_focus(path) is None
    clear_focus(path)  # idempotent


def test_dead_pid_is_not_live(tmp_path: Path) -> None:
    path = path_for_focus(tmp_path / "stem")
    path.write_text(
        json.dumps(
            {
                "scenario": "MARKED_RUN",
                "difficulty": 0,
                "phase": "after-eval",
                "pid": 2**31 - 2,
            }
        )
        + "\n",
        encoding="utf-8",
    )
    assert load_live_focus(path) is None


def test_corrupt_or_incomplete_is_idle(tmp_path: Path) -> None:
    path = path_for_focus(tmp_path / "stem")
    path.write_text("{not json", encoding="utf-8")
    assert load_live_focus(path) is None
    path.write_text('{"scenario": "TD_RUN", "pid": 1}\n', encoding="utf-8")
    assert load_live_focus(path) is None
