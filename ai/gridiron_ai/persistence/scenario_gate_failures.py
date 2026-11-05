"""``{stem}.scenario_gate_failures.json`` — scenario → level → imperfect after-eval count."""

from __future__ import annotations

import json
from pathlib import Path
from typing import TypeAlias

from gridiron_ai.api.harness_wire import ScenarioName
from gridiron_ai.persistence.json_io import atomic_write_json

_SUFFIX = ".scenario_gate_failures.json"
_MAX_DIFFICULTY = 5

# Difficulty level keys are decimal strings ``"0"``…``"5"``.
GateFailuresStore: TypeAlias = dict[ScenarioName, dict[str, int]]


def path_for_gate_failures(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final.scenario_gate_failures.json`` beside difficulty JSON."""
    return Path(str(save_stem) + _SUFFIX)


def _clamp(level: int) -> int:
    return max(0, min(_MAX_DIFFICULTY, int(level)))


def load_gate_failures(path: Path) -> GateFailuresStore:
    """Scenario curriculum key → difficulty level (str) → imperfect after-eval count."""
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    failures: GateFailuresStore = {}
    for scenario, by_level in raw.items():
        if not isinstance(by_level, dict):
            continue
        counts: dict[str, int] = {}
        for level_key, count in by_level.items():
            try:
                counts[str(_clamp(int(level_key)))] = max(0, int(count))
            except (TypeError, ValueError):
                continue
        if counts:
            failures[str(scenario)] = counts
    return failures


def save_gate_failures(path: Path, failures: GateFailuresStore) -> None:
    payload = {
        scenario: {str(_clamp(int(level))): max(0, int(count)) for level, count in sorted(by_level.items())}
        for scenario, by_level in sorted(failures.items())
        if by_level
    }
    atomic_write_json(path, payload)
