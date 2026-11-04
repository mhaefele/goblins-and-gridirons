"""Episode action stats and shared aggregation (train + eval).

Owner: ``info["bb_episode"]`` shape + ``aggregate_episodes`` used by TensorBoard
and eval CLI. Does not define rewards. See ``ai/OWNERSHIP.md``.

Counts sampled wire kinds (``kind_count`` = per-episode means for TB;
``kind_totals`` / ``kind_breakdown`` = session raw counts as scenarios / games / total).
Scenario runs may carry ``first_kind``. Ball rates sample once per ``turnNumber`` at
turn start; ``ball_gained_*`` count !holds→holds edges.
"""

from __future__ import annotations

import json
from collections import Counter
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from gridiron_ai.expert_stats import (
    BUCKET_GAMES,
    BUCKET_SCENARIOS,
    BUCKET_TOTAL,
    KIND_BUCKETS,
    add_kind_to_breakdown,
    breakdown_to_jsonable,
    empty_kind_breakdown,
    format_kind_breakdown,
    merge_kind_breakdown,
    write_kind_stats,
)
from gridiron_ai.paths import KIND_TOTALS_JSON_NAME, KIND_TOTALS_TXT_NAME, RUNS_DIR
from gridiron_ai.reward import (
    coordinate_xy,
    endzone_proximity,
    them_ball_carrier,
    them_holds_ball,
    us_ball_carrier,
    us_holds_ball,
)

# Perspective row past midfield (endzone_proximity > 0.5 → opponent half; < 0.5 → ours).
_OPP_HALF_PROXIMITY = 0.5

_BALL_RATE_KEYS = (
    "ball_rate_us",
    "ball_rate_them",
    "ball_opp_half_rate_us",
    "ball_opp_half_rate_them",
    "ball_proximity_us",
)


def total_score(observation_from_server: dict[str, Any]) -> int:
    return int(observation_from_server["scoreUs"]) + int(observation_from_server["scoreThem"])


def _mean(values: list[float]) -> float:
    return (sum(values) / len(values)) if values else 0.0


def _empty_aggregate() -> dict[str, Any]:
    return {
        "episodes": 0,
        "turns": 0.0,
        "total_actions": 0.0,
        "challenge_cards": 0.0,
        "challenge_cards_them": 0.0,
        "touchdowns": 0.0,
        "touchdowns_them": 0.0,
        "total_score": 0.0,
        "score_us": 0.0,
        "score_them": 0.0,
        "score_diff": 0.0,
        "truncation_rate": 0.0,
        "submit_error_rate": 0.0,
        "submit_avg_ms": 0.0,
        "legal_actions_avg": 0.0,
        "wins": 0,
        "losses": 0,
        "draws": 0,
        "winrate": 0.0,
        "turn_start_samples": 0.0,
        **{k: 0.0 for k in _BALL_RATE_KEYS},
        "ball_gained_us": 0.0,
        "ball_gained_them": 0.0,
        "kind_count": {},
        "kind_totals": {},
        "kind_breakdown": {bucket: {} for bucket in KIND_BUCKETS},
        "first_kind_count": {},
        "scenario_difficulty": None,
        "first_action_legal_count": None,
    }


def format_metrics_line(label: str, stats: dict[str, Any]) -> str:
    """One-line train/eval summary for stdout (TensorBoard callback + CLI)."""
    kinds = " ".join(
        f"{k}={v:.2f}" for k, v in sorted(stats["kind_count"].items()) if v
    )
    difficulty = stats.get("scenario_difficulty")
    difficulty_bit = f" difficulty={difficulty}" if difficulty is not None else ""
    first_legals = stats.get("first_action_legal_count")
    first_legals_bit = (
        f" first_legals={first_legals:.1f}" if first_legals is not None else ""
    )
    return (
        f"{label}: episodes={stats['episodes']} "
        f"winrate={stats['winrate']:.3f} (W{stats['wins']}/L{stats['losses']}/D{stats['draws']}) "
        f"score={stats['score_us']:.1f}-{stats['score_them']:.1f} (diff={stats['score_diff']:.2f}) "
        f"td={stats['touchdowns']:.2f}/{stats['touchdowns_them']:.2f} "
        f"cards={stats['challenge_cards']:.2f}/{stats['challenge_cards_them']:.2f} "
        f"ball={stats['ball_rate_us']:.2f}/{stats['ball_rate_them']:.2f} "
        f"opp_half={stats['ball_opp_half_rate_us']:.2f}/{stats['ball_opp_half_rate_them']:.2f} "
        f"prox={stats['ball_proximity_us']:.2f} "
        f"gained={stats['ball_gained_us']:.2f}/{stats['ball_gained_them']:.2f} "
        f"kinds[{kinds}] "
        f"trunc={stats['truncation_rate']:.3f} submit_err={stats['submit_error_rate']:.3f} "
        f"submit_ms={stats['submit_avg_ms']:.1f}"
        f"{difficulty_bit}{first_legals_bit}"
    )


