"""Packed step/submit JSON contract checks — missing wire fields must fail loud."""

from __future__ import annotations

import pytest

from gridiron_ai.api.client import (
    GridironApiError,
    _assert_packed_step,
    _assert_packed_submit,
)
from gridiron_ai.constants import OBSERVATION_VECTOR_LENGTH, SCHEMA_VERSION


def _rewards_view() -> dict:
    return {
        "turnNumber": 1,
        "scoreUs": 0,
        "scoreThem": 0,
        "touchdownsUs": 0,
        "touchdownsThem": 0,
        "challengeCardsUs": 0,
        "challengeCardsThem": 0,
        "players": [
            {
                "playerId": "h0",
                "playerIndex": 1,
                "teamRel": "US",
                "coordinate": {"x": 2, "y": 3},
            }
        ],
        "ballCoordinate": {"x": 4, "y": 7},
    }


def _scoring_view() -> dict:
    return {
        "touchdownsUs": 0,
        "touchdownsThem": 0,
        "challengeCardsUs": 0,
        "challengeCardsThem": 0,
    }


def _harness_meta(*, ended: bool = False) -> dict:
    return {
        "actingSeat": "HOME",
        "perspectiveSeat": "HOME",
        "resultKind": None,
        "resultWinnerRel": None,
        "trainingEpisodeEnded": ended,
        "trainingOutcome": "PASS" if ended else None,
    }


def _legal_row(*, action_id: str = "a") -> dict:
    return {
        "action": {"kind": "run", "id": action_id},
        "softMaskLegal": False,
        "passingLegal": False,
    }


def _legal_rows(*, n: int = 1) -> list[dict]:
    return [_legal_row(action_id=f"a{i}") for i in range(n)]


def _minimal_step(*, n: int = 1, ended: bool = False, **overrides: object) -> dict:
    row_count = 0 if ended else n
    base = {
        "schemaVersion": SCHEMA_VERSION,
        "obs": [0.0] * OBSERVATION_VECTOR_LENGTH,
        "legalMoves": [[-1, 0, -1, -1, -1, -1, 0, 0, 0, -1, -1, -1, -1, -1, -1]] * row_count,
        "rewards": _rewards_view(),
        "legalRows": _legal_rows(n=row_count),
        **_harness_meta(ended=ended),
    }
    base.update(overrides)
    return base


def _minimal_submit(*, terminal: bool = False, handoff: bool = False) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "chosenLegalActionId": "x",
        "scoring": _scoring_view(),
        "afterSubmitRewards": _rewards_view() if handoff else None,
        "next": _minimal_step(n=1, ended=terminal),
    }


def test_assert_packed_step_requires_obs() -> None:
    step = _minimal_step()
    del step["obs"]
    with pytest.raises(GridironApiError, match="missing obs"):
        _assert_packed_step(step)


def test_assert_packed_step_requires_legal_rows() -> None:
    step = _minimal_step()
    del step["legalRows"][0]["softMaskLegal"]
    with pytest.raises(GridironApiError, match="missing softMaskLegal"):
        _assert_packed_step(step)


def test_assert_packed_step_requires_rewards_fields() -> None:
    step = _minimal_step()
    del step["rewards"]["turnNumber"]
    with pytest.raises(GridironApiError, match="missing turnNumber"):
        _assert_packed_step(step)


def test_assert_packed_step_requires_players() -> None:
    step = _minimal_step()
    del step["rewards"]["players"]
    with pytest.raises(GridironApiError, match="missing players"):
        _assert_packed_step(step)


def test_assert_packed_step_requires_obs_length() -> None:
    step = _minimal_step()
    step["obs"] = [0.0]
    with pytest.raises(GridironApiError, match=f"obs length 1 != {OBSERVATION_VECTOR_LENGTH}"):
        _assert_packed_step(step)


def test_assert_packed_step_live_requires_non_empty_legals() -> None:
    step = _minimal_step(n=0)
    with pytest.raises(GridironApiError, match="empty legalMoves"):
        _assert_packed_step(step)


def test_assert_packed_step_legal_rows_length_mismatch() -> None:
    step = _minimal_step(n=2)
    step["legalRows"] = [_legal_row()]
    with pytest.raises(GridironApiError, match="legalRows length 1 != 2"):
        _assert_packed_step(step)


def test_assert_packed_submit_requires_legal_rows() -> None:
    result = _minimal_submit()
    del result["next"]["legalRows"][0]["softMaskLegal"]
    with pytest.raises(GridironApiError, match="missing softMaskLegal"):
        _assert_packed_submit(result)


def test_assert_packed_submit_requires_after_submit_rewards_fields() -> None:
    result = _minimal_submit(handoff=True)
    del result["afterSubmitRewards"]["turnNumber"]
    with pytest.raises(GridironApiError, match="missing turnNumber"):
        _assert_packed_submit(result)


def test_assert_packed_submit_requires_scoring() -> None:
    result = _minimal_submit()
    del result["scoring"]["challengeCardsUs"]
    with pytest.raises(GridironApiError, match="missing challengeCardsUs"):
        _assert_packed_submit(result)


def test_assert_packed_submit_same_frame_omits_after_submit_rewards() -> None:
    result = _minimal_submit()
    assert result["afterSubmitRewards"] is None
    _assert_packed_submit(result)


def test_assert_packed_submit_handoff_includes_after_submit_rewards() -> None:
    result = _minimal_submit(handoff=True)
    assert result["afterSubmitRewards"] is not None
    _assert_packed_submit(result)


def test_assert_packed_submit_handoff_terminal_next() -> None:
    """Game can end during computer handoff — next is terminal with empty legals."""
    result = _minimal_submit(handoff=True, terminal=True)
    _assert_packed_submit(result)


def test_assert_packed_submit_terminal_rejects_live_legals() -> None:
    result = _minimal_submit(terminal=True)
    result["next"]["legalRows"] = [_legal_row()]
    result["next"]["legalMoves"] = [[-1, 0, -1, -1, -1, -1, 0, 0, 0, -1, -1, -1, -1, -1, -1]]
    with pytest.raises(GridironApiError, match="terminal but legalMoves"):
        _assert_packed_submit(result)
