"""Unit tests for removable ball-opposite-end opening probe."""

from __future__ import annotations

import numpy as np

from gridiron_ai.actions import (
    FACTOR_ACTOR_HAS_BALL,
    FACTOR_SOURCE_X,
    FACTOR_SOURCE_Y,
    FACTOR_TARGET_HAS_BALL,
    FACTOR_TARGET_X,
    FACTOR_TARGET_Y,
)
from gridiron_ai.constants import (
    CELL_CH_CAN_PICKUP,
    CELL_CH_DIST_TO_BALL,
    CELL_CH_HAS_BALL,
    FACTORS_PER_LEGAL_MOVE,
    MAX_LEGAL_MOVES,
    NUMBERS_PER_BOARD_CELL,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
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


def _row(*, sx: int, sy: int, tx: int, ty: int, actor_ball: int, target_ball: int) -> list[int]:
    row = [0] * FACTORS_PER_LEGAL_MOVE
    row[FACTOR_TARGET_X] = tx
    row[FACTOR_TARGET_Y] = ty
    row[FACTOR_SOURCE_X] = sx
    row[FACTOR_SOURCE_Y] = sy
    row[FACTOR_ACTOR_HAS_BALL] = actor_ball
    row[FACTOR_TARGET_HAS_BALL] = target_ball
    return row


def _pitch_with_ball(y: int, x: int, *, legal_rows: list[list[int]] | None = None) -> dict[str, np.ndarray]:
    pitch = np.zeros((NUMBERS_PER_BOARD_CELL, PLAY_HEIGHT, PLAY_WIDTH), dtype=np.float32)
    pitch[CELL_CH_HAS_BALL, y, x] = 1.0
    pitch[CELL_CH_CAN_PICKUP, y, x] = 1.0
    for yy in range(PLAY_HEIGHT):
        for xx in range(PLAY_WIDTH):
            pitch[CELL_CH_DIST_TO_BALL, yy, xx] = float(max(abs(xx - x), abs(yy - y)))
    out: dict[str, np.ndarray] = {OBS_KEY_PITCH: pitch}
    if legal_rows is not None:
        padded = np.full((MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=np.int64)
        n = len(legal_rows)
        if n:
            padded[:n] = np.asarray(legal_rows, dtype=np.int64)
        out[OBS_KEY_LEGAL_MOVES] = padded
        out[OBS_KEY_LEGAL_MOVE_COUNT] = np.asarray([n], dtype=np.int32)
    return out


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


def test_relocate_rewrites_legal_row_ball_flags() -> None:
    """Packed actorHasBall / targetHasBall follow the lied ball square, not the old one."""
    y0, x0 = 2, 4
    y1 = PLAY_HEIGHT - 1 - y0
    rows = [
        _row(sx=x0, sy=y0, tx=x0, ty=y0, actor_ball=1, target_ball=1),  # was carrier @ ball
        _row(sx=x0, sy=y0, tx=5, ty=5, actor_ball=1, target_ball=0),  # carrier run elsewhere
        _row(sx=1, sy=1, tx=x0, ty=y0, actor_ball=0, target_ball=1),  # pickup toward old ball
        _row(sx=1, sy=1, tx=x0, ty=y1, actor_ball=0, target_ball=0),  # dest = new ball square
    ]
    obs, meta = relocate_ball_opposite_end(_pitch_with_ball(y0, x0, legal_rows=rows))
    assert meta["to"] == (x0, y1)
    legal = obs[OBS_KEY_LEGAL_MOVES]
    assert int(legal[0, FACTOR_ACTOR_HAS_BALL]) == 0
    assert int(legal[0, FACTOR_TARGET_HAS_BALL]) == 0
    assert int(legal[1, FACTOR_ACTOR_HAS_BALL]) == 0
    assert int(legal[1, FACTOR_TARGET_HAS_BALL]) == 0
    assert int(legal[2, FACTOR_ACTOR_HAS_BALL]) == 0
    assert int(legal[2, FACTOR_TARGET_HAS_BALL]) == 0
    assert int(legal[3, FACTOR_ACTOR_HAS_BALL]) == 0
    assert int(legal[3, FACTOR_TARGET_HAS_BALL]) == 1
    assert int(legal[4, 0]) == -1  # pad untouched


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
