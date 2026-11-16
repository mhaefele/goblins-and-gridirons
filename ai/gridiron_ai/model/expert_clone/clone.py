"""Behavioral clone: copy expert legal-row indices into a MaskablePPO zip.

Owner: npz → checkpoint weights via ``evaluate_actions`` (not ``learn()``). No HTTP.
Called by ``scripts/clone_expert``. See ``ai/OWNERSHIP.md``.

Soft-mask is forced off during clone (zeros in obs) so NLL fits weights without the
train-time ``+SOFT_MASK_LOGIT_BIAS`` crutch. PPO rollouts still use soft-mask via the env.

# DEVIATION: we call ``evaluate_actions`` ourselves instead of ``MaskablePPO.learn()``.
"""

from __future__ import annotations

import json
import logging
import os
import re
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

from gridiron_ai.model.actions import legal_row_action_mask, legal_row_action_n
from gridiron_ai.train.checkpoints import atomic_model_save
from gridiron_ai.model.constants import OBS_KEY_SOFT_MASK_LEGAL
from gridiron_ai.model.expert_clone.expert import NPZ_KEY_ACTION, batch_gym_obs, load_npz
from gridiron_ai.model.legal_row_policy import LegalRowActorCriticPolicy, maskable_ppo_policy_kwargs
from gridiron_ai.model.obs import dict_observation_space, empty_step_observation

logger = logging.getLogger(__name__)

# Match train_self_play A/B-locked PPO sizes so the cloned zip can resume ``learn()``.
_CLONE_N_ENVS = 2
_CLONE_N_STEPS = 128
_CLONE_BATCH_SIZE = 256
# Fit minibatch; 512 amortizes overhead vs historical 256 without the 1024 flop.
_CLONE_FIT_BATCH_SIZE = 512
_CLONE_DEVICE = "cpu"
# Rare full-dump pass — stop early when mean NLL plateaus; cap so it cannot run forever.
MAX_CLONE_EPOCHS = 30
# One epoch that fails to beat best_mean by at least this NLL drop → stop (was 3× / 0.01).
_CLONE_PLATEAU_PATIENCE = 1
_CLONE_PLATEAU_MIN_DELTA = 0.02
# Heartbeat while an epoch is still chewing through millions of steps.
_CLONE_PROGRESS_EVERY_S = 120.0

_PROGRESS_JSON_SUFFIX = ".clone_progress.json"
_EPOCH_ZIP_RE = re.compile(r"^(?P<stem>.+)\.clone_epoch_(?P<epoch>\d+)\.zip$")


def _configure_cpu_threads() -> int:
    """Use all logical CPUs for Torch intra-op matmuls (clone is otherwise one-thread-looking)."""
    n = os.cpu_count() or 1
    th.set_num_threads(n)
    return int(th.get_num_threads())


def _format_duration(seconds: float) -> str:
    total = max(0, int(round(seconds)))
    hours, rem = divmod(total, 3600)
    minutes, secs = divmod(rem, 60)
    if hours:
        return f"{hours}h{minutes:02d}m{secs:02d}s"
    if minutes:
        return f"{minutes}m{secs:02d}s"
    return f"{secs}s"


def clone_progress_json_path(final_zip: Path) -> Path:
    """Sidecar next to the destination zip (``maskable_ppo_seed.clone_progress.json``)."""
    zip_path = final_zip if final_zip.suffix == ".zip" else final_zip.with_suffix(".zip")
    return zip_path.with_name(f"{zip_path.stem}{_PROGRESS_JSON_SUFFIX}")


def clone_epoch_zip_path(final_zip: Path, completed_epochs: int) -> Path:
    """Single in-progress weights file tagged with how many epochs finished."""
    zip_path = final_zip if final_zip.suffix == ".zip" else final_zip.with_suffix(".zip")
    return zip_path.with_name(f"{zip_path.stem}.clone_epoch_{int(completed_epochs)}.zip")


def find_clone_epoch_zip(final_zip: Path, completed_epochs: int | None = None) -> Path | None:
    """Locate ``*.clone_epoch_N.zip`` beside the destination (exact N, or highest if N is None)."""
    zip_path = final_zip if final_zip.suffix == ".zip" else final_zip.with_suffix(".zip")
    stem = zip_path.stem
    parent = zip_path.parent
    if completed_epochs is not None:
        candidate = clone_epoch_zip_path(zip_path, completed_epochs)
        return candidate if candidate.is_file() else None
    best_n = -1
    best_path: Path | None = None
    for path in parent.glob(f"{stem}.clone_epoch_*.zip"):
        match = _EPOCH_ZIP_RE.match(path.name)
        if match is None or match.group("stem") != stem:
            continue
        n = int(match.group("epoch"))
        if n > best_n:
            best_n = n
            best_path = path
    return best_path


