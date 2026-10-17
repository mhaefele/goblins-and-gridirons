"""Shared fixture catalog for unit tests (no HTTP).

Mirrors a slim slice of the Kotlin catalog: a few board drills (incl. bounce proxy)
plus two claims — enough for curriculum / phase gate tests.
"""

from __future__ import annotations

from gridiron_ai.scenarios import ScenarioCatalog, ScenarioInfo, set_catalog

_FIXTURE = ScenarioCatalog.from_entries(
    [
        ScenarioInfo("TD_RUN", "BOARD"),
        ScenarioInfo("MARKED_RUN", "BOARD"),
        ScenarioInfo("THROW_TO_ENDZONE", "BOARD"),
        ScenarioInfo("THROW_CLOSER_RANGE", "BOARD"),
        ScenarioInfo("THROW_OPEN_RECEIVER", "BOARD"),
        ScenarioInfo("LOOSE_BALL_RUN", "BOARD"),
        ScenarioInfo("LOOSE_BALL_BOUNCE", "BOARD", difficulty_proxy="LOOSE_BALL_RUN"),
        ScenarioInfo("STRIP_AND_SCORE", "BOARD"),
        ScenarioInfo("CLAIM_GRAB_IT", "CLAIM"),
        ScenarioInfo("CLAIM_HUDDLE_UP", "CLAIM"),
    ]
)

BOARD_NAMES = _FIXTURE.board_names()
CLAIM_NAMES = _FIXTURE.claim_names()
FULL_NAMES = _FIXTURE.names()


def install_fixture_catalog() -> ScenarioCatalog:
    return set_catalog(_FIXTURE)
