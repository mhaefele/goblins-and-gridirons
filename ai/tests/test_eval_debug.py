"""Tests for scenario-eval action label formatting."""

from __future__ import annotations

from gridiron_ai.constants import PLAY_HEIGHT
from gridiron_ai.eval_debug import ScenarioEvalDebug, _format_expected_moves


def _rewards(*, players: list[dict], ball: dict | None = None) -> dict:
    return {"players": players, "ballCoordinate": ball}


def test_format_action_joins_actor_via_player_id() -> None:
    choice = {
        "kind": "throw",
        "playerId": "h0",
        "targetCoordinate": {"x": 4, "y": 14},
    }
    rewards = _rewards(
        players=[
            {
                "playerId": "h0",
                "playerIndex": 1,
                "teamRel": "US",
                "coordinate": {"x": 1, "y": 13},
            }
        ]
    )
    assert ScenarioEvalDebug.format_action(choice, rewards=rewards) == "throw(1,13->4,14)"


def test_format_action_throw_annotates_ball_when_off_destination() -> None:
    choice = {
        "kind": "throw",
        "playerId": "h0",
        "targetCoordinate": {"x": 7, "y": 15},
    }
    before = _rewards(
        players=[
            {
                "playerId": "h0",
                "playerIndex": 1,
                "teamRel": "US",
                "coordinate": {"x": 1, "y": 10},
            }
        ],
        ball={"x": 1, "y": 10},
    )
    after_miss = _rewards(players=before["players"], ball={"x": 2, "y": 14})
    after_catch = _rewards(players=before["players"], ball={"x": 7, "y": 15})
    assert (
        ScenarioEvalDebug.format_action(choice, rewards=before, rewards_after=after_miss)
        == "throw(1,10->7,15)(ball->2,14)"
    )
    assert (
        ScenarioEvalDebug.format_action(choice, rewards=before, rewards_after=after_catch)
        == "throw(1,10->7,15)"
    )


def test_format_action_marks_expected_scored_and_card_suffixes() -> None:
    choice = {"kind": "run"}
    assert ScenarioEvalDebug.format_action(choice, expected_move=True) == "run*"
    assert ScenarioEvalDebug.format_action(choice, scored_touchdown=True) == "run!"
    assert ScenarioEvalDebug.format_action(choice, acquired_card=True) == "run+"
    assert ScenarioEvalDebug.format_action(choice, expected_move=True, scored_touchdown=True) == "run*!"
    assert ScenarioEvalDebug.format_action(choice, expected_move=True, acquired_card=True) == "run*+"


def test_format_expected_moves_shown_for_one_or_two_flags() -> None:
    rewards = _rewards(
        players=[
            {
                "playerId": "h0",
                "playerIndex": 1,
                "teamRel": "US",
                "coordinate": {"x": 2, "y": 3},
            }
        ]
    )
    run = {
        "action": {"kind": "run", "playerId": "h0", "targetCoordinate": {"x": 2, "y": PLAY_HEIGHT}},
        "softMaskLegal": False,
        "passingLegal": True,
    }
    block = {
        "action": {"kind": "block", "playerId": "h0", "targetCoordinate": {"x": 3, "y": 3}},
        "softMaskLegal": False,
        "passingLegal": True,
    }

    one = _format_expected_moves(
        rewards=rewards, legal_row_meta=[run, block], passing_legal=[True, False]
    )
    assert one == (1, ["run(2,3->2,15)"])

    two = _format_expected_moves(
        rewards=rewards, legal_row_meta=[run, block], passing_legal=[True, True]
    )
    assert two == (2, ["run(2,3->2,15)", "block(2,3->3,3)"])

    many = _format_expected_moves(
        rewards=rewards, legal_row_meta=[run, block, block], passing_legal=[True, True, True]
    )
    assert many == (3, ["run(2,3->2,15)", "block(2,3->3,3)", "block(2,3->3,3)"])

    none = _format_expected_moves(
        rewards=rewards, legal_row_meta=[run, block], passing_legal=[False, False]
    )
    assert none is None


