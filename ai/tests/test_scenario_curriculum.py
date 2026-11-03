from gridiron_ai.scenario_curriculum import (
    CLAIM_UNLOCK_BOARD_DIFFICULTY,
    DEFAULT_SCENARIO_TRAIN_LEARNING,
    DEFAULT_SCENARIO_TRAIN_MASTERED,
    DEFAULT_SCENARIO_TRAIN_MAINTENANCE,
    FULL_GAME_UNLOCK_DIFFICULTY,
    GATE_PROGRESS_WAIVE_FAILURES,
    MAX_DIFFICULTY,
    SCENARIO_EVAL_GAMES,
    SCENARIO_EVAL_PASS_MIN_WINS,
    SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER,
    SCENARIO_TRAIN_GATE_FAIL_MULTIPLIER,
    SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD,
    active_scenario_catalog,
    all_scenarios_mastered,
    apply_after_eval_curriculum,
    claims_unlocked,
    drill_stat_count,
    effective_gates_progress,
    full_game_train_timesteps,
    gate_failure_count,
    gating_pack_max_difficulty,
    gating_pack_min_difficulty,
    gating_scenarios_mastered,
    get_difficulty,
    is_progress_waived,
    maybe_bump_after_perfect_eval,
    merge_drill_stats,
    maybe_demote_after_failed_max_eval,
    record_gate_failure,
    record_train_cycles,
    scenario_max_coach_turns,
    scenarios_for_cycle,
    scenarios_for_eval,
    scenario_needs_maintenance,
    train_timesteps_for_difficulty,
    train_timesteps_for_scenario,
)
from gridiron_ai.scenarios import ScenarioCatalog, ScenarioInfo, set_catalog
from tests.catalog_fixture import BOARD_NAMES, CLAIM_NAMES, FULL_NAMES, install_fixture_catalog

install_fixture_catalog()

_EVAL = SCENARIO_EVAL_GAMES
_PASS = SCENARIO_EVAL_PASS_MIN_WINS
_CATALOG = ["TD_RUN", "LOOSE_BALL_RUN", "MARKED_RUN"]
_FULL = list(FULL_NAMES)


def test_train_timesteps_learning_below_max() -> None:
    assert train_timesteps_for_difficulty(0) == DEFAULT_SCENARIO_TRAIN_LEARNING
    assert train_timesteps_for_difficulty(MAX_DIFFICULTY - 1) == DEFAULT_SCENARIO_TRAIN_LEARNING


def test_train_timesteps_mastered_at_max() -> None:
    assert train_timesteps_for_difficulty(MAX_DIFFICULTY) == DEFAULT_SCENARIO_TRAIN_MASTERED
    assert train_timesteps_for_difficulty(MAX_DIFFICULTY + 99) == DEFAULT_SCENARIO_TRAIN_MASTERED


def test_train_timesteps_all_mastered_uses_maintenance() -> None:
    assert train_timesteps_for_difficulty(MAX_DIFFICULTY, all_mastered=True) == DEFAULT_SCENARIO_TRAIN_MAINTENANCE
    assert train_timesteps_for_difficulty(0, all_mastered=True, maintenance=100) == 100


def test_train_timesteps_game_phase_at_d5_is_maintenance() -> None:
    assert train_timesteps_for_difficulty(MAX_DIFFICULTY, game_phase=True) == DEFAULT_SCENARIO_TRAIN_MAINTENANCE
    assert train_timesteps_for_difficulty(3, game_phase=True) == DEFAULT_SCENARIO_TRAIN_LEARNING


def test_train_budgets_are_whole_rollouts() -> None:
    """Learning = mastered = maintenance = 1×3840 (locked n_envs×n_steps defaults)."""
    assert DEFAULT_SCENARIO_TRAIN_LEARNING == 3_840
    assert DEFAULT_SCENARIO_TRAIN_MASTERED == 3_840
    assert DEFAULT_SCENARIO_TRAIN_MAINTENANCE == 3_840


def test_scenario_needs_maintenance_soft_d5() -> None:
    failures = {"TD_RUN": {str(MAX_DIFFICULTY): 1}}
    assert scenario_needs_maintenance("TD_RUN", difficulty=MAX_DIFFICULTY, gate_failures=failures)
    assert not scenario_needs_maintenance("TD_RUN", difficulty=MAX_DIFFICULTY, gate_failures={})
    assert not scenario_needs_maintenance("TD_RUN", difficulty=3, gate_failures=failures)


