"""Scenario curriculum: PASS-edge +0.5, window-loss −0.5, passing-move / opening mispick."""

from __future__ import annotations

from gridiron_ai.model.reward import (
    SCENARIO_OPENING_NON_PASSING_PENALTY,
    SCENARIO_OPENING_NON_PASSING_PENALTY_D0_D2,
    SCENARIO_PASSING_MOVE_BONUS,
    SCENARIO_PASSING_MOVE_BONUS_D0_D2,
    SCENARIO_TERMINAL_LOSS,
    SCENARIO_TERMINAL_WIN,
    TERMINAL_WIN,
    RewardConfig,
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


def _reward(*, before: dict, after: dict, config: RewardConfig | None = None, **terminal: object) -> float:
    # TODO: flatten kwargs mirror step_reward — collapse when step_reward takes structs.
    return step_reward(
        rewards_before=before,
        rewards_after=after,
        result_kind=terminal.get("resultKind"),
        result_winner_rel=terminal.get("resultWinnerRel"),
        training_episode_ended=bool(terminal.get("trainingEpisodeEnded")),
        training_outcome=terminal.get("trainingOutcome"),  # type: ignore[arg-type]
        training_outcome_before=terminal.get("training_outcome_before"),  # type: ignore[arg-type]
        passing_legal_move=bool(terminal.get("passing_legal_move")),
        scenario_opening_non_passing=bool(terminal.get("scenario_opening_non_passing")),
        scenario_difficulty=terminal.get("scenario_difficulty"),  # type: ignore[arg-type]
        config=config,
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


def test_passing_legal_d0_d2_uses_louder_bonus() -> None:
    before = _rewards()
    after = _rewards()
    assert (
        _reward(before=before, after=after, passing_legal_move=True, scenario_difficulty=0)
        == SCENARIO_PASSING_MOVE_BONUS_D0_D2
    )
    assert (
        _reward(before=before, after=after, passing_legal_move=True, scenario_difficulty=2)
        == SCENARIO_PASSING_MOVE_BONUS_D0_D2
    )
    assert (
        _reward(before=before, after=after, passing_legal_move=True, scenario_difficulty=3)
        == SCENARIO_PASSING_MOVE_BONUS
    )


def test_passing_legal_stacks_with_pass_edge() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(
        before=before,
        after=after,
        trainingOutcome="US",
        training_outcome_before=None,
        passing_legal_move=True,
        scenario_difficulty=1,
    ) == SCENARIO_PASSING_MOVE_BONUS_D0_D2 + SCENARIO_TERMINAL_WIN


def test_non_passing_move_gets_no_bonus() -> None:
    before = _rewards()
    after = _rewards()
    assert _reward(before=before, after=after, passing_legal_move=False) == 0.0


def test_scenario_opening_non_passing_penalty() -> None:
    before = _rewards()
    after = _rewards()
    assert (
        _reward(before=before, after=after, scenario_opening_non_passing=True)
        == -SCENARIO_OPENING_NON_PASSING_PENALTY
    )


def test_opening_non_passing_d0_d2_uses_louder_penalty() -> None:
    before = _rewards()
    after = _rewards()
    assert (
        _reward(
            before=before,
            after=after,
            scenario_opening_non_passing=True,
            scenario_difficulty=1,
        )
        == -SCENARIO_OPENING_NON_PASSING_PENALTY_D0_D2
    )


def test_opening_non_passing_stacks_with_window_loss() -> None:
    before = _rewards()
    after = _rewards()
    assert (
        _reward(
            before=before,
            after=after,
            trainingEpisodeEnded=True,
            trainingOutcome="THEM",
            scenario_opening_non_passing=True,
            scenario_difficulty=0,
        )
        == -SCENARIO_OPENING_NON_PASSING_PENALTY_D0_D2 + SCENARIO_TERMINAL_LOSS
    )


def test_curriculum_scale_multiplies_opening_and_grade() -> None:
    """Optional env scale still multiplies curriculum ± (train leaves scale at 1.0)."""
    before = _rewards()
    after = _rewards()
    scale = 4.0
    cfg = RewardConfig(curriculum_scale=scale)
    assert _reward(
        before=before,
        after=after,
        passing_legal_move=True,
        trainingOutcome="US",
        training_outcome_before=None,
        scenario_difficulty=0,
        config=cfg,
    ) == (SCENARIO_PASSING_MOVE_BONUS_D0_D2 + SCENARIO_TERMINAL_WIN) * scale
    assert _reward(
        before=before,
        after=after,
        scenario_opening_non_passing=True,
        trainingEpisodeEnded=True,
        trainingOutcome="THEM",
        scenario_difficulty=0,
        config=cfg,
    ) == (-SCENARIO_OPENING_NON_PASSING_PENALTY_D0_D2 + SCENARIO_TERMINAL_LOSS) * scale


def test_proxy_score_only_zeros_curriculum_and_terminals() -> None:
    """Difficulty-proxy train: RewardConfig.score_only → score-margin (+ shaping) only."""
    before = _rewards(score_us=0, score_them=0)
    after = _rewards(score_us=1, score_them=0)
    cfg = RewardConfig().score_only()
    assert _reward(
        before=before,
        after=after,
        passing_legal_move=True,
        trainingOutcome="US",
        training_outcome_before=None,
        scenario_difficulty=0,
        config=cfg,
        resultKind="COMPLETED",
        resultWinnerRel="US",
    ) == 1.0
    assert _reward(
        before=_rewards(),
        after=_rewards(),
        scenario_opening_non_passing=True,
        trainingEpisodeEnded=True,
        trainingOutcome="THEM",
        scenario_difficulty=0,
        config=cfg,
    ) == 0.0
