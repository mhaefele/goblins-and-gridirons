"""``{stem}.scenario_last_eval.json`` — train-time eval metrics per drill@d.

Each slot keeps a rolling ``history`` of slim snapshots (oldest→newest), tagged by
``source``:

- ``before`` / ``after`` — that drill's own sandwich evals (trend / current / prior)
- ``peer`` — probe after another drill trained (``peer`` = that drill's name); kept for
  interference notes, excluded from drill_rate trends

``current`` / ``prior`` always mirror the last two **own-train** snaps.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Literal, NotRequired, TypeAlias, TypedDict

from gridiron_ai.api.harness_wire import ScenarioName
from gridiron_ai.persistence.json_io import atomic_write_json

_SUFFIX = ".scenario_last_eval.json"
_MAX_DIFFICULTY = 5
# Own-train (before/after) snaps kept for trends / current / prior.
LAST_EVAL_HISTORY_MAX = 4
# Peer-probe snaps kept alongside (do not displace the own-train window).
LAST_EVAL_PEER_HISTORY_MAX = 8

LAST_EVAL_SOURCE_BEFORE = "before"
LAST_EVAL_SOURCE_AFTER = "after"
LAST_EVAL_SOURCE_PEER = "peer"
LastEvalSource: TypeAlias = Literal["before", "after", "peer"]
LAST_EVAL_OWN_SOURCES: frozenset[str] = frozenset(
    {LAST_EVAL_SOURCE_BEFORE, LAST_EVAL_SOURCE_AFTER}
)

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
    # Tag: own sandwich vs probe after another drill trained. Legacy snaps omit → treat as after.
    source: NotRequired[LastEvalSource]
    # When source=peer: curriculum name of the drill that just trained.
    peer: NotRequired[ScenarioName | None]


class LastEvalEntryDict(TypedDict):
    """One drill@d slot: rolling history plus last-two own-train mirrors."""

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


def normalize_last_eval_source(raw: object | None) -> LastEvalSource:
    """Legacy untagged snaps count as ``after`` (own-train)."""
    if raw == LAST_EVAL_SOURCE_BEFORE:
        return LAST_EVAL_SOURCE_BEFORE
    if raw == LAST_EVAL_SOURCE_PEER:
        return LAST_EVAL_SOURCE_PEER
    return LAST_EVAL_SOURCE_AFTER


def is_own_train_snap(snap: LastEvalMetricsDict | dict[str, object]) -> bool:
    return normalize_last_eval_source(snap.get("source")) in LAST_EVAL_OWN_SOURCES


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
        "source": LAST_EVAL_SOURCE_AFTER,
        "peer": None,
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
    source = normalize_last_eval_source(raw.get("source"))
    peer_raw = raw.get("peer")
    peer: ScenarioName | None
    if source == LAST_EVAL_SOURCE_PEER and peer_raw:
        peer = str(peer_raw)
    else:
        peer = None
    return LastEvalMetricsDict(
        **values,  # type: ignore[misc]
        source=source,
        peer=peer,
    )


def trim_last_eval_history(
    history: list[LastEvalMetricsDict],
) -> list[LastEvalMetricsDict]:
    """Keep last ``LAST_EVAL_HISTORY_MAX`` own-train + last ``LAST_EVAL_PEER_HISTORY_MAX`` peers."""
    own_kept = 0
    peer_kept = 0
    kept: list[LastEvalMetricsDict] = []
    for snap in reversed(history):
        if is_own_train_snap(snap):
            if own_kept >= LAST_EVAL_HISTORY_MAX:
                continue
            own_kept += 1
        else:
            if peer_kept >= LAST_EVAL_PEER_HISTORY_MAX:
                continue
            peer_kept += 1
        kept.append(snap)
    kept.reverse()
    return kept


def _own_train_snaps(history: list[LastEvalMetricsDict]) -> list[LastEvalMetricsDict]:
    return [snap for snap in history if is_own_train_snap(snap)]


def _entry_from_history(history: list[LastEvalMetricsDict]) -> LastEvalEntryDict | None:
    if not history:
        return None
    trimmed = trim_last_eval_history(history)
    own = _own_train_snaps(trimmed)
    # Prefer own-train mirrors; fall back to last snap when only peers exist (unusual).
    current = own[-1] if own else trimmed[-1]
    prior = own[-2] if len(own) >= 2 else None
    return {
        "history": trimmed,
        "current": current,
        "prior": prior,
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


def _snap_payload(snap: LastEvalMetricsDict) -> dict[str, object]:
    payload: dict[str, object] = {field: snap.get(field) for field in LAST_EVAL_METRIC_KEYS}
    source = normalize_last_eval_source(snap.get("source"))
    payload["source"] = source
    payload["peer"] = snap.get("peer") if source == LAST_EVAL_SOURCE_PEER else None
    return payload


def save_last_eval(path: Path, last_eval: LastEvalStore) -> None:
    payload = {
        scenario: {
            str(_clamp(int(level))): {
                "history": [
                    _snap_payload(snap)
                    for snap in trim_last_eval_history(list(entry.get("history") or []))
                ],
                "current": _snap_payload(entry.get("current") or _empty_slim()),
                "prior": (
                    _snap_payload(entry["prior"])
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


def extract_last_eval_metrics(
    stats: dict[str, object],
    *,
    source: LastEvalSource = LAST_EVAL_SOURCE_AFTER,
    peer: ScenarioName | None = None,
) -> LastEvalMetricsDict:
    """Pull the slim prior-delta fields from a full eval ``stats`` dict."""
    values: dict[str, float | None] = {}
    for field in LAST_EVAL_METRIC_KEYS:
        value = stats.get(field)
        values[field] = None if value is None else float(value)
    normalized = normalize_last_eval_source(source)
    return LastEvalMetricsDict(
        **values,  # type: ignore[misc]
        source=normalized,
        peer=str(peer) if normalized == LAST_EVAL_SOURCE_PEER and peer else None,
    )