def test_train_timesteps_game_phase_soft_d5_gets_maintenance() -> None:
    levels = {name: MAX_DIFFICULTY for name in _CATALOG}
    failures = {"TD_RUN": {str(MAX_DIFFICULTY): 2}}
    assert (
        train_timesteps_for_scenario(
            "TD_RUN", levels, _CATALOG, gate_failures=failures, game_phase=True
        )
        == DEFAULT_SCENARIO_TRAIN_MAINTENANCE
    )
    assert (
        train_timesteps_for_scenario(
            "LOOSE_BALL_RUN", levels, _CATALOG, gate_failures=failures, game_phase=True
        )
        == 0
    )


def test_train_timesteps_game_phase_straggler_still_learns() -> None:
    levels = {"TD_RUN": MAX_DIFFICULTY, "LOOSE_BALL_RUN": 3, "MARKED_RUN": MAX_DIFFICULTY}
    assert (
        train_timesteps_for_scenario("LOOSE_BALL_RUN", levels, _CATALOG, game_phase=True)
        == DEFAULT_SCENARIO_TRAIN_LEARNING
    )


def test_full_game_train_timesteps_unlocked_bypasses_live_gate() -> None:
    levels = {"TD_RUN": 5, "LOOSE_BALL_RUN": 0, "MARKED_RUN": 3}
    assert full_game_train_timesteps(30_000, levels, _CATALOG, game_phase_unlocked=True) == 30_000


def test_full_game_unlock_equals_max() -> None:
    assert FULL_GAME_UNLOCK_DIFFICULTY == MAX_DIFFICULTY


def test_full_game_train_timesteps_off_until_unlock() -> None:
    levels = {"TD_RUN": 5, "LOOSE_BALL_RUN": 0, "MARKED_RUN": 3}
    assert full_game_train_timesteps(30_000, levels, _CATALOG) == 0


def test_full_game_train_timesteps_off_at_old_d3_floor() -> None:
    """Games unlock only at MAX — d3 across the catalog is not enough."""
    levels = {name: 3 for name in _CATALOG}
    assert full_game_train_timesteps(30_000, levels, _CATALOG) == 0


def test_full_game_train_timesteps_on_when_all_at_max() -> None:
    levels = {"TD_RUN": 5, "LOOSE_BALL_RUN": 5, "MARKED_RUN": 5}
    assert full_game_train_timesteps(30_000, levels, _CATALOG) == 30_000


def test_full_game_train_timesteps_full_when_catalog_empty() -> None:
    assert full_game_train_timesteps(10_000, {}, []) == 10_000


def test_full_game_off_while_claims_deferred() -> None:
    """Board at d4+ but claims still at 0 → scenario_b not done → no full games."""
    levels = {name: CLAIM_UNLOCK_BOARD_DIFFICULTY for name in BOARD_NAMES}
    assert claims_unlocked(levels, _FULL)
    assert full_game_train_timesteps(30_000, levels, _FULL) == 0


def test_full_game_on_after_gating_drills_at_max() -> None:
    levels = {name: MAX_DIFFICULTY for name in BOARD_NAMES}
    for name in CLAIM_NAMES:
        levels[name] = MAX_DIFFICULTY
    assert gating_scenarios_mastered(levels, _FULL)
    assert full_game_train_timesteps(30_000, levels, _FULL) == 30_000


def test_claims_deferred_until_board_d4() -> None:
    levels = {name: 3 for name in BOARD_NAMES}
    assert not claims_unlocked(levels, _FULL)
    active = active_scenario_catalog(levels, _FULL)
    assert all(not name.startswith("CLAIM_") for name in active)
    assert "TD_RUN" in active


def test_claims_enter_catalog_at_board_d4() -> None:
    levels = {name: CLAIM_UNLOCK_BOARD_DIFFICULTY for name in BOARD_NAMES}
    assert claims_unlocked(levels, _FULL)
    active = active_scenario_catalog(levels, _FULL)
    assert CLAIM_NAMES[0] in active


