"""``{stem}.scenario_drill_stats.json`` — per-phase ``cycles`` / ``d5_demotes``."""

from __future__ import annotations

import json
from pathlib import Path

from gridiron_ai.persistence.json_io import atomic_write_json

_SUFFIX = ".scenario_drill_stats.json"


def path_for_drill_stats(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final.scenario_drill_stats.json`` beside difficulty JSON."""
    return Path(str(save_stem) + _SUFFIX)


def load_drill_stats(path: Path) -> dict[str, dict[str, int]]:
    """Scenario curriculum key → ``cycles`` / ``d5_demotes`` counters."""
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    stats: dict[str, dict[str, int]] = {}
    for scenario, counters in raw.items():
        if not isinstance(counters, dict):
            continue
        cycles = max(0, int(counters.get("cycles", 0) or 0))
        demotes = max(0, int(counters.get("d5_demotes", 0) or 0))
        if cycles or demotes:
            stats[str(scenario)] = {"cycles": cycles, "d5_demotes": demotes}
    return stats


def save_drill_stats(path: Path, drill_stats: dict[str, dict[str, int]]) -> None:
    payload = {
        scenario: {
            "cycles": max(0, int(counters.get("cycles", 0))),
            "d5_demotes": max(0, int(counters.get("d5_demotes", 0))),
        }
        for scenario, counters in sorted(drill_stats.items())
        if int(counters.get("cycles", 0) or 0) or int(counters.get("d5_demotes", 0) or 0)
    }
    atomic_write_json(path, payload)
