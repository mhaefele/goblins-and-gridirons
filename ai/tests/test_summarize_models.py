"""summarize_models routes curriculum via resolve_targets."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from gridiron_ai.pipeline_state import PipelineState, ProfileSplit, save_pipeline
from gridiron_ai.scenario_curriculum import (
    CLAIM_UNLOCK_BOARD_DIFFICULTY,
    GATE_PROGRESS_WAIVE_FAILURES,
    MAX_DIFFICULTY,
    path_for_gate_failures,
    record_gate_failure,
)
from gridiron_ai.api.scenarios import ScenarioCatalog, ScenarioInfo, set_catalog
from gridiron_ai.training_phases import PHASE_SCENARIO_A, PHASE_SCENARIO_B, TrainTarget, resolve_targets
from scripts.summarize_models import (
    _approx_kl_level,
    _bottom_summary_rows,
    _clip_fraction_level,
    _compact_column_guide,
    _curriculum_note,
    _explained_variance_level,
    _fmt_compact_board,
    _fmt_lookahead_trend,
    _fmt_model_config_table,
    _fmt_profiles_bottom_summary,
    _fmt_train_health_table,
    _latest_scenario_eval,
    _policy_entropy_level,
    _scalar_from_summary_value,
    _scenario_eval_difficulty,
    _summarize_target,
    _tb_trend_with_qualifier,
    _value_loss_level,
    _winrate_arrow,
)
from tests.catalog_fixture import BOARD_NAMES, FULL_NAMES, install_fixture_catalog

install_fixture_catalog()

_STEM = "maskable_ppo_selfplay_final"
_BOARD = list(BOARD_NAMES)


@pytest.fixture(autouse=True)
def _pipeline_root(monkeypatch, tmp_path: Path) -> None:
    """Saved pipeline lives beside each test's planted checkpoints — never the real one."""
    monkeypatch.setattr("gridiron_ai.persistence.pipeline.CHECKPOINT_DIR", tmp_path / "checkpoints")


def _plant(ckpt_root: Path, key: str, levels: dict[str, int], *, steps: int | None = None) -> None:
    ckpt = ckpt_root / key
    ckpt.mkdir(parents=True, exist_ok=True)
    (ckpt / f"{_STEM}.zip").write_bytes(b"zip")
    (ckpt / f"{_STEM}.scenario_difficulty.json").write_text(json.dumps(levels), encoding="utf-8")
    if steps is not None:
        (ckpt / f"maskable_ppo_selfplay_{steps}_steps.zip").write_bytes(b"zip")


def test_baseline_summary_reads_scenario_a_curriculum(monkeypatch, tmp_path: Path) -> None:
    ckpt_root = tmp_path / "checkpoints"
    eval_root = tmp_path / "evals"
    runs_root = tmp_path / "runs"
    ckpt_root.mkdir()
    eval_root.mkdir()
    runs_root.mkdir()

    stale = {name: 0 for name in _BOARD}
    stale["THROW_TO_ENDZONE"] = 5
    _plant(ckpt_root, "baseline", stale, steps=2_100_000)

    live = {name: CLAIM_UNLOCK_BOARD_DIFFICULTY for name in _BOARD}
    live["THROW_OPEN_RECEIVER"] = 3
    live["THROW_CLOSER_RANGE"] = 4
    _plant(ckpt_root, PHASE_SCENARIO_A, live, steps=50_000)

    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.RUNS_DIR", runs_root)
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", eval_root)
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", eval_root)

    targets = resolve_targets("baseline")
    assert len(targets) == 1
    assert targets[0].checkpoint_key == PHASE_SCENARIO_A

    text = _summarize_target(targets[0], trend_steps=100_000)
    assert "[scenario_a]" in text
    assert "throw_open_receiver  d3  " in text
    assert "throw_closer_range   d4  " in text
    assert "steps=50k" in text
    assert "steps=2.1M" not in text
    assert "claim_grab_it" in text and "— (deferred)" in text
    board_block, claim_block = text.split("scenario drills (curriculum d · latest eval @d; --scenario N or name):\n", 1)[1].split(
        "\n\n", 1
    )
    assert "throw_open_receiver" in board_block
    assert "claim_grab_it" in claim_block
    assert "scenario_difficulty=" not in text