def test_scenarios_for_cycle_runs_all_active_even_when_spread() -> None:
    levels = {"TD_RUN": 0, "LOOSE_BALL_RUN": 2, "MARKED_RUN": 5}
    cycle = scenarios_for_cycle(levels, _CATALOG)
    assert set(cycle) == set(_CATALOG)
    assert cycle == sorted(cycle, key=lambda name: (get_difficulty(levels, name), _CATALOG.index(name)))


def test_scenarios_for_cycle_orders_by_ascending_difficulty() -> None:
    levels = {
        "TD_RUN": 2,
        "MARKED_RUN": 0,
        "LOOSE_BALL_RUN": 1,
        "THROW_TO_ENDZONE": 2,
    }
    cycle = scenarios_for_cycle(levels, _CATALOG)
    difficulties = [get_difficulty(levels, name) for name in cycle]
    assert difficulties == sorted(difficulties)
    assert cycle.index("MARKED_RUN") < cycle.index("LOOSE_BALL_RUN")
    assert cycle.index("LOOSE_BALL_RUN") < cycle.index("TD_RUN")


def test_train_timesteps_zero_knobs_skip() -> None:
    assert train_timesteps_for_difficulty(0, learning=0, mastered=0) == 0
    assert train_timesteps_for_difficulty(MAX_DIFFICULTY, learning=0, mastered=0) == 0


def test_bounce_difficulty_tracks_loose_ball_run() -> None:
    levels = {"LOOSE_BALL_RUN": 3, "LOOSE_BALL_BOUNCE": 0, "TD_RUN": 1}
    assert get_difficulty(levels, "LOOSE_BALL_BOUNCE") == 3
    assert get_difficulty(levels, "LOOSE_BALL_RUN") == 3


def test_new_catalog_name_starts_at_difficulty_zero() -> None:
    assert get_difficulty({}, "TD_RUN") == 0
    assert get_difficulty({"MARKED_RUN": 4}, "TD_RUN") == 0


def test_eval_replaces_bounce_with_loose_ball_run() -> None:
    catalog = ["TD_RUN", "LOOSE_BALL_RUN", "LOOSE_BALL_BOUNCE"]
    levels = {"TD_RUN": 0, "LOOSE_BALL_RUN": 0}
    assert scenarios_for_eval(levels, catalog) == ["TD_RUN", "LOOSE_BALL_RUN"]
    assert scenarios_for_eval(levels, ["LOOSE_BALL_BOUNCE"]) == ["LOOSE_BALL_RUN"]


def test_scenario_max_coach_turns_by_difficulty() -> None:
    assert scenario_max_coach_turns(0) == 1
    assert scenario_max_coach_turns(3) == 1
    assert scenario_max_coach_turns(4) == 6
    assert scenario_max_coach_turns(5) == 6


def test_optional_claim_deferred_until_board_d4() -> None:
    catalog = ScenarioCatalog.from_entries(
        [
            ScenarioInfo("TD_RUN", "BOARD"),
            ScenarioInfo("CLAIM_EXTRA", "CLAIM", gates_progress=False),
            ScenarioInfo("CLAIM_GRAB_IT", "CLAIM"),
        ]
    )
    set_catalog(catalog)
    levels = {"TD_RUN": 0}
    active = active_scenario_catalog(levels, catalog.names())
    assert "CLAIM_EXTRA" not in active
    assert "CLAIM_GRAB_IT" not in active
    levels = {"TD_RUN": CLAIM_UNLOCK_BOARD_DIFFICULTY}
    active = active_scenario_catalog(levels, catalog.names())
    assert "CLAIM_EXTRA" in active
    assert "CLAIM_GRAB_IT" in active
    install_fixture_catalog()


def test_non_gating_drill_stays_in_cycle() -> None:
    catalog = ScenarioCatalog.from_entries(
        [
            ScenarioInfo("TD_RUN", "BOARD"),
            ScenarioInfo("LOOSE_BALL_RUN", "BOARD"),
            ScenarioInfo("HELD_SS_REROLL", "BOARD", gates_progress=False),
        ]
    )
    set_catalog(catalog)
    levels = {"TD_RUN": 0, "LOOSE_BALL_RUN": 0, "HELD_SS_REROLL": 5}
    assert scenarios_for_cycle(levels, catalog.names()) == ["TD_RUN", "LOOSE_BALL_RUN", "HELD_SS_REROLL"]
    install_fixture_catalog()


