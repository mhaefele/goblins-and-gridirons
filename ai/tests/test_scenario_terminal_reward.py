"""Scenario curriculum: PASS-edge +0.5, window-loss −0.5, passing-move +0.25."""

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
        training_outcome=terminal.get("trainingOutcome"),  # type: ignore[arg-type]
        training_outcome_before=terminal.get("training_outcome_before"),  # type: ignore[arg-type]
        passing_legal_move=bool(terminal.get("passing_legal_move")),
    )


def test_pass_edge_adds_half_without_episode_end() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(
        before=before,
        after=after,
        trainingOutcome="US",
        training_outcome_before=None,
    ) == SCENARIO_TERMINAL_WIN


def test_pass_edge_not_double_counted_on_later_steps() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(
        before=before,
        after=after,
        trainingOutcome="US",
        training_outcome_before="US",
        trainingEpisodeEnded=True,
    ) == 0.0


def test_pass_edge_and_window_end_same_submit_credits_once() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(
        before=before,
        after=after,
        trainingEpisodeEnded=True,
        trainingOutcome="US",
        training_outcome_before=None,
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
        training_outcome_before=None,
    ) == 4.0 + SCENARIO_TERMINAL_WIN + TERMINAL_WIN


def test_no_scenario_win_without_outcome_edge() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(before=before, after=after, trainingOutcome="US", training_outcome_before="US") == 0.0


def test_passing_legal_move_adds_bonus() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(before=before, after=after, passing_legal_move=True) == SCENARIO_PASSING_MOVE_BONUS


def test_passing_legal_stacks_with_pass_edge() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(
        before=before,
        after=after,
        trainingOutcome="US",
        training_outcome_before=None,
        passing_legal_move=True,
    ) == SCENARIO_PASSING_MOVE_BONUS + SCENARIO_TERMINAL_WIN


def test_non_passing_move_gets_no_bonus() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(before=before, after=after, passing_legal_move=False) == 0.0
