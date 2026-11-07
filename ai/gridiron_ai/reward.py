"""Step reward: score-margin + terminal W/L/D + optional profile shaping.

Owner: ``step_reward`` / profile presets (``baseline`` / ``general`` / ``td`` /
``defense``). Phases that use baseline vs shaped: ``TRAINING_ROADMAP.md``.
See ``ai/OWNERSHIP.md``.

After each submit, env diffs consecutive [HarnessRewardSnapshotDto]-shaped ``rewards`` JSON
from the wire (submitter perspective after the move).

  1. **Score-margin change** — (our score − their score) after minus before.
  2. **Match-end bonus** — +1 win / 0 draw / −1 loss when the match just finished.
  3. **Scenario curriculum** — +0.5 when ``trainingOutcome`` first becomes ``US``
     (PASS graded; often mid coach-window). −0.5 when the window closes on ``THEM``.
     ``DISTRACTED`` at end is 0. Not stacked on real match W/L.
  4. **Scenario passing-move bonus** — +0.25 when the submitted row was
     ``passingLegal`` (mid-episode, whether or not the drill has PASSed yet).
  5. **Scenario opening mispick** — −1.0 on the **first** submit when a ``passingLegal``
     row was available and the chosen row was not (TRAIN scenarios only; env-gated).
  6. **Profile shaping** ([RewardShaping]) — possession / approach / TD amp / defense
     targeting / covering-radius spread / etc. (see presets below).

Scenario episodes use this same stack. ``passingLegal`` marks PASS-grade curriculum moves
(and the +0.25 above); ``softMaskLegal`` biases sampling only. Drill US/THEM grades
curriculum/winrate.

Eval runs with profile shaping off.

Harness ``rewards.players`` (absolute coords + ``status`` + ``playerId``) and
``ballCoordinate`` (where the ball is). Legal rows = action + curriculum flags only.
Packed ``legalMoves`` are policy-only — never read here.

Called by
---------
GridironEnv after each legal-action submit.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from gridiron_ai.constants import PLAY_HEIGHT, PLAY_WIDTH
from gridiron_ai.api.harness_wire import (
    AiScoringDeltaDict,
    HarnessPlayerDict,
    HarnessRewardSnapshotDict,
    LegalActionDict,
    MatchResultKind,
    PackedStepDict,
    PlayerStatus,
    TeamRel,
    TeamSide,
    TrainingOutcome,
)

TERMINAL_WIN = 1.0
TERMINAL_DRAW = 0.0
TERMINAL_LOSS = -1.0
# Curriculum drill grade — half match terminal so short episodes nudge without matching full-game stakes.
# Win credits on the PASS edge (``trainingOutcome`` → US); loss stays at coach-window close.
SCENARIO_TERMINAL_WIN = 0.5
SCENARIO_TERMINAL_LOSS = -0.5
# Per submitting a ``passingLegal`` row (pickup / lesson path) — not soft-mask distractors.
SCENARIO_PASSING_MOVE_BONUS = 0.25
# First submit when a passing row was on the menu and they took something else (env gates opening).
SCENARIO_OPENING_NON_PASSING_PENALTY = 1.0

RewardProfileName = Literal["baseline", "general", "spread", "td", "defense"]
AllProfilesPurpose = Literal["train", "eval", "summary"]
# Shaped specialists fork after scenario_b. ``baseline`` = margin + terminal only; stays in
# post-split ``--profile all`` rotation as the unshaped control (``TRAINING_ROADMAP.md``).
SHAPED_PROFILES: tuple[RewardProfileName, ...] = ("general", "spread", "td", "defense")
REWARD_PROFILES: tuple[RewardProfileName, ...] = ("baseline", *SHAPED_PROFILES)
# Default train/eval ``--profile all`` rotates these shaped specialists. ``td`` stays
# pin-able and appears in summary ``all`` (``profiles_for_all``).
TRAIN_ALL_SHAPED_PROFILES: tuple[RewardProfileName, ...] = ("general", "spread", "defense")
# Full-game train/eval seats these as ``ComputerOpponentKind.ONNX`` (classpath
# ``ai-opponent/{id}/`` — same ids as reward profiles). Equal parts with heuristic/lookahead.
FULL_GAME_ONNX_OPPONENT_PROFILES: tuple[RewardProfileName, ...] = ("general", "defense")


def profiles_for_all(purpose: AllProfilesPurpose = "train") -> tuple[RewardProfileName, ...]:
    """Canonical CLI ``all`` → concrete reward profile names.

    Train/eval omit ``td`` (still pin with ``--profile td``). Summary includes every
    reward profile so specialist checkpoints stay visible.
    """
    if purpose == "summary":
        return REWARD_PROFILES
    return ("baseline", *TRAIN_ALL_SHAPED_PROFILES)

# Absolute pitch: HOME scores at y = PLAY_HEIGHT; AWAY at y = 1.
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
    # Positive Δ in them PRONE count (STANDING→PRONE). Separate from dugout send-offs.
    opponent_prone_delta_bonus: float = 0.0
    any_block_bonus: float = 0.0
    # Initiating a block action whose target holds the ball (before dice / follow-ups).
    block_ball_carrier_bonus: float = 0.0
    # When they hold: no any_block; block carrier for carrier bonus; else this penalty.
    block_non_carrier_penalty: float = 0.0
    # LegalAction actor playerId matches our ball carrier (we hold on pitch before).
    ball_carrier_action_bonus: float = 0.0
    # Absolute dest is acting seat's scoring end zone while we do not hold.
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
                self.opponent_prone_delta_bonus,
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
    # Bash outcomes only: KD (prone Δ) + send-offs (dugout Δ) + free the ball
    # (them_lost / us_gained). No initiate-block bonuses — dice results own the credit.
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
        opponent_prone_delta_bonus=0.5,
        ball_carrier_action_bonus=_SHARED_BALL_CARRIER_ACTION,
        empty_scoring_endzone_penalty=_SHARED_EMPTY_SCORING_ENDZONE,
    ),
}


def score_margin(rewards: HarnessRewardSnapshotDict) -> float:
    """Our score minus their score in one harness rewards snapshot."""
    return float(rewards["scoreUs"]) - float(rewards["scoreThem"])


def _is_us_team_rel(team_rel: TeamRel | None) -> bool:
    """Harness ``AiTeamRel`` / outcome name strings (not packed ordinals)."""
    return team_rel == TeamRel.US


def _is_them_team_rel(team_rel: TeamRel | None) -> bool:
    return team_rel == TeamRel.THEM


def _is_distracted_training_outcome(outcome: TrainingOutcome | None) -> bool:
    return outcome == TrainingOutcome.DISTRACTED


def training_outcome_winner_rel(outcome: TrainingOutcome | None) -> TeamRel | None:
    """Map wire ``trainingOutcome`` to winrate W/L; ``None`` for DISTRACTED draw."""
    if _is_us_team_rel(outcome):
        return TeamRel.US
    if _is_them_team_rel(outcome):
        return TeamRel.THEM
    return None


def _players(rewards: HarnessRewardSnapshotDict) -> list[HarnessPlayerDict]:
    raw = rewards.get("players") or []
    return [p for p in raw if isinstance(p, dict)]  # type: ignore[misc]


def coordinate_xy(raw: object) -> tuple[int, int] | None:
    """Parse wire ``{x,y}`` (1-based absolute) → tuple, or None if absent/null."""
    if not isinstance(raw, dict):
        return None
    return int(raw["x"]), int(raw["y"])


def player_by_id(
    rewards: HarnessRewardSnapshotDict,
    player_id: str,
) -> HarnessPlayerDict | None:
    """Join LegalAction ``playerId`` → harness player row."""
    for player in _players(rewards):
        if player.get("playerId") == player_id:
            return player
    return None


def player_at(
    rewards: HarnessRewardSnapshotDict,
    xy: tuple[int, int],
) -> HarnessPlayerDict | None:
    """Join absolute playable square → on-pitch harness player."""
    for player in _players(rewards):
        if coordinate_xy(player.get("coordinate")) == xy:
            return player
    return None


def ball_xy(rewards: HarnessRewardSnapshotDict) -> tuple[int, int] | None:
    """Absolute playable square of the ball from ``ballCoordinate``."""
    return coordinate_xy(rewards.get("ballCoordinate"))


def us_ball_carrier(rewards: HarnessRewardSnapshotDict) -> HarnessPlayerDict | None:
    """US player whose square matches ``ballCoordinate``, if any."""
    ball = ball_xy(rewards)
    if ball is None:
        return None
    player = player_at(rewards, ball)
    if player and _is_us_team_rel(player.get("teamRel")):
        return player
    return None


def them_ball_carrier(rewards: HarnessRewardSnapshotDict) -> HarnessPlayerDict | None:
    """Them player whose square matches ``ballCoordinate``, if any."""
    ball = ball_xy(rewards)
    if ball is None:
        return None
    player = player_at(rewards, ball)
    if player and _is_them_team_rel(player.get("teamRel")):
        return player
    return None


def manhattan_xy(a: tuple[int, int], b: tuple[int, int]) -> int:
    """Grid distance between two absolute playable squares."""
    return abs(a[0] - b[0]) + abs(a[1] - b[1])


def us_pitch_coordinates(rewards: HarnessRewardSnapshotDict) -> list[tuple[int, int]]:
    """Absolute squares occupied by perspective-us players on pitch."""
    coords: list[tuple[int, int]] = []
    for player in _players(rewards):
        if not _is_us_team_rel(player.get("teamRel")):
            continue
        xy = coordinate_xy(player.get("coordinate"))
        if xy is not None:
            coords.append(xy)
    return coords


def covering_radius(rewards: HarnessRewardSnapshotDict) -> int | None:
    """Max over pitch of min Manhattan dist to any US player — None if nobody is on pitch."""
    players = us_pitch_coordinates(rewards)
    if not players:
        return None
    worst = 0
    for y in range(1, PLAY_HEIGHT + 1):
        for x in range(1, PLAY_WIDTH + 1):
            square = (x, y)
            best = min(manhattan_xy(square, player) for player in players)
            if best > worst:
                worst = best
    return worst


def min_us_distance_to_ball(rewards: HarnessRewardSnapshotDict) -> int | None:
    """Shortest Manhattan distance from any US on-pitch player to the ball."""
    ball = ball_xy(rewards)
    if ball is None:
        return None
    players = us_pitch_coordinates(rewards)
    if not players:
        return None
    return min(manhattan_xy(player, ball) for player in players)


def us_holds_ball(rewards: HarnessRewardSnapshotDict) -> bool:
    """True when a perspective-us player holds the ball."""
    return us_ball_carrier(rewards) is not None


def them_holds_ball(rewards: HarnessRewardSnapshotDict) -> bool:
    """True when a perspective-them player holds the ball."""
    return them_ball_carrier(rewards) is not None


def endzone_proximity(xy: tuple[int, int], *, acting_seat: TeamSide) -> float:
    """0 at our defensive end zone, 1 at our scoring end zone (absolute pitch)."""
    _, y = xy
    if acting_seat == TeamSide.HOME:
        return (y - 1) / _MAX_END_ZONE_ROW_DIST
    if acting_seat == TeamSide.AWAY:
        return (PLAY_HEIGHT - y) / _MAX_END_ZONE_ROW_DIST
    raise ValueError(f"acting_seat must be HOME or AWAY, got {acting_seat!r}")


def them_in_dugout(rewards: HarnessRewardSnapshotDict) -> int:
    """Count perspective-them players in reserves (dugout)."""
    return sum(
        1
        for player in _players(rewards)
        if _is_them_team_rel(player.get("teamRel"))
        and (
            player.get("status") == PlayerStatus.RESERVES
            or (player.get("status") is None and player.get("coordinate") is None)
        )
    )


def them_prone(rewards: HarnessRewardSnapshotDict) -> int:
    """Count perspective-them players currently PRONE on the pitch."""
    return sum(
        1
        for player in _players(rewards)
        if _is_them_team_rel(player.get("teamRel")) and player.get("status") == PlayerStatus.PRONE
    )


def is_block_on_them_ball_carrier(
    rewards_before: HarnessRewardSnapshotDict,
    legal_choice: LegalActionDict | None,
) -> bool:
    """True when ``legal_choice`` is a block whose dest is Them's ball square."""
    if not legal_choice or str(legal_choice.get("kind")) != "block":
        return False
    target = action_target_coordinate(legal_choice)
    ball = ball_xy(rewards_before)
    if target is None or ball is None or target != ball:
        return False
    player = player_at(rewards_before, target)
    return bool(player and _is_them_team_rel(player.get("teamRel")))