def test_turn1_log_suffix_includes_expected_moves() -> None:
    suffix = ScenarioEvalDebug.turn1_log_suffix(
        {
            "first_action_legal_count": 12,
            "expected_move_count": 1,
            "expected_moves": ["R(2,3->2,15)"],
            "first_turn_actions": ["R(2,3->3,4)*", "R(2,3->4,4)"],
        }
    )
    assert "expected=R(2,3->2,15)" in suffix
    assert "turn1=R(2,3->3,4)* R(2,3->4,4)" in suffix

    many_suffix = ScenarioEvalDebug.turn1_log_suffix(
        {
            "expected_move_count": 47,
            "expected_moves": ["R(7,2->7,3)", "R(8,3->9,2)", "R(9,1->9,2)"],
        }
    )
    assert "expected=47 R(7,2->7,3) R(8,3->9,2)" in many_suffix
    assert "R(9,1->9,2)" not in many_suffix


def test_turn1_log_suffix_annotates_rank_and_p() -> None:
    suffix = ScenarioEvalDebug.turn1_log_suffix(
        {
            "expected_move_count": 1,
            "expected_moves": ["run(6,13->5,14)"],
            "first_turn_actions": ["run(6,13->10,15)", "reserves(p1)"],
            "first_turn_step_rewards": [0.12, -1.0],
            "opening_move_scores": {
                "chosen": {"rank": 1, "p": 0.42, "logit": 1.5},
                "expected": [{"rank": 52, "p": 0.001, "logit": -2.0}],
            },
        }
    )
    assert "expected=run(6,13->5,14) [rank=52 p=0.001]" in suffix
    assert (
        "turn1 [reward=-0.88]=run(6,13->10,15) [rank=1 p=0.420 reward=+0.12] "
        "reserves(p1) [reward=-1]"
    ) in suffix


def test_turn1_log_suffix_orders_expected_by_best_rank() -> None:
    suffix = ScenarioEvalDebug.turn1_log_suffix(
        {
            "expected_move_count": 2,
            "expected_moves": ["run(1,13->6,15)", "run(1,13->4,15)"],
            "opening_move_scores": {
                "expected": [
                    {"rank": 38, "p": 0.007},
                    {"rank": 33, "p": 0.007},
                ],
            },
        }
    )
    assert (
        "expected=run(1,13->4,15) [rank=33 p=0.007] run(1,13->6,15) [rank=38 p=0.007]"
        in suffix
    )

    many = ScenarioEvalDebug.turn1_log_suffix(
        {
            "expected_move_count": 3,
            "expected_moves": [
                "run(1,13->6,15)",
                "run(1,13->4,15)",
                "run(1,13->2,15)",
            ],
            "opening_move_scores": {
                "expected": [
                    {"rank": 38, "p": 0.007},
                    {"rank": 33, "p": 0.007},
                    {"rank": 90, "p": 0.001},
                ],
            },
        }
    )
    assert (
        "expected=3 run(1,13->4,15) [rank=33 p=0.007] run(1,13->6,15) [rank=38 p=0.007]"
        in many
    )
    assert "run(1,13->2,15)" not in many


def test_describe_satisfied_claim_on_attached_claim() -> None:
    from gridiron_ai.eval_debug import _describe_satisfied_claim

    choice = {
        "kind": "run",
        "potentialChallengeClaims": [
            {"claimCondition": "PUSH_FORWARD", "challengeTitle": "Push Forward", "certainty": "CERTAIN"}
        ],
    }
    assert _describe_satisfied_claim(choice, action_label="R(2,3->3,4)+") == "Push Forward(R(2,3->3,4)+)"


def test_format_scenario_draw_log_block() -> None:
    debug = ScenarioEvalDebug()
    summary = {
        "result_winner_rel": "US",
        "score_us": 2,
        "score_them": 0,
        "scenario_initial_ball": "4,7",
        "scenario_us_positions": "2,3",
        "scenario_available_cards": "Grab It(2pts) slot1=empty slot2=empty",
        "first_turn_actions": ["R(2,3->3,4)+"],
        "scenario_satisfied_claims": ["Push Forward(R(2,3->3,4)+)"],
    }
    block = debug.format_scenario_draw_log(
        scenario="CLAIM_PUSH_FORWARD",
        difficulty=2,
        episode_mode="EVAL",
        summary=summary,
    )
    assert "scenario=CLAIM_PUSH_FORWARD d=2 mode=EVAL outcome=US" in block
    assert "ball=4,7 us=2,3" in block
    assert "cards=Grab It(2pts)" in block
    assert "turn1=R(2,3->3,4)+" in block
    assert "claimed=Push Forward(R(2,3->3,4)+)" in block
