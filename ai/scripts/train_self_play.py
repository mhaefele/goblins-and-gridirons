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
(today: ``general``, ``spread``, ``defense``; pin ``td`` explicitly).

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
import builtins
import json
import logging
import shutil
import sys
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from dataclasses import dataclass
from typing import Any, Callable, Iterator

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

from gridiron_ai.api.client import GridironApiError, GridironClient
from gridiron_ai.reward import (
    FULL_GAME_ONNX_OPPONENT_PROFILES,
    PROFILE_SHAPING,
    REWARD_PROFILES,
    SHAPED_PROFILES,
    RewardProfileName,
    RewardShaping,
    profiles_for_all,
)
from gridiron_ai.api.env import (
    COMPUTER_KIND_ONNX,
    OPPONENT_COMPUTER,
    GridironEnv,
    opponent_metrics_label,
)
from gridiron_ai.eval_debug import ScenarioEvalDebug
from gridiron_ai.eval_opening_diag import (
    aggregate_opening_move_scores,
    diagnose_opening_mispick,
)
from gridiron_ai.metrics import (
    accumulate_kind_totals,
    aggregate_episodes,
    format_scenario_learn_line,
    load_kind_breakdown,
    print_kind_mix,
    print_metrics,
    print_scenario_eval_metrics,
    format_scenario_eval_headline,
)
from gridiron_ai.scenario_curriculum import (
    CLAIM_UNLOCK_BOARD_DIFFICULTY,
    DEFAULT_SCENARIO_TRAIN_LEARNING,
    DEFAULT_SCENARIO_TRAIN_MASTERED,
    DEFAULT_SCENARIO_TRAIN_MAINTENANCE,
    MAX_DIFFICULTY,
    apply_after_eval_curriculum,
    claims_unlocked,
    full_game_train_timesteps,
    gate_failure_count,
    gating_scenarios_mastered,
    get_difficulty,
    LAST_EVAL_HISTORY_MAX,
    last_eval_metrics,
    last_eval_rank_history,
    last_eval_winrate_history,
    load_drill_stats,
    load_gate_failures,
    load_last_eval,
    load_levels,
    path_for_drill_stats,
    path_for_gate_failures,
    path_for_last_eval,
    path_for_stem,
    record_last_eval,
    record_train_cycles,
    save_drill_stats,
    save_gate_failures,
    save_last_eval,
    save_levels,
    scenarios_for_cycle,
    scenarios_for_eval,
    SCENARIO_EVAL_GAMES,
    SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER,
    scenario_eval_games,
    format_train_multiplier_lines,
    train_multiplier_drill_label,
    train_multiplier_groups,
    train_timesteps_for_difficulty,
    train_timesteps_for_scenario,
)
from gridiron_ai.api.scenarios import refresh_catalog, require_catalog, resolve_scenario_pin
from gridiron_ai.ppo_update_evidence import PpoUpdateEvidenceCallback
from gridiron_ai.tb_callback import GridironMetricsCallback, PeriodicFinalSaveCallback
from gridiron_ai.training_phases import (
    EVAL_PHASE_CHOICES,
    PhaseName,
    TrainTarget,
    advance_pipeline,
    ensure_checkpoint_from_prior,
    eval_zip_for_phase,
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
# PPO knobs: stock SB3 unless noted in TRAINING_ROADMAP.md § "PPO vs SB3 stock".
_DEFAULT_LEARNING_RATE = 3e-4
# 2× stock — only while training 8× (bottleneck) scenario drills; restored after the segment.
_BOTTLENECK_LEARNING_RATE = 6e-4
DEFAULT_MAX_EPISODE_STEPS = 500
DEFAULT_POLICY_SEAT = "HOME"
DEFAULT_DEVICE = "cpu"
FINAL_SAVE_FREQ = 50_000
STEP_CHECKPOINT_FREQ = 500_000
# Intentional vs stock 0.0 — keep menu exploratory on large legal-row Discrete.
DEFAULT_ENT_COEF = 0.005
DEFAULT_VF_COEF = 0.5
N_EPOCHS = 10
CLIP_RANGE = 0.2
TARGET_KL: float | None = None

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

# Deterministic games for each scenario after-eval (10); delta vs prior eval at same @d.
_VECNORMALIZE_SUFFIX = "_vecnormalize.pkl"
# (scenario, difficulty) → last train-time eval this process; disk mirror is scenario_last_eval.json.
_last_scenario_after_eval: dict[tuple[str, int], dict[str, Any]] = {}
# One blank line between drills (phase banners carry the rest of the structure).
_EVAL_SECTION_GAP = "\n"
_scenario_perfect_summaries: list[str] = []
_SECTION_INDENT = "  "


def _prior_scenario_eval(
    scenario: str,
    difficulty: int,
    save_stem: Path,
) -> dict[str, Any] | None:
    """In-memory last eval, else slim metrics from ``{stem}.scenario_last_eval.json``."""
    prior = _last_scenario_after_eval.get((scenario, difficulty))
    if prior is not None:
        return prior
    return last_eval_metrics(load_last_eval(path_for_last_eval(save_stem)), scenario, difficulty)


def _remember_scenario_eval(
    scenario: str,
    difficulty: int,
    stats: dict[str, Any],
    save_stem: Path,
) -> None:
    """Update process cache + persist slim metrics beside the checkpoint stem."""
    _last_scenario_after_eval[(scenario, difficulty)] = stats
    path = path_for_last_eval(save_stem)
    save_last_eval(path, record_last_eval(load_last_eval(path), scenario, difficulty, stats))


def _note_kind_mix(
    runs_key: str,
    label: str,
    stats: dict[str, Any],
    *,
    print_tables: bool,
    print_lifetime: bool = False,
    accumulate: bool = True,
    indent: int = 0,
) -> None:
    """Optionally print segment kind tables; train also merges into ``kind_totals.*``.

    Lifetime under ``runs/{runs_key}/`` tracks **training** mix only — eval prints
    the segment table when asked and must not pollute that file. Lifetime stdout is
    opt-in (once per scenario-train cycle, not once per drill).
    """
    if accumulate:
        segment = stats.get("kind_breakdown") or stats.get("kind_totals") or {}
        accumulate_kind_totals(runs_key, segment)
    if print_tables:
        print_kind_mix(label, stats, indent=indent)
    if print_lifetime:
        _print_kind_lifetime(runs_key, label, indent=indent)


def _print_kind_lifetime(runs_key: str, label: str, *, indent: int = 0) -> None:
    print_kind_mix(
        f"{label} lifetime [{runs_key}]",
        {"kind_breakdown": load_kind_breakdown(runs_key)},
        indent=indent,
    )


def _eval_batch_all_wins(stats: dict[str, Any], *, games: int) -> bool:
    """True on a full batch where every decided game is a US win."""
    episodes = int(stats.get("episodes") or 0)
    if episodes < games:
        return False
    return int(stats.get("wins") or 0) == games


def _eval_section_break() -> None:
    print(_EVAL_SECTION_GAP, end="", flush=True)


def _section_print(text: str, *, indent: int = 1) -> None:
    """Print ``text`` with ``indent`` levels of nesting under a phase banner."""
    prefix = _SECTION_INDENT * indent
    for line in text.splitlines():
        print(f"{prefix}{line}" if line else "", flush=True)


def _scenario_phase_banner(label: str, *, blank_before: bool = False) -> None:
    """Stdout fence between before-eval / train / after-eval for one drill."""
    if blank_before:
        print(flush=True)
    print(f"---- {label} ----", flush=True)


def _record_scenario_perfect_summary(
    stats: dict[str, Any],
    *,
    scenario: str,
    scenario_difficulty: int,
) -> None:
    short = require_catalog().short_name(scenario)
    line = "  " + format_scenario_eval_headline(
        short_name=short,
        difficulty=scenario_difficulty,
        stats=stats,
    )
    _scenario_perfect_summaries.append(line)


def _print_scenario_perfect_summaries() -> None:
    if not _scenario_perfect_summaries:
        return
    print("\nScenario eval summary (perfect batches):", flush=True)
    for line in _scenario_perfect_summaries:
        print(line, flush=True)


def _print_interrupt_warnings(profile: str) -> None:
    """Ctrl-C footer: STUCK!/bad train-health shouts (lazy import — avoid cycle with summarize)."""
    try:
        from scripts.summarize_models import format_train_warnings_section

        text = format_train_warnings_section(profile)
    except Exception as exc:  # noqa: BLE001 — interrupt path must not raise
        print(f"\n(train warnings unavailable: {exc})", flush=True)
        return
    if not text:
        return
    print(flush=True)
    print(text, flush=True)


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
    soft_mask_bias: bool
    verbose: bool


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
            soft_mask_bias=True,
            verbose=bool(args.verbose),
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
        soft_mask_bias: bool,
        verbose: bool = False,
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
            soft_mask_bias=soft_mask_bias,
            verbose=verbose,
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
            soft_mask_bias=soft_mask_bias,
            verbose=verbose,
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
    soft_mask_bias: bool = True,
    verbose: bool = False,
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
                    soft_mask_bias=soft_mask_bias,
                    verbose=verbose,
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
    soft_mask_bias: bool,
    *,
    verbose: bool = False,
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
            soft_mask_bias=soft_mask_bias,
            verbose=verbose,
        )

    return _init


