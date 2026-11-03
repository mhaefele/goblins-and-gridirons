"""Chosen-move kind tallies for expert dumps (pack summary / summarize_expert).

Owner: count from JSONL ``chosenLegalActionId`` wire prefixes (not a mirrored AiLegalKind
catalog). NPZ fallback uses ``chosenFactors[kind]`` as ``kind_{ord}``. See ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

from collections import Counter
from pathlib import Path
from typing import Any


def wire_kind_from_action_id(action_id: Any) -> str:
    """``RUN_h4_…`` → ``RUN``; empty / missing → ``?``."""
    text = str(action_id or "").strip()
    if not text:
        return "?"
    return text.split("_", 1)[0]


def count_kinds_from_jsonl(path: Path) -> Counter[str]:
    """Stream dump lines; tally ``chosenLegalActionId`` prefixes."""
    # Lazy import: expert.py imports format helpers from this module.
    from gridiron_ai.expert import load_jsonl_records

    counts: Counter[str] = Counter()
    for record in load_jsonl_records(path):
        counts[wire_kind_from_action_id(record.get("chosenLegalActionId"))] += 1
    return counts


def count_kinds_from_npz(path: Path) -> Counter[str]:
    """Tally packed kind ordinals as ``kind_{ord}`` (no AiLegalKind name mirror)."""
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


def write_kind_stats(path: Path, counts: Counter[str]) -> None:
    """Write the same table next to a pack artifact (e.g. ``lookahead_kind_stats.txt``)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(format_kind_table(counts) + "\n", encoding="utf-8")
