"""Chosen-move kind tallies for expert dumps (pack summary / summarize_expert).

Owner: count from JSONL ``chosenLegalActionId`` wire prefixes (not a mirrored AiLegalKind
catalog). NPZ fallback uses ``chosenFactors[kind]`` as ``kind_{ord}``. See ``ai/OWNERSHIP.md``.

Breakdown buckets: ``scenarios`` / ``games`` / ``total`` from dump ``episodeFamily``
(``scenario`` | ``regular``). Untagged legacy lines contribute to ``total`` only.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Mapping
from pathlib import Path
from typing import Any

BUCKET_SCENARIOS = "scenarios"
BUCKET_GAMES = "games"
BUCKET_TOTAL = "total"
KIND_BUCKETS = (BUCKET_SCENARIOS, BUCKET_GAMES, BUCKET_TOTAL)

# Wire values from Kotlin ``ExpertStepRecord.episodeFamily``.
EPISODE_FAMILY_SCENARIO = "scenario"
EPISODE_FAMILY_REGULAR = "regular"


def empty_kind_breakdown() -> dict[str, Counter[str]]:
    return {bucket: Counter() for bucket in KIND_BUCKETS}


def bucket_for_episode_family(family: Any) -> str | None:
    """Map dump ``episodeFamily`` → ``scenarios`` / ``games``; unknown → ``None`` (total only)."""
    text = str(family or "").strip().lower()
    if text == EPISODE_FAMILY_SCENARIO:
        return BUCKET_SCENARIOS
    if text == EPISODE_FAMILY_REGULAR:
        return BUCKET_GAMES
    return None


def add_kind_to_breakdown(
    breakdown: dict[str, Counter[str]],
    kind: str,
    *,
    bucket: str | None,
    count: int = 1,
) -> None:
    """Add ``count`` to ``total`` and optionally to ``scenarios`` / ``games``."""
    n = int(count)
    if n <= 0:
        return
    breakdown[BUCKET_TOTAL][kind] += n
    if bucket in (BUCKET_SCENARIOS, BUCKET_GAMES):
        breakdown[bucket][kind] += n


def wire_kind_from_action_id(action_id: Any) -> str:
    """``RUN_h4_…`` → ``RUN``; empty / missing → ``?``."""
    text = str(action_id or "").strip()
    if not text:
        return "?"
    return text.split("_", 1)[0]


def count_kind_breakdown_from_jsonl(path: Path) -> dict[str, Counter[str]]:
    """Stream dump lines; tally kinds into scenarios / games / total via ``episodeFamily``."""
    # Lazy import: expert.py imports format helpers from this module.
    from gridiron_ai.expert import load_jsonl_records

    breakdown = empty_kind_breakdown()
    for record in load_jsonl_records(path):
        kind = wire_kind_from_action_id(record.get("chosenLegalActionId"))
        add_kind_to_breakdown(
            breakdown,
            kind,
            bucket=bucket_for_episode_family(record.get("episodeFamily")),
        )
    return breakdown


def count_kinds_from_jsonl(path: Path) -> Counter[str]:
    """Stream dump lines; tally ``chosenLegalActionId`` prefixes (total only)."""
    return count_kind_breakdown_from_jsonl(path)[BUCKET_TOTAL]


def count_kinds_from_npz(path: Path) -> Counter[str]:
    """Tally packed kind ordinals as ``kind_{ord}`` (no AiLegalKind name mirror; total only)."""
    from gridiron_ai.actions import FACTOR_KIND
    from gridiron_ai.expert import (
        NPZ_KEY_ACTION,
        NPZ_KEY_LEGAL_MOVES_FLAT,
        NPZ_KEY_LEGAL_ROW_OFFSET,
        load_npz,
    )

    arrays = load_npz(path)
    actions = arrays[NPZ_KEY_ACTION]
    offsets = arrays[NPZ_KEY_LEGAL_ROW_OFFSET]
    flat = arrays[NPZ_KEY_LEGAL_MOVES_FLAT]
    counts: Counter[str] = Counter()
    for i in range(int(actions.shape[0])):
        start = int(offsets[i])
        row = start + int(actions[i])
        counts[f"kind_{int(flat[row, FACTOR_KIND])}"] += 1
    return counts


def format_kind_table(counts: Counter[str], *, title: str | None = None) -> str:
    """Human table: kind, count, percent (descending count)."""
    total = sum(counts.values())
    lines: list[str] = []
    if title:
        lines.append(title)
    if total == 0:
        lines.append("(no steps)")
        return "\n".join(lines)
    width = max(4, max(len(k) for k in counts))
    lines.append(f"{'kind':<{width}}  {'count':>10}  {'pct':>7}")
    lines.append("-" * (width + 21))
    for kind, count in counts.most_common():
        lines.append(f"{kind:<{width}}  {count:>10,}  {100.0 * count / total:>6.2f}%")
    lines.append("-" * (width + 21))
    lines.append(f"{'TOTAL':<{width}}  {total:>10,}  {100.0:>6.2f}%")
    return "\n".join(lines)


def _as_breakdown(
    counts_or_breakdown: Counter[str] | Mapping[str, Counter[str] | Mapping[str, int]],
) -> dict[str, Counter[str]]:
    if isinstance(counts_or_breakdown, Counter):
        out = empty_kind_breakdown()
        out[BUCKET_TOTAL] = Counter(counts_or_breakdown)
        return out
    out = empty_kind_breakdown()
    for bucket in KIND_BUCKETS:
        raw = counts_or_breakdown.get(bucket) or {}
        out[bucket] = Counter({str(k): int(v) for k, v in raw.items() if int(v) > 0})
    return out


def format_kind_breakdown(
    counts_or_breakdown: Counter[str] | Mapping[str, Counter[str] | Mapping[str, int]],
    *,
    title: str | None = None,
) -> str:
    """Three tables: scenarios, games, total (flat Counter → total section only)."""
    breakdown = _as_breakdown(counts_or_breakdown)
    sections: list[str] = []
    if title:
        sections.append(title)
    for bucket in KIND_BUCKETS:
        sections.append(format_kind_table(breakdown[bucket], title=f"=== {bucket} ==="))
    return "\n\n".join(sections)


def write_kind_stats(
    path: Path,
    counts_or_breakdown: Counter[str] | Mapping[str, Counter[str] | Mapping[str, int]],
) -> None:
    """Write scenarios / games / total tables next to a pack or train artifact."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(format_kind_breakdown(counts_or_breakdown) + "\n", encoding="utf-8")


def breakdown_to_jsonable(breakdown: Mapping[str, Counter[str] | Mapping[str, int]]) -> dict[str, dict[str, int]]:
    """Stable JSON object for ``kind_totals.json``."""
    return {
        bucket: dict(sorted({str(k): int(v) for k, v in (breakdown.get(bucket) or {}).items() if int(v) > 0}.items()))
        for bucket in KIND_BUCKETS
    }


def merge_kind_breakdown(
    lifetime: dict[str, Counter[str]],
    segment: Mapping[str, Counter[str] | Mapping[str, int]],
) -> dict[str, Counter[str]]:
    """Add segment bucket counts into ``lifetime`` (mutates and returns ``lifetime``)."""
    for bucket in KIND_BUCKETS:
        for kind, count in (segment.get(bucket) or {}).items():
            n = int(count)
            if n:
                lifetime[bucket][str(kind)] += n
    return lifetime
