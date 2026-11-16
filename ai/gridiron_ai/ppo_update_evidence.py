"""Verbose-only PPO update evidence — sidecar, not core training.

Owner: ``--verbose`` drill Passed/Failed blocks + PASS-edge buffer summary after each
PPO update. Wired only when ``PpoUpdateEvidenceCallback(verbose=1)`` is in the
callback list. Does not own metrics/TB (``tb_callback.py``) or rewards (``reward.py``).

DEVIATION: wraps ``model.train`` once so we snapshot the rollout buffer *after* GAE and
*before* the next ``collect_rollouts`` resets it (emitting on ``on_rollout_start`` reads
zeros). ``env=`` is raw ``step_reward``; ``buf=`` is VecNormalize-scaled when on.

Buffer index: SB3 ``collect_rollouts`` runs ``callback.on_step()`` *before*
``rollout_buffer.add()``. Record ``buf.pos`` (slot about to be written), not
``pos - 1`` (previous transition) — that off-by-one made openings show the prior
episode's terminal ``-0.5`` and scoring show the pickup's ``+0.25``.
"""

from __future__ import annotations

from typing import Any

import numpy as np
from stable_baselines3.common.callbacks import BaseCallback
from torch import nn

from gridiron_ai.eval_opening_diag import annotate_move_label, diagnose_opening_mispick
from gridiron_ai.features import (
    JOIN_BOARD_FEAT,
    JOIN_PITCH_FLAT,
    LEGAL_ROW_FEATURE_DIM,
    LEGAL_ROW_FEATURE_SEGMENTS,
    _non_pitch_width,
)


def uninstall_evidence_train_hook(model: Any) -> None:
    """Remove verbose ``model.train`` wrapper so SB3 zip pickle cannot see Subproc locks."""
    if "train" in model.__dict__:
        del model.train
    model.__dict__.pop("_gridiron_evidence_train_hook", None)


def _policy_param_l2(model: Any) -> float:
    total = 0.0
    for param in model.policy.parameters():
        if param.requires_grad:
            total += float(param.detach().float().pow(2).sum().cpu())
    return total**0.5


def _mean(values: list[float]) -> float:
    return float(sum(values) / len(values)) if values else float("nan")


def iter_pass_step_advantages(
    *,
    advantages: np.ndarray,
    rewards: np.ndarray,
    episode_starts: np.ndarray,
    pass_edges: list[tuple[int, int]],
) -> list[dict[str, float | int | None]]:
    """Per PASS-edge GAE / reward and the prior buffer step (pickup / continue)."""
    rows: list[dict[str, float | int | None]] = []
    for time_idx, env_idx in pass_edges:
        row: dict[str, float | int | None] = {
            "t": time_idx,
            "env": env_idx,
            "score_adv": float(advantages[time_idx, env_idx]),
            "score_rew": float(rewards[time_idx, env_idx]),
            "prior_adv": None,
            "prior_rew": None,
        }
        if time_idx > 0 and not bool(episode_starts[time_idx, env_idx]):
            row["prior_adv"] = float(advantages[time_idx - 1, env_idx])
            row["prior_rew"] = float(rewards[time_idx - 1, env_idx])
        rows.append(row)
    return rows


def summarize_pass_step_advantages(
    *,
    advantages: np.ndarray,
    rewards: np.ndarray,
    episode_starts: np.ndarray,
    pass_edges: list[tuple[int, int]] | None = None,
    pass_terminals: list[tuple[int, int]] | None = None,
) -> dict[str, float | int]:
    """Mean of [iter_pass_step_advantages] over one rollout."""
    edges = pass_edges if pass_edges is not None else (pass_terminals or [])
    rows = iter_pass_step_advantages(
        advantages=advantages,
        rewards=rewards,
        episode_starts=episode_starts,
        pass_edges=edges,
    )
    score_advs = [float(r["score_adv"]) for r in rows if r["score_adv"] is not None]
    score_rews = [float(r["score_rew"]) for r in rows if r["score_rew"] is not None]
    prior_advs = [float(r["prior_adv"]) for r in rows if r["prior_adv"] is not None]
    prior_rews = [float(r["prior_rew"]) for r in rows if r["prior_rew"] is not None]
    return {
        "pass_n": len(rows),
        "score_adv_mean": _mean(score_advs),
        "score_rew_mean": _mean(score_rews),
        "prior_n": len(prior_advs),
        "prior_adv_mean": _mean(prior_advs),
        "prior_rew_mean": _mean(prior_rews),
    }


