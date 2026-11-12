"""Gymnasium env for MaskablePPO — vs COMPUTER or browser human.

Owner: Gymnasium reset/step/action_masks over ``GridironClient``; wires reward +
episode metrics. Does **not** own packing (Kotlin), curriculum, or PPO loops.
See ``ai/OWNERSHIP.md`` / ``DESIGN.md`` (seats).

MaskablePPO callbacks: Dict obs; ``action_masks()`` enables ``0..legal_move_count-1``.
Policy scores legal rows joined to the board (``LegalRowActorCriticPolicy``).
Modes: ``computer`` / ``human``.

# DEVIATION: one POST /api/ai/.../submit per decision (reward frame + next obs).
# forSeat tells the server this client plays one seat (policy_seat).
"""

from __future__ import annotations

import time
from typing import Any, Callable

import gymnasium as gym
import numpy as np
from gymnasium import spaces

from gridiron_ai.actions import (
    ResolvedAction,
    legal_row_action_mask,
    legal_row_action_n,
    resolve_legal_row_index,
)
from gridiron_ai.api.client import GridironApiError, GridironClient, flatten_packed_submit, submit_rewards_after, submit_scoring, submit_terminal_meta
from gridiron_ai.constants import SCHEMA_VERSION
from gridiron_ai.eval_debug import ScenarioEvalDebug, append_scenario_draw_log
from gridiron_ai.api.harness_wire import (
    AiLegalChoiceDict,
    ComputerOpponentKind,
    EpisodeMode,
    HarnessRewardSnapshotDict,
    PackedStepDict,
    PackedSubmitDict,
    ScenarioName,
    TeamSide,
    TrainingOutcome,
)
from gridiron_ai.metrics import EpisodeActionStats, total_score
from gridiron_ai.obs import dict_observation_space, slice_packed_step
from gridiron_ai.reward import (
    REWARD_PROFILES,
    RewardConfig,
    RewardProfileName,
    is_episode_ended,
    step_reward,
    training_outcome_winner_rel,
)

OPPONENT_COMPUTER = "computer"
# Both seats HUMAN; harness only submits for policy_seat; server waits while the other seat
# (browser human) acts — no ComputerCoachAgent.
OPPONENT_HUMAN = "human"

_VALID_OPPONENT_MODES = (OPPONENT_COMPUTER, OPPONENT_HUMAN)

# Wire ComputerOpponentKind names — ONNX seats a reward profile via onnx_profile.
COMPUTER_KIND_LOOKAHEAD = ComputerOpponentKind.LOOKAHEAD
COMPUTER_KIND_HEURISTIC = ComputerOpponentKind.HEURISTIC
COMPUTER_KIND_ONNX = ComputerOpponentKind.ONNX
_VALID_COMPUTER_KINDS = (COMPUTER_KIND_LOOKAHEAD, COMPUTER_KIND_HEURISTIC, COMPUTER_KIND_ONNX)

# Metrics label when the server ended the episode via training coach-window (not MatchResultKind).
_SCENARIO_RESULT_KIND = "scenario"


def validate_computer_opponent(kind: ComputerOpponentKind, onnx_profile: str | None) -> None:
    """LOOKAHEAD/HEURISTIC take no profile; ONNX requires a ``REWARD_PROFILES`` id."""
    if kind not in _VALID_COMPUTER_KINDS:
        raise ValueError(f"computer_opponent must be one of {_VALID_COMPUTER_KINDS}, got {kind!r}")
    if kind == COMPUTER_KIND_ONNX:
        if onnx_profile not in REWARD_PROFILES:
            raise ValueError(
                f"onnx_profile must be one of {REWARD_PROFILES} when computer_opponent=ONNX, "
                f"got {onnx_profile!r}"
            )
        return
    if onnx_profile is not None:
        raise ValueError(
            f"onnx_profile is only valid for ONNX (got computer_opponent={kind!r}, "
            f"onnx_profile={onnx_profile!r})"
        )


