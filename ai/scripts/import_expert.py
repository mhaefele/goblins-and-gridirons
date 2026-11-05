#!/usr/bin/env python3
"""Pack a Kotlin expert JSONL dump into an npz clone_expert can load.

JSONL lines are already packed (obs float[] + ragged legalMoves); this slices game-state
into gym Dict arrays and keeps legals ragged (flat + offsets) for RAM. Also writes
``{out.stem}_kind_stats.txt`` (chosen-move wire-kind counts + %).

Usage (from ai/):
  .venv/bin/python -m scripts.import_expert --in output/expert/lookahead.jsonl.gz --out output/expert/lookahead.npz

Keep every ``p.add_argument(...)`` on one line — do not wrap.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[1]
if str(_AI_ROOT) not in sys.path:
    sys.path.insert(0, str(_AI_ROOT))

from gridiron_ai.expert_clone.expert import jsonl_to_npz
from gridiron_ai.paths import EXPERT_DIR

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s", force=True)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Slice packed expert JSONL into ragged-legal npz")
    p.add_argument("--in", dest="jsonl", type=Path, default=EXPERT_DIR / "lookahead.jsonl.gz", help="Kotlin dump (*.jsonl or *.jsonl.gz)")
    p.add_argument("--out", type=Path, default=EXPERT_DIR / "lookahead.npz", help="Packed npz")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    jsonl_path = args.jsonl.expanduser().resolve()
    if not jsonl_path.is_file():
        raise SystemExit(f"expert dump not found: {jsonl_path}")
    out_path = args.out.expanduser().resolve()
    jsonl_to_npz(jsonl_path, out_path)
    stats_path = out_path.with_name(f"{out_path.stem}_kind_stats.txt")
    print(f"wrote {out_path}", flush=True)
    print(f"wrote {stats_path}", flush=True)


if __name__ == "__main__":
    main()
