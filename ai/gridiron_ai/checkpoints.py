"""Checkpoint zip I/O helpers (atomic long-running saves, step-checkpoint pruning).

Owner: resume zip durability + ``*_steps.zip`` retention under ``checkpoints/``.
See ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any

from gridiron_ai.paths import CHECKPOINT_DIR

STEP_CHECKPOINT_PREFIX = "maskable_ppo_selfplay"
DEFAULT_STEP_CHECKPOINT_KEEP = 50
_STEP_ZIP_RE = re.compile(
    rf"^{re.escape(STEP_CHECKPOINT_PREFIX)}_(?P<steps>\d+)_steps\.zip$"
)


def atomic_model_save(model: Any, save_stem: str | Path) -> Path:
    """Write model zip via temp file + rename so resume checkpoint never stays truncated."""
    stem = Path(save_stem)
    final_zip = stem.with_suffix(".zip")
    tmp_zip = stem.with_name(f"{stem.name}.writing.zip")
    try:
        # SB3 appends/replaces to ``.zip`` only when the path is not already ``.zip``.
        model.save(str(tmp_zip))
        if not tmp_zip.is_file():
            raise FileNotFoundError(f"model.save did not create {tmp_zip}")
        os.replace(tmp_zip, final_zip)
    except Exception:
        tmp_zip.unlink(missing_ok=True)
        raise
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