def make_bootstrap_vec_env(
    *,
    base_url: str,
    continue_on_submit_error: bool = False,
    reward_shaping: RewardShaping | None = None,
    n_envs: int = DEFAULT_N_ENVS,
    verbose: bool = False,
) -> DummyVecEnv:
    """In-process vec-env for MaskablePPO load/create (spaces only; train swaps Subproc).

    Kept as a named helper so tests can smoke the same path ``_load_or_create_model`` uses.
    """
    shaping = reward_shaping or RewardShaping()
    return DummyVecEnv(
        [
            _env_thunk(
                base_url,
                DEFAULT_MAX_EPISODE_STEPS,
                continue_on_submit_error,
                OPPONENT_COMPUTER,
                "LOOKAHEAD",
                None,
                DEFAULT_POLICY_SEAT,
                shaping,
                None,
                0,
                "TRAIN",
                True,
                verbose=verbose,
            )
            for _ in range(n_envs)
        ]
    )


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
    soft_mask_bias: bool = True,
    verbose: bool = False,
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
                soft_mask_bias,
                verbose=verbose,
            )
            for _ in range(n_envs)
        ],
        start_method="spawn",
    )


def parse_args() -> argparse.Namespace:
    # Conceptual + phase-based only; minimal surface. Never a free-form model path — eval pins
    # are ``--phase`` → that phase’s end zip. One-offs → edit source (common.sh train_cli_usage).
    p = argparse.ArgumentParser(
        description="Train/eval driver — invoked by ./train.sh, ./eval.sh, and ./start_over.sh (not day-to-day CLI)"
    )
    p.add_argument("--base-url", default=DEFAULT_BASE_URL, help=argparse.SUPPRESS)
    p.add_argument("--cycles", type=int, default=0, help="How many times to run the train/eval block (0 = forever until Ctrl-C)")
    p.add_argument("--profile", default="all", choices=("all", *REWARD_PROFILES), help="Reward profile or all (default: all — baseline + general + spread + defense; pin td; summary still lists it)")
    p.add_argument(
        "--skip-scenarios",
        action="store_true",
        help="Skip scenario train; with --eval-only skip scenario eval too (eval.sh)",
    )
    p.add_argument("--train-scenario-learning", type=int, default=DEFAULT_SCENARIO_TRAIN_LEARNING, help="PPO timesteps per scenario still below difficulty 5 (0 = skip learning scenarios)")
    p.add_argument("--train-scenario-mastered", type=int, default=DEFAULT_SCENARIO_TRAIN_MASTERED, help="PPO timesteps per healthy d5 while pack climbs (default 0 = skip; soft-d5 uses maintenance)")
    p.add_argument("--train-heuristic", type=int, default=DEFAULT_TRAIN_HEURISTIC, help="PPO timesteps vs HEURISTIC computer per cycle (0 = skip)")
    p.add_argument("--train-lookahead", type=int, default=DEFAULT_TRAIN_LOOKAHEAD, help="PPO timesteps vs LOOKAHEAD computer per cycle (0 = skip)")
    p.add_argument("--train-onnx", type=int, default=DEFAULT_TRAIN_ONNX, help=f"PPO timesteps per ONNX profile seat per cycle ({', '.join(FULL_GAME_ONNX_OPPONENT_PROFILES)}; 0 = skip)")
    p.add_argument("--eval-heuristic", type=int, default=DEFAULT_EVAL_HEURISTIC, help=f"Deterministic eval games vs HEURISTIC (train: every {FULL_GAME_EVAL_EVERY_CYCLES} visits/profile; eval-only: each pass; 0 = skip)")
    p.add_argument("--eval-lookahead", type=int, default=DEFAULT_EVAL_LOOKAHEAD, help=f"Deterministic eval games vs LOOKAHEAD (train: every {FULL_GAME_EVAL_EVERY_CYCLES} visits/profile; eval-only: each pass; 0 = skip)")
    p.add_argument("--eval-onnx", type=int, default=DEFAULT_EVAL_ONNX, help=f"Deterministic eval games per ONNX profile ({', '.join(FULL_GAME_ONNX_OPPONENT_PROFILES)}; same cadence as heuristic/lookahead; 0 = skip)")
    p.add_argument("--eval-only", action="store_true", help="Skip all train; eval pass without eval JSON (eval.sh sets this)")
    p.add_argument("--eval-episode-mode", choices=("EVAL", "TRAIN"), default="EVAL", help="Scenario/full-game eval: EVAL=unaided (soft-mask bias off); TRAIN=soft-mask logit bias on (+ claim reachability soft-mask search)")
    p.add_argument("--verbose", action="store_true", help="Scenario eval opening-mispick policy_diag on THEM games; also PPO update evidence (‖θ‖/approx_kl, PASS-edge adv, opening Δp_chosen US vs fail with played paths)")
    p.add_argument("--super-verbose", action="store_true", help="Implies --verbose; also dumps the full ranked legal menu (idx + probs) on THEM opening mispicks")
    p.add_argument("--phase", choices=EVAL_PHASE_CHOICES, default=None, help="Eval-only: load the zip that phase produced (seed / scenario_a / scenario_b); read-only; one pass")
    p.add_argument("--scenario", default=None, help="One drill only — skips full-game train/eval (default: cycle all from server)")
    p.add_argument(
        "--difficulty",
        type=int,
        default=None,
        metavar="N",
        help="Eval-only: pin drill difficulty 0–5 (requires --scenario; ignores curriculum JSON; never writes levels)",
    )
    p.add_argument("--force-promote", action="store_true", help="Persist next phase gate without live curriculum (at profiles: sticky full-game unlock); fail if checkpoint missing")
    p.add_argument("--continue-on-submit-error", action="store_true", help=argparse.SUPPRESS)
    p.add_argument("--reset-long-running", action="store_true", help="Delete long-running checkpoints and TensorBoard logs for the selected profile(s), then exit (start_over.sh)")
    args = p.parse_args()
    if args.super_verbose:
        args.verbose = True
    if args.difficulty is not None:
        if args.scenario is None:
            raise SystemExit("--difficulty requires --scenario")
        if not 0 <= args.difficulty <= MAX_DIFFICULTY:
            raise SystemExit(f"--difficulty must be 0–{MAX_DIFFICULTY}, got {args.difficulty}")
        if not args.eval_only:
            raise SystemExit("--difficulty is eval-only (use with ./eval.sh / --eval-only)")
    return args


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