def _plain_rank_by_index(rows: list[dict[str, Any]]) -> dict[int, tuple[int, float]]:
    """Map legal-row index → (1-based plain rank, p_plain)."""
    ordered = sorted(rows, key=lambda row: float(row["p_plain"]), reverse=True)
    return {
        int(row["index"]): (rank, float(row["p_plain"]))
        for rank, row in enumerate(ordered, start=1)
    }


def score_opening_chosen_plain(policy: Any, snapshot: dict[str, Any]) -> dict[str, Any]:
    """Unbiased (plain) mass/rank of the opening move that was actually taken.

    Also returns ``expected_moves`` annotated with the same ``[rank= p=]`` bits eval uses
    (``annotate_move_label``), so failed-drill expected lines match turn1/debug format.
    """
    diag = diagnose_opening_mispick(policy, snapshot, all_rows=True)
    rows = list(diag.get("ranked_rows") or [])
    plain = _plain_rank_by_index(rows)
    chosen_i = int(snapshot.get("chosen_row_index", -1))
    chosen = next((row for row in rows if int(row["index"]) == chosen_i), None)
    expected_moves = expected_move_labels_from_snapshot(snapshot, plain_by_index=plain)
    if chosen is None:
        return {
            "p_chosen": None,
            "rank_chosen": None,
            "passing": False,
            "label": str(snapshot.get("chosen_label") or "?"),
            "expected_moves": expected_moves,
            "plain_by_index": plain,
        }
    rank, p_chosen = plain.get(chosen_i, (None, float(chosen["p_plain"])))
    return {
        "p_chosen": p_chosen,
        "rank_chosen": float(rank) if rank is not None else None,
        "passing": bool(chosen.get("passing")),
        "label": str(snapshot.get("chosen_label") or chosen.get("label") or "?"),
        "expected_moves": expected_moves,
        "plain_by_index": plain,
    }


def format_move_sequence(
    sequence: list[dict[str, Any]] | None,
    *,
    opening_score: dict[str, Any] | None = None,
) -> str:
    """Lesson path only: stop at PASS-edge / TD (``!``), drop coach-window chaff after.

    Move bits use [annotate_move_label] — same ``label [rank= p= reward=…]`` shape as eval turn1.
    """
    if not sequence:
        return "(no moves)"
    steps: list[dict[str, Any]] = []
    for step in sequence:
        steps.append(step)
        label = str(step.get("label") or "")
        if step.get("pass_edge") or step.get("scored_td") or "!" in label:
            break
    parts: list[str] = []
    for index, step in enumerate(steps):
        label = str(step.get("label") or "?")
        score = opening_score if index == 0 else None
        reward = step.get("reward")
        parts.append(
            annotate_move_label(
                label,
                score,
                reward=float(reward) if reward is not None else None,
            )
        )
    return " → ".join(parts)


def _fmt_p_delta(before: float | None, after: float | None) -> tuple[str, str]:
    if before is None or after is None:
        return "n/a", "n/a"
    # .6f so tiny KL/mass moves are not rounded to Δ=+0.000.
    return f"{after - before:+.6f}", f"{before:.6f}→{after:.6f}"


def _fmt_buf_pair(rew: float | None, adv: float | None) -> str:
    if rew is None and adv is None:
        return "buf n/a"
    r = "n/a" if rew is None else f"{rew:+.4f}"
    a = "n/a" if adv is None else f"{adv:+.4f}"
    return f"buf(rew={r} adv={a})"


def _fmt_mass_rank(
    p_before: float | None,
    p_after: float | None,
    rank_before: float | None,
    rank_after: float | None,
) -> str:
    d_bit, p_bit = _fmt_p_delta(p_before, p_after)
    if rank_before is not None and rank_after is not None:
        rank_bit = f"rank {rank_before:.0f}→{rank_after:.0f}"
    else:
        rank_bit = "rank n/a"
    return f"mass {p_bit} (Δ={d_bit}, {rank_bit})"


def _annotate_opening_label(
    label: str,
    *,
    p: float | None,
    rank: float | None,
) -> str:
    """Opening move with shared eval ``[rank= p=]`` when scores exist."""
    if p is None or rank is None:
        return label
    return annotate_move_label(label, {"rank": int(rank), "p": float(p)})


