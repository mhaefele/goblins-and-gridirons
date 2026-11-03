#!/usr/bin/env python3
"""Print chosen-move kind counts (%) for an expert JSONL dump or packed npz.

Wire kinds come from ``chosenLegalActionId`` prefixes on JSONL; npz falls back to
``kind_{ordinal}`` (no AiLegalKind name catalog in Python).

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

from gridiron_ai.expert_stats import count_kinds_from_jsonl, count_kinds_from_npz, format_kind_table
from gridiron_ai.paths import EXPERT_DIR


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Tally expert chosen-move kinds (count + percent)")
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
        counts = count_kinds_from_npz(path)
        source = "npz kind ordinal"
    elif name.endswith(".jsonl") or name.endswith(".jsonl.gz") or name.endswith(".gz"):
        counts = count_kinds_from_jsonl(path)
        source = "chosenLegalActionId prefix"
    else:
        raise SystemExit(f"unsupported expert artifact (want jsonl/jsonl.gz/npz): {path}")
    print(format_kind_table(counts, title=f"{path.name} ({source})"), flush=True)


if __name__ == "__main__":
    main()
