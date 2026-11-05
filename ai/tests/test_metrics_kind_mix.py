"""Session kind totals + mix table from episode summaries."""

from __future__ import annotations

from collections import Counter
from pathlib import Path

import gridiron_ai.metrics as metrics
from gridiron_ai.expert_stats import BUCKET_GAMES, BUCKET_SCENARIOS, BUCKET_TOTAL
from gridiron_ai.metrics import (
    accumulate_kind_totals,
    aggregate_episodes,
    format_metrics_line,
    load_kind_breakdown,
    load_kind_totals,
    print_kind_mix,
)


def test_aggregate_episodes_kind_totals() -> None:
    stats = aggregate_episodes(
        [
            {"kind_count": {"run": 2, "block": 1}, "result_kind": "FULL", "result_winner_rel": "US"},
            {"kind_count": {"run": 1, "throw": 1}, "result_kind": "FULL", "result_winner_rel": "US"},
        ]
    )
    assert stats["kind_totals"] == {"block": 1, "run": 3, "throw": 1}
    assert stats["kind_count"]["run"] == 1.5
    assert stats["kind_breakdown"][BUCKET_GAMES] == {"block": 1, "run": 3, "throw": 1}
    assert stats["kind_breakdown"][BUCKET_SCENARIOS] == {}
    assert stats["kind_breakdown"][BUCKET_TOTAL] == {"block": 1, "run": 3, "throw": 1}


def test_aggregate_episodes_splits_scenario_vs_game() -> None:
    stats = aggregate_episodes(
        [
            {
                "kind_count": {"run": 2},
                "scenario_difficulty": 1,
                "result_kind": "scenario",
                "result_winner_rel": "US",
            },
            {"kind_count": {"throw": 3}, "result_kind": "FULL", "result_winner_rel": "US"},
        ]
    )
    assert stats["kind_breakdown"][BUCKET_SCENARIOS] == {"run": 2}
    assert stats["kind_breakdown"][BUCKET_GAMES] == {"throw": 3}
    assert stats["kind_breakdown"][BUCKET_TOTAL] == {"run": 2, "throw": 3}
    assert stats["kind_totals"] == {"run": 2, "throw": 3}


def test_print_kind_mix_stdout(capsys) -> None:
    print_kind_mix(
        "Train test",
        {
            "kind_breakdown": {
                BUCKET_SCENARIOS: {"run": 2},
                BUCKET_GAMES: {"block": 1},
                BUCKET_TOTAL: {"run": 2, "block": 1},
            }
        },
    )
    out = capsys.readouterr().out
    assert "Train test chosen-move kinds" in out
    assert "=== scenarios ===" in out and "=== games ===" in out and "=== total ===" in out
    assert "run" in out and "block" in out
    assert "TOTAL" in out


def test_accumulate_kind_totals_persists(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(metrics, "RUNS_DIR", tmp_path)
    assert load_kind_totals("scenario_a") == Counter()
    first = accumulate_kind_totals(
        "scenario_a",
        {
            BUCKET_SCENARIOS: {"run": 2, "block": 1},
            BUCKET_GAMES: {},
            BUCKET_TOTAL: {"run": 2, "block": 1},
        },
    )
    assert first[BUCKET_SCENARIOS]["run"] == 2 and first[BUCKET_TOTAL]["block"] == 1
    second = accumulate_kind_totals(
        "scenario_a",
        {
            BUCKET_SCENARIOS: {},
            BUCKET_GAMES: {"run": 1, "throw": 4},
            BUCKET_TOTAL: {"run": 1, "throw": 4},
        },
    )
    assert second[BUCKET_SCENARIOS]["run"] == 2
    assert second[BUCKET_GAMES]["throw"] == 4
    assert second[BUCKET_TOTAL]["run"] == 3 and second[BUCKET_TOTAL]["throw"] == 4
    assert load_kind_breakdown("scenario_a") == second
    assert load_kind_totals("scenario_a") == second[BUCKET_TOTAL]
    txt = (tmp_path / "scenario_a" / "kind_totals.txt").read_text(encoding="utf-8")
    # Both families populated → scenarios + games + total.
    assert "=== scenarios ===" in txt and "=== games ===" in txt and "=== total ===" in txt
    assert "run" in txt and "TOTAL" in txt


def test_accumulate_kind_totals_single_family_txt_omits_empty(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(metrics, "RUNS_DIR", tmp_path)
    accumulate_kind_totals(
        "scenario_a",
        {
            BUCKET_SCENARIOS: {"run": 2},
            BUCKET_GAMES: {},
            BUCKET_TOTAL: {"run": 2},
        },
    )
    txt = (tmp_path / "scenario_a" / "kind_totals.txt").read_text(encoding="utf-8")
    assert "=== scenarios ===" in txt
    assert "=== games ===" not in txt
    assert "=== total ===" not in txt


def test_accumulate_kind_totals_legacy_flat(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(metrics, "RUNS_DIR", tmp_path)
    lifetime = accumulate_kind_totals("scenario_a", {"run": 2, "block": 1})
    assert lifetime[BUCKET_TOTAL]["run"] == 2
    assert lifetime[BUCKET_SCENARIOS] == Counter()
    assert lifetime[BUCKET_GAMES] == Counter()
    txt = (tmp_path / "scenario_a" / "kind_totals.txt").read_text(encoding="utf-8")
    assert "=== total ===" in txt
    assert "=== scenarios ===" not in txt


def test_load_kind_breakdown_legacy_counts_json(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(metrics, "RUNS_DIR", tmp_path)
    root = tmp_path / "scenario_a"
    root.mkdir(parents=True)
    (root / "kind_totals.json").write_text(
        '{"counts": {"run": 5, "block": 2}}\n',
        encoding="utf-8",
    )
    loaded = load_kind_breakdown("scenario_a")
    assert loaded[BUCKET_TOTAL] == Counter({"run": 5, "block": 2})
    assert loaded[BUCKET_SCENARIOS] == Counter()
    assert loaded[BUCKET_GAMES] == Counter()
    # Next accumulate rewrites the new shape without dropping the legacy total.
    updated = accumulate_kind_totals(
        "scenario_a",
        {BUCKET_GAMES: {"throw": 1}, BUCKET_SCENARIOS: {}, BUCKET_TOTAL: {"throw": 1}},
    )
    assert updated[BUCKET_TOTAL]["run"] == 5 and updated[BUCKET_TOTAL]["throw"] == 1


def test_format_metrics_line_winrate_delta_vs_prior() -> None:
    stats = aggregate_episodes(
        [{"kind_count": {"run": 1}, "result_kind": "FULL", "result_winner_rel": "THEM"}]
    )
    stats["opening_expected_p_mean"] = 0.006
    prior = {"winrate": 0.2, "opening_expected_p_mean": 0.05}
    line = format_metrics_line("Eval [baseline] vs-x", stats, prior=prior)
    assert "winrate=0.200→0.000" in line
    assert "expected_p=0.050→0.006" in line
