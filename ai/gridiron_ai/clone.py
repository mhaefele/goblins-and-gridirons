"""Behavioral clone: copy expert legal-row indices into a MaskablePPO zip.

Owner: npz → checkpoint weights via ``evaluate_actions`` (not ``learn()``). No HTTP.
Called by ``scripts/clone_expert``. See ``ai/OWNERSHIP.md``.

Soft-mask is forced off during clone (zeros in obs) so NLL fits weights without the
train-time ``+SOFT_MASK_LOGIT_BIAS`` crutch. PPO rollouts still use soft-mask via the env.

# DEVIATION: we call ``evaluate_actions`` ourselves instead of ``MaskablePPO.learn()``.
"""

from __future__ import annotations

import logging
import time
from pathlib import Path
from typing import Any

import gymnasium as gym
import numpy as np
import torch as th
from gymnasium import spaces
from sb3_contrib import MaskablePPO
from sb3_contrib.common.wrappers import ActionMasker
from stable_baselines3.common.vec_env import DummyVecEnv

from gridiron_ai.actions import legal_row_action_mask, legal_row_action_n
from gridiron_ai.checkpoints import atomic_model_save
from gridiron_ai.constants import OBS_KEY_SOFT_MASK_LEGAL
from gridiron_ai.expert import NPZ_KEY_ACTION, batch_gym_obs, load_npz
from gridiron_ai.legal_row_policy import LegalRowActorCriticPolicy
from gridiron_ai.obs import dict_observation_space, empty_step_observation

logger = logging.getLogger(__name__)

# Match train_self_play A/B-locked PPO sizes so the cloned zip can resume ``learn()``.
_CLONE_N_ENVS = 2
_CLONE_N_STEPS = 128
_CLONE_BATCH_SIZE = 256
# Fit minibatches (separate from the PPO constructor batch above).
_CLONE_FIT_BATCH_SIZE = 1024
# Rare full-dump pass — stop early when mean NLL plateaus; cap so it cannot run forever.
MAX_CLONE_EPOCHS = 30
_CLONE_PLATEAU_PATIENCE = 3
_CLONE_PLATEAU_MIN_DELTA = 0.01
# Heartbeat while an epoch is still chewing through millions of steps.
_CLONE_PROGRESS_EVERY_S = 120.0


def _clone_device() -> str:
    """Prefer Apple MPS, then CUDA, else CPU — clone is offline and wall-clock heavy."""
    if getattr(th.backends, "mps", None) is not None and th.backends.mps.is_available():
        return "mps"
    if th.cuda.is_available():
        return "cuda"
    return "cpu"


def _format_duration(seconds: float) -> str:
    total = max(0, int(round(seconds)))
    hours, rem = divmod(total, 3600)
    minutes, secs = divmod(rem, 60)
    if hours:
        return f"{hours}h{minutes:02d}m{secs:02d}s"
    if minutes:
        return f"{minutes}m{secs:02d}s"
    return f"{secs}s"


def clone_npz_into_checkpoint(
    npz_path: Path,
    checkpoint_zips: list[Path],
    *,
    learning_rate: float = 1e-4,
    seed: int = 0,
    resume_zip: Path | None = None,
) -> float:
    """Train so the policy assigns higher probability to the recorded move. Returns last-epoch average loss."""
    arrays = load_npz(npz_path)
    return clone_arrays_into_checkpoint(
        arrays,
        checkpoint_zips,
        learning_rate=learning_rate,
        seed=seed,
        resume_zip=resume_zip,
    )


def clone_arrays_into_checkpoint(
    arrays: dict[str, np.ndarray],
    checkpoint_zips: list[Path],
    *,
    learning_rate: float = 1e-4,
    seed: int = 0,
    resume_zip: Path | None = None,
) -> float:
    """Behavioral clone from packed arrays. Returns last-epoch average loss."""
    n = int(arrays[NPZ_KEY_ACTION].shape[0])
    if n == 0:
        raise ValueError("expert arrays contain 0 steps")
    device = _clone_device()
    logger.info(
        "cloning %s expert steps (device=%s fit_batch=%s max_epochs=%s)",
        f"{n:,}",
        device,
        _CLONE_FIT_BATCH_SIZE,
        MAX_CLONE_EPOCHS,
    )

    bootstrap = _policy_spaces_vec_env()
    if resume_zip is not None:
        logger.info("resuming weights from %s", resume_zip)
        model = MaskablePPO.load(str(resume_zip), env=bootstrap, device=device)
    else:
        model = MaskablePPO(
            LegalRowActorCriticPolicy,
            bootstrap,
            n_steps=_CLONE_N_STEPS,
            batch_size=_CLONE_BATCH_SIZE,
            verbose=0,
            seed=seed,
            device=device,
        )
    mean_nll = fit_behavioral_clone(
        model,
        arrays,
        learning_rate=learning_rate,
        seed=seed,
    )
    for zip_path in checkpoint_zips:
        zip_path.parent.mkdir(parents=True, exist_ok=True)
        atomic_model_save(model, zip_path.with_suffix(""))
        logger.info("wrote %s", zip_path if zip_path.suffix == ".zip" else zip_path.with_suffix(".zip"))
    bootstrap.close()
    return mean_nll