def _set_ent_coef(model: MaskablePPO) -> None:
    """Apply ent_coef on create and resume (zip may still carry an older 0.0)."""
    model.ent_coef = DEFAULT_ENT_COEF


def _set_vf_coef(model: MaskablePPO) -> None:
    """Apply vf_coef on create and resume (zip may still carry a profile override)."""
    model.vf_coef = DEFAULT_VF_COEF


def _set_ppo_stability(model: MaskablePPO) -> None:
    """Apply PPO epoch/clip/KL knobs on create and resume (zip may carry factor-era values)."""
    model.n_epochs = N_EPOCHS
    model.clip_range = lambda _progress: CLIP_RANGE
    model.target_kl = TARGET_KL


@contextmanager
def _sb3_kl_early_stop_explained() -> Iterator[None]:
    """Rewrite MaskablePPO's stock KL early-stop print into a one-line human gloss.

    SB3 trips when approx KL > 1.5×``target_kl`` and skips remaining PPO epochs for that update.
    No-op when ``TARGET_KL`` is None (stock SB3 — no early stop).
    """
    if TARGET_KL is None:
        yield
        return
    real_print = builtins.print
    trip = 1.5 * TARGET_KL

    def _print(*args: object, **kwargs: object) -> None:
        if (
            args
            and isinstance(args[0], str)
            and "Early stopping at step" in args[0]
            and "reaching max kl" in args[0]
        ):
            gloss = (
                f"{args[0].rstrip()} — policy shifted too far this update "
                f"(approx KL > {trip:g} = 1.5×target_kl={TARGET_KL}); "
                f"skipping remaining PPO epochs to stay stable."
            )
            real_print(gloss, *args[1:], **kwargs)
            return
        real_print(*args, **kwargs)

    builtins.print = _print  # type: ignore[assignment]
    try:
        yield
    finally:
        builtins.print = real_print


def _vecnormalize_path(save_stem: Path) -> Path:
    return Path(str(save_stem) + _VECNORMALIZE_SUFFIX)