def opponent_metrics_label(kind: ComputerOpponentKind, onnx_profile: str | None) -> str:
    """TB / eval-file label: ``lookahead``, ``heuristic``, or ``onnx-general``."""
    if kind == COMPUTER_KIND_ONNX:
        return f"onnx-{onnx_profile}"
    return str(kind).lower()


class GridironEnv(gym.Env):
    """One match at a time over the AI HTTP API (vs computer or vs human)."""

    metadata = {"render_modes": []}

    def __init__(
        self,
        *,
        base_url: str = "http://localhost:18080",
        same_team_both_seats: bool = True,
        max_episode_steps: int = 2_000,
        continue_on_submit_error: bool = False,
        opponent_mode: str = OPPONENT_COMPUTER,
        computer_opponent: ComputerOpponentKind = ComputerOpponentKind.LOOKAHEAD,
        onnx_profile: RewardProfileName | None = None,
        policy_seat: TeamSide = TeamSide.HOME,
        turn_timeout_s: float = 180.0,
        on_match_created: Callable[[str], None] | None = None,
        client: GridironClient | None = None,
        reward_config: RewardConfig | None = None,
        scenario: ScenarioName | None = None,
        scenario_difficulty: int = 0,
        episode_mode: EpisodeMode = EpisodeMode.EVAL,
        soft_mask_bias: bool = False,
        verbose: bool = False,
    ) -> None:
        super().__init__()
        if opponent_mode not in _VALID_OPPONENT_MODES:
            raise ValueError(
                f"opponent_mode must be one of {_VALID_OPPONENT_MODES}, got {opponent_mode!r}"
            )
        if policy_seat not in (TeamSide.HOME, TeamSide.AWAY):
            raise ValueError(f"policy_seat must be HOME or AWAY, got {policy_seat!r}")
        validate_computer_opponent(computer_opponent, onnx_profile)
        if not 0 <= scenario_difficulty <= 5:
            raise ValueError(f"scenario_difficulty must be 0–5, was {scenario_difficulty}")
        if episode_mode not in (EpisodeMode.TRAIN, EpisodeMode.EVAL):
            raise ValueError(f"episode_mode must be TRAIN or EVAL, got {episode_mode!r}")

        self._owns_client = client is None
        self.client = client or GridironClient(base_url=base_url)
        self.same_team_both_seats = same_team_both_seats
        self.max_episode_steps = max_episode_steps
        self.continue_on_submit_error = continue_on_submit_error
        self.opponent_mode = opponent_mode
        self.computer_opponent = computer_opponent
        self.onnx_profile = onnx_profile
        self.policy_seat = policy_seat
        self.turn_timeout_s = turn_timeout_s
        self.on_match_created = on_match_created
        self.reward_config = reward_config or RewardConfig()
        self.scenario = scenario
        self.scenario_difficulty = scenario_difficulty
        self.episode_mode = episode_mode
        # Pack 0/1 preferred-row flags into obs when True. Strength is policy-side only.
        self.soft_mask_bias = soft_mask_bias
        self.verbose = verbose

        self.observation_space = dict_observation_space()
        self.action_space = spaces.Discrete(legal_row_action_n())

        self.match_id: str | None = None
        self._match_released = False
        self._current_step_dto: PackedStepDict | None = None
        self._episode_steps = 0
        self._action_stats = EpisodeActionStats()
        self._pending_episode_summary: dict[str, Any] | None = None
        self._pending_submit_error: dict[str, Any] | None = None
        # Train --verbose only: feed for ``ppo_update_evidence`` (opening menu + played path).
        self._episode_opening_snapshot: dict[str, Any] | None = None
        self._episode_move_sequence: list[dict[str, Any]] = []
        self._turn_start_score_us = 0
        self._tracked_turn_number = 0
        self._sampled_turn_starts: set[int] = set()
        self._scenario_eval_debug = ScenarioEvalDebug() if scenario is not None else None

    def apply_segment_config(
        self,
        *,
        opponent_mode: str,
        computer_opponent: ComputerOpponentKind,
        onnx_profile: RewardProfileName | None,
        policy_seat: TeamSide,
        reward_config: RewardConfig,
        scenario: ScenarioName | None,
        scenario_difficulty: int,
        episode_mode: EpisodeMode,
        soft_mask_bias: bool,
        verbose: bool = False,
    ) -> None:
        """Reconfigure an idle worker between train/eval segments (pool reuse)."""
        if opponent_mode not in _VALID_OPPONENT_MODES:
            raise ValueError(
                f"opponent_mode must be one of {_VALID_OPPONENT_MODES}, got {opponent_mode!r}"
            )
        if policy_seat not in (TeamSide.HOME, TeamSide.AWAY):
            raise ValueError(f"policy_seat must be HOME or AWAY, got {policy_seat!r}")
        validate_computer_opponent(computer_opponent, onnx_profile)
        if not 0 <= scenario_difficulty <= 5:
            raise ValueError(f"scenario_difficulty must be 0–5, was {scenario_difficulty}")
        if episode_mode not in (EpisodeMode.TRAIN, EpisodeMode.EVAL):
            raise ValueError(f"episode_mode must be TRAIN or EVAL, got {episode_mode!r}")

        self._release_match()
        self.opponent_mode = opponent_mode
        self.computer_opponent = computer_opponent
        self.onnx_profile = onnx_profile
        self.policy_seat = policy_seat
        self.reward_config = reward_config
        self.scenario = scenario
        self.scenario_difficulty = scenario_difficulty
        self.episode_mode = episode_mode
        self.soft_mask_bias = soft_mask_bias
        self.verbose = verbose
        if scenario is not None:
            if self._scenario_eval_debug is None:
                self._scenario_eval_debug = ScenarioEvalDebug()
        else:
            self._scenario_eval_debug = None

    def action_masks(self) -> np.ndarray:
        """True for live legal-row indices this step (pad slots masked off)."""
        return legal_row_action_mask(legal_move_count=len(self._legal_action_ids))

    def reset(
        self, *, seed: int | None = None, options: dict[str, Any] | None = None
    ) -> tuple[dict[str, np.ndarray], dict[str, Any]]:
        super().reset(seed=seed)

        try:
            self._start_new_match_on_server(options or {})
            self._verify_server_schema_compatibility()
        except TimeoutError as exc:
            print(f"[gridiron] {exc}", flush=True)
            raise
        self._pending_episode_summary = None
        self._pending_submit_error = None

        return self._build_observation(), self._get_match_metadata()

    def step(
        self, chosen_row_index: np.ndarray | int
    ) -> tuple[dict[str, np.ndarray], float, bool, bool, dict[str, Any]]:
        self._ensure_match_is_active()
        self._pending_episode_summary = None
        self._pending_submit_error = None

        try:
            # Gym may call step() once more after a prior terminal (or reset landed
            # on a finished match). Always attach bb_episode — do not drop it.
            if self._is_match_already_finished():
                self._pending_episode_summary = self._episode_end_summary(truncated=False)
                info = self._get_match_metadata()
                self._release_match()
                return self._build_observation(), 0.0, True, False, info

            if not self._legal_action_ids:
                raise RuntimeError(
                    "Empty legal choices before submit "
                    f"match_id={self.match_id} resultKind={self._current_step_dto.get('resultKind') if self._current_step_dto else None} "
                    f"actingSeat={self._current_step_dto.get('actingSeat') if self._current_step_dto else None} "
                    f"perspectiveSeat={self._current_step_dto.get('perspectiveSeat') if self._current_step_dto else None} "
                    f"episode_steps={self._episode_steps} scenario={self.scenario} "
                    f"opponent_mode={self.opponent_mode} policy_seat={self.policy_seat}"
                )
            resolved = resolve_legal_row_index(
                chosen_row_index,
                legal_moves=self._legal_moves,
                legal_rows=self._legal_rows,
            )
            self._action_stats.record(resolved.sampled_kind)
            self._action_stats.record_legal_count(len(self._legal_action_ids))

            row_i = int(np.asarray(chosen_row_index, dtype=np.int64).reshape(-1)[0])
            policy_obs = self._build_observation()
            # Train --verbose: freeze opening for PPO chosen-mass before→after (joined at episode end).
            if (
                self.verbose
                and self.scenario is not None
                and self.episode_mode == EpisodeMode.TRAIN
                and self._episode_steps == 0
            ):
                from gridiron_ai.eval_opening_diag import snapshot_opening_mispick

                opening_rewards = self._harness_rewards(self._current_step_dto)
                self._episode_opening_snapshot = snapshot_opening_mispick(
                    rewards=opening_rewards,
                    policy_obs=policy_obs,
                    legal_moves=np.asarray(self._legal_moves, dtype=np.int64),
                    passing_legal=self._passing_legal,
                    soft_mask_legal=self._server_soft_mask_legal,
                    legal_row_meta=self._legal_row_meta,
                    chosen_row_index=row_i,
                    chosen_label=ScenarioEvalDebug.format_action(
                        resolved.legal_choice,
                        rewards=opening_rewards,
                        expected_move=bool(
                            row_i < len(self._passing_legal) and self._passing_legal[row_i]
                        ),
                    ),
                )
            reward, submit_error = self._submit_resolved_action(
                resolved,
                chosen_row_index=row_i,
                policy_obs=policy_obs,
            )
            if submit_error is not None:
                return self._finish_after_submit_error(submit_error)

            match_over = self._is_episode_finished()
            step_limit = (not match_over) and self._episode_steps >= self.max_episode_steps
            terminated = match_over
            truncated = step_limit

            if terminated or truncated:
                self._pending_episode_summary = self._episode_end_summary(
                    truncated=truncated,
                )

            info = self._get_match_metadata()
            if terminated or truncated:
                self._release_match()

            return (
                self._build_observation(),
                reward,
                terminated,
                truncated,
                info,
            )
        except TimeoutError as exc:
            print(f"[gridiron] {exc}", flush=True)
            raise

    def close(self) -> None:
        self._release_match()
        self.match_id = None
        if self._owns_client:
            self.client.close()

    def _release_match(self) -> None:
        """Best-effort DELETE so scenario/trainer churn does not retain server memory."""
        match_id = self.match_id
        if match_id is None or self._match_released:
            return
        try:
            self.client.delete_match(match_id)
        except GridironApiError as exc:
            print(f"[gridiron] delete match {match_id} failed: {exc}", flush=True)
        finally:
            self._match_released = True

    @property
    def _legal_moves(self) -> list[list[int]]:
        if self._current_step_dto is None:
            return []
        return list(self._current_step_dto["legalMoves"])

    @property
    def _legal_rows(self) -> list[AiLegalChoiceDict]:
        if self._current_step_dto is None:
            return []
        return list(self._current_step_dto["legalRows"])

    @property
    def _legal_action_ids(self) -> list[str]:
        return [str(row["action"]["id"]) for row in self._legal_rows]

    @property
    def _server_soft_mask_legal(self) -> list[bool]:
        """Harness ``softMaskLegal`` flags (diagnostics / grading); independent of obs bias."""
        return [bool(row["softMaskLegal"]) for row in self._legal_rows]

    @property
    def _soft_mask_legal(self) -> list[int]:
        """Obs soft-mask flags (0/1). Strength is ``policy.soft_mask_strength``, never this channel."""
        rows = self._legal_rows
        if not self.soft_mask_bias:
            return [0] * len(rows)
        return [1 if flag else 0 for flag in self._server_soft_mask_legal]

    @property
    def _passing_legal(self) -> list[bool]:
        return [bool(row["passingLegal"]) for row in self._legal_rows]

    @property
    def _legal_row_meta(self) -> list[AiLegalChoiceDict]:
        """Full harness legal rows (action + curriculum flags)."""
        return [dict(row) for row in self._legal_rows]

    @property
    def _perspective_seat(self) -> str:
        if self._current_step_dto is None:
            return TeamSide.HOME
        return str(self._current_step_dto.get("perspectiveSeat") or TeamSide.HOME)

    def _harness_rewards(
        self, source: PackedStepDict | HarnessRewardSnapshotDict
    ) -> HarnessRewardSnapshotDict:
        """Harness ``rewards`` JSON — scores + players + ballCoordinate. Accepts step or bare."""
        if "players" in source:
            rewards = source
        else:
            rewards = source["rewards"]  # type: ignore[index]
        if not isinstance(rewards, dict) or "players" not in rewards:
            raise RuntimeError("Packed frame missing harness rewards (scores + players)")
        return rewards  # type: ignore[return-value]

    def _build_observation(self) -> dict[str, np.ndarray]:
        assert self._current_step_dto is not None
        return slice_packed_step(
            obs_flat=self._current_step_dto["obs"],
            legal_moves=self._legal_moves,
            soft_mask_legal=self._soft_mask_legal,
        )

    def _start_new_match_on_server(self, options: dict[str, Any]) -> None:
        self._release_match()
        home, away = self.client.default_team_ids(same_team_both_seats=self.same_team_both_seats)
        home = options.get("home_team_id", home)
        away = options.get("away_team_id", away)

        if self.opponent_mode == OPPONENT_HUMAN:
            # Browser plays the non-policy seat; policy seat is the trainer.
            if self.policy_seat == "HOME":
                home_ctrl, away_ctrl = "HARNESS", "HUMAN"
            else:
                home_ctrl, away_ctrl = "HUMAN", "HARNESS"
            computer_opponent = "LOOKAHEAD"  # ignored
            onnx_profile = None
        elif self.policy_seat == "HOME":
            home_ctrl, away_ctrl = "HARNESS", "COMPUTER"
            computer_opponent = self.computer_opponent
            onnx_profile = self.onnx_profile
        else:
            home_ctrl, away_ctrl = "COMPUTER", "HARNESS"
            computer_opponent = self.computer_opponent
            onnx_profile = self.onnx_profile

        if self.scenario is not None:
            if self.policy_seat != "HOME":
                raise ValueError("scenario drills require policy_seat=HOME (HOME is always HARNESS)")
            if self.opponent_mode == OPPONENT_HUMAN:
                away_ctrl = "HUMAN"
            else:
                away_ctrl = "COMPUTER"
            if computer_opponent == COMPUTER_KIND_ONNX:
                raise ValueError(
                    "scenario drills do not seat ONNX yet (training matches omit onnxProfile); "
                    "use LOOKAHEAD/HEURISTIC for drills"
                )
            self.match_id = self.client.create_scenario_match(
                self.scenario,
                difficulty=self.scenario_difficulty,
                episode_mode=self.episode_mode,
                away_controlled_by=away_ctrl,
                computer_opponent=self.computer_opponent,
            )
        else:
            self.match_id = self.client.create_and_start(
                home,
                away,
                home_controlled_by=home_ctrl,
                away_controlled_by=away_ctrl,
                computer_opponent=computer_opponent,
                onnx_profile=onnx_profile,
            )
        self._match_released = False
        self._episode_steps = 0
        self._action_stats.reset()
        self._episode_opening_snapshot = None
        self._episode_move_sequence = []
        self._turn_start_score_us = 0
        self._tracked_turn_number = 0
        self._sampled_turn_starts = set()
        if self._scenario_eval_debug is not None:
            self._scenario_eval_debug.reset()
        if self.on_match_created is not None:
            self.on_match_created(self.match_id)
        self._current_step_dto = self._fetch_step_for_policy()
        if self.scenario is not None and self._scenario_eval_debug is not None:
            start_rewards = self._harness_rewards(self._current_step_dto)
            self._scenario_eval_debug.on_scenario_start(
                int(start_rewards["turnNumber"]),
                start_rewards,
                policy_seat=self.policy_seat,
                first_action_legal_count=len(self._legal_action_ids),
                legal_row_meta=self._legal_row_meta,
                passing_legal=self._passing_legal,
            )
        self._maybe_record_turn_start(self._harness_rewards(self._current_step_dto))

    def _submit_resolved_action(
        self,
        resolved: ResolvedAction,
        *,
        chosen_row_index: int | None = None,
        policy_obs: dict[str, np.ndarray] | None = None,
    ) -> tuple[float, GridironApiError | None]:
        assert self.match_id is not None and self._current_step_dto is not None

        observation_before_move = self._harness_rewards(self._current_step_dto)
        self._sync_turn_tracking(observation_before_move)

        t0 = time.perf_counter()
        try:
            result = self.client.ai_submit(
                self.match_id,
                resolved.legal_action_id,
                for_seat=self.policy_seat,
                timeout_s=self.turn_timeout_s,
            )
        except GridironApiError as exc:
            self._action_stats.record_submit_ms((time.perf_counter() - t0) * 1000.0)
            if self.continue_on_submit_error and exc.status is not None and exc.status >= 400:
                return 0.0, exc
            raise
        self._action_stats.record_submit_ms((time.perf_counter() - t0) * 1000.0)
        self._episode_steps += 1

        after_obs = submit_rewards_after(result)
        terminal = submit_terminal_meta(result)
        scoring = submit_scoring(result)
        self._action_stats.record_ball_gain(observation_before_move, after_obs)
        passing = self._passing_legal
        soft_flags = self._server_soft_mask_legal
        passing_legal_move = False
        soft_taken = False
        row_i = chosen_row_index
        try:
            if row_i is None:
                row_i = self._legal_action_ids.index(resolved.legal_action_id)
            passing_legal_move = row_i < len(passing) and passing[row_i]
            soft_taken = row_i < len(soft_flags) and bool(soft_flags[row_i])
        except ValueError:
            pass
        training_outcome_before = self._current_step_dto.get("trainingOutcome")
        training_outcome = terminal.get("trainingOutcome")
        # First TRAIN scenario submit: slap non-passing when a passing row was on the menu.
        scenario_opening_non_passing = (
            self.scenario is not None
            and self.episode_mode == EpisodeMode.TRAIN
            and self._episode_steps == 1
            and not passing_legal_move
            and any(passing)
        )
        # TODO: stop unpacking — pass terminal / scoring / resolved (and before/after
        # snapshots) into step_reward as structs; see TODO on step_reward.
        reward = step_reward(
            rewards_before=observation_before_move,
            rewards_after=after_obs,
            result_kind=terminal.get("resultKind"),
            result_winner_rel=terminal.get("resultWinnerRel"),
            training_episode_ended=bool(terminal.get("trainingEpisodeEnded")),
            training_outcome=training_outcome,
            training_outcome_before=training_outcome_before,
            turn_start_score_us=self._turn_start_score_us,
            config=self.reward_config,
            scoring=scoring,
            legal_choice=resolved.legal_choice,
            perspective_seat=self._perspective_seat,
            passing_legal_move=passing_legal_move,
            scenario_opening_non_passing=scenario_opening_non_passing,
            scenario_difficulty=self.scenario_difficulty if self.scenario is not None else None,
        )
        scored_td = int(after_obs["touchdownsUs"]) > int(observation_before_move["touchdownsUs"])
        # Train --verbose: played path + PASS-edge tag for PPO evidence (not window close).
        if self.verbose and self.scenario is not None and self.episode_mode == EpisodeMode.TRAIN:
            move_label = ScenarioEvalDebug.format_action(
                resolved.legal_choice,
                rewards=observation_before_move,
                rewards_after=after_obs,
                expected_move=passing_legal_move,
                scored_touchdown=scored_td,
            )
            pass_edge = (
                training_outcome == TrainingOutcome.US
                and training_outcome_before != TrainingOutcome.US
            )
            self._episode_move_sequence.append(
                {
                    "label": move_label,
                    "passing": bool(passing_legal_move),
                    "soft_mask": soft_taken,
                    "reward": float(reward),
                    "scored_td": bool(scored_td),
                    "pass_edge": bool(pass_edge),
                }
            )
        if self._scenario_eval_debug is not None:
            acquired_card = int(scoring["challengeCardsUs"]) > 0
            self._scenario_eval_debug.maybe_record_action(
                turn_number=int(observation_before_move["turnNumber"]),
                rewards=observation_before_move,
                legal_choice=resolved.legal_choice,
                scored_touchdown=scored_td,
                expected_move=passing_legal_move,
                acquired_card=acquired_card,
                step_reward=reward,
                rewards_after=after_obs,
                policy_obs=policy_obs,
                legal_moves=self._legal_moves,
                passing_legal=passing,
                soft_mask_legal=self._server_soft_mask_legal,
                legal_row_meta=self._legal_row_meta,
                chosen_row_index=chosen_row_index,
            )

        self._apply_submit_result(result)
        return reward, None

    def _apply_submit_result(self, result: PackedSubmitDict) -> None:
        """Install the next gym observation from one packed submit response."""
        after_obs = submit_rewards_after(result)
        if result["next"]["obs"] is None:
            raise RuntimeError("Packed submit missing obs")
        self._current_step_dto = flatten_packed_submit(result)

        installed_obs = self._harness_rewards(self._current_step_dto)
        self._action_stats.record_ball_gain(after_obs, installed_obs)
        after_turn = int(after_obs["turnNumber"])
        installed_turn = int(installed_obs["turnNumber"])
        if after_turn != installed_turn:
            self._maybe_record_turn_start(after_obs)
        elif is_episode_ended(self._current_step_dto):
            self._maybe_record_turn_start(after_obs)
        self._maybe_record_turn_start(installed_obs)

    def _maybe_record_turn_start(self, observation: HarnessRewardSnapshotDict) -> None:
        """Record possession once for each distinct turnNumber."""
        turn_number = int(observation["turnNumber"])
        if turn_number in self._sampled_turn_starts:
            return
        self._sampled_turn_starts.add(turn_number)
        self._action_stats.record_turn_start(
            observation, perspective_seat=self._perspective_seat
        )

    def _sync_turn_tracking(self, observation: HarnessRewardSnapshotDict) -> None:
        """Remember scoreUs at the start of the current turnNumber (acting-seat perspective)."""
        turn_number = int(observation["turnNumber"])
        if turn_number != self._tracked_turn_number:
            self._turn_start_score_us = int(observation["scoreUs"])
            self._tracked_turn_number = turn_number

    def _fetch_step_for_policy(self) -> PackedStepDict:
        assert self.match_id is not None
        return self.client.ai_step(
            self.match_id,
            for_seat=self.policy_seat,
            timeout_s=self.turn_timeout_s,
        )

    def _finish_after_submit_error(
        self, error: GridironApiError
    ) -> tuple[dict[str, np.ndarray], float, bool, bool, dict[str, Any]]:
        assert self._current_step_dto is not None
        print(
            f"[gridiron] submit HTTP {error.status} — truncating episode "
            f"match_id={self.match_id} steps={self._episode_steps}: {error}",
            flush=True,
        )
        self._pending_submit_error = {
            "status": error.status,
            "message": str(error),
            "body": error.body,
        }
        self._pending_episode_summary = self._episode_end_summary(truncated=True, submit_error=True)
        info = self._get_match_metadata()
        self._release_match()
        return self._build_observation(), 0.0, False, True, info

    def _episode_end_summary(
        self,
        *,
        truncated: bool,
        submit_error: bool = False,
    ) -> dict[str, Any]:
        assert self._current_step_dto is not None
        state = self._current_step_dto
        obs = self._harness_rewards(state)
        result_kind = state.get("resultKind")
        result_winner_rel = state.get("resultWinnerRel")
        # Server drill grade — winrate only once the coach window closes (US may appear earlier).
        training_outcome = state.get("trainingOutcome")
        if state.get("trainingEpisodeEnded") and training_outcome is not None:
            result_winner_rel = training_outcome_winner_rel(training_outcome)
            result_kind = _SCENARIO_RESULT_KIND
        summary = self._action_stats.episode_summary(
            turns=int(obs["turnNumber"]),
            truncated=truncated,
            total_score=total_score(obs),
            challenge_cards=int(obs["challengeCardsUs"]),
            challenge_cards_them=int(obs["challengeCardsThem"]),
            touchdowns=int(obs["touchdownsUs"]),
            touchdowns_them=int(obs["touchdownsThem"]),
            submit_error=submit_error,
            score_us=int(obs["scoreUs"]),
            score_them=int(obs["scoreThem"]),
            result_kind=result_kind,
            result_winner_rel=result_winner_rel,
        )
        if self.scenario is not None:
            summary["scenario_difficulty"] = self.scenario_difficulty
            if self._action_stats.first_kind is not None:
                summary["first_kind"] = self._action_stats.first_kind
            # Verbose train only — join opening + played path to US/fail for PPO mass evidence.
            if self._episode_opening_snapshot is not None:
                summary["opening_snapshot"] = self._episode_opening_snapshot
                self._episode_opening_snapshot = None
            if self._episode_move_sequence:
                summary["move_sequence"] = list(self._episode_move_sequence)
                self._episode_move_sequence = []
        if self._scenario_eval_debug is not None:
            summary.update(self._scenario_eval_debug.episode_fields())
            snapshot = self._scenario_eval_debug.opening_decision_snapshot()
            if snapshot is not None:
                summary["_opening_mispick_snapshot"] = snapshot
            assert self.scenario is not None
            append_scenario_draw_log(
                self._scenario_eval_debug.format_scenario_draw_log(
                    scenario=self.scenario,
                    difficulty=self.scenario_difficulty,
                    episode_mode=self.episode_mode,
                    summary=summary,
                )
            )
        return summary

    def _is_episode_finished(self) -> bool:
        assert self._current_step_dto is not None
        return is_episode_ended(self._current_step_dto)

    def _is_match_already_finished(self) -> bool:
        """True when reset/step lands on an already-terminal gym state."""
        return self._is_episode_finished()

    def _ensure_match_is_active(self) -> None:
        if self.match_id is None or self._current_step_dto is None:
            raise RuntimeError("Match has not been initialized. Call reset() first.")

    def _verify_server_schema_compatibility(self) -> None:
        assert self._current_step_dto is not None
        if self._current_step_dto.get("schemaVersion") != SCHEMA_VERSION:
            raise RuntimeError(
                f"Server schemaVersion={self._current_step_dto.get('schemaVersion')} "
                f"!= harness SCHEMA_VERSION={SCHEMA_VERSION}"
            )
        obs = self._current_step_dto.get("obs")
        if not isinstance(obs, list) or len(obs) == 0:
            raise RuntimeError("Packed step missing obs array")

    def _get_match_metadata(self) -> dict[str, Any]:
        if self._current_step_dto is None:
            return {}
        state = self._current_step_dto
        summary = self._pending_episode_summary
        # Prefer bb_episode fields so synthetic scenario US/THEM show up on info too.
        result_kind = (summary or {}).get("result_kind", state.get("resultKind"))
        result_winner_rel = (summary or {}).get("result_winner_rel", state.get("resultWinnerRel"))
        info: dict[str, Any] = {
            "match_id": self.match_id,
            "acting_seat": state.get("actingSeat"),
            "perspective_seat": state.get("perspectiveSeat"),
            "result_kind": result_kind,
            "result_winner_rel": result_winner_rel,
            "legal_move_count": len(self._legal_action_ids),
            "episode_steps": self._episode_steps,
            "schema_version": state.get("schemaVersion"),
            "opponent_mode": self.opponent_mode,
            "computer_opponent": self.computer_opponent if self.opponent_mode == OPPONENT_COMPUTER else None,
            "onnx_profile": self.onnx_profile if self.opponent_mode == OPPONENT_COMPUTER else None,
        }
        if self.scenario is not None:
            info["scenario_difficulty"] = self.scenario_difficulty
        if summary is not None:
            info["bb_episode"] = summary
        if self._pending_submit_error is not None:
            info["bb_submit_error"] = self._pending_submit_error
        return info