def format_passed_drill_block(
    *,
    sequence: str,
    opening_label: str,
    opening_reward: float | None,
    p_before: float | None,
    p_after: float | None,
    rank_before: float | None,
    rank_after: float | None,
    scenario: str | None,
    opening_buf_rew: float | None = None,
    opening_buf_adv: float | None = None,
) -> str:
    """One multi-line block for a US drill (PPO bits printed once per update separately).

    No separate ``scoring:`` line — on one-shot LBR passes opening *is* the score, and
    multi-step rewards already sit on ``steps:`` via ``annotate_move_label``.
    """
    tag = f" [{scenario}]" if scenario else ""
    open_r = "n/a" if opening_reward is None else f"{opening_reward:+.2f}"
    open_move = _annotate_opening_label(opening_label, p=p_after, rank=rank_after)
    return "\n".join(
        [
            f"[gridiron] Passed the drill.{tag}",
            f"  steps: {sequence}",
            f"  opening: {open_move} env={open_r} {_fmt_buf_pair(opening_buf_rew, opening_buf_adv)} "
            f"{_fmt_mass_rank(p_before, p_after, rank_before, rank_after)}",
        ]
    )


def _terminal_differs_from_opening(
    *,
    opening_reward: float | None,
    last_reward: float | None,
    opening_buf_rew: float | None,
    opening_buf_adv: float | None,
    last_buf_rew: float | None,
    last_buf_adv: float | None,
) -> bool:
    """True when episode-end env/buf is not just a copy of the opening step."""
    if opening_reward != last_reward:
        return True
    if opening_buf_rew != last_buf_rew or opening_buf_adv != last_buf_adv:
        return True
    return False


def format_failed_drill_block(
    *,
    sequence: str,
    opening_label: str,
    opening_passing: bool,
    opening_reward: float | None,
    last_reward: float | None,
    p_before: float | None,
    p_after: float | None,
    rank_before: float | None,
    rank_after: float | None,
    scenario: str | None,
    opening_buf_rew: float | None = None,
    opening_buf_adv: float | None = None,
    last_buf_rew: float | None = None,
    last_buf_adv: float | None = None,
    expected_moves: str | None = None,
    opening_soft_mask: bool | None = None,
) -> str:
    """One multi-line block for a THEM drill (PPO bits printed once per update separately).

    ``last:`` is the episode-terminal step (often window-loss −0.5). Omitted when it
    matches the opening env/buf — common on one-shot LBR fails where the slap *is* the end.
    """
    tag = f" [{scenario}]" if scenario else ""
    grade = "passing" if opening_passing else "non-passing"
    soft_bit = (
        ", softmask"
        if opening_soft_mask is True
        else (", plain" if opening_soft_mask is False else "")
    )
    open_r = "n/a" if opening_reward is None else f"{opening_reward:+.2f}"
    open_move = _annotate_opening_label(opening_label, p=p_after, rank=rank_after)
    lines = [
        f"[gridiron] Failed the drill.{tag}",
        f"  steps: {sequence}",
    ]
    if expected_moves:
        lines.append(f"  expected: {expected_moves}")
    lines.append(
        f"  opening: {open_move} ({grade}{soft_bit}) env={open_r} "
        f"{_fmt_buf_pair(opening_buf_rew, opening_buf_adv)} "
        f"{_fmt_mass_rank(p_before, p_after, rank_before, rank_after)}"
    )
    if _terminal_differs_from_opening(
        opening_reward=opening_reward,
        last_reward=last_reward,
        opening_buf_rew=opening_buf_rew,
        opening_buf_adv=opening_buf_adv,
        last_buf_rew=last_buf_rew,
        last_buf_adv=last_buf_adv,
    ):
        last_r = "n/a" if last_reward is None else f"{last_reward:+.2f}"
        lines.append(f"  last: env={last_r} {_fmt_buf_pair(last_buf_rew, last_buf_adv)}")
    return "\n".join(lines)