def test_summary_marks_optional_and_waived_gate_exclusions(monkeypatch, tmp_path: Path) -> None:
    catalog = ScenarioCatalog.from_entries(
        [
            ScenarioInfo("TD_RUN", "BOARD"),
            ScenarioInfo("HELD_SS_REROLL", "BOARD", gates_progress=False),
            ScenarioInfo("CLAIM_GRAB_IT", "CLAIM"),
        ]
    )
    set_catalog(catalog)

    ckpt_root = tmp_path / "checkpoints"
    eval_root = tmp_path / "evals"
    runs_root = tmp_path / "runs"
    ckpt_root.mkdir()
    eval_root.mkdir()
    runs_root.mkdir()

    levels = {"TD_RUN": 2, "HELD_SS_REROLL": 1, "CLAIM_GRAB_IT": 2}
    _plant(ckpt_root, PHASE_SCENARIO_A, levels, steps=10_000)
    failures_path = path_for_gate_failures(ckpt_root / PHASE_SCENARIO_A / _STEM)
    failures_path.write_text(
        json.dumps(
            {
                "TD_RUN": {"2": GATE_PROGRESS_WAIVE_FAILURES},
                "CLAIM_GRAB_IT": {"2": GATE_PROGRESS_WAIVE_FAILURES},
            }
        ),
        encoding="utf-8",
    )

    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.RUNS_DIR", runs_root)
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", eval_root)

    # Direct target — avoid phase routing promoting scenario_a when the only gating
    # claim is waived (boards never waive even past the fail threshold).
    target = TrainTarget(
        phase=PHASE_SCENARIO_A,
        checkpoint_key=PHASE_SCENARIO_A,
        reward_profile="baseline",
    )
    try:
        text = _summarize_target(target, trend_steps=100_000)
    finally:
        install_fixture_catalog()

    assert "held_ss_reroll" in text and "(optional)" in text
    assert "td_run" in text and "(waived)" not in text.split("td_run", 1)[1].split("\n", 1)[0]
    assert "claim_grab_it" in text and "(waived)" in text
    assert "gate_fails=" in text
    drill_section = text.split("scenario drills (curriculum d · latest eval @d; --scenario N or name):\n", 1)[1]
    board_lines = drill_section.split("\n\n", 1)[0].splitlines()
    board_names = [line.split()[1] for line in board_lines if line.startswith("    ")]
    assert board_names.index("td_run") < board_names.index("held_ss_reroll")


def test_curriculum_note_combined_excludes_optional_from_counts() -> None:
    catalog = ScenarioCatalog.from_entries(
        [
            ScenarioInfo("TD_RUN", "BOARD"),
            ScenarioInfo("MARKED_RUN", "BOARD"),
            ScenarioInfo("HELD_SS_REROLL", "BOARD", gates_progress=False),
            ScenarioInfo("CLAIM_GRAB_IT", "CLAIM"),
        ]
    )
    set_catalog(catalog)
    try:
        levels = {
            "TD_RUN": MAX_DIFFICULTY,
            "MARKED_RUN": MAX_DIFFICULTY,
            "HELD_SS_REROLL": MAX_DIFFICULTY,
            "CLAIM_GRAB_IT": 2,
        }
        note = _curriculum_note(levels, game_phase=False)
    finally:
        install_fixture_catalog()

    assert "2/3 at" in note
    assert "3/4" not in note


def test_curriculum_note_board_only_excludes_optional() -> None:
    catalog = ScenarioCatalog.from_entries(
        [
            ScenarioInfo("TD_RUN", "BOARD"),
            ScenarioInfo("MARKED_RUN", "BOARD"),
            ScenarioInfo("HELD_SS_REROLL", "BOARD", gates_progress=False),
            ScenarioInfo("CLAIM_GRAB_IT", "CLAIM"),
        ]
    )
    set_catalog(catalog)
    try:
        levels = {
            "TD_RUN": CLAIM_UNLOCK_BOARD_DIFFICULTY,
            "MARKED_RUN": 2,
            "HELD_SS_REROLL": CLAIM_UNLOCK_BOARD_DIFFICULTY,
        }
        note = _curriculum_note(levels, game_phase=False)
    finally:
        install_fixture_catalog()

    assert "board-only 1/2 at" in note
    assert "1/3" not in note


def test_latest_scenario_eval_returns_newest_by_mtime(tmp_path: Path) -> None:
    eval_dir = tmp_path / "evals"
    eval_dir.mkdir()
    older = eval_dir / "eval_vs-lookahead-scenario-throw_open_receiver-d3_20260101_000000.json"
    newer = eval_dir / "eval_vs-lookahead-scenario-throw_open_receiver-d2_20260102_000000.json"
    older.write_text(
        json.dumps({"winrate": 1.0, "wins": 10, "episodes": 10, "scenario_difficulty": 3}),
        encoding="utf-8",
    )
    newer.write_text(
        json.dumps({"winrate": 0.2, "wins": 2, "episodes": 10, "scenario_difficulty": 2}),
        encoding="utf-8",
    )
    path, stats = _latest_scenario_eval(eval_dir, "THROW_OPEN_RECEIVER")
    assert path == newer
    assert stats is not None and stats["wins"] == 2
    assert _scenario_eval_difficulty(path, stats) == 2


