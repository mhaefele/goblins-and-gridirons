"""``checkpoints/pipeline.json`` — saved training pipeline FSM."""

from __future__ import annotations

import json
import os
from dataclasses import asdict
from pathlib import Path

from gridiron_ai.paths import CHECKPOINT_DIR
from gridiron_ai.pipeline_state import PipelineState, ProfileSplit, ProfileState


def pipeline_path() -> Path:
    return CHECKPOINT_DIR / "pipeline.json"


def load_pipeline() -> PipelineState:
    """Saved state; a missing file is a fresh pipeline (``scenario_a``, nothing forked)."""
    path = pipeline_path()
    if not path.is_file():
        return PipelineState()
    payload = json.loads(path.read_text(encoding="utf-8"))
    split = payload.get("split")
    return PipelineState(
        stage=payload["stage"],
        split=ProfileSplit(**split) if split is not None else None,
        profiles={key: ProfileState(**p) for key, p in payload.get("profiles", {}).items()},
    )


def save_pipeline(state: PipelineState) -> None:
    """Atomic write — train and summary processes may read concurrently."""
    path = pipeline_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(asdict(state), indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)