def _read_clone_progress(final_zip: Path) -> dict[str, Any] | None:
    path = clone_progress_json_path(final_zip)
    if not path.is_file():
        return None
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(raw, dict):
        return None
    return raw


def _write_clone_progress(final_zip: Path, payload: dict[str, Any]) -> None:
    path = clone_progress_json_path(final_zip)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f"{path.name}.writing")
    tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def _clear_clone_progress(final_zip: Path) -> None:
    """Remove in-progress zip(s) + progress sidecar after a successful final save."""
    zip_path = final_zip if final_zip.suffix == ".zip" else final_zip.with_suffix(".zip")
    clone_progress_json_path(zip_path).unlink(missing_ok=True)
    for path in zip_path.parent.glob(f"{zip_path.stem}.clone_epoch_*.zip"):
        path.unlink(missing_ok=True)
    # Leftover atomic temps from a killed save.
    for path in zip_path.parent.glob(f"{zip_path.stem}.clone_epoch_*.writing.zip"):
        path.unlink(missing_ok=True)


def _save_clone_epoch_checkpoint(
    model: MaskablePPO,
    final_zip: Path,
    *,
    completed_epochs: int,
    best_mean: float,
    stagnant: int,
    last_mean: float,
    seed: int,
    n_steps: int,
) -> Path:
    """Atomically write ``*.clone_epoch_N.zip``, drop the previous epoch zip, refresh JSON."""
    zip_path = final_zip if final_zip.suffix == ".zip" else final_zip.with_suffix(".zip")
    new_zip = clone_epoch_zip_path(zip_path, completed_epochs)
    new_zip.parent.mkdir(parents=True, exist_ok=True)
    # atomic_model_save uses Path.with_suffix(".zip"), which breaks on stems like
    # ``*.clone_epoch_10`` (``.10`` looks like a suffix). Save via an underscore stem, then rename.
    safe_stem = zip_path.parent / f"{zip_path.stem}_clone_writing"
    written = atomic_model_save(model, safe_stem)
    os.replace(written, new_zip)
    # Delete older epoch zips for this stem (keep only the latest).
    for path in zip_path.parent.glob(f"{zip_path.stem}.clone_epoch_*.zip"):
        if path.resolve() != new_zip.resolve():
            path.unlink(missing_ok=True)
    _write_clone_progress(
        final_zip,
        {
            "completed_epochs": int(completed_epochs),
            "best_mean": float(best_mean),
            "stagnant": int(stagnant),
            "last_mean": float(last_mean),
            "seed": int(seed),
            "n_steps": int(n_steps),
        },
    )
    logger.info("clone checkpoint → %s (completed_epochs=%s)", new_zip, completed_epochs)
    return new_zip


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
    """Behavioral clone from packed arrays. Returns last-epoch average loss.

    Progress is anchored on ``checkpoint_zips[0]``: ``*.clone_epoch_N.zip`` +
    ``*.clone_progress.json``. A later run resumes from that pair automatically.
    """
    if not checkpoint_zips:
        raise ValueError("checkpoint_zips must be non-empty")
    n = int(arrays[NPZ_KEY_ACTION].shape[0])
    if n == 0:
        raise ValueError("expert arrays contain 0 steps")
    device = _CLONE_DEVICE
    batch_size = _CLONE_FIT_BATCH_SIZE
    threads = _configure_cpu_threads() if device == "cpu" else th.get_num_threads()
    primary_zip = checkpoint_zips[0] if checkpoint_zips[0].suffix == ".zip" else checkpoint_zips[0].with_suffix(".zip")

    start_epoch = 0
    best_mean = float("inf")
    stagnant = 0
    load_zip = resume_zip
    progress = _read_clone_progress(primary_zip)
    progress_zip = None
    if progress is not None:
        completed = int(progress.get("completed_epochs") or 0)
        progress_zip = find_clone_epoch_zip(primary_zip, completed)
        if progress_zip is None:
            progress_zip = find_clone_epoch_zip(primary_zip)
        if (
            progress_zip is not None
            and completed > 0
            and int(progress.get("n_steps") or -1) in (-1, n)
            and int(progress.get("seed") or seed) == seed
        ):
            start_epoch = completed
            best_mean = float(progress.get("best_mean", best_mean))
            stagnant = int(progress.get("stagnant") or 0)
            load_zip = progress_zip
            logger.info(
                "resuming clone from %s (next epoch %s/%s best_mean=%.4f stagnant=%s)",
                progress_zip,
                start_epoch + 1,
                MAX_CLONE_EPOCHS,
                best_mean if best_mean < float("inf") else float("nan"),
                stagnant,
            )
        elif progress_zip is None and completed > 0:
            logger.warning(
                "clone progress json present (%s epochs) but epoch zip missing — starting fresh",
                completed,
            )

    logger.info(
        "cloning %s expert steps (device=%s batch=%s max_epochs=%s torch_threads=%s start_epoch=%s)",
        f"{n:,}",
        device,
        batch_size,
        MAX_CLONE_EPOCHS,
        threads,
        start_epoch,
    )

    bootstrap = _policy_spaces_vec_env()
    if load_zip is not None:
        if resume_zip is not None and load_zip != resume_zip and progress_zip is None:
            logger.info("resuming weights from %s", resume_zip)
        model = MaskablePPO.load(str(load_zip), env=bootstrap, device=device)
    else:
        model = MaskablePPO(
            LegalRowActorCriticPolicy,
            bootstrap,
            n_steps=_CLONE_N_STEPS,
            batch_size=_CLONE_BATCH_SIZE,
            policy_kwargs=maskable_ppo_policy_kwargs(),
            verbose=0,
            seed=seed,
            device=device,
        )
    mean_nll = fit_behavioral_clone(
        model,
        arrays,
        batch_size=batch_size,
        learning_rate=learning_rate,
        seed=seed,
        start_epoch=start_epoch,
        best_mean=best_mean,
        stagnant=stagnant,
        progress_zip=primary_zip,
    )
    for zip_path in checkpoint_zips:
        zip_path.parent.mkdir(parents=True, exist_ok=True)
        atomic_model_save(model, zip_path.with_suffix(""))
        logger.info("wrote %s", zip_path if zip_path.suffix == ".zip" else zip_path.with_suffix(".zip"))
    _clear_clone_progress(primary_zip)
    bootstrap.close()
    return mean_nll