def print_metrics(label: str, stats: dict[str, Any]) -> None:
    print(format_metrics_line(label, stats), flush=True)


def print_kind_mix(label: str, stats: dict[str, Any]) -> None:
    """Session chosen-move kinds as scenarios / games / total (same shape as expert pack stats)."""
    breakdown = stats.get("kind_breakdown")
    if not breakdown:
        totals = stats.get("kind_totals") or {}
        if not totals:
            return
        breakdown = {BUCKET_TOTAL: totals}
    if not any(breakdown.get(bucket) for bucket in KIND_BUCKETS):
        return
    print(format_kind_breakdown(breakdown, title=f"{label} chosen-move kinds"), flush=True)


def kind_totals_paths(runs_key: str) -> tuple[Path, Path]:
    """``runs/{runs_key}/kind_totals.json`` + matching ``.txt`` table."""
    root = RUNS_DIR / runs_key
    return root / KIND_TOTALS_JSON_NAME, root / KIND_TOTALS_TXT_NAME


def load_kind_breakdown(runs_key: str) -> dict[str, Counter[str]]:
    """Read cumulative scenarios/games/total kind counts (missing → empty)."""
    json_path, _txt = kind_totals_paths(runs_key)
    if not json_path.is_file():
        return empty_kind_breakdown()
    raw = json.loads(json_path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return empty_kind_breakdown()
    # Legacy flat ``{"counts": {...}}`` → total only.
    if "counts" in raw and BUCKET_TOTAL not in raw and BUCKET_SCENARIOS not in raw:
        counts = raw.get("counts") or {}
        out = empty_kind_breakdown()
        out[BUCKET_TOTAL] = Counter({str(k): int(v) for k, v in counts.items() if int(v) > 0})
        return out
    out = empty_kind_breakdown()
    for bucket in KIND_BUCKETS:
        part = raw.get(bucket) or {}
        if isinstance(part, dict):
            out[bucket] = Counter({str(k): int(v) for k, v in part.items() if int(v) > 0})
    return out


def load_kind_totals(runs_key: str) -> Counter[str]:
    """Read cumulative total kind counts for a checkpoint key (missing → empty)."""
    return load_kind_breakdown(runs_key)[BUCKET_TOTAL]


def _segment_as_breakdown(
    segment: Mapping[str, Any],
) -> dict[str, Counter[str]]:
    """Accept full breakdown or flat kind→count (legacy → total only)."""
    if any(bucket in segment for bucket in KIND_BUCKETS):
        out = empty_kind_breakdown()
        for bucket in KIND_BUCKETS:
            part = segment.get(bucket) or {}
            if isinstance(part, Mapping):
                out[bucket] = Counter({str(k): int(v) for k, v in part.items() if int(v) > 0})
        return out
    out = empty_kind_breakdown()
    out[BUCKET_TOTAL] = Counter({str(k): int(v) for k, v in segment.items() if int(v) > 0})
    return out


def accumulate_kind_totals(
    runs_key: str,
    segment_totals: Mapping[str, Any],
) -> dict[str, Counter[str]]:
    """Add segment into ``runs/{runs_key}/kind_totals.{json,txt}``; return lifetime breakdown.

    ``segment_totals`` may be a full ``kind_breakdown`` (scenarios/games/total) or a flat
    kind→count map (legacy → added to ``total`` only).
    """
    lifetime = load_kind_breakdown(runs_key)
    merge_kind_breakdown(lifetime, _segment_as_breakdown(segment_totals))
    json_path, txt_path = kind_totals_paths(runs_key)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(breakdown_to_jsonable(lifetime), indent=2) + "\n", encoding="utf-8")
    write_kind_stats(txt_path, lifetime)
    return lifetime


