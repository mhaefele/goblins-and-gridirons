"""Step reward: score-margin + terminal W/L/D + optional profile shaping.

Owner: ``step_reward`` / profile presets (``baseline`` / ``general`` / ``td`` /
``defense``). Phases that use baseline vs shaped: ``TRAINING_ROADMAP.md``.
See ``ai/OWNERSHIP.md``.

After each submit, env diffs consecutive [HarnessRewardSnapshotDto]-shaped ``rewards`` JSON
from the wire (submitter perspective after the move).

  1. **Score-margin change** — (our score − their score) after minus before.
  2. **Match-end bonus** — +1 win / 0 draw / −1 loss when the match just finished.
  3. **Scenario coach-window terminal** — ±0.5 from ``trainingOutcome`` when
     ``trainingEpisodeEnded`` (not stacked on real match W/L).
  4. **Profile shaping** ([RewardShaping]) — possession / approach / TD amp / defense
     targeting / covering-radius spread / etc. (see presets below).

Scenario episodes use this same stack. ``passingLegal`` marks PASS-grade curriculum moves;
``softMaskLegal`` biases sampling only. Drill US/THEM grades curriculum/winrate.

Eval runs with profile shaping off.

Perspective (important)
-----------------------
"Us" and "them" are relative to whoever just acted. Submit returns ``afterSubmitRewards`` (pre-handoff
snapshot) when set, else top-level ``rewards``. Episode terminal meta uses [next] on same-frame
submit; on handoff it is usually omitted unless [next] is already terminal after the computer wait.
in that seat's perspective, so both before and after snapshots use the same "us".
Self-play reuses one policy for both seats; each step is still scored for the seat that moved.

Called by
---------
GridironEnv after each legal-action submit.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from gridiron_ai.constants import PLAY_HEIGHT, PLAY_WIDTH, PITCH_SQUARE_COUNT

TERMINAL_WIN = 1.0
TERMINAL_DRAW = 0.0
TERMINAL_LOSS = -1.0
# Coach-window drill grade — half match terminal so short episodes nudge without matching full-game stakes.
SCENARIO_TERMINAL_WIN = 0.5
SCENARIO_TERMINAL_LOSS = -0.5

RewardProfileName = Literal["baseline", "general", "spread", "td", "defense"]
AllProfilesPurpose = Literal["train", "eval", "summary"]
# Shaped specialists fork after scenario_b. ``baseline`` = margin + terminal only; stays in
# post-split ``--profile all`` rotation as the unshaped control (``TRAINING_ROADMAP.md``).
SHAPED_PROFILES: tuple[RewardProfileName, ...] = ("general", "spread", "td", "defense")
REWARD_PROFILES: tuple[RewardProfileName, ...] = ("baseline", *SHAPED_PROFILES)
# Default train/eval ``--profile all`` rotates these shaped specialists only. ``td`` / ``defense``
# stay pin-able and appear in summary ``all`` (``profiles_for_all``).
TRAIN_ALL_SHAPED_PROFILES: tuple[RewardProfileName, ...] = ("general", "spread")
# Full-game train/eval seats these as ``ComputerOpponentKind.ONNX`` (classpath
# ``ai-opponent/{id}/`` — same ids as reward profiles). Equal parts with heuristic/lookahead.
FULL_GAME_ONNX_OPPONENT_PROFILES: tuple[RewardProfileName, ...] = ("general", "defense")


def profiles_for_all(purpose: AllProfilesPurpose = "train") -> tuple[RewardProfileName, ...]:
    """Canonical CLI ``all`` → concrete reward profile names.

    Train/eval omit ``td`` / ``defense`` (still pin with ``--profile td|defense``).
    Summary includes every reward profile so specialist checkpoints stay visible.
    """
    if purpose == "summary":
        return REWARD_PROFILES
    return ("baseline", *TRAIN_ALL_SHAPED_PROFILES)

# Bench index ranges in perspective-relative AiObsLayout (them = 175..184).
_THEM_BENCH_START = 175
_BENCH_SLOTS = 10
# Perspective pitch: scoring end zone is y = PLAY_HEIGHT (AiObsLayout 180° for AWAY).
_MAX_END_ZONE_ROW_DIST = PLAY_HEIGHT - 1


@dataclass(frozen=True)
class RewardShaping:
    """Micro-shaping layered on v1 reward. All zeros = pure margin + terminal."""

    idle_turn_penalty: float = 0.0
    # Hold shaping: absolute (0.5 + 0.5×prox) per step, or TD Δendzone_proximity while carrying.
    ball_possession_bonus: float = 0.0
    ball_possession_delta: bool = False
    approach_ball_bonus: float = 0.0
    us_gained_ball_bonus: float = 0.0
    uncertain_challenge_bonus: float = 0.0
    touchdown_bonus: float = 0.0
    them_gained_ball_penalty: float = 0.0
    them_lost_ball_bonus: float = 0.0
    them_holds_ball_step_penalty: float = 0.0
    opponent_dugout_delta_bonus: float = 0.0
    any_block_bonus: float = 0.0
    # Initiating a block action whose target holds the ball (before dice / follow-ups).
    block_ball_carrier_bonus: float = 0.0
    # When they hold: no any_block; block carrier for carrier bonus; else this penalty.
    block_non_carrier_penalty: float = 0.0
    # Actor factor is our ball carrier (when we hold on pitch before the action).
    ball_carrier_action_bonus: float = 0.0
    # Path lands in scoring end zone while we do not hold (empty EZ sightseeing).
    empty_scoring_endzone_penalty: float = 0.0
    # Signed Δ covering radius: max over pitch of min Manhattan dist to any US player
    # (lower = more spread). Symmetric so peel-out then walk-back nets ~0.
    covering_radius_delta_bonus: float = 0.0

    @property
    def enabled(self) -> bool:
        return any(
            (
                self.idle_turn_penalty,
                self.ball_possession_bonus,
                self.approach_ball_bonus,
                self.us_gained_ball_bonus,
                self.uncertain_challenge_bonus,
                self.touchdown_bonus,
                self.them_gained_ball_penalty,
                self.them_lost_ball_bonus,
                self.them_holds_ball_step_penalty,
                self.opponent_dugout_delta_bonus,
                self.any_block_bonus,
                self.block_ball_carrier_bonus,
                self.block_non_carrier_penalty,
                self.ball_carrier_action_bonus,
                self.empty_scoring_endzone_penalty,
                self.covering_radius_delta_bonus,
            )
        )


# Shared light base for shaped profiles (sparse margin alone under-learned).
_SHARED_IDLE = 0.01
_SHARED_BALL = 0.05
_SHARED_APPROACH_BALL = 0.02
_SHARED_US_GAINED_BALL = 0.3
_SHARED_UNCERTAIN_CHALLENGE = 0.05
_SHARED_THEM_LOST_BALL = 0.5
_SHARED_BLOCK_BALL_CARRIER = 0.05
_SHARED_ANY_BLOCK = 0.02
_SHARED_BALL_CARRIER_ACTION = 0.02
_SHARED_EMPTY_SCORING_ENDZONE = 0.2

PROFILE_SHAPING: dict[RewardProfileName, RewardShaping] = {
    # Ablation: score-margin + terminal win/loss only (shaping.enabled is False).
    "baseline": RewardShaping(),
    "general": RewardShaping(
        idle_turn_penalty=_SHARED_IDLE,
        ball_possession_bonus=_SHARED_BALL,
        approach_ball_bonus=_SHARED_APPROACH_BALL,
        us_gained_ball_bonus=_SHARED_US_GAINED_BALL,
        uncertain_challenge_bonus=_SHARED_UNCERTAIN_CHALLENGE,
        them_lost_ball_bonus=_SHARED_THEM_LOST_BALL,
        any_block_bonus=_SHARED_ANY_BLOCK,
        block_ball_carrier_bonus=_SHARED_BLOCK_BALL_CARRIER,
        ball_carrier_action_bonus=_SHARED_BALL_CARRIER_ACTION,
        empty_scoring_endzone_penalty=_SHARED_EMPTY_SCORING_ENDZONE,
    ),
    # Field coverage: same ball base as general + reward shrinking the US covering radius.
    "spread": RewardShaping(
        idle_turn_penalty=_SHARED_IDLE,
        ball_possession_bonus=_SHARED_BALL,
        approach_ball_bonus=_SHARED_APPROACH_BALL,
        us_gained_ball_bonus=_SHARED_US_GAINED_BALL,
        uncertain_challenge_bonus=_SHARED_UNCERTAIN_CHALLENGE,
        them_lost_ball_bonus=_SHARED_THEM_LOST_BALL,
        any_block_bonus=_SHARED_ANY_BLOCK,
        block_ball_carrier_bonus=_SHARED_BLOCK_BALL_CARRIER,
        ball_carrier_action_bonus=_SHARED_BALL_CARRIER_ACTION,
        empty_scoring_endzone_penalty=_SHARED_EMPTY_SCORING_ENDZONE,
        covering_radius_delta_bonus=_SHARED_APPROACH_BALL,
    ),
    # TD farmer: loud but sub-scoreboard so the critic can track returns (eval is shaping-off).
    "td": RewardShaping(
        idle_turn_penalty=_SHARED_IDLE,
        ball_possession_bonus=0.4,
        ball_possession_delta=True,
        approach_ball_bonus=0.2,
        us_gained_ball_bonus=_SHARED_US_GAINED_BALL,
        uncertain_challenge_bonus=_SHARED_UNCERTAIN_CHALLENGE,
        touchdown_bonus=3.0,
        them_lost_ball_bonus=_SHARED_THEM_LOST_BALL,
        any_block_bonus=0.01,
        block_ball_carrier_bonus=_SHARED_BLOCK_BALL_CARRIER,
        ball_carrier_action_bonus=_SHARED_BALL_CARRIER_ACTION,
        empty_scoring_endzone_penalty=_SHARED_EMPTY_SCORING_ENDZONE,
    ),
    "defense": RewardShaping(
        idle_turn_penalty=_SHARED_IDLE,
        ball_possession_bonus=_SHARED_BALL,
        approach_ball_bonus=_SHARED_APPROACH_BALL,
        us_gained_ball_bonus=_SHARED_US_GAINED_BALL,
        uncertain_challenge_bonus=_SHARED_UNCERTAIN_CHALLENGE,
        them_gained_ball_penalty=0.5,
        them_lost_ball_bonus=_SHARED_THEM_LOST_BALL,
        them_holds_ball_step_penalty=0.03,
        opponent_dugout_delta_bonus=1.0,
        any_block_bonus=0.2,
        block_ball_carrier_bonus=1.0,
        block_non_carrier_penalty=0.3,
        ball_carrier_action_bonus=_SHARED_BALL_CARRIER_ACTION,
        empty_scoring_endzone_penalty=_SHARED_EMPTY_SCORING_ENDZONE,
    ),
}


def score_margin(rewards: dict[str, Any]) -> float:
    """Our score minus their score in one harness rewards snapshot."""
    return float(rewards["scoreUs"]) - float(rewards["scoreThem"])


def _is_us_team_rel(team_rel: Any) -> bool:
    # Wire: AiTeamRel ordinal (0=US) or legacy name string.
    return team_rel in (0, "US")


def _is_them_team_rel(team_rel: Any) -> bool:
    return team_rel in (1, "THEM")


def _is_distracted_training_outcome(outcome: Any) -> bool:
    return outcome in (2, "DISTRACTED")


def training_outcome_winner_rel(outcome: Any) -> str | None:
    """Map wire ``trainingOutcome`` to winrate W/L; ``None`` for DISTRACTED draw."""
    if _is_us_team_rel(outcome):
        return "US"
    if _is_them_team_rel(outcome):
        return "THEM"
    return None


def _player_on_ball(cell: dict[str, Any]) -> dict[str, Any] | None:
    """Occupant of a hasBall square (held, or rare loose-under-player bounce)."""
    player = cell.get("player")
    if player and cell.get("hasBall"):
        return player
    return None


def us_ball_carrier_pitch_index(observation_from_server: dict[str, Any]) -> int | None:
    """Pitch cell index of our ball carrier, or None if we do not hold the ball on pitch."""
    cells = observation_from_server.get("cells") or []
    for index in range(min(len(cells), PITCH_SQUARE_COUNT)):
        player = _player_on_ball(cells[index])
        if player and _is_us_team_rel(player.get("teamRel")):
            return index
    return None


def them_ball_carrier_pitch_index(observation_from_server: dict[str, Any]) -> int | None:
    """Pitch cell index of their ball carrier, or None if they do not hold the ball on pitch."""
    cells = observation_from_server.get("cells") or []
    for index in range(min(len(cells), PITCH_SQUARE_COUNT)):
        player = _player_on_ball(cells[index])
        if player and _is_them_team_rel(player.get("teamRel")):
            return index
    return None


def get_absolute_ball_pitch_index(observation_from_server: dict[str, Any]) -> int | None:
    """Find the ball's location, whether someone is holding it or it's loose on the floor."""
    cells = observation_from_server.get("cells") or []
    for index in range(min(len(cells), PITCH_SQUARE_COUNT)):
        if cells[index].get("hasBall"):
            return index
    return None


def manhattan_distance(index_a: int, index_b: int) -> int:
    """Grid distance between two pitch indices."""
    x_a, y_a = index_a % PLAY_WIDTH, index_a // PLAY_WIDTH
    x_b, y_b = index_b % PLAY_WIDTH, index_b // PLAY_WIDTH
    return abs(x_a - x_b) + abs(y_a - y_b)


def us_pitch_indices(observation_from_server: dict[str, Any]) -> list[int]:
    """Pitch cell indices occupied by perspective-us players."""
    cells = observation_from_server.get("cells") or []
    indices: list[int] = []
    for index in range(min(len(cells), PITCH_SQUARE_COUNT)):
        player = cells[index].get("player")
        if player and _is_us_team_rel(player.get("teamRel")):
            indices.append(index)
    return indices


def covering_radius(observation_from_server: dict[str, Any]) -> int | None:
    """Max over pitch of min Manhattan dist to any US player — None if nobody is on pitch."""
    players = us_pitch_indices(observation_from_server)
    if not players:
        return None
    worst = 0
    for square in range(PITCH_SQUARE_COUNT):
        best = min(manhattan_distance(square, player) for player in players)
        if best > worst:
            worst = best
    return worst


def min_us_distance_to_ball(observation_from_server: dict[str, Any]) -> int | None:
    """Finds the shortest distance between any 'US' player and the ball."""
    ball_idx = get_absolute_ball_pitch_index(observation_from_server)
    if ball_idx is None:
        return None

    cells = observation_from_server.get("cells") or []
    min_dist = 9999
    found_us = False

    for index in range(min(len(cells), PITCH_SQUARE_COUNT)):
        player = cells[index].get("player")
        if player and _is_us_team_rel(player.get("teamRel")):
            found_us = True
            dist = manhattan_distance(index, ball_idx)
            if dist < min_dist:
                min_dist = dist

    return min_dist if found_us else None


def us_holds_ball(observation_from_server: dict[str, Any]) -> bool:
    """True when a perspective-us player holds the ball."""
    return us_ball_carrier_pitch_index(observation_from_server) is not None


def them_holds_ball(observation_from_server: dict[str, Any]) -> bool:
    """True when a perspective-them player holds the ball."""
    return them_ball_carrier_pitch_index(observation_from_server) is not None


def endzone_proximity(pitch_index: int) -> float:
    """0 at our end-zone row, 1 in the opponent end zone (perspective-relative pitch)."""
    y = pitch_index // PLAY_WIDTH + 1
    return (y - 1) / _MAX_END_ZONE_ROW_DIST


def is_scoring_endzone_xy(x: int, y: int) -> bool:
    """True when 0-based pitch ``(x, y)`` is on the perspective scoring end-zone row."""
    return 0 <= x < PLAY_WIDTH and y == PLAY_HEIGHT - 1


def _cell_at_xy(observation_from_server: dict[str, Any], x: int, y: int) -> dict[str, Any] | None:
    """Nested obs ``cells`` are still a flat pitch+bench list — only lookup site for dest xy."""
    if not (0 <= x < PLAY_WIDTH and 0 <= y < PLAY_HEIGHT):
        return None
    cells = observation_from_server.get("cells") or []
    index = y * PLAY_WIDTH + x
    if index >= len(cells):
        return None
    return cells[index]


def is_loose_ball_at_xy(observation_from_server: dict[str, Any], x: int, y: int) -> bool:
    """True when the ball is loose (hasBall, no occupant) on that pitch square."""
    cell = _cell_at_xy(observation_from_server, x, y)
    if cell is None:
        return False
    return bool(cell.get("hasBall")) and cell.get("player") is None


def is_run_onto_loose_ball(
    observation_before_move: dict[str, Any],
    legal_choice: dict[str, Any] | None,
) -> bool:
    """True when ``legal_choice`` runs onto the square with a loose ball (we do not hold yet)."""
    if not legal_choice:
        return False
    if str(legal_choice.get("kind")) not in ("run", "declareRun", "sidestep"):
        return False
    if us_holds_ball(observation_before_move):
        return False
    target = _factor_board_target_xy(legal_choice)
    if target is None:
        return False
    return is_loose_ball_at_xy(observation_before_move, target[0], target[1])


def them_in_dugout(observation_from_server: dict[str, Any]) -> int:
    """Count opponent players in the perspective-them bench region."""
    cells = observation_from_server.get("cells") or []
    count = 0
    for index in range(_THEM_BENCH_START, _THEM_BENCH_START + _BENCH_SLOTS):
        if index >= len(cells):
            break
        player = cells[index].get("player")
        if player and _is_them_team_rel(player.get("teamRel")):
            count += 1
    return count


def _claims_all_uncertain(legal_choice: dict[str, Any] | None) -> bool:
    if not legal_choice:
        return False
    claims = legal_choice.get("potentialChallengeClaims") or []
    if not claims:
        return False
    return all(claim.get("certainty") in ("POTENTIAL", 1) for claim in claims)


def _factor_board_target_xy(legal_choice: dict[str, Any] | None) -> tuple[int, int] | None:
    """0-based pitch ``(x, y)`` from harness ``target``, or None if absent / out of range."""
    if not legal_choice:
        return None
    target = legal_choice.get("target")
    if not target:
        return None
    x = int(target["x"])
    y = int(target["y"])
    if 0 <= x < PLAY_WIDTH and 0 <= y < PLAY_HEIGHT:
        return x, y
    return None


def _factor_actor(legal_choice: dict[str, Any] | None) -> int | None:
    if not legal_choice:
        return None
    for factor in legal_choice.get("factors") or []:
        if factor.get("kind") == "actor":
            return int(factor["playerIndex"])
    return None


def _us_ball_carrier_player_index(observation_from_server: dict[str, Any]) -> int | None:
    """Roster playerIndex of our ball carrier, or None if we do not hold on pitch."""
    pitch = us_ball_carrier_pitch_index(observation_from_server)
    if pitch is None:
        return None
    cells = observation_from_server.get("cells") or []
    if pitch < 0 or pitch >= len(cells):
        return None
    player = cells[pitch].get("player")
    if not player:
        return None
    return int(player["playerIndex"])


def _them_ball_carrier_at_xy(observation_from_server: dict[str, Any], x: int, y: int) -> bool:
    cell = _cell_at_xy(observation_from_server, x, y)
    if cell is None:
        return False
    player = _player_on_ball(cell)
    return bool(player and _is_them_team_rel(player.get("teamRel")))


def is_block_on_them_ball_carrier(
        observation_before_move: dict[str, Any],
        legal_choice: dict[str, Any] | None,
) -> bool:
    """True when ``legal_choice`` is a block whose target holds the ball for Them."""
    if not legal_choice or str(legal_choice.get("kind")) != "block":
        return False
    target = _factor_board_target_xy(legal_choice)
    if target is None:
        return False
    return _them_ball_carrier_at_xy(observation_before_move, target[0], target[1])


def shaping_reward(
        *,
        rewards_before: dict[str, Any],
        rewards_after: dict[str, Any],
        turn_start_score_us: int,
        shaping: RewardShaping,
        scoring: dict[str, Any] | None = None,
        legal_choice: dict[str, Any] | None = None,
) -> float:
    """Extra reward beyond score-margin delta. Zero when shaping disabled."""
    if not shaping.enabled:
        return 0.0
    observation_before_move = rewards_before
    next_observation = rewards_after

    extra = 0.0
    before_turn = int(observation_before_move["turnNumber"])
    after_turn = int(next_observation["turnNumber"])
    kind = str(legal_choice.get("kind")) if legal_choice else ""
    target = _factor_board_target_xy(legal_choice)

    if shaping.idle_turn_penalty > 0.0 and after_turn > before_turn:
        if int(next_observation["scoreUs"]) <= turn_start_score_us:
            extra -= shaping.idle_turn_penalty

    if shaping.ball_possession_bonus > 0.0:
        carrier_after = us_ball_carrier_pitch_index(next_observation)
        if carrier_after is not None:
            if shaping.ball_possession_delta:
                carrier_before = us_ball_carrier_pitch_index(observation_before_move)
                if carrier_before is not None:
                    delta = endzone_proximity(carrier_after) - endzone_proximity(carrier_before)
                    if delta != 0.0:
                        extra += shaping.ball_possession_bonus * delta
            else:
                proximity = endzone_proximity(carrier_after)
                extra += shaping.ball_possession_bonus * (0.5 + (0.5 * proximity))

    if shaping.us_gained_ball_bonus > 0.0:
        if (not us_holds_ball(observation_before_move)) and us_holds_ball(next_observation):
            extra += shaping.us_gained_ball_bonus

    if shaping.approach_ball_bonus > 0.0 and not us_holds_ball(observation_before_move):
        dist_before = min_us_distance_to_ball(observation_before_move)
        dist_after = min_us_distance_to_ball(next_observation)
        if dist_before is not None and dist_after is not None:
            if dist_after < dist_before:
                extra += shaping.approach_ball_bonus

    if shaping.covering_radius_delta_bonus > 0.0:
        radius_before = covering_radius(observation_before_move)
        radius_after = covering_radius(next_observation)
        if radius_before is not None and radius_after is not None and radius_before != radius_after:
            extra += shaping.covering_radius_delta_bonus * (radius_before - radius_after)

    if (
        shaping.empty_scoring_endzone_penalty > 0.0
        and kind in ("run", "sidestep")
        and target is not None
        and is_scoring_endzone_xy(target[0], target[1])
        and not us_holds_ball(observation_before_move)
    ):
        extra -= shaping.empty_scoring_endzone_penalty

    if shaping.uncertain_challenge_bonus > 0.0 and _claims_all_uncertain(legal_choice):
        extra += shaping.uncertain_challenge_bonus

    if shaping.ball_carrier_action_bonus > 0.0:
        carrier = _us_ball_carrier_player_index(observation_before_move)
        actor = _factor_actor(legal_choice)
        if carrier is not None and actor == carrier:
            extra += shaping.ball_carrier_action_bonus

    them_had = them_holds_ball(observation_before_move)
    them_has = them_holds_ball(next_observation)

    if legal_choice and kind == "block":
        is_carrier_block = is_block_on_them_ball_carrier(observation_before_move, legal_choice)
        if shaping.block_non_carrier_penalty > 0.0 and them_had:
            if is_carrier_block and shaping.block_ball_carrier_bonus > 0.0:
                extra += shaping.block_ball_carrier_bonus
            elif not is_carrier_block:
                extra -= shaping.block_non_carrier_penalty
        else:
            if shaping.any_block_bonus > 0.0:
                extra += shaping.any_block_bonus
            if is_carrier_block and shaping.block_ball_carrier_bonus > 0.0:
                extra += shaping.block_ball_carrier_bonus

    scoring = scoring or {}
    touchdowns_us = int(scoring.get("touchdownsUs") or 0)
    if shaping.touchdown_bonus > 0.0 and touchdowns_us > 0:
        extra += shaping.touchdown_bonus * touchdowns_us
    if shaping.them_gained_ball_penalty > 0.0 and (not them_had) and them_has:
        extra -= shaping.them_gained_ball_penalty
    if shaping.them_lost_ball_bonus > 0.0 and them_had and (not them_has):
        extra += shaping.them_lost_ball_bonus
    if shaping.them_holds_ball_step_penalty > 0.0 and them_has:
        extra -= shaping.them_holds_ball_step_penalty

    if shaping.opponent_dugout_delta_bonus > 0.0:
        dugout_delta = them_in_dugout(next_observation) - them_in_dugout(observation_before_move)
        if dugout_delta > 0:
            extra += shaping.opponent_dugout_delta_bonus * dugout_delta

    return float(extra)


def is_game_ended(state: dict[str, Any]) -> bool:
    """True when ``resultKind`` is set — real match finished ([AiHarnessStepDto.isGameEnded])."""
    return state.get("resultKind") is not None


def is_episode_ended(state: dict[str, Any]) -> bool:
    """Gym terminal: match result or training coach-window end ([AiHarnessStepDto.isEpisodeEnded])."""
    return is_game_ended(state) or bool(state.get("trainingEpisodeEnded"))


def _is_us_winner_rel(winner_rel: Any) -> bool:
    # Harness metadata is name strings; accept ordinals if a packed path ever emits them.
    return winner_rel in (0, "US")


def _is_them_winner_rel(winner_rel: Any) -> bool:
    return winner_rel in (1, "THEM")


def step_reward(
        *,
        rewards_before: dict[str, Any],
        rewards_after: dict[str, Any],
        result_kind: Any | None = None,
        result_winner_rel: Any | None = None,
        training_episode_ended: bool = False,
        training_outcome: Any | None = None,
        turn_start_score_us: int = 0,
        shaping: RewardShaping | None = None,
        scoring: dict[str, Any] | None = None,
        legal_choice: dict[str, Any] | None = None,
) -> float:
    """Reward for the seat that just moved.

    ``rewards_before`` / ``rewards_after`` are harness JSON snapshots from the wire
    (same seat perspective). Episode terminal fields come from the submit frame meta.
    """
    shaping_cfg = shaping or RewardShaping()
    reward = score_margin(rewards_after) - score_margin(rewards_before)
    reward += shaping_reward(
        rewards_before=rewards_before,
        rewards_after=rewards_after,
        turn_start_score_us=turn_start_score_us,
        shaping=shaping_cfg,
        scoring=scoring,
        legal_choice=legal_choice,
    )
    if result_kind is not None:
        if _is_us_winner_rel(result_winner_rel):
            reward += TERMINAL_WIN
        elif _is_them_winner_rel(result_winner_rel):
            reward += TERMINAL_LOSS
        else:
            reward += TERMINAL_DRAW
        return float(reward)
    if training_episode_ended:
        if _is_us_winner_rel(training_outcome):
            reward += SCENARIO_TERMINAL_WIN
        elif _is_them_winner_rel(training_outcome):
            reward += SCENARIO_TERMINAL_LOSS
        elif not _is_distracted_training_outcome(training_outcome):
            raise ValueError(f"unknown trainingOutcome on coach-window end: {training_outcome!r}")
    return float(reward)