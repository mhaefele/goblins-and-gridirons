"""Eval game-line outcome labels from episode summaries."""

from __future__ import annotations

from typing import Any


def format_eval_outcome(summary: dict[str, Any] | None) -> str:
    """US/THEM/DISTRACTED/DRAW/ERROR/OPEN from a bb_episode-style summary."""
    if summary is None:
        return "OPEN"
    if summary.get("submit_error"):
        return "ERROR"
    winner = summary.get("result_winner_rel")
    if winner:
        return str(winner)
    if summary.get("result_kind"):
        return "DRAW"
    return "OPEN"