def test_scenario_drill_line_shows_wld(monkeypatch, tmp_path: Path) -> None:
    ckpt_root = tmp_path / "checkpoints"
    eval_root = tmp_path / "evals" / "baseline"
    runs_root = tmp_path / "runs"
    eval_root.mkdir(parents=True)
    runs_root.mkdir()
    levels = {name: 0 for name in _BOARD}
    _plant(ckpt_root, PHASE_SCENARIO_A, levels, steps=10_000)

    eval_root.joinpath("eval_vs-lookahead-scenario-td_run-d0_20260101_000000.json").write_text(
        json.dumps(
            {
                "winrate": 0.5,
                "wins": 5,
                "losses": 3,
                "draws": 2,
                "episodes": 10,
                "scenario_difficulty": 0,
                "opening_expected_p_mean": 0.012,
                "opening_expected_p_best_mean": 0.018,
                "opening_chosen_p_mean": 0.14,
                "opening_expected_rank_best_mean": 22.5,
            }
        ),
        encoding="utf-8",
    )

    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.RUNS_DIR", runs_root)
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", eval_root.parent)
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", eval_root.parent)

    target = TrainTarget(
        phase=PHASE_SCENARIO_A,
        checkpoint_key=PHASE_SCENARIO_A,
        reward_profile="baseline",
    )
    text = _summarize_target(target, trend_steps=100_000)
    assert "td_run" in text
    assert "W5/L3/D2 (10 ep)" in text
    assert "expected_p=0.012" in text
    assert "expected_best_p=0.018" in text
    assert "first_p=0.140" in text
    assert "expected_best_rank=22.5" in text


def test_profiles_bottom_summary_aligns_columns(monkeypatch, tmp_path: Path) -> None:
    ckpt_root = tmp_path / "checkpoints"
    eval_baseline = tmp_path / "evals" / "baseline"
    eval_general = tmp_path / "evals" / "general"
    runs_root = tmp_path / "runs"
    eval_baseline.mkdir(parents=True)
    eval_general.mkdir(parents=True)
    runs_root.mkdir()

    levels = {name: MAX_DIFFICULTY for name in FULL_NAMES}
    _plant(ckpt_root, PHASE_SCENARIO_B, levels, steps=10_000)
    _plant(ckpt_root, "baseline", levels, steps=10_000)
    _plant(ckpt_root, "general", levels, steps=20_000)
    split_at = (ckpt_root / "general" / f"{_STEM}.zip").stat().st_mtime
    pre_split = split_at - 3600
    post_split = split_at + 3600

    eval_payload = {
        "winrate": 0.6,
        "wins": 18,
        "losses": 10,
        "draws": 2,
        "episodes": 30,
        "score_us": 0,
        "score_them": 0,
        "score_diff": 0,
        "ball_rate_us": 0,
        "ball_rate_them": 0,
        "ball_opp_half_rate_us": 0,
        "ball_proximity_us": 0,
        "turns": 0,
        "total_actions": 0,
    }
    for eval_dir, prefix, wins, losses, draws, winrate, when in (
        (eval_baseline, "eval_vs-lookahead_", 8, 20, 2, 0.27, pre_split),
        (eval_baseline, "eval_vs-heuristic_", 9, 19, 2, 0.30, pre_split),
        (eval_baseline, "eval_vs-lookahead_", 18, 10, 2, 0.6, post_split),
        (eval_baseline, "eval_vs-heuristic_", 18, 10, 2, 0.6, post_split),
        (eval_baseline, "eval_vs-onnx-general_", 15, 12, 3, 0.5, post_split),
        (eval_baseline, "eval_vs-onnx-defense_", 10, 18, 2, 0.33, post_split),
        (eval_general, "eval_vs-lookahead_", 18, 10, 2, 0.6, post_split),
        (eval_general, "eval_vs-heuristic_", 12, 16, 2, 0.4, post_split),
        (eval_general, "eval_vs-onnx-general_", 14, 14, 2, 0.47, post_split),
        (eval_general, "eval_vs-onnx-defense_", 11, 17, 2, 0.37, post_split),
    ):
        path = eval_dir / f"{prefix}20260101_{int(when)}.json"
        payload = dict(eval_payload)
        payload.update({"winrate": winrate, "wins": wins, "losses": losses, "draws": draws})
        path.write_text(json.dumps(payload), encoding="utf-8")
        os.utime(path, (when, when))

    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.RUNS_DIR", runs_root)
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", tmp_path / "evals")
    save_pipeline(PipelineState(stage="profiles", split=ProfileSplit(at=split_at)))

    from gridiron_ai.training_phases import PHASE_PROFILES

    targets = [
        TrainTarget(phase=PHASE_PROFILES, checkpoint_key="baseline", reward_profile="baseline"),
        TrainTarget(phase=PHASE_PROFILES, checkpoint_key="general", reward_profile="general"),
    ]
    rows = _bottom_summary_rows(targets)
    assert [row[0] for row in rows] == [
        "export",
        "scenario_a",
        "scenario_b",
        "baseline",
        "general",
    ]
    dash_tail = ("—", "—", "—", "—", "—", "—")
    assert rows[0][1:] == dash_tail
    assert rows[1][1:] == dash_tail
    assert rows[2][1] == "W8/L20/D2 (27%)"
    assert rows[3][1] == "W18/L10/D2 (60%↑)"
    assert rows[3][2] == "W18/L10/D2 (60%↑)"
    assert rows[3][3] == "W15/L12/D3 (50%)"
    assert rows[3][4] == "W10/L18/D2 (33%)"
    assert rows[4][3] == "W14/L14/D2 (47%)"
    assert rows[4][4] == "W11/L17/D2 (37%)"
    assert rows[2][6] == "10k"
    assert rows[3][6] == "10k"
    assert rows[4][6] == "20k"

    text = _fmt_profiles_bottom_summary(targets)
    assert text.startswith("summary\n")
    header = next(line for line in text.splitlines() if "profile" in line and "lookahead" in line)
    assert "heuristic" in header
    assert "general" in header
    assert "defense" in header
    data_lines = [
        line
        for line in text.splitlines()
        if line.startswith("  ") and not line.strip().startswith("profile")
    ]
    assert len(data_lines) == 5
    assert data_lines[0].startswith("  export")
    assert data_lines[1].startswith("  scenario_a")
    assert data_lines[2].startswith("  scenario_b")
    assert data_lines[3].startswith("  baseline")
    assert data_lines[4].startswith("  general")
    lookahead_starts = [line.index("W") for line in data_lines if "W" in line]
    assert len(set(lookahead_starts)) == 1


