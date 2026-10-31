#!/usr/bin/env python3
"""Train / eval MaskablePPO on the live AI API — one entrypoint.

Owner: orchestration only (PPO, vec-env, cycle segments, CLI). Phase/curriculum *policy*
lives in ``training_phases`` / ``scenario_curriculum`` — do not grow gates here.
See ``ai/OWNERSHIP.md`` (why this file is large → BACKLOG AI2.14).

Day-to-day: ``./train.sh`` / ``./eval.sh``. Phases: scenario_a → scenario_b → shaped
profiles (``training_phases``). Mix knobs: ``TRAINING_ROADMAP.md`` + curriculum constants.

Per cycle: game phase → full-game train/eval first, scenario eval + straggler/soft-d5 train;
climbing → scenario sandwich then full-game (usually 0 until unlock).
climbing → scenario train → after-eval → full-game. ``--profile all`` rotates baseline
(unshaped control on scenario_b) plus ``profiles_for_all(\"train\")`` shaped names
(today: ``general``, ``spread``; pin ``td`` / ``defense`` explicitly).

Contents
--------
- ``make_env`` / ``make_vec_env`` / ``WorkerPool`` — gym + SubprocVecEnv factories; pool spawns once
- ``_wrap_vecnormalize`` / model create·resume helpers
- ``_train_segment`` / ``_eval_segment`` — full-game blocks
- ``_train_scenarios_segment`` / ``_eval_scenarios_segment`` — drill sandwich
- ``_run_one_cycle`` / ``main`` — profile rotation + forever loop

Keep every ``p.add_argument(...)`` on one line — do not wrap.
"""

from __future__ import annotations

import argparse
import json
import logging
import shutil
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from dataclasses import dataclass
from typing import Any, Callable

import gymnasium as gym
import numpy as np

_AI_ROOT = Path(__file__).resolve().parents[1]
if str(_AI_ROOT) not in sys.path:
    sys.path.insert(0, str(_AI_ROOT))

from gridiron_ai.checkpoints import (
    DEFAULT_STEP_CHECKPOINT_KEEP,
    STEP_CHECKPOINT_PREFIX,
    atomic_model_save,
    prepare_checkpoint_dirs,
    trim_step_checkpoints,
)
from gridiron_ai.dev_stack import DEFAULT_BASE_URL, require_backend
from gridiron_ai.paths import CHECKPOINT_DIR, CYCLE_LOG_PATH, EVAL_DIR, RUNS_DIR

from sb3_contrib import MaskablePPO
from sb3_contrib.common.wrappers import ActionMasker
from stable_baselines3.common.callbacks import CallbackList
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv, SubprocVecEnv, VecEnv, VecNormalize
from stable_baselines3.common.vec_env.subproc_vec_env import _stack_obs

from gridiron_ai.client import GridironApiError, GridironClient
from gridiron_ai.reward import (
    FULL_GAME_ONNX_OPPONENT_PROFILES,
    PROFILE_SHAPING,
    REWARD_PROFILES,
    SHAPED_PROFILES,
    RewardProfileName,
    RewardShaping,
    profiles_for_all,
)
from gridiron_ai.env import (
    COMPUTER_KIND_ONNX,
    OPPONENT_COMPUTER,
    GridironEnv,
    opponent_metrics_label,
)
from gridiron_ai.eval_debug import ScenarioEvalDebug
from gridiron_ai.eval_head_agreement import (
    diagnose_eval_actions,
    format_head_agreement,
    merge_head_agreement,
    new_head_agreement,
    record_head_decision,
)
from gridiron_ai.eval_opening_diag import diagnose_opening_mispick
from gridiron_ai.metrics import aggregate_episodes, print_metrics
from gridiron_ai.scenario_curriculum import (
    CLAIM_UNLOCK_BOARD_DIFFICULTY,
    DEFAULT_SCENARIO_TRAIN_LEARNING,
    DEFAULT_SCENARIO_TRAIN_MASTERED,
    DEFAULT_SCENARIO_TRAIN_MAINTENANCE,
    MAX_DIFFICULTY,
    all_scenarios_mastered,
    apply_after_eval_curriculum,
    claims_unlocked,
    full_game_train_timesteps,
    gate_failure_count,
    gating_scenarios_mastered,
    get_difficulty,
    load_drill_stats,
    load_gate_failures,
    load_levels,
    path_for_drill_stats,
    path_for_gate_failures,
    path_for_stem,
    record_train_cycles,
    save_drill_stats,
    save_gate_failures,
    save_levels,
    scenarios_for_cycle,
    scenarios_for_eval,
    SCENARIO_EVAL_GAMES,
    train_timesteps_for_difficulty,
    train_timesteps_for_scenario,
)
from gridiron_ai.scenarios import refresh_catalog, require_catalog, resolve_scenario_pin
from gridiron_ai.tb_callback import GridironMetricsCallback, PeriodicFinalSaveCallback
from gridiron_ai.training_phases import (
    PhaseName,
    TrainTarget,
    advance_pipeline,
    ensure_checkpoint_from_prior,
    force_promote,
    game_phase_unlocked,
    load_drill_stats_cumulative,
    long_running_stem,
    long_running_zip,
    resolve_targets,
    runs_dir,
    PHASE_PROFILES,
)
from gridiron_ai.pipeline_state import load_pipeline, rewind_pipeline

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s", force=True)

_LONG_RUNNING_STEM = "maskable_ppo_selfplay_final"
_SESSION_LONG = "train_sessions_long.tsv"
_DEFAULT_LEARNING_RATE = 3e-5
DEFAULT_MAX_EPISODE_STEPS = 500
DEFAULT_POLICY_SEAT = "HOME"
DEFAULT_DEVICE = "cpu"
FINAL_SAVE_FREQ = 50_000
STEP_CHECKPOINT_FREQ = 500_000
# PPO entropy bonus — stock SB3 default is 0.0 (peaks fast). Prefer more random early;
# raise/lower later from TensorBoard policy_entropy vs drill results (see TRAINING_ROADMAP.md).
DEFAULT_ENT_COEF = 0.005
PROFILE_ENT_COEF: dict[RewardProfileName, float] = {
    "defense": 0.009,
}
# Value-loss weight — td shaping is spiky; nudge critic harder there (see TRAINING_ROADMAP.md).
DEFAULT_VF_COEF = 0.5
PROFILE_VF_COEF: dict[RewardProfileName, float] = {
    "td": 0.8,
}
# Critic / KL stability (stock n_epochs=10 + clip=0.2 thrashed TD_RUN drills).
N_EPOCHS = 4
CLIP_RANGE = 0.1
TARGET_KL = 0.02

# --- Training cycle defaults (canonical — change here when the mix should change) ---
# Per-scenario drill timesteps (learning / mastered) live in scenario_curriculum.py.
# Full-game heuristic/lookahead/ONNX are off until every drill is at MAX_DIFFICULTY (scenario_b gate).
# Equal parts: HEURISTIC + LOOKAHEAD + each FULL_GAME_ONNX_OPPONENT_PROFILES seat (today: general, defense).
DEFAULT_TRAIN_HEURISTIC = 20_000
DEFAULT_TRAIN_LOOKAHEAD = 20_000
DEFAULT_TRAIN_ONNX = 20_000
DEFAULT_EVAL_HEURISTIC = 200
DEFAULT_EVAL_LOOKAHEAD = 200
DEFAULT_EVAL_ONNX = 200
# Full-game eval every N visits per profile (rotation round). eval-only always runs.
# With --profile all, global cycle%N would only ever hit targets[0] (baseline).
FULL_GAME_EVAL_EVERY_CYCLES = 4
DEFAULT_N_ENVS = 30
DEFAULT_N_STEPS = 128
DEFAULT_BATCH_SIZE = 256

# Deterministic games for each scenario after-eval (10); delta vs prior after-eval at same @d.
_VECNORMALIZE_SUFFIX = "_vecnormalize.pkl"
# (scenario, difficulty) → stats from the last after-eval this process (cycle TSV / stdout delta).
_last_scenario_after_eval: dict[tuple[str, int], dict[str, Any]] = {}
_EVAL_SECTION_GAP = "\n\n\n"
_scenario_perfect_summaries: list[str] = []


def _eval_batch_all_wins(stats: dict[str, Any], *, games: int) -> bool:
    """True on a full batch where every decided game is a US win."""
    episodes = int(stats.get("episodes") or 0)
    if episodes < games:
        return False
    return int(stats.get("wins") or 0) == games


def _eval_section_break() -> None:
    print(_EVAL_SECTION_GAP, end="", flush=True)


def _record_scenario_perfect_summary(
    stats: dict[str, Any],
    *,
    scenario: str,
    scenario_difficulty: int,
    head_agreement: dict[str, int] | None = None,
) -> None:
    wins = int(stats.get("wins") or 0)
    losses = int(stats.get("losses") or 0)
    draws = int(stats.get("draws") or 0)
    games = int(stats.get("episodes") or 0)
    winrate = float(stats.get("winrate") or 0.0)
    short = require_catalog().short_name(scenario)
    line = f"  {short} d{scenario_difficulty}  drill_rate={winrate:.3f} W{wins}/L{losses}/D{draws} ({games} ep)"
    if head_agreement and int(head_agreement.get("decisions") or 0) > 0:
        line += " " + format_head_agreement(head_agreement)
    _scenario_perfect_summaries.append(line)


def _print_scenario_perfect_summaries() -> None:
    if not _scenario_perfect_summaries:
        return
    print("\nScenario eval summary (perfect batches):", flush=True)
    for line in _scenario_perfect_summaries:
        print(line, flush=True)


def _mask_fn(env: Any) -> Any:
    return env.unwrapped.action_masks()


class _PoolWorkerRoot(gym.Wrapper):
    """Outermost worker env so SubprocVecEnv.env_method reaches segment reconfigure."""

    def apply_segment_config(self, **kwargs: Any) -> None:
        self.env.unwrapped.apply_segment_config(**kwargs)