def test_gating_scenarios_mastered_ignores_non_gating() -> None:
    catalog = ScenarioCatalog.from_entries(
        [
            ScenarioInfo("TD_RUN", "BOARD"),
            ScenarioInfo("HELD_SS_REROLL", "BOARD", gates_progress=False),
        ]
    )
    set_catalog(catalog)
    levels = {"TD_RUN": MAX_DIFFICULTY, "HELD_SS_REROLL": 0}
    assert gating_scenarios_mastered(levels, catalog.names())
    assert not all_scenarios_mastered(levels, catalog.names())
    install_fixture_catalog()


def test_non_gating_drill_bumps_on_perfect_eval() -> None:
    levels: dict[str, int] = {"HELD_SS_REROLL": 0}
    stats = {"episodes": _EVAL, "wins": _PASS, "losses": 1, "draws": 0}
    updated, bumped = maybe_bump_after_perfect_eval(
        levels, "HELD_SS_REROLL", stats, expected_games=_EVAL
    )
    assert bumped
    assert updated["HELD_SS_REROLL"] == 1


def test_non_gating_board_and_claims_share_cycle_after_unlock() -> None:
    catalog = ScenarioCatalog.from_entries(
        [
            ScenarioInfo("TD_RUN", "BOARD"),
            ScenarioInfo("HELD_SS_REROLL", "BOARD", gates_progress=False),
            ScenarioInfo("CLAIM_EXTRA", "CLAIM", gates_progress=False),
            ScenarioInfo("CLAIM_GRAB_IT", "CLAIM"),
        ]
    )
    set_catalog(catalog)
    levels = {"TD_RUN": 0, "HELD_SS_REROLL": 3, "CLAIM_EXTRA": 4, "CLAIM_GRAB_IT": 0}
    active = active_scenario_catalog(levels, catalog.names())
    assert "HELD_SS_REROLL" in active
    assert "CLAIM_EXTRA" not in active
    assert "CLAIM_GRAB_IT" not in active
    cycle = scenarios_for_cycle(levels, catalog.names())
    assert cycle == ["TD_RUN", "HELD_SS_REROLL"]
    levels = {
        "TD_RUN": CLAIM_UNLOCK_BOARD_DIFFICULTY,
        "HELD_SS_REROLL": 3,
        "CLAIM_EXTRA": 4,
        "CLAIM_GRAB_IT": 0,
    }
    cycle = scenarios_for_cycle(levels, catalog.names())
    assert "HELD_SS_REROLL" in cycle
    assert "CLAIM_EXTRA" in cycle
    assert "CLAIM_GRAB_IT" in cycle
    install_fixture_catalog()


def test_demote_failed_max_eval() -> None:
    levels = {"TD_RUN": MAX_DIFFICULTY}
    stats = {"episodes": _EVAL, "wins": 7, "losses": 3, "draws": 0}
    updated, demoted = maybe_demote_after_failed_max_eval(
        levels, "TD_RUN", stats, expected_games=_EVAL
    )
    assert demoted
    assert updated["TD_RUN"] == MAX_DIFFICULTY - 1


def test_no_demote_single_failure_at_max() -> None:
    levels = {"TD_RUN": MAX_DIFFICULTY}
    stats = {"episodes": _EVAL, "wins": 8, "losses": 2, "draws": 0}
    updated, demoted = maybe_demote_after_failed_max_eval(
        levels, "TD_RUN", stats, expected_games=_EVAL
    )
    assert not demoted
    assert updated["TD_RUN"] == MAX_DIFFICULTY


def test_no_demote_below_max() -> None:
    levels = {"TD_RUN": 4}
    stats = {"episodes": _EVAL, "wins": 0, "losses": _EVAL, "draws": 0}
    updated, demoted = maybe_demote_after_failed_max_eval(
        levels, "TD_RUN", stats, expected_games=_EVAL
    )
    assert not demoted
    assert updated["TD_RUN"] == 4


