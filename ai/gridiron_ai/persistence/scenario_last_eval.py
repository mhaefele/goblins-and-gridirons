"""``{stem}.scenario_last_eval.json`` — last train-time eval metrics per drill@d.

Each slot keeps up to ``LAST_EVAL_HISTORY_MAX`` slim snapshots (oldest→newest). ``current`` /
``prior`` are the last two entries (dual-written for readers that only need a delta).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import TypeAlias, TypedDict

from gridiron_ai.api.harness_wire import ScenarioName
from gridiron_ai.persistence.json_io import atomic_write_json

_SUFFIX = ".scenario_last_eval.json"
_MAX_DIFFICULTY = 5
LAST_EVAL_HISTORY_MAX = 4

# Slim fields persisted for Eval-line / learn-line / summary prior→current deltas.
LAST_EVAL_METRIC_KEYS = (
    "winrate",
    "opening_expected_p_mean",
    "opening_expected_p_best_mean",
    "opening_chosen_p_mean",
    "opening_expected_rank_best_mean",
    "opening_expected_legal_count_mean",
    "opening_expected_n_preferred_mean",
    "opening_expected_logit_gap_mean",
    # ball_swap_probe (eval_ball_swap_probe.py) — optional; None when unused
    "opening_ball_swap_expected_p_best_mean",
    "opening_ball_swap_expected_rank_best_mean",
    # Last PPO update health from the train segment before this after-eval (None on before-eval).
    "approx_kl",
)


class LastEvalMetricsDict(TypedDict):
    """Slim prior-delta fields from a full eval ``stats`` dict."""

    winrate: float | None
    opening_expected_p_mean: float | None
    opening_expected_p_best_mean: float | None
    opening_chosen_p_mean: float | None
    opening_expected_rank_best_mean: float | None
    opening_expected_legal_count_mean: float | None
    opening_expected_n_preferred_mean: float | None
    opening_expected_logit_gap_mean: float | None
    opening_ball_swap_expected_p_best_mean: float | None
    opening_ball_swap_expected_rank_best_mean: float | None
    approx_kl: float | None


class LastEvalEntryDict(TypedDict):
    """One drill@d slot: rolling history plus last-two mirrors."""

    history: list[LastEvalMetricsDict]
    current: LastEvalMetricsDict
    prior: LastEvalMetricsDict | None


# scenario → level (str) → entry
LastEvalStore: TypeAlias = dict[ScenarioName, dict[str, LastEvalEntryDict]]


def path_for_last_eval(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final.scenario_last_eval.json`` beside difficulty JSON."""
    return Path(str(save_stem) + _SUFFIX)


def _clamp(level: int) -> int:
    return max(0, min(_MAX_DIFFICULTY, int(level)))


def _empty_slim() -> LastEvalMetricsDict:
    return {
        "winrate": None,
        "opening_expected_p_mean": None,
        "opening_expected_p_best_mean": None,
        "opening_chosen_p_mean": None,
        "opening_expected_rank_best_mean": None,
        "opening_expected_legal_count_mean": None,
        "opening_expected_n_preferred_mean": None,
        "opening_expected_logit_gap_mean": None,
        "opening_ball_swap_expected_p_best_mean": None,
        "opening_ball_swap_expected_rank_best_mean": None,
        "approx_kl": None,
    }


def _slim_metrics(raw: object) -> LastEvalMetricsDict | None:
    if not isinstance(raw, dict):
        return None
    values: dict[str, float | None] = {}
    for field in LAST_EVAL_METRIC_KEYS:
        value = raw.get(field)
        if value is None:
            values[field] = None
        else:
            try:
                values[field] = float(value)
            except (TypeError, ValueError):
                values[field] = None
    return LastEvalMetricsDict(**values)  # type: ignore[misc]


def _entry_from_history(history: list[LastEvalMetricsDict]) -> LastEvalEntryDict | None:
    if not history:
        return None
    trimmed = history[-LAST_EVAL_HISTORY_MAX:]
    return {
        "history": trimmed,
        "current": trimmed[-1],
        "prior": trimmed[-2] if len(trimmed) >= 2 else None,
    }


def _parse_entry(raw: dict[str, object]) -> LastEvalEntryDict | None:
    """Normalize ``history`` / nested ``current``/``prior`` / legacy flat → entry dict."""
    if isinstance(raw.get("history"), list):
        history = [m for m in (_slim_metrics(item) for item in raw["history"]) if m is not None]
        return _entry_from_history(history)
    if "current" in raw or "prior" in raw:
        history: list[LastEvalMetricsDict] = []
        prior = _slim_metrics(raw["prior"]) if raw.get("prior") is not None else None
        current = _slim_metrics(raw.get("current"))
        if prior is not None:
            history.append(prior)
        if current is not None:
            history.append(current)
        return _entry_from_history(history)
    # Legacy flat object = current only.
    current = _slim_metrics(raw)
    if current is None:
        return None
    return _entry_from_history([current])


def load_last_eval(path: Path) -> LastEvalStore:
    """Scenario curriculum key → difficulty level (str) → history entry."""
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    out: LastEvalStore = {}
    for scenario, by_level in raw.items():
        if not isinstance(by_level, dict):
            continue
        levels: dict[str, LastEvalEntryDict] = {}
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
                "history": [
                    {field: snap.get(field) for field in LAST_EVAL_METRIC_KEYS}
                    for snap in (entry.get("history") or [])[-LAST_EVAL_HISTORY_MAX:]
                ],
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


def extract_last_eval_metrics(stats: dict[str, object]) -> LastEvalMetricsDict:
    """Pull the slim prior-delta fields from a full eval ``stats`` dict."""
    values: dict[str, float | None] = {}
    for field in LAST_EVAL_METRIC_KEYS:
        value = stats.get(field)
        values[field] = None if value is None else float(value)
    return LastEvalMetricsDict(**values)  # type: ignore[misc]