class ActiveSubprocVecEnv(VecEnv):
    """First *n_active* envs of a shared SubprocVecEnv — idle workers stay cold."""

    def __init__(self, subproc: SubprocVecEnv, n_active: int) -> None:
        if not 1 <= n_active <= subproc.num_envs:
            raise ValueError(f"n_active must be 1..{subproc.num_envs}, got {n_active}")
        self._subproc = subproc
        self._n_active = n_active
        super().__init__(n_active, subproc.observation_space, subproc.action_space)

    def set_active(self, n_active: int) -> None:
        if not 1 <= n_active <= self._subproc.num_envs:
            raise ValueError(f"n_active must be 1..{self._subproc.num_envs}, got {n_active}")
        self._n_active = n_active
        self.num_envs = n_active

    @property
    def _remotes(self) -> list[Any]:
        return self._subproc.remotes[: self._n_active]

    def _resolve_indices(self, indices: int | list[int] | None) -> list[int]:
        if indices is None:
            return list(range(self._n_active))
        if isinstance(indices, int):
            return [indices]
        return list(indices)

    def reset(self) -> Any:
        for env_idx, remote in enumerate(self._remotes):
            remote.send(("reset", (self._subproc._seeds[env_idx], self._subproc._options[env_idx])))
        results = [remote.recv() for remote in self._remotes]
        obs, _reset_infos = zip(*results, strict=True)
        self._subproc._reset_seeds()
        self._subproc._reset_options()
        return _stack_obs(obs, self.observation_space)

    def step_async(self, actions: np.ndarray) -> None:
        for remote, action in zip(self._remotes, actions, strict=True):
            remote.send(("step", action))
        self._subproc.waiting = True

    def step_wait(self) -> Any:
        results = [remote.recv() for remote in self._remotes]
        self._subproc.waiting = False
        obs, rews, dones, infos, _reset_infos = zip(*results, strict=True)
        return _stack_obs(obs, self.observation_space), np.stack(rews), np.stack(dones), infos

    def close(self) -> None:
        return

    def env_is_wrapped(self, wrapper_class: type[gym.Wrapper]) -> bool:
        return self._subproc.env_is_wrapped(wrapper_class)

    def env_method(
        self,
        method_name: str,
        *method_args: Any,
        indices: int | list[int] | None = None,
        **method_kwargs: Any,
    ) -> list[Any]:
        return self._subproc.env_method(
            method_name,
            *method_args,
            indices=self._resolve_indices(indices),
            **method_kwargs,
        )

    def get_attr(self, attr_name: str, indices: int | list[int] | None = None) -> list[Any]:
        return self._subproc.get_attr(attr_name, indices=self._resolve_indices(indices))

    def set_attr(self, attr_name: str, value: Any, indices: int | list[int] | None = None) -> None:
        self._subproc.set_attr(attr_name, value, indices=self._resolve_indices(indices))


@dataclass(frozen=True)
class SegmentEnvConfig:
    opponent_mode: str
    computer_opponent: str
    onnx_profile: RewardProfileName | None
    policy_seat: str
    reward_shaping: RewardShaping
    scenario: str | None
    scenario_difficulty: int
    episode_mode: str


class WorkerPool:
    """One SubprocVecEnv for the process — segments reconfigure workers instead of respawning."""

    def __init__(self, args: argparse.Namespace, *, n_envs: int = DEFAULT_N_ENVS) -> None:
        self._n_envs = n_envs
        t0 = time.perf_counter()
        print(f"Worker pool: spawning {n_envs} workers (once)…", flush=True)
        self._subproc = make_vec_env(
            args.base_url,
            DEFAULT_MAX_EPISODE_STEPS,
            n_envs,
            continue_on_submit_error=args.continue_on_submit_error,
            opponent_mode=OPPONENT_COMPUTER,
            computer_opponent="LOOKAHEAD",
            onnx_profile=None,
            policy_seat=DEFAULT_POLICY_SEAT,
            reward_shaping=RewardShaping(),
            scenario=None,
            scenario_difficulty=0,
            episode_mode="TRAIN",
        )
        self._active_view = ActiveSubprocVecEnv(self._subproc, n_envs)
        self._last_config: SegmentEnvConfig | None = None
        print(f"  worker pool ready in {_fmt_wall_s(time.perf_counter() - t0)}", flush=True)

    def configure(
        self,
        *,
        opponent_mode: str,
        computer_opponent: str,
        onnx_profile: RewardProfileName | None,
        policy_seat: str,
        reward_shaping: RewardShaping,
        scenario: str | None,
        scenario_difficulty: int,
        episode_mode: str,
    ) -> None:
        config = SegmentEnvConfig(
            opponent_mode=opponent_mode,
            computer_opponent=computer_opponent,
            onnx_profile=onnx_profile,
            policy_seat=policy_seat,
            reward_shaping=reward_shaping,
            scenario=scenario,
            scenario_difficulty=scenario_difficulty,
            episode_mode=episode_mode,
        )
        if config == self._last_config:
            return
        self._subproc.env_method(
            "apply_segment_config",
            opponent_mode=opponent_mode,
            computer_opponent=computer_opponent,
            onnx_profile=onnx_profile,
            policy_seat=policy_seat,
            reward_shaping=reward_shaping,
            scenario=scenario,
            scenario_difficulty=scenario_difficulty,
            episode_mode=episode_mode,
        )
        self._last_config = config

    def vec_env(self, n_envs: int) -> VecEnv:
        if not 1 <= n_envs <= self._n_envs:
            raise ValueError(f"n_envs must be 1..{self._n_envs}, got {n_envs}")
        if n_envs == self._n_envs:
            return self._subproc
        self._active_view.set_active(n_envs)
        return self._active_view

    def close(self) -> None:
        _close_vec_env(self._subproc)


def make_env(
    base_url: str,
    max_episode_steps: int,
    *,
    continue_on_submit_error: bool = False,
    opponent_mode: str = OPPONENT_COMPUTER,
    computer_opponent: str = "LOOKAHEAD",
    onnx_profile: RewardProfileName | None = None,
    policy_seat: str = "HOME",
    reward_shaping: RewardShaping | None = None,
    scenario: str | None = None,
    scenario_difficulty: int = 0,
    episode_mode: str = "TRAIN",
) -> _PoolWorkerRoot:
    return _PoolWorkerRoot(
        ActionMasker(
            Monitor(
                GridironEnv(
                    base_url=base_url,
                    max_episode_steps=max_episode_steps,
                    continue_on_submit_error=continue_on_submit_error,
                    opponent_mode=opponent_mode,
                    computer_opponent=computer_opponent,
                    onnx_profile=onnx_profile,
                    policy_seat=policy_seat,
                    reward_shaping=reward_shaping,
                    scenario=scenario,
                    scenario_difficulty=scenario_difficulty,
                    episode_mode=episode_mode,
                )
            ),
            _mask_fn,
        )
    )


def _env_thunk(
    base_url: str,
    max_episode_steps: int,
    continue_on_submit_error: bool,
    opponent_mode: str,
    computer_opponent: str,
    onnx_profile: RewardProfileName | None,
    policy_seat: str,
    reward_shaping: RewardShaping | None,
    scenario: str | None,
    scenario_difficulty: int,
    episode_mode: str,
) -> Callable[[], _PoolWorkerRoot]:
    def _init() -> _PoolWorkerRoot:
        logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s", force=True)
        return make_env(
            base_url,
            max_episode_steps,
            continue_on_submit_error=continue_on_submit_error,
            opponent_mode=opponent_mode,
            computer_opponent=computer_opponent,
            onnx_profile=onnx_profile,
            policy_seat=policy_seat,
            reward_shaping=reward_shaping,
            scenario=scenario,
            scenario_difficulty=scenario_difficulty,
            episode_mode=episode_mode,
        )

    return _init