def expected_move_labels_from_snapshot(
    snapshot: dict[str, Any],
    *,
    plain_by_index: dict[int, tuple[int, float]] | None = None,
) -> str:
    """Format opening ``passingLegal`` rows — same ``[rank= p=]`` as eval turn1 when scored."""
    from gridiron_ai.eval_debug import ScenarioEvalDebug

    # Snapshots store numpy arrays — do not use ``x or []`` (ambiguous truth value).
    raw_passing = snapshot.get("passing_legal")
    passing = list(raw_passing) if raw_passing is not None else []
    raw_meta = snapshot.get("legal_row_meta")
    meta = list(raw_meta) if raw_meta is not None else []
    rewards = snapshot.get("rewards")
    labels: list[str] = []
    for index, is_pass in enumerate(passing):
        if not bool(is_pass):
            continue
        action = None
        if index < len(meta):
            row = meta[index]
            action = row.get("action") if isinstance(row, dict) else None
            if action is None and isinstance(row, dict):
                action = row
        if isinstance(action, dict):
            label = ScenarioEvalDebug.format_action(
                action, rewards=rewards, expected_move=True
            )
        else:
            label = f"row{index}*"
        score = None
        if plain_by_index is not None and index in plain_by_index:
            rank, p = plain_by_index[index]
            score = {"rank": rank, "p": p}
        labels.append(annotate_move_label(label, score))
    return " ".join(labels) if labels else "(none)"


def format_pass_edge_buffer_line(
    *,
    stats: dict[str, float | int],
    scenario: str | None,
) -> str:
    """Rollout-buffer GAE on PASS-edge + prior step (VecNormalize scale when enabled)."""
    tag = f" [{scenario}]" if scenario else ""
    return (
        f"[gridiron] PASS-edge buffer (often VecNormalize-scaled): "
        f"n={int(stats['pass_n'])} "
        f"score(rew={stats['score_rew_mean']:+.4f} adv={stats['score_adv_mean']:+.4f}) "
        f"prior(n={int(stats['prior_n'])} rew={stats['prior_rew_mean']:+.4f} "
        f"adv={stats['prior_adv_mean']:+.4f}). "
        f"prior_adv is credit on the move before the score (pickup / continue).{tag}"
    )


def format_ppo_update_line(
    *,
    theta_before: float,
    theta_after: float,
    approx_kl: object,
    scenario: str | None,
    n_passed: int,
    n_failed: int,
) -> str:
    """Once per PPO update — not repeated on every drill block."""
    tag = f" [{scenario}]" if scenario else ""
    return (
        f"[gridiron] PPO update{tag}: passed={n_passed} failed={n_failed}; "
        f"‖θ‖ {theta_before:.6g}→{theta_after:.6g} "
        f"(Δ={theta_after - theta_before:+.6g}), approx_kl={approx_kl}"
    )


def row_scorer_segment_mean_abs(policy: Any) -> list[tuple[str, float]]:
    """Mean |W| of ``row_scorer`` first Linear columns per join input segment.

    Order: pitchFlat, nonPitch, then [LEGAL_ROW_FEATURE_SEGMENTS].
    """
    scorer = getattr(policy, "row_scorer", None)
    if scorer is None or not isinstance(scorer, nn.Sequential) or not scorer:
        return []
    first = scorer[0]
    if not isinstance(first, nn.Linear):
        return []
    weight = first.weight.detach().float()
    in_features = int(weight.shape[1])
    if in_features < JOIN_BOARD_FEAT + LEGAL_ROW_FEATURE_DIM:
        return []
    out: list[tuple[str, float]] = []
    col = 0
    for name, width in (
        ("pitchFlat", JOIN_PITCH_FLAT),
        ("nonPitch", _non_pitch_width()),
    ):
        out.append((name, float(weight[:, col : col + width].abs().mean().cpu())))
        col += width
    for name, width in LEGAL_ROW_FEATURE_SEGMENTS:
        out.append((name, float(weight[:, col : col + width].abs().mean().cpu())))
        col += width
    return out


def format_row_scorer_weights_line(
    *,
    after: list[tuple[str, float]],
    before: list[tuple[str, float]] | None = None,
    scenario: str | None = None,
) -> str:
    """Verbose join-input |W| meters — watch ``targetHasBall`` across PPO updates."""
    tag = f" [{scenario}]" if scenario else ""
    before_map = {name: value for name, value in before} if before else {}
    parts: list[str] = []
    for name, value in after:
        prior = before_map.get(name)
        if prior is None:
            parts.append(f"{name}={value:.4g}")
        else:
            parts.append(f"{name}={value:.4g}(Δ={value - prior:+.3g})")
    return f"[gridiron] row_scorer |W|{tag}: " + " ".join(parts)


def _first_opening_lesson_step(sequence: list[dict[str, Any]]) -> dict[str, Any] | None:
    for step in sequence:
        if step.get("passing"):
            return step
    return None