def test_scenario_b_snapshot_ignores_rolling_final_zip_touch(monkeypatch, tmp_path: Path) -> None:
    import os
    import time

    from gridiron_ai.pipeline_state import load_pipeline
    from scripts.summarize_models import _scenario_b_pre_split_row

    ckpt_root = tmp_path / "checkpoints"
    eval_baseline = tmp_path / "evals" / "baseline"
    eval_baseline.mkdir(parents=True)

    levels = {name: MAX_DIFFICULTY for name in FULL_NAMES}
    _plant(ckpt_root, PHASE_SCENARIO_B, levels, steps=5_300_000)
    _plant(ckpt_root, "general", levels, steps=10_000_000)

    fork_at = (ckpt_root / "general" / "maskable_ppo_selfplay_10000000_steps.zip").stat().st_mtime
    pre_split = fork_at - 3600
    post_split = fork_at + 7200
    for wins, losses, when in ((0, 30, pre_split), (3, 27, post_split)):
        path = eval_baseline / f"eval_vs-lookahead_{int(when)}.json"
        path.write_text(
            json.dumps(
                {
                    "winrate": wins / 30,
                    "wins": wins,
                    "losses": losses,
                    "draws": 0,
                    "episodes": 30,
                }
            ),
            encoding="utf-8",
        )
        os.utime(path, (when, when))

    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", tmp_path / "evals")
    save_pipeline(PipelineState(stage="profiles", split=ProfileSplit(at=fork_at)))

    split_at = load_pipeline().split.at
    row_before = _scenario_b_pre_split_row(split_at)
    assert row_before is not None
    assert row_before[1] == "W0/L30/D0 (0%)"
    assert row_before[6] == "5.3M"

    time.sleep(0.01)
    later = time.time() + 10_000
    os.utime(ckpt_root / "general" / f"{_STEM}.zip", (later, later))

    row_after = _scenario_b_pre_split_row(load_pipeline().split.at)
    assert row_after == row_before


