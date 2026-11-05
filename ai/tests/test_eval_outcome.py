"""Tests for eval game-line outcome labels."""

from __future__ import annotations

from gridiron_ai.eval_outcome import format_eval_outcome


def test_format_eval_outcome_submit_error() -> None:
    assert format_eval_outcome({"submit_error": True}) == "ERROR"
    assert format_eval_outcome({"submit_error": True, "result_winner_rel": "US"}) == "ERROR"


def test_format_eval_outcome_winner_draw_open() -> None:
    assert format_eval_outcome({"result_winner_rel": "THEM"}) == "THEM"
    assert format_eval_outcome({"result_kind": "FULL"}) == "DRAW"
    assert format_eval_outcome({}) == "OPEN"
    assert format_eval_outcome(None) == "OPEN"
