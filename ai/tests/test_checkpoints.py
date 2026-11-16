"""Checkpoint save helpers."""

from __future__ import annotations

import sys
from pathlib import Path

from gridiron_ai.train import checkpoints


def test_install_legacy_pickle_aliases_noop_when_missing(tmp_path: Path) -> None:
    assert checkpoints.install_legacy_pickle_aliases(tmp_path / "missing.properties") == 0


def test_install_legacy_pickle_aliases_applies_mappings(tmp_path: Path) -> None:
    import gridiron_ai
    import gridiron_ai.model.legal_row_policy as modern_policy

    props = tmp_path / "aliases.properties"
    props.write_text(
        "\n".join(
            [
                "# comment",
                "legacy_pkg_test=gridiron_ai",
                "legacy_pkg_test.legal_row_policy=gridiron_ai.model.legal_row_policy",
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


def test_atomic_model_save_survives_env_lock_via_schedule_closure(tmp_path: Path) -> None:
    """Ctrl-C mid-SubprocVecEnv: schedule lambda closing over model must not poison save."""
    import threading

    import cloudpickle
    from stable_baselines3.common.utils import FloatSchedule

    class _LockyModel:
        def __init__(self) -> None:
            self.env = type("E", (), {"lock": threading.Lock()})()
            self.learning_rate = 1e-3
            self.lr_schedule = FloatSchedule(lambda _p: self.learning_rate)
            self.clip_range = FloatSchedule(lambda _p: 0.2)
            self.clip_range_vf = None
            self._vec_normalize_env = self.env
            self._payload = b"saved"

        def save(self, path: str) -> None:
            # Mimic SB3: schedules are in the pickled ``data`` blob.
            cloudpickle.dumps(
                {
                    "lr_schedule": self.lr_schedule,
                    "clip_range": self.clip_range,
                    "learning_rate": self.learning_rate,
                }
            )
            zip_path = Path(path)
            if zip_path.suffix != ".zip":
                zip_path = zip_path.with_suffix(".zip")
            zip_path.write_bytes(self._payload)

    model = _LockyModel()
    stem = tmp_path / "maskable_ppo_selfplay_final"
    checkpoints.atomic_model_save(model, stem)
    assert (tmp_path / "maskable_ppo_selfplay_final.zip").read_bytes() == b"saved"
    assert model.env is not None
    assert callable(model.lr_schedule)


def test_atomic_model_save_strips_verbose_train_hook_closure(tmp_path: Path) -> None:
    """``--verbose`` evidence wraps ``model.train`` over ``model`` → env locks."""
    import threading

    import cloudpickle

    class _HookyModel:
        def __init__(self) -> None:
            self.env = type("E", (), {"lock": threading.Lock()})()
            self.learning_rate = 1e-3
            self.lr_schedule = 1e-3
            self.clip_range = 0.2
            self.clip_range_vf = None
            self._payload = b"saved"

            def train_with_evidence() -> None:
                _ = self.env.lock

            self.train = train_with_evidence
            self.__dict__["_gridiron_evidence_train_hook"] = True
            # Surprise attr SB3 would pickle into data= (not in exclude list).
            self._mystery_lock_holder = {"env": self.env}

        def _excluded_save_params(self) -> list[str]:
            return [
                "policy",
                "device",
                "env",
                "replay_buffer",
                "rollout_buffer",
                "_vec_normalize_env",
                "_logger",
                "_custom_logger",
            ]

        def _get_torch_save_params(self) -> tuple[list[str], list[str]]:
            return [], []

        def save(self, path: str) -> None:
            exclude = set(self._excluded_save_params())
            for key, value in self.__dict__.items():
                if key in exclude:
                    continue
                cloudpickle.dumps(value)
            zip_path = Path(path)
            if zip_path.suffix != ".zip":
                zip_path = zip_path.with_suffix(".zip")
            zip_path.write_bytes(self._payload)

    model = _HookyModel()
    stem = tmp_path / "maskable_ppo_selfplay_final"
    checkpoints.atomic_model_save(model, stem)
    assert (tmp_path / "maskable_ppo_selfplay_final.zip").read_bytes() == b"saved"
    assert "train" in model.__dict__
    assert model._mystery_lock_holder is not None


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