def test_train_health_table_aligns_columns(monkeypatch, tmp_path: Path) -> None:
    from gridiron_ai.training_phases import PHASE_PROFILES

    ckpt_root = tmp_path / "checkpoints"
    _plant(ckpt_root, PHASE_SCENARIO_B, {name: 0 for name in _BOARD}, steps=10_000)
    _plant(ckpt_root, "baseline", {name: 0 for name in _BOARD}, steps=10_000)
    _plant(ckpt_root, "general", {name: 0 for name in _BOARD}, steps=20_000)
    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)

    def fake_cells(checkpoint_key: str, *, trend_steps: int) -> tuple[str, str, str, str, str, str]:
        if checkpoint_key == "baseline":
            return (
                "≈1.04 nats ok",
                "entropy_loss=-1.043 (exploring more, ok)",
                "0.0022 ok",
                "0.76 ok (flat)",
                "0.18 ok (↓ better)",
                "0.042 ok (flat)",
            )
        return (
            "≈0.80 nats ok",
            "entropy_loss=-0.800 (tightening (less random), ok)",
            "0.0010 ok",
            "0.50 ok (↑ better)",
            "0.22 ok (↑ worse)",
            "0.051 watch (↑ worse)",
        )

    monkeypatch.setattr("scripts.summarize_models._train_health_cells", fake_cells)
    targets = [
        TrainTarget(phase=PHASE_PROFILES, checkpoint_key="baseline", reward_profile="baseline"),
        TrainTarget(phase=PHASE_PROFILES, checkpoint_key="general", reward_profile="general"),
    ]
    text = _fmt_train_health_table(targets, trend_steps=100_000)
    assert text.startswith("train (TensorBoard, last 100k steps)\n")
    data_lines = [
        line
        for line in text.splitlines()
        if line.startswith("  ") and not line.strip().startswith(("steps", "profile"))
    ]
    assert len(data_lines) == 2
    assert data_lines[0].split()[0] == "10k"
    assert data_lines[0].split()[1] == "baseline"
    assert "≈1.04 nats" in data_lines[0]
    assert "exploring more" in data_lines[0]
    assert "entropy_loss=-1.043 (exploring more, ok)" in data_lines[0]
    assert data_lines[1].split()[0] == "20k"
    assert data_lines[1].split()[1] == "general"
    approx_kl_starts = [line.index("0.00") for line in data_lines]
    assert len(set(approx_kl_starts)) == 1


def test_scalar_from_summary_value_reads_tensor() -> None:
    from tensorboard.compat.proto.summary_pb2 import Summary

    value = Summary.Value(tag="train/entropy_loss")
    value.tensor.float_val.append(-1.25)
    assert _scalar_from_summary_value(value) == -1.25

    simple = Summary.Value(tag="train/x", simple_value=0.5)
    assert _scalar_from_summary_value(simple) == 0.5


def test_load_tb_scalars_scans_past_episode_only_files(monkeypatch, tmp_path: Path) -> None:
    """Newest files are often gridiron_* only — keep scanning until train/* appears."""
    import time

    from tensorboard.compat.proto.event_pb2 import Event
    from tensorboard.compat.proto.summary_pb2 import Summary
    from tensorboard.summary.writer.event_file_writer import EventFileWriter

    from scripts.summarize_models import (
        _TB_SCALAR_CACHE,
        _load_tb_scalar_series,
        _train_health_cells,
    )

    runs_root = tmp_path / "runs"
    run_dir = runs_root / "spread" / "MaskablePPO_0"
    run_dir.mkdir(parents=True)

    def _write(tag_values: dict[str, float], *, step: int, wall: float) -> None:
        writer = EventFileWriter(str(run_dir))
        summary = Summary(
            value=[Summary.Value(tag=tag, simple_value=value) for tag, value in tag_values.items()]
        )
        writer.add_event(Event(wall_time=wall, step=step, summary=summary))
        writer.flush()
        writer.close()

    base = time.time()
    _write(
        {
            "train/entropy_loss": -1.1,
            "train/approx_kl": 0.004,
            "train/explained_variance": 0.55,
            "train/value_loss": 0.2,
            "train/clip_fraction": 0.1,
        },
        step=1_000_000,
        wall=base,
    )
    for i in range(40):
        _write({"gridiron_game/winrate": 0.5}, step=1_000_000 + i, wall=base + 1 + i)

    monkeypatch.setattr("gridiron_ai.training_phases.RUNS_DIR", runs_root)
    monkeypatch.setattr("scripts.summarize_models._TB_MIN_ENTROPY_POINTS", 1)
    _TB_SCALAR_CACHE.clear()

    series = _load_tb_scalar_series("spread")
    assert series["train/entropy_loss"]
    step, value = series["train/entropy_loss"][-1]
    assert step == 1_000_000
    assert value == pytest.approx(-1.1)

    policy_entropy, *_rest = _train_health_cells("spread", trend_steps=100_000)
    assert policy_entropy.startswith("≈1.10 nats")


