"""Expert chosen-move kind tallies."""

from __future__ import annotations

from collections import Counter

from gridiron_ai.expert_stats import format_kind_table, wire_kind_from_action_id


def test_wire_kind_from_action_id() -> None:
    assert wire_kind_from_action_id("RUN_h4_8x13-9x14") == "RUN"
    assert wire_kind_from_action_id("REPLACECHALLENGE_0") == "REPLACECHALLENGE"
    assert wire_kind_from_action_id("") == "?"
    assert wire_kind_from_action_id(None) == "?"


def test_format_kind_table_percents() -> None:
    text = format_kind_table(Counter({"RUN": 3, "BLOCK": 1}))
    assert "RUN" in text and "75.00%" in text
    assert "BLOCK" in text and "25.00%" in text
    assert "TOTAL" in text and "4" in text
