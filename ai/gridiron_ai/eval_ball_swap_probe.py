"""REMOVABLE probe: re-score opening PASS rows after moving the ball to the opposite pitch end.

Eval-only helper for ``TD_RUN`` / ``LOOSE_BALL_RUN``. Does not change training, rewards, or
legals — only mutates a *copy* of the frozen opening ``policy_obs`` pitch ball channels and
re-runs join-score diagnostics.

Delete this module + the thin call sites tagged ``# ball_swap_probe`` to remove.
Scenario allowlist is intentional probe policy (not a catalog mirror for product logic).
"""

from __future__ import annotations

from typing import Any

import numpy as np

from gridiron_ai.constants import (
    CELL_CH_CAN_PICKUP,
    CELL_CH_CAN_REACH_BALL,
    CELL_CH_DIST_TO_BALL,
    CELL_CH_HAS_BALL,
    OBS_KEY_PITCH,
    PLAY_HEIGHT,
    PLAY_WIDTH,
)
from gridiron_ai.eval_opening_diag import annotate_move_label, diagnose_opening_mispick

# Probe policy — only these drills (user-requested). Not used as a product catalog.
BALL_SWAP_PROBE_SCENARIOS: frozenset[str] = frozenset({"TD_RUN", "LOOSE_BALL_RUN"})

# Optional last-eval / headline fields (safe None when probe off or N/A).
LAST_EVAL_BALL_SWAP_KEYS: tuple[str, ...] = (
    "opening_ball_swap_expected_p_best_mean",
    "opening_ball_swap_expected_rank_best_mean",
)

_DIST_ABSENT = float(max(PLAY_HEIGHT, PLAY_WIDTH))


def probe_enabled_for(scenario: str | None) -> bool:
    return scenario is not None and scenario in BALL_SWAP_PROBE_SCENARIOS


def _find_ball_yx(pitch: np.ndarray) -> tuple[int, int] | None:
    """Pitch is ``(C, H, W)``. Return ``(y, x)`` of the first ``hasBall`` cell."""
    has = np.asarray(pitch[CELL_CH_HAS_BALL])
    ys, xs = np.where(has > 0.5)
    if ys.size == 0:
        return None
    return int(ys[0]), int(xs[0])


def _opposite_end_yx(y: int, x: int) -> tuple[int, int]:
    """Same column, other end of the board in perspective coords.

    Prefer vertical mirror. Odd-height midfield mirrors onto itself — snap to the far
    endzone row (``PLAY_HEIGHT - 1``) so the probe still moves the ball.
    """
    y = int(y)
    x = int(x)
    mirrored = PLAY_HEIGHT - 1 - y
    if mirrored != y:
        return (mirrored, x)
    return (PLAY_HEIGHT - 1, x)


def relocate_ball_opposite_end(policy_obs: dict[str, np.ndarray]) -> tuple[dict[str, np.ndarray], dict[str, Any]]:
    """Copy obs; move pitch ``hasBall`` to the opposite end; refresh ``distToBall``.

    Clears ``canPickup`` / ``canReachBall`` on all pitch cells so stale affordances do not
    still point at the old square (probe is about ball *location* channels).
    """
    out = {key: np.asarray(value).copy() for key, value in policy_obs.items()}
    pitch = out[OBS_KEY_PITCH]
    if pitch.ndim != 3 or pitch.shape[0] <= CELL_CH_DIST_TO_BALL:
        return out, {"error": "bad_pitch_shape"}
    found = _find_ball_yx(pitch)
    if found is None:
        return out, {"error": "no_ball"}
    y0, x0 = found
    y1, x1 = _opposite_end_yx(y0, x0)
    if (y1, x1) == (y0, x0):
        return out, {"error": "ball_unmoved", "from": (x0, y0), "to": (x1, y1)}

    pitch[CELL_CH_HAS_BALL].fill(0.0)
    pitch[CELL_CH_HAS_BALL, y1, x1] = 1.0
    pitch[CELL_CH_CAN_PICKUP].fill(0.0)
    pitch[CELL_CH_CAN_REACH_BALL].fill(0.0)
    for y in range(PLAY_HEIGHT):
        for x in range(PLAY_WIDTH):
            pitch[CELL_CH_DIST_TO_BALL, y, x] = float(max(abs(x - x1), abs(y - y1)))
    meta = {
        "from": (x0, y0),
        "to": (x1, y1),
        "dist_absent": _DIST_ABSENT,
    }
    return out, meta


def attach_ball_swap_opening_scores(
    policy: Any,
    snapshot: dict[str, Any],
    summary: dict[str, Any],
    *,
    scenario: str | None,
) -> None:
    """If scenario is probed, set ``summary['opening_ball_swap']``; else clear it."""
    summary.pop("opening_ball_swap", None)
    if not probe_enabled_for(scenario):
        return
    swapped_obs, meta = relocate_ball_opposite_end(snapshot["policy_obs"])
    if meta.get("error"):
        summary["opening_ball_swap"] = {"error": meta["error"], **{k: v for k, v in meta.items() if k != "error"}}
        return
    swapped_snapshot = {
        **snapshot,
        "policy_obs": swapped_obs,
    }
    diag = diagnose_opening_mispick(policy, swapped_snapshot, all_rows=False)
    scores = diag.get("opening_move_scores") or {}
    expected_scores = list(scores.get("expected") or [])
    # Labels in the same order as passing indices / expected_moves on the live summary.
    live_labels = list(summary.get("expected_moves") or [])
    summary["opening_ball_swap"] = {
        "from": meta.get("from"),
        "to": meta.get("to"),
        "expected_moves": live_labels,
        "expected": expected_scores,
        "expected_pref": scores.get("expected_pref") or diag.get("expected_rank"),
    }