def test_train_level_helpers() -> None:
    assert _policy_entropy_level(1.44) == "ok"
    assert _policy_entropy_level(0.3) == "bad"
    assert _approx_kl_level(0.0029) == "ok"
    assert _approx_kl_level(0.04) == "watch"
    assert _explained_variance_level(0.74) == "ok"
    assert _explained_variance_level(-0.1) == "bad"
    assert _clip_fraction_level(0.04) == "ok"
    assert _clip_fraction_level(0.25) == "bad"
    assert _value_loss_level([0.2, 0.2, 0.2, 0.4]) == "watch"
    assert _value_loss_level([0.2, 0.2, 0.2, 0.7]) == "bad"


def test_tb_trend_with_qualifier() -> None:
    assert _tb_trend_with_qualifier([0.1, 0.2, 0.3, 0.4], higher_is_better=True) == "↑ better"
    assert _tb_trend_with_qualifier([0.4, 0.3, 0.2, 0.1], higher_is_better=True) == "↓ worse"
    assert _tb_trend_with_qualifier([0.4, 0.3, 0.2, 0.1], higher_is_better=False) == "↓ better"
    assert _tb_trend_with_qualifier([0.1, 0.2, 0.3, 0.4], higher_is_better=False) == "↑ worse"
    assert _tb_trend_with_qualifier([0.2, 0.2, 0.2, 0.2], higher_is_better=True) == "flat"


def test_compact_column_guide_mentions_critic() -> None:
    text = _compact_column_guide(trend_steps=100_000)
    assert "explained_variance" in text
    assert "value_loss" in text
    assert "clip_fraction" in text
    assert "Critic quality" in text
    assert "100k" in text
    assert "summary —" not in text
    assert "lookahead" not in text.lower()
    assert "Level tags" in text
    assert "↑ better" in text
    assert "↓ good" not in text


def test_model_config_table_default_and_overrides() -> None:
    text = _fmt_model_config_table()
    assert text.startswith("config (PPO + reward shaping")
    assert "default" in text
    assert "3e-05" in text or "3e-5" in text
    assert "0.005" in text
    assert "margin + terminal only" in text
    assert "general" in text
    assert "shared ball / challenge shaping" in text
    assert "td" in text
    assert "0.8" in text
    assert "TD amp" in text
    assert "spread" in text
    assert "covering-radius" in text
    assert "defense" in text
    assert "0.009" in text
    assert "strip / dugout" in text
    data_lines = [
        line
        for line in text.splitlines()
        if line.startswith("  ") and not line.strip().startswith("profile")
    ]
    assert len(data_lines) == 5
    assert data_lines[0].split()[0] == "default"
    profile_names = {line.split()[0] for line in data_lines[1:]}
    assert profile_names == {"general", "spread", "td", "defense"}


def test_compact_board_guide_before_tables(monkeypatch, tmp_path: Path) -> None:
    ckpt_root = tmp_path / "checkpoints"
    eval_baseline = tmp_path / "evals" / "baseline"
    eval_baseline.mkdir(parents=True)
    _plant(ckpt_root, PHASE_SCENARIO_B, {name: 0 for name in _BOARD}, steps=10_000)
    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr(
        "scripts.summarize_models._train_health_cells",
        lambda checkpoint_key, *, trend_steps: ("≈1.00 nats ok", "entropy_loss=-1.000 (flat, ok)", "0.0001 ok", "0.50 ok (flat)", "0.20 ok (flat)", "0.040 ok (flat)"),
    )
    eval_payload = {"winrate": 0.5, "wins": 1, "losses": 1, "draws": 0}
    (eval_baseline / "eval_vs-lookahead_20260101.json").write_text(json.dumps(eval_payload), encoding="utf-8")
    (eval_baseline / "eval_vs-heuristic_20260101.json").write_text(json.dumps(eval_payload), encoding="utf-8")
    targets = [TrainTarget(phase=PHASE_SCENARIO_B, checkpoint_key=PHASE_SCENARIO_B, reward_profile="baseline")]
    text = _fmt_compact_board(targets, trend_steps=100_000, include_guide=True)
    assert text.startswith("Train column guide")
    assert "config (PPO + reward shaping" in text
    assert "train (TensorBoard" in text
    assert text.index("Train column guide") < text.index("config (PPO")
    assert text.index("config (PPO") < text.index("train (TensorBoard")
    assert text.index("train (TensorBoard") < text.index("\nsummary\n")


