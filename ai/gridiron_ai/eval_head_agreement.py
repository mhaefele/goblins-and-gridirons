"""Eval-only check that the three action heads point at the same move.

Side log on the action ``predict`` already chose (hard remask, plus soft-mask
bias when the server flagged rows). A second forward reads raw head logits.
Nothing here is fed back into the action.

``heads n=…`` rates, each over decisions in the game or the eval segment:

- ``triple`` — unmasked argmax of actor×kind, target, and challenge is one legal row.
- ``ak_tgt`` — unmasked actor×kind and target argmaxes co-occur on a legal row.
  ``triple`` can sit lower when the challenge head's favorite is not the unused
  sentinel stored on those rows.
- ``played_tgt`` — the target head's unmasked argmax is still legal after the
  played actor×kind remasks the target head. Low means the second head wanted a
  square the first head's choice cannot play.
- ``joint`` — the legal row with the highest sum of raw factor logits is the
  row that was played. Low means sequential remask and scoring the legal rows
  directly disagree.
- ``sm`` — decisions that had a soft-mask row. Play includes that logit bias;
  ``joint`` uses raw logits, so a drill can disagree for that reason.

``played_tgt`` and ``joint`` carry the same level words as the summary tables:
ok at or above 0.80, watch at or above 0.50, bad below. ``triple`` and
``ak_tgt`` stay ungraded — ``triple`` is often low because the challenge head's
favorite is not the unused slot on the row.

Ties take the first argmax (NumPy).
"""

from __future__ import annotations

from typing import Any

import numpy as np
import torch as th

from gridiron_ai.actions import (
    FACTOR_ACTOR_KIND,
    FACTOR_BOARD_TARGET,
    conditional_factor_mask,
)
from gridiron_ai.constants import (
    ACTION_HEAD_SIZES,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_SOFT_MASK_LEGAL,
)

# Same bands the summary table prints beside played_tgt and joint.
HEAD_AGREEMENT_OK_RATE = 0.80
HEAD_AGREEMENT_WATCH_RATE = 0.50

_COUNT_KEYS = (
    "triple_legal",
    "actor_target_legal",
    "played_target_ok",
    "joint_matches_played",
    "soft_bias",
)


def new_head_agreement() -> dict[str, int]:
    """Zeroed counters for one game or one eval segment."""
    return {"decisions": 0, **{key: 0 for key in _COUNT_KEYS}}


def record_head_decision(totals: dict[str, int], decision: dict[str, Any]) -> None:
    """Fold one ``diagnose_factor_logits`` result into ``totals``."""
    totals["decisions"] += 1
    for key in _COUNT_KEYS:
        totals[key] += int(bool(decision[key]))


def merge_head_agreement(destination: dict[str, int], source: dict[str, int]) -> None:
    """Add ``source`` counters into ``destination``."""
    for key in destination:
        destination[key] += int(source[key])


def head_agreement_level(rate: float) -> str:
    """ok / watch / bad for one agreement rate. Higher means the heads agree."""
    if rate >= HEAD_AGREEMENT_OK_RATE:
        return "ok"
    if rate >= HEAD_AGREEMENT_WATCH_RATE:
        return "watch"
    return "bad"


def format_head_agreement(stats: dict[str, int]) -> str:
    """One token for an eval game line, a segment rollup, or summary.sh."""
    decisions = int(stats["decisions"])
    if decisions <= 0:
        return "heads n=0"

    def rate(key: str) -> float:
        return int(stats[key]) / decisions

    def shown(key: str) -> str:
        return f"{rate(key):.2f}"

    played = rate("played_target_ok")
    joint = rate("joint_matches_played")
    return (
        f"heads n={decisions} triple={shown('triple_legal')} "
        f"ak_tgt={shown('actor_target_legal')} "
        f"played_tgt={played:.2f} {head_agreement_level(played)} "
        f"joint={joint:.2f} {head_agreement_level(joint)} "
        f"sm={int(stats['soft_bias'])}/{decisions}"
    )