def _wrap_vecnormalize(env: SubprocVecEnv, save_stem: Path, *, training: bool) -> VecNormalize:
    """Wrap rollouts; reload running stats from the checkpoint sidecar when present."""
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
    """Close a VecEnv; SB3 SubprocVecEnv leaves parent Pipe FDs open after ``close()``.

    Ctrl-C during the SB3 ``remote.recv()`` handshake must not dump a traceback after a
    clean interrupt save — swallow pipe/interrupt noise and force-close remotes/procs.
    """
    subproc: SubprocVecEnv | None = env.venv if isinstance(env, VecNormalize) else env
    if not isinstance(subproc, SubprocVecEnv):
        subproc = None
    try:
        env.close()
    except (KeyboardInterrupt, EOFError, BrokenPipeError, OSError):
        pass
    if subproc is None:
        return
    for remote in subproc.remotes:
        try:
            remote.close()
        except OSError:
            pass
    for process in subproc.processes:
        try:
            if process.is_alive():
                process.terminate()
            process.join(timeout=1.0)
            if process.is_alive():
                process.kill()
            process.close()
        except (OSError, ValueError):
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

    def lap(self, label: str, *, echo: bool = True, indent: int = 1) -> float:
        now = time.perf_counter()
        dt = now - self._last
        self._last = now
        if echo:
            _section_print(f"{label}: {_fmt_wall_s(dt)}", indent=indent)
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
        f"{key}_expected_p": _fmt_optional_float(stats.get("opening_expected_p_mean")),
        f"{key}_expected_p_best": _fmt_optional_float(stats.get("opening_expected_p_best_mean")),
        f"{key}_first_p": _fmt_optional_float(stats.get("opening_chosen_p_mean")),
        f"{key}_expected_rank_best": _fmt_optional_float(stats.get("opening_expected_rank_best_mean")),
    }


