"""Focused tests for training_phases gate helpers + active routing."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from gridiron_ai.pipeline_state import (
    PipelineState,
    ProfileSplit,
    ProfileState,
    load_pipeline,
    save_pipeline,
)
from gridiron_ai.scenario_curriculum import (
    CLAIM_UNLOCK_BOARD_DIFFICULTY,
    MAX_DIFFICULTY,
    drill_stat_count,
    path_for_drill_stats,
)
from gridiron_ai.training_phases import (
    PHASE_PROFILES,
    PHASE_SCENARIO_A,
    PHASE_SCENARIO_B,
    TrainTarget,
    active_targets,
    advance_pipeline,
    drill_stats_lineage_keys,
    ensure_checkpoint_from_prior,
    force_promote,
    game_phase_unlocked,
    load_drill_stats_cumulative,
    phase_status_lines,
    resolve_targets,
    resolve_train_target,
    scenario_a_gate_met,
    scenario_b_gate_met,
)
from tests.catalog_fixture import BOARD_NAMES, CLAIM_NAMES, FULL_NAMES, install_fixture_catalog

install_fixture_catalog()

_BOARD = list(BOARD_NAMES)
_FULL = list(FULL_NAMES)
_STEM = "maskable_ppo_selfplay_final"
_ALL_PROFILES = ("baseline", "general", "spread", "td", "defense")


@pytest.fixture(autouse=True)
def _roots(monkeypatch, tmp_path: Path) -> tuple[Path, Path]:
    """Every test gets an empty checkpoint root — never the real ``pipeline.json``."""
    return _patch_roots(monkeypatch, tmp_path)


def _patch_roots(monkeypatch, tmp_path: Path) -> tuple[Path, Path]:
    ckpt_root = tmp_path / "checkpoints"
    runs_root = tmp_path / "runs"
    ckpt_root.mkdir(exist_ok=True)
    runs_root.mkdir(exist_ok=True)
    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.persistence.pipeline.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.RUNS_DIR", runs_root)
    return ckpt_root, runs_root


def _at_profiles(**profiles: ProfileState) -> None:
    split = ProfileSplit(at=1.0) if profiles else None
    save_pipeline(PipelineState(stage=PHASE_PROFILES, split=split, profiles=profiles))


def _plant_phase(ckpt_root: Path, runs_root: Path, key: str, levels: dict[str, int]) -> None:
    ckpt = ckpt_root / key
    ckpt.mkdir(parents=True, exist_ok=True)
    (ckpt / f"{_STEM}.zip").write_bytes(b"zip")
    (ckpt / f"{_STEM}.scenario_difficulty.json").write_text(
        json.dumps(levels), encoding="utf-8"
    )
    (ckpt / f"{_STEM}_vecnormalize.pkl").write_bytes(b"vn")
    runs = runs_root / key
    runs.mkdir(parents=True, exist_ok=True)
    (runs / "dummy.tfevents").write_text("tb", encoding="utf-8")


def test_profiles_for_all_train_omits_td() -> None:
    from gridiron_ai.reward import profiles_for_all

    assert profiles_for_all("train") == ("baseline", "general", "spread", "defense")
    assert profiles_for_all("eval") == ("baseline", "general", "spread", "defense")
    assert profiles_for_all("summary") == ("baseline", "general", "spread", "td", "defense")


def test_scenario_a_gate_needs_board_d4() -> None:
    levels = {name: CLAIM_UNLOCK_BOARD_DIFFICULTY - 1 for name in _BOARD}
    assert not scenario_a_gate_met(levels, _FULL)
    levels = {name: CLAIM_UNLOCK_BOARD_DIFFICULTY for name in _BOARD}
    assert scenario_a_gate_met(levels, _FULL)


def test_scenario_b_gate_needs_all_max() -> None:
    levels = {name: CLAIM_UNLOCK_BOARD_DIFFICULTY for name in _BOARD}
    for name in CLAIM_NAMES:
        levels[name] = MAX_DIFFICULTY
    assert not scenario_b_gate_met(levels, _FULL)
    for name in _FULL:
        levels[name] = MAX_DIFFICULTY
    assert scenario_b_gate_met(levels, _FULL)


def test_resolve_defers_shaped_profile_before_scenario_a() -> None:
    targets = resolve_targets("td")
    assert len(targets) == 1
    target = targets[0]
    assert target.phase == PHASE_SCENARIO_A
    assert target.checkpoint_key == PHASE_SCENARIO_A
    assert target.reward_profile == "baseline"
    assert target.deferral_note is not None
    assert "scenario_a" in target.deferral_note
    compat = resolve_train_target("td")
    assert not isinstance(compat, list)
    assert compat.checkpoint_key == PHASE_SCENARIO_A


def test_phase_status_lines_pre_split_and_unforked_profiles() -> None:
    lines = phase_status_lines(scenario_names=_FULL)
    assert lines[0] == "pipeline"
    assert any(line.startswith("  ▶ scenario_a") for line in lines)
    assert any("locked" in line and "scenario_b" in line for line in lines)
    assert any("locked" in line and "profiles" in line for line in lines)
    assert any(line.startswith("  cycle:") for line in lines)

    _at_profiles()
    lines = phase_status_lines(scenario_names=_FULL)
    assert any(line.startswith("  ▶ profiles") and "baseline not forked" in line for line in lines)
    assert any(line.startswith("    games") and "locked" in line for line in lines)
    assert lines[-1].startswith("  cycle: scenarios→games; full-game gated")


def test_phase_status_lines_games_row_per_profile_after_split() -> None:
    _at_profiles(
        baseline=ProfileState(stage="games"),
        general=ProfileState(),
        spread=ProfileState(),
    )
    lines = phase_status_lines(scenario_names=_FULL)
    assert any(line.startswith("  ▶ profiles") for line in lines)
    assert any(line.startswith("  ▶ games") and "baseline unlocked" in line for line in lines)
    assert lines[-1] == (
        "  cycle: games→scenarios for baseline; scenarios→games for general, spread"
    )

    _at_profiles(**{name: ProfileState(stage="games") for name in ("baseline", "general", "spread")})
    lines = phase_status_lines(scenario_names=_FULL)
    assert any(line.startswith("    profiles") for line in lines)
    assert any("baseline, general, spread unlocked" in line for line in lines)
    assert lines[-1].startswith("  cycle: games→scenarios; full-game first")


def test_active_targets_baseline_only_before_split() -> None:
    targets = active_targets()
    assert len(targets) == 1
    assert targets[0].reward_profile == "baseline"
    assert targets[0].checkpoint_key == PHASE_SCENARIO_A
    assert resolve_targets("all") == targets
    assert resolve_targets("general")[0].checkpoint_key == PHASE_SCENARIO_A


def test_active_targets_includes_baseline_after_split() -> None:
    _at_profiles()
    targets = active_targets()
    assert [t.checkpoint_key for t in targets] == ["baseline", "general", "spread"]
    assert targets[0].reward_profile == "baseline"
    assert targets[0].phase == PHASE_PROFILES
    assert all(t.phase == PHASE_PROFILES for t in targets[1:])
    assert all(t.reward_profile == t.checkpoint_key for t in targets[1:])
    assert resolve_targets("all") == targets
    summary = resolve_targets("all", purpose="summary")
    assert [t.checkpoint_key for t in summary] == list(_ALL_PROFILES)


def test_active_targets_train_all_shaped_even_when_only_some_forked() -> None:
    _at_profiles(td=ProfileState())
    targets = active_targets()
    assert [t.checkpoint_key for t in targets] == ["baseline", "general", "spread"]
    assert targets[0].reward_profile == "baseline"
    assert all(t.phase == PHASE_PROFILES for t in targets[1:])


def test_eval_only_all_uses_forked_profiles() -> None:
    _at_profiles(baseline=ProfileState(), general=ProfileState(), td=ProfileState())
    targets = resolve_targets("all", eval_only=True)
    assert [t.checkpoint_key for t in targets] == ["baseline", "general"]


def test_eval_only_all_falls_back_to_scenario_b_baseline(_roots) -> None:
    ckpt_root, runs_root = _roots
    all_max = {name: MAX_DIFFICULTY for name in _FULL}
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_B, all_max)
    _at_profiles()
    targets = resolve_targets("all", eval_only=True)
    assert len(targets) == 1
    assert targets[0].checkpoint_key == PHASE_SCENARIO_B
    assert targets[0].reward_profile == "baseline"
    assert targets[0].phase == PHASE_SCENARIO_B


def test_resolve_baseline_is_own_profile_after_split() -> None:
    _at_profiles()
    targets = resolve_targets("baseline")
    assert len(targets) == 1
    assert targets[0].phase == PHASE_PROFILES
    assert targets[0].checkpoint_key == "baseline"
    assert targets[0].reward_profile == "baseline"


def test_train_target_paths_use_checkpoint_key(monkeypatch, tmp_path: Path, _roots) -> None:
    ckpt_root, runs_root = _roots
    eval_root = tmp_path / "evals"
    eval_root.mkdir()
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", eval_root)
    levels = {name: 0 for name in _BOARD}
    levels["THROW_OPEN_RECEIVER"] = 3
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_A, levels)
    target = resolve_targets("td")[0]
    assert target.checkpoint_dir() == ckpt_root / PHASE_SCENARIO_A
    assert target.zip_path() == ckpt_root / PHASE_SCENARIO_A / f"{_STEM}.zip"
    assert target.eval_dir() == eval_root / "baseline"
    assert target.load_levels()["THROW_OPEN_RECEIVER"] == 3
    assert target.has_artifacts()


def test_saved_stage_survives_demotion(_roots) -> None:
    ckpt_root, runs_root = _roots
    demoted = {name: MAX_DIFFICULTY for name in _FULL}
    demoted["TD_RUN"] = MAX_DIFFICULTY - 1
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_B, demoted)
    _at_profiles()
    assert game_phase_unlocked(PHASE_SCENARIO_B)
    targets = active_targets()
    assert [t.checkpoint_key for t in targets] == ["baseline", "general", "spread"]


def test_drill_stats_lineage_keys() -> None:
    assert drill_stats_lineage_keys(PHASE_SCENARIO_A) == []
    assert drill_stats_lineage_keys(PHASE_SCENARIO_B) == [PHASE_SCENARIO_A]
    assert drill_stats_lineage_keys("baseline") == [PHASE_SCENARIO_A, PHASE_SCENARIO_B]
    assert drill_stats_lineage_keys("general") == [PHASE_SCENARIO_A, PHASE_SCENARIO_B]


def test_load_drill_stats_cumulative_sums_prior_phases(_roots) -> None:
    ckpt_root, _runs_root = _roots
    a_stats = ckpt_root / PHASE_SCENARIO_A / f"{_STEM}.scenario_drill_stats.json"
    b_stats = ckpt_root / PHASE_SCENARIO_B / f"{_STEM}.scenario_drill_stats.json"
    a_stats.parent.mkdir(parents=True, exist_ok=True)
    b_stats.parent.mkdir(parents=True, exist_ok=True)
    a_stats.write_text(json.dumps({"TD_RUN": {"cycles": 12, "d5_demotes": 0}}), encoding="utf-8")
    b_stats.write_text(json.dumps({"TD_RUN": {"cycles": 3, "d5_demotes": 1}}), encoding="utf-8")
    merged = load_drill_stats_cumulative(PHASE_SCENARIO_B)
    assert drill_stat_count(merged, "TD_RUN", "cycles") == 15
    assert drill_stat_count(merged, "TD_RUN", "d5_demotes") == 1


def test_advance_pipeline_moves_stage_on_live_gate(_roots) -> None:
    ckpt_root, runs_root = _roots
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_A, {name: CLAIM_UNLOCK_BOARD_DIFFICULTY for name in _BOARD})
    target = TrainTarget(phase=PHASE_SCENARIO_A, checkpoint_key=PHASE_SCENARIO_A, reward_profile="baseline")
    assert advance_pipeline(target) == "pipeline: scenario_a gate passed → scenario_b"
    assert load_pipeline().stage == PHASE_SCENARIO_B
    assert advance_pipeline(target) is None
    assert resolve_targets("all")[0].checkpoint_key == PHASE_SCENARIO_B


def test_d5_demotes_alone_do_not_advance(_roots) -> None:
    ckpt_root, runs_root = _roots
    demoted = {name: MAX_DIFFICULTY - 1 for name in _FULL}
    demoted["TD_RUN"] = MAX_DIFFICULTY
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_B, demoted)
    stats_path = path_for_drill_stats(ckpt_root / PHASE_SCENARIO_B / _STEM)
    stats_path.write_text(json.dumps({"TD_RUN": {"cycles": 3, "d5_demotes": 1}}), encoding="utf-8")
    save_pipeline(PipelineState(stage=PHASE_SCENARIO_B))
    target = TrainTarget(phase=PHASE_SCENARIO_B, checkpoint_key=PHASE_SCENARIO_B, reward_profile="baseline")
    assert advance_pipeline(target) is None
    assert load_pipeline().stage == PHASE_SCENARIO_B


def test_advance_pipeline_profile_climbing_to_games_is_sticky(_roots) -> None:
    ckpt_root, runs_root = _roots
    all_max = {name: MAX_DIFFICULTY for name in _FULL}
    _plant_phase(ckpt_root, runs_root, "baseline", all_max)
    _at_profiles(baseline=ProfileState())
    target = resolve_targets("baseline")[0]
    assert not game_phase_unlocked("baseline")
    assert advance_pipeline(target) == "pipeline: baseline climbing → games (gating drills d5)"
    assert game_phase_unlocked("baseline")
    demoted = {name: MAX_DIFFICULTY - 1 for name in _FULL}
    _plant_phase(ckpt_root, runs_root, "baseline", demoted)
    assert advance_pipeline(target) is None
    assert game_phase_unlocked("baseline")


def test_force_promote_scenario_b_without_live_gate(_roots) -> None:
    ckpt_root, runs_root = _roots
    below = {name: MAX_DIFFICULTY - 1 for name in _FULL}
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_B, below)
    save_pipeline(PipelineState(stage=PHASE_SCENARIO_B))
    assert force_promote() == [PHASE_SCENARIO_B]
    assert load_pipeline().stage == PHASE_PROFILES
    assert game_phase_unlocked(PHASE_SCENARIO_B)


def test_force_promote_fails_without_checkpoint() -> None:
    with pytest.raises(SystemExit, match="missing checkpoint"):
        force_promote()


def test_force_promote_profiles_forks_unforked(_roots) -> None:
    ckpt_root, runs_root = _roots
    all_max = {name: MAX_DIFFICULTY for name in _FULL}
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_B, all_max)
    _at_profiles()
    assert force_promote() == [f"profiles/{name}" for name in _ALL_PROFILES]
    assert (ckpt_root / "baseline" / f"{_STEM}.zip").is_file()
    state = load_pipeline()
    assert state.split is not None
    assert state.profiles == {name: ProfileState() for name in _ALL_PROFILES}


def test_ensure_checkpoint_from_prior_records_profile_fork(_roots) -> None:
    ckpt_root, runs_root = _roots
    all_max = {name: MAX_DIFFICULTY for name in _FULL}
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_B, all_max)
    (ckpt_root / PHASE_SCENARIO_B / "maskable_ppo_selfplay_50000_steps.zip").write_bytes(b"zip")
    _at_profiles()
    ensure_checkpoint_from_prior("general", PHASE_PROFILES)
    state = load_pipeline()
    assert state.split is not None
    assert state.split.scenario_b_steps == 50_000
    assert state.profiles == {"general": ProfileState()}
    assert not (ckpt_root / "general" / f"{_STEM}.scenario_difficulty.json").is_file()
    ensure_checkpoint_from_prior("spread", PHASE_PROFILES)
    assert load_pipeline().split == state.split


def test_ensure_checkpoint_from_prior_refuses_untracked_profile_zip(_roots) -> None:
    ckpt_root, runs_root = _roots
    all_max = {name: MAX_DIFFICULTY for name in _FULL}
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_B, all_max)
    _plant_phase(ckpt_root, runs_root, "general", all_max)
    _at_profiles()
    with pytest.raises(SystemExit, match="no fork for general"):
        ensure_checkpoint_from_prior("general", PHASE_PROFILES)


def test_force_promote_moves_climbing_profiles_to_games(_roots) -> None:
    ckpt_root, runs_root = _roots
    _plant_phase(ckpt_root, runs_root, PHASE_SCENARIO_B, {name: MAX_DIFFICULTY for name in _FULL})
    _at_profiles(**{name: ProfileState() for name in _ALL_PROFILES})
    assert force_promote() == [f"game_phase/{name}" for name in _ALL_PROFILES]
    assert load_pipeline().profiles["baseline"] == ProfileState(stage="games", forced=True)
    assert game_phase_unlocked("baseline")
    with pytest.raises(SystemExit, match="every profile plays games"):
        force_promote()