def aggregate_episodes(episodes: list[dict[str, Any]]) -> dict[str, Any]:
    """Mean turns/actions/scoring/submit latency + truncation/submit-error rates + win/loss/draw.

    Works for train rollouts and eval suites. Outcome fields are optional on older
    summaries (missing → ignored for winrate; means still work for the rest).
    """
    if not episodes:
        return _empty_aggregate()

    n = len(episodes)
    submit_errors = sum(1 for ep in episodes if ep.get("submit_error"))
    truncs = sum(1 for ep in episodes if ep.get("truncated") and not ep.get("submit_error"))
    submit_avg_ms_values = [
        float(ep["submit_avg_ms"]) for ep in episodes if ep.get("submit_avg_ms") is not None
    ]
    legal_actions_avg_values = [
        float(ep["legal_actions_avg"]) for ep in episodes if ep.get("legal_actions_avg") is not None
    ]

    # Decided games: finished (or scenario drill result), not truncated/errored, with a result_kind.
    decided = [
        ep
        for ep in episodes
        if not ep.get("truncated")
        and not ep.get("submit_error")
        and ep.get("result_kind") is not None
    ]
    wins = sum(1 for ep in decided if ep.get("result_winner_rel") == "US")
    losses = sum(1 for ep in decided if ep.get("result_winner_rel") == "THEM")
    draws = sum(1 for ep in decided if ep.get("result_winner_rel") is None)
    n_decided = wins + losses + draws

    score_us = [float(ep["score_us"]) for ep in episodes if ep.get("score_us") is not None]
    score_them = [float(ep["score_them"]) for ep in episodes if ep.get("score_them") is not None]
    mean_score_us = _mean(score_us)
    mean_score_them = _mean(score_them)

    kind_keys = sorted({k for ep in episodes for k in ep.get("kind_count", {})})
    kind_breakdown = empty_kind_breakdown()
    for ep in episodes:
        bucket = BUCKET_SCENARIOS if ep.get("scenario_difficulty") is not None else BUCKET_GAMES
        for kind, count in (ep.get("kind_count") or {}).items():
            add_kind_to_breakdown(kind_breakdown, str(kind), bucket=bucket, count=int(count))
    kind_totals = {k: int(kind_breakdown[BUCKET_TOTAL][k]) for k in kind_keys}
    kind_means = {k: kind_totals[k] / n for k in kind_keys}

    first_kind_keys = sorted({ep["first_kind"] for ep in episodes if ep.get("first_kind")})
    first_kind_means = {
        k: sum(1 for ep in episodes if ep.get("first_kind") == k) / n for k in first_kind_keys
    }

    difficulty_values = [
        int(ep["scenario_difficulty"]) for ep in episodes if ep.get("scenario_difficulty") is not None
    ]
    # All workers share one level for a segment; take the first if present.
    scenario_difficulty = difficulty_values[0] if difficulty_values else None

    first_legal_values = [
        float(ep["first_action_legal_count"])
        for ep in episodes
        if ep.get("first_action_legal_count") is not None
    ]
    # Mean: difficulty scatter can change the opening legal set across games.
    first_action_legal_count = _mean(first_legal_values) if first_legal_values else None

    return {
        "episodes": n,
        "turns": sum(ep.get("turns", 0) for ep in episodes) / n,
        "total_actions": sum(ep.get("actions", 0) for ep in episodes) / n,
        "challenge_cards": sum(ep.get("challenge_cards", 0) for ep in episodes) / n,
        "challenge_cards_them": sum(ep.get("challenge_cards_them", 0) for ep in episodes) / n,
        "touchdowns": sum(ep.get("touchdowns", 0) for ep in episodes) / n,
        "touchdowns_them": sum(ep.get("touchdowns_them", 0) for ep in episodes) / n,
        "total_score": sum(ep.get("total_score", 0) for ep in episodes) / n,
        "score_us": mean_score_us,
        "score_them": mean_score_them,
        "score_diff": (mean_score_us - mean_score_them) if score_us and score_them else 0.0,
        "truncation_rate": truncs / n,
        "submit_error_rate": submit_errors / n,
        "submit_avg_ms": _mean(submit_avg_ms_values),
        "legal_actions_avg": _mean(legal_actions_avg_values),
        "wins": wins,
        "losses": losses,
        "draws": draws,
        "winrate": (wins / n_decided) if n_decided else 0.0,
        "turn_start_samples": sum(ep.get("turn_start_samples", 0) for ep in episodes) / n,
        **{k: _mean([float(ep[k]) for ep in episodes if ep.get(k) is not None]) for k in _BALL_RATE_KEYS},
        "ball_gained_us": sum(ep.get("ball_gained_us", 0) for ep in episodes) / n,
        "ball_gained_them": sum(ep.get("ball_gained_them", 0) for ep in episodes) / n,
        "kind_count": kind_means,
        "kind_totals": kind_totals,
        "kind_breakdown": {bucket: dict(kind_breakdown[bucket]) for bucket in KIND_BUCKETS},
        "first_kind_count": first_kind_means,
        "scenario_difficulty": scenario_difficulty,
        "first_action_legal_count": first_action_legal_count,
    }