def _claims_all_uncertain(action: LegalActionDict | None) -> bool:
    if not action:
        return False
    claims = action.get("potentialChallengeClaims") or []
    if not claims:
        return False
    return all(claim.get("certainty") == "POTENTIAL" for claim in claims)


def action_target_coordinate(action: LegalActionDict | None) -> tuple[int, int] | None:
    """Absolute playable ``Coordinate`` from LegalAction ``targetCoordinate``."""
    if not action:
        return None
    return coordinate_xy(action.get("targetCoordinate"))


def action_actor_coordinate(
    rewards: HarnessRewardSnapshotDict,
    action: LegalActionDict | None,
) -> tuple[int, int] | None:
    """Actor pitch square via LegalAction ``playerId`` → ``rewards.players``."""
    if not action:
        return None
    player_id = action.get("playerId")
    if not isinstance(player_id, str):
        return None
    player = player_by_id(rewards, player_id)
    if player is None:
        return None
    return coordinate_xy(player.get("coordinate"))


def is_absolute_scoring_endzone(x: int, y: int, *, acting_seat: TeamSide) -> bool:
    if acting_seat == TeamSide.HOME:
        return 1 <= x <= PLAY_WIDTH and y == PLAY_HEIGHT
    if acting_seat == TeamSide.AWAY:
        return 1 <= x <= PLAY_WIDTH and y == 1
    raise ValueError(f"acting_seat must be HOME or AWAY, got {acting_seat!r}")


