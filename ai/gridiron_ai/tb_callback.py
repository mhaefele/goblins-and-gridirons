"""Stable-Baselines3 callback: flush Goblins & Gridirons episode metrics to TensorBoard.

Owner: TB ``gridiron_*`` scalars from ``info["bb_episode"]`` via ``aggregate_episodes``.
See ``metrics.py`` / ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from stable_baselines3.common.callbacks import BaseCallback

from gridiron_ai.checkpoints import atomic_model_save
from gridiron_ai.metrics import aggregate_episodes, print_metrics


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
