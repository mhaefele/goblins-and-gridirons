"""Policy diagnostics when scenario eval mispicks the opening coach turn.

Owner: throwaway eval instrumentation — not part of the learning contract.
See ``eval_debug.py`` / ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

from typing import Any

import numpy as np
import torch as th

from gridiron_ai.actions import (
    FACTOR_ACTOR_KIND,
    conditional_factor_mask,
    preferred_factor_values,
)
from gridiron_ai.constants import (
    LEGAL_ACTION_KIND_COUNT,
    SOFT_MASK_LOGIT_BIAS,
)

def decode_actor_kind(encoded: int) -> tuple[int, int]:
    actor = int(encoded) // LEGAL_ACTION_KIND_COUNT
    kind_index = int(encoded) % LEGAL_ACTION_KIND_COUNT
    return actor, kind_index


def kind_names_by_index(legal_moves: np.ndarray, legal_row_meta: list[dict[str, Any]]) -> dict[int, str]:
    """Server ``kind`` per kind-axis index, read off the legal rows (no client copy of ``AiLegalKind``)."""
    names: dict[int, str] = {}
    for row, meta in zip(legal_moves, legal_row_meta):
        _, kind_index = decode_actor_kind(int(row[FACTOR_ACTOR_KIND]))
        names[kind_index] = str(meta.get("kind") or "?")
    return names


def kind_name(kind_index: int, names: dict[int, str]) -> str:
    return names.get(kind_index, f"?{kind_index}")


def ball_carrier_player_index(observation: dict[str, Any]) -> int | None:
    cells = observation.get("cells") or []
    for cell in cells:
        player = cell.get("player")
        if player and cell.get("hasBall"):
            team_rel = player.get("teamRel")
            if team_rel in (0, "US"):
                return int(player["playerIndex"])
    return None


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


def snapshot_opening_mispick(
    *,
    observation: dict[str, Any],
    policy_obs: dict[str, np.ndarray],
    legal_moves: np.ndarray,
    passing_legal: list[bool],
    soft_mask_legal: list[bool],
    legal_row_meta: list[dict[str, Any]],
    chosen_factors: np.ndarray,
    chosen_label: str,
) -> dict[str, Any]:
    """Frozen decision state for ``diagnose_opening_mispick`` after the episode."""
    return {
        "policy_obs": _snapshot_policy_obs(policy_obs),
        "observation": observation,
        "legal_moves": np.asarray(legal_moves, dtype=np.int64).copy(),
        "passing_legal": np.asarray(passing_legal, dtype=np.int8).copy(),
        "soft_mask_legal": np.asarray(soft_mask_legal, dtype=np.int8).copy(),
        "legal_row_meta": list(legal_row_meta),
        "chosen_factors": np.asarray(chosen_factors, dtype=np.int64).reshape(-1).copy(),
        "chosen_label": chosen_label,
    }


def diagnose_opening_mispick(policy: Any, snapshot: dict[str, Any]) -> dict[str, Any]:
    """Score the opening actor×kind head (biased vs unbiased) at the mispick decision."""
    policy.set_training_mode(False)
    obs_tensor, _ = policy.obs_to_tensor(snapshot["policy_obs"])
    with th.no_grad():
        features = policy.extract_features(obs_tensor)
        latent_pi, _ = policy.mlp_extractor(features)
        policy._get_action_dist_from_latent(latent_pi)

    legal_moves = snapshot["legal_moves"]
    legal_count = int(legal_moves.shape[0])
    soft_mask = snapshot["soft_mask_legal"][:legal_count].astype(bool)
    passing = snapshot["passing_legal"][:legal_count].astype(bool)
    legal_row_meta = snapshot["legal_row_meta"]
    kind_names = kind_names_by_index(legal_moves[:legal_count], legal_row_meta)
    observation = snapshot["observation"]
    chosen = [int(value) for value in np.asarray(snapshot["chosen_factors"]).reshape(-1)]

    logits = policy.action_dist.distributions[FACTOR_ACTOR_KIND].logits[0].detach().cpu().numpy()
    mask = conditional_factor_mask(
        legal_moves,
        legal_move_count=legal_count,
        chosen_factors={},
        factor_slot=FACTOR_ACTOR_KIND,
    )
    preferred_values = preferred_factor_values(
        legal_moves,
        soft_mask,
        legal_move_count=legal_count,
        chosen_factors={},
        factor_slot=FACTOR_ACTOR_KIND,
    )
    bias = np.zeros_like(logits)
    if preferred_values.size > 0:
        bias[preferred_values] = SOFT_MASK_LOGIT_BIAS

    passing_rows = legal_moves[:legal_count][passing] if np.any(passing) else np.empty((0, legal_moves.shape[1]), dtype=legal_moves.dtype)
    passing_actor_kinds = set(int(value) for value in np.unique(passing_rows[:, FACTOR_ACTOR_KIND])) if passing_rows.size else set()

    prob_plain = _masked_softmax(logits, mask)
    prob_biased = _masked_softmax(logits + bias, mask)

    carrier = ball_carrier_player_index(observation)
    rows: list[dict[str, Any]] = []
    for encoded in np.where(mask)[0]:
        actor, kind_index = decode_actor_kind(int(encoded))
        kind = kind_name(kind_index, kind_names)
        rows.append(
            {
                "actor": actor,
                "kind": kind,
                "p_plain": float(prob_plain[encoded]),
                "p_biased": float(prob_biased[encoded]),
                "logit": float(logits[encoded]),
                "soft_mask": bool(encoded in preferred_values),
                "passing": int(encoded) in passing_actor_kinds,
            }
        )
    rows.sort(key=lambda row: row["p_biased"], reverse=True)

    carrier_kinds: dict[str, dict[str, float]] = {}
    if carrier is not None:
        for row in rows:
            if row["actor"] != carrier:
                continue
            bucket = carrier_kinds.setdefault(row["kind"], {"p_plain": 0.0, "p_biased": 0.0})
            bucket["p_plain"] = max(bucket["p_plain"], row["p_plain"])
            bucket["p_biased"] = max(bucket["p_biased"], row["p_biased"])

    chosen_actor, chosen_kind_index = decode_actor_kind(chosen[FACTOR_ACTOR_KIND])
    chosen_kind = kind_name(chosen_kind_index, kind_names)
    chosen_encoded = chosen[FACTOR_ACTOR_KIND]
    legal_ak = int(mask.sum())
    return {
        "chosen": snapshot.get("chosen_label"),
        "chosen_actor_kind": {
            "actor": chosen_actor,
            "kind": chosen_kind,
            "p_plain": float(prob_plain[chosen_encoded]) if mask[chosen_encoded] else 0.0,
            "p_biased": float(prob_biased[chosen_encoded]) if mask[chosen_encoded] else 0.0,
            "soft_mask": bool(chosen_encoded in preferred_values) if mask[chosen_encoded] else False,
        },
        "ball_carrier_player_index": carrier,
        "carrier_kind_mass": carrier_kinds,
        "top_actor_kind": rows[:6],
        "soft_mask_bias": SOFT_MASK_LOGIT_BIAS,
        "legal_count": legal_count,
        "passing_row_count": int(passing.sum()),
        "soft_mask_row_count": int(soft_mask.sum()),
        "preferred_actor_kind_count": int(preferred_values.size),
        "legal_actor_kind_count": legal_ak,
    }


def format_opening_mispick_diag(diag: dict[str, Any]) -> str:
    """One log line after ``turn1=`` when diagnostics ran."""
    if diag.get("error"):
        return f"\n            policy_diag=error:{diag['error']}"
    chosen = diag.get("chosen_actor_kind") or {}
    sm_rows = diag.get("soft_mask_row_count")
    legal_rows = diag.get("legal_count")
    pref = diag.get("preferred_actor_kind_count")
    legal_ak = diag.get("legal_actor_kind_count")
    parts = [
        f"picked={diag.get('chosen')}",
        f"ak={chosen.get('actor')},{chosen.get('kind')}",
        f"p={chosen.get('p_biased', 0):.3f}({chosen.get('p_plain', 0):.3f}plain)",
    ]
    if sm_rows is not None and legal_rows is not None:
        parts.append(f"sm={sm_rows}/{legal_rows}")
    if pref is not None and legal_ak is not None:
        parts.append(f"ak_sm={pref}/{legal_ak}")
    if chosen.get("soft_mask"):
        parts.append("picked_sm")
    carrier = diag.get("ball_carrier_player_index")
    masses = diag.get("carrier_kind_mass") or {}
    if carrier is not None and masses:
        kind_bits = []
        for kind in ("sidestep", "run", "block", "mark", "throw"):
            if kind in masses:
                m = masses[kind]
                kind_bits.append(f"{kind}={m['p_biased']:.2f}/{m['p_plain']:.2f}")
        if kind_bits:
            parts.append(f"carrier_p{carrier}:" + ",".join(kind_bits))
    top = diag.get("top_actor_kind") or []
    if top:
        leader = top[0]
        parts.append(f"top={leader['actor']},{leader['kind']}@{leader['p_biased']:.2f}")
    return "\n            policy_diag=" + " ".join(parts)