def shaping_reward(
        *,
        rewards_before: HarnessRewardSnapshotDict,
        rewards_after: HarnessRewardSnapshotDict,
        turn_start_score_us: int,
        shaping: RewardShaping,
        scoring: AiScoringDeltaDict | None = None,
        legal_choice: LegalActionDict | None = None,
        perspective_seat: TeamSide = TeamSide.HOME,
) -> float:
    """Extra reward beyond score-margin delta. Zero when shaping disabled.

    ``legal_choice`` is engine LegalAction (absolute). Board facts join via ``rewards.players``.
    """
    if not shaping.enabled:
        return 0.0
    observation_before_move = rewards_before
    next_observation = rewards_after

    extra = 0.0
    before_turn = int(observation_before_move["turnNumber"])
    after_turn = int(next_observation["turnNumber"])
    kind = str(legal_choice.get("kind")) if legal_choice else ""
    target = action_target_coordinate(legal_choice)

    if shaping.idle_turn_penalty > 0.0 and after_turn > before_turn:
        if int(next_observation["scoreUs"]) <= turn_start_score_us:
            extra -= shaping.idle_turn_penalty

    if shaping.ball_possession_bonus > 0.0:
        carrier_after = us_ball_carrier(next_observation)
        after_xy = coordinate_xy(carrier_after.get("coordinate")) if carrier_after else None
        if after_xy is not None:
            if shaping.ball_possession_delta:
                carrier_before = us_ball_carrier(observation_before_move)
                before_xy = coordinate_xy(carrier_before.get("coordinate")) if carrier_before else None
                if before_xy is not None:
                    delta = (
                        endzone_proximity(after_xy, acting_seat=perspective_seat)
                        - endzone_proximity(before_xy, acting_seat=perspective_seat)
                    )
                    if delta != 0.0:
                        extra += shaping.ball_possession_bonus * delta
            else:
                proximity = endzone_proximity(after_xy, acting_seat=perspective_seat)
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
        and is_absolute_scoring_endzone(target[0], target[1], acting_seat=perspective_seat)
        and not us_holds_ball(observation_before_move)
    ):
        extra -= shaping.empty_scoring_endzone_penalty

    if shaping.uncertain_challenge_bonus > 0.0 and _claims_all_uncertain(legal_choice):
        extra += shaping.uncertain_challenge_bonus

    if shaping.ball_carrier_action_bonus > 0.0 and legal_choice:
        carrier = us_ball_carrier(observation_before_move)
        if carrier is not None and legal_choice.get("playerId") == carrier.get("playerId"):
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

    if shaping.opponent_prone_delta_bonus > 0.0:
        prone_delta = them_prone(next_observation) - them_prone(observation_before_move)
        if prone_delta > 0:
            extra += shaping.opponent_prone_delta_bonus * prone_delta

    return float(extra)


