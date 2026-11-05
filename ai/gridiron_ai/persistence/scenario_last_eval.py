"""``{stem}.scenario_last_eval.json`` — last train-time eval metrics per drill@d."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from gridiron_ai.persistence.json_io import atomic_write_json

_SUFFIX = ".scenario_last_eval.json"
_MAX_DIFFICULTY = 5

# Slim fields persisted for Eval-line / learn-line prior→current deltas.
LAST_EVAL_METRIC_KEYS = (
    "winrate",
    "opening_expected_p_mean",
    "opening_expected_p_best_mean",
    "opening_chosen_p_mean",
    "opening_expected_rank_best_mean",
)


def path_for_last_eval(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final.scenario_last_eval.json`` beside difficulty JSON."""
    return Path(str(save_stem) + _SUFFIX)


def _clamp(level: int) -> int:
    return max(0, min(_MAX_DIFFICULTY, int(level)))


def load_last_eval(path: Path) -> dict[str, dict[str, dict[str, Any]]]:
    """Scenario curriculum key → difficulty level (str) → slim last-eval metrics."""
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    out: dict[str, dict[str, dict[str, Any]]] = {}
    for scenario, by_level in raw.items():
        if not isinstance(by_level, dict):
            continue
        levels: dict[str, dict[str, Any]] = {}
        for level_key, metrics in by_level.items():
            if not isinstance(metrics, dict):
                continue
            try:
                level = str(_clamp(int(level_key)))
            except (TypeError, ValueError):
                continue
            slim: dict[str, Any] = {}
            for field in LAST_EVAL_METRIC_KEYS:
                value = metrics.get(field)
                if value is None:
                    slim[field] = None
                else:
                    try:
                        slim[field] = float(value)
                    except (TypeError, ValueError):
                        slim[field] = None
            levels[level] = slim
        if levels:
            out[str(scenario)] = levels
    return out


def save_last_eval(path: Path, last_eval: dict[str, dict[str, dict[str, Any]]]) -> None:
    payload = {
        scenario: {
            str(_clamp(int(level))): {
                field: metrics.get(field) for field in LAST_EVAL_METRIC_KEYS
            }
            for level, metrics in sorted(by_level.items())
        }
        for scenario, by_level in sorted(last_eval.items())
        if by_level
    }
    atomic_write_json(path, payload)


def extract_last_eval_metrics(stats: dict[str, Any]) -> dict[str, Any]:
    """Pull the slim prior-delta fields from a full eval ``stats`` dict."""
    slim: dict[str, Any] = {}
    for field in LAST_EVAL_METRIC_KEYS:
        value = stats.get(field)
        if value is None:
            slim[field] = None
        else:
            slim[field] = float(value)
    return slim