def format_ball_swap_turn1_line(summary: dict[str, Any]) -> str:
    """Extra turn1 line: ``expected=run(...) [rank=N p=0.013]`` (ball at opposite end)."""
    payload = summary.get("opening_ball_swap")
    if not isinstance(payload, dict):
        return ""
    if payload.get("error"):
        return f"\n            expected(ball_opp)=error:{payload['error']}"
    labels = list(payload.get("expected_moves") or [])
    scored = list(payload.get("expected") or [])
    if not labels and not scored:
        return ""
    paired = [
        (labels[i] if i < len(labels) else f"row{scored[i].get('index')}", scored[i] if i < len(scored) else None)
        for i in range(max(len(labels), len(scored)))
    ]
    paired.sort(
        key=lambda item: (
            0 if item[1] and item[1].get("rank") is not None else 1,
            int(item[1]["rank"]) if item[1] and item[1].get("rank") is not None else 0,
        )
    )
    shown = paired if len(paired) <= 2 else paired[:2]
    bits = [annotate_move_label(label, score) for label, score in shown]
    if not bits:
        return ""
    where = ""
    if payload.get("from") is not None and payload.get("to") is not None:
        fx, fy = payload["from"]
        tx, ty = payload["to"]
        where = f" ball={fx},{fy}->{tx},{ty}"
    body = " ".join(bits) if len(paired) <= 2 else f"{len(paired)} " + " ".join(bits)
    return f"\n            expected(ball_opp)={body}{where}"


def aggregate_ball_swap_scores(episodes: list[dict[str, Any]]) -> dict[str, float | None]:
    """Batch means for headline / last-eval optional fields."""
    best_ps: list[float] = []
    best_ranks: list[float] = []
    for episode in episodes:
        payload = episode.get("opening_ball_swap")
        if not isinstance(payload, dict) or payload.get("error"):
            continue
        expected = payload.get("expected") or []
        if not isinstance(expected, list) or not expected:
            pref = payload.get("expected_pref")
            if isinstance(pref, dict) and pref.get("best_rank") is not None:
                best_ranks.append(float(pref["best_rank"]))
            continue
        ps = [float(row["p"]) for row in expected if isinstance(row, dict) and row.get("p") is not None]
        ranks = [
            float(row["rank"])
            for row in expected
            if isinstance(row, dict) and row.get("rank") is not None
        ]
        if ps:
            best_ps.append(max(ps))
        if ranks:
            best_ranks.append(min(ranks))
    return {
        "opening_ball_swap_expected_p_best_mean": (
            float(sum(best_ps) / len(best_ps)) if best_ps else None
        ),
        "opening_ball_swap_expected_rank_best_mean": (
            float(sum(best_ranks) / len(best_ranks)) if best_ranks else None
        ),
    }


def ball_swap_rank_trail_from_history(snaps: list[dict[str, Any]]) -> list[float]:
    """Oldest→newest ``opening_ball_swap_expected_rank_best_mean`` from last-eval slim snaps."""
    ranks: list[float] = []
    for snap in snaps:
        rank = snap.get("opening_ball_swap_expected_rank_best_mean")
        if rank is not None:
            ranks.append(float(rank))
    return ranks


def format_ball_swap_headline_bits(
    stats: dict[str, Any],
    *,
    prior: dict[str, Any] | None = None,
    rank_history: list[float] | None = None,
) -> str:
    """Compact `` ball_opp: 12.0→9.5 p=0.013`` (or empty)."""
    from gridiron_ai.eval_opening_diag import fmt_metric_delta

    p_after = stats.get("opening_ball_swap_expected_p_best_mean")
    rank_after = stats.get("opening_ball_swap_expected_rank_best_mean")
    p_before = None if prior is None else prior.get("opening_ball_swap_expected_p_best_mean")
    rank_before = None if prior is None else prior.get("opening_ball_swap_expected_rank_best_mean")
    if rank_history:
        trail = [float(r) for r in rank_history if r is not None][-4:]
        rank_bit = "→".join(f"{r:.1f}" for r in trail) if trail else None
    else:
        rank_bit = fmt_metric_delta(
            float(rank_before) if rank_before is not None else None,
            float(rank_after) if rank_after is not None else None,
            digits=1,
        )
    p_bit = fmt_metric_delta(
        float(p_before) if p_before is not None else None,
        float(p_after) if p_after is not None else None,
        digits=3,
    )
    if rank_bit is None and p_bit is None:
        return ""
    parts: list[str] = []
    if rank_bit is not None:
        parts.append(rank_bit)
    if p_bit is not None:
        parts.append(f"p={p_bit}")
    return f" ball_opp: {' '.join(parts)}"
