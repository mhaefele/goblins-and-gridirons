"""Clone in-progress epoch zip + progress sidecar helpers."""

from __future__ import annotations

from pathlib import Path

import gridiron_ai.expert_clone.clone as clone
from gridiron_ai.expert_clone.clone import (
    clone_epoch_zip_path,
    clone_progress_json_path,
    find_clone_epoch_zip,
)


def test_clone_progress_paths(tmp_path: Path) -> None:
    final_zip = tmp_path / "maskable_ppo_seed.zip"
    assert clone_progress_json_path(final_zip).name == "maskable_ppo_seed.clone_progress.json"
    assert clone_epoch_zip_path(final_zip, 7).name == "maskable_ppo_seed.clone_epoch_7.zip"


def test_find_and_clear_epoch_zips(tmp_path: Path) -> None:
    final_zip = tmp_path / "maskable_ppo_seed.zip"
    older = clone_epoch_zip_path(final_zip, 3)
    newer = clone_epoch_zip_path(final_zip, 5)
    older.write_bytes(b"old")
    newer.write_bytes(b"new")
    clone_progress_json_path(final_zip).write_text('{"completed_epochs": 5}\n', encoding="utf-8")
    assert find_clone_epoch_zip(final_zip, 5) == newer
    assert find_clone_epoch_zip(final_zip) == newer
    assert find_clone_epoch_zip(final_zip, 3) == older
    clone._clear_clone_progress(final_zip)
    assert find_clone_epoch_zip(final_zip) is None
    assert not clone_progress_json_path(final_zip).is_file()


def test_save_clone_epoch_checkpoint_keeps_only_latest(tmp_path: Path, monkeypatch) -> None:
    final_zip = tmp_path / "maskable_ppo_seed.zip"
    saved: list[Path] = []

    def _fake_atomic(model, save_stem):  # noqa: ANN001
        # Mirror checkpoints.atomic_model_save naming (stem must not rely on dotted suffixes).
        stem = Path(save_stem)
        out = Path(str(stem) + ".zip") if stem.suffix == "" else stem.with_suffix(".zip")
        out.write_bytes(b"weights")
        saved.append(out)
        return out

    monkeypatch.setattr(clone, "atomic_model_save", _fake_atomic)
    clone._save_clone_epoch_checkpoint(
        object(),  # type: ignore[arg-type]
        final_zip,
        completed_epochs=1,
        best_mean=3.0,
        stagnant=0,
        last_mean=3.0,
        seed=0,
        n_steps=100,
    )
    clone._save_clone_epoch_checkpoint(
        object(),  # type: ignore[arg-type]
        final_zip,
        completed_epochs=2,
        best_mean=2.5,
        stagnant=0,
        last_mean=2.5,
        seed=0,
        n_steps=100,
    )
    assert find_clone_epoch_zip(final_zip, 1) is None
    assert find_clone_epoch_zip(final_zip, 2) is not None
    progress = clone._read_clone_progress(final_zip)
    assert progress is not None
    assert progress["completed_epochs"] == 2
    assert progress["best_mean"] == 2.5
    assert len(saved) == 2
