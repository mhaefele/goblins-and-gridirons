#!/usr/bin/env python3
"""Play the trained policy against you in the browser (not self-play).

Creates a both-HUMAN match, opens the frontend URL for your seat (`?coach=`), and runs the
policy on the other seat. With `?coach=` set, the GUI only accepts input for your seat
(the other HUMAN is driven by this script — not hotseat).

Defaults match ``./dev.sh`` ports (backend ``:18080``, frontend ``:15173``). Checks both
are up before starting; otherwise tells you to run ``./dev.sh``.

Usage (from ai/):
  .venv/bin/python -m scripts.play_vs_human
  .venv/bin/python -m scripts.play_vs_human --human-seat HOME --no-open

Keep every ``p.add_argument(...)`` on one line — do not wrap.
"""

from __future__ import annotations

import argparse
import logging
import sys
import webbrowser
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[1]
if str(_AI_ROOT) not in sys.path:
    sys.path.insert(0, str(_AI_ROOT))

from sb3_contrib import MaskablePPO

from gridiron_ai.dev_stack import DEFAULT_BASE_URL, DEFAULT_FRONTEND_URL, require_dev_stack
from gridiron_ai.api.env import OPPONENT_HUMAN, GridironEnv
from gridiron_ai.reward import REWARD_PROFILES
from gridiron_ai.training_phases import resolve_targets

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s", force=True)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Play trained policy vs you in the browser")
    p.add_argument("--base-url", default=DEFAULT_BASE_URL, help="Backend API base URL (dev.sh default :18080)")
    p.add_argument("--frontend-url", default=DEFAULT_FRONTEND_URL, help="Vite frontend origin (dev.sh default :15173)")
    p.add_argument("--profile", default="general", choices=REWARD_PROFILES, help="Reward profile (pre-split → baseline phase checkpoint)")
    p.add_argument("--human-seat", default="HOME", choices=("HOME", "AWAY"), help="Your seat in the browser (?coach=)")
    p.add_argument("--device", default="cpu", help="Torch device for the policy")
    p.add_argument("--max-episode-steps", type=int, default=2000, help="Max model submits before truncate")
    p.add_argument("--turn-timeout-s", type=float, default=3600.0, help="Max seconds waiting for your turn between model moves")
    p.add_argument("--no-open", action="store_true", help="Print the URL only; do not open a browser")
    p.add_argument("--stochastic", action="store_true", help="Sample actions instead of deterministic")
    return p.parse_args()


def _play_url(frontend_url: str, match_id: str, human_seat: str) -> str:
    base = frontend_url.rstrip("/")
    return f"{base}/?matchId={match_id}&coach={human_seat}"


def main() -> None:
    args = parse_args()
    require_dev_stack(args.base_url, args.frontend_url)

    targets = resolve_targets(args.profile)
    if not targets:
        raise SystemExit("No resolvable checkpoint for this profile")
    target = targets[0]
    if target.deferral_note:
        print(target.deferral_note, flush=True)
    if target.checkpoint_key != args.profile:
        print(
            f"Using checkpoint_key={target.checkpoint_key} (requested profile={args.profile})",
            flush=True,
        )
    model_path = target.zip_path()
    if not model_path.is_file():
        raise SystemExit(f"No checkpoint at {model_path}")

    policy_seat = "AWAY" if args.human_seat == "HOME" else "HOME"
    print(f"Loading {model_path} …", flush=True)
    model = MaskablePPO.load(str(model_path), device=args.device)

    opened: dict[str, str] = {}

    def on_match_created(match_id: str) -> None:
        url = _play_url(args.frontend_url, match_id, args.human_seat)
        opened["url"] = url
        opened["match_id"] = match_id
        print(f"\nYou are {args.human_seat}. Policy is {policy_seat}.", flush=True)
        print(f"Open: {url}\n", flush=True)
        if not args.no_open:
            webbrowser.open(url)

    env = GridironEnv(
        base_url=args.base_url,
        opponent_mode=OPPONENT_HUMAN,
        policy_seat=policy_seat,
        max_episode_steps=args.max_episode_steps,
        turn_timeout_s=args.turn_timeout_s,
        on_match_created=on_match_created,
    )

    print(
        f"Waiting for your turns (timeout {args.turn_timeout_s:.0f}s).",
        flush=True,
    )
    obs, info = env.reset()
    step = 0
    try:
        while True:
            action, _state = model.predict(obs, deterministic=not args.stochastic)
            obs, _reward, terminated, truncated, info = env.step(action)
            step += 1
            kind = info.get("result_kind")
            if terminated or truncated:
                winner = info.get("result_winner_rel")
                summary = info.get("bb_episode") or {}
                print(
                    f"Match over after {step} policy moves: "
                    f"result={kind} winner_rel={winner} "
                    f"score={summary.get('score_us')}-{summary.get('score_them')}",
                    flush=True,
                )
                break
            if step % 5 == 0:
                print(f"  … policy moves so far: {step} (match {opened.get('match_id', '?')})", flush=True)
    except KeyboardInterrupt:
        print("\nStopped.", flush=True)
    finally:
        env.close()


if __name__ == "__main__":
    main()
