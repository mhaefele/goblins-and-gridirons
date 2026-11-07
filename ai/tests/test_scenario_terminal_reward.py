"""Coach-window scenario terminal (±0.5) + passing-move (+0.25) vs match W/L (±1)."""

from __future__ import annotations

from gridiron_ai.reward import (
    SCENARIO_PASSING_MOVE_BONUS,
    SCENARIO_TERMINAL_LOSS,
    SCENARIO_TERMINAL_WIN,
    TERMINAL_WIN,
    step_reward,
)


def _rewards(*, score_us: int = 0, score_them: int = 0) -> dict:
    return {
        "scoreUs": score_us,
        "scoreThem": score_them,
        "turnNumber": 1,
        "players": [],
        "ballCoordinate": None,
    }


def _reward(*, before: dict, after: dict, **terminal: object) -> float:
    return step_reward(
        rewards_before=before,
        rewards_after=after,
        result_kind=terminal.get("resultKind"),
        result_winner_rel=terminal.get("resultWinnerRel"),
        training_episode_ended=bool(terminal.get("trainingEpisodeEnded")),
        training_outcome=terminal.get("trainingOutcome"),
        passing_legal_move=bool(terminal.get("passing_legal_move")),
    )


def test_scenario_win_adds_half_terminal() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(
        before=before,
        after=after,
        trainingEpisodeEnded=True,
        trainingOutcome="US",
    ) == SCENARIO_TERMINAL_WIN


def test_scenario_loss_adds_half_terminal() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(
        before=before,
        after=after,
        trainingEpisodeEnded=True,
        trainingOutcome="THEM",
    ) == SCENARIO_TERMINAL_LOSS


def test_scenario_distracted_adds_no_terminal() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(
        before=before,
        after=after,
        trainingEpisodeEnded=True,
        trainingOutcome="DISTRACTED",
    ) == 0.0


def test_match_win_not_stacked_with_training_outcome() -> None:
    before = _rewards()
    after = _rewards(score_us=4)
    assert _reward(
        before=before,
        after=after,
        resultKind="TOUCHDOWN",
        resultWinnerRel="US",
        trainingEpisodeEnded=False,
        trainingOutcome="US",
    ) == 4.0 + TERMINAL_WIN


def test_no_scenario_terminal_without_coach_window_flag() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(before=before, after=after, trainingOutcome="US") == 0.0


def test_passing_legal_move_adds_bonus() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(before=before, after=after, passing_legal_move=True) == SCENARIO_PASSING_MOVE_BONUS


def test_passing_legal_stacks_with_coach_window_win() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(
        before=before,
        after=after,
        trainingEpisodeEnded=True,
        trainingOutcome="US",
        passing_legal_move=True,
    ) == SCENARIO_PASSING_MOVE_BONUS + SCENARIO_TERMINAL_WIN


def test_non_passing_move_gets_no_bonus() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(before=before, after=after, passing_legal_move=False) == 0.0
