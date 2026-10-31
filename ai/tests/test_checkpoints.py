"""Checkpoint save helpers."""

from __future__ import annotations

import sys
from pathlib import Path

from gridiron_ai import checkpoints


def test_install_legacy_pickle_aliases_noop_when_missing(tmp_path: Path) -> None:
    assert checkpoints.install_legacy_pickle_aliases(tmp_path / "missing.properties") == 0


def test_install_legacy_pickle_aliases_applies_mappings(tmp_path: Path) -> None:
    import gridiron_ai
    import gridiron_ai.legal_row_policy as modern_policy

    props = tmp_path / "aliases.properties"
    props.write_text(
        "\n".join(
            [
                "# comment",
                "legacy_pkg_test=gridiron_ai",
                "legacy_pkg_test.legal_row_policy=gridiron_ai.legal_row_policy",
                "",
            ]
        ),
        encoding="utf-8",
    )
    assert checkpoints.install_legacy_pickle_aliases(props) == 2
    assert sys.modules.get("legacy_pkg_test") is gridiron_ai
    assert sys.modules.get("legacy_pkg_test.legal_row_policy") is modern_policy


class _FakeModel:
    def __init__(self, payload: bytes = b"ok") -> None:
        self._payload = payload

    def save(self, path: str) -> None:
        zip_path = Path(path)
        if zip_path.suffix != ".zip":
            zip_path = zip_path.with_suffix(".zip")
        zip_path.write_bytes(self._payload)


def test_atomic_model_save_replaces_final(tmp_path: Path) -> None:
    stem = tmp_path / "maskable_ppo_selfplay_final"
    checkpoints.atomic_model_save(_FakeModel(b"v1"), stem)
    assert (tmp_path / "maskable_ppo_selfplay_final.zip").read_bytes() == b"v1"
    assert not (tmp_path / "maskable_ppo_selfplay_final.writing.zip").exists()

    checkpoints.atomic_model_save(_FakeModel(b"v2"), stem)
    assert (tmp_path / "maskable_ppo_selfplay_final.zip").read_bytes() == b"v2"


def test_atomic_model_save_does_not_leave_truncated_final(tmp_path: Path) -> None:
    stem = tmp_path / "maskable_ppo_selfplay_final"
    final_zip = tmp_path / "maskable_ppo_selfplay_final.zip"
    final_zip.write_bytes(b"keep-me")

    class _FailModel:
        def save(self, path: str) -> None:
            zip_path = Path(path)
            if zip_path.suffix != ".zip":
                zip_path = zip_path.with_suffix(".zip")
            zip_path.write_bytes(b"partial")
            raise RuntimeError("disk full")

    try:
        checkpoints.atomic_model_save(_FailModel(), stem)
    except RuntimeError:
        pass
    assert final_zip.read_bytes() == b"keep-me"
    assert not (tmp_path / "maskable_ppo_selfplay_final.writing.zip").exists()


def test_trim_step_checkpoints_keeps_newest_by_step(tmp_path: Path) -> None:
    prefix = checkpoints.STEP_CHECKPOINT_PREFIX
    for steps in (100, 200, 300, 400, 500):
        (tmp_path / f"{prefix}_{steps}_steps.zip").write_bytes(b"x")
    removed = checkpoints.trim_step_checkpoints(tmp_path, keep=2)
    assert removed == 3
    remaining = sorted(p.name for p in tmp_path.glob(f"{prefix}_*_steps.zip"))
    assert remaining == [f"{prefix}_400_steps.zip", f"{prefix}_500_steps.zip"]


def test_prepare_checkpoint_dirs_prunes_and_clears_writing(
    monkeypatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(checkpoints, "CHECKPOINT_DIR", tmp_path)
    prefix = checkpoints.STEP_CHECKPOINT_PREFIX
    general = tmp_path / "general"
    general.mkdir()
    for steps in range(10):
        (general / f"{prefix}_{steps}_steps.zip").write_bytes(b"x")
    (general / "maskable_ppo_selfplay_final.writing.zip").write_bytes(b"stale")

    removed_by_dir = checkpoints.prepare_checkpoint_dirs(keep=3)
    assert removed_by_dir == {"general": 7}
    assert not (general / "maskable_ppo_selfplay_final.writing.zip").exists()
    assert len(list(general.glob(f"{prefix}_*_steps.zip"))) == 3
