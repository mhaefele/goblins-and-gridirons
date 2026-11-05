"""``{stem}.scenario_difficulty.json`` — curriculum key → difficulty 0–5."""

from __future__ import annotations

import json
from pathlib import Path
from typing import TypeAlias

from gridiron_ai.harness_wire import ScenarioName
from gridiron_ai.persistence.json_io import atomic_write_json
from gridiron_ai.scenarios import require_catalog

_SUFFIX = ".scenario_difficulty.json"
# Keep in sync with ``scenario_curriculum.MAX_DIFFICULTY``.
_MAX_DIFFICULTY = 5

ScenarioDifficultyLevels: TypeAlias = dict[ScenarioName, int]


def path_for_stem(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final`` → ``…/maskable_ppo_selfplay_final.scenario_difficulty.json``."""
    return Path(str(save_stem) + _SUFFIX)


def _clamp(level: int) -> int:
    return max(0, min(_MAX_DIFFICULTY, int(level)))


def load_levels(path: Path) -> ScenarioDifficultyLevels:
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    levels: ScenarioDifficultyLevels = {}
    for key, value in raw.items():
        try:
            levels[str(key)] = _clamp(int(value))
        except (TypeError, ValueError):
            continue
    return levels


def save_levels(path: Path, levels: ScenarioDifficultyLevels) -> None:
    # Drop proxy sources — difficulty is read from the proxy target.
    proxies = require_catalog().proxy_sources()
    payload = {
        name: _clamp(level)
        for name, level in sorted(levels.items())
        if name not in proxies
    }
    atomic_write_json(path, payload)
