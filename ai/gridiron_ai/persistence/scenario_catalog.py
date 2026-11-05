"""``output/scenario_catalog.json`` — cached GET /api/ai/training/scenarios payload."""

from __future__ import annotations

import json
from typing import Any

from gridiron_ai.paths import SCENARIO_CATALOG_PATH
from gridiron_ai.persistence.json_io import atomic_write_json

_CACHE_SCHEMA = 1


def catalog_path():
    """``output/scenario_catalog.json``."""
    return SCENARIO_CATALOG_PATH


def load_catalog_payload() -> dict[str, Any] | None:
    """Raw cache dict, or ``None`` when missing / unusable."""
    path = SCENARIO_CATALOG_PATH
    if not path.is_file():
        return None
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return None
    scenarios = raw.get("scenarios")
    if not isinstance(scenarios, list):
        return None
    return raw


def save_catalog_payload(scenarios: list[dict[str, Any]]) -> None:
    """Write wire-shaped scenario rows under ``schemaVersion``."""
    atomic_write_json(
        SCENARIO_CATALOG_PATH,
        {"schemaVersion": _CACHE_SCHEMA, "scenarios": scenarios},
    )