def test_record_gate_failure_counts_per_level() -> None:
    failures = record_gate_failure({}, "TD_RUN", 5)
    failures = record_gate_failure(failures, "TD_RUN", 5)
    failures = record_gate_failure(failures, "TD_RUN", 4)
    assert gate_failure_count(failures, "TD_RUN", difficulty=5) == 2
    assert gate_failure_count(failures, "TD_RUN", difficulty=4) == 1
    assert gate_failure_count(failures, "TD_RUN") == 3


def test_apply_after_eval_single_fail_at_max_counts_without_demote() -> None:
    levels = {"TD_RUN": MAX_DIFFICULTY}
    stats = {"episodes": _EVAL, "wins": 8, "losses": 2, "draws": 0}
    levels, failures, drill_stats, action = apply_after_eval_curriculum(
        levels, {}, "TD_RUN", stats, expected_games=_EVAL
    )
    assert action == "fail"
    assert levels["TD_RUN"] == MAX_DIFFICULTY
    assert gate_failure_count(failures, "TD_RUN", difficulty=MAX_DIFFICULTY) == 1
    assert drill_stat_count(drill_stats, "TD_RUN", "cycles") == 0
    assert drill_stat_count(drill_stats, "TD_RUN", "d5_demotes") == 0


def test_apply_after_eval_demotes_and_counts() -> None:
    levels = {"TD_RUN": MAX_DIFFICULTY}
    stats = {"episodes": _EVAL, "wins": 7, "losses": 3, "draws": 0}
    levels, failures, drill_stats, action = apply_after_eval_curriculum(
        levels, {}, "TD_RUN", stats, expected_games=_EVAL
    )
    assert action == "demote"
    assert levels["TD_RUN"] == MAX_DIFFICULTY - 1
    assert gate_failure_count(failures, "TD_RUN", difficulty=MAX_DIFFICULTY) == 1
    assert drill_stat_count(drill_stats, "TD_RUN", "cycles") == 0
    assert drill_stat_count(drill_stats, "TD_RUN", "d5_demotes") == 1


def test_record_train_cycles_counts_units() -> None:
    stats = record_train_cycles({}, "TD_RUN", units=1)
    stats = record_train_cycles(stats, "TD_RUN", units=2)
    assert drill_stat_count(stats, "TD_RUN", "cycles") == 3


def test_merge_drill_stats_sums_across_phases() -> None:
    merged = merge_drill_stats(
        {"TD_RUN": {"cycles": 12, "d5_demotes": 1}},
        {"TD_RUN": {"cycles": 3, "d5_demotes": 0}, "MARKED_RUN": {"cycles": 2, "d5_demotes": 1}},
    )
    assert drill_stat_count(merged, "TD_RUN", "cycles") == 15
    assert drill_stat_count(merged, "TD_RUN", "d5_demotes") == 1
    assert drill_stat_count(merged, "MARKED_RUN", "cycles") == 2
    assert drill_stat_count(merged, "MARKED_RUN", "d5_demotes") == 1


def test_apply_after_eval_eight_of_ten_does_not_bump() -> None:
    levels = {"TD_RUN": 2}
    stats = {"episodes": _EVAL, "wins": 8, "losses": 2, "draws": 0}
    levels, failures, drill_stats, action = apply_after_eval_curriculum(
        levels, {}, "TD_RUN", stats, expected_games=_EVAL
    )
    assert action == "fail"
    assert levels["TD_RUN"] == 2
    assert gate_failure_count(failures, "TD_RUN", difficulty=2) == 1


def test_apply_after_eval_bump_keeps_all_time_fails() -> None:
    levels = {"TD_RUN": 2}
    failures = record_gate_failure({}, "TD_RUN", 2)
    failures = record_gate_failure(failures, "TD_RUN", 2)
    stats = {"episodes": _EVAL, "wins": _EVAL, "losses": 0, "draws": 0}
    levels, failures, drill_stats, action = apply_after_eval_curriculum(
        levels, failures, "TD_RUN", stats, expected_games=_EVAL
    )
    assert action == "bump"
    assert levels["TD_RUN"] == 3
    assert gate_failure_count(failures, "TD_RUN", difficulty=2) == 2
    assert gate_failure_count(failures, "TD_RUN", difficulty=3) == 0
    assert gate_failure_count(failures, "TD_RUN") == 2
    assert drill_stat_count(drill_stats, "TD_RUN", "cycles") == 0