def fit_behavioral_clone(
    model: MaskablePPO,
    arrays: dict[str, np.ndarray],
    *,
    batch_size: int,
    learning_rate: float,
    seed: int,
    start_epoch: int = 0,
    best_mean: float = float("inf"),
    stagnant: int = 0,
    progress_zip: Path | None = None,
) -> float:
    """Shuffle the dump and walk until mean NLL plateaus (or ``MAX_CLONE_EPOCHS``). Returns last-epoch average loss.

    When ``progress_zip`` is set, writes ``*.clone_epoch_N.zip`` + progress JSON after each epoch
    so a killed run can continue. Shuffle is ``seed + epoch`` so resume does not replay old order.
    """
    n = int(arrays[NPZ_KEY_ACTION].shape[0])
    model.policy.set_training_mode(True)
    for group in model.policy.optimizer.param_groups:
        group["lr"] = learning_rate
    last_mean = float("nan")
    clone_started = time.perf_counter()
    epoch_durations: list[float] = []
    if start_epoch >= MAX_CLONE_EPOCHS:
        logger.info("clone already finished all %s epochs — nothing to do", MAX_CLONE_EPOCHS)
        return last_mean
    for epoch in range(start_epoch, MAX_CLONE_EPOCHS):
        epoch_started = time.perf_counter()
        progress_logged_at = epoch_started
        # Per-epoch RNG so resume at epoch k gets a fresh shuffle without replaying 0..k-1.
        order = np.random.default_rng(seed + epoch).permutation(n)
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
        prev_mean = last_mean
        last_mean = total / max(batches, 1)
        delta_s = "—" if np.isnan(prev_mean) else f"{last_mean - prev_mean:+.4f}"
        epoch_s = time.perf_counter() - epoch_started
        epoch_durations.append(epoch_s)
        elapsed_s = time.perf_counter() - clone_started
        steps_per_s = n / epoch_s if epoch_s > 0 else 0.0
        remaining_epochs = MAX_CLONE_EPOCHS - (epoch + 1)
        mean_epoch_s = sum(epoch_durations) / len(epoch_durations)
        eta_s = mean_epoch_s * remaining_epochs
        logger.info(
            "clone epoch %s/%s done  mean_loss=%.4f (%s)  epoch=%s  %.0f steps/s  elapsed=%s  eta~%s (if no early-stop)",
            epoch + 1,
            MAX_CLONE_EPOCHS,
            last_mean,
            delta_s,
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
        if progress_zip is not None:
            _save_clone_epoch_checkpoint(
                model,
                progress_zip,
                completed_epochs=epoch + 1,
                best_mean=best_mean,
                stagnant=stagnant,
                last_mean=last_mean,
                seed=seed,
                n_steps=n,
            )
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