def test_compact_board_is_train_then_summary(monkeypatch, tmp_path: Path) -> None:
    ckpt_root = tmp_path / "checkpoints"
    eval_baseline = tmp_path / "evals" / "baseline"
    eval_general = tmp_path / "evals" / "general"
    runs_root = tmp_path / "runs"
    eval_baseline.mkdir(parents=True)
    eval_general.mkdir(parents=True)
    runs_root.mkdir()

    levels = {name: MAX_DIFFICULTY for name in FULL_NAMES}
    _plant(ckpt_root, PHASE_SCENARIO_B, levels, steps=10_000)
    _plant(ckpt_root, "general", levels, steps=20_000)

    eval_payload = {
        "winrate": 0.6,
        "wins": 18,
        "losses": 10,
        "draws": 2,
        "episodes": 30,
        "score_us": 0,
        "score_them": 0,
        "score_diff": 0,
        "ball_rate_us": 0,
        "ball_rate_them": 0,
        "ball_opp_half_rate_us": 0,
        "ball_proximity_us": 0,
        "turns": 0,
        "total_actions": 0,
    }
    for eval_dir, prefix in (
        (eval_baseline, "eval_vs-lookahead_"),
        (eval_baseline, "eval_vs-heuristic_"),
        (eval_general, "eval_vs-lookahead_"),
        (eval_general, "eval_vs-heuristic_"),
    ):
        (eval_dir / f"{prefix}20260101_000000.json").write_text(json.dumps(eval_payload), encoding="utf-8")

    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.RUNS_DIR", runs_root)
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr(
        "scripts.summarize_models._train_health_cells",
        lambda checkpoint_key, *, trend_steps: (
            "≈1.00 nats ok",
            "entropy_loss=-1.000 (flat, ok)",
            "0.0001 ok",
            "0.50 ok (flat)",
            "0.20 ok (flat)",
            "0.040 ok (flat)",
        ),
    )

    from gridiron_ai.training_phases import PHASE_PROFILES

    targets = [
        TrainTarget(phase=PHASE_PROFILES, checkpoint_key="baseline", reward_profile="baseline"),
        TrainTarget(phase=PHASE_PROFILES, checkpoint_key="general", reward_profile="general"),
    ]
    text = _fmt_compact_board(targets, trend_steps=100_000)
    assert text.startswith("config (PPO")
    assert "\n\nsummary\n" in text
    assert "Goblins & Gridirons model summary" not in text


def test_compact_board_includes_train_multipliers_when_drills_run(monkeypatch, tmp_path: Path) -> None:
    ckpt_root = tmp_path / "checkpoints"
    eval_baseline = tmp_path / "evals" / "baseline"
    eval_baseline.mkdir(parents=True)
    levels = {name: MAX_DIFFICULTY for name in FULL_NAMES}
    levels["TD_RUN"] = 3
    _plant(ckpt_root, PHASE_SCENARIO_B, levels, steps=10_000)
    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr(
        "scripts.summarize_models._train_health_cells",
        lambda checkpoint_key, *, trend_steps: (
            "≈1.00 nats ok",
            "entropy_loss=-1.000 (flat, ok)",
            "0.0001 ok",
            "0.50 ok (flat)",
            "0.20 ok (flat)",
            "0.040 ok (flat)",
        ),
    )
    targets = [TrainTarget(phase=PHASE_SCENARIO_B, checkpoint_key=PHASE_SCENARIO_B, reward_profile="baseline")]
    text = _fmt_compact_board(targets, trend_steps=100_000)
    assert "train multipliers (next cycle):" in text
    assert "td_run" in text
    assert "0×: all others" in text


def test_compact_board_omits_train_multipliers_when_nothing_trains(monkeypatch, tmp_path: Path) -> None:
    ckpt_root = tmp_path / "checkpoints"
    (tmp_path / "evals" / "baseline").mkdir(parents=True)
    levels = {name: MAX_DIFFICULTY for name in FULL_NAMES}
    _plant(ckpt_root, PHASE_SCENARIO_B, levels, steps=10_000)
    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr(
        "scripts.summarize_models._train_health_cells",
        lambda checkpoint_key, *, trend_steps: (
            "≈1.00 nats ok",
            "entropy_loss=-1.000 (flat, ok)",
            "0.0001 ok",
            "0.50 ok (flat)",
            "0.20 ok (flat)",
            "0.040 ok (flat)",
        ),
    )
    targets = [TrainTarget(phase=PHASE_SCENARIO_B, checkpoint_key=PHASE_SCENARIO_B, reward_profile="baseline")]
    text = _fmt_compact_board(targets, trend_steps=100_000)
    assert "train multipliers" not in text


