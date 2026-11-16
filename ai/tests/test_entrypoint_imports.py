"""Import / CLI entry smokes — Python's stand-in for a compile error.

Circular imports and missing symbols fail at process start for ``./start_over.sh`` /
``./train.sh``. These tests load the same modules and ``python -m`` entrypoints those
scripts use; they do not need a live backend.
"""

from __future__ import annotations

import importlib
import subprocess
import sys
from pathlib import Path

import pytest

_AI_ROOT = Path(__file__).resolve().parents[1]
_PYTHON = sys.executable

# Modules the wipe → train handoff imports (or runs as ``-m``).
_ENTRY_MODULES = (
    "gridiron_ai.train.pipeline_state",
    "gridiron_ai.train.persistence.pipeline",
    "gridiron_ai.train.training_phases",
    "scripts.train_self_play",
    "scripts.summarize_models",
)


@pytest.mark.parametrize("module_name", _ENTRY_MODULES)
def test_entry_module_imports(module_name: str) -> None:
    """Any cycle / missing symbol here is a start_over/train boot failure."""
    importlib.import_module(module_name)


def test_pipeline_state_cli_entrypoint_loads() -> None:
    """``common.sh rewind_pipeline`` runs ``python -m gridiron_ai.train.pipeline_state``."""
    # ``-m`` loads the module as ``__main__`` *and* as ``gridiron_ai.train.pipeline_state`` —
    # that dual load is exactly what exposed the persistence ↔ pipeline_state cycle.
    proc = subprocess.run(
        [_PYTHON, "-m", "gridiron_ai.train.pipeline_state", "rewind", "--help"],
        cwd=_AI_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc.returncode == 0, proc.stderr or proc.stdout


def test_train_self_play_module_cli_loads() -> None:
    """``./train.sh`` ends in ``python -m scripts.train_self_play``."""
    proc = subprocess.run(
        [_PYTHON, "-m", "scripts.train_self_play", "--help"],
        cwd=_AI_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc.returncode == 0, proc.stderr or proc.stdout


def test_pipeline_state_reexports_io_helpers() -> None:
    """Callers use ``from gridiron_ai.train.pipeline_state import load_pipeline`` — must resolve."""
    mod = importlib.import_module("gridiron_ai.train.pipeline_state")
    assert callable(mod.load_pipeline)
    assert callable(mod.save_pipeline)
    assert callable(mod.pipeline_path)
