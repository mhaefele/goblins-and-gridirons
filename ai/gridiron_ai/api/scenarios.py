"""Training scenario catalog — fetched from Kotlin, cached under ``output/``.

Owner: parse/cache ``GET /api/ai/training/scenarios`` (name, family, difficultyProxy).
Board scripts stay in Kotlin. Curriculum *policy* stays in ``scenario_curriculum``.
Do **not** hard-code enum name lists here — see ``ai/OWNERSHIP.md``.

Train/eval: ``refresh_catalog(client)`` once at startup (writes cache). Mid-run callers
use the in-process catalog. Offline tools: ``load_cached_catalog()``.
Unknown drills in a difficulty JSON are ignored; new catalog names start at difficulty 0.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Sequence

from gridiron_ai.api.harness_wire import ScenarioCatalogEntryDict, ScenarioFamily, ScenarioName
from gridiron_ai.paths import SCENARIO_CATALOG_PATH
from gridiron_ai.persistence.scenario_catalog import load_catalog_payload, save_catalog_payload

_process_catalog: ScenarioCatalog | None = None


@dataclass(frozen=True)
class ScenarioInfo:
    name: ScenarioName
    family: ScenarioFamily
    difficulty_proxy: ScenarioName | None = None
    gates_progress: bool = True


@dataclass(frozen=True)
class ScenarioCatalog:
    """Ordered catalog matching the server list endpoint."""

    entries: tuple[ScenarioInfo, ...]

    def names(self) -> list[ScenarioName]:
        return [entry.name for entry in self.entries]

    def board_names(self) -> list[ScenarioName]:
        return [entry.name for entry in self.entries if entry.family == ScenarioFamily.BOARD]

    def claim_names(self) -> list[ScenarioName]:
        return [entry.name for entry in self.entries if entry.family == ScenarioFamily.CLAIM]

    def get(self, name: ScenarioName) -> ScenarioInfo | None:
        for entry in self.entries:
            if entry.name == name:
                return entry
        return None

    def is_claim(self, name: ScenarioName | None) -> bool:
        if not name:
            return False
        entry = self.get(name)
        return entry is not None and entry.family == ScenarioFamily.CLAIM

    def is_board(self, name: ScenarioName | None) -> bool:
        if not name:
            return False
        entry = self.get(name)
        return entry is not None and entry.family == ScenarioFamily.BOARD

    def gates_progress(self, name: ScenarioName | None) -> bool:
        if not name:
            return True
        entry = self.get(name)
        return entry is None or entry.gates_progress

    def curriculum_key(self, scenario: ScenarioName) -> ScenarioName:
        entry = self.get(scenario)
        if entry is not None and entry.difficulty_proxy:
            return entry.difficulty_proxy
        return scenario

    def proxy_sources(self) -> frozenset[ScenarioName]:
        """Scenarios whose difficulty is stored under another name (omit from JSON)."""
        return frozenset(entry.name for entry in self.entries if entry.difficulty_proxy)

    def short_name(self, scenario: ScenarioName) -> str:
        return scenario.lower()

    @classmethod
    def from_entries(cls, entries: Sequence[ScenarioInfo]) -> ScenarioCatalog:
        return cls(entries=tuple(entries))

    @classmethod
    def from_wire(cls, raw: Sequence[ScenarioCatalogEntryDict]) -> ScenarioCatalog:
        entries: list[ScenarioInfo] = []
        for item in raw:
            if not isinstance(item, dict):
                raise ValueError(f"catalog entry must be an object, got {type(item).__name__}")
            name = str(item["name"])
            try:
                family = ScenarioFamily(str(item["family"]))
            except ValueError as exc:
                raise ValueError(f"unknown scenario family {item['family']!r} for {name}") from exc
            proxy = item.get("difficultyProxy")
            gates = item.get("gatesProgress", True)
            entries.append(
                ScenarioInfo(
                    name=name,
                    family=family,
                    difficulty_proxy=str(proxy) if proxy else None,
                    gates_progress=bool(gates),
                )
            )
        return cls.from_entries(entries)


def set_catalog(catalog: ScenarioCatalog) -> ScenarioCatalog:
    """Install the process-wide catalog (tests / after fetch)."""
    global _process_catalog
    _process_catalog = catalog
    return catalog


def clear_catalog() -> None:
    global _process_catalog
    _process_catalog = None


def get_catalog() -> ScenarioCatalog | None:
    return _process_catalog


def require_catalog() -> ScenarioCatalog:
    if _process_catalog is not None:
        return _process_catalog
    cached = load_cached_catalog()
    if cached is not None:
        return set_catalog(cached)
    raise RuntimeError(
        f"scenario catalog not loaded (no process catalog and no cache at {SCENARIO_CATALOG_PATH}); "
        "run train/eval against a live server once, or call refresh_catalog(client)"
    )


def load_cached_catalog() -> ScenarioCatalog | None:
    raw = load_catalog_payload()
    if raw is None:
        return None
    try:
        return ScenarioCatalog.from_wire(raw["scenarios"])
    except (KeyError, TypeError, ValueError):
        return None


def save_catalog(catalog: ScenarioCatalog) -> None:
    rows: list[ScenarioCatalogEntryDict] = []
    for entry in catalog.entries:
        row: ScenarioCatalogEntryDict = {"name": entry.name, "family": entry.family}
        if entry.difficulty_proxy:
            row["difficultyProxy"] = entry.difficulty_proxy
        if not entry.gates_progress:
            row["gatesProgress"] = entry.gates_progress
        rows.append(row)
    save_catalog_payload(rows)


def refresh_catalog(client: Any) -> ScenarioCatalog:
    """GET catalog from the live API, write ``output/scenario_catalog.json``, install process-wide."""
    catalog = ScenarioCatalog.from_wire(client.list_training_scenario_catalog())
    save_catalog(catalog)
    return set_catalog(catalog)


def is_claim_scenario(scenario: ScenarioName | None) -> bool:
    return require_catalog().is_claim(scenario)


def scenario_short_name(scenario: ScenarioName) -> str:
    return require_catalog().short_name(scenario)


def scenario_phase_order(
    names: Sequence[ScenarioName], catalog: ScenarioCatalog | None = None
) -> list[ScenarioName]:
    """Gating drills first, optional (``gatesProgress=false``) last — within one family."""
    catalog = catalog or require_catalog()
    gating = [name for name in names if catalog.gates_progress(name)]
    optional = [name for name in names if not catalog.gates_progress(name)]
    return gating + optional


def summary_scenario_order(catalog: ScenarioCatalog | None = None) -> list[ScenarioName]:
    """Board then claim, same order as ``summarize_models`` drill list (for ``--scenario N``)."""
    catalog = catalog or require_catalog()
    return scenario_phase_order(catalog.board_names(), catalog) + scenario_phase_order(
        catalog.claim_names(), catalog
    )


def format_scenario_pin_options(catalog: ScenarioCatalog | None = None) -> str:
    catalog = catalog or require_catalog()
    return "\n".join(
        f"  {num:2d}: {catalog.short_name(name)}"
        for num, name in enumerate(summary_scenario_order(catalog), start=1)
    )


def resolve_scenario_pin(pin: str, catalog: ScenarioCatalog | None = None) -> ScenarioName:
    """Map ``--scenario`` pin to canonical catalog name (number, any case, or exact name)."""
    catalog = catalog or require_catalog()
    raw = pin.strip()
    if not raw:
        raise ValueError(
            "Empty --scenario value. Options (number or name, case-insensitive):\n"
            f"{format_scenario_pin_options(catalog)}"
        )
    if raw.isdigit():
        index = int(raw)
        ordered = summary_scenario_order(catalog)
        if index < 1 or index > len(ordered):
            raise ValueError(
                f"Unknown scenario number {index!r} (valid 1–{len(ordered)}). Options:\n"
                f"{format_scenario_pin_options(catalog)}"
            )
        return ordered[index - 1]
    lowered = raw.lower()
    for name in catalog.names():
        if name.lower() == lowered:
            return name
    raise ValueError(
        f"Unknown scenario {pin!r}. Options (number or name, case-insensitive):\n"
        f"{format_scenario_pin_options(catalog)}"
    )