def test_winrate_arrow() -> None:
    assert _winrate_arrow(0.20, 0.30) == "↑"
    assert _winrate_arrow(0.30, 0.20) == "↓"
    assert _winrate_arrow(0.20, 0.204) == "→"


def test_lookahead_trend_samples_winrates(monkeypatch, tmp_path: Path) -> None:
    from gridiron_ai.training_phases import PHASE_PROFILES

    ckpt_root = tmp_path / "checkpoints"
    eval_dir = tmp_path / "evals" / "baseline"
    eval_dir.mkdir(parents=True)
    _plant(ckpt_root, "baseline", {name: 0 for name in _BOARD}, steps=10_000_000)

    for steps, winrate in (
        (1_000_000, 0.10),
        (3_000_000, 0.20),
        (5_000_000, 0.25),
        (7_000_000, 0.30),
        (9_000_000, 0.35),
        (10_000_000, 0.40),
    ):
        path = eval_dir / f"eval_vs-lookahead_{steps}.json"
        path.write_text(
            json.dumps({"winrate": winrate, "wins": 1, "losses": 1, "draws": 0, "training_steps": steps}),
            encoding="utf-8",
        )

    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", tmp_path / "evals")

    targets = [
        TrainTarget(phase=PHASE_PROFILES, checkpoint_key="baseline", reward_profile="baseline"),
    ]
    text = _fmt_lookahead_trend(targets, window=10_000_000, stride=2_000_000)
    assert text.startswith("lookahead trend (last 10.0M @ 2.0M)")
    assert "baseline" in text
    # marks: 0, 2M, 4M, 6M, 8M, 10M → at-or-before: —, 10, 20, 25, 30, 40
    assert "— → 10 → 20 → 25 → 30 → 40%" in text


def test_compact_board_includes_lookahead_trend(monkeypatch, tmp_path: Path) -> None:
    from gridiron_ai.training_phases import PHASE_PROFILES

    ckpt_root = tmp_path / "checkpoints"
    eval_dir = tmp_path / "evals" / "baseline"
    eval_dir.mkdir(parents=True)
    _plant(ckpt_root, "baseline", {name: MAX_DIFFICULTY for name in FULL_NAMES}, steps=2_000_000)
    for steps, winrate in ((1_000_000, 0.2), (2_000_000, 0.3)):
        (eval_dir / f"eval_vs-lookahead_{steps}.json").write_text(
            json.dumps(
                {
                    "winrate": winrate,
                    "wins": int(30 * winrate),
                    "losses": 30 - int(30 * winrate),
                    "draws": 0,
                    "episodes": 30,
                    "training_steps": steps,
                    "score_us": 0,
                    "score_them": 0,
                    "score_diff": 0,
                    "ball_rate_us": 0,
                    "ball_rate_them": 0,
                    "ball_opp_half_rate_us": 0,
                    "ball_proximity_us": 0,
                    "turns": 0,
                    "total_actions": 0,
                }
            ),
            encoding="utf-8",
        )
    (eval_dir / "eval_vs-heuristic_1.json").write_text(
        json.dumps({"winrate": 0.3, "wins": 9, "losses": 21, "draws": 0, "training_steps": 2_000_000}),
        encoding="utf-8",
    )

    monkeypatch.setattr("gridiron_ai.training_phases.CHECKPOINT_DIR", ckpt_root)
    monkeypatch.setattr("gridiron_ai.training_phases.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr("scripts.summarize_models.EVAL_DIR", tmp_path / "evals")
    monkeypatch.setattr(
        "scripts.summarize_models._train_health_cells",
        lambda checkpoint_key, *, trend_steps: (
            "≈1.00 nats ok",
            "entropy_loss=-1.000 (flat, ok)",
            "0.0001 ok",
            "0.50 ok (flat)",
            "0.20 ok (flat)",
            "0.040 ok (flat)",
        ),
    )

    targets = [
        TrainTarget(phase=PHASE_PROFILES, checkpoint_key="baseline", reward_profile="baseline"),
    ]
    text = _fmt_compact_board(targets, trend_steps=100_000)
    assert "\nsummary\n" in text
    assert "lookahead trend (last 10.0M @ 1.0M)" in text
    assert text.index("\nsummary\n") < text.index("lookahead trend")
    assert "30%↑" in text


def test_active_targets_includes_baseline_in_bottom_summary() -> None:
    save_pipeline(PipelineState(stage="profiles"))
    targets = resolve_targets("all", purpose="summary")
    assert targets[0].reward_profile == "baseline"
    assert targets[0].checkpoint_key == "baseline"
    assert [t.checkpoint_key for t in targets] == [
        "baseline",
        "general",
        "spread",
        "td",
        "defense",
    ]