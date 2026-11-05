"""``{stem}.scenario_last_eval.json`` — last + previous train-time eval metrics per drill@d."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from gridiron_ai.persistence.json_io import atomic_write_json

_SUFFIX = ".scenario_last_eval.json"
_MAX_DIFFICULTY = 5

# Slim fields persisted for Eval-line / learn-line / summary prior→current deltas.
LAST_EVAL_METRIC_KEYS = (
    "winrate",
    "opening_expected_p_mean",
    "opening_expected_p_best_mean",
    "opening_chosen_p_mean",
    "opening_expected_rank_best_mean",
)

# scenario → level → ``{"current": slim, "prior": slim | None}``
LastEvalStore = dict[str, dict[str, dict[str, Any]]]


def path_for_last_eval(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final.scenario_last_eval.json`` beside difficulty JSON."""
    return Path(str(save_stem) + _SUFFIX)


def _clamp(level: int) -> int:
    return max(0, min(_MAX_DIFFICULTY, int(level)))


def _slim_metrics(raw: Any) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        return None
    slim: dict[str, Any] = {}
    for field in LAST_EVAL_METRIC_KEYS:
        value = raw.get(field)
        if value is None:
            slim[field] = None
        else:
            try:
                slim[field] = float(value)
            except (TypeError, ValueError):
                slim[field] = None
    return slim


def _parse_entry(raw: dict[str, Any]) -> dict[str, Any] | None:
    """Normalize nested ``current``/``prior`` or legacy flat metrics → entry dict."""
    if "current" in raw or "prior" in raw:
        current = _slim_metrics(raw.get("current"))
        if current is None:
            return None
        prior = _slim_metrics(raw["prior"]) if raw.get("prior") is not None else None
        return {"current": current, "prior": prior}
    # Legacy flat object = current only.
    current = _slim_metrics(raw)
    if current is None:
        return None
    return {"current": current, "prior": None}


def load_last_eval(path: Path) -> LastEvalStore:
    """Scenario curriculum key → difficulty level (str) → ``{current, prior}``."""
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    out: LastEvalStore = {}
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
            entry = _parse_entry(metrics)
            if entry is not None:
                levels[level] = entry
        if levels:
            out[str(scenario)] = levels
    return out


def save_last_eval(path: Path, last_eval: LastEvalStore) -> None:
    payload = {
        scenario: {
            str(_clamp(int(level))): {
                "current": {
                    field: entry.get("current", {}).get(field) for field in LAST_EVAL_METRIC_KEYS
                },
                "prior": (
                    {field: entry["prior"].get(field) for field in LAST_EVAL_METRIC_KEYS}
                    if entry.get("prior") is not None
                    else None
                ),
            }
            for level, entry in sorted(by_level.items())
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
