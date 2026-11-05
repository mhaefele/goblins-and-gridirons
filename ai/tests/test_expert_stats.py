"""Expert chosen-move kind tallies."""

from __future__ import annotations

from collections import Counter

from gridiron_ai.expert_stats import (
    BUCKET_GAMES,
    BUCKET_SCENARIOS,
    BUCKET_TOTAL,
    add_kind_to_breakdown,
    bucket_for_episode_family,
    empty_kind_breakdown,
    format_kind_breakdown,
    format_kind_table,
    wire_kind_from_action_id,
)


def test_wire_kind_from_action_id() -> None:
    assert wire_kind_from_action_id("RUN_h4_8x13-9x14") == "RUN"
    assert wire_kind_from_action_id("REPLACECHALLENGE_0") == "REPLACECHALLENGE"
    assert wire_kind_from_action_id("") == "?"
    assert wire_kind_from_action_id(None) == "?"


def test_bucket_for_episode_family() -> None:
    assert bucket_for_episode_family("scenario") == BUCKET_SCENARIOS
    assert bucket_for_episode_family("regular") == BUCKET_GAMES
    assert bucket_for_episode_family(None) is None
    assert bucket_for_episode_family("") is None


def test_format_kind_table_percents() -> None:
    text = format_kind_table(Counter({"RUN": 3, "BLOCK": 1}))
    assert "RUN" in text and "75.00%" in text
    assert "BLOCK" in text and "25.00%" in text
    assert "TOTAL" in text and "4" in text


def test_format_kind_breakdown_sections() -> None:
    breakdown = empty_kind_breakdown()
    add_kind_to_breakdown(breakdown, "RUN", bucket=BUCKET_SCENARIOS, count=2)
    add_kind_to_breakdown(breakdown, "THROWTEAMMATE", bucket=BUCKET_GAMES, count=1)
    text = format_kind_breakdown(breakdown, title="pack kinds")
    assert "pack kinds" in text
    assert "=== scenarios ===" in text
    assert "=== games ===" in text
    assert "=== total ===" in text
    assert "RUN" in text and "THROWTEAMMATE" in text
    assert breakdown[BUCKET_TOTAL]["RUN"] == 2
    assert breakdown[BUCKET_TOTAL]["THROWTEAMMATE"] == 1


def test_format_kind_breakdown_single_family_omits_empty_and_total() -> None:
    breakdown = empty_kind_breakdown()
    add_kind_to_breakdown(breakdown, "RUN", bucket=BUCKET_SCENARIOS, count=2)
    text = format_kind_breakdown(breakdown, title="scenario only")
    assert "=== scenarios ===" in text
    assert "=== games ===" not in text
    assert "=== total ===" not in text
    assert "(no steps)" not in text


def test_format_kind_breakdown_untagged_total_only() -> None:
    breakdown = empty_kind_breakdown()
    add_kind_to_breakdown(breakdown, "RUN", bucket=None, count=3)
    text = format_kind_breakdown(breakdown)
    assert "=== total ===" in text
    assert "=== scenarios ===" not in text
    assert "=== games ===" not in text


def test_untagged_family_counts_total_only() -> None:
    breakdown = empty_kind_breakdown()
    add_kind_to_breakdown(breakdown, "RUN", bucket=bucket_for_episode_family(None), count=3)
    assert breakdown[BUCKET_SCENARIOS] == Counter()
    assert breakdown[BUCKET_GAMES] == Counter()
    assert breakdown[BUCKET_TOTAL] == Counter({"RUN": 3})
