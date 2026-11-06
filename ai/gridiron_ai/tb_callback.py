"""Stable-Baselines3 callback: flush Goblins & Gridirons episode metrics to TensorBoard.

Owner: TB ``gridiron_*`` scalars from ``info["bb_episode"]`` via ``aggregate_episodes``.
Also optional PPO update evidence (approx_kl / Δθ / PASS-step advantages).
See ``metrics.py`` / ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np
from stable_baselines3.common.callbacks import BaseCallback

from gridiron_ai.checkpoints import atomic_model_save
from gridiron_ai.metrics import aggregate_episodes, print_metrics


def _policy_param_l2(model: Any) -> float:
    """L2 norm of trainable policy parameters (CPU float)."""
    total = 0.0
    for param in model.policy.parameters():
        if param.requires_grad:
            total += float(param.detach().float().pow(2).sum().cpu())
    return total**0.5


def iter_pass_step_advantages(
    *,
    advantages: np.ndarray,
    rewards: np.ndarray,
    episode_starts: np.ndarray,
    pass_terminals: list[tuple[int, int]],
) -> list[dict[str, float | int | None]]:
    """Per-PASS GAE / reward on the terminal step and the prior step (pickup / continue).

    ``pass_terminals`` are ``(time_idx, env_idx)`` into the rollout buffer for US drill wins.
    Prior fields are ``None`` when missing or when ``episode_starts[t]`` (new episode).
    Rewards/advantages are whatever the buffer holds (often VecNormalize-scaled).
    """
    rows: list[dict[str, float | int | None]] = []
    for time_idx, env_idx in pass_terminals:
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
    pass_terminals: list[tuple[int, int]],
) -> dict[str, float | int]:
    """Mean of [iter_pass_step_advantages] over one rollout."""
    rows = iter_pass_step_advantages(
        advantages=advantages,
        rewards=rewards,
        episode_starts=episode_starts,
        pass_terminals=pass_terminals,
    )
    score_advs = [float(r["score_adv"]) for r in rows if r["score_adv"] is not None]
    score_rews = [float(r["score_rew"]) for r in rows if r["score_rew"] is not None]
    prior_advs = [float(r["prior_adv"]) for r in rows if r["prior_adv"] is not None]
    prior_rews = [float(r["prior_rew"]) for r in rows if r["prior_rew"] is not None]

    def _mean(values: list[float]) -> float:
        return float(sum(values) / len(values)) if values else float("nan")

    return {
        "pass_n": len(rows),
        "score_adv_mean": _mean(score_advs),
        "score_rew_mean": _mean(score_rews),
        "prior_n": len(prior_advs),
        "prior_adv_mean": _mean(prior_advs),
        "prior_rew_mean": _mean(prior_rews),
    }


class GridironMetricsCallback(BaseCallback):
    """Log harness metrics under TensorBoard ``gridiron_*`` prefixes."""

    def __init__(
        self,
        *,
        profile: str | None = None,
        computer_opponent: str | None = None,
        scenario: str | None = None,
        scenario_difficulty: int | None = None,
        verbose: int = 0,
    ) -> None:
        super().__init__(verbose)
        self.profile = profile
        self.computer_opponent = computer_opponent
        self.scenario = scenario
        self.scenario_difficulty = scenario_difficulty
        self._rollout_episodes: list[dict[str, Any]] = []
        self._session_episodes: list[dict[str, Any]] = []

    def _on_training_start(self) -> None:
        self._session_episodes = []

    def _on_rollout_start(self) -> None:
        self._rollout_episodes = []

    def _on_step(self) -> bool:
        for info in self.locals.get("infos") or []:
            summary = info.get("bb_episode")
            if summary is not None:
                self._rollout_episodes.append(summary)
                self._session_episodes.append(summary)
        return True

    def _on_rollout_end(self) -> None:
        if self.profile is not None:
            self.logger.record("gridiron_game/profile", self.profile)
        if self.computer_opponent is not None:
            self.logger.record("gridiron_game/opponent", self.computer_opponent)
        if self.scenario is not None:
            self.logger.record("gridiron_game/scenario", self.scenario)
        if self.scenario_difficulty is not None:
            self.logger.record("gridiron_game/difficulty", self.scenario_difficulty)

        stats = aggregate_episodes(self._rollout_episodes)
        if stats["episodes"] == 0:
            return

        self.logger.record("gridiron_game/winrate", stats["winrate"])
        self.logger.record("gridiron_game/score_diff", stats["score_diff"])

        self.logger.record("gridiron_score/challenge_cards_us", stats["challenge_cards"])
        self.logger.record("gridiron_score/challenge_cards_them", stats["challenge_cards_them"])
        self.logger.record("gridiron_score/touchdowns_us", stats["touchdowns"])
        self.logger.record("gridiron_score/touchdowns_them", stats["touchdowns_them"])
        self.logger.record("gridiron_score/score_us", stats["score_us"])
        self.logger.record("gridiron_score/score_them", stats["score_them"])

        self.logger.record("gridiron_stats/turns", stats["turns"])
        self.logger.record("gridiron_stats/total_actions", stats["total_actions"])
        self.logger.record("gridiron_stats/legal_actions_avg", stats["legal_actions_avg"])
        self.logger.record("gridiron_stats/submit_avg_ms", stats["submit_avg_ms"])
        self.logger.record("gridiron_stats/submit_error_rate", stats["submit_error_rate"])
        self.logger.record("gridiron_stats/truncation_rate", stats["truncation_rate"])
        self.logger.record("gridiron_stats/ball_rate_us", stats["ball_rate_us"])
        self.logger.record("gridiron_stats/ball_rate_them", stats["ball_rate_them"])
        self.logger.record("gridiron_stats/ball_opp_half_rate_us", stats["ball_opp_half_rate_us"])
        self.logger.record("gridiron_stats/ball_opp_half_rate_them", stats["ball_opp_half_rate_them"])
        self.logger.record("gridiron_stats/ball_proximity_us", stats["ball_proximity_us"])
        self.logger.record("gridiron_stats/ball_gained_us", stats["ball_gained_us"])
        self.logger.record("gridiron_stats/ball_gained_them", stats["ball_gained_them"])

        for bucket, count in sorted(stats["kind_count"].items()):
            self.logger.record(f"gridiron_z_actions/{bucket}", count)

        if self.scenario is not None:
            for bucket, count in sorted(stats["first_kind_count"].items()):
                self.logger.record(f"gridiron_z_first_actions/{bucket}", count)

        if self.verbose >= 1:
            label = self.computer_opponent or "train"
            if self.scenario is not None:
                label = f"{label} {self.scenario}"
                if self.scenario_difficulty is not None:
                    label = f"{label} d{self.scenario_difficulty}"
            print_metrics(f"Train {label} @{self.num_timesteps}", stats)

    def session_summary(self) -> dict[str, Any]:
        """Totals over every finished episode in this ``learn()`` call."""
        return aggregate_episodes(self._session_episodes)


class PeriodicFinalSaveCallback(BaseCallback):
    """Overwrite the continuing-model zip every ``save_freq`` timesteps during ``learn()``."""

    def __init__(self, save_stem: str | Path, save_freq: int, verbose: int = 1) -> None:
        super().__init__(verbose)
        self.save_stem = Path(save_stem)
        self.save_freq = save_freq
        self._last_save = 0

    def _on_training_start(self) -> None:
        self._last_save = self.num_timesteps

    def _on_step(self) -> bool:
        if self.num_timesteps - self._last_save < self.save_freq:
            return True
        atomic_model_save(self.model, self.save_stem)
        self._last_save = self.num_timesteps
        if self.verbose >= 1:
            print(f"Periodic final save at {self.num_timesteps} timesteps → {self.save_stem}.zip")
        return True


class PpoUpdateEvidenceCallback(BaseCallback):
    """Stdout evidence that PPO updates ran and that PASS returns reach prior steps.

    Does **not** wrap ``model.train`` (that put a closure/bound method on the instance and
    broke ``model.save`` via cloudpickle → TensorBoard locks).

    Update evidence: snapshot ‖θ‖ at ``on_rollout_end`` (before ``train``); emit approx_kl /
    Δ‖θ‖ on the next ``on_rollout_start`` or at ``on_training_end`` after the last update.

    PASS advantages: record US ``bb_episode`` buffer slots; after GAE print each PASS and means.
    """

    def __init__(self, *, scenario: str | None = None, verbose: int = 0) -> None:
        super().__init__(verbose)
        self.scenario = scenario
        self._pass_terminals: list[tuple[int, int]] = []
        self._l2_before_train: float | None = None

    def _on_training_start(self) -> None:
        # Leftover from an older wrap-based revision — strip so save never pickles it.
        if "train" in self.model.__dict__:
            del self.model.train
        self._l2_before_train = None
        self._pass_terminals = []

    def _on_training_end(self) -> None:
        if self.verbose >= 1:
            self._emit_update_evidence()

    def _emit_update_evidence(self) -> None:
        if self._l2_before_train is None:
            return
        before = self._l2_before_train
        self._l2_before_train = None
        after = _policy_param_l2(self.model)
        kl = self.logger.name_to_value.get("train/approx_kl")
        loss = self.logger.name_to_value.get("train/loss")
        pg = self.logger.name_to_value.get("train/policy_gradient_loss")
        print(
            f"[gridiron] PPO update approx_kl={kl} loss={loss} pg_loss={pg} "
            f"Δ‖θ‖={after - before:+.6g} ‖θ‖={after:.6g}",
            flush=True,
        )

    def _on_rollout_start(self) -> None:
        # Previous iteration's ``train()`` just finished (except the first rollout).
        if self.verbose >= 1:
            self._emit_update_evidence()
        self._pass_terminals = []

    def _on_step(self) -> bool:
        if self.verbose < 1 or self.scenario is None:
            return True
        # SB3 on-policy: ``on_step`` runs *before* ``rollout_buffer.add`` for this
        # transition — ``pos`` is the index that is about to be written (not pos-1).
        infos = self.locals.get("infos") or []
        buf = self.model.rollout_buffer
        step_idx = int(buf.pos)
        if step_idx < 0 or step_idx >= int(buf.buffer_size):
            return True
        for env_idx, info in enumerate(infos):
            summary = info.get("bb_episode") if isinstance(info, dict) else None
            if not isinstance(summary, dict):
                continue
            if summary.get("result_winner_rel") == "US":
                self._pass_terminals.append((step_idx, env_idx))
        return True

    def _on_rollout_end(self) -> None:
        if self.verbose < 1:
            return
        if self.scenario is not None:
            if not self._pass_terminals:
                print(f"[gridiron] PPO rollout PASS advantages: none (0 US ends)", flush=True)
            else:
                buf = self.model.rollout_buffer
                advantages = np.asarray(buf.advantages)
                rewards = np.asarray(buf.rewards)
                episode_starts = np.asarray(buf.episode_starts)
                rows = iter_pass_step_advantages(
                    advantages=advantages,
                    rewards=rewards,
                    episode_starts=episode_starts,
                    pass_terminals=self._pass_terminals,
                )
                # GAE exists only after the rollout — one line per PASS (not at env submit time).
                for row in rows:
                    prior = (
                        f"prior(adv={row['prior_adv']:+.4f} rew={row['prior_rew']:+.4f})"
                        if row["prior_adv"] is not None and row["prior_rew"] is not None
                        else "prior=(none)"
                    )
                    print(
                        f"[gridiron] PASS adv env={row['env']} t={row['t']} "
                        f"score(adv={row['score_adv']:+.4f} rew={row['score_rew']:+.4f}) "
                        f"{prior} [{self.scenario}]",
                        flush=True,
                    )
                stats = summarize_pass_step_advantages(
                    advantages=advantages,
                    rewards=rewards,
                    episode_starts=episode_starts,
                    pass_terminals=self._pass_terminals,
                )
                print(
                    f"[gridiron] PPO rollout PASS advantages n={stats['pass_n']} "
                    f"score(adv={stats['score_adv_mean']:+.4f} rew={stats['score_rew_mean']:+.4f}) "
                    f"prior(n={stats['prior_n']} adv={stats['prior_adv_mean']:+.4f} rew={stats['prior_rew_mean']:+.4f}) "
                    f"[{self.scenario}]",
                    flush=True,
                )
        # Snapshot before SB3 ``train()`` (runs right after ``on_rollout_end``).
        self._l2_before_train = _policy_param_l2(self.model)
