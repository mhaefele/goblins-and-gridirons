"""``{stem}.scenario_focus.json`` — which drill the live train process is on.

Train writes/clears this sidecar; summarize reads it to mark the active row.
Not curriculum state — a process heartbeat only. Dead ``pid`` → treat as idle.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Literal, TypedDict

from gridiron_ai.train.persistence.json_io import atomic_write_json

_SUFFIX = ".scenario_focus.json"

FocusPhase = Literal["before-eval", "train", "after-eval", "peer-eval", "eval"]


class ScenarioFocusDict(TypedDict):
    """On-disk focus cursor for one long-running train process."""

    scenario: str
    difficulty: int
    phase: FocusPhase
    pid: int


def path_for_focus(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final.scenario_focus.json`` beside difficulty JSON."""
    return Path(str(save_stem) + _SUFFIX)


def write_focus(
    path: Path,
    *,
    scenario: str,
    difficulty: int,
    phase: FocusPhase,
) -> None:
    """Point the cursor at ``scenario`` / ``phase`` for this process."""
    payload: ScenarioFocusDict = {
        "scenario": str(scenario),
        "difficulty": int(difficulty),
        "phase": phase,
        "pid": os.getpid(),
    }
    atomic_write_json(path, payload)


def clear_focus(path: Path) -> None:
    """Remove the cursor (segment finished or process exiting)."""
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def _pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def load_live_focus(path: Path) -> ScenarioFocusDict | None:
    """Focus for a still-running writer, or ``None`` when idle / stale / unreadable."""
    if not path.is_file():
        return None
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(raw, dict):
        return None
    scenario = raw.get("scenario")
    phase = raw.get("phase")
    try:
        difficulty = int(raw.get("difficulty"))
        pid = int(raw.get("pid"))
    except (TypeError, ValueError):
        return None
    if not isinstance(scenario, str) or not scenario:
        return None
    if phase not in ("before-eval", "train", "after-eval", "eval"):
        return None
    if not _pid_alive(pid):
        return None
    return {
        "scenario": scenario,
        "difficulty": difficulty,
        "phase": phase,  # type: ignore[typeddict-item]
        "pid": pid,
    }
