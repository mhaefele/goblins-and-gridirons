#!/usr/bin/env python3
"""Print chosen-move kind counts (%) for an expert JSONL dump or packed npz.

JSONL: wire kinds from ``chosenLegalActionId`` prefixes, split by ``episodeFamily``
into scenarios / games / total. NPZ: ``kind_{ordinal}`` under total only (family not packed).

Usage (from ai/ — also runs automatically at the end of pack / import_expert):
  .venv/bin/python -m scripts.summarize_expert
  .venv/bin/python -m scripts.summarize_expert --in output/expert/lookahead.jsonl.gz

Keep every ``p.add_argument(...)`` on one line — do not wrap.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[1]
if str(_AI_ROOT) not in sys.path:
    sys.path.insert(0, str(_AI_ROOT))

from gridiron_ai.expert_stats import (
    BUCKET_TOTAL,
    count_kind_breakdown_from_jsonl,
    count_kinds_from_npz,
    empty_kind_breakdown,
    format_kind_breakdown,
)
from gridiron_ai.paths import EXPERT_DIR


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Tally expert chosen-move kinds (scenarios / games; total when both)")
    p.add_argument("--in", dest="path", type=Path, default=EXPERT_DIR / "lookahead.jsonl.gz", help="Expert JSONL/JSONL.GZ or packed npz")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    path = args.path.expanduser().resolve()
    if not path.is_file():
        raise SystemExit(f"expert artifact not found: {path}")
    suffix = path.suffix.lower()
    name = path.name.lower()
    if suffix == ".npz" or name.endswith(".npz"):
        breakdown = empty_kind_breakdown()
        breakdown[BUCKET_TOTAL] = count_kinds_from_npz(path)
        source = "npz kind ordinal; total only (no episodeFamily)"
    elif name.endswith(".jsonl") or name.endswith(".jsonl.gz") or name.endswith(".gz"):
        breakdown = count_kind_breakdown_from_jsonl(path)
        source = "chosenLegalActionId + episodeFamily"
    else:
        raise SystemExit(f"unsupported expert artifact (want jsonl/jsonl.gz/npz): {path}")
    print(format_kind_breakdown(breakdown, title=f"{path.name} ({source})"), flush=True)


if __name__ == "__main__":
    main()
