"""``runs/{runs_key}/kind_totals.json`` (+ companion ``.txt`` table)."""

from __future__ import annotations

import json
from collections import Counter
from collections.abc import Mapping
from pathlib import Path
from typing import TypeAlias, TypedDict

from gridiron_ai.model.expert_clone.expert_stats import (
    BUCKET_SCENARIOS,
    BUCKET_TOTAL,
    KIND_BUCKETS,
    breakdown_to_jsonable,
    empty_kind_breakdown,
    merge_kind_breakdown,
    write_kind_stats,
)
from gridiron_ai.train.paths import KIND_TOTALS_JSON_NAME, KIND_TOTALS_TXT_NAME, RUNS_DIR

# kind name → count within one bucket (``scenarios`` / ``games`` / ``total``).
KindCountMap: TypeAlias = dict[str, int]


class KindTotalsDict(TypedDict, total=False):
    """On-disk ``kind_totals.json`` — bucket → kind→count (legacy flat ``counts`` also accepted)."""

    scenarios: KindCountMap
    games: KindCountMap
    total: KindCountMap
    counts: KindCountMap  # legacy flat → total only


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
    segment: Mapping[str, object],
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
    segment_totals: Mapping[str, object],
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