def test_apply_after_eval_bump_without_failure() -> None:
    levels = {"TD_RUN": 2}
    stats = {"episodes": _EVAL, "wins": _PASS, "losses": 1, "draws": 0}
    levels, failures, drill_stats, action = apply_after_eval_curriculum(
        levels, {}, "TD_RUN", stats, expected_games=_EVAL
    )
    assert action == "bump"
    assert levels["TD_RUN"] == 3
    assert failures == {}
    assert drill_stat_count(drill_stats, "TD_RUN", "cycles") == 0


def test_progress_waived_after_threshold() -> None:
    # Claims only — board drills never waive even past the fail threshold.
    catalog = ["TD_RUN", "LOOSE_BALL_RUN", "MARKED_RUN", "CLAIM_GRAB_IT"]
    levels = {
        "TD_RUN": MAX_DIFFICULTY,
        "LOOSE_BALL_RUN": MAX_DIFFICULTY,
        "MARKED_RUN": MAX_DIFFICULTY,
        "CLAIM_GRAB_IT": 3,
    }
    failures: dict[str, dict[str, int]] = {}
    for _ in range(GATE_PROGRESS_WAIVE_FAILURES):
        failures = record_gate_failure(failures, "CLAIM_GRAB_IT", 3)
    assert is_progress_waived(failures, "CLAIM_GRAB_IT", levels)
    assert not effective_gates_progress("CLAIM_GRAB_IT", levels, failures)
    assert gating_scenarios_mastered(levels, catalog, failures)


def test_board_drills_never_progress_waived() -> None:
    levels = {"TD_RUN": 1, "LOOSE_BALL_RUN": 5, "MARKED_RUN": 5}
    failures: dict[str, dict[str, int]] = {}
    for _ in range(GATE_PROGRESS_WAIVE_FAILURES):
        failures = record_gate_failure(failures, "TD_RUN", 1)
    assert not is_progress_waived(failures, "TD_RUN", levels)
    assert effective_gates_progress("TD_RUN", levels, failures)
    assert not gating_scenarios_mastered(levels, _CATALOG, failures)


def test_perfect_eval_restores_progress_gating() -> None:
    catalog = ["TD_RUN", "LOOSE_BALL_RUN", "MARKED_RUN", "CLAIM_GRAB_IT"]
    levels = {
        "TD_RUN": MAX_DIFFICULTY,
        "LOOSE_BALL_RUN": MAX_DIFFICULTY,
        "MARKED_RUN": MAX_DIFFICULTY,
        "CLAIM_GRAB_IT": MAX_DIFFICULTY,
    }
    failures: dict[str, dict[str, int]] = {}
    for _ in range(GATE_PROGRESS_WAIVE_FAILURES):
        failures = record_gate_failure(failures, "CLAIM_GRAB_IT", MAX_DIFFICULTY)
    # Older-level fails stay in all-time after recover.
    failures = record_gate_failure(failures, "CLAIM_GRAB_IT", 3)
    stats = {"episodes": _EVAL, "wins": _EVAL, "losses": 0, "draws": 0}
    levels, failures, drill_stats, action = apply_after_eval_curriculum(
        levels, failures, "CLAIM_GRAB_IT", stats, expected_games=_EVAL
    )
    assert action == "recover"
    assert effective_gates_progress("CLAIM_GRAB_IT", levels, failures)
    assert gating_scenarios_mastered(levels, catalog, failures)
    assert gate_failure_count(failures, "CLAIM_GRAB_IT", difficulty=MAX_DIFFICULTY) == 0
    assert gate_failure_count(failures, "CLAIM_GRAB_IT", difficulty=3) == 1
    assert gate_failure_count(failures, "CLAIM_GRAB_IT") == 1
    assert drill_stat_count(drill_stats, "CLAIM_GRAB_IT", "cycles") == 0


def test_gating_pack_max_difficulty() -> None:
    levels = {"TD_RUN": 5, "LOOSE_BALL_RUN": 4, "MARKED_RUN": 5}
    assert gating_pack_max_difficulty(levels, _CATALOG) == 5


