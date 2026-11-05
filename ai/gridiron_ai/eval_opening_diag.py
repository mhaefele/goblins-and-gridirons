"""Opening join-score diagnostics and score-bit formatting for scenario eval.

Owner: per-game opening mispick diag + batch ``opening_*`` aggregates / ``expected_p``
format (including prior→current deltas). Episode metrics lines live in ``metrics.py``.
Throwaway turn1 logging stays in ``eval_debug.py``. See ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

from typing import Any

import numpy as np
import torch as th

from gridiron_ai.actions import FACTOR_ACTOR, FACTOR_KIND
from gridiron_ai.constants import OBS_KEY_LEGAL_MOVE_COUNT, OBS_KEY_SOFT_MASK_LEGAL, SOFT_MASK_LOGIT_BIAS
from gridiron_ai.reward import us_ball_carrier


def _masked_softmax(logits: np.ndarray, mask: np.ndarray) -> np.ndarray:
    masked = np.full_like(logits, -1.0e8, dtype=np.float64)
    masked[mask] = logits[mask].astype(np.float64)
    masked = masked - masked.max()
    exp = np.exp(masked)
    exp[~mask] = 0.0
    total = exp.sum()
    if total <= 0.0:
        return np.zeros_like(exp)
    return exp / total


def _snapshot_policy_obs(policy_obs: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    return {key: np.asarray(value).copy() for key, value in policy_obs.items()}


def ball_carrier_player_index(rewards: dict[str, Any]) -> int | None:
    """US ball-carrier roster ``playerIndex`` from harness rewards, if any."""
    carrier = us_ball_carrier(rewards)
    if carrier is None:
        return None
    return int(carrier["playerIndex"])


def preferred_row_rank(*, logits: np.ndarray, legal_count: int, preferred: np.ndarray) -> dict[str, Any] | None:
    """Best preferred live row by logit rank (1 = argmax). None when no preferred flags."""
    n = int(legal_count)
    if n <= 0:
        return None
    live = np.asarray(logits[:n], dtype=np.float64)
    pref = np.asarray(preferred[:n], dtype=bool)
    pref_idx = np.flatnonzero(pref)
    if pref_idx.size == 0:
        return None
    order = np.argsort(-live)
    ranks = np.empty(n, dtype=np.int32)
    ranks[order] = np.arange(1, n + 1)
    best_i = int(pref_idx[np.argmin(ranks[pref_idx])])
    argmax_i = int(order[0])
    return {
        "best_rank": int(ranks[best_i]),
        "best_index": best_i,
        "n_preferred": int(pref_idx.size),
        "legal_count": n,
        "argmax_index": argmax_i,
        "logit_gap": float(live[argmax_i] - live[best_i]),
    }


def snapshot_opening_mispick(
    *,
    rewards: dict[str, Any],
    policy_obs: dict[str, np.ndarray],
    legal_moves: np.ndarray,
    passing_legal: list[bool],
    soft_mask_legal: list[bool],
    legal_row_meta: list[dict[str, Any]],
    chosen_row_index: int,
    chosen_label: str,
) -> dict[str, Any]:
    """Frozen decision state for ``diagnose_opening_mispick`` after the episode."""
    return {
        "policy_obs": _snapshot_policy_obs(policy_obs),
        "rewards": rewards,
        "legal_moves": np.asarray(legal_moves, dtype=np.int64).copy(),
        "passing_legal": np.asarray(passing_legal, dtype=np.int8).copy(),
        "soft_mask_legal": np.asarray(soft_mask_legal, dtype=np.int8).copy(),
        "legal_row_meta": list(legal_row_meta),
        "chosen_row_index": int(chosen_row_index),
        "chosen_label": chosen_label,
    }


def diagnose_opening_mispick(
    policy: Any,
    snapshot: dict[str, Any],
    *,
    all_rows: bool = False,
) -> dict[str, Any]:
    """Score the opening legal-row menu (plain vs soft-mask-biased) at the mispick."""
    from gridiron_ai.eval_debug import ScenarioEvalDebug

    policy.set_training_mode(False)
    obs_tensor, _ = policy.obs_to_tensor(snapshot["policy_obs"])
    with th.no_grad():
        _values, logits_t = policy._board_and_row_logits(obs_tensor)
    logits = logits_t[0].detach().cpu().numpy()

    legal_moves = snapshot["legal_moves"]
    count_arr = snapshot["policy_obs"].get(OBS_KEY_LEGAL_MOVE_COUNT)
    legal_count = int(np.asarray(count_arr).reshape(-1)[0]) if count_arr is not None else int(legal_moves.shape[0])
    legal_count = min(legal_count, int(legal_moves.shape[0]), logits.shape[0])
    soft_mask = snapshot["soft_mask_legal"][:legal_count].astype(bool)
    passing = snapshot["passing_legal"][:legal_count].astype(bool)
    legal_row_meta = snapshot["legal_row_meta"]
    chosen_i = int(snapshot["chosen_row_index"])
    rewards = snapshot["rewards"]

    mask = np.zeros(logits.shape[0], dtype=bool)
    mask[:legal_count] = True
    # Forward logits already include obs soft-mask bias when eval episode_mode=TRAIN.
    # Strip whatever was in obs, then re-apply server flags for a clean plain vs biased pair.
    acting = logits.astype(np.float64)
    obs_soft_src = snapshot["policy_obs"].get(OBS_KEY_SOFT_MASK_LEGAL)
    obs_soft = np.zeros(legal_count, dtype=bool)
    if obs_soft_src is not None:
        obs_soft = np.asarray(obs_soft_src).reshape(-1)[:legal_count] > 0
    plain = acting.copy()
    plain[:legal_count][obs_soft] -= SOFT_MASK_LOGIT_BIAS
    biased = plain.copy()
    biased[:legal_count][soft_mask] += SOFT_MASK_LOGIT_BIAS
    prob_plain = _masked_softmax(plain, mask)
    prob_biased = _masked_softmax(biased, mask)
    acting_biased = bool(obs_soft.any())

    def _row_action(index: int) -> dict[str, Any]:
        if 0 <= index < len(legal_row_meta):
            row = legal_row_meta[index]
            action = row["action"] if isinstance(row.get("action"), dict) else row
            if isinstance(action, dict):
                return action
        return {"kind": f"k{int(legal_moves[index, FACTOR_KIND])}"}

    def _row_kind(index: int) -> str:
        return str(_row_action(index).get("kind") or "?")

    def _row_actor(index: int) -> int:
        if 0 <= index < legal_count:
            return int(legal_moves[index, FACTOR_ACTOR])
        return 0

    rows: list[dict[str, Any]] = []
    for index in range(legal_count):
        action = _row_action(index)
        rows.append(
            {
                "index": index,
                "actor": _row_actor(index),
                "kind": _row_kind(index),
                "label": ScenarioEvalDebug.format_action(
                    action,
                    rewards=rewards,
                    expected_move=bool(passing[index]),
                ),
                "p_plain": float(prob_plain[index]),
                "p_biased": float(prob_biased[index]),
                "logit": float(plain[index]),
                "logit_acting": float(acting[index]),
                "soft_mask": bool(soft_mask[index]),
                "passing": bool(passing[index]),
            }
        )
    # Top list follows acting ranking (biased when episode_mode=TRAIN packed soft flags).
    rows.sort(key=lambda row: row["p_biased"] if acting_biased else row["p_plain"], reverse=True)
    for rank, row in enumerate(rows, start=1):
        row["rank"] = rank

    carrier = ball_carrier_player_index(rewards)
    carrier_kinds: dict[str, dict[str, float]] = {}
    if carrier is not None:
        for row in rows:
            if row["actor"] != carrier:
                continue
            bucket = carrier_kinds.setdefault(row["kind"], {"p_plain": 0.0, "p_biased": 0.0})
            bucket["p_plain"] = max(bucket["p_plain"], row["p_plain"])
            bucket["p_biased"] = max(bucket["p_biased"], row["p_biased"])

    chosen_ok = 0 <= chosen_i < legal_count
    # Ranks / gap use acting logits (what deterministic predict actually ranked).
    order = np.argsort(-acting[:legal_count]) if legal_count else np.zeros(0, dtype=np.int64)
    ranks = np.empty(legal_count, dtype=np.int32)
    if legal_count:
        ranks[order] = np.arange(1, legal_count + 1)

    expected_rank = preferred_row_rank(logits=acting, legal_count=legal_count, preferred=passing)
    soft_rank = preferred_row_rank(logits=acting, legal_count=legal_count, preferred=soft_mask)

    p_key = "p_biased" if acting_biased else "p_plain"
    # Same order as ``_format_expected_moves`` labels; display sorts by rank and keeps the best.
    passing_indices = [int(index) for index in np.flatnonzero(passing)]
    expected_rows = [
        {
            "index": index,
            "rank": int(ranks[index]),
            "p": float(prob_biased[index] if acting_biased else prob_plain[index]),
            "logit": float(acting[index]),
        }
        for index in passing_indices
    ]

    return {
        "chosen": snapshot.get("chosen_label"),
        "chosen_row": {
            "index": chosen_i,
            "actor": _row_actor(chosen_i) if chosen_ok else None,
            "kind": _row_kind(chosen_i) if chosen_ok else None,
            "rank": int(ranks[chosen_i]) if chosen_ok else None,
            "p_plain": float(prob_plain[chosen_i]) if chosen_ok else 0.0,
            "p_biased": float(prob_biased[chosen_i]) if chosen_ok else 0.0,
            "soft_mask": bool(soft_mask[chosen_i]) if chosen_ok else False,
            "passing": bool(passing[chosen_i]) if chosen_ok else False,
        },
        "expected_rank": expected_rank,
        "expected_rows": expected_rows,
        "soft_rank": soft_rank,
        "ball_carrier_player_index": carrier,
        "carrier_kind_mass": carrier_kinds,
        "top_rows": rows[:6],
        "ranked_rows": list(rows) if all_rows else None,
        "acting_biased": acting_biased,
        "soft_mask_bias": SOFT_MASK_LOGIT_BIAS,
        "legal_count": legal_count,
        "passing_row_count": int(passing.sum()),
        "soft_mask_row_count": int(soft_mask.sum()),
        "opening_move_scores": {
            "chosen": (
                {
                    "rank": int(ranks[chosen_i]),
                    "p": float(prob_biased[chosen_i] if acting_biased else prob_plain[chosen_i]),
                    "logit": float(acting[chosen_i]),
                }
                if chosen_ok
                else None
            ),
            "expected": expected_rows,
            "p_key": p_key,
        },
    }


def annotate_move_label(label: str, score: dict[str, Any] | None) -> str:
    """Append ``rank=`` / ``p=`` from opening join-score mass when present."""
    if not score or score.get("rank") is None:
        return label
    p = float(score.get("p") or 0.0)
    return f"{label} [rank={int(score['rank'])} p={p:.3f}]"


def _mean(values: list[float]) -> float | None:
    if not values:
        return None
    return float(sum(values) / len(values))


def aggregate_opening_move_scores(episodes: list[dict[str, Any]]) -> dict[str, float | None]:
    """Batch means from per-game ``opening_move_scores`` (None when no scored openings).

    - ``opening_expected_p_mean`` — mean over games of that game's mean expected-row ``p``
    - ``opening_expected_p_best_mean`` — mean over games of max expected-row ``p``
    - ``opening_chosen_p_mean`` — mean first-move (argmax) ``p``
    - ``opening_expected_rank_best_mean`` — mean over games of best (lowest) expected rank
    """
    expected_means: list[float] = []
    expected_bests: list[float] = []
    chosen_ps: list[float] = []
    expected_best_ranks: list[float] = []
    for episode in episodes:
        scores = episode.get("opening_move_scores")
        if not isinstance(scores, dict):
            continue
        expected = scores.get("expected") or []
        if isinstance(expected, list) and expected:
            ps = [float(row["p"]) for row in expected if isinstance(row, dict) and row.get("p") is not None]
            ranks = [
                float(row["rank"])
                for row in expected
                if isinstance(row, dict) and row.get("rank") is not None
            ]
            if ps:
                expected_means.append(sum(ps) / len(ps))
                expected_bests.append(max(ps))
            if ranks:
                expected_best_ranks.append(min(ranks))
        chosen = scores.get("chosen")
        if isinstance(chosen, dict) and chosen.get("p") is not None:
            chosen_ps.append(float(chosen["p"]))
    return {
        "opening_expected_p_mean": _mean(expected_means),
        "opening_expected_p_best_mean": _mean(expected_bests),
        "opening_chosen_p_mean": _mean(chosen_ps),
        "opening_expected_rank_best_mean": _mean(expected_best_ranks),
    }


_OPENING_SCORE_KEYS = (
    "opening_expected_p_mean",
    "opening_expected_p_best_mean",
    "opening_chosen_p_mean",
    "opening_expected_rank_best_mean",
)


def opening_score_fields(stats: dict[str, Any]) -> dict[str, float | None]:
    """Four opening aggregates from ``stats``, or recompute from ``episodes_raw``."""
    if any(stats.get(key) is not None for key in _OPENING_SCORE_KEYS):
        return {key: (float(stats[key]) if stats.get(key) is not None else None) for key in _OPENING_SCORE_KEYS}
    raw = stats.get("episodes_raw")
    if isinstance(raw, list) and raw:
        return aggregate_opening_move_scores(raw)
    return {key: None for key in _OPENING_SCORE_KEYS}


def fmt_metric_delta(before: float | None, after: float | None, *, digits: int = 3) -> str | None:
    """``0.300→0.400`` when ``before`` is set, else ``0.400``; ``None`` if after missing."""
    if after is None:
        return None
    if before is None:
        return f"{after:.{digits}f}"
    return f"{before:.{digits}f}→{after:.{digits}f}"


def format_opening_score_bits(
    stats: dict[str, Any],
    *,
    prior: dict[str, Any] | None = None,
) -> str:
    """Compact `` expected_p=… expected_best_p=… first_p=… expected_rank=…`` or empty.

    When ``prior`` has the same aggregates, values render as ``before→after`` drift.
    """
    fields = opening_score_fields(stats)
    prior_fields = opening_score_fields(prior) if prior is not None else None
    bits: list[str] = []
    for key, label, digits in (
        ("opening_expected_p_mean", "expected_p", 3),
        ("opening_expected_p_best_mean", "expected_best_p", 3),
        ("opening_chosen_p_mean", "first_p", 3),
        ("opening_expected_rank_best_mean", "expected_rank", 1),
    ):
        before = None if prior_fields is None else prior_fields[key]
        rendered = fmt_metric_delta(before, fields[key], digits=digits)
        if rendered is not None:
            bits.append(f"{label}={rendered}")
    return (" " + " ".join(bits)) if bits else ""


def format_opening_mispick_diag(diag: dict[str, Any]) -> str:
    """Summary line after ``turn1=``; optional full ranked menu when ``ranked_rows`` present."""
    if diag.get("error"):
        return f"\n            policy_diag=error:{diag['error']}"
    chosen = diag.get("chosen_row") or {}
    parts = [
        f"picked={diag.get('chosen')}",
        f"kind={chosen.get('kind')}",
        f"rank={chosen.get('rank')}/{diag.get('legal_count')}",
        f"p={chosen.get('p_plain', 0):.3f}({chosen.get('p_biased', 0):.3f}sm)",
    ]
    expected = diag.get("expected_rank")
    if expected is not None:
        parts.append(
            f"expected_best={expected['best_rank']}/{expected['legal_count']}"
            f"(n={expected['n_preferred']} gap={expected['logit_gap']:+.2f})"
        )
    soft = diag.get("soft_rank")
    if soft is not None:
        parts.append(
            f"soft_best={soft['best_rank']}/{soft['legal_count']}"
            f"(n={soft['n_preferred']} gap={soft['logit_gap']:+.2f})"
        )
    sm_rows = diag.get("soft_mask_row_count")
    legal_rows = diag.get("legal_count")
    if sm_rows is not None and legal_rows is not None:
        parts.append(f"sm={sm_rows}/{legal_rows}")
    if chosen.get("soft_mask"):
        parts.append("picked_sm")
    if chosen.get("passing"):
        parts.append("picked_pass")
    carrier = diag.get("ball_carrier_player_index")
    masses = diag.get("carrier_kind_mass") or {}
    if carrier is not None and masses:
        kind_bits = []
        for kind in ("sidestep", "run", "block", "mark", "throw"):
            if kind in masses:
                m = masses[kind]
                kind_bits.append(f"{kind}={m['p_plain']:.2f}/{m['p_biased']:.2f}")
        if kind_bits:
            parts.append(f"carrier_p{carrier}:" + ",".join(kind_bits))
    top = diag.get("top_rows") or []
    if top:
        leader = top[0]
        parts.append(f"top={leader['kind']}@{leader['p_plain']:.2f}")
    out = "\n            policy_diag=" + " ".join(parts)
    ranked = diag.get("ranked_rows") or []
    if not ranked:
        return out
    acting_biased = bool(diag.get("acting_biased"))
    chosen_index = chosen.get("index")
    lines = [out, "\n            policy_menu="]
    for row in ranked:
        flags: list[str] = []
        if row.get("passing"):
            flags.append("*")
        if row.get("soft_mask"):
            flags.append("sm")
        if chosen_index is not None and row.get("index") == chosen_index:
            flags.append("<<<")
        flag_bit = f" {' '.join(flags)}" if flags else ""
        p_act = row["p_biased"] if acting_biased else row["p_plain"]
        lines.append(
            f"\n              #{row['rank']} idx={row['index']} {row.get('label') or row.get('kind')} "
            f"p={p_act:.4f} plain={row['p_plain']:.4f} sm={row['p_biased']:.4f} "
            f"logit={row.get('logit_acting', row.get('logit', 0)):.3f}{flag_bit}"
        )
    return "".join(lines)