def diagnose_factor_logits(
    logits: list[np.ndarray] | tuple[np.ndarray, ...],
    legal_moves: np.ndarray,
    legal_move_count: int,
    played: np.ndarray | list[int] | tuple[int, ...],
    *,
    soft_bias: bool = False,
) -> dict[str, Any] | None:
    """Score one decision from raw per-head logits.

    ``logits[slot]`` has length ``ACTION_HEAD_SIZES[slot]``. ``legal_moves`` is
    the padded or live factor matrix; only the first ``legal_move_count`` rows
    count. Returns None when that count is 0 (no choice was offered).
    """
    count = int(legal_move_count)
    if count <= 0:
        return None
    rows = np.asarray(legal_moves, dtype=np.int64)
    if rows.ndim != 2 or rows.shape[1] != len(ACTION_HEAD_SIZES):
        raise ValueError(f"legal_moves shape {rows.shape} != (n, {len(ACTION_HEAD_SIZES)})")
    if count > rows.shape[0]:
        raise ValueError(f"legal_move_count {count} > rows {rows.shape[0]}")
    live = rows[:count]
    if np.any(live < 0):
        raise ValueError("legal row contains a pad sentinel inside legal_move_count")
    head_logits: list[np.ndarray] = []
    for slot, size in enumerate(ACTION_HEAD_SIZES):
        column = np.asarray(logits[slot], dtype=np.float64).reshape(-1)
        if column.shape[0] != size:
            raise ValueError(f"head {slot} logit width {column.shape[0]} != {size}")
        if live[:, slot].max() >= size:
            raise ValueError(f"legal factor {int(live[:, slot].max())} outside head {slot} width {size}")
        head_logits.append(column)

    unmasked = np.array([int(np.argmax(column)) for column in head_logits], dtype=np.int64)
    played_factors = np.asarray(played, dtype=np.int64).reshape(-1)
    if played_factors.shape[0] != len(ACTION_HEAD_SIZES):
        raise ValueError(f"played width {played_factors.shape[0]} != {len(ACTION_HEAD_SIZES)}")

    triple_legal = bool(np.any(np.all(live == unmasked, axis=1)))
    same_actor = live[:, FACTOR_ACTOR_KIND] == unmasked[FACTOR_ACTOR_KIND]
    same_target = live[:, FACTOR_BOARD_TARGET] == unmasked[FACTOR_BOARD_TARGET]
    actor_target_legal = bool(np.any(same_actor & same_target))
    target_mask = conditional_factor_mask(
        live,
        legal_move_count=count,
        chosen_factors={FACTOR_ACTOR_KIND: int(played_factors[FACTOR_ACTOR_KIND])},
        factor_slot=FACTOR_BOARD_TARGET,
    )
    target_argmax = int(unmasked[FACTOR_BOARD_TARGET])
    played_target_ok = bool(0 <= target_argmax < target_mask.size and target_mask[target_argmax])

    scores = head_logits[0][live[:, 0]] + head_logits[1][live[:, 1]] + head_logits[2][live[:, 2]]
    joint = live[int(np.argmax(scores))]
    return {
        "triple_legal": triple_legal,
        "actor_target_legal": actor_target_legal,
        "played_target_ok": played_target_ok,
        "joint_matches_played": bool(np.array_equal(joint, played_factors)),
        "soft_bias": bool(soft_bias),
        "unmasked": [int(value) for value in unmasked],
        "played": [int(value) for value in played_factors],
        "joint": [int(value) for value in joint],
    }


def _as_numpy(value: Any) -> np.ndarray:
    if isinstance(value, th.Tensor):
        return value.detach().cpu().numpy()
    return np.asarray(value)


def diagnose_eval_actions(policy: Any, obs: Any, actions: np.ndarray) -> list[dict[str, Any] | None]:
    """One diagnosis per vec-env row. ``actions`` are the factors ``predict`` returned."""
    policy.set_training_mode(False)
    obs_tensor, _vectorized = policy.obs_to_tensor(obs)
    with th.no_grad():
        features = policy.extract_features(obs_tensor)
        latent_pi, _latent_vf = policy.mlp_extractor(features)
        policy._get_action_dist_from_latent(latent_pi)

    head_logits = [distribution.logits.detach().cpu().numpy() for distribution in policy.action_dist.distributions]
    legal_moves = _as_numpy(obs[OBS_KEY_LEGAL_MOVES])
    legal_counts = _as_numpy(obs[OBS_KEY_LEGAL_MOVE_COUNT]).reshape(-1)
    if legal_moves.ndim == 2:
        legal_moves = legal_moves[None, ...]
    try:
        soft = _as_numpy(obs[OBS_KEY_SOFT_MASK_LEGAL])
    except (KeyError, TypeError):
        soft = np.zeros(legal_moves.shape[:2], dtype=np.int8)
    if soft.ndim == 1:
        soft = soft[None, ...]

    played = _as_numpy(actions)
    if played.ndim == 1:
        played = played.reshape(1, -1)
    batch = int(played.shape[0])
    if legal_moves.shape[0] != batch or legal_counts.shape[0] != batch:
        raise ValueError(
            f"batch mismatch actions={batch} legal_moves={legal_moves.shape[0]} counts={legal_counts.shape[0]}"
        )
    if len(head_logits) != len(ACTION_HEAD_SIZES) or any(column.shape[0] != batch for column in head_logits):
        widths = [column.shape for column in head_logits]
        raise ValueError(f"policy head logits {widths} != batch {batch} x {len(ACTION_HEAD_SIZES)} heads")

    diagnoses: list[dict[str, Any] | None] = []
    for index in range(batch):
        count = int(legal_counts[index])
        soft_bias = bool(count > 0 and np.any(soft[index, :count]))
        diagnoses.append(
            diagnose_factor_logits(
                [column[index] for column in head_logits],
                legal_moves[index],
                count,
                played[index],
                soft_bias=soft_bias,
            )
        )
    return diagnoses
