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

from gridiron_ai.paths import CHECKPOINT_DIR, PICKLE_ALIASES_PATH

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


def atomic_model_save(model: Any, save_stem: str | Path) -> Path:
    """Write model zip via temp file + rename so resume checkpoint never stays truncated.

    # DEVIATION: SB3 excludes ``env`` from the zip dict, but a schedule lambda that closed
    # over ``model`` (or ``self``) still pickles the live SubprocVecEnv → ``_thread.lock``.
    # Ctrl-C mid-``remote.recv()`` hits that path. Detach env + rebind schedules to floats
    # for the save only, then restore.
    """
    stem = Path(save_stem)
    final_zip = stem.with_suffix(".zip")
    tmp_zip = stem.with_name(f"{stem.name}.writing.zip")
    held: dict[str, Any] = {}
    for name in ("env", "_vec_normalize_env"):
        if hasattr(model, name):
            held[name] = getattr(model, name)
            setattr(model, name, None)
    for name, fallback in (
        ("learning_rate", 3e-4),
        ("lr_schedule", 3e-4),
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
            setattr(model, name, value)
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
