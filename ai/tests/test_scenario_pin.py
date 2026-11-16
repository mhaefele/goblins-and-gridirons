"""Tests for --scenario pin resolution (number + case-insensitive name)."""

from __future__ import annotations

import pytest

from gridiron_ai.train.api.scenarios import (
    ScenarioCatalog,
    ScenarioInfo,
    format_scenario_pin_options,
    resolve_scenario_pin,
    summary_scenario_order,
)
from tests.catalog_fixture import install_fixture_catalog


def test_resolve_scenario_pin_case_insensitive() -> None:
    install_fixture_catalog()
    assert resolve_scenario_pin("claim_grab_it") == "CLAIM_GRAB_IT"
    assert resolve_scenario_pin("td_run") == "TD_RUN"
    assert resolve_scenario_pin("TD_RUN") == "TD_RUN"


def test_resolve_scenario_pin_by_summary_number() -> None:
    catalog = install_fixture_catalog()
    ordered = summary_scenario_order(catalog)
    assert resolve_scenario_pin("1", catalog) == ordered[0]
    assert resolve_scenario_pin(str(len(ordered)), catalog) == ordered[-1]


def test_resolve_scenario_pin_unknown_name_lists_numbered_options() -> None:
    install_fixture_catalog()
    with pytest.raises(ValueError, match="Unknown scenario 'nope'"):
        resolve_scenario_pin("nope")
    with pytest.raises(ValueError, match=r"valid 1–"):
        resolve_scenario_pin("999")


def test_format_scenario_pin_options_matches_summary_order() -> None:
    catalog = install_fixture_catalog()
    lines = format_scenario_pin_options(catalog).splitlines()
    assert lines[0].endswith(": td_run")
    assert lines[-1].endswith(": claim_huddle_up")