def _fmt_optional_float(value: Any, *, digits: int = 6) -> str:
    if value is None:
        return ""
    return f"{float(value):.{digits}f}"


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
    runs_key: str,
    scenario: str | None = None,
    scenario_difficulty: int = 0,
) -> dict[str, Any]:
    """One ``learn()`` block; swaps vec-env to the requested opponent mode."""
    opp_label = opponent_metrics_label(computer_opponent, onnx_profile)
    if scenario is not None:
        label = f"vs-{opp_label}-scenario-{scenario.lower()}-d{scenario_difficulty}"
        body_indent = 1
        lap_indent = 2
    else:
        label = f"vs-{opp_label}"
        body_indent = 0
        lap_indent = 1
    _section_print(
        f"Train {label}: {timesteps} timesteps "
        f"(n_envs={DEFAULT_N_ENVS}, n_steps={model.n_steps}, batch_size={model.batch_size})",
        indent=body_indent,
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
        soft_mask_bias=True,
        verbose=bool(args.verbose),
    )
    clk.lap("configure workers", indent=lap_indent)
    env = _wrap_vecnormalize(pool.vec_env(DEFAULT_N_ENVS), save_stem, training=True)
    clk.lap("vecnormalize load", indent=lap_indent)
    model.set_env(env)
    _require_batch_divides_rollout(int(model.n_steps), DEFAULT_N_ENVS, int(model.batch_size))

    metrics_cb = GridironMetricsCallback(
        profile=profile,
        computer_opponent=opp_label,
        scenario=scenario if scenario is not None else "regular game",
        scenario_difficulty=scenario_difficulty if scenario is not None else None,
        # Scenario sandwich: final Train metrics line only (no per-rollout reprint).
        verbose=0 if scenario is not None else 1,
    )
    callbacks: list[Any] = [
        metrics_cb,
        PpoUpdateEvidenceCallback(scenario=scenario, verbose=1 if args.verbose else 0),
    ]
    if FINAL_SAVE_FREQ > 0 and timesteps >= FINAL_SAVE_FREQ:
        callbacks.append(PeriodicFinalSaveCallback(save_stem, FINAL_SAVE_FREQ))

    try:
        with _sb3_kl_early_stop_explained():
            model.learn(
                total_timesteps=timesteps,
                callback=CallbackList(callbacks),
                progress_bar=False,
                reset_num_timesteps=reset_num_timesteps,
            )
    except (GridironApiError, EOFError):
        _release_segment_env(env, pool)
        raise SystemExit(1) from None
    clk.lap("learn", indent=lap_indent)
    _save_vecnormalize(env, save_stem)
    clk.lap("vecnormalize save", indent=lap_indent)
    atomic_model_save(model, save_stem)
    _save_step_checkpoint(model, save_dir, name_prefix=name_prefix, timesteps=timesteps)
    clk.lap("checkpoint save", indent=lap_indent)
    stats = metrics_cb.session_summary()
    if scenario is not None:
        stats["scenario_difficulty"] = scenario_difficulty
    _release_segment_env(env, pool)
    # Laps already printed — no second timing summary line.
    print_metrics(f"Train {label}", stats, indent=body_indent)
    # Segment table only — lifetime once after all scenarios (or after full-game train).
    _note_kind_mix(
        runs_key,
        f"Train {label}",
        stats,
        print_tables=True,
        print_lifetime=scenario is None,
        indent=body_indent,
    )
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
    runs_key: str,
    save_stem: Path,
    scenario: str,
    difficulty: int,
    games: int = SCENARIO_EVAL_GAMES,
) -> dict[str, Any]:
    """Deterministic eval for one drill at its current curriculum difficulty.

    Eval line shows ``before→after`` vs the last same drill@d train-time eval (memory,
    else ``{stem}.scenario_last_eval.json``). Persists only while training — ``--eval-only``
    never writes the cache or sidecar. ``games`` is 100 for 8× train units, else 10.
    Before-eval and after-eval both append to the rolling last-eval history.
    """
    prior = _prior_scenario_eval(scenario, difficulty, save_stem)
    stored = load_last_eval(path_for_last_eval(save_stem))
    stored_ranks = last_eval_rank_history(stored, scenario, difficulty)
    stored_rates = last_eval_winrate_history(stored, scenario, difficulty)
    stats = _eval_segment(
        model,
        args=args,
        pool=pool,
        games=games,
        opponent_mode=OPPONENT_COMPUTER,
        computer_opponent="LOOKAHEAD",
        onnx_profile=None,
        profile=profile,
        runs_key=runs_key,
        scenario=scenario,
        scenario_difficulty=difficulty,
        metrics_prior=prior,
        rank_history_before=stored_ranks,
        winrate_history_before=stored_rates,
    )
    if not args.eval_only:
        _remember_scenario_eval(scenario, difficulty, stats, save_stem)
    return stats


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
    expected_games: int = SCENARIO_EVAL_GAMES,
) -> tuple[dict[str, int], dict[str, dict[str, int]], dict[str, dict[str, int]]]:
    """Persist bump / demote / gate-failure / drill-stat counts from one after-eval pass."""
    levels, gate_failures, drill_stats, action = apply_after_eval_curriculum(
        levels,
        gate_failures,
        scenario,
        stats,
        expected_games=expected_games,
        drill_stats=drill_stats,
    )
    full_batch = int(stats.get("episodes") or 0) >= expected_games
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
        _section_print(
            f"curriculum: {scenario} passed at d{difficulty} → d{new_level} ({curriculum_path.name})",
            indent=1,
        )
    elif action == "demote":
        _section_print(
            f"curriculum: {scenario} failed at d{difficulty} → d{new_level} "
            f"(gate_failures d{difficulty}={fails}; {curriculum_path.name})",
            indent=1,
        )
    elif action == "waive":
        _section_print(
            f"curriculum: {scenario} progress gating waived at d{difficulty} "
            f"({fails} fails; still training; {gate_failures_path.name})",
            indent=1,
        )
    elif action == "recover":
        _section_print(
            f"curriculum: {scenario} passed at d{difficulty} — progress gating restored "
            f"({curriculum_path.name})",
            indent=1,
        )
    elif action == "fail":
        _section_print(
            f"curriculum: {scenario} gate fail at d{difficulty} "
            f"(failures={fails}; {gate_failures_path.name})",
            indent=1,
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
    runs_key: str,
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
            runs_key=runs_key,
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
    by_units: dict[int, list[str]] = {}
    plan_units: list[tuple[str, int, int, int]] = []
    for scenario, difficulty, timesteps in plan:
        base_ts = train_timesteps_for_difficulty(
            difficulty,
            learning=args.train_scenario_learning,
            mastered=args.train_scenario_mastered,
            game_phase=game_phase,
        )
        units = max(1, timesteps // base_ts) if base_ts > 0 else 1
        plan_units.append((scenario, difficulty, timesteps, units))
        by_units.setdefault(units, []).append(
            train_multiplier_drill_label(
                scenario, levels, difficulty=difficulty, gate_failures=gate_failures
            )
        )
    for line in format_train_multiplier_lines(
        by_units,
        header=f"{prefix} ({len(plan)} drills):",
        include_zero=False,
    ):
        print(line, flush=True)

    did_train = False
    proxy_sources = require_catalog().proxy_sources()
    for scenario, difficulty, timesteps, units in plan_units:
        slug = scenario.lower()
        do_eval = scenario not in proxy_sources
        eval_games = scenario_eval_games(units)
        fresh_before = do_eval and units >= SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER

        before: dict[str, Any] | None = None
        tag = f"{scenario} d{difficulty}"
        if fresh_before:
            _scenario_phase_banner(
                f"before-eval starting — {tag} ({units}×, {eval_games}g)"
            )
            phase_t0 = time.perf_counter()
            before = _eval_one_scenario(
                model,
                args=args,
                pool=pool,
                profile=profile,
                runs_key=runs_key,
                save_stem=save_stem,
                scenario=scenario,
                difficulty=difficulty,
                games=eval_games,
            )
            row.update(_cycle_stats_fields(f"eval_scenario_{slug}_before", before))
            _scenario_phase_banner(
                f"before-eval complete — {tag} ({_fmt_wall_s(time.perf_counter() - phase_t0)})"
            )
        elif do_eval:
            prior = _prior_scenario_eval(scenario, difficulty, save_stem)
            if prior is not None:
                before = prior

        bottleneck = units >= SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER
        lr = _BOTTLENECK_LEARNING_RATE if bottleneck else _DEFAULT_LEARNING_RATE
        if do_eval:
            train_detail = f"{timesteps} ts lr={lr:g}"
        else:
            proxy = require_catalog().curriculum_key(scenario)
            train_detail = f"{timesteps} ts lr={lr:g}; no eval — tracks {proxy}"
        _scenario_phase_banner(
            f"training starting — {tag} ({train_detail})",
            blank_before=fresh_before,
        )

        phase_t0 = time.perf_counter()
        if bottleneck:
            _set_learning_rate(model, _BOTTLENECK_LEARNING_RATE)
        try:
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
                runs_key=runs_key,
                scenario=scenario,
                scenario_difficulty=difficulty,
            )
        finally:
            if bottleneck:
                _set_learning_rate(model, _DEFAULT_LEARNING_RATE)
        did_train = True
        row.update(_cycle_stats_fields(f"train_scenario_{slug}", stats))
        drill_stats = record_train_cycles(drill_stats, scenario, units=units)
        save_drill_stats(drill_stats_path, drill_stats)
        _scenario_phase_banner(
            f"training complete — {tag} ({_fmt_wall_s(time.perf_counter() - phase_t0)})"
        )

        if not do_eval:
            _eval_section_break()
            continue

        _scenario_phase_banner(
            f"after-eval starting — {tag} ({eval_games}g)",
            blank_before=True,
        )
        phase_t0 = time.perf_counter()
        after = _eval_one_scenario(
            model,
            args=args,
            pool=pool,
            profile=profile,
            runs_key=runs_key,
            save_stem=save_stem,
            scenario=scenario,
            difficulty=difficulty,
            games=eval_games,
        )
        row.update(_cycle_stats_fields(f"eval_scenario_{slug}_after", after))
        _print_scenario_learn_line(
            scenario=scenario,
            difficulty=difficulty,
            before=before,
            after=after,
            row=row,
            slug=slug,
        )
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
            expected_games=eval_games,
        )
        _scenario_phase_banner(
            f"after-eval complete — {tag} ({_fmt_wall_s(time.perf_counter() - phase_t0)})"
        )
        _eval_section_break()
    if did_train:
        _print_kind_lifetime(runs_key, "Train scenarios")
    return row