def _scoring_step(sequence: list[dict[str, Any]]) -> dict[str, Any] | None:
    for step in sequence:
        label = str(step.get("label") or "")
        if step.get("scored_td") or step.get("pass_edge") or "!" in label:
            return step
    return None


def _step_reward(step: dict[str, Any] | None) -> float | None:
    if step is None or step.get("reward") is None:
        return None
    return float(step["reward"])


def _step_index(sequence: list[dict[str, Any]], step: dict[str, Any] | None) -> int | None:
    if step is None:
        return None
    for index, row in enumerate(sequence):
        if row is step:
            return index
    return None


def _unflatten_rollout_field(arr: np.ndarray, *, buffer_size: int, n_envs: int) -> np.ndarray:
    """Undo SB3 ``RolloutBuffer.get`` ``swap_and_flatten`` → ``(buffer_size, n_envs)``."""
    flat = np.asarray(arr)
    if flat.shape == (buffer_size, n_envs):
        return flat
    if flat.shape == (n_envs * buffer_size, 1) or flat.shape == (n_envs * buffer_size,):
        return flat.reshape(n_envs, buffer_size).T
    return flat


def snapshot_rollout_buffer(buf: Any) -> dict[str, np.ndarray]:
    """Copy rewards/advantages/episode_starts in ``(buffer_size, n_envs)`` layout."""
    buffer_size = int(buf.buffer_size)
    n_envs = int(buf.n_envs)
    return {
        "rewards": np.array(buf.rewards, copy=True),
        "advantages": _unflatten_rollout_field(
            np.array(buf.advantages, copy=True),
            buffer_size=buffer_size,
            n_envs=n_envs,
        ),
        "episode_starts": np.array(buf.episode_starts, copy=True),
    }


def _buf_rew_adv(
    snapshot: Any,
    *,
    time_idx: int | None,
    env_idx: int | None,
) -> tuple[float | None, float | None]:
    if snapshot is None or time_idx is None or env_idx is None:
        return None, None
    try:
        rewards = snapshot["rewards"] if isinstance(snapshot, dict) else snapshot.rewards
        advantages = snapshot["advantages"] if isinstance(snapshot, dict) else snapshot.advantages
        rew = float(np.asarray(rewards)[time_idx, env_idx])
        adv = float(np.asarray(advantages)[time_idx, env_idx])
    except (IndexError, TypeError, ValueError, AttributeError, KeyError):
        return None, None
    return rew, adv


def _buf_ts_at(buf_ts: list[int], index: int | None) -> int | None:
    if index is None or index < 0 or index >= len(buf_ts):
        return None
    return int(buf_ts[index])


def _pass_edges_from_graded(graded: list[dict[str, Any]]) -> list[tuple[int, int]]:
    """Scoring-step (t, env) for each US drill."""
    edges: list[tuple[int, int]] = []
    for row in graded:
        if row.get("outcome") != "US":
            continue
        env_idx = row.get("env_idx")
        if env_idx is None:
            continue
        raw_seq = list(row.get("raw_sequence") or [])
        buf_ts = list(row.get("buf_ts") or [])
        scoring = _scoring_step(raw_seq)
        time_idx = _buf_ts_at(buf_ts, _step_index(raw_seq, scoring))
        if time_idx is not None:
            edges.append((time_idx, int(env_idx)))
    return edges


