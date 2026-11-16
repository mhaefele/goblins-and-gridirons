"""Checkpoint zip I/O helpers (atomic long-running saves, step-checkpoint pruning).

Owner: resume zip durability + ``*_steps.zip`` retention under ``checkpoints/``.
See ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

import importlib
import os
import re
import sys
from pathlib import Path
from typing import Any

import cloudpickle

from gridiron_ai.train.paths import CHECKPOINT_DIR, PICKLE_ALIASES_PATH

STEP_CHECKPOINT_PREFIX = "maskable_ppo_selfplay"
DEFAULT_STEP_CHECKPOINT_KEEP = 50
_STEP_ZIP_RE = re.compile(
    rf"^{re.escape(STEP_CHECKPOINT_PREFIX)}_(?P<steps>\d+)_steps\.zip$"
)


def install_legacy_pickle_aliases(path: Path | None = None) -> int:
    """Apply optional ``old.module=new.module`` aliases from a local properties file.

    # DEVIATION: SB3 ``MaskablePPO.load`` unpickles ``policy_class`` by module path.
    # Zips saved under a prior package name fail without a local remap. Missing/empty
    # file → no-op. After resume zips are re-saved under current module paths, delete
    # the file. Path is gitignored (``local.pickle_aliases.properties``).
    """
    aliases_path = PICKLE_ALIASES_PATH if path is None else path
    if not aliases_path.is_file():
        return 0
    applied = 0
    for raw in aliases_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        old_name, new_name = (part.strip() for part in line.split("=", 1))
        if not old_name or not new_name:
            continue
        try:
            module = importlib.import_module(new_name)
        except ModuleNotFoundError:
            # Stale local remap after a rename/delete — skip so import still works.
            continue
        sys.modules.setdefault(old_name, module)
        applied += 1
    return applied


def _schedule_as_float(value: Any, *, fallback: float) -> float:
    """Evaluate an SB3 schedule/callable to a plain float for pickle-safe checkpointing."""
    if value is None:
        return fallback
    if callable(value):
        try:
            return float(value(1.0))
        except Exception:
            return fallback
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _sb3_data_exclude_keys(model: Any) -> set[str]:
    """Keys SB3 omits from the zip ``data`` blob (still kept on the live model for save)."""
    exclude = set(model._excluded_save_params()) if hasattr(model, "_excluded_save_params") else set()
    if hasattr(model, "_get_torch_save_params"):
        state_names, torch_names = model._get_torch_save_params()
        for name in list(state_names) + list(torch_names):
            exclude.add(str(name).split(".", 1)[0])
    return exclude


def atomic_model_save(model: Any, save_stem: str | Path) -> Path:
    """Write model zip via temp file + rename so resume checkpoint never stays truncated.

    # DEVIATION: SB3 excludes ``env`` from the zip dict, but instance attrs that close over
    # ``model`` (verbose ``model.train`` hook, schedule lambdas, ep_info payloads, …) still
    # pickle SubprocVecEnv → ``_thread.lock``. Sanitize known offenders, then drop anything
    # else in ``__dict__`` that still fails ``cloudpickle`` (restored after save).
    """
    stem = Path(save_stem)
    final_zip = stem.with_suffix(".zip")
    tmp_zip = stem.with_name(f"{stem.name}.writing.zip")
    held: dict[str, Any] = {}
    # Verbose evidence installs ``model.train = closure(model)`` — not in SB3's exclude list.
    held_train = model.__dict__.pop("train", None)
    held_hook_flag = model.__dict__.pop("_gridiron_evidence_train_hook", None)
    for name in ("env", "_vec_normalize_env"):
        if hasattr(model, name):
            held[name] = getattr(model, name)
            setattr(model, name, None)
    for name, fallback in (
        ("learning_rate", 1e-3),
        ("lr_schedule", 1e-3),
        ("clip_range", 0.2),
        ("clip_range_vf", 0.2),
    ):
        if not hasattr(model, name):
            continue
        current = getattr(model, name)
        if current is None:
            continue
        held[name] = current
        setattr(model, name, _schedule_as_float(current, fallback=fallback))
    # Last resort: anything still unpicklable would blow up data_to_json the same way.
    exclude = _sb3_data_exclude_keys(model)
    stripped: list[str] = []
    for key in list(model.__dict__.keys()):
        if key in exclude or key in held:
            continue
        value = model.__dict__[key]
        try:
            cloudpickle.dumps(value)
        except Exception:
            held[key] = model.__dict__.pop(key)
            stripped.append(key)
    if stripped:
        print(
            f"[gridiron] checkpoint save: temporarily dropped unpicklable attrs {stripped}",
            flush=True,
        )
    try:
        # SB3 appends/replaces to ``.zip`` only when the path is not already ``.zip``.
        model.save(str(tmp_zip))
        if not tmp_zip.is_file():
            raise FileNotFoundError(f"model.save did not create {tmp_zip}")
        os.replace(tmp_zip, final_zip)
    except Exception:
        tmp_zip.unlink(missing_ok=True)
        raise
    finally:
        for name, value in held.items():
            if name in ("env", "_vec_normalize_env", "learning_rate", "lr_schedule", "clip_range", "clip_range_vf"):
                setattr(model, name, value)
            else:
                model.__dict__[name] = value
        if held_train is not None:
            model.__dict__["train"] = held_train
        if held_hook_flag is not None:
            model.__dict__["_gridiron_evidence_train_hook"] = held_hook_flag
    return final_zip


def trim_step_checkpoints(
    checkpoint_dir: Path,
    *,
    keep: int = DEFAULT_STEP_CHECKPOINT_KEEP,
    name_prefix: str = STEP_CHECKPOINT_PREFIX,
) -> int:
    """Drop oldest ``*_steps.zip`` checkpoints, keeping ``keep`` newest by step count."""
    if keep <= 0 or not checkpoint_dir.is_dir():
        return 0
    candidates: list[tuple[int, Path]] = []
    for path in checkpoint_dir.glob(f"{name_prefix}_*_steps.zip"):
        match = _STEP_ZIP_RE.match(path.name)
        if match is None:
            continue
        candidates.append((int(match.group("steps")), path))
    candidates.sort(key=lambda item: item[0])
    to_remove = candidates[:-keep] if len(candidates) > keep else []
    for _, path in to_remove:
        path.unlink()
    return len(to_remove)


def prepare_checkpoint_dirs(
    *,
    keep: int = DEFAULT_STEP_CHECKPOINT_KEEP,
    name_prefix: str = STEP_CHECKPOINT_PREFIX,
) -> dict[str, int]:
    """Startup housekeeping: stale ``.writing.zip`` temps + prune old step checkpoints."""
    if not CHECKPOINT_DIR.is_dir():
        return {}
    removed_by_dir: dict[str, int] = {}
    for subdir in sorted(CHECKPOINT_DIR.iterdir()):
        if not subdir.is_dir():
            continue
        for path in subdir.glob("*.writing.zip"):
            path.unlink()
        removed = trim_step_checkpoints(subdir, keep=keep, name_prefix=name_prefix)
        if removed:
            removed_by_dir[subdir.name] = removed
    return removed_by_dir
