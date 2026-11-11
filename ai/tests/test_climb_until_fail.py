"""Expert floor probe: climb d0… until fail; leave level at the frontier."""

from __future__ import annotations

from catalog_fixture import install_fixture_catalog
from gridiron_ai.scenario_curriculum import (
    MAX_DIFFICULTY,
    SCENARIO_EVAL_GAMES,
    climb_until_fail_levels,
)


def _stats(wins: int, *, episodes: int = SCENARIO_EVAL_GAMES) -> dict[str, int]:
    return {"episodes": episodes, "wins": wins}


def test_climb_stops_at_first_failing_difficulty() -> None:
    install_fixture_catalog()

    def eval_at(_scenario: str, difficulty: int) -> dict[str, int]:
        # Pass d0–d1 (≥18/20), fail d2.
        return _stats(SCENARIO_EVAL_GAMES if difficulty < 2 else 3)

    levels = climb_until_fail_levels(["TD_RUN"], eval_at)
    assert levels == {"TD_RUN": 2}


def test_climb_all_pass_leaves_max() -> None:
    install_fixture_catalog()

    def eval_at(_scenario: str, difficulty: int) -> dict[str, int]:
        return _stats(SCENARIO_EVAL_GAMES)

    levels = climb_until_fail_levels(["MARKED_RUN"], eval_at)
    assert levels == {"MARKED_RUN": MAX_DIFFICULTY}


def test_climb_fail_at_d0_stays_zero() -> None:
    install_fixture_catalog()

    def eval_at(_scenario: str, _difficulty: int) -> dict[str, int]:
        return _stats(0)

    levels = climb_until_fail_levels(["LOOSE_BALL_RUN"], eval_at)
    assert levels == {"LOOSE_BALL_RUN": 0}