def is_game_ended(state: PackedStepDict) -> bool:
    """True when ``resultKind`` is set — real match finished ([AiHarnessStepDto.isGameEnded])."""
    return state.get("resultKind") is not None


def is_episode_ended(state: PackedStepDict) -> bool:
    """Gym terminal: match result or training coach-window end ([AiHarnessStepDto.isEpisodeEnded])."""
    return is_game_ended(state) or bool(state.get("trainingEpisodeEnded"))


def _is_us_winner_rel(winner_rel: TeamRel | None) -> bool:
    return winner_rel == TeamRel.US


def _is_them_winner_rel(winner_rel: TeamRel | None) -> bool:
    return winner_rel == TeamRel.THEM


def step_reward(
        *,
        rewards_before: HarnessRewardSnapshotDict,
        rewards_after: HarnessRewardSnapshotDict,
        result_kind: MatchResultKind | None = None,
        result_winner_rel: TeamRel | None = None,
        training_episode_ended: bool = False,
        training_outcome: TrainingOutcome | None = None,
        training_outcome_before: TrainingOutcome | None = None,
        turn_start_score_us: int = 0,
        shaping: RewardShaping | None = None,
        scoring: AiScoringDeltaDict | None = None,
        legal_choice: LegalActionDict | None = None,
        perspective_seat: TeamSide = TeamSide.HOME,
        passing_legal_move: bool = False,
        scenario_opening_non_passing: bool = False,
) -> float:
    """Reward for the seat that just moved.

    ``perspective_seat`` is the acting seat (absolute end-zone geometry + us/them scores).
    Board facts join via ``rewards.players`` / ``ballCoordinate``.
    ``passing_legal_move`` is True when the submitted harness row had ``passingLegal``.
    ``scenario_opening_non_passing`` is env-gated (first TRAIN scenario step, passing
    available, non-passing chosen) → −[SCENARIO_OPENING_NON_PASSING_PENALTY].
    ``training_outcome_before`` / ``training_outcome`` edge-detect PASS → +0.5 once.
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
        perspective_seat=perspective_seat,
    )
    if passing_legal_move:
        reward += SCENARIO_PASSING_MOVE_BONUS
    if scenario_opening_non_passing:
        reward -= SCENARIO_OPENING_NON_PASSING_PENALTY
    # PASS may land mid coach-window; credit here so chaff after the lesson is not the carrier.
    if _is_us_winner_rel(training_outcome) and not _is_us_winner_rel(training_outcome_before):
        reward += SCENARIO_TERMINAL_WIN
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
            pass  # +0.5 already applied on the PASS edge (possibly this same submit)
        elif _is_them_winner_rel(training_outcome):
            reward += SCENARIO_TERMINAL_LOSS
        elif not _is_distracted_training_outcome(training_outcome):
            raise ValueError(f"unknown trainingOutcome on coach-window end: {training_outcome!r}")
    return float(reward)