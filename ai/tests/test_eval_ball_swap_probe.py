"""Unit tests for removable ball-opposite-end opening probe."""

from __future__ import annotations

import numpy as np

from gridiron_ai.constants import (
    CELL_CH_CAN_PICKUP,
    CELL_CH_DIST_TO_BALL,
    CELL_CH_HAS_BALL,
    NUMBERS_PER_BOARD_CELL,
    OBS_KEY_PITCH,
    PLAY_HEIGHT,
    PLAY_WIDTH,
)
from gridiron_ai.eval_ball_swap_probe import (
    aggregate_ball_swap_scores,
    format_ball_swap_turn1_line,
    probe_enabled_for,
    relocate_ball_opposite_end,
)


def _pitch_with_ball(y: int, x: int) -> dict[str, np.ndarray]:
    pitch = np.zeros((NUMBERS_PER_BOARD_CELL, PLAY_HEIGHT, PLAY_WIDTH), dtype=np.float32)
    pitch[CELL_CH_HAS_BALL, y, x] = 1.0
    pitch[CELL_CH_CAN_PICKUP, y, x] = 1.0
    for yy in range(PLAY_HEIGHT):
        for xx in range(PLAY_WIDTH):
            pitch[CELL_CH_DIST_TO_BALL, yy, xx] = float(max(abs(xx - x), abs(yy - y)))
    return {OBS_KEY_PITCH: pitch}


def test_probe_only_td_run_and_loose_ball() -> None:
    assert probe_enabled_for("TD_RUN")
    assert probe_enabled_for("LOOSE_BALL_RUN")
    assert not probe_enabled_for("MARKED_RUN")
    assert not probe_enabled_for(None)


def test_relocate_ball_mirrors_row_and_clears_pickup() -> None:
    obs, meta = relocate_ball_opposite_end(_pitch_with_ball(2, 4))
    assert meta["from"] == (4, 2)
    assert meta["to"] == (4, PLAY_HEIGHT - 1 - 2)
    pitch = obs[OBS_KEY_PITCH]
    assert float(pitch[CELL_CH_HAS_BALL, 2, 4]) == 0.0
    ty = PLAY_HEIGHT - 1 - 2
    assert float(pitch[CELL_CH_HAS_BALL, ty, 4]) == 1.0
    assert float(pitch[CELL_CH_CAN_PICKUP].max()) == 0.0
    assert float(pitch[CELL_CH_DIST_TO_BALL, ty, 4]) == 0.0
    assert float(pitch[CELL_CH_DIST_TO_BALL, 2, 4]) == float(abs(ty - 2))


def test_relocate_midfield_ball_snaps_to_far_endzone() -> None:
    """Odd PLAY_HEIGHT midfield mirrors onto itself — probe must still move the ball."""
    mid_y = PLAY_HEIGHT // 2
    assert PLAY_HEIGHT - 1 - mid_y == mid_y
    obs, meta = relocate_ball_opposite_end(_pitch_with_ball(mid_y, 3))
    assert "error" not in meta
    assert meta["from"] == (3, mid_y)
    assert meta["to"] == (3, PLAY_HEIGHT - 1)
    pitch = obs[OBS_KEY_PITCH]
    assert float(pitch[CELL_CH_HAS_BALL, mid_y, 3]) == 0.0
    assert float(pitch[CELL_CH_HAS_BALL, PLAY_HEIGHT - 1, 3]) == 1.0


def test_format_ball_swap_turn1_line() -> None:
    summary = {
        "opening_ball_swap": {
            "from": (4, 2),
            "to": (4, 12),
            "expected_moves": ["run(9,13->8,14)*"],
            "expected": [{"index": 3, "rank": 12, "p": 0.013}],
        }
    }
    line = format_ball_swap_turn1_line(summary)
    assert line.startswith("\n            expected(ball_opp)=")
    assert "run(9,13->8,14)*" in line
    assert "rank=12" in line
    assert "p=0.013" in line
    assert "ball=4,2->4,12" in line


def test_aggregate_ball_swap_scores() -> None:
    stats = aggregate_ball_swap_scores(
        [
            {
                "opening_ball_swap": {
                    "expected": [{"rank": 10, "p": 0.02}, {"rank": 20, "p": 0.01}],
                }
            },
            {
                "opening_ball_swap": {
                    "expected": [{"rank": 4, "p": 0.08}],
                }
            },
            {"opening_ball_swap": {"error": "no_ball"}},
        ]
    )
    assert stats["opening_ball_swap_expected_rank_best_mean"] == 7.0  # min(10,4) mean
    assert abs(float(stats["opening_ball_swap_expected_p_best_mean"]) - 0.05) < 1e-9