def test_gating_pack_min_difficulty() -> None:
    levels = {"TD_RUN": 1, "LOOSE_BALL_RUN": 4, "MARKED_RUN": 0}
    assert gating_pack_min_difficulty(levels, _CATALOG) == 0


def test_train_timesteps_double_on_current_gate_fails() -> None:
    # Flat pack at claim-unlock floor: fail-2× only (not behind, not bottleneck, not opening).
    levels = {
        "TD_RUN": CLAIM_UNLOCK_BOARD_DIFFICULTY,
        "LOOSE_BALL_RUN": CLAIM_UNLOCK_BOARD_DIFFICULTY,
        "MARKED_RUN": CLAIM_UNLOCK_BOARD_DIFFICULTY,
    }
    assert SCENARIO_TRAIN_GATE_FAIL_MULTIPLIER == 2
    assert train_timesteps_for_scenario("TD_RUN", levels, _CATALOG) == DEFAULT_SCENARIO_TRAIN_LEARNING
    failures = record_gate_failure({}, "TD_RUN", CLAIM_UNLOCK_BOARD_DIFFICULTY)
    assert (
        train_timesteps_for_scenario("TD_RUN", levels, _CATALOG, gate_failures=failures)
        == DEFAULT_SCENARIO_TRAIN_LEARNING * 2
    )


def test_train_timesteps_no_double_on_d5_gate_fails() -> None:
    levels = {"TD_RUN": 5, "LOOSE_BALL_RUN": 5, "MARKED_RUN": 5}
    failures = record_gate_failure({}, "TD_RUN", MAX_DIFFICULTY)
    assert (
        train_timesteps_for_scenario("TD_RUN", levels, _CATALOG, gate_failures=failures)
        == DEFAULT_SCENARIO_TRAIN_MASTERED
    )


def test_train_timesteps_behind_pack_doubles_without_claim_unlock() -> None:
    levels = {"TD_RUN": 0, "LOOSE_BALL_RUN": 5, "MARKED_RUN": 5}
    assert train_timesteps_for_scenario("TD_RUN", levels, _CATALOG) == DEFAULT_SCENARIO_TRAIN_LEARNING * 2


def test_train_timesteps_game_phase_no_multipliers() -> None:
    levels = {"TD_RUN": 5, "LOOSE_BALL_RUN": 4, "MARKED_RUN": 5}
    failures = record_gate_failure({}, "LOOSE_BALL_RUN", 4)
    stats = {"LOOSE_BALL_RUN": {"d5_demotes": SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD, "cycles": 0}}
    assert (
        train_timesteps_for_scenario(
            "LOOSE_BALL_RUN", levels, _CATALOG, gate_failures=failures, drill_stats=stats, game_phase=True
        )
        == DEFAULT_SCENARIO_TRAIN_LEARNING
    )


def test_train_timesteps_double_below_pack_when_healthy() -> None:
    levels = {"TD_RUN": 5, "LOOSE_BALL_RUN": 4, "MARKED_RUN": 5}
    assert SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER == 4
    assert train_timesteps_for_scenario("LOOSE_BALL_RUN", levels, _CATALOG) == DEFAULT_SCENARIO_TRAIN_LEARNING * 2
    assert train_timesteps_for_scenario("TD_RUN", levels, _CATALOG) == DEFAULT_SCENARIO_TRAIN_MASTERED


def test_train_timesteps_bottleneck_fail_quadruples_before_claim_unlock() -> None:
    levels = {"TD_RUN": 0, "LOOSE_BALL_RUN": 5, "MARKED_RUN": 5}
    failures = record_gate_failure({}, "TD_RUN", 0)
    assert (
        train_timesteps_for_scenario("TD_RUN", levels, _CATALOG, gate_failures=failures)
        == DEFAULT_SCENARIO_TRAIN_LEARNING * 4
    )


def test_train_timesteps_non_bottleneck_fail_stays_double() -> None:
    levels = {"TD_RUN": 0, "LOOSE_BALL_RUN": 4, "MARKED_RUN": 5}
    failures = record_gate_failure({}, "LOOSE_BALL_RUN", 4)
    assert (
        train_timesteps_for_scenario("LOOSE_BALL_RUN", levels, _CATALOG, gate_failures=failures)
        == DEFAULT_SCENARIO_TRAIN_LEARNING * 2
    )