def make_vec_env(
    base_url: str,
    max_episode_steps: int,
    n_envs: int,
    *,
    continue_on_submit_error: bool = False,
    opponent_mode: str = OPPONENT_COMPUTER,
    computer_opponent: str = "LOOKAHEAD",
    onnx_profile: RewardProfileName | None = None,
    policy_seat: str = "HOME",
    reward_shaping: RewardShaping | None = None,
    scenario: str | None = None,
    scenario_difficulty: int = 0,
    episode_mode: str = "TRAIN",
) -> SubprocVecEnv:
    # spawn: N fresh Python interpreters; each imports torch/sb3/gym and builds GridironEnv
    # (HTTP client only — backend matches start on first reset(), not during spawn).
    return SubprocVecEnv(
        [
            _env_thunk(
                base_url,
                max_episode_steps,
                continue_on_submit_error,
                opponent_mode,
                computer_opponent,
                onnx_profile,
                policy_seat,
                reward_shaping,
                scenario,
                scenario_difficulty,
                episode_mode,
            )
            for _ in range(n_envs)
        ],
        start_method="spawn",
    )


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Train/eval driver — invoked by ./train.sh, ./eval.sh, and ./start_over.sh (not day-to-day CLI)"
    )
    p.add_argument("--base-url", default=DEFAULT_BASE_URL, help=argparse.SUPPRESS)
    p.add_argument("--cycles", type=int, default=0, help="How many times to run the train/eval block (0 = forever until Ctrl-C)")
    p.add_argument("--profile", default="all", choices=("all", *REWARD_PROFILES), help="Reward profile or all (default: all — baseline + general + spread; pin td/defense; summary still lists them)")
    p.add_argument(
        "--skip-scenarios",
        action="store_true",
        help="Skip scenario train; with --eval-only skip scenario eval too (eval.sh)",
    )
    p.add_argument("--train-scenario-learning", type=int, default=DEFAULT_SCENARIO_TRAIN_LEARNING, help="PPO timesteps per scenario still below difficulty 5 (0 = skip learning scenarios)")
    p.add_argument("--train-scenario-mastered", type=int, default=DEFAULT_SCENARIO_TRAIN_MASTERED, help="PPO timesteps per scenario already at difficulty 5 while pack climbs (0 = skip)")
    p.add_argument("--train-heuristic", type=int, default=DEFAULT_TRAIN_HEURISTIC, help="PPO timesteps vs HEURISTIC computer per cycle (0 = skip)")
    p.add_argument("--train-lookahead", type=int, default=DEFAULT_TRAIN_LOOKAHEAD, help="PPO timesteps vs LOOKAHEAD computer per cycle (0 = skip)")
    p.add_argument("--train-onnx", type=int, default=DEFAULT_TRAIN_ONNX, help=f"PPO timesteps per ONNX profile seat per cycle ({', '.join(FULL_GAME_ONNX_OPPONENT_PROFILES)}; 0 = skip)")
    p.add_argument("--eval-heuristic", type=int, default=DEFAULT_EVAL_HEURISTIC, help=f"Deterministic eval games vs HEURISTIC (train: every {FULL_GAME_EVAL_EVERY_CYCLES} visits/profile; eval-only: each pass; 0 = skip)")
    p.add_argument("--eval-lookahead", type=int, default=DEFAULT_EVAL_LOOKAHEAD, help=f"Deterministic eval games vs LOOKAHEAD (train: every {FULL_GAME_EVAL_EVERY_CYCLES} visits/profile; eval-only: each pass; 0 = skip)")
    p.add_argument("--eval-onnx", type=int, default=DEFAULT_EVAL_ONNX, help=f"Deterministic eval games per ONNX profile ({', '.join(FULL_GAME_ONNX_OPPONENT_PROFILES)}; same cadence as heuristic/lookahead; 0 = skip)")
    p.add_argument("--eval-only", action="store_true", help="Skip all train; eval pass without eval JSON (eval.sh sets this)")
    p.add_argument("--eval-episode-mode", choices=("EVAL", "TRAIN"), default="EVAL", help="Scenario/full-game eval episodeMode (EVAL=default; TRAIN enables soft-mask search on multi-step claims)")
    p.add_argument("--scenario", default=None, help="One drill only — skips full-game train/eval (default: cycle all from server)")
    p.add_argument("--force-promote", action="store_true", help="Persist next phase gate without live curriculum (at profiles: sticky full-game unlock); fail if checkpoint missing")
    p.add_argument("--continue-on-submit-error", action="store_true", help=argparse.SUPPRESS)
    p.add_argument("--reset-long-running", action="store_true", help="Delete long-running checkpoints and TensorBoard logs for the selected profile(s), then exit (start_over.sh)")
    return p.parse_args()


def _reset_long_running(checkpoint_dir: Path, tb_log: Path, *, eval_dir: Path | None = None) -> None:
    """Drop an entire checkpoint tree (+ optional eval dir). Mirrors ``common.sh`` profile wipe."""
    removed: list[str] = []
    if checkpoint_dir.is_dir():
        shutil.rmtree(checkpoint_dir)
        removed.append(str(checkpoint_dir))
    elif checkpoint_dir.is_file():
        checkpoint_dir.unlink()
        removed.append(str(checkpoint_dir))
    if tb_log.is_dir():
        shutil.rmtree(tb_log)
        removed.append(str(tb_log))
    if eval_dir is not None and eval_dir.is_dir():
        shutil.rmtree(eval_dir)
        removed.append(str(eval_dir))
    if removed:
        print("Removed long-running artifacts:")
        for line in removed:
            print(f"  {line}")
    else:
        suffix = f" / {eval_dir}" if eval_dir is not None else ""
        print(f"No long-running artifacts to remove under {checkpoint_dir} / {tb_log}{suffix}.")


def _reset_profiles(profile_arg: str) -> None:
    # ``all`` resets post-split profile trees (baseline + shaped). Scenario phase dirs stay.
    # Never wipe evals/baseline here — pre-split JSONs back the synthetic summary scenario_b row.
    if profile_arg == "all":
        keys: tuple[str, ...] = ("baseline", *SHAPED_PROFILES)
    elif profile_arg == "baseline" or profile_arg in SHAPED_PROFILES:
        keys = (profile_arg,)
    else:
        raise SystemExit(f"unknown profile: {profile_arg}")
    for key in keys:
        _reset_long_running(
            CHECKPOINT_DIR / key,
            runs_dir(key),
            eval_dir=None if key == "baseline" else EVAL_DIR / key,
        )
    rewind_pipeline("profiles", profile=profile_arg)
    # Legacy pre-profile zip (schema v4 era) — safe to clear on reset.
    legacy = CHECKPOINT_DIR / f"{_LONG_RUNNING_STEM}.zip"
    if legacy.is_file():
        legacy.unlink()
        print(f"Removed legacy checkpoint {legacy}")


def _set_learning_rate(model: MaskablePPO, learning_rate: float) -> None:
    """Apply learning rate on create and resume (zip may still carry an older schedule)."""
    model.learning_rate = learning_rate
    model.lr_schedule = lambda _progress: learning_rate
    for group in model.policy.optimizer.param_groups:
        group["lr"] = learning_rate


def _ent_coef_for_profile(profile: RewardProfileName) -> float:
    return PROFILE_ENT_COEF.get(profile, DEFAULT_ENT_COEF)


def _vf_coef_for_profile(profile: RewardProfileName) -> float:
    return PROFILE_VF_COEF.get(profile, DEFAULT_VF_COEF)


def _set_ent_coef(model: MaskablePPO, profile: RewardProfileName) -> None:
    """Apply profile ent_coef on create and resume (zip may still carry an older 0.0)."""
    model.ent_coef = _ent_coef_for_profile(profile)


def _set_vf_coef(model: MaskablePPO, profile: RewardProfileName) -> None:
    """Apply profile vf_coef on create and resume (zip may still carry stock 0.5)."""
    model.vf_coef = _vf_coef_for_profile(profile)


def _set_ppo_stability(model: MaskablePPO) -> None:
    """Apply critic/KL knobs on create and resume (zip may still carry stock defaults)."""
    model.n_epochs = N_EPOCHS
    model.clip_range = lambda _progress: CLIP_RANGE
    model.target_kl = TARGET_KL


def _vecnormalize_path(save_stem: Path) -> Path:
    return Path(str(save_stem) + _VECNORMALIZE_SUFFIX)


def _wrap_vecnormalize(env: SubprocVecEnv, save_stem: Path, *, training: bool) -> VecNormalize:
    """Reward-normalize rollouts; reload running stats from the checkpoint sidecar when present."""
    stats_path = _vecnormalize_path(save_stem)
    if stats_path.is_file():
        wrapped = VecNormalize.load(str(stats_path), env)
        wrapped.training = training
        wrapped.norm_reward = training
        return wrapped
    return VecNormalize(
        env,
        training=training,
        norm_obs=False,
        norm_reward=training,
        clip_reward=10.0,
        gamma=0.99,
    )


def _save_vecnormalize(env: Any, save_stem: Path) -> None:
    if isinstance(env, VecNormalize):
        env.save(str(_vecnormalize_path(save_stem)))


def _release_segment_env(env: Any, pool: WorkerPool | None) -> None:
    """Drop a segment VecNormalize wrapper without tearing down a shared worker pool."""
    if pool is not None:
        return
    _close_vec_env(env)


def _close_vec_env(env: Any) -> None:
    """Close a VecEnv; SB3 SubprocVecEnv leaves parent Pipe FDs open after ``close()``."""
    subproc: SubprocVecEnv | None = env.venv if isinstance(env, VecNormalize) else env
    if not isinstance(subproc, SubprocVecEnv):
        subproc = None
    env.close()
    if subproc is None:
        return
    for remote in subproc.remotes:
        try:
            remote.close()
        except OSError:
            pass
    for process in subproc.processes:
        try:
            process.close()
        except OSError:
            pass