class PpoUpdateEvidenceCallback(BaseCallback):
    """Verbose-only sidecar: after each PPO update, print what the rewards did."""

    def __init__(self, *, scenario: str | None = None, verbose: int = 0) -> None:
        super().__init__(verbose)
        self.scenario = scenario
        self._graded_openings: list[dict[str, Any]] = []
        self._graded_before: list[dict[str, Any]] = []
        self._env_buf_ts: dict[int, list[int]] = {}
        self._l2_before_train: float | None = None
        self._join_w_before: list[tuple[str, float]] | None = None
        self._buffer_snapshot: dict[str, np.ndarray] | None = None

    def _on_training_start(self) -> None:
        uninstall_evidence_train_hook(self.model)
        self._l2_before_train = None
        self._join_w_before = None
        self._graded_openings = []
        self._graded_before = []
        self._env_buf_ts = {}
        self._buffer_snapshot = None
        if self.verbose >= 1:
            self._install_train_hook()

    def _install_train_hook(self) -> None:
        model = self.model
        if model.__dict__.get("_gridiron_evidence_train_hook"):
            return
        original_train = model.train
        callback = self

        def train_with_evidence(*args: Any, **kwargs: Any) -> Any:
            result = original_train(*args, **kwargs)
            callback._buffer_snapshot = snapshot_rollout_buffer(model.rollout_buffer)
            callback._emit_update_evidence()
            return result

        model.train = train_with_evidence  # type: ignore[method-assign]
        model.__dict__["_gridiron_evidence_train_hook"] = True

    def _on_training_end(self) -> None:
        if self.verbose >= 1:
            self._emit_update_evidence()
        # Drop the closure before any checkpoint — it closes over ``model`` → SubprocVecEnv locks.
        uninstall_evidence_train_hook(self.model)

    def _emit_update_evidence(self) -> None:
        if self._l2_before_train is None:
            return
        theta_before = self._l2_before_train
        self._l2_before_train = None
        theta_after = _policy_param_l2(self.model)
        kl = self.logger.name_to_value.get("train/approx_kl")
        graded = self._graded_before
        self._graded_before = []
        snapshot = self._buffer_snapshot
        self._buffer_snapshot = None
        pass_edges = _pass_edges_from_graded(graded)
        if pass_edges and snapshot is not None:
            stats = summarize_pass_step_advantages(
                advantages=snapshot["advantages"],
                rewards=snapshot["rewards"],
                episode_starts=snapshot["episode_starts"],
                pass_edges=pass_edges,
            )
            if int(stats["pass_n"]) > 0:
                print(format_pass_edge_buffer_line(stats=stats, scenario=self.scenario), flush=True)
        n_passed = 0
        n_failed = 0
        for row in graded:
            outcome = row.get("outcome")
            raw_seq = list(row.get("raw_sequence") or [])
            buf_ts = list(row.get("buf_ts") or [])
            env_idx = row.get("env_idx")
            env_i = int(env_idx) if env_idx is not None else None
            scored = score_opening_chosen_plain(self.model.policy, row["snapshot"])
            opening_score = None
            if row.get("p_before") is not None and row.get("rank_before") is not None:
                opening_score = {
                    "rank": int(row["rank_before"]),
                    "p": float(row["p_before"]),
                }
            sequence = format_move_sequence(raw_seq, opening_score=opening_score)
            if outcome == "US":
                n_passed += 1
                opening = _first_opening_lesson_step(raw_seq)
                open_t = _buf_ts_at(buf_ts, _step_index(raw_seq, opening))
                open_buf_rew, open_buf_adv = _buf_rew_adv(snapshot, time_idx=open_t, env_idx=env_i)
                print(
                    format_passed_drill_block(
                        sequence=sequence,
                        opening_label=str(
                            (opening or {}).get("label") or row.get("label") or "?"
                        ),
                        opening_reward=_step_reward(opening),
                        p_before=row.get("p_before"),
                        p_after=scored.get("p_chosen"),
                        rank_before=row.get("rank_before"),
                        rank_after=scored.get("rank_chosen"),
                        scenario=self.scenario,
                        opening_buf_rew=open_buf_rew,
                        opening_buf_adv=open_buf_adv,
                    ),
                    flush=True,
                )
                continue
            if outcome != "THEM":
                continue
            n_failed += 1
            first = raw_seq[0] if raw_seq else None
            last = raw_seq[-1] if raw_seq else None
            first_t = _buf_ts_at(buf_ts, 0 if raw_seq else None)
            last_t = _buf_ts_at(buf_ts, len(raw_seq) - 1 if raw_seq else None)
            first_buf_rew, first_buf_adv = _buf_rew_adv(snapshot, time_idx=first_t, env_idx=env_i)
            last_buf_rew, last_buf_adv = _buf_rew_adv(snapshot, time_idx=last_t, env_idx=env_i)
            snap = row.get("snapshot") if isinstance(row.get("snapshot"), dict) else {}
            opening_soft = first.get("soft_mask") if isinstance(first, dict) else None
            if opening_soft is None and snap:
                chosen_i = snap.get("chosen_row_index")
                soft_arr = snap.get("soft_mask_legal")
                if chosen_i is not None and soft_arr is not None:
                    soft_list = list(soft_arr)
                    if 0 <= int(chosen_i) < len(soft_list):
                        opening_soft = bool(soft_list[int(chosen_i)])
            expected = scored.get("expected_moves")
            if not expected and snap:
                expected = expected_move_labels_from_snapshot(snap)
            print(
                format_failed_drill_block(
                    sequence=sequence,
                    opening_label=str(row.get("label") or (first or {}).get("label") or "?"),
                    opening_passing=bool(row.get("passing")),
                    opening_reward=_step_reward(first),
                    last_reward=_step_reward(last),
                    p_before=row.get("p_before"),
                    p_after=scored.get("p_chosen"),
                    rank_before=row.get("rank_before"),
                    rank_after=scored.get("rank_chosen"),
                    scenario=self.scenario,
                    opening_buf_rew=first_buf_rew,
                    opening_buf_adv=first_buf_adv,
                    last_buf_rew=last_buf_rew,
                    last_buf_adv=last_buf_adv,
                    expected_moves=expected,
                    opening_soft_mask=bool(opening_soft) if opening_soft is not None else None,
                ),
                flush=True,
            )
        if n_passed or n_failed:
            print(
                format_ppo_update_line(
                    theta_before=theta_before,
                    theta_after=theta_after,
                    approx_kl=kl,
                    scenario=self.scenario,
                    n_passed=n_passed,
                    n_failed=n_failed,
                ),
                flush=True,
            )
            join_before = self._join_w_before
            self._join_w_before = None
            join_after = row_scorer_segment_mean_abs(self.model.policy)
            if join_after:
                print(
                    format_row_scorer_weights_line(
                        after=join_after,
                        before=join_before,
                        scenario=self.scenario,
                    ),
                    flush=True,
                )

    def _on_rollout_start(self) -> None:
        self._graded_openings = []
        self._env_buf_ts = {}

    def _on_step(self) -> bool:
        if self.verbose < 1 or self.scenario is None:
            return True
        infos = self.locals.get("infos") or []
        buf = getattr(self.model, "rollout_buffer", None)
        # SB3: on_step runs before rollout_buffer.add — pos is the write slot for
        # *this* step's reward, not the previous transition.
        t = int(buf.pos) if buf is not None else None
        if t is not None and t >= int(buf.buffer_size):
            t = None
        dones = self.locals.get("dones")
        for env_idx, info in enumerate(infos):
            if not isinstance(info, dict):
                continue
            if t is not None:
                self._env_buf_ts.setdefault(env_idx, []).append(t)
            summary = info.get("bb_episode")
            if isinstance(summary, dict):
                snap = summary.get("opening_snapshot")
                if isinstance(snap, dict):
                    self._graded_openings.append(
                        {
                            "snapshot": snap,
                            "outcome": summary.get("result_winner_rel"),
                            "sequence": summary.get("move_sequence") or [],
                            "buf_ts": list(self._env_buf_ts.get(env_idx) or []),
                            "env_idx": env_idx,
                        }
                    )
                self._env_buf_ts.pop(env_idx, None)
            elif dones is not None and bool(np.asarray(dones).reshape(-1)[env_idx]):
                self._env_buf_ts.pop(env_idx, None)
        return True

    def _on_rollout_end(self) -> None:
        if self.verbose < 1:
            return
        if self.scenario is not None:
            graded_before: list[dict[str, Any]] = []
            for item in self._graded_openings:
                scored = score_opening_chosen_plain(self.model.policy, item["snapshot"])
                raw_seq = list(item.get("sequence") or [])
                graded_before.append(
                    {
                        "snapshot": item["snapshot"],
                        "outcome": item.get("outcome"),
                        "p_before": scored.get("p_chosen"),
                        "rank_before": scored.get("rank_chosen"),
                        "passing": scored.get("passing"),
                        "label": scored.get("label"),
                        "raw_sequence": raw_seq,
                        "sequence": format_move_sequence(
                            raw_seq,
                            opening_score=(
                                {
                                    "rank": int(scored["rank_chosen"]),
                                    "p": float(scored["p_chosen"]),
                                }
                                if scored.get("rank_chosen") is not None
                                and scored.get("p_chosen") is not None
                                else None
                            ),
                        ),
                        "buf_ts": list(item.get("buf_ts") or []),
                        "env_idx": item.get("env_idx"),
                    }
                )
            self._graded_before = graded_before
        self._l2_before_train = _policy_param_l2(self.model)
        self._join_w_before = row_scorer_segment_mean_abs(self.model.policy)
