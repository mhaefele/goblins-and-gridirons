#!/usr/bin/env python3
"""Export profile MaskablePPO checkpoint(s) to ONNX for the Kotlin opponent.

Usage (prefer ``./export_opponent.sh``):
  .venv/bin/python -m scripts.export_onnx_opponent
  .venv/bin/python -m scripts.export_onnx_opponent --profile general
  .venv/bin/python -m scripts.export_onnx_opponent --profile all
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[1]
if str(_AI_ROOT) not in sys.path:
    sys.path.insert(0, str(_AI_ROOT))

from sb3_contrib import MaskablePPO

from gridiron_ai.export_onnx import export_policy_onnx, smoke_compare_logits
from gridiron_ai.reward import REWARD_PROFILES
from gridiron_ai.training_phases import freeze_exported_checkpoint, resolve_targets


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Export profile checkpoint(s) → ONNX opponent artifact(s)")
    p.add_argument(
        "--profile",
        default="all",
        choices=[*sorted(REWARD_PROFILES), "all"],
        help="Reward profile, or all profiles that have a final.zip (default: all)",
    )
    p.add_argument("--device", default="cpu", help="Torch device for load + export")
    p.add_argument("--skip-smoke", action="store_true", help="Skip ONNXRuntime parity check")
    return p.parse_args()


def _profiles_to_export(requested: str) -> list[str]:
    if requested != "all":
        return [requested]
    found: list[str] = []
    for name in REWARD_PROFILES:
        targets = resolve_targets(name, purpose="summary")
        if not targets:
            continue
        zip_path = targets[0].zip_path()
        if zip_path.is_file():
            found.append(name)
    return found


def _export_one(profile: str, *, device: str, skip_smoke: bool) -> Path:
    targets = resolve_targets(profile, purpose="summary")
    if not targets:
        raise SystemExit(f"No resolvable checkpoint for profile={profile}")
    target = targets[0]
    if target.deferral_note:
        print(target.deferral_note, flush=True)
    zip_path = target.zip_path()
    if not zip_path.is_file():
        raise SystemExit(f"No checkpoint at {zip_path} — train or pick another --profile")

    print(f"Loading {zip_path} (checkpoint_key={target.checkpoint_key}) …", flush=True)
    model = MaskablePPO.load(str(zip_path), device=device)
    out = export_policy_onnx(
        model.policy,
        profile=profile,
        checkpoint_key=target.checkpoint_key,
        checkpoint_zip=zip_path,
    )
    onnx_path = out / "policy.onnx"
    print(f"Wrote {onnx_path}", flush=True)
    print(f"Wrote {out / 'manifest.json'}", flush=True)
    freeze_exported_checkpoint(target.checkpoint_key)
    if not skip_smoke:
        print(f"Smoke: Torch vs ONNX logits ({profile}) …", flush=True)
        smoke_compare_logits(model.policy, onnx_path)
        print("Smoke OK", flush=True)
    return out


def main() -> None:
    args = parse_args()
    profiles = _profiles_to_export(args.profile)
    if not profiles:
        raise SystemExit("No profiles with a final.zip to export")
    print(f"Exporting profiles: {', '.join(profiles)}", flush=True)
    for profile in profiles:
        _export_one(profile, device=args.device, skip_smoke=args.skip_smoke)


if __name__ == "__main__":
    main()
