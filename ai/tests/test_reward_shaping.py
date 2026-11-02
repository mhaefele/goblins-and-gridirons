"""Unit tests for empty-EZ slap + spread covering-radius shaping."""

from __future__ import annotations

from dataclasses import replace

from gridiron_ai.constants import PLAY_HEIGHT, PLAY_WIDTH
from gridiron_ai.reward import PROFILE_SHAPING, covering_radius, shaping_reward


def _coord(x: int, y: int) -> dict:
    return {"x": x, "y": y}


def _player(
    *,
    player_id: str,
    player_index: int,
    team_rel: str,
    coordinate: dict | None,
) -> dict:
    return {
        "playerId": player_id,
        "playerIndex": player_index,
        "teamRel": team_rel,
        "coordinate": coordinate,
    }


def _obs(
    *,
    ball: tuple[int, int] | None = None,
    us: list[tuple[int, int]] | None = None,
    us_holds: bool = False,
    them: tuple[int, int] | None = None,
    them_holds: bool = False,
    them_in_dugout: int = 0,
) -> dict:
    players: list[dict] = []
    us_coords = list(us or [])
    for i, (x, y) in enumerate(us_coords):
        players.append(
            _player(
                player_id=f"h{i}",
                player_index=i + 1,
                team_rel="US",
                coordinate=_coord(x, y),
            )
        )
    if them is not None:
        players.append(
            _player(
                player_id="a0",
                player_index=1,
                team_rel="THEM",
                coordinate=_coord(*them),
            )
        )
    for i in range(them_in_dugout):
        players.append(
            _player(
                player_id=f"a_dug{i}",
                player_index=min(i + 2, 10),
                team_rel="THEM",
                coordinate=None,
            )
        )
    ball_coordinate = None
    if us_holds and us_coords:
        ball_coordinate = _coord(*us_coords[0])
    elif them_holds and them is not None:
        ball_coordinate = _coord(*them)
    elif ball is not None:
        ball_coordinate = _coord(*ball)
    return {
        "players": players,
        "ballCoordinate": ball_coordinate,
        "scoreUs": 0,
        "scoreThem": 0,
        "turnNumber": 1,
    }


def _run_choice(x: int, y: int, *, actor: int = 1) -> dict:
    return {
        "kind": "run",
        "playerId": f"h{actor - 1}",
        "targetCoordinate": _coord(x, y),
        "potentialChallengeClaims": [],
    }


def _delta(
    before: dict,
    after: dict,
    choice: dict,
    *,
    shaping,
) -> float:
    return shaping_reward(
        rewards_before=before,
        rewards_after=after,
        turn_start_score_us=0,
        shaping=shaping,
        legal_choice=choice,
        perspective_seat="HOME",
    )


def test_empty_scoring_endzone_penalty() -> None:
    shaping = PROFILE_SHAPING["general"]
    before = _obs(ball=(6, 5), us=[(5, 4)])
    after = _obs(ball=(6, 5), us=[(5, PLAY_HEIGHT)])
    choice = _run_choice(5, PLAY_HEIGHT)
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
    before = _obs(us=[(5, 4)], us_holds=True)
    after = _obs(us=[(5, PLAY_HEIGHT)], us_holds=True)
    choice = _run_choice(5, PLAY_HEIGHT)
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
    before = _obs(us=[(1, 1), (2, 1)])
    after = _obs(us=[(1, 1), (PLAY_WIDTH, PLAY_HEIGHT)])
    assert covering_radius(after) < covering_radius(before)
    choice = _run_choice(PLAY_WIDTH, PLAY_HEIGHT, actor=2)
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
    before = _obs(us=[(1, 1), (PLAY_WIDTH, PLAY_HEIGHT)])
    after = _obs(us=[(1, 1), (2, 1)])
    assert covering_radius(after) > covering_radius(before)
    choice = _run_choice(2, 1, actor=2)
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
    clumped = _obs(us=[(1, 1), (2, 1)])
    spread_out = _obs(us=[(1, 1), (PLAY_WIDTH, PLAY_HEIGHT)])
    out = _delta(clumped, spread_out, _run_choice(PLAY_WIDTH, PLAY_HEIGHT, actor=2), shaping=shaping)
    back = _delta(spread_out, clumped, _run_choice(2, 1, actor=2), shaping=shaping)
    assert out == -back
    assert out + back == 0.0


def test_general_ignores_covering_radius() -> None:
    shaping = PROFILE_SHAPING["general"]
    before = _obs(us=[(1, 1), (2, 1)])
    after = _obs(us=[(1, 1), (PLAY_WIDTH, PLAY_HEIGHT)])
    choice = _run_choice(PLAY_WIDTH, PLAY_HEIGHT, actor=2)
    assert shaping.covering_radius_delta_bonus == 0.0
    assert _delta(before, after, choice, shaping=shaping) == _delta(
        before,
        after,
        choice,
        shaping=replace(shaping, covering_radius_delta_bonus=0.0),
    )


def test_block_shaping_joins_them_ball_via_ball_coordinate() -> None:
    before = _obs(them=(1, 1), them_holds=True)
    hit_choice = {"kind": "block", "playerId": "h0", "targetCoordinate": _coord(1, 1)}
    miss_choice = {"kind": "block", "playerId": "h0", "targetCoordinate": _coord(2, 1)}
    shaping = replace(
        PROFILE_SHAPING["defense"],
        them_lost_ball_bonus=0.0,
        them_holds_ball_step_penalty=0.0,
        them_gained_ball_penalty=0.0,
        opponent_dugout_delta_bonus=0.0,
        idle_turn_penalty=0.0,
        ball_possession_bonus=0.0,
        approach_ball_bonus=0.0,
        us_gained_ball_bonus=0.0,
        uncertain_challenge_bonus=0.0,
        ball_carrier_action_bonus=0.0,
        empty_scoring_endzone_penalty=0.0,
        any_block_bonus=0.0,
    )
    hit = _delta(before, before, hit_choice, shaping=shaping)
    miss = _delta(before, before, miss_choice, shaping=shaping)
    assert hit == shaping.block_ball_carrier_bonus
    assert miss == -shaping.block_non_carrier_penalty