class EpisodeActionStats:
    """Accumulate per-kind action counts, turn-start possession, and submit latency for one match."""

    def __init__(self) -> None:
        self.reset()

    def reset(self) -> None:
        self.counts: dict[str, int] = {}
        self.first_kind: str | None = None
        self.submit_ms_total: float = 0.0
        self.submit_count: int = 0
        self.legal_count_sum: int = 0
        self.legal_count_n: int = 0
        self.turn_start_samples: int = 0
        self.ball_us: int = 0
        self.ball_them: int = 0
        self.ball_us_opp_half: int = 0
        self.ball_them_opp_half: int = 0
        self.ball_us_proximity_sum: float = 0.0
        self.ball_us_proximity_n: int = 0
        self.ball_us_gained: int = 0
        self.ball_them_gained: int = 0

    def record(self, wire_kind: str) -> None:
        if self.first_kind is None:
            self.first_kind = wire_kind
        self.counts[wire_kind] = self.counts.get(wire_kind, 0) + 1

    def record_legal_count(self, legal_count: int) -> None:
        """Sample branching factor; skip tiny menus (dialogs / forced picks)."""
        if legal_count <= 4:
            return
        self.legal_count_sum += legal_count
        self.legal_count_n += 1

    def record_submit_ms(self, ms: float) -> None:
        self.submit_ms_total += ms
        self.submit_count += 1

    def record_ball_gain(self, before: dict[str, Any], after: dict[str, Any]) -> None:
        """Count !holds→holds for us and them (same-perspective snapshots)."""
        if (not us_holds_ball(before)) and us_holds_ball(after):
            self.ball_us_gained += 1
        if (not them_holds_ball(before)) and them_holds_ball(after):
            self.ball_them_gained += 1

    def record_turn_start(
        self,
        observation_from_server: dict[str, Any],
        *,
        perspective_seat: str,
    ) -> None:
        """One turn-start possession sample (caller dedupes by ``turnNumber``)."""
        self.turn_start_samples += 1
        us_carrier = us_ball_carrier(observation_from_server)
        them_carrier = them_ball_carrier(observation_from_server)
        us_xy = coordinate_xy(us_carrier.get("coordinate") if us_carrier else None)
        them_xy = coordinate_xy(them_carrier.get("coordinate") if them_carrier else None)
        if us_xy is not None:
            self.ball_us += 1
            proximity = endzone_proximity(us_xy, acting_seat=perspective_seat)
            self.ball_us_proximity_sum += proximity
            self.ball_us_proximity_n += 1
            if proximity > _OPP_HALF_PROXIMITY:
                self.ball_us_opp_half += 1
        if them_xy is not None:
            self.ball_them += 1
            # Them deep in our half = low proximity toward our scoring end zone.
            if endzone_proximity(them_xy, acting_seat=perspective_seat) < _OPP_HALF_PROXIMITY:
                self.ball_them_opp_half += 1

    @property
    def total_actions(self) -> int:
        return sum(self.counts.values())

    @property
    def submit_avg_ms(self) -> float | None:
        if self.submit_count == 0:
            return None
        return self.submit_ms_total / self.submit_count

    @property
    def legal_actions_avg(self) -> float | None:
        if self.legal_count_n == 0:
            return None
        return self.legal_count_sum / self.legal_count_n

    def _ball_rates(self) -> dict[str, float]:
        n = self.turn_start_samples
        if n == 0:
            return {k: 0.0 for k in _BALL_RATE_KEYS}
        return {
            "ball_rate_us": self.ball_us / n,
            "ball_rate_them": self.ball_them / n,
            "ball_opp_half_rate_us": self.ball_us_opp_half / n,
            "ball_opp_half_rate_them": self.ball_them_opp_half / n,
            "ball_proximity_us": (
                self.ball_us_proximity_sum / self.ball_us_proximity_n if self.ball_us_proximity_n else 0.0
            ),
        }

    def episode_summary(
        self,
        *,
        turns: int,
        truncated: bool,
        total_score: int,
        challenge_cards: int,
        challenge_cards_them: int,
        touchdowns: int,
        touchdowns_them: int,
        submit_error: bool = False,
        score_us: int | None = None,
        score_them: int | None = None,
        result_kind: str | None = None,
        result_winner_rel: str | None = None,
    ) -> dict[str, Any]:
        """Snapshot for the terminating step's info (train TB + eval suite)."""
        return {
            "turns": int(turns),
            "actions": self.total_actions,
            "truncated": bool(truncated),
            "submit_error": bool(submit_error),
            "submit_avg_ms": self.submit_avg_ms,
            "legal_actions_avg": self.legal_actions_avg,
            "kind_count": dict(self.counts),
            "challenge_cards": int(challenge_cards),
            "challenge_cards_them": int(challenge_cards_them),
            "touchdowns": int(touchdowns),
            "touchdowns_them": int(touchdowns_them),
            "total_score": int(total_score),
            "score_us": score_us,
            "score_them": score_them,
            "result_kind": result_kind,
            "result_winner_rel": result_winner_rel,
            "turn_start_samples": self.turn_start_samples,
            **self._ball_rates(),
            "ball_gained_us": self.ball_us_gained,
            "ball_gained_them": self.ball_them_gained,
        }
