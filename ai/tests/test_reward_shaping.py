"""Unit tests for empty-EZ slap + spread covering-radius shaping."""

from __future__ import annotations

from dataclasses import replace

from gridiron_ai.constants import PLAY_HEIGHT, PLAY_WIDTH, PITCH_SQUARE_COUNT
from gridiron_ai.reward import PROFILE_SHAPING, covering_radius, shaping_reward


def _empty_cell(*, has_ball: bool = False, player: dict | None = None) -> dict:
    return {"hasBall": has_ball, "player": player}


def _us_player(**kwargs: object) -> dict:
    base = {
        "teamRel": 0,
        "status": 0,
        "turnEnded": False,
        "move": 4,
        "blockDice": 1,
        "throwStat": 3,
        "armour": 4,
        "ability": None,
        "canThrowTeammate": False,
        "playerIndex": 1,
    }
    base.update(kwargs)
    return base


def _obs(
    *,
    ball_index: int | None = None,
    us_at: int | None = None,
    us_holds: bool = False,
    us_at_list: list[int] | None = None,
) -> dict:
    cells = [_empty_cell() for _ in range(PITCH_SQUARE_COUNT + 20)]
    if ball_index is not None and not us_holds:
        cells[ball_index] = _empty_cell(has_ball=True)
    positions = list(us_at_list) if us_at_list is not None else ([] if us_at is None else [us_at])
    for i, pitch in enumerate(positions):
        holds = us_holds and i == 0
        cells[pitch] = _empty_cell(has_ball=holds, player=_us_player(playerIndex=i + 1))
    return {"cells": cells, "scoreUs": 0, "scoreThem": 0, "turnNumber": 1}


def _run_choice(board_index: int, *, actor: int = 1) -> dict:
    return {
        "kind": "run",
        "kindIndex": 0,
        "factors": [{"kind": "actor", "playerIndex": actor}, {"kind": "boardTarget", "boardIndex": board_index}],
    }


def _delta(before: dict, after: dict, choice: dict, *, shaping) -> float:
    return shaping_reward(
        rewards_before=before,
        rewards_after=after,
        turn_start_score_us=0,
        shaping=shaping,
        legal_choice=choice,
    )


def test_empty_scoring_endzone_penalty() -> None:
    shaping = PROFILE_SHAPING["general"]
    scoring_ez = (PLAY_HEIGHT - 1) * PLAY_WIDTH + 4
    before = _obs(ball_index=50, us_at=40)
    after = _obs(ball_index=50, us_at=scoring_ez)
    choice = _run_choice(scoring_ez)
    with_slap = _delta(before, after, choice, shaping=shaping)
    without = _delta(
        before,
        after,
        choice,
        shaping=replace(shaping, empty_scoring_endzone_penalty=0.0),
    )
    assert with_slap == without - shaping.empty_scoring_endzone_penalty


def test_no_empty_ez_penalty_when_holding() -> None:
    shaping = PROFILE_SHAPING["general"]
    scoring_ez = (PLAY_HEIGHT - 1) * PLAY_WIDTH + 4
    before = _obs(us_at=40, us_holds=True)
    after = _obs(us_at=scoring_ez, us_holds=True)
    choice = _run_choice(scoring_ez)
    with_slap = _delta(before, after, choice, shaping=shaping)
    without = _delta(
        before,
        after,
        choice,
        shaping=replace(shaping, empty_scoring_endzone_penalty=0.0),
    )
    assert with_slap == without


def test_spread_rewards_covering_radius_shrink() -> None:
    shaping = PROFILE_SHAPING["spread"]
    # Clump in one corner → peel a player toward the opposite corner (coverage improves).
    clump_a = 0
    clump_b = 1
    far = PITCH_SQUARE_COUNT - 1
    before = _obs(us_at_list=[clump_a, clump_b])
    after = _obs(us_at_list=[clump_a, far])
    assert covering_radius(after) < covering_radius(before)
    choice = _run_choice(far, actor=2)
    with_cover = _delta(before, after, choice, shaping=shaping)
    without = _delta(
        before,
        after,
        choice,
        shaping=replace(shaping, covering_radius_delta_bonus=0.0),
    )
    drop = covering_radius(before) - covering_radius(after)
    assert with_cover == without + shaping.covering_radius_delta_bonus * drop


def test_spread_penalizes_covering_radius_grow() -> None:
    shaping = PROFILE_SHAPING["spread"]
    far = PITCH_SQUARE_COUNT - 1
    before = _obs(us_at_list=[0, far])
    after = _obs(us_at_list=[0, 1])
    assert covering_radius(after) > covering_radius(before)
    choice = _run_choice(1, actor=2)
    with_cover = _delta(before, after, choice, shaping=shaping)
    without = _delta(
        before,
        after,
        choice,
        shaping=replace(shaping, covering_radius_delta_bonus=0.0),
    )
    grow = covering_radius(after) - covering_radius(before)
    assert with_cover == without - shaping.covering_radius_delta_bonus * grow


def test_spread_round_trip_nets_zero_cover() -> None:
    shaping = replace(
        PROFILE_SHAPING["spread"],
        idle_turn_penalty=0.0,
        ball_possession_bonus=0.0,
        approach_ball_bonus=0.0,
        us_gained_ball_bonus=0.0,
        uncertain_challenge_bonus=0.0,
        them_lost_ball_bonus=0.0,
        any_block_bonus=0.0,
        block_ball_carrier_bonus=0.0,
        ball_carrier_action_bonus=0.0,
        empty_scoring_endzone_penalty=0.0,
    )
    far = PITCH_SQUARE_COUNT - 1
    clumped = _obs(us_at_list=[0, 1])
    spread_out = _obs(us_at_list=[0, far])
    out = _delta(clumped, spread_out, _run_choice(far, actor=2), shaping=shaping)
    back = _delta(spread_out, clumped, _run_choice(1, actor=2), shaping=shaping)
    assert out == -back
    assert out + back == 0.0


def test_general_ignores_covering_radius() -> None:
    shaping = PROFILE_SHAPING["general"]
    before = _obs(us_at_list=[0, 1])
    after = _obs(us_at_list=[0, PITCH_SQUARE_COUNT - 1])
    choice = _run_choice(PITCH_SQUARE_COUNT - 1, actor=2)
    assert shaping.covering_radius_delta_bonus == 0.0
    assert _delta(before, after, choice, shaping=shaping) == _delta(
        before,
        after,
        choice,
        shaping=replace(shaping, covering_radius_delta_bonus=0.0),
    )