def _print_scenario_learn_line(
    *,
    scenario: str,
    difficulty: int,
    before: dict[str, Any] | None,
    after: dict[str, Any],
    row: dict[str, Any],
    slug: str,
) -> None:
    """Stdout learn line + cycle-row winrate deltas (formatting owned by ``metrics``)."""
    if before is not None:
        prior_rate = float(before["winrate"])
        delta = float(after["winrate"]) - prior_rate
        row[f"eval_scenario_{slug}_prior_winrate"] = f"{prior_rate:.6f}"
        row[f"eval_scenario_{slug}_delta_winrate"] = f"{delta:.6f}"
    # Strip leading spaces from formatter — section indent owns nesting.
    _section_print(
        format_scenario_learn_line(
            scenario=scenario,
            difficulty=difficulty,
            after=after,
            before=before,
        ).lstrip(),
        indent=1,
    )


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
    from gridiron_ai.eval_outcome import format_eval_outcome

    outcome = format_eval_outcome(summary)
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
    if scenario is not None:
        difficulty = summary.get("scenario_difficulty")
        if difficulty is not None:
            line += f" difficulty={difficulty}"
        line += ScenarioEvalDebug.turn1_log_suffix(summary)
    return line


def _attach_opening_move_scores(
    model: MaskablePPO,
    summary: dict[str, Any],
    *,
    verbose: bool = False,
    all_rows: bool = False,
) -> None:
    """Score opening join-score mass for expected/turn1 rank+p; full diag on verbose THEM mispicks."""
    snapshot = summary.pop("_opening_mispick_snapshot", None)
    if snapshot is None:
        summary.pop("opening_mispick", None)
        summary.pop("opening_mispick_chosen", None)
        summary.pop("opening_mispick_diag", None)
        summary.pop("opening_move_scores", None)
        return
    try:
        diag = diagnose_opening_mispick(
            model.policy,
            snapshot,
            all_rows=all_rows,
        )
    except Exception as exc:
        summary.pop("opening_move_scores", None)
        if verbose and summary.get("opening_mispick") and summary.get("result_winner_rel") == "THEM":
            summary["opening_mispick_diag"] = {"error": str(exc)}
        else:
            summary.pop("opening_mispick", None)
            summary.pop("opening_mispick_chosen", None)
            summary.pop("opening_mispick_diag", None)
        return
    scores = diag.get("opening_move_scores")
    if scores is not None:
        summary["opening_move_scores"] = scores
    else:
        summary.pop("opening_move_scores", None)
    keep_verbose_diag = (
        verbose
        and summary.get("opening_mispick")
        and summary.get("result_winner_rel") == "THEM"
    )
    if keep_verbose_diag:
        summary["opening_mispick_diag"] = diag
    else:
        # Avoid ``policy_diag=pending`` on lean (rank/p only) lines.
        summary.pop("opening_mispick_diag", None)
        summary.pop("opening_mispick", None)
        summary.pop("opening_mispick_chosen", None)