def test_train_timesteps_opening_straggler_quadruples_with_fail() -> None:
    # Below claim-unlock floor with fails → 4× even when not the absolute pack min.
    levels = {"TD_RUN": 1, "LOOSE_BALL_RUN": 5, "MARKED_RUN": 0}
    failures = record_gate_failure({}, "TD_RUN", 1)
    assert (
        train_timesteps_for_scenario("TD_RUN", levels, _CATALOG, gate_failures=failures)
        == DEFAULT_SCENARIO_TRAIN_LEARNING * 4
    )


def test_train_timesteps_high_demotes_need_claim_unlock_for_quadruple() -> None:
    levels = {"TD_RUN": 0, "LOOSE_BALL_RUN": 4, "MARKED_RUN": 5}
    failures = record_gate_failure({}, "LOOSE_BALL_RUN", 4)
    stats = {"LOOSE_BALL_RUN": {"d5_demotes": SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD, "cycles": 0}}
    assert (
        train_timesteps_for_scenario(
            "LOOSE_BALL_RUN", levels, _CATALOG, gate_failures=failures, drill_stats=stats
        )
        == DEFAULT_SCENARIO_TRAIN_LEARNING * 2
    )
    unlocked = {"TD_RUN": 4, "LOOSE_BALL_RUN": 4, "MARKED_RUN": 5, "CLAIM_GRAB_IT": 0}
    catalog = ["TD_RUN", "LOOSE_BALL_RUN", "MARKED_RUN", "CLAIM_GRAB_IT"]
    # Claim at d0 is the bottleneck; board demote treadmill gets 4× only via high_demotes.
    failures = record_gate_failure({}, "LOOSE_BALL_RUN", 4)
    assert (
        train_timesteps_for_scenario(
            "LOOSE_BALL_RUN", unlocked, catalog, gate_failures=failures, drill_stats=stats
        )
        == DEFAULT_SCENARIO_TRAIN_LEARNING * 4
    )


def test_train_timesteps_bottleneck_and_gate_fail_take_max_multiplier() -> None:
    levels = {"TD_RUN": 5, "LOOSE_BALL_RUN": 4, "MARKED_RUN": 5}
    failures = record_gate_failure({}, "LOOSE_BALL_RUN", 4)
    assert (
        train_timesteps_for_scenario("LOOSE_BALL_RUN", levels, _CATALOG, gate_failures=failures)
        == DEFAULT_SCENARIO_TRAIN_LEARNING * 4
    )


def test_train_timesteps_optional_no_double_on_current_fails() -> None:
    catalog = ScenarioCatalog.from_entries(
        [
            ScenarioInfo("TD_RUN", "BOARD"),
            ScenarioInfo("HELD_SS_REROLL", "BOARD", gates_progress=False),
        ]
    )
    set_catalog(catalog)
    levels = {"TD_RUN": 5, "HELD_SS_REROLL": 0}
    failures = record_gate_failure({}, "HELD_SS_REROLL", 0)
    assert (
        train_timesteps_for_scenario(
            "HELD_SS_REROLL", levels, catalog.names(), gate_failures=failures
        )
        == DEFAULT_SCENARIO_TRAIN_LEARNING
    )
    install_fixture_catalog()


def test_train_timesteps_waived_still_doubles_on_current_fails() -> None:
    catalog = ["TD_RUN", "LOOSE_BALL_RUN", "MARKED_RUN", "CLAIM_GRAB_IT"]
    levels = {
        "TD_RUN": MAX_DIFFICULTY,
        "LOOSE_BALL_RUN": MAX_DIFFICULTY,
        "MARKED_RUN": MAX_DIFFICULTY,
        "CLAIM_GRAB_IT": 4,
    }
    failures: dict[str, dict[str, int]] = {}
    for _ in range(GATE_PROGRESS_WAIVE_FAILURES):
        failures = record_gate_failure(failures, "CLAIM_GRAB_IT", 4)
    assert is_progress_waived(failures, "CLAIM_GRAB_IT", levels)
    assert (
        train_timesteps_for_scenario("CLAIM_GRAB_IT", levels, catalog, gate_failures=failures)
        == DEFAULT_SCENARIO_TRAIN_LEARNING * 2
    )
