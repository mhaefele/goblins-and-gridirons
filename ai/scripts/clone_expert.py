#!/usr/bin/env python3
"""Copy packed expert moves into a MaskablePPO checkpoint zip.

No backend. Overwrites ``output/checkpoints/{profile}/maskable_ppo_selfplay_final.zip``
(all three if ``--profile all``). Does not modify the npz unless ``--out`` is set.

Usage (from ai/):
  .venv/bin/python -m scripts.clone_expert --in output/expert/lookahead.npz --profile all

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

from gridiron_ai.clone import clone_npz_into_checkpoint
from gridiron_ai.paths import CHECKPOINT_DIR, EXPERT_DIR
from gridiron_ai.reward import REWARD_PROFILES, RewardProfileName

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s", force=True)

_LONG_RUNNING_STEM = "maskable_ppo_selfplay_final"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Copy expert npz into MaskablePPO checkpoint zip(s) (no HTTP)")
    p.add_argument("--in", dest="npz", type=Path, default=EXPERT_DIR / "lookahead.npz", help="Packed expert dump from import_expert")
    p.add_argument("--profile", default="general", choices=("all", *REWARD_PROFILES), help="Long-running zip to overwrite (all = every reward profile)")
    p.add_argument("--batch-size", type=int, default=256, help="Minibatch size")
    p.add_argument("--lr", type=float, default=1e-4, help="Adam learning rate for this pass")
    p.add_argument("--seed", type=int, default=0, help="Weight init + shuffle seed (fresh models)")
    p.add_argument("--device", default="cpu", help="Torch device")
    p.add_argument("--resume", type=Path, default=None, help="Optional existing zip to fine-tune instead of random weights")
    p.add_argument("--to", type=Path, default=None, help="Write this zip instead of profile long-running zips")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    zips = [args.to.expanduser().resolve()] if args.to is not None else _destination_zips(args.profile)
    resume = args.resume.expanduser().resolve() if args.resume is not None else None
    if resume is not None and not resume.is_file():
        raise SystemExit(f"--resume zip not found: {resume}")

    npz_path = args.npz.expanduser().resolve()
    if not npz_path.is_file():
        raise SystemExit(f"expert npz not found: {npz_path}")
    mean_loss = clone_npz_into_checkpoint(
        npz_path,
        zips,
        batch_size=args.batch_size,
        learning_rate=args.lr,
        seed=args.seed,
        device=args.device,
        resume_zip=resume,
    )

    for zip_path in zips:
        print(f"wrote {zip_path.with_suffix('.zip')}  last_epoch_loss={mean_loss:.4f}", flush=True)


def _destination_zips(profile: str) -> list[Path]:
    names = REWARD_PROFILES if profile == "all" else (profile,)
    return [_long_running_zip(name) for name in names]


def _long_running_zip(profile: RewardProfileName) -> Path:
    return CHECKPOINT_DIR / profile / f"{_LONG_RUNNING_STEM}.zip"


if __name__ == "__main__":
    main()
