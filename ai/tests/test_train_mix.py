import argparse

from gridiron_ai.train_mix import (
    FULL_GAME_EVAL_EVERY_CYCLES,
    HEALTHY_D5_EVAL_EVERY_CYCLES,
    TrainMix,
)
from gridiron_ai.training_phases import PHASE_PROFILES, PHASE_SCENARIO_A, PHASE_SCENARIO_B


def _args(**overrides: object) -> argparse.Namespace:
    base = dict(
        train_scenario_learning=3840,
        train_scenario_mastered=0,
        train_heuristic=20_000,
        train_lookahead=20_000,
        train_onnx=20_000,
        eval_heuristic=200,
        eval_lookahead=200,
        eval_onnx=200,
        eval_only=False,
        skip_scenarios=False,
        scenario=None,
    )
    base.update(overrides)
    return argparse.Namespace(**base)


def test_from_args_eval_only_zeros_train() -> None:
    mix = TrainMix.from_args(_args(eval_only=True))
    assert mix.eval_only
    assert mix.scenario_learning == 0
    assert mix.train_heuristic == 0
    assert mix.eval_heuristic == 200
    assert mix.has_work()


def test_from_args_scenario_pin_zeros_full_game() -> None:
    mix = TrainMix.from_args(_args(scenario="TD_RUN"))
    assert mix.train_heuristic == 0
    assert mix.eval_onnx == 0
    assert mix.scenario_learning == 3840


def test_from_args_skip_scenarios_zeros_scenario_train() -> None:
    mix = TrainMix.from_args(_args(skip_scenarios=True))
    assert mix.skip_scenario_train()
    assert not mix.skip_scenario_eval()
    mix_eval = TrainMix.from_args(_args(skip_scenarios=True, eval_only=True))
    assert mix_eval.skip_scenario_eval()


def test_full_game_eval_cadence() -> None:
    mix = TrainMix()
    assert mix.run_full_game_eval(cycle_index=1, n_targets=1)
    assert not mix.run_full_game_eval(cycle_index=2, n_targets=1)
    assert mix.run_full_game_eval(cycle_index=1 + FULL_GAME_EVAL_EVERY_CYCLES, n_targets=1)
    assert TrainMix(eval_only=True).run_full_game_eval(cycle_index=2, n_targets=1)


def test_healthy_d5_eval_climbing_only() -> None:
    mix = TrainMix()
    assert mix.run_healthy_d5_eval(
        game_phase=False, phase=PHASE_SCENARIO_A, cycle_index=1, n_targets=1
    )
    assert not mix.run_healthy_d5_eval(
        game_phase=False, phase=PHASE_SCENARIO_A, cycle_index=2, n_targets=1
    )
    assert mix.run_healthy_d5_eval(
        game_phase=False,
        phase=PHASE_SCENARIO_B,
        cycle_index=1 + HEALTHY_D5_EVAL_EVERY_CYCLES,
        n_targets=1,
    )
    assert not mix.run_healthy_d5_eval(
        game_phase=True, phase=PHASE_SCENARIO_B, cycle_index=1, n_targets=1
    )
    assert not mix.run_healthy_d5_eval(
        game_phase=False, phase=PHASE_PROFILES, cycle_index=1, n_targets=1
    )


def test_full_game_eval_games_off_cadence_are_zero() -> None:
    mix = TrainMix()
    assert mix.full_game_eval_games(cycle_index=2, n_targets=1) == (0, 0, 0)
    assert mix.full_game_eval_games(cycle_index=1, n_targets=1) == (200, 200, 200)