def _require_batch_divides_rollout(n_steps: int, n_envs: int, batch_size: int) -> None:
    rollout = n_steps * n_envs
    if rollout % batch_size == 0:
        return
    hint = next((s for s in (256, 128, 64, 32, 16, 8, 4, 2, 1) if rollout % s == 0), 1)
    raise SystemExit(
        f"batch_size={batch_size} must divide n_steps×n_envs={rollout} "
        f"(got remainder {rollout % batch_size}); edit DEFAULT_BATCH_SIZE in train_self_play.py "
        f"(try {hint})"
    )


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _fmt_utc(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _fmt_wall_s(seconds: float) -> str:
    if seconds < 0.05:
        return f"{seconds * 1000:.0f}ms"
    if seconds < 60:
        return f"{seconds:.1f}s"
    minutes = int(seconds // 60)
    return f"{minutes}m {seconds % 60:.0f}s"


class _SegmentTimer:
    """Wall-clock laps for train/eval segments (stdout only)."""

    __slots__ = ("_t0", "_last")

    def __init__(self) -> None:
        self._t0 = time.perf_counter()
        self._last = self._t0

    def lap(self, label: str, *, echo: bool = True) -> float:
        now = time.perf_counter()
        dt = now - self._last
        self._last = now
        if echo:
            print(f"  {label}: {_fmt_wall_s(dt)}", flush=True)
        return dt

    def total_s(self) -> float:
        return time.perf_counter() - self._t0

    def summary(self, *parts: tuple[str, float]) -> str:
        bits = [f"{name} {_fmt_wall_s(seconds)}" for name, seconds in parts]
        bits.append(f"total {_fmt_wall_s(self.total_s())}")
        return "; ".join(bits)


def _append_tsv_row(path: Path, row: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    columns = list(row.keys())
    write_header = not path.is_file() or path.stat().st_size == 0
    with path.open("a", encoding="utf-8") as f:
        if write_header:
            f.write("\t".join(columns) + "\n")
        f.write("\t".join(str(row[c]) for c in columns) + "\n")


def _cycle_stats_fields(key: str, stats: dict[str, Any]) -> dict[str, Any]:
    """Flat cycle-TSV columns shared by train and eval segments."""
    return {
        f"{key}_episodes": stats["episodes"],
        f"{key}_winrate": f"{stats['winrate']:.6f}",
        f"{key}_mean_diff": f"{stats['score_diff']:.4f}",
        f"{key}_td": f"{stats['touchdowns']:.4f}",
        f"{key}_td_them": f"{stats['touchdowns_them']:.4f}",
        f"{key}_cards": f"{stats['challenge_cards']:.4f}",
        f"{key}_cards_them": f"{stats['challenge_cards_them']:.4f}",
        f"{key}_ball_us": f"{stats['ball_rate_us']:.4f}",
        f"{key}_ball_them": f"{stats['ball_rate_them']:.4f}",
        f"{key}_opp_half_us": f"{stats['ball_opp_half_rate_us']:.4f}",
        f"{key}_opp_half_them": f"{stats['ball_opp_half_rate_them']:.4f}",
        f"{key}_ball_prox": f"{stats['ball_proximity_us']:.4f}",
        f"{key}_ball_gained_us": f"{stats['ball_gained_us']:.4f}",
        f"{key}_ball_gained_them": f"{stats['ball_gained_them']:.4f}",
        f"{key}_td_rate": f"{stats.get('td_rate', 0.0):.6f}",
        f"{key}_win_by_4_rate": f"{stats.get('win_by_4_rate', 0.0):.6f}",
        f"{key}_difficulty": stats.get("scenario_difficulty", ""),
        f"{key}_first_legals": (
            f"{stats['first_action_legal_count']:.1f}"
            if stats.get("first_action_legal_count") is not None
            else ""
        ),
    }


def _episode_success_rates(episodes: list[dict[str, Any]]) -> dict[str, float]:
    n = len(episodes)
    if n == 0:
        return {"td_rate": 0.0, "win_by_4_rate": 0.0}
    td_rate = sum(1 for ep in episodes if int(ep.get("touchdowns") or 0) >= 1) / n
    win_by_4_rate = sum(
        1
        for ep in episodes
        if ep.get("score_us") is not None
        and ep.get("score_them") is not None
        and int(ep["score_us"]) - int(ep["score_them"]) >= 4
    ) / n
    return {"td_rate": td_rate, "win_by_4_rate": win_by_4_rate}


def _reward_shaping_for_profile(profile: RewardProfileName) -> RewardShaping:
    """Profile preset only — no CLI overrides (keeps the reward stack inspectable)."""
    return PROFILE_SHAPING[profile]


def _save_step_checkpoint(model: MaskablePPO, save_dir: Path, *, name_prefix: str, timesteps: int) -> None:
    """Keep a ``*_steps.zip`` snapshot when this segment crossed a ``STEP_CHECKPOINT_FREQ`` boundary.

    DEVIATION: stock SB3 ``CheckpointCallback`` counts calls since its own construction, and each
    segment builds a fresh one for a few thousand timesteps — any ``save_freq`` above one segment
    would never fire, so the boundary is checked on cumulative ``num_timesteps`` here instead.
    """
    after = int(model.num_timesteps)
    if after // STEP_CHECKPOINT_FREQ <= max(after - timesteps, 0) // STEP_CHECKPOINT_FREQ:
        return
    atomic_model_save(model, save_dir / f"{name_prefix}_{after}_steps")
    trim_step_checkpoints(save_dir, name_prefix=name_prefix)


def _train_segment(
    model: MaskablePPO,
    *,
    args: argparse.Namespace,
    pool: WorkerPool,
    timesteps: int,
    opponent_mode: str,
    computer_opponent: str,
    onnx_profile: RewardProfileName | None,
    save_stem: Path,
    save_dir: Path,
    name_prefix: str,
    reset_num_timesteps: bool,
    reward_shaping: RewardShaping,
    profile: RewardProfileName,
    scenario: str | None = None,
    scenario_difficulty: int = 0,
) -> dict[str, Any]:
    """One ``learn()`` block; swaps vec-env to the requested opponent mode."""
    opp_label = opponent_metrics_label(computer_opponent, onnx_profile)
    if scenario is not None:
        label = f"vs-{opp_label}-scenario-{scenario.lower()}-d{scenario_difficulty}"
    else:
        label = f"vs-{opp_label}"
    print(
        f"Train {label}: {timesteps} timesteps "
        f"(n_envs={DEFAULT_N_ENVS}, n_steps={model.n_steps}, batch_size={model.batch_size})",
        flush=True,
    )
    clk = _SegmentTimer()
    pool.configure(
        opponent_mode=opponent_mode,
        computer_opponent=computer_opponent,
        onnx_profile=onnx_profile,
        policy_seat=DEFAULT_POLICY_SEAT,
        reward_shaping=reward_shaping,
        scenario=scenario,
        scenario_difficulty=scenario_difficulty,
        episode_mode="TRAIN",
    )
    configure_s = clk.lap("configure workers")
    env = _wrap_vecnormalize(pool.vec_env(DEFAULT_N_ENVS), save_stem, training=True)
    vecnorm_s = clk.lap("vecnormalize load")
    model.set_env(env)
    _require_batch_divides_rollout(int(model.n_steps), DEFAULT_N_ENVS, int(model.batch_size))

    metrics_cb = GridironMetricsCallback(
        profile=profile,
        computer_opponent=opp_label,
        scenario=scenario if scenario is not None else "regular game",
        scenario_difficulty=scenario_difficulty if scenario is not None else None,
        verbose=1,
    )
    callbacks: list[Any] = [metrics_cb]
    if FINAL_SAVE_FREQ > 0 and timesteps >= FINAL_SAVE_FREQ:
        callbacks.append(PeriodicFinalSaveCallback(save_stem, FINAL_SAVE_FREQ))

    try:
        model.learn(
            total_timesteps=timesteps,
            callback=CallbackList(callbacks),
            progress_bar=False,
            reset_num_timesteps=reset_num_timesteps,
        )
    except (GridironApiError, EOFError):
        _release_segment_env(env, pool)
        raise SystemExit(1) from None
    learn_s = clk.lap("learn")
    _save_vecnormalize(env, save_stem)
    vecnorm_save_s = clk.lap("vecnormalize save")
    atomic_model_save(model, save_stem)
    _save_step_checkpoint(model, save_dir, name_prefix=name_prefix, timesteps=timesteps)
    ckpt_save_s = clk.lap("checkpoint save")
    stats = metrics_cb.session_summary()
    if scenario is not None:
        stats["scenario_difficulty"] = scenario_difficulty
    _release_segment_env(env, pool)
    print(
        f"  timing: {clk.summary(('configure', configure_s), ('vecnorm', vecnorm_s), ('learn', learn_s), ('vecnorm save', vecnorm_save_s), ('ckpt save', ckpt_save_s))}",
        flush=True,
    )
    print_metrics(f"Train {label}", stats)
    return stats


def _scenario_names(*, pin: str | None) -> list[str]:
    """Names from the process catalog (refreshed once at train startup)."""
    catalog = require_catalog()
    if pin is not None:
        try:
            return [resolve_scenario_pin(pin, catalog)]
        except ValueError as exc:
            raise SystemExit(str(exc)) from None
    return catalog.names()


def _eval_one_scenario(
    model: MaskablePPO,
    *,
    args: argparse.Namespace,
    pool: WorkerPool,
    profile: RewardProfileName,
    scenario: str,
    difficulty: int,
) -> dict[str, Any]:
    """Deterministic eval for one drill at its current curriculum difficulty."""
    return _eval_segment(
        model,
        args=args,
        pool=pool,
        games=SCENARIO_EVAL_GAMES,
        opponent_mode=OPPONENT_COMPUTER,
        computer_opponent="LOOKAHEAD",
        onnx_profile=None,
        profile=profile,
        scenario=scenario,
        scenario_difficulty=difficulty,
    )


def _apply_scenario_after_eval(
    *,
    scenario: str,
    difficulty: int,
    stats: dict[str, Any],
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]],
    drill_stats: dict[str, dict[str, int]],
    curriculum_path: Path,
    gate_failures_path: Path,
    drill_stats_path: Path,
) -> tuple[dict[str, int], dict[str, dict[str, int]], dict[str, dict[str, int]]]:
    """Persist bump / demote / gate-failure / drill-stat counts from one after-eval pass."""
    levels, gate_failures, drill_stats, action = apply_after_eval_curriculum(
        levels,
        gate_failures,
        scenario,
        stats,
        expected_games=SCENARIO_EVAL_GAMES,
        drill_stats=drill_stats,
    )
    full_batch = int(stats.get("episodes") or 0) >= SCENARIO_EVAL_GAMES
    if not full_batch:
        return levels, gate_failures, drill_stats
    if action in ("bump", "demote"):
        save_levels(curriculum_path, levels)
    if action in ("demote", "fail", "waive", "recover"):
        save_gate_failures(gate_failures_path, gate_failures)
    if action == "demote":
        save_drill_stats(drill_stats_path, drill_stats)
    new_level = get_difficulty(levels, scenario)
    fails = gate_failure_count(gate_failures, scenario, difficulty=difficulty)
    if action == "bump":
        print(
            f"  curriculum: {scenario} passed at d{difficulty} → d{new_level} ({curriculum_path.name})",
            flush=True,
        )
    elif action == "demote":
        print(
            f"  curriculum: {scenario} failed at d{difficulty} → d{new_level} "
            f"(gate_failures d{difficulty}={fails}; {curriculum_path.name})",
            flush=True,
        )
    elif action == "waive":
        print(
            f"  curriculum: {scenario} progress gating waived at d{difficulty} "
            f"({fails} fails; still training; {gate_failures_path.name})",
            flush=True,
        )
    elif action == "recover":
        print(
            f"  curriculum: {scenario} passed at d{difficulty} — progress gating restored "
            f"({curriculum_path.name})",
            flush=True,
        )
    elif action == "fail":
        print(
            f"  curriculum: {scenario} gate fail at d{difficulty} "
            f"(failures={fails}; {gate_failures_path.name})",
            flush=True,
        )
    return levels, gate_failures, drill_stats


def _train_scenarios_segment(
    model: MaskablePPO,
    *,
    args: argparse.Namespace,
    pool: WorkerPool,
    save_stem: Path,
    save_dir: Path,
    name_prefix: str,
    reset_num_timesteps: bool,
    reward_shaping: RewardShaping,
    profile: RewardProfileName,
    game_phase: bool,
) -> dict[str, Any]:
    """Climbing: train → after-eval. Game phase: eval all, then train stragglers → after-eval."""
    scenarios = _scenario_names(pin=args.scenario)
    if not scenarios:
        print("Train scenarios: catalog empty — skip", flush=True)
        return {}

    curriculum_path = path_for_stem(save_stem)
    gate_failures_path = path_for_gate_failures(save_stem)
    drill_stats_path = path_for_drill_stats(save_stem)
    levels = load_levels(curriculum_path)
    gate_failures = load_gate_failures(gate_failures_path)
    drill_stats = load_drill_stats(drill_stats_path)
    cumulative_drill_stats = load_drill_stats_cumulative(save_stem.parent.name)

    row: dict[str, Any] = {}
    if game_phase:
        row = _eval_scenarios_segment(
            model,
            args=args,
            pool=pool,
            profile=profile,
            save_stem=save_stem,
            update_curriculum=not args.eval_only,
        )
        levels = load_levels(curriculum_path)
        gate_failures = load_gate_failures(gate_failures_path)
        drill_stats = load_drill_stats(drill_stats_path)

    active = scenarios_for_cycle(levels, scenarios, gate_failures)
    plan: list[tuple[str, int, int]] = []
    for scenario in active:
        difficulty = get_difficulty(levels, scenario)
        timesteps = train_timesteps_for_scenario(
            scenario,
            levels,
            scenarios,
            learning=args.train_scenario_learning,
            mastered=args.train_scenario_mastered,
            game_phase=game_phase,
            gate_failures=gate_failures,
            drill_stats=cumulative_drill_stats,
        )
        if timesteps > 0:
            plan.append((scenario, difficulty, timesteps))
    if not plan:
        msg = (
            "Game phase: scenario eval done — no stragglers or soft-d5 to train"
            if game_phase
            else "Train scenarios: all per-scenario timesteps 0 — skip"
        )
        print(msg, flush=True)
        return row

    prefix = "Game phase scenario train" if game_phase else "Train scenarios contiguous"
    budget = ", ".join(f"{scenario} d{difficulty}→{timesteps}ts" for scenario, difficulty, timesteps in plan)
    print(f"{prefix} ({len(plan)} drills): {budget}", flush=True)

    did_train = False
    proxy_sources = require_catalog().proxy_sources()
    for scenario, difficulty, timesteps in plan:
        slug = scenario.lower()
        do_eval = scenario not in proxy_sources
        if not do_eval:
            proxy = require_catalog().curriculum_key(scenario)
            print(
                f"Scenario {scenario} d{difficulty}: train {timesteps}ts "
                f"(no scenario eval — difficulty tracks {proxy})",
                flush=True,
            )
        else:
            print(f"Scenario {scenario} d{difficulty}: train {timesteps}ts → after-eval", flush=True)

        stats = _train_segment(
            model,
            args=args,
            pool=pool,
            timesteps=timesteps,
            opponent_mode=OPPONENT_COMPUTER,
            computer_opponent="LOOKAHEAD",
            onnx_profile=None,
            save_stem=save_stem,
            save_dir=save_dir,
            name_prefix=name_prefix,
            reset_num_timesteps=reset_num_timesteps and not did_train,
            reward_shaping=reward_shaping,
            profile=profile,
            scenario=scenario,
            scenario_difficulty=difficulty,
        )
        did_train = True
        row.update(_cycle_stats_fields(f"train_scenario_{slug}", stats))
        base_ts = train_timesteps_for_difficulty(
            difficulty,
            learning=args.train_scenario_learning,
            mastered=args.train_scenario_mastered,
            game_phase=game_phase,
        )
        units = max(1, timesteps // base_ts) if base_ts > 0 else 1
        drill_stats = record_train_cycles(drill_stats, scenario, units=units)
        save_drill_stats(drill_stats_path, drill_stats)

        if not do_eval:
            _eval_section_break()
            continue

        after = _eval_one_scenario(
            model, args=args, pool=pool, profile=profile, scenario=scenario, difficulty=difficulty
        )
        row.update(_cycle_stats_fields(f"eval_scenario_{slug}_after", after))
        after_rate = float(after["winrate"])
        prior = _last_scenario_after_eval.get((scenario, difficulty))
        if prior is not None:
            prior_rate = float(prior["winrate"])
            delta = after_rate - prior_rate
            row[f"eval_scenario_{slug}_prior_winrate"] = f"{prior_rate:.6f}"
            row[f"eval_scenario_{slug}_delta_winrate"] = f"{delta:.6f}"
            print(
                f"  scenario learn: {scenario} d{difficulty} "
                f"drill_rate {prior_rate:.3f} → {after_rate:.3f} (Δ={delta:+.3f})",
                flush=True,
            )
        else:
            print(
                f"  scenario learn: {scenario} d{difficulty} drill_rate {after_rate:.3f}",
                flush=True,
            )
        _last_scenario_after_eval[(scenario, difficulty)] = after
        levels, gate_failures, drill_stats = _apply_scenario_after_eval(
            scenario=scenario,
            difficulty=difficulty,
            stats=after,
            levels=levels,
            gate_failures=gate_failures,
            drill_stats=drill_stats,
            curriculum_path=curriculum_path,
            gate_failures_path=gate_failures_path,
            drill_stats_path=drill_stats_path,
        )
        _eval_section_break()
    return row


def _attach_opening_mispick_diag(model: MaskablePPO, summary: dict[str, Any]) -> None:
    """Score biased vs plain actor×kind mass for the first lesson mispick — THEM only."""
    snapshot = summary.pop("_opening_mispick_snapshot", None)
    if snapshot is None:
        return
    if summary.get("result_winner_rel") != "THEM":
        summary.pop("opening_mispick", None)
        summary.pop("opening_mispick_chosen", None)
        return
    try:
        summary["opening_mispick_diag"] = diagnose_opening_mispick(model.policy, snapshot)
    except Exception as exc:
        summary["opening_mispick_diag"] = {"error": str(exc)}


def _format_eval_game_line(
    summary: dict[str, Any] | None,
    *,
    game_num: int,
    games: int,
    truncated: bool,
    scenario: str | None = None,
) -> str:
    if summary is None:
        return (
            f"  game {game_num}/{games} MISSING bb_episode "
            f"(truncated={truncated}) — not counted"
        )
    outcome = summary.get("result_winner_rel") or ("DRAW" if summary.get("result_kind") else "OPEN")
    line = (
        f"  game {game_num}/{games} outcome={outcome} "
        f"score={summary.get('score_us')}-{summary.get('score_them')} "
        f"td={summary.get('touchdowns')}/{summary.get('touchdowns_them')} "
        f"cards={summary.get('challenge_cards')}/{summary.get('challenge_cards_them')} "
        f"ball={summary.get('ball_rate_us', 0):.2f}/{summary.get('ball_rate_them', 0):.2f} "
        f"opp_half={summary.get('ball_opp_half_rate_us', 0):.2f}/{summary.get('ball_opp_half_rate_them', 0):.2f} "
        f"prox={summary.get('ball_proximity_us', 0):.2f} "
        f"gained={summary.get('ball_gained_us', 0)}/{summary.get('ball_gained_them', 0)}"
    )
    head = summary.get("head_agreement")
    if head and int(head.get("decisions") or 0) > 0:
        line += " " + format_head_agreement(head)
    if scenario is not None:
        difficulty = summary.get("scenario_difficulty")
        if difficulty is not None:
            line += f" difficulty={difficulty}"
        line += ScenarioEvalDebug.turn1_log_suffix(summary)
    return line


def _log_eval_game(
    summary: dict[str, Any] | None,
    *,
    game_num: int,
    games: int,
    truncated: bool,
    scenario: str | None = None,
    sink: list[str] | None = None,
) -> None:
    line = _format_eval_game_line(
        summary,
        game_num=game_num,
        games=games,
        truncated=truncated,
        scenario=scenario,
    )
    if sink is not None:
        sink.append(line)
    else:
        print(line, flush=True)


def _eval_segment(
    model: MaskablePPO,
    *,
    args: argparse.Namespace,
    pool: WorkerPool,
    games: int,
    opponent_mode: str,
    computer_opponent: str,
    onnx_profile: RewardProfileName | None,
    profile: RewardProfileName,
    scenario: str | None = None,
    scenario_difficulty: int = 0,
) -> dict[str, Any]:
    """Deterministic games; shaping off so metrics are real match outcomes."""
    workers = min(games, DEFAULT_N_ENVS)
    opp_label = opponent_metrics_label(computer_opponent, onnx_profile)
    if scenario is not None:
        label = f"vs-{opp_label}-scenario-{scenario.lower()}-d{scenario_difficulty}"
    else:
        label = f"vs-{opp_label}"
    tagged = f"[{profile}] {label}"
    episode_mode = args.eval_episode_mode
    header = (
        f"Eval {tagged}: {games} games (deterministic, shaping off, "
        f"episode_mode={episode_mode}, n_envs={workers})…"
    )
    quiet_scenario = scenario is not None
    game_lines: list[str] | None = [] if quiet_scenario else None
    echo_laps = not quiet_scenario
    if echo_laps:
        print(header, flush=True)
    elif not args.eval_only:
        print(header, flush=True)
    clk = _SegmentTimer()
    pool.configure(
        opponent_mode=opponent_mode,
        computer_opponent=computer_opponent,
        onnx_profile=onnx_profile,
        policy_seat=DEFAULT_POLICY_SEAT,
        reward_shaping=RewardShaping(),
        scenario=scenario,
        scenario_difficulty=scenario_difficulty,
        episode_mode=episode_mode,
    )
    configure_s = clk.lap("configure workers", echo=echo_laps)
    env = pool.vec_env(workers)
    episodes: list[dict[str, Any]] = []
    head_segment = new_head_agreement()
    try:
        obs = env.reset()
        reset_s = clk.lap(f"reset {workers} matches", echo=echo_laps)
        completions = 0
        head_pending = [new_head_agreement() for _ in range(workers)]
        while completions < games:
            actions, _states = model.predict(obs, deterministic=True)
            # Factor-head agreement only applies to the old MultiDiscrete remask policy.
            if isinstance(model.action_space, gym.spaces.MultiDiscrete):
                for env_index, decision in enumerate(diagnose_eval_actions(model.policy, obs, actions)):
                    if decision is not None:
                        record_head_decision(head_pending[env_index], decision)
            obs, _rewards, dones, infos = env.step(actions)
            for env_index, (info, done) in enumerate(zip(infos, dones)):
                if not done:
                    continue
                completions += 1
                summary = info.get("bb_episode")
                agreement = dict(head_pending[env_index])
                head_pending[env_index] = new_head_agreement()
                truncated = bool(summary.get("truncated")) if summary is not None else bool(info.get("TimeLimit.truncated"))
                if summary is not None:
                    summary["head_agreement"] = agreement
                    merge_head_agreement(head_segment, agreement)
                    _attach_opening_mispick_diag(model, summary)
                _log_eval_game(
                    summary,
                    game_num=completions,
                    games=games,
                    truncated=truncated,
                    scenario=scenario,
                    sink=game_lines,
                )
                if summary is not None:
                    episodes.append(summary)
                if completions >= games:
                    break
        games_s = clk.lap(f"play {games} games", echo=echo_laps)
    except (GridironApiError, EOFError):
        _release_segment_env(env, pool)
        raise
    _release_segment_env(env, pool)

    stats = aggregate_episodes(episodes)
    stats.update(_episode_success_rates(episodes))
    if scenario is not None:
        stats["scenario_difficulty"] = scenario_difficulty
    timing_bit = f" ({clk.summary(('configure', configure_s), ('reset', reset_s), ('games', games_s))})"
    if quiet_scenario and _eval_batch_all_wins(stats, games=games):
        _record_scenario_perfect_summary(
            stats,
            scenario=scenario,
            scenario_difficulty=scenario_difficulty,
            head_agreement=head_segment,
        )
    else:
        if quiet_scenario:
            _eval_section_break()
            print(f"{header}{timing_bit}", flush=True)
        else:
            print(f"  timing: {timing_bit.lstrip(' (').rstrip(')')}", flush=True)
        if game_lines:
            for line in game_lines:
                print(line, flush=True)
        print_metrics(f"Eval {tagged}", stats)
        if int(head_segment["decisions"]) > 0:
            print(f"  {format_head_agreement(head_segment)}", flush=True)
        if quiet_scenario:
            print(
                f"  scenario success: drill_rate={stats['winrate']:.3f} win_by_4_rate={stats['win_by_4_rate']:.3f}",
                flush=True,
            )

    if not args.eval_only:
        eval_dir = EVAL_DIR / profile
        eval_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        out = eval_dir / f"eval_{label.replace('/', '_')}_{stamp}.json"
        payload = {
            "label": label,
            "profile": profile,
            "training_steps": int(model.num_timesteps),
            **stats,
            "episodes_raw": episodes,
        }
        if scenario is not None:
            payload["scenario"] = scenario
            payload["scenario_difficulty"] = scenario_difficulty
        if int(head_segment["decisions"]) > 0:
            payload["head_agreement"] = head_segment
        t0 = time.perf_counter()
        out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        write_s = time.perf_counter() - t0
        if quiet_scenario:
            timing_on_wrote = (
                f"{clk.summary(('configure', configure_s), ('reset', reset_s), ('games', games_s))}; "
                f"write {_fmt_wall_s(write_s)}"
            )
        else:
            timing_on_wrote = f"total {_fmt_wall_s(clk.total_s())}; write {_fmt_wall_s(write_s)}"
        print(f"Wrote {out} ({timing_on_wrote})", flush=True)
    return stats


def _eval_scenarios_segment(
    model: MaskablePPO,
    *,
    args: argparse.Namespace,
    pool: WorkerPool,
    profile: RewardProfileName,
    save_stem: Path,
    update_curriculum: bool = True,
) -> dict[str, Any]:
    """Scenario eval pass (no train). Curriculum updates only when ``update_curriculum``.

    Train sandwiches own bump/demote/gate fails. ``--eval-only`` passes
    ``update_curriculum=False`` so a standalone eval does not mutate levels, and skips
    writing eval JSON under ``output/evals/``.
    Game-phase cycles (scenario train skipped) keep ``True`` so d5 demote / recover
    still run on the eval pass.
    """
    scenarios = _scenario_names(pin=args.scenario)
    if not scenarios:
        print("Eval scenarios: catalog empty — skip", flush=True)
        return {}

    curriculum_path = path_for_stem(save_stem)
    levels = load_levels(curriculum_path)
    gate_failures = load_gate_failures(path_for_gate_failures(save_stem))
    active = scenarios_for_eval(levels, scenarios, gate_failures)
    _eval_section_break()
    if update_curriculum:
        print(f"Eval scenarios ({len(active)} drills, curriculum on)", flush=True)
    else:
        print(f"Eval scenarios ({len(active)} drills, curriculum off)", flush=True)
    row: dict[str, Any] = {}
    drill_stats = load_drill_stats(path_for_drill_stats(save_stem)) if update_curriculum else {}
    gate_failures_path = path_for_gate_failures(save_stem)
    drill_stats_path = path_for_drill_stats(save_stem)
    for scenario in active:
        difficulty = get_difficulty(levels, scenario)
        stats = _eval_one_scenario(
            model, args=args, pool=pool, profile=profile, scenario=scenario, difficulty=difficulty
        )
        if update_curriculum:
            levels, gate_failures, drill_stats = _apply_scenario_after_eval(
                scenario=scenario,
                difficulty=difficulty,
                stats=stats,
                levels=levels,
                gate_failures=gate_failures,
                drill_stats=drill_stats,
                curriculum_path=curriculum_path,
                gate_failures_path=gate_failures_path,
                drill_stats_path=drill_stats_path,
            )
        row.update(_cycle_stats_fields(f"eval_scenario_{scenario.lower()}", stats))
    return row


def _load_or_create_model(
    args: argparse.Namespace,
    *,
    checkpoint_key: str,
    phase: PhaseName,
    reward_profile: RewardProfileName,
    needs_train: bool,
    reward_shaping: RewardShaping,
) -> tuple[MaskablePPO, Path, Path, str, bool, Path]:
    checkpoint_dir = CHECKPOINT_DIR / checkpoint_key
    tb_runs = runs_dir(checkpoint_key)
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    tb_runs.mkdir(parents=True, exist_ok=True)

    def _bootstrap_env() -> DummyVecEnv:
        # In-process only: PPO ctor needs matching n_envs/spaces; train segments swap in SubprocVecEnv.
        return DummyVecEnv(
            [
                _env_thunk(
                    args.base_url,
                    DEFAULT_MAX_EPISODE_STEPS,
                    args.continue_on_submit_error,
                    OPPONENT_COMPUTER,
                    "LOOKAHEAD",
                    None,
                    DEFAULT_POLICY_SEAT,
                    reward_shaping,
                    None,
                    0,
                    "TRAIN",
                )
                for _ in range(DEFAULT_N_ENVS)
            ]
        )

    # Fork from prior phase (expert → scenario_a → scenario_b → profile) when missing.
    ensure_checkpoint_from_prior(checkpoint_key, phase)
    save_stem = long_running_stem(checkpoint_key)
    name_prefix = STEP_CHECKPOINT_PREFIX
    save_dir = checkpoint_dir
    session_log = tb_runs / _SESSION_LONG
    zip_path = long_running_zip(checkpoint_key)
    continued = zip_path.is_file()
    if continued:
        print(f"Continuing {checkpoint_key} ({phase}/{reward_profile}) from {zip_path}")
        if needs_train:
            bootstrap = _bootstrap_env()
            model = MaskablePPO.load(
                str(zip_path),
                env=bootstrap,
                device=DEFAULT_DEVICE,
                tensorboard_log=str(tb_runs),
            )
            model.verbose = 1
            bootstrap.close()
            _require_batch_divides_rollout(int(model.n_steps), DEFAULT_N_ENVS, int(model.batch_size))
            _set_learning_rate(model, _DEFAULT_LEARNING_RATE)
            _set_ent_coef(model, reward_profile)
            _set_vf_coef(model, reward_profile)
            _set_ppo_stability(model)
        else:
            model = MaskablePPO.load(str(zip_path), device=DEFAULT_DEVICE)
            model.verbose = 1
            _set_learning_rate(model, _DEFAULT_LEARNING_RATE)
            _set_ent_coef(model, reward_profile)
            _set_vf_coef(model, reward_profile)
            _set_ppo_stability(model)
    else:
        raise SystemExit(f"No checkpoint at {zip_path} after ensure_checkpoint_from_prior")
    return model, save_stem, save_dir, name_prefix, continued, session_log


def _skip_scenario_eval(args: argparse.Namespace) -> bool:
    """``--skip-scenarios`` always skips train; also skips eval in ``--eval-only`` (``./eval.sh``)."""
    return bool(args.skip_scenarios and args.eval_only)


def _run_one_cycle(
    model: MaskablePPO,
    args: argparse.Namespace,
    *,
    pool: WorkerPool,
    cycle_index: int,
    n_targets: int,
    target: TrainTarget,
    reward_shaping: RewardShaping,
    save_stem: Path,
    save_dir: Path,
    name_prefix: str,
    first_train_resets_timesteps: bool,
) -> dict[str, Any]:
    profile = target.reward_profile
    row: dict[str, Any] = {
        "cycle": cycle_index,
        "phase": target.phase,
        "checkpoint_key": target.checkpoint_key,
        "profile": profile,
    }
    did_train = False

    levels = load_levels(path_for_stem(save_stem))
    gate_failures = load_gate_failures(path_for_gate_failures(save_stem))
    scenarios = _scenario_names(pin=args.scenario)
    game_phase = game_phase_unlocked(target.checkpoint_key)
    heuristic_timesteps = full_game_train_timesteps(
        args.train_heuristic, levels, scenarios, gate_failures, game_phase_unlocked=game_phase
    )
    lookahead_timesteps = full_game_train_timesteps(
        args.train_lookahead, levels, scenarios, gate_failures, game_phase_unlocked=game_phase
    )
    onnx_timesteps = full_game_train_timesteps(
        args.train_onnx, levels, scenarios, gate_failures, game_phase_unlocked=game_phase
    )
    onnx_mix = ",".join(FULL_GAME_ONNX_OPPONENT_PROFILES)
    if scenarios and not claims_unlocked(levels, scenarios, gate_failures):
        print(
            f"Curriculum: board drills only until every board scenario reaches "
            f"d{CLAIM_UNLOCK_BOARD_DIFFICULTY} (claims deferred)",
            flush=True,
        )
    if game_phase:
        print(
            f"Game phase: full-game first — heuristic={heuristic_timesteps} "
            f"lookahead={lookahead_timesteps} onnx={onnx_timesteps}×[{onnx_mix}]",
            flush=True,
        )
    elif scenarios and not gating_scenarios_mastered(levels, scenarios, gate_failures):
        print(
            f"Full-game train: effective heuristic={heuristic_timesteps} "
            f"lookahead={lookahead_timesteps} onnx={onnx_timesteps}×[{onnx_mix}] "
            f"(gated until claims unlocked and every gating drill reaches d{MAX_DIFFICULTY}; "
            f"config heuristic={args.train_heuristic} lookahead={args.train_lookahead} "
            f"onnx={args.train_onnx})",
            flush=True,
        )
    elif heuristic_timesteps > 0 or lookahead_timesteps > 0 or onnx_timesteps > 0:
        print(
            f"Full-game train: effective heuristic={heuristic_timesteps} "
            f"lookahead={lookahead_timesteps} onnx={onnx_timesteps}×[{onnx_mix}]",
            flush=True,
        )

    # (timesteps, mode, computer_kind, onnx_profile, key)
    train_jobs: list[tuple[int, str, str, RewardProfileName | None, str]] = [
        (heuristic_timesteps, OPPONENT_COMPUTER, "HEURISTIC", None, "train_heuristic"),
        (lookahead_timesteps, OPPONENT_COMPUTER, "LOOKAHEAD", None, "train_lookahead"),
    ]
    for onnx_name in FULL_GAME_ONNX_OPPONENT_PROFILES:
        train_jobs.append(
            (onnx_timesteps, OPPONENT_COMPUTER, COMPUTER_KIND_ONNX, onnx_name, f"train_onnx_{onnx_name}")
        )
    run_full_game_eval = args.eval_only or (
        ((cycle_index - 1) // max(n_targets, 1)) % FULL_GAME_EVAL_EVERY_CYCLES == 0
    )
    eval_lookahead = args.eval_lookahead if run_full_game_eval else 0
    eval_heuristic = args.eval_heuristic if run_full_game_eval else 0
    eval_onnx = args.eval_onnx if run_full_game_eval else 0
    if not run_full_game_eval and (
        args.eval_lookahead > 0 or args.eval_heuristic > 0 or args.eval_onnx > 0
    ):
        round_i = (cycle_index - 1) // max(n_targets, 1)
        next_round = round_i + (FULL_GAME_EVAL_EVERY_CYCLES - round_i % FULL_GAME_EVAL_EVERY_CYCLES)
        next_eval = next_round * max(n_targets, 1) + 1
        print(
            f"  full-game eval skipped (every {FULL_GAME_EVAL_EVERY_CYCLES} visits/profile; "
            f"next eval round starts cycle {next_eval})",
            flush=True,
        )
    eval_jobs: list[tuple[int, str, str, RewardProfileName | None, str]] = [
        (eval_lookahead, OPPONENT_COMPUTER, "LOOKAHEAD", None, "eval_lookahead"),
        (eval_heuristic, OPPONENT_COMPUTER, "HEURISTIC", None, "eval_heuristic"),
    ]
    for onnx_name in FULL_GAME_ONNX_OPPONENT_PROFILES:
        eval_jobs.append(
            (eval_onnx, OPPONENT_COMPUTER, COMPUTER_KIND_ONNX, onnx_name, f"eval_onnx_{onnx_name}")
        )
    trained_scenarios = False

    def _run_scenarios() -> None:
        nonlocal trained_scenarios, did_train
        skip_train = args.train_scenario_learning <= 0 and args.train_scenario_mastered <= 0
        if skip_train:
            if game_phase and not _skip_scenario_eval(args):
                row.update(
                    _eval_scenarios_segment(
                        model,
                        args=args,
                        pool=pool,
                        profile=profile,
                        save_stem=save_stem,
                        update_curriculum=not args.eval_only,
                    )
                )
            return
        scenario_row = _train_scenarios_segment(
            model,
            args=args,
            pool=pool,
            save_stem=save_stem,
            save_dir=save_dir,
            name_prefix=name_prefix,
            reset_num_timesteps=first_train_resets_timesteps and not did_train,
            reward_shaping=reward_shaping,
            profile=profile,
            game_phase=game_phase,
        )
        row.update(scenario_row)
        if scenario_row:
            trained_scenarios = True
            did_train = True

    def _run_games() -> None:
        nonlocal did_train
        for timesteps, mode, computer, onnx_name, key in train_jobs:
            if timesteps <= 0:
                continue
            seat = opponent_metrics_label(computer, onnx_name)
            print(f"▶ {key} train ({timesteps} ts vs {seat})", flush=True)
            stats = _train_segment(
                model,
                args=args,
                pool=pool,
                timesteps=timesteps,
                opponent_mode=mode,
                computer_opponent=computer,
                onnx_profile=onnx_name,
                save_stem=save_stem,
                save_dir=save_dir,
                name_prefix=name_prefix,
                reset_num_timesteps=first_train_resets_timesteps and not did_train,
                reward_shaping=reward_shaping,
                profile=profile,
            )
            did_train = True
            row.update(_cycle_stats_fields(key, stats))
        for games, mode, computer, onnx_name, key in eval_jobs:
            if games <= 0:
                continue
            seat = opponent_metrics_label(computer, onnx_name)
            print(f"▶ {key} eval ({games} games vs {seat})", flush=True)
            stats = _eval_segment(
                model,
                args=args,
                pool=pool,
                games=games,
                opponent_mode=mode,
                computer_opponent=computer,
                onnx_profile=onnx_name,
                profile=profile,
            )
            row.update(_cycle_stats_fields(key, stats))

    t_cycle = time.perf_counter()
    if game_phase:
        _run_games()
        _run_scenarios()
    else:
        _run_scenarios()
        _run_games()
        if not trained_scenarios and not _skip_scenario_eval(args):
            row.update(
                _eval_scenarios_segment(
                    model,
                    args=args,
                    pool=pool,
                    profile=profile,
                    save_stem=save_stem,
                    update_curriculum=not args.eval_only,
                )
            )
    t0 = time.perf_counter()
    atomic_model_save(model, save_stem)
    print(f"  cycle checkpoint save: {_fmt_wall_s(time.perf_counter() - t0)}", flush=True)
    if not args.eval_only:
        advanced = advance_pipeline(target)
        if advanced:
            print(advanced, flush=True)
    print(f"Cycle {cycle_index} body done in {_fmt_wall_s(time.perf_counter() - t_cycle)}", flush=True)
    return row


def _print_startup_train_plan(
    args: argparse.Namespace,
    *,
    targets: list[TrainTarget],
    forever: bool,
) -> None:
    """Log config knobs and the curriculum-gated timesteps that will actually run."""
    print(
        f"Cycles={'∞' if forever else args.cycles} profile={args.profile} lr={_DEFAULT_LEARNING_RATE} "
        f"n_envs={DEFAULT_N_ENVS} "
        f"train_scenario_learning={args.train_scenario_learning} "
        f"train_scenario_mastered={args.train_scenario_mastered} "
        f"train_scenario_maintenance={DEFAULT_SCENARIO_TRAIN_MAINTENANCE} "
        f"train_heuristic_config={args.train_heuristic} "
        f"train_lookahead_config={args.train_lookahead} "
        f"train_onnx_config={args.train_onnx}×[{','.join(FULL_GAME_ONNX_OPPONENT_PROFILES)}] "
        f"eval_lookahead={args.eval_lookahead} eval_heuristic={args.eval_heuristic} "
        f"eval_onnx={args.eval_onnx} "
        f"full_game_eval_every={FULL_GAME_EVAL_EVERY_CYCLES}",
        flush=True,
    )
    try:
        scenarios = _scenario_names(pin=args.scenario)
    except SystemExit:
        raise

    for target in targets:
        levels = target.load_levels()
        gate_failures = load_gate_failures(path_for_gate_failures(target.save_stem()))
        stem_note = target.curriculum_path().name
        all_mastered = all_scenarios_mastered(levels, scenarios) if scenarios else False
        game_phase = game_phase_unlocked(target.checkpoint_key) if scenarios else False
        gating_mastered = gating_scenarios_mastered(levels, scenarios, gate_failures) if scenarios else False
        heuristic = full_game_train_timesteps(
            args.train_heuristic, levels, scenarios, gate_failures, game_phase_unlocked=game_phase
        )
        lookahead = full_game_train_timesteps(
            args.train_lookahead, levels, scenarios, gate_failures, game_phase_unlocked=game_phase
        )
        onnx_ts = full_game_train_timesteps(
            args.train_onnx, levels, scenarios, gate_failures, game_phase_unlocked=game_phase
        )
        active = scenarios_for_cycle(levels, scenarios, gate_failures) if scenarios else []
        scenario_bits: list[str] = []
        for scenario in active:
            difficulty = get_difficulty(levels, scenario)
            timesteps = train_timesteps_for_scenario(
                scenario,
                levels,
                scenarios,
                learning=args.train_scenario_learning,
                mastered=args.train_scenario_mastered,
                all_mastered=all_mastered,
                game_phase=game_phase,
                gate_failures=gate_failures,
                drill_stats=load_drill_stats_cumulative(target.checkpoint_key),
            )
            if timesteps > 0:
                scenario_bits.append(f"{scenario} d{difficulty}→{timesteps}ts")
        if not claims_unlocked(levels, scenarios, gate_failures):
            gate = f"claims deferred until board drills d{CLAIM_UNLOCK_BOARD_DIFFICULTY}"
        elif game_phase:
            gate = (
                f"game phase (profile stem gating d{MAX_DIFFICULTY}) — full-game first"
                if target.phase == PHASE_PROFILES
                else "game phase (scenario_b unlocked) — full-game first"
            )
            if scenario_bits:
                gate += f"; scenario train: {len(scenario_bits)}"
            elif _skip_scenario_eval(args):
                gate += "; full-game eval only"
            else:
                gate += "; scenario eval only (no soft-d5)"
        elif gating_mastered:
            gate = f"full-game unlocked (gating drills d{MAX_DIFFICULTY})"
        else:
            gate = f"full-game gated until gating drills d{MAX_DIFFICULTY}"
        order = "games→scenarios" if game_phase else "scenarios→games"
        print(
            f"  effective [{target.phase}/{target.checkpoint_key}/{target.reward_profile}] ({stem_note}): "
            f"order={order} scenarios=[{', '.join(scenario_bits) or 'none'}] "
            f"heuristic={heuristic} lookahead={lookahead} "
            f"onnx={onnx_ts}×[{','.join(FULL_GAME_ONNX_OPPONENT_PROFILES)}] — {gate}",
            flush=True,
        )


def main() -> None:
    args = parse_args()
    if args.reset_long_running:
        _reset_profiles(args.profile)
        return
    if args.force_promote:
        promoted = force_promote()
        print(f"Forced promote → {', '.join(promoted)}", flush=True)
    if args.scenario is not None:
        args.train_heuristic = 0
        args.train_lookahead = 0
        args.train_onnx = 0
        args.eval_heuristic = 0
        args.eval_lookahead = 0
        args.eval_onnx = 0
    if args.skip_scenarios:
        args.train_scenario_learning = 0
        args.train_scenario_mastered = 0
    if args.eval_only:
        args.train_scenario_learning = 0
        args.train_scenario_mastered = 0
        args.train_lookahead = 0
        args.train_heuristic = 0
        args.train_onnx = 0
    if args.cycles < 0:
        raise SystemExit("--cycles must be >= 0 (0 = forever)")

    any_work = any(
        n > 0
        for n in (
            args.train_scenario_learning,
            args.train_scenario_mastered,
            args.train_lookahead,
            args.train_heuristic,
            args.train_onnx,
            args.eval_lookahead,
            args.eval_heuristic,
            args.eval_onnx,
        )
    ) or args.eval_only
    if not any_work:
        raise SystemExit("Nothing to do — set at least one --train-* or --eval-* > 0 (or use --eval-only)")

    require_backend(args.base_url)

    # One live catalog fetch per process; write output/scenario_catalog.json for offline tools.
    _catalog_client = GridironClient(args.base_url)
    try:
        catalog = refresh_catalog(_catalog_client)
        print(
            f"Scenario catalog: {len(catalog.entries)} drills "
            f"({len(catalog.board_names())} board, {len(catalog.claim_names())} claim) "
            f"→ cached",
            flush=True,
        )
    finally:
        _catalog_client.close()

    CHECKPOINT_DIR.mkdir(parents=True, exist_ok=True)
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    pruned = prepare_checkpoint_dirs(keep=DEFAULT_STEP_CHECKPOINT_KEEP)
    if pruned:
        total = sum(pruned.values())
        detail = ", ".join(f"{key}={count}" for key, count in sorted(pruned.items()))
        print(
            f"Pruned {total} step checkpoint(s) "
            f"(keeping {DEFAULT_STEP_CHECKPOINT_KEEP} newest per profile): {detail}",
            flush=True,
        )

    needs_train = any(
        n > 0
        for n in (
            args.train_scenario_learning,
            args.train_scenario_mastered,
            args.train_lookahead,
            args.train_heuristic,
            args.train_onnx,
        )
    )
    forever = args.cycles == 0
    cycle_index = 0
    cycle_log = CYCLE_LOG_PATH

    # Resolve once for startup plan / eval-only cycle count; forever re-resolves each cycle.
    startup_targets = resolve_targets(args.profile, eval_only=args.eval_only)
    if args.eval_only:
        # Default --cycles 0 means forever; for a quick eval pass, run each target once.
        if args.cycles == 0:
            args.cycles = len(startup_targets)
            forever = False
        if args.profile == "all":
            skipped = [
                p
                for p in profiles_for_all("eval")
                if p != "baseline" and p not in load_pipeline().profiles
            ]
            if skipped:
                print(f"Skipping profiles without checkpoint: {', '.join(skipped)}", flush=True)
    for note_target in startup_targets:
        if note_target.deferral_note:
            print(note_target.deferral_note, flush=True)
            break

    # Single checkpoint_key: keep one model in memory across cycles. Rotate / phase-change: reload.
    model: MaskablePPO | None = None
    save_stem: Path | None = None
    save_dir: Path | None = None
    name_prefix: str | None = None
    session_log: Path | None = None
    loaded_key: str | None = None
    first_train_resets = True
    continued_any = False

    _print_startup_train_plan(args, targets=startup_targets, forever=forever)

    pool = WorkerPool(args)
    try:
        while forever or cycle_index < args.cycles:
            cycle_index += 1
            _scenario_perfect_summaries.clear()
            targets = resolve_targets(args.profile, eval_only=args.eval_only)
            target = targets[(cycle_index - 1) % len(targets)]
            keep_model = len(targets) == 1
            if target.deferral_note:
                print(target.deferral_note, flush=True)

            reward_shaping = _reward_shaping_for_profile(target.reward_profile)
            start = _utc_now()
            t0 = time.perf_counter()
            print(
                f"\n=== Cycle {cycle_index} phase={target.phase} key={target.checkpoint_key} "
                f"profile={target.reward_profile} {_fmt_utc(start)} "
                f"ent_coef={_ent_coef_for_profile(target.reward_profile)} "
                f"vf_coef={_vf_coef_for_profile(target.reward_profile)} "
                f"n_epochs={N_EPOCHS} clip_range={CLIP_RANGE} target_kl={TARGET_KL} "
                f"shaping(idle={reward_shaping.idle_turn_penalty}, ball={reward_shaping.ball_possession_bonus}, "
                f"approach={reward_shaping.approach_ball_bonus}, gained={reward_shaping.us_gained_ball_bonus}, "
                f"uncert={reward_shaping.uncertain_challenge_bonus}, "
                f"td={reward_shaping.touchdown_bonus}, block={reward_shaping.any_block_bonus}, "
                f"block_bc={reward_shaping.block_ball_carrier_bonus}, "
                f"bc_act={reward_shaping.ball_carrier_action_bonus}, "
                f"empty_ez={reward_shaping.empty_scoring_endzone_penalty}, "
                f"cover={reward_shaping.covering_radius_delta_bonus}) ===",
                flush=True,
            )

            if model is None or not keep_model or loaded_key != target.checkpoint_key:
                model, save_stem, save_dir, name_prefix, continued, session_log = _load_or_create_model(
                    args,
                    checkpoint_key=target.checkpoint_key,
                    phase=target.phase,
                    reward_profile=target.reward_profile,
                    needs_train=needs_train,
                    reward_shaping=reward_shaping,
                )
                loaded_key = target.checkpoint_key
                continued_any = continued_any or continued
                first_train_resets = not continued

            assert model is not None and save_stem is not None and save_dir is not None
            assert name_prefix is not None and session_log is not None

            row = _run_one_cycle(
                model,
                args,
                pool=pool,
                cycle_index=cycle_index,
                n_targets=len(targets),
                target=target,
                reward_shaping=reward_shaping,
                save_stem=save_stem,
                save_dir=save_dir,
                name_prefix=name_prefix,
                first_train_resets_timesteps=first_train_resets,
            )
            first_train_resets = False
            wall_s = time.perf_counter() - t0
            row["start_utc"] = _fmt_utc(start)
            row["end_utc"] = _fmt_utc(_utc_now())
            row["wall_s"] = f"{wall_s:.3f}"
            _append_tsv_row(cycle_log, row)
            _append_tsv_row(
                session_log,
                {
                    "start_utc": row["start_utc"],
                    "end_utc": row["end_utc"],
                    "continued": int(continued_any or cycle_index > 1),
                    "cycle": cycle_index,
                    "phase": target.phase,
                    "checkpoint_key": target.checkpoint_key,
                    "profile": target.reward_profile,
                    "wall_s": row["wall_s"],
                    "device": str(model.device),
                },
            )
            print(f"Cycle log → {cycle_log}", flush=True)
            print(f"=== Cycle {cycle_index} finished in {_fmt_wall_s(wall_s)} ===", flush=True)
            _print_scenario_perfect_summaries()

            if not keep_model:
                atomic_model_save(model, save_stem)
                model = None
                save_stem = None
                loaded_key = None
    except KeyboardInterrupt:
        print("\nInterrupted — saving and exiting.", flush=True)
        if model is not None and save_stem is not None:
            atomic_model_save(model, save_stem)
        _print_scenario_perfect_summaries()
    finally:
        pool.close()


if __name__ == "__main__":
    main()
