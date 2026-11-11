"""CLI train/eval mix knobs + cadence.

Frozen config built once from argparse — same shape as ``RewardConfig.for_profile``:
classmethod factory, then pass the object instead of re-reading ``args`` for budgets.
Answers how much train/eval to run this cycle (and whether cadence gates fire).

Curriculum *policy* (soft-d5, multipliers, difficulty bump/demote) stays in
``scenario_curriculum`` — this module only holds CLI amounts and when gates fire.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass

from gridiron_ai.scenario_curriculum import (
    DEFAULT_SCENARIO_TRAIN_LEARNING,
    DEFAULT_SCENARIO_TRAIN_MASTERED,
    full_game_train_timesteps,
    train_multiplier_groups,
    train_timesteps_for_difficulty,
    train_timesteps_for_scenario,
)
from gridiron_ai.training_phases import PHASE_SCENARIO_A, PHASE_SCENARIO_B, PhaseName

# Full-game eval every N visits per profile (rotation round). eval-only always runs.
# With --profile all, global cycle%N would only ever hit targets[0] (baseline).
FULL_GAME_EVAL_EVERY_CYCLES = 4
# Climbing (scenario_a / scenario_b): healthy d5 drift-eval every N cycles while others train.
# Game phase already evals all drills each cycle.
HEALTHY_D5_EVAL_EVERY_CYCLES = 2

DEFAULT_TRAIN_HEURISTIC = 20_000
DEFAULT_TRAIN_LOOKAHEAD = 20_000
DEFAULT_TRAIN_ONNX = 20_000
DEFAULT_EVAL_HEURISTIC = 200
DEFAULT_EVAL_LOOKAHEAD = 200
DEFAULT_EVAL_ONNX = 200


@dataclass(frozen=True)
class TrainMix:
    """Everything the cycle driver asks about train/eval *amounts* and cadence.

    Built once via [from_args] after CLI parse (applies eval-only / skip / scenario-pin
    zeroing without mutating the namespace). Pass this through the loop — not ``args`` —
    when the question is “do we run X / how many?”.
    """

    scenario_learning: int = DEFAULT_SCENARIO_TRAIN_LEARNING
    scenario_mastered: int = DEFAULT_SCENARIO_TRAIN_MASTERED
    train_heuristic: int = DEFAULT_TRAIN_HEURISTIC
    train_lookahead: int = DEFAULT_TRAIN_LOOKAHEAD
    train_onnx: int = DEFAULT_TRAIN_ONNX
    eval_heuristic: int = DEFAULT_EVAL_HEURISTIC
    eval_lookahead: int = DEFAULT_EVAL_LOOKAHEAD
    eval_onnx: int = DEFAULT_EVAL_ONNX
    eval_only: bool = False
    skip_scenarios: bool = False
    full_game_eval_every: int = FULL_GAME_EVAL_EVERY_CYCLES
    healthy_d5_eval_every: int = HEALTHY_D5_EVAL_EVERY_CYCLES

    @classmethod
    def from_args(cls, args: argparse.Namespace) -> TrainMix:
        """Snapshot CLI mix knobs; apply pin / skip / eval-only zeroing here."""
        scenario_learning = int(args.train_scenario_learning)
        scenario_mastered = int(args.train_scenario_mastered)
        train_heuristic = int(args.train_heuristic)
        train_lookahead = int(args.train_lookahead)
        train_onnx = int(args.train_onnx)
        eval_heuristic = int(args.eval_heuristic)
        eval_lookahead = int(args.eval_lookahead)
        eval_onnx = int(args.eval_onnx)
        eval_only = bool(args.eval_only)
        skip_scenarios = bool(args.skip_scenarios)

        if args.scenario is not None:
            train_heuristic = 0
            train_lookahead = 0
            train_onnx = 0
            eval_heuristic = 0
            eval_lookahead = 0
            eval_onnx = 0
        if skip_scenarios:
            scenario_learning = 0
            scenario_mastered = 0
        if eval_only:
            scenario_learning = 0
            scenario_mastered = 0
            train_heuristic = 0
            train_lookahead = 0
            train_onnx = 0

        return cls(
            scenario_learning=scenario_learning,
            scenario_mastered=scenario_mastered,
            train_heuristic=train_heuristic,
            train_lookahead=train_lookahead,
            train_onnx=train_onnx,
            eval_heuristic=eval_heuristic,
            eval_lookahead=eval_lookahead,
            eval_onnx=eval_onnx,
            eval_only=eval_only,
            skip_scenarios=skip_scenarios,
        )

    def has_work(self) -> bool:
        """True when any train/eval amount is positive, or eval-only (stdout pass)."""
        if self.eval_only:
            return True
        return any(
            n > 0
            for n in (
                self.scenario_learning,
                self.scenario_mastered,
                self.train_heuristic,
                self.train_lookahead,
                self.train_onnx,
                self.eval_heuristic,
                self.eval_lookahead,
                self.eval_onnx,
            )
        )

    @property
    def update_curriculum(self) -> bool:
        """Train runs may bump/demote; standalone ``--eval-only`` must not."""
        return not self.eval_only

    def skip_scenario_train(self) -> bool:
        return self.scenario_learning <= 0 and self.scenario_mastered <= 0

    def skip_scenario_eval(self) -> bool:
        """``--skip-scenarios`` always skips train; also skips eval in ``--eval-only``."""
        return self.skip_scenarios and self.eval_only

    @staticmethod
    def visit_round(cycle_index: int, n_targets: int) -> int:
        return (cycle_index - 1) // max(n_targets, 1)

    def run_full_game_eval(self, *, cycle_index: int, n_targets: int) -> bool:
        if self.eval_only:
            return True
        return self.visit_round(cycle_index, n_targets) % self.full_game_eval_every == 0

    def run_healthy_d5_eval(
        self,
        *,
        game_phase: bool,
        phase: PhaseName,
        cycle_index: int,
        n_targets: int,
    ) -> bool:
        """Climbing scenario_a/b only — game phase already evals all drills each cycle."""
        if game_phase or phase not in (PHASE_SCENARIO_A, PHASE_SCENARIO_B):
            return False
        return self.visit_round(cycle_index, n_targets) % self.healthy_d5_eval_every == 0

    def full_game_eval_games(
        self, *, cycle_index: int, n_targets: int
    ) -> tuple[int, int, int]:
        """``(heuristic, lookahead, onnx)`` games this cycle (0s when off cadence)."""
        if not self.run_full_game_eval(cycle_index=cycle_index, n_targets=n_targets):
            return 0, 0, 0
        return self.eval_heuristic, self.eval_lookahead, self.eval_onnx

    def next_full_game_eval_cycle(self, *, cycle_index: int, n_targets: int) -> int:
        round_i = self.visit_round(cycle_index, n_targets)
        every = max(1, self.full_game_eval_every)
        next_round = round_i + (every - round_i % every)
        return next_round * max(n_targets, 1) + 1

    def next_healthy_d5_eval_cycle(self, *, cycle_index: int, n_targets: int) -> int:
        round_i = self.visit_round(cycle_index, n_targets)
        every = max(1, self.healthy_d5_eval_every)
        next_round = round_i + (every - round_i % every)
        return next_round * max(n_targets, 1) + 1

    def effective_full_game_train(
        self,
        base: int,
        levels: dict[str, int],
        scenario_names: list[str],
        gate_failures: dict[str, dict[str, int]] | None = None,
        *,
        game_phase: bool,
    ) -> int:
        return full_game_train_timesteps(
            base,
            levels,
            scenario_names,
            gate_failures,
            game_phase_unlocked=game_phase,
        )

    def scenario_train_timesteps(
        self,
        scenario: str,
        levels: dict[str, int],
        scenario_names: list[str],
        *,
        game_phase: bool = False,
        gate_failures: dict[str, dict[str, int]] | None = None,
        drill_stats: dict[str, dict[str, int]] | None = None,
    ) -> int:
        return train_timesteps_for_scenario(
            scenario,
            levels,
            scenario_names,
            learning=self.scenario_learning,
            mastered=self.scenario_mastered,
            game_phase=game_phase,
            gate_failures=gate_failures,
            drill_stats=drill_stats,
        )

    def scenario_train_base_timesteps(
        self,
        difficulty: int,
        *,
        game_phase: bool = False,
    ) -> int:
        return train_timesteps_for_difficulty(
            difficulty,
            learning=self.scenario_learning,
            mastered=self.scenario_mastered,
            game_phase=game_phase,
        )

    def scenario_train_multiplier_groups(
        self,
        levels: dict[str, int],
        scenario_names: list[str],
        gate_failures: dict[str, dict[str, int]],
        drill_stats: dict[str, dict[str, int]],
        *,
        game_phase: bool,
    ) -> dict[int, list[str]]:
        return train_multiplier_groups(
            levels,
            scenario_names,
            gate_failures,
            drill_stats,
            learning=self.scenario_learning,
            mastered=self.scenario_mastered,
            game_phase=game_phase,
        )