def fit_behavioral_clone(
    model: MaskablePPO,
    arrays: dict[str, np.ndarray],
    *,
    learning_rate: float,
    seed: int,
) -> float:
    """Shuffle the dump and walk until mean NLL plateaus (or ``MAX_CLONE_EPOCHS``). Returns last-epoch average loss."""
    n = int(arrays[NPZ_KEY_ACTION].shape[0])
    batch_size = _CLONE_FIT_BATCH_SIZE
    rng = np.random.default_rng(seed)
    model.policy.set_training_mode(True)
    for group in model.policy.optimizer.param_groups:
        group["lr"] = learning_rate
    last_mean = float("nan")
    best_mean = float("inf")
    stagnant = 0
    clone_started = time.perf_counter()
    epoch_durations: list[float] = []
    for epoch in range(MAX_CLONE_EPOCHS):
        epoch_started = time.perf_counter()
        progress_logged_at = epoch_started
        order = rng.permutation(n)
        total = 0.0
        batches = 0
        steps_done = 0
        for start in range(0, n, batch_size):
            idx = order[start : start + batch_size]
            loss = _batch_nll(model, arrays, idx)
            model.policy.optimizer.zero_grad()
            loss.backward()
            model.policy.optimizer.step()
            total += float(loss.item())
            batches += 1
            steps_done += int(idx.shape[0])
            now = time.perf_counter()
            if now - progress_logged_at >= _CLONE_PROGRESS_EVERY_S:
                epoch_so_far = now - epoch_started
                running_mean = total / max(batches, 1)
                rate = steps_done / epoch_so_far if epoch_so_far > 0 else 0.0
                remain_steps = n - steps_done
                epoch_eta = remain_steps / rate if rate > 0 else 0.0
                logger.info(
                    "clone epoch %s/%s  %s/%s steps (%.0f%%)  running_loss=%.4f  %.0f steps/s  epoch_eta~%s  elapsed=%s",
                    epoch + 1,
                    MAX_CLONE_EPOCHS,
                    f"{steps_done:,}",
                    f"{n:,}",
                    100.0 * steps_done / n,
                    running_mean,
                    rate,
                    _format_duration(epoch_eta),
                    _format_duration(now - clone_started),
                )
                progress_logged_at = now
        last_mean = total / max(batches, 1)
        epoch_s = time.perf_counter() - epoch_started
        epoch_durations.append(epoch_s)
        elapsed_s = time.perf_counter() - clone_started
        steps_per_s = n / epoch_s if epoch_s > 0 else 0.0
        remaining_epochs = MAX_CLONE_EPOCHS - (epoch + 1)
        mean_epoch_s = sum(epoch_durations) / len(epoch_durations)
        eta_s = mean_epoch_s * remaining_epochs
        logger.info(
            "clone epoch %s/%s done  mean_loss=%.4f  epoch=%s  %.0f steps/s  elapsed=%s  eta~%s (if no early-stop)",
            epoch + 1,
            MAX_CLONE_EPOCHS,
            last_mean,
            _format_duration(epoch_s),
            steps_per_s,
            _format_duration(elapsed_s),
            _format_duration(eta_s),
        )
        if last_mean < best_mean - _CLONE_PLATEAU_MIN_DELTA:
            best_mean = last_mean
            stagnant = 0
        else:
            stagnant += 1
            if stagnant >= _CLONE_PLATEAU_PATIENCE:
                logger.info(
                    "clone early-stop: mean_loss plateau (patience=%s, min_delta=%.4f) after %s",
                    _CLONE_PLATEAU_PATIENCE,
                    _CLONE_PLATEAU_MIN_DELTA,
                    _format_duration(elapsed_s),
                )
                break
    return last_mean


def _batch_nll(model: MaskablePPO, arrays: dict[str, np.ndarray], idx: np.ndarray) -> th.Tensor:
    """Loss for one minibatch: how unlikely the recorded moves currently are (lower is better)."""
    obs_np = batch_gym_obs(arrays, idx)
    # Clone ignores dump/npz soft-mask flags — imitate Lookahead under plain logits.
    obs_np[OBS_KEY_SOFT_MASK_LEGAL] = np.zeros_like(obs_np[OBS_KEY_SOFT_MASK_LEGAL])
    obs_tensor, _ = model.policy.obs_to_tensor(obs_np)
    actions = th.as_tensor(arrays[NPZ_KEY_ACTION][idx], device=model.device)
    _values, log_prob, _entropy = model.policy.evaluate_actions(obs_tensor, actions)
    return -log_prob.mean()


def _policy_spaces_vec_env() -> DummyVecEnv:
    """DummyVecEnv sized so n_steps×n_envs divides the PPO batch_size (no HTTP)."""

    def _thunk() -> ActionMasker:
        return ActionMasker(_PolicySpacesEnv(), _mask_fn)

    return DummyVecEnv([_thunk for _ in range(_CLONE_N_ENVS)])


def _mask_fn(env: Any) -> Any:
    return env.unwrapped.action_masks()


class _PolicySpacesEnv(gym.Env):
    """Spaces + dummy reset/step so MaskablePPO can be constructed without the Kotlin server."""

    metadata = {"render_modes": []}

    def __init__(self) -> None:
        super().__init__()
        self.observation_space = dict_observation_space()
        self.action_space = spaces.Discrete(legal_row_action_n())

    def reset(self, *, seed: int | None = None, options: dict[str, Any] | None = None) -> tuple[dict[str, np.ndarray], dict[str, Any]]:
        super().reset(seed=seed)
        return empty_step_observation(), {}

    def step(self, action: Any) -> tuple[dict[str, np.ndarray], float, bool, bool, dict[str, Any]]:
        del action
        return empty_step_observation(), 0.0, True, False, {}

    def action_masks(self) -> np.ndarray:
        return legal_row_action_mask(legal_move_count=0)