def _drop_opening_mispick_snapshot(summary: dict[str, Any]) -> None:
    """Drop frozen opening obs when scoring is skipped (non-scenario eval)."""
    summary.pop("_opening_mispick_snapshot", None)
    summary.pop("opening_mispick", None)
    summary.pop("opening_mispick_chosen", None)
    summary.pop("opening_mispick_diag", None)
    summary.pop("opening_move_scores", None)


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
    runs_key: str,
    scenario: str | None = None,
    scenario_difficulty: int = 0,
    metrics_prior: dict[str, Any] | None = None,
    rank_history_before: list[float] | None = None,
    winrate_history_before: list[float] | None = None,
) -> dict[str, Any]:
    """Deterministic games; shaping off. Soft-mask bias follows ``eval_episode_mode`` (TRAIN=on).

    ``metrics_prior``: optional prior same-drill eval stats so the Eval line can show
    ``before→after`` drift (``_eval_one_scenario`` supplies the last eval this process).
    ``rank_history_before`` / ``winrate_history_before``: stored trails; current appended.
    """
    workers = min(games, DEFAULT_N_ENVS)
    opp_label = opponent_metrics_label(computer_opponent, onnx_profile)
    if scenario is not None:
        label = f"vs-{opp_label}-scenario-{scenario.lower()}-d{scenario_difficulty}"
    else:
        label = f"vs-{opp_label}"
    tagged = f"[{profile}] {label}"
    episode_mode = args.eval_episode_mode
    soft_mask_bias = episode_mode == "TRAIN"
    bias_bit = "soft-mask bias on" if soft_mask_bias else "soft-mask bias off"
    header = (
        f"Eval {tagged}: {games} games (deterministic, shaping off, {bias_bit}, "
        f"episode_mode={episode_mode}, n_envs={workers})…"
    )
    quiet_scenario = scenario is not None
    game_lines: list[str] | None = [] if quiet_scenario else None
    echo_laps = not quiet_scenario
    body_indent = 1 if quiet_scenario else 0
    # One header only — no second reprint with timing after the games.
    _section_print(header, indent=body_indent)
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
        soft_mask_bias=soft_mask_bias,
        verbose=bool(args.verbose),
    )
    configure_s = clk.lap("configure workers", echo=echo_laps, indent=1)
    env = pool.vec_env(workers)
    episodes: list[dict[str, Any]] = []
    try:
        obs = env.reset()
        reset_s = clk.lap(f"reset {workers} matches", echo=echo_laps, indent=1)
        completions = 0
        while completions < games:
            actions, _states = model.predict(obs, deterministic=True)
            obs, _rewards, dones, infos = env.step(actions)
            for info, done in zip(infos, dones):
                if not done:
                    continue
                completions += 1
                summary = info.get("bb_episode")
                truncated = bool(summary.get("truncated")) if summary is not None else bool(info.get("TimeLimit.truncated"))
                if summary is not None:
                    if scenario is not None:
                        _attach_opening_move_scores(
                            model,
                            summary,
                            verbose=bool(args.verbose),
                            all_rows=bool(args.super_verbose),
                        )
                    else:
                        _drop_opening_mispick_snapshot(summary)
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
        games_s = clk.lap(f"play {games} games", echo=echo_laps, indent=1)
    except (GridironApiError, EOFError):
        _release_segment_env(env, pool)
        raise
    _release_segment_env(env, pool)

    stats = aggregate_episodes(episodes)
    stats.update(_episode_success_rates(episodes))
    if scenario is not None:
        stats["scenario_difficulty"] = scenario_difficulty
        stats.update(aggregate_opening_move_scores(episodes))
    quiet_perfect = quiet_scenario and _eval_batch_all_wins(stats, games=games)
    if quiet_perfect:
        _record_scenario_perfect_summary(
            stats,
            scenario=scenario,
            scenario_difficulty=scenario_difficulty,
        )
    else:
        if echo_laps:
            _section_print(
                f"timing: {clk.summary(('configure', configure_s), ('reset', reset_s), ('games', games_s))}",
                indent=1,
            )
        if game_lines:
            for line in game_lines:
                # Built-in ``  game`` + detail indents; +1 nests under the Eval header.
                _section_print(line, indent=body_indent)
        if scenario is not None:
            rank_history: list[float] | None = None
            if rank_history_before is not None:
                rank_history = list(rank_history_before)
                cur_rank = stats.get("opening_expected_rank_best_mean")
                if cur_rank is not None:
                    rank_history.append(float(cur_rank))
                rank_history = rank_history[-LAST_EVAL_HISTORY_MAX:]
            winrate_history: list[float] | None = None
            if winrate_history_before is not None:
                winrate_history = list(winrate_history_before)
                winrate_history.append(float(stats.get("winrate") or 0.0))
                winrate_history = winrate_history[-LAST_EVAL_HISTORY_MAX:]
            print_scenario_eval_metrics(
                short_name=require_catalog().short_name(scenario),
                difficulty=int(scenario_difficulty),
                stats=stats,
                prior=metrics_prior,
                rank_history=rank_history,
                winrate_history=winrate_history,
                indent=body_indent,
            )
        else:
            print_metrics(f"Eval {tagged}", stats, prior=metrics_prior, indent=body_indent)
        _note_kind_mix(
            runs_key,
            f"Eval {tagged}",
            stats,
            print_tables=True,
            accumulate=False,
            indent=body_indent,
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
        t0 = time.perf_counter()
        out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        write_s = time.perf_counter() - t0
        # Phase banner carries wall time; only note write cost + basename here.
        _section_print(f"wrote {out.name} (write {_fmt_wall_s(write_s)})", indent=body_indent)
    return stats


def _eval_scenarios_segment(
    model: MaskablePPO,
    *,
    args: argparse.Namespace,
    pool: WorkerPool,
    profile: RewardProfileName,
    runs_key: str,
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
    pin_difficulty = args.difficulty
    # Forced --difficulty must not bump/demote curriculum levels.
    write_curriculum = update_curriculum and pin_difficulty is None
    _eval_section_break()
    if pin_difficulty is not None:
        print(
            f"Eval scenarios ({len(active)} drills, difficulty pin d{pin_difficulty}, curriculum off)",
            flush=True,
        )
    elif write_curriculum:
        print(f"Eval scenarios ({len(active)} drills, curriculum on)", flush=True)
    else:
        print(f"Eval scenarios ({len(active)} drills, curriculum off)", flush=True)
    row: dict[str, Any] = {}
    drill_stats = load_drill_stats(path_for_drill_stats(save_stem)) if write_curriculum else {}
    gate_failures_path = path_for_gate_failures(save_stem)
    drill_stats_path = path_for_drill_stats(save_stem)
    for scenario in active:
        difficulty = pin_difficulty if pin_difficulty is not None else get_difficulty(levels, scenario)
        stats = _eval_one_scenario(
            model,
            args=args,
            pool=pool,
            profile=profile,
            runs_key=runs_key,
            save_stem=save_stem,
            scenario=scenario,
            difficulty=difficulty,
        )
        if write_curriculum:
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


def _eval_phase_zip(args: argparse.Namespace) -> Path | None:
    """Zip for ``--phase`` eval pin, or None when using the live long-running tree."""
    if args.phase is None:
        return None
    if not args.eval_only:
        raise SystemExit("--phase requires --eval-only (read-only; will not train or overwrite the zip)")
    zip_path = eval_zip_for_phase(args.phase)
    if not zip_path.is_file():
        raise SystemExit(f"--phase {args.phase}: missing artifact {zip_path}")
    return zip_path


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
        return make_bootstrap_vec_env(
            base_url=args.base_url,
            continue_on_submit_error=args.continue_on_submit_error,
            reward_shaping=reward_shaping,
            n_envs=DEFAULT_N_ENVS,
            verbose=bool(args.verbose),
        )

    phase_zip = _eval_phase_zip(args)
    if phase_zip is not None:
        if needs_train:
            raise SystemExit("--phase requires --eval-only (read-only; will not train or overwrite the zip)")
        # Scratch stem: curriculum sidecars empty; never write back into the phase zip.
        save_stem = RUNS_DIR / "eval_phase" / phase_zip.stem
        save_stem.parent.mkdir(parents=True, exist_ok=True)
        name_prefix = STEP_CHECKPOINT_PREFIX
        save_dir = save_stem.parent
        session_log = tb_runs / _SESSION_LONG
        print(f"Eval --phase {args.phase} ({phase}/{reward_profile}): {phase_zip}", flush=True)
        model = MaskablePPO.load(str(phase_zip), device=DEFAULT_DEVICE)
        model.verbose = 1
        _set_learning_rate(model, _DEFAULT_LEARNING_RATE)
        _set_ent_coef(model)
        _set_vf_coef(model)
        _set_ppo_stability(model)
        return model, save_stem, save_dir, name_prefix, True, session_log

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
            _set_ent_coef(model)
            _set_vf_coef(model)
            _set_ppo_stability(model)
        else:
            model = MaskablePPO.load(str(zip_path), device=DEFAULT_DEVICE)
            model.verbose = 1
            _set_learning_rate(model, _DEFAULT_LEARNING_RATE)
            _set_ent_coef(model)
            _set_vf_coef(model)
            _set_ppo_stability(model)
    else:
        raise SystemExit(f"No checkpoint at {zip_path} after ensure_checkpoint_from_prior")
    return model, save_stem, save_dir, name_prefix, continued, session_log


def _skip_scenario_eval(args: argparse.Namespace) -> bool:
    """``--skip-scenarios`` always skips train; also skips eval in ``--eval-only`` (``./eval.sh``)."""
    return bool(args.skip_scenarios and args.eval_only)


def _filter_seatable_opponent_jobs(
    jobs: list[tuple[int, str, str, RewardProfileName | None, str]],
    seatable: set[tuple[str, str | None]],
    unavailable_reasons: dict[tuple[str, str | None], str],
) -> list[tuple[int, str, str, RewardProfileName | None, str]]:
    """Drop train/eval seats the server catalog omits (e.g. stale ONNX schema)."""
    kept: list[tuple[int, str, str, RewardProfileName | None, str]] = []
    for amount, mode, computer, onnx_name, key in jobs:
        seat = (computer, onnx_name)
        if seat not in seatable:
            if amount > 0:
                reason = unavailable_reasons.get(
                    seat,
                    "not in server seatable catalog (GET /api/computer-opponents)",
                )
                print(
                    f"  skip {key}: {opponent_metrics_label(computer, onnx_name)} — {reason}",
                    flush=True,
                )
            continue
        kept.append((amount, mode, computer, onnx_name, key))
    return kept


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
    runs_key = target.checkpoint_key
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
    catalog_client = GridironClient(args.base_url)
    seatable = catalog_client.seatable_computer_opponents()
    unavailable = catalog_client.unavailable_computer_opponent_reasons()
    train_jobs = _filter_seatable_opponent_jobs(train_jobs, seatable, unavailable)
    eval_jobs = _filter_seatable_opponent_jobs(eval_jobs, seatable, unavailable)
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
                        runs_key=runs_key,
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
            runs_key=runs_key,
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
                runs_key=runs_key,
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
                runs_key=runs_key,
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
                    runs_key=runs_key,
                    save_stem=save_stem,
                    update_curriculum=not args.eval_only,
                )
            )
    if args.phase is None:
        t0 = time.perf_counter()
        atomic_model_save(model, save_stem)
        print(f"  cycle checkpoint save: {_fmt_wall_s(time.perf_counter() - t0)}", flush=True)
    else:
        print(f"  skip checkpoint save (--phase {args.phase} read-only eval)", flush=True)
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
    """Log config knobs, full-game budgets, and shared 1×/2×/8× scenario train listing."""
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
        drill_stats = load_drill_stats_cumulative(target.checkpoint_key) if scenarios else {}
        by_units = (
            train_multiplier_groups(
                levels,
                scenarios,
                gate_failures,
                drill_stats,
                learning=args.train_scenario_learning,
                mastered=args.train_scenario_mastered,
                game_phase=game_phase,
            )
            if scenarios
            else {}
        )
        n_scenario_train = sum(len(labels) for labels in by_units.values())
        if not claims_unlocked(levels, scenarios, gate_failures):
            gate = f"claims deferred until board drills d{CLAIM_UNLOCK_BOARD_DIFFICULTY}"
        elif game_phase:
            gate = (
                f"game phase (profile stem gating d{MAX_DIFFICULTY}) — full-game first"
                if target.phase == PHASE_PROFILES
                else "game phase (scenario_b unlocked) — full-game first"
            )
            if n_scenario_train:
                gate += f"; scenario train: {n_scenario_train}"
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
            f"order={order} "
            f"heuristic={heuristic} lookahead={lookahead} "
            f"onnx={onnx_ts}×[{','.join(FULL_GAME_ONNX_OPPONENT_PROFILES)}] — {gate}",
            flush=True,
        )
        for line in format_train_multiplier_lines(by_units):
            print(line, flush=True)
        if scenarios and not by_units:
            print("  train multipliers (next cycle): none", flush=True)


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
    if args.phase is not None:
        _eval_phase_zip(args)  # fail fast if missing
        # One zip → one pass (ignore profile rotation / forever).
        startup_targets = startup_targets[:1]
        args.cycles = 1
        forever = False
        print(
            f"--phase {args.phase}: single eval pass with reward profile={startup_targets[0].reward_profile} "
            f"(live key={startup_targets[0].checkpoint_key})",
            flush=True,
        )
    if args.eval_only:
        # Default --cycles 0 means forever; for a quick eval pass, run each target once.
        if args.cycles == 0:
            args.cycles = len(startup_targets)
            forever = False
        if args.profile == "all" and args.phase is None:
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
                f"ent_coef={DEFAULT_ENT_COEF} "
                f"vf_coef={DEFAULT_VF_COEF} "
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
                if args.phase is None:
                    atomic_model_save(model, save_stem)
                model = None
                save_stem = None
                loaded_key = None
    except KeyboardInterrupt:
        print("\nInterrupted — saving and exiting.", flush=True)
        try:
            if model is not None and save_stem is not None and args.phase is None:
                atomic_model_save(model, save_stem)
            _print_scenario_perfect_summaries()
            _print_interrupt_warnings(args.profile)
        except KeyboardInterrupt:
            print("Interrupted again during save — exiting.", flush=True)
    finally:
        try:
            pool.close()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
