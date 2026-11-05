"""HTTP client for the Kotlin game / AI trainer API (no SSE).

Owner: REST only — create/start, packed step/submit, training-scenario list/create.
Called by ``env`` (and train helpers). Does not interpret obs beyond JSON→dict.
See ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

import logging
from typing import Any

import requests

from gridiron_ai.constants import (
    BENCH_SLOTS,
    FACTORS_PER_LEGAL_MOVE,
    OBSERVATION_VECTOR_LENGTH,
    PLAY_HEIGHT,
    PLAY_WIDTH,
    SCHEMA_VERSION,
)
from gridiron_ai.harness_wire import (
    AiLegalChoiceDict,
    AiScoringDeltaDict,
    ComputerOpponentKind,
    ControlledBy,
    CoordinateDict,
    EpisodeMode,
    HarnessPlayerDict,
    HarnessRewardSnapshotDict,
    PackedStepDict,
    PackedSubmitDict,
    ScenarioCatalogEntryDict,
    ScenarioName,
    SubmitTerminalMetaDict,
    TeamRel,
)

logger = logging.getLogger(__name__)

# DEVIATION: pin a simple roster for v1 training (AI2.5 will rotate teams). Amazon abilities
# (Strike and Fade, Disruptor, …) lengthen block chains; Human is closer to baseline rules.
_DEFAULT_TRAIN_TEAM_ID = "human"

_REWARDS_KEYS = (
    "turnNumber",
    "scoreUs",
    "scoreThem",
    "touchdownsUs",
    "touchdownsThem",
    "challengeCardsUs",
    "challengeCardsThem",
    "players",
    "ballCoordinate",
)


_SCORING_KEYS = ("touchdownsUs", "touchdownsThem", "challengeCardsUs", "challengeCardsThem")

_HARNESS_META_KEYS = (
    "actingSeat",
    "perspectiveSeat",
    "resultKind",
    "resultWinnerRel",
    "trainingEpisodeEnded",
    "trainingOutcome",
)

_HARNESS_LEGAL_ROW_KEYS = (
    "action",
    "softMaskLegal",
    "passingLegal",
)


def _assert_packed_legal_row(row: object, *, label: str) -> None:
    if not isinstance(row, dict):
        raise GridironApiError(f"{label} must be an object")
    for key in _HARNESS_LEGAL_ROW_KEYS:
        _require_key(row, key, label=label)
    if not isinstance(row["softMaskLegal"], bool):
        raise GridironApiError(f"{label} softMaskLegal must be a bool")
    if not isinstance(row["passingLegal"], bool):
        raise GridironApiError(f"{label} passingLegal must be a bool")
    action = row["action"]
    if not isinstance(action, dict):
        raise GridironApiError(f"{label} action must be an object")
    _require_key(action, "kind", label=f"{label}.action")
    _require_key(action, "id", label=f"{label}.action")


def _assert_packed_legal_rows(rows: object, *, label: str, expected_rows: int) -> None:
    if not isinstance(rows, list):
        raise GridironApiError(f"{label} must be a JSON array")
    if len(rows) != expected_rows:
        raise GridironApiError(f"{label} length {len(rows)} != {expected_rows}")
    for index, row in enumerate(rows):
        _assert_packed_legal_row(row, label=f"{label}[{index}]")


class GridironApiError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None, body: str | None = None) -> None:
        super().__init__(message)
        self.status = status
        self.body = body


def _response_message(resp: requests.Response) -> str:
    """Prefer Spring ProblemDetail ``detail`` (then message/title); else a short body snippet."""
    text = (resp.text or "").strip()
    if not text:
        return "(empty body)"
    try:
        payload = resp.json()
    except ValueError:
        return text if len(text) <= 500 else text[:497] + "..."
    if isinstance(payload, dict):
        for key in ("detail", "message", "title"):
            value = payload.get(key)
            if value:
                return str(value)
    return text if len(text) <= 500 else text[:497] + "..."


class GridironClient:
    def __init__(self, base_url: str = "http://localhost:18080", timeout_s: float = 60.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_s = timeout_s
        self._session = requests.Session()

    def close(self) -> None:
        self._session.close()

    # --- Public API Methods ---

    def catalog_teams(self) -> list[dict[str, Any]]:
        """GET /api/catalog/teams"""
        return list(self._get("/api/catalog/teams")["teams"])

    def list_computer_opponents(self) -> dict[str, Any]:
        """GET /api/computer-opponents — seatable ``opponents`` + ``unavailable`` with reasons."""
        return self._get("/api/computer-opponents")

    def seatable_computer_opponents(self) -> set[tuple[str, str | None]]:
        """``(kind, onnxProfile|None)`` pairs the server will accept on match create."""
        catalog = self.list_computer_opponents()
        seats: set[tuple[str, str | None]] = set()
        for row in catalog["opponents"]:
            kind = str(row["kind"])
            profile = row.get("onnxProfile")
            seats.add((kind, str(profile) if profile is not None else None))
        return seats

    def unavailable_computer_opponent_reasons(self) -> dict[tuple[str, str | None], str]:
        """``(kind, onnxProfile|None) → reason`` for seats the server will refuse."""
        catalog = self.list_computer_opponents()
        reasons: dict[tuple[str, str | None], str] = {}
        for row in catalog.get("unavailable") or []:
            kind = str(row["kind"])
            profile = row.get("onnxProfile")
            reasons[(kind, str(profile) if profile is not None else None)] = str(row["reason"])
        return reasons

    def default_team_ids(self, *, same_team_both_seats: bool = True) -> tuple[str, str]:
        teams = self.catalog_teams()
        if not teams:
            raise GridironApiError("Catalog returned no teams")
        ids = {str(team["id"]) for team in teams}
        if _DEFAULT_TRAIN_TEAM_ID not in ids:
            raise GridironApiError(f"Default train team {_DEFAULT_TRAIN_TEAM_ID!r} not in catalog")
        home = _DEFAULT_TRAIN_TEAM_ID
        away = home if same_team_both_seats else next(tid for tid in ids if tid != home)
        return home, away

    def create_match(
        self,
        home_team_id: str,
        away_team_id: str,
        *,
        pitch_definition_id: str = "default",
        home_controlled_by: ControlledBy = ControlledBy.HUMAN,
        away_controlled_by: ControlledBy = ControlledBy.HUMAN,
        computer_opponent: ComputerOpponentKind = ComputerOpponentKind.LOOKAHEAD,
        onnx_profile: str | None = None,
    ) -> str:
        """POST /api/matches.

        Train/eval: one HARNESS (policy) + one COMPUTER. ``computer_opponent`` is LOOKAHEAD,
        HEURISTIC, or ONNX (with ``onnx_profile`` = reward profile id / ``ai-opponent/{id}/``).
        Ignored when both seats are HUMAN; server default LOOKAHEAD.
        """
        body: dict[str, Any] = {
            "homeTeamId": home_team_id,
            "awayTeamId": away_team_id,
            "pitchDefinitionId": pitch_definition_id,
            "homeControlledBy": home_controlled_by,
            "awayControlledBy": away_controlled_by,
            "computerOpponent": computer_opponent,
        }
        if onnx_profile is not None:
            body["onnxProfile"] = onnx_profile
        return self._post("/api/matches", json=body)["matchId"]

    def list_training_scenario_catalog(self) -> list[ScenarioCatalogEntryDict]:
        """GET /api/ai/training/scenarios — catalog entries (name, family, difficultyProxy)."""
        return list(self._get("/api/ai/training/scenarios"))

    def start_match(self, match_id: str) -> None:
        """POST /api/matches/{id}/start"""
        self._post(f"/api/matches/{match_id}/start")

    def create_and_start(self, home_team_id: str, away_team_id: str, **kwargs: Any) -> str:
        match_id = self.create_match(home_team_id, away_team_id, **kwargs)
        self.start_match(match_id)
        return match_id

    def delete_match(self, match_id: str) -> None:
        """DELETE /api/matches/{id} — free in-memory match state (idempotent)."""
        self._request("DELETE", f"/api/matches/{match_id}")

    def create_scenario_match(
        self,
        scenario: ScenarioName,
        *,
        difficulty: int,
        episode_mode: EpisodeMode,
        away_controlled_by: ControlledBy,
        computer_opponent: ComputerOpponentKind,
    ) -> str:
        """POST /api/ai/training/matches — create + open a scenario already mid-drill.

        ``scenario`` is a catalog name (``TrainingScenarioEnum`` on the server). HOME is always
        HARNESS; teams and pitch are chosen on the server. Callers must pass every field (no
        Kotlin defaults). ``difficulty`` (0–5) scatters that many extra trainer reserves after
        scenario pieces. ``episode_mode`` is reserved (TRAIN/EVAL); legals are always full
        engine today. Returns the match id; no separate start call is needed.
        """
        body: dict[str, Any] = {
            "scenario": scenario,
            "difficulty": difficulty,
            "episodeMode": episode_mode,
            "awayControlledBy": away_controlled_by,
            "computerOpponent": computer_opponent,
        }
        return self._post("/api/ai/training/matches", json=body)["matchId"]

    def ai_step(
        self,
        match_id: str,
        *,
        perspective_seat: str | None = None,
        for_seat: str,
        timeout_s: float | None = None,
    ) -> PackedStepDict:
        """GET /api/ai/matches/{id}/step/packed — [AiStepDto] with float[] policy.obs.

        ``for_seat`` blocks until that seat must act (or terminal).
        Nested ``…/step`` stays for curl / Kotlin functional tests only.
        """
        path = f"/api/ai/matches/{match_id}/step/packed" + _query(
            perspectiveSeat=perspective_seat, forSeat=for_seat
        )
        step = self._request("GET", path, timeout_s=timeout_s)
        _assert_packed_step(step)
        return flatten_packed_step(step)

    def ai_submit(
        self,
        match_id: str,
        legal_action_id: str,
        *,
        for_seat: str,
        timeout_s: float | None = None,
    ) -> PackedSubmitDict:
        """POST /api/ai/matches/{id}/submit/packed — legalActionId body; [AiSubmitResultDto]."""
        path = f"/api/ai/matches/{match_id}/submit/packed" + _query(forSeat=for_seat)
        result = self._request("POST", path, json=legal_action_id, timeout_s=timeout_s)
        _assert_packed_submit(result)
        return result

    # --- Private Helpers ---

    def _get(self, path: str) -> Any:
        return self._request("GET", path)

    def _post(self, path: str, json: Any = None) -> Any:
        return self._request("POST", path, json=json)

    def _request(self, method: str, path: str, json: Any = None, timeout_s: float | None = None) -> Any:
        timeout = self.timeout_s if timeout_s is None else timeout_s
        try:
            resp = self._session.request(method, f"{self.base_url}{path}", json=json, timeout=timeout)
        except requests.RequestException as exc:
            msg = f"{method} {path} failed: {exc}"
            logger.error(msg)
            raise GridironApiError(msg) from None
        if resp.status_code >= 400:
            detail = _response_message(resp)
            msg = f"{method} {path} → HTTP {resp.status_code}: {detail}"
            # One line only — SubprocVecEnv workers do not forward exceptions (parent sees EOFError).
            logger.error(msg)
            raise GridironApiError(msg, status=resp.status_code, body=resp.text) from None
        return None if not resp.content else resp.json()


def _query(**params: str | None) -> str:
    parts = [f"{key}={value}" for key, value in params.items() if value]
    return "?" + "&".join(parts) if parts else ""


def _require_key(payload: dict[str, object], key: str, *, label: str) -> None:
    if key not in payload:
        raise GridironApiError(f"{label} missing {key}")


def _require_bool(payload: dict[str, object], key: str, *, label: str) -> None:
    _require_key(payload, key, label=label)
    if not isinstance(payload[key], bool):
        raise GridironApiError(f"{label} {key} must be a bool")


def _is_episode_ended_state(state: PackedStepDict) -> bool:
    return state["resultKind"] is not None or bool(state["trainingEpisodeEnded"])


def submit_scoring(result: PackedSubmitDict) -> AiScoringDeltaDict:
    """Per-submit TD / challenge-card deltas."""
    scoring = result["scoring"]
    if not isinstance(scoring, dict):
        raise GridironApiError("Packed submit scoring must be an object")
    for key in _SCORING_KEYS:
        _require_key(scoring, key, label="Packed submit scoring")
    return scoring


def submit_rewards_after(result: PackedSubmitDict) -> HarnessRewardSnapshotDict:
    """Harness snapshot for scoring the action just submitted — pre-handoff when set."""
    after = result.get("afterSubmitRewards")
    if after is not None:
        return after
    return result["next"]["rewards"]


def submit_terminal_meta(result: PackedSubmitDict) -> SubmitTerminalMetaDict:
    """Episode meta for step_reward.

    Same-frame submit: read from [next]. Handoff submit: usually none (trainer still
    playing out the turn); when [next] is already terminal after the computer handoff,
    use that frame so match-end reward fires on this step.
    """
    nxt = result["next"]
    if result.get("afterSubmitRewards") is not None and not _is_episode_ended_state(nxt):
        return {
            "resultKind": None,
            "resultWinnerRel": None,
            "trainingEpisodeEnded": False,
            "trainingOutcome": None,
        }
    return {
        "resultKind": nxt.get("resultKind"),
        "resultWinnerRel": nxt.get("resultWinnerRel"),
        "trainingEpisodeEnded": bool(nxt.get("trainingEpisodeEnded")),
        "trainingOutcome": nxt.get("trainingOutcome"),
    }


def flatten_packed_step(step: PackedStepDict) -> PackedStepDict:
    """Packed step JSON → flat dict for gym helpers (server wire is already flat)."""
    return {
        "schemaVersion": step["schemaVersion"],
        "obs": step["obs"],
        "legalMoves": step["legalMoves"],
        "rewards": step["rewards"],
        "legalRows": step["legalRows"],
        **{key: step[key] for key in _HARNESS_META_KEYS},
    }


def flatten_packed_submit(result: PackedSubmitDict) -> PackedStepDict:
    """Packed submit [next] → flat step dict for gym install."""
    return flatten_packed_step(result["next"])


def _assert_packed_submit(result: PackedSubmitDict) -> None:
    _require_key(result, "schemaVersion", label="Packed submit")
    if result["schemaVersion"] != SCHEMA_VERSION:
        raise GridironApiError(
            f"Packed submit schemaVersion={result['schemaVersion']} (harness expects {SCHEMA_VERSION})"
        )
    _require_key(result, "chosenLegalActionId", label="Packed submit")
    if not result["chosenLegalActionId"]:
        raise GridironApiError("Packed submit missing chosenLegalActionId")
    _require_key(result, "afterSubmitRewards", label="Packed submit")
    _require_key(result, "scoring", label="Packed submit")
    _require_key(result, "next", label="Packed submit")
    submit_scoring(result)
    after = result["afterSubmitRewards"]
    if after is not None:
        _assert_harness_rewards(after, label="Packed submit afterSubmitRewards")
    _assert_packed_step(result["next"], label="Packed submit next")
    next_ended = _is_episode_ended_state(result["next"])
    row_count = len(result["next"]["legalMoves"])
    if next_ended:
        if row_count != 0:
            raise GridironApiError("Packed submit terminal post-action requires empty legalMoves")
    elif row_count == 0:
        raise GridironApiError("Packed submit continuing requires non-empty legalMoves")


def _assert_packed_harness_legal_rows(
    rows: list[AiLegalChoiceDict] | object, *, label: str, expected_rows: int
) -> None:
    _assert_packed_legal_rows(rows, label=f"{label} legalRows", expected_rows=expected_rows)


def _assert_coordinate(raw: CoordinateDict | object, *, label: str) -> None:
    if not isinstance(raw, dict):
        raise GridironApiError(f"{label} must be an object")
    _require_key(raw, "x", label=label)
    _require_key(raw, "y", label=label)
    x = raw["x"]
    y = raw["y"]
    if not isinstance(x, int) or isinstance(x, bool) or not (1 <= x <= PLAY_WIDTH):
        raise GridironApiError(f"{label}.x={x!r} out of range")
    if not isinstance(y, int) or isinstance(y, bool) or not (1 <= y <= PLAY_HEIGHT):
        raise GridironApiError(f"{label}.y={y!r} out of range")


def _assert_harness_player(player: HarnessPlayerDict | object, *, label: str) -> None:
    if not isinstance(player, dict):
        raise GridironApiError(f"{label} must be an object")
    _require_key(player, "playerId", label=label)
    _require_key(player, "playerIndex", label=label)
    _require_key(player, "teamRel", label=label)
    _require_key(player, "coordinate", label=label)
    player_id = player["playerId"]
    if not isinstance(player_id, str) or not player_id:
        raise GridironApiError(f"{label}.playerId must be a non-empty string")
    index = player["playerIndex"]
    if not isinstance(index, int) or isinstance(index, bool) or not (1 <= index <= BENCH_SLOTS):
        raise GridironApiError(f"{label}.playerIndex={index!r} out of range")
    team_rel = player["teamRel"]
    if team_rel not in (TeamRel.US, TeamRel.THEM):
        raise GridironApiError(f"{label}.teamRel={team_rel!r} must be US or THEM")
    coord = player["coordinate"]
    if coord is not None:
        _assert_coordinate(coord, label=f"{label}.coordinate")


def _assert_harness_rewards(rewards: HarnessRewardSnapshotDict | object, *, label: str) -> None:
    if not isinstance(rewards, dict):
        raise GridironApiError(f"{label} rewards must be an object")
    for key in _REWARDS_KEYS:
        _require_key(rewards, key, label=label)
    players = rewards["players"]
    if not isinstance(players, list):
        raise GridironApiError(f"{label} rewards.players must be a JSON array")
    if len(players) > 2 * BENCH_SLOTS:
        raise GridironApiError(
            f"{label} rewards.players length {len(players)} > {2 * BENCH_SLOTS}"
        )
    seen_ids: set[str] = set()
    for index, player in enumerate(players):
        player_label = f"{label} rewards.players[{index}]"
        _assert_harness_player(player, label=player_label)
        player_id = player["playerId"]
        if player_id in seen_ids:
            raise GridironApiError(f"{label} rewards.players duplicate playerId={player_id!r}")
        seen_ids.add(player_id)
    ball = rewards["ballCoordinate"]
    if ball is not None:
        _assert_coordinate(ball, label=f"{label} rewards.ballCoordinate")


def _assert_packed_step(step: PackedStepDict, *, label: str = "Packed step") -> None:
    _require_key(step, "schemaVersion", label=label)
    if step["schemaVersion"] != SCHEMA_VERSION:
        raise GridironApiError(
            f"{label} schemaVersion={step['schemaVersion']} (harness expects {SCHEMA_VERSION})"
        )
    _require_key(step, "obs", label=label)
    _require_key(step, "legalMoves", label=label)
    for key in _HARNESS_META_KEYS:
        _require_key(step, key, label=label)
    _require_bool(step, "trainingEpisodeEnded", label=label)
    _require_key(step, "rewards", label=label)
    _assert_harness_rewards(step["rewards"], label=label)
    if step["trainingEpisodeEnded"]:
        if step["resultKind"] is not None:
            raise GridironApiError(f"{label} trainingEpisodeEnded must not combine with resultKind")
        if step["trainingOutcome"] is None:
            raise GridironApiError(f"{label} trainingEpisodeEnded requires trainingOutcome")
    obs = step["obs"]
    if not isinstance(obs, list):
        raise GridironApiError(f"{label} obs must be a JSON array")
    if len(obs) != OBSERVATION_VECTOR_LENGTH:
        raise GridironApiError(f"{label} obs length {len(obs)} != {OBSERVATION_VECTOR_LENGTH}")
    moves = step["legalMoves"]
    if not isinstance(moves, list):
        raise GridironApiError(f"{label} legalMoves must be a JSON array")
    for index, row in enumerate(moves):
        if not isinstance(row, list):
            raise GridironApiError(f"{label} legalMoves[{index}] must be a JSON array")
        if len(row) != FACTORS_PER_LEGAL_MOVE:
            raise GridironApiError(
                f"{label} legalMoves[{index}] width {len(row)} != {FACTORS_PER_LEGAL_MOVE}"
            )
    _require_key(step, "legalRows", label=label)
    row_count = len(moves)
    _assert_packed_harness_legal_rows(step["legalRows"], label=label, expected_rows=row_count)
    ended = _is_episode_ended_state(step)
    if ended and row_count != 0:
        raise GridironApiError(f"{label} terminal but legalMoves length {row_count} != 0")
    if not ended and row_count == 0:
        raise GridironApiError(f"{label} live decision has empty legalMoves")
