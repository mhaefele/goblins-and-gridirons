"""Tests for scenario-eval action label formatting."""

from __future__ import annotations

import numpy as np

from gridiron_ai.constants import PLAY_HEIGHT, PLAY_WIDTH, PITCH_SQUARE_COUNT
from gridiron_ai.eval_debug import ScenarioEvalDebug, _format_expected_moves


def _cell(*, player: dict | None = None, terrain: str = "FIELD") -> dict:
    return {"hasBall": False, "player": player, "terrain": terrain}


def _pitch(x: int, y: int) -> int:
    return (y - 1) * PLAY_WIDTH + (x - 1)


def test_format_action_prefers_us_when_roster_index_collides() -> None:
    """playerIndex is per-team; THEM earlier on the board must not steal the source square."""
    cells = [_cell() for _ in range(PITCH_SQUARE_COUNT)]
    cells[_pitch(1, 7)] = _cell(player={"teamRel": 1, "playerIndex": 1})
    cells[_pitch(1, 13)] = _cell(player={"teamRel": 0, "playerIndex": 1})
    obs = {"cells": cells}
    choice = {
        "kind": "throw",
        "factors": [
            {"kind": "actor", "playerIndex": 1},
            {"kind": "boardTarget", "boardIndex": _pitch(4, 14)},
        ],
    }
    assert ScenarioEvalDebug.format_action(choice, observation=obs) == "throw(1,13->4,14)"


def test_format_action_marks_expected_scored_and_card_suffixes() -> None:
    choice = {"kind": "run", "factors": []}
    assert ScenarioEvalDebug.format_action(choice, expected_move=True) == "run*"
    assert ScenarioEvalDebug.format_action(choice, scored_touchdown=True) == "run!"
    assert ScenarioEvalDebug.format_action(choice, acquired_card=True) == "run+"
    assert ScenarioEvalDebug.format_action(choice, expected_move=True, scored_touchdown=True) == "run*!"
    assert ScenarioEvalDebug.format_action(choice, expected_move=True, acquired_card=True) == "run*+"


def test_format_expected_moves_shown_for_one_or_two_flags() -> None:
    cells = [_cell() for _ in range(PITCH_SQUARE_COUNT)]
    cells[_pitch(2, 3)] = _cell(player={"teamRel": 0, "playerIndex": 0})
    obs = {"cells": cells}
    td_target = _pitch(2, PLAY_HEIGHT)
    run = {
        "kind": "run",
        "factors": [
            {"kind": "actor", "playerIndex": 0},
            {"kind": "boardTarget", "boardIndex": td_target},
        ],
    }
    block = {"kind": "block", "factors": [{"kind": "actor", "playerIndex": 0}, {"kind": "boardTarget", "boardIndex": _pitch(3, 3)}]}

    one = _format_expected_moves(obs, policy_seat="HOME", legal_row_meta=[run, block], passing_legal=[True, False])
    assert one == (1, ["run(2,3->2,15)"])

    two = _format_expected_moves(obs, policy_seat="HOME", legal_row_meta=[run, block], passing_legal=[True, True])
    assert two == (2, ["run(2,3->2,15)", "block(2,3->3,3)"])

    many = _format_expected_moves(obs, policy_seat="HOME", legal_row_meta=[run, block, block], passing_legal=[True, True, True])
    assert many == (3, ["run(2,3->2,15)", "block(2,3->3,3)"])

    none = _format_expected_moves(obs, policy_seat="HOME", legal_row_meta=[run, block], passing_legal=[False, False])
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
            "expected_moves": ["R(7,2->7,3)", "R(8,3->9,2)"],
        }
    )
    assert "expected=47 R(7,2->7,3) R(8,3->9,2)" in many_suffix


def test_turn1_log_suffix_includes_policy_diag() -> None:
    suffix = ScenarioEvalDebug.turn1_log_suffix(
        {
            "first_turn_actions": ["Cr(0)", "R(2,3->3,4)*", "R(5,5->5,6)"],
            "opening_mispick_diag": {
                "chosen": "R(5,5->5,6)",
                "chosen_actor_kind": {"actor": 1, "kind": "run", "p_plain": 0.6, "p_biased": 0.55},
                "top_actor_kind": [{"actor": 0, "kind": "mark", "p_biased": 0.3}],
            },
        }
    )
    assert "turn1=Cr(0)" in suffix
    assert "policy_diag=" in suffix
    assert "p=0.550(0.600plain)" in suffix


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


def test_opening_mispick_skips_replace_and_captures_first_off_pass() -> None:
    """Replace-first openings leave expected_move_count=0; still snapshot the off-PASS run."""
    debug = ScenarioEvalDebug()
    cells = [_cell() for _ in range(PITCH_SQUARE_COUNT)]
    cells[_pitch(2, 3)] = _cell(player={"teamRel": 0, "playerIndex": 0})
    cells[_pitch(5, 5)] = _cell(player={"teamRel": 0, "playerIndex": 1})
    obs = {"cells": cells, "turnNumber": 1}
    debug.on_scenario_start(
        1,
        obs,
        policy_seat="HOME",
        first_action_legal_count=2,
        legal_row_meta=[{"kind": "replaceChallenge", "factors": []}],
        passing_legal=[False],
    )
    assert debug._expected_move_count == 0

    policy_obs = {"dummy": np.zeros(1, dtype=np.float32)}
    legal_moves = np.zeros((2, 8), dtype=np.int64)
    factors = np.zeros(8, dtype=np.int64)

    debug.maybe_record_action(
        turn_number=1,
        observation=obs,
        legal_choice={"kind": "replaceChallenge", "factors": [{"kind": "challengeSlot", "slot": 0}]},
        expected_move=False,
        policy_obs=policy_obs,
        legal_moves=legal_moves,
        passing_legal=[False, False],
        soft_mask_legal=[False, False],
        legal_row_meta=[{"kind": "replaceChallenge"}, {"kind": "replaceChallenge"}],
        chosen_factors=factors,
    )
    assert debug.opening_mispick_snapshot() is None

    # Empty PASS/soft-mask on this menu must still capture — that is the interesting failure.
    off_pass = {
        "kind": "run",
        "factors": [
            {"kind": "actor", "playerIndex": 1},
            {"kind": "boardTarget", "boardIndex": _pitch(5, 6)},
        ],
    }
    debug.maybe_record_action(
        turn_number=1,
        observation=obs,
        legal_choice=off_pass,
        expected_move=False,
        policy_obs=policy_obs,
        legal_moves=legal_moves,
        passing_legal=[False, False],
        soft_mask_legal=[False, False],
        legal_row_meta=[off_pass, {"kind": "run"}],
        chosen_factors=factors,
    )
    snap = debug.opening_mispick_snapshot()
    assert snap is not None
    assert snap["chosen_label"].startswith("run(5,5->5,6)")
