"""Throwaway scenario-eval debug helpers — not part of the learning contract.

Owner: optional first-action / failure logging for scenario eval. Safe to delete.
See ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

import fcntl
from typing import Any

import numpy as np

from gridiron_ai.constants import PLAY_HEIGHT
from gridiron_ai.paths import SCENARIO_DRAWS_LOG_PATH
from gridiron_ai.reward import (
    action_actor_coordinate,
    action_target_coordinate,
    ball_xy,
    coordinate_xy,
    player_by_id,
)

def _scoring_endzone_row_y(*, policy_seat: str) -> int:
    """Perspective-relative row y (`1..PLAY_HEIGHT`) of the end zone the policy seat scores in."""
    from gridiron_ai.constants import PLAY_HEIGHT

    if policy_seat == "HOME":
        return PLAY_HEIGHT
    if policy_seat == "AWAY":
        return 1
    raise ValueError(f"policy_seat must be HOME or AWAY, got {policy_seat!r}")


class ScenarioEvalDebug:
    """Per-episode first-turn action trace for scenario eval loss lines."""

    def __init__(self) -> None:
        self._start_turn: int | None = None
        self._actions: list[str] = []
        self._initial_ball: str | None = None
        self._us_positions: str | None = None
        self._them_positions: str | None = None
        self._endzone_y: int | None = None
        self._first_action_legal_count: int | None = None
        self._expected_move_count: int = 0
        self._expected_moves: list[str] = []
        self._opening_mispick_snapshot: dict[str, Any] | None = None
        self._available_cards: str | None = None
        self._satisfied_claims: list[str] = []

    def reset(self) -> None:
        self._start_turn = None
        self._actions = []
        self._initial_ball = None
        self._us_positions = None
        self._them_positions = None
        self._endzone_y = None
        self._first_action_legal_count = None
        self._expected_move_count = 0
        self._expected_moves = []
        self._opening_mispick_snapshot = None
        self._available_cards = None
        self._satisfied_claims = []

    def on_scenario_start(
        self,
        turn_number: int,
        rewards: dict[str, Any],
        *,
        policy_seat: str,
        first_action_legal_count: int,
        legal_row_meta: list[dict[str, Any]] | None = None,
        passing_legal: list[bool] | None = None,
    ) -> None:
        """``rewards`` is harness [HarnessRewardSnapshotDto] — board snapshot for log headers."""
        self._start_turn = int(turn_number)
        self._first_action_legal_count = int(first_action_legal_count)
        ball = ball_xy(rewards)
        if ball is not None:
            self._initial_ball = _abs_xy_label(*ball)
        us, them = _pitch_player_position_labels(rewards)
        self._us_positions = ";".join(us) if us else None
        self._them_positions = ";".join(them) if them else None
        self._endzone_y = _scoring_endzone_row_y(policy_seat=policy_seat)
        meta = legal_row_meta or []
        self._available_cards = None
        expected = _format_expected_moves(
            rewards=rewards,
            legal_row_meta=meta,
            passing_legal=passing_legal or [],
        )
        if expected is not None:
            self._expected_move_count, self._expected_moves = expected

    def maybe_record_action(
        self,
        *,
        turn_number: int,
        rewards: dict[str, Any],
        legal_choice: dict[str, Any],
        scored_touchdown: bool = False,
        expected_move: bool = False,
        acquired_card: bool = False,
        policy_obs: dict[str, np.ndarray] | None = None,
        legal_moves: np.ndarray | None = None,
        passing_legal: list[bool] | None = None,
        soft_mask_legal: list[bool] | None = None,
        legal_row_meta: list[dict[str, Any]] | None = None,
        chosen_row_index: int | None = None,
    ) -> None:
        if (
            policy_obs is not None
            and legal_moves is not None
            and passing_legal is not None
            and soft_mask_legal is not None
            and chosen_row_index is not None
        ):
            self._capture_opening_mispick_if_needed(
                turn_number=turn_number,
                rewards=rewards,
                policy_obs=policy_obs,
                legal_moves=legal_moves,
                passing_legal=passing_legal,
                soft_mask_legal=soft_mask_legal,
                legal_row_meta=legal_row_meta or [],
                chosen_row_index=chosen_row_index,
                legal_choice=legal_choice,
                was_expected=expected_move,
            )
        if self._start_turn is not None and turn_number == self._start_turn:
            action_label = self.format_action(
                legal_choice,
                rewards=rewards,
                scored_touchdown=scored_touchdown,
                expected_move=expected_move,
                acquired_card=acquired_card,
            )
            self._actions.append(action_label)
            if acquired_card:
                claim_label = _describe_satisfied_claim(legal_choice, action_label=action_label)
                if claim_label is not None:
                    self._satisfied_claims.append(claim_label)

    def format_scenario_draw_log(
        self,
        *,
        scenario: str,
        difficulty: int,
        episode_mode: str,
        summary: dict[str, Any],
    ) -> str:
        """Multi-line text block for ``scenario_draws.log`` (one draw per episode)."""
        outcome = summary.get("result_winner_rel") or ("DRAW" if summary.get("result_kind") else "OPEN")
        header = (
            f"=== scenario={scenario} d={difficulty} mode={episode_mode} "
            f"outcome={outcome} score={summary.get('score_us')}-{summary.get('score_them')} ==="
        )
        indent = "  "
        setup_bits: list[str] = []
        ball = summary.get("scenario_initial_ball")
        if ball:
            setup_bits.append(f"ball={ball}")
        us = summary.get("scenario_us_positions")
        if us:
            setup_bits.append(f"us={us}")
        them = summary.get("scenario_them_positions")
        if them:
            setup_bits.append(f"them={them}")
        lines = [header]
        if setup_bits:
            lines.append(f"{indent}" + " ".join(setup_bits))
        cards = summary.get("scenario_available_cards")
        if cards:
            lines.append(f"{indent}cards={cards}")
        actions = summary.get("first_turn_actions") or []
        if actions:
            lines.append(f"{indent}turn1=" + " ".join(actions))
        claimed = summary.get("scenario_satisfied_claims") or []
        if claimed:
            lines.append(f"{indent}claimed=" + " ".join(claimed))
        return "\n".join(lines) + "\n"

    def episode_fields(self) -> dict[str, Any]:
        fields: dict[str, Any] = {}
        if self._first_action_legal_count is not None:
            fields["first_action_legal_count"] = self._first_action_legal_count
        if self._initial_ball is not None:
            fields["scenario_initial_ball"] = self._initial_ball
        if self._us_positions is not None:
            fields["scenario_us_positions"] = self._us_positions
        if self._them_positions is not None:
            fields["scenario_them_positions"] = self._them_positions
        if self._endzone_y is not None:
            fields["scenario_endzone_y"] = self._endzone_y
        if self._actions:
            fields["first_turn_actions"] = list(self._actions)
        if self._available_cards is not None:
            fields["scenario_available_cards"] = self._available_cards
        if self._satisfied_claims:
            fields["scenario_satisfied_claims"] = list(self._satisfied_claims)
        if self._expected_move_count:
            fields["expected_move_count"] = self._expected_move_count
            fields["expected_moves"] = list(self._expected_moves)
        if self._opening_mispick_snapshot is not None:
            fields["opening_mispick"] = True
            fields["opening_mispick_chosen"] = self._opening_mispick_snapshot.get("chosen_label")
        return fields

    def opening_mispick_snapshot(self) -> dict[str, Any] | None:
        return self._opening_mispick_snapshot

    @staticmethod
    def turn1_log_suffix(summary: dict[str, Any]) -> str:
        """Extra lines after the game summary: ball/setup, then turn1 (heavy indent)."""
        indent = "            "
        line1_bits: list[str] = []
        legals = summary.get("first_action_legal_count")
        if legals is not None:
            line1_bits.append(f"legals={legals}")

        line2_bits: list[str] = []
        ball = summary.get("scenario_initial_ball")
        if ball:
            line2_bits.append(f"ball={ball}")
        us = summary.get("scenario_us_positions")
        if us:
            line2_bits.append(f"us={us}")
        them = summary.get("scenario_them_positions")
        if them:
            line2_bits.append(f"them={them}")
        endzone_y = summary.get("scenario_endzone_y")
        if endzone_y is not None:
            line2_bits.append(f"endzone_y={endzone_y}")

        expected_count = int(summary.get("expected_move_count") or 0)
        expected = summary.get("expected_moves") or []
        if expected_count:
            if expected_count <= 2:
                line3 = f"{indent}expected=" + " ".join(expected)
            else:
                line3 = f"{indent}expected={expected_count} " + " ".join(expected)
        else:
            line3 = ""

        cards = summary.get("scenario_available_cards")
        line_cards = f"\n{indent}cards={cards}" if cards else ""

        actions = summary.get("first_turn_actions") or []
        line4 = f"{indent}turn1=" + " ".join(actions) if actions else ""

        claimed = summary.get("scenario_satisfied_claims") or []
        line_claimed = f"\n{indent}claimed=" + " ".join(claimed) if claimed else ""

        line5 = ""
        policy_diag = summary.get("opening_mispick_diag")
        if policy_diag:
            from gridiron_ai.eval_opening_diag import format_opening_mispick_diag

            line5 = format_opening_mispick_diag(policy_diag)
        elif summary.get("opening_mispick"):
            line5 = "\n            policy_diag=pending"

        parts: list[str] = []
        if line1_bits:
            parts.append(" " + " ".join(line1_bits))
        if line2_bits:
            parts.append(f"\n{indent}" + " ".join(line2_bits))
        if line3:
            parts.append(f"\n{line3}")
        if line_cards:
            parts.append(line_cards)
        if line4:
            parts.append(f"\n{line4}")
        if line_claimed:
            parts.append(line_claimed)
        if line5:
            parts.append(line5)
        return "".join(parts)

    @staticmethod
    def format_action(
        legal_choice: dict[str, Any],
        *,
        rewards: dict[str, Any] | None = None,
        scored_touchdown: bool = False,
        expected_move: bool = False,
        acquired_card: bool = False,
    ) -> str:
        """Compact label for logs. Actor geometry joins ``playerId`` → ``rewards.players``."""
        tag = str(legal_choice.get("kind") or "?")

        challenge_slot = _challenge_slot(legal_choice)
        attached_bonus = None
        bonus = legal_choice.get("requiredBonusCard")
        if bonus is not None:
            attached_bonus = str(bonus)

        target_xy = action_target_coordinate(legal_choice)
        source_xy = action_actor_coordinate(rewards, legal_choice) if rewards is not None else None
        actor_player_index = None
        if source_xy is None and rewards is not None:
            player_id = legal_choice.get("playerId")
            if isinstance(player_id, str):
                player = player_by_id(rewards, player_id)
                if player is not None:
                    actor_player_index = int(player["playerIndex"])

        extras: list[str] = []
        if challenge_slot is not None:
            extras.append(str(challenge_slot))
        if attached_bonus is not None:
            extras.append(attached_bonus)

        suffix = ("*" if expected_move else "") + ("!" if scored_touchdown else "") + ("+" if acquired_card else "")

        if source_xy is not None and target_xy is not None:
            from_label = _abs_xy_label(*source_xy)
            to_label = _abs_xy_label(*target_xy)
            body = from_label if source_xy == target_xy else f"{from_label}->{to_label}"
        elif source_xy is not None:
            body = _abs_xy_label(*source_xy)
        elif actor_player_index is not None:
            body = f"p{actor_player_index}"
        elif target_xy is not None:
            body = _abs_xy_label(*target_xy)
        elif extras:
            body = ",".join(extras)
        else:
            return f"{tag}{suffix}"

        if extras and (source_xy is not None or actor_player_index is not None or target_xy is not None):
            body = f"{body},{','.join(extras)}"
        return f"{tag}({body}){suffix}"

    def _capture_opening_mispick_if_needed(
        self,
        *,
        turn_number: int,
        rewards: dict[str, Any],
        policy_obs: dict[str, np.ndarray],
        legal_moves: np.ndarray,
        passing_legal: list[bool],
        soft_mask_legal: list[bool],
        legal_row_meta: list[dict[str, Any]],
        chosen_row_index: int,
        legal_choice: dict[str, Any],
        was_expected: bool,
    ) -> None:
        """First off-PASS player action on the opening coach turn.

        Do not gate on opening ``expected_move_count`` (replace menus have none) or on
        PASS/soft-mask rows for this decision (empty is the interesting failure). Skip
        coach-only rows (no ``playerId``) so a challenge-menu click is not the mispick.
        """
        if self._start_turn is None or turn_number != self._start_turn:
            return
        if was_expected or self._opening_mispick_snapshot is not None:
            return
        if not _has_actor(legal_choice):
            return
        from gridiron_ai.eval_opening_diag import snapshot_opening_mispick

        self._opening_mispick_snapshot = snapshot_opening_mispick(
            rewards=rewards,
            policy_obs=policy_obs,
            legal_moves=legal_moves,
            passing_legal=passing_legal,
            soft_mask_legal=soft_mask_legal,
            legal_row_meta=legal_row_meta,
            chosen_row_index=chosen_row_index,
            chosen_label=self.format_action(legal_choice, rewards=rewards),
        )


def append_scenario_draw_log(block: str, *, path: Any | None = None) -> None:
    """Append one draw block to the shared log (fcntl lock — safe under SubprocVecEnv)."""
    log_path = path or SCENARIO_DRAWS_LOG_PATH
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            handle.write(block)
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def _has_actor(legal_choice: dict[str, Any]) -> bool:
    """Player actions carry ``playerId``. Coach-only rows (replace, decline, claim) do not."""
    player_id = legal_choice.get("playerId")
    return isinstance(player_id, str) and bool(player_id)


def _challenge_slot(legal_choice: dict[str, Any]) -> int | None:
    if "slotIndex" in legal_choice and legal_choice["slotIndex"] is not None:
        return int(legal_choice["slotIndex"])
    return None


def _describe_satisfied_claim(legal_choice: dict[str, Any], *, action_label: str) -> str | None:
    """Which challenge card this action actually claimed (best-effort from server hints)."""
    claims = legal_choice.get("potentialChallengeClaims") or []
    slot = _challenge_slot(legal_choice)
    slot_bit = f"@slot{slot}" if slot is not None else ""
    if not claims:
        return f"unknown({action_label})"
    certain = [claim for claim in claims if claim.get("certainty") == "CERTAIN"]
    pool = certain or claims
    if len(pool) == 1:
        title = pool[0].get("challengeTitle") or pool[0].get("claimCondition")
        return f"{title}{slot_bit}({action_label})"
    titles = [str(claim.get("challengeTitle") or claim.get("claimCondition")) for claim in pool]
    return f"one-of[{','.join(titles)}]{slot_bit}({action_label})"


def _format_expected_moves(
    *,
    rewards: dict[str, Any],
    legal_row_meta: list[dict[str, Any]],
    passing_legal: list[bool],
) -> tuple[int, list[str]] | None:
    """Total passing-scenario rows and labels to print (all when ≤2, else two samples)."""
    if not legal_row_meta:
        return None
    if len(passing_legal) < len(legal_row_meta):
        passing_legal = list(passing_legal) + [False] * (len(legal_row_meta) - len(passing_legal))
    expected_indices = [index for index, passing in enumerate(passing_legal[: len(legal_row_meta)]) if passing]
    if not expected_indices:
        return None
    labels: list[str] = []
    for index in expected_indices:
        row = legal_row_meta[index]
        choice = row["action"] if isinstance(row.get("action"), dict) else row
        labels.append(ScenarioEvalDebug.format_action(choice, rewards=rewards))
    count = len(labels)
    if count <= 2:
        return count, labels
    return count, labels[:2]


def _abs_xy_label(x: int, y: int) -> str:
    return f"{x},{y}"


def _pitch_player_position_labels(rewards: dict[str, Any]) -> tuple[list[str], list[str]]:
    """Absolute pitch ``x,y`` labels for US / THEM from harness ``rewards.players``."""
    us: list[str] = []
    them: list[str] = []
    for player in rewards.get("players") or []:
        if not isinstance(player, dict):
            continue
        xy = coordinate_xy(player.get("coordinate"))
        if xy is None:
            continue
        label = _abs_xy_label(*xy)
        team_rel = player.get("teamRel")
        if team_rel == "US":
            us.append(label)
        elif team_rel == "THEM":
            them.append(label)
    return us, them

