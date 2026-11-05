"""Per-model scenario difficulty (0–5) and cycle filters.

Owner: curriculum *policy* next to a checkpoint stem — levels JSON, unlock/lead gates,
per-drill train budgets, which drills run this cycle.
Reads: process/cached ``ScenarioCatalog`` (family + difficultyProxy); levels from disk.
Writes: ``{stem}.scenario_difficulty.json``. Does **not** place boards (Kotlin) or own
phase routing (``training_phases``). See ``ai/OWNERSHIP.md`` / ``TRAINING_ROADMAP.md``.

File: ``{save_stem}.scenario_difficulty.json`` — name → level; bump after perfect after-eval;
demote when a drill labeled d5 fails after-eval with ``DEMOTE_FROM_MAX_MAX_WINS`` or fewer
wins (default 7/10 — 8/10 is a gate fail only). ``{save_stem}.scenario_gate_failures.json``
counts imperfect after-evals per difficulty level (curriculum key). Per-level counts are kept
across promotions (all-time = sum); only the current level is cleared on waive recover.
``{save_stem}.scenario_drill_stats.json`` — per-phase drill ``cycles`` (train sandwich
units; gate-fail 2× budget counts as 2) and ``d5_demotes``. Summary / train multipliers
use ``training_phases.load_drill_stats_cumulative`` (scenario_a → … → current).
Phases (scenario_a board→d4 / scenario_b all→d5 / profiles games): ``training_phases``.
Drills with fails at their **current** difficulty get ``SCENARIO_TRAIN_GATE_FAIL_MULTIPLIER``×
train budget below d5 (gating drills only — ``gatesProgress=false`` never multiplies; d5 gate
fails do not double). Gating drills below the pack max (not waived) get the same 2× with no
current-level fail — no claim-unlock door, so stuck openings catch up while the pack is ahead.
``SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER``× requires a current-level fail plus either being at
the pack **min** (bottleneck), still below claim-unlock difficulty while boards are not yet
d4+, or ``d5_demotes`` ≥ ``SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD`` after every gating board is
d4+ (demote grinding must not outspend unfinished openings).
After ``GATE_PROGRESS_WAIVE_FAILURES`` imperfect after-evals at the current level,
a gating **claim** drill stops blocking progress (still trains); board drills never waive.
A perfect after-eval at that level clears **current-level** fails and restores gating
(historical levels kept).
Proxy drills (catalog ``difficultyProxy``) train but skip scenario-specific after-eval;
difficulty tracks the proxy target and ``gatesProgress`` is always false. Other drills with
``gatesProgress=false`` still eval and climb d0→d5 on perfect eval, but do not affect claim
unlock or phase/full-game gates — used when we want training exposure but do not yet trust
the oracle (right moves unknown) or placement (not always solvable). New catalog names missing
from the JSON start at difficulty 0; stale JSON keys for removed drills are ignored (restart
training if that matters).
Coach-window end / US|THEM grading: Kotlin. Scenario budgets: DEFAULT_SCENARIO_TRAIN_* below
(learning below MAX; healthy d5 skip; soft-d5 maintenance); full-game defaults in
``scripts/train_self_play.py``.
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any

from gridiron_ai.scenarios import require_catalog

# Matches backend TrainingScenarioController / TrainingScenarioPlacer.
MAX_DIFFICULTY = 5
# Claims stay out until every board drill is ≥ this level (scenario_a gate). Why: TRAINING_ROADMAP
# “Why board before claims” — shared heads; establish core openings before claim dialects.
CLAIM_UNLOCK_BOARD_DIFFICULTY = 4
# No heuristic/lookahead full-game train until every catalog drill is at MAX (scenario_b gate).
# Alias kept so callers/docs can say "full-game unlock == all mastered".
FULL_GAME_UNLOCK_DIFFICULTY = MAX_DIFFICULTY
# Imperfect after-evals at the current level before a gating claim stops blocking progress.
GATE_PROGRESS_WAIVE_FAILURES = 20
# Scenario after-eval batch size (train → after-eval).
SCENARIO_EVAL_GAMES = 10
# Wins needed to pass / bump (9 or 10 of SCENARIO_EVAL_GAMES).
SCENARIO_EVAL_PASS_MIN_WINS = 9
# At d5: demote when wins are at or below this (7 or fewer of SCENARIO_EVAL_GAMES).
DEMOTE_FROM_MAX_MAX_WINS = 7
# Extra train when the drill has imperfect after-evals at its current difficulty.
SCENARIO_TRAIN_GATE_FAIL_MULTIPLIER = 2
# Current-level fail at pack min / below-d4 opening / high demotes after board d4+.
SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER = 4
# d5_demotes at or above this → 4× with a current-level fail only once boards are d4+.
SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD = 5
_SUFFIX = ".scenario_difficulty.json"
_GATE_FAILURES_SUFFIX = ".scenario_gate_failures.json"
_DRILL_STATS_SUFFIX = ".scenario_drill_stats.json"

# Per-scenario PPO timesteps each train cycle (not pooled — each scenario gets its own budget).
# Sized in whole rollouts at locked defaults (n_envs=30 × n_steps=128 = 3840).
_SCENARIO_ROLLOUT_TIMESTEPS = 3_840
DEFAULT_SCENARIO_TRAIN_LEARNING = _SCENARIO_ROLLOUT_TIMESTEPS  # level < MAX: one PPO update
# Healthy d5: no train (0). Soft-d5 uses DEFAULT_SCENARIO_TRAIN_MAINTENANCE instead.
DEFAULT_SCENARIO_TRAIN_MASTERED = 0
# Soft d5 (imperfect after-eval at MAX): one rollout; healthy d5 stay eval-only.
DEFAULT_SCENARIO_TRAIN_MAINTENANCE = _SCENARIO_ROLLOUT_TIMESTEPS


def path_for_stem(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final`` → ``…/maskable_ppo_selfplay_final.scenario_difficulty.json``."""
    return Path(str(save_stem) + _SUFFIX)


def path_for_gate_failures(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final.scenario_gate_failures.json`` beside difficulty JSON."""
    return Path(str(save_stem) + _GATE_FAILURES_SUFFIX)


def path_for_drill_stats(save_stem: Path) -> Path:
    """``…/maskable_ppo_selfplay_final.scenario_drill_stats.json`` beside difficulty JSON."""
    return Path(str(save_stem) + _DRILL_STATS_SUFFIX)


def load_levels(path: Path) -> dict[str, int]:
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    levels: dict[str, int] = {}
    for key, value in raw.items():
        try:
            levels[str(key)] = _clamp(int(value))
        except (TypeError, ValueError):
            continue
    return levels


def load_gate_failures(path: Path) -> dict[str, dict[str, int]]:
    """Scenario curriculum key → difficulty level (str) → imperfect after-eval count."""
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    failures: dict[str, dict[str, int]] = {}
    for scenario, by_level in raw.items():
        if not isinstance(by_level, dict):
            continue
        counts: dict[str, int] = {}
        for level_key, count in by_level.items():
            try:
                counts[str(_clamp(int(level_key)))] = max(0, int(count))
            except (TypeError, ValueError):
                continue
        if counts:
            failures[str(scenario)] = counts
    return failures


def save_gate_failures(path: Path, failures: dict[str, dict[str, int]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        scenario: {str(_clamp(int(level))): max(0, int(count)) for level, count in sorted(by_level.items())}
        for scenario, by_level in sorted(failures.items())
        if by_level
    }
    text = json.dumps(payload, indent=2) + "\n"
    fd, tmp_name = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as tmp:
            tmp.write(text)
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def gate_failure_count(
    failures: dict[str, dict[str, int]],
    scenario: str,
    *,
    difficulty: int | None = None,
) -> int:
    """Failures at one level, or summed across all levels when ``difficulty`` is omitted."""
    by_level = failures.get(curriculum_key(scenario), {})
    if difficulty is not None:
        return int(by_level.get(str(_clamp(difficulty)), 0))
    return sum(int(count) for count in by_level.values())


def load_drill_stats(path: Path) -> dict[str, dict[str, int]]:
    """Scenario curriculum key → ``cycles`` / ``d5_demotes`` counters."""
    if not path.is_file():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return {}
    stats: dict[str, dict[str, int]] = {}
    for scenario, counters in raw.items():
        if not isinstance(counters, dict):
            continue
        cycles = max(0, int(counters.get("cycles", 0) or 0))
        demotes = max(0, int(counters.get("d5_demotes", 0) or 0))
        if cycles or demotes:
            stats[str(scenario)] = {"cycles": cycles, "d5_demotes": demotes}
    return stats


def save_drill_stats(path: Path, drill_stats: dict[str, dict[str, int]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        scenario: {
            "cycles": max(0, int(counters.get("cycles", 0))),
            "d5_demotes": max(0, int(counters.get("d5_demotes", 0))),
        }
        for scenario, counters in sorted(drill_stats.items())
        if int(counters.get("cycles", 0) or 0) or int(counters.get("d5_demotes", 0) or 0)
    }
    text = json.dumps(payload, indent=2) + "\n"
    fd, tmp_name = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as tmp:
            tmp.write(text)
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def merge_drill_stats(
    *parts: dict[str, dict[str, int]],
) -> dict[str, dict[str, int]]:
    """Sum ``cycles`` / ``d5_demotes`` across phase-local drill-stats files."""
    merged: dict[str, dict[str, int]] = {}
    for part in parts:
        for scenario, counters in part.items():
            bucket = merged.setdefault(scenario, {"cycles": 0, "d5_demotes": 0})
            bucket["cycles"] += max(0, int(counters.get("cycles", 0) or 0))
            bucket["d5_demotes"] += max(0, int(counters.get("d5_demotes", 0) or 0))
    return merged


def drill_stat_count(
    drill_stats: dict[str, dict[str, int]],
    scenario: str,
    field: str,
) -> int:
    return int(drill_stats.get(curriculum_key(scenario), {}).get(field, 0) or 0)


def _bump_drill_stat(
    drill_stats: dict[str, dict[str, int]],
    scenario: str,
    field: str,
    *,
    by: int = 1,
) -> dict[str, dict[str, int]]:
    key = curriculum_key(scenario)
    updated = {name: dict(counters) for name, counters in drill_stats.items()}
    counters = updated.setdefault(key, {"cycles": 0, "d5_demotes": 0})
    counters[field] = max(0, int(counters.get(field, 0) or 0) + by)
    return updated


def save_levels(path: Path, levels: dict[str, int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # Drop proxy sources — difficulty is read from the proxy target.
    proxies = require_catalog().proxy_sources()
    payload = {
        name: _clamp(level)
        for name, level in sorted(levels.items())
        if name not in proxies
    }
    text = json.dumps(payload, indent=2) + "\n"
    # Atomic replace so a crash mid-write does not corrupt the file.
    fd, tmp_name = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as tmp:
            tmp.write(text)
        os.replace(tmp_name, path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def curriculum_key(scenario: str) -> str:
    """Scenario whose stored level is used for ``scenario`` (bounce → loose-ball run)."""
    return require_catalog().curriculum_key(scenario)


def get_difficulty(levels: dict[str, int], scenario: str) -> int:
    return _clamp(int(levels.get(curriculum_key(scenario), 0)))


def board_scenarios_in_catalog(
    scenario_names: list[str],
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> list[str]:
    """Gating board drills present in ``scenario_names`` (claims / waived / non-gating excluded)."""
    failures = gate_failures if gate_failures is not None else {}
    catalog = require_catalog()
    return [
        name
        for name in scenario_names
        if catalog.is_board(name) and effective_gates_progress(name, levels, failures)
    ]


def _gating_scenario_names(
    scenario_names: list[str],
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> list[str]:
    failures = gate_failures if gate_failures is not None else {}
    return [
        name
        for name in scenario_names
        if effective_gates_progress(name, levels, failures)
    ]


def is_progress_waived(
    gate_failures: dict[str, dict[str, int]],
    scenario: str,
    levels: dict[str, int],
) -> bool:
    """True when a gating **claim** has hit the failure threshold at its current level.

    Board drills never waive — a stuck opening must keep blocking claim unlock / phase gates.
    ``gatesProgress=false`` drills are already non-gating (optional), so they never waive either.
    """
    catalog = require_catalog()
    if not catalog.gates_progress(scenario) or not catalog.is_claim(scenario):
        return False
    difficulty = get_difficulty(levels, scenario)
    return gate_failure_count(gate_failures, scenario, difficulty=difficulty) >= GATE_PROGRESS_WAIVE_FAILURES


def effective_gates_progress(
    scenario: str,
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> bool:
    """Whether ``scenario`` counts toward lead pack, claim unlock, and phase/full-game gates."""
    catalog = require_catalog()
    if not catalog.gates_progress(scenario):
        return False
    failures = gate_failures if gate_failures is not None else {}
    if is_progress_waived(failures, scenario, levels):
        return False
    return True


def clear_gate_failures_at_difficulty(
    gate_failures: dict[str, dict[str, int]],
    scenario: str,
    difficulty: int,
) -> dict[str, dict[str, int]]:
    """Drop fails for ``scenario`` at one difficulty (waive recover); keep other levels."""
    key = curriculum_key(scenario)
    by_level = gate_failures.get(key)
    if not by_level:
        return gate_failures
    level_key = str(_clamp(difficulty))
    if level_key not in by_level:
        return gate_failures
    updated = {name: dict(levels) for name, levels in gate_failures.items()}
    updated_levels = dict(updated[key])
    updated_levels.pop(level_key, None)
    if updated_levels:
        updated[key] = updated_levels
    else:
        updated.pop(key, None)
    return updated


def claims_unlocked(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> bool:
    """True when claim drills may enter the cycle.

    - No claims in the catalog → unlocked (board-only / pin).
    - Claims present → every **board** drill in the catalog must be at
      CLAIM_UNLOCK_BOARD_DIFFICULTY+.
    """
    catalog = require_catalog()
    claims = [name for name in scenario_names if catalog.is_claim(name) and catalog.gates_progress(name)]
    if not claims:
        return True
    board = board_scenarios_in_catalog(scenario_names, levels, gate_failures)
    if not board:
        return True
    return all_scenarios_at_least(levels, board, CLAIM_UNLOCK_BOARD_DIFFICULTY, gate_failures)


def scenarios_for_eval(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> list[str]:
    """Cycle drills to evaluate. Proxy drills (bounce) are replaced by their target (run)."""
    eval_names: list[str] = []
    for name in scenarios_for_cycle(levels, scenario_names, gate_failures):
        key = curriculum_key(name)
        if key not in eval_names:
            eval_names.append(key)
    return eval_names


def scenario_max_coach_turns(difficulty: int) -> int:
    """Mirror of Kotlin ``scenarioCoachTurnWindow`` (server owns episode end).

    Difficulties 0–3: trainer turn only. Difficulties 4–5: six coach turns.
    Kept here for offline docs / curriculum helpers — the env no longer truncates.
    """
    return 1 if difficulty <= 3 else 6


def _gating_active_names(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> list[str]:
    failures = gate_failures if gate_failures is not None else {}
    return [
        name
        for name in active_scenario_catalog(levels, scenario_names, gate_failures)
        if effective_gates_progress(name, levels, failures)
    ]


def gating_pack_max_difficulty(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> int | None:
    """Highest stored level among effective gating drills in the active catalog, or None."""
    gating = _gating_active_names(levels, scenario_names, gate_failures)
    if not gating:
        return None
    return max(get_difficulty(levels, name) for name in gating)


def gating_pack_min_difficulty(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> int | None:
    """Lowest stored level among effective gating drills in the active catalog, or None."""
    gating = _gating_active_names(levels, scenario_names, gate_failures)
    if not gating:
        return None
    return min(get_difficulty(levels, name) for name in gating)


def gating_board_at_claim_unlock(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> bool:
    """True when every gating board drill is at ``CLAIM_UNLOCK_BOARD_DIFFICULTY`` or higher."""
    board = board_scenarios_in_catalog(scenario_names, levels, gate_failures)
    if not board:
        return True
    return all_scenarios_at_least(levels, board, CLAIM_UNLOCK_BOARD_DIFFICULTY, gate_failures)


def scenario_train_multiplier(
    scenario: str,
    levels: dict[str, int],
    scenario_names: list[str],
    *,
    difficulty: int,
    gate_failures: dict[str, dict[str, int]],
    drill_stats: dict[str, dict[str, int]] | None = None,
) -> int:
    """Highest applicable train budget multiplier for one gating drill (1 = no boost)."""
    catalog = require_catalog()
    if not catalog.gates_progress(scenario):
        return 1
    multiplier = 1
    has_current_fail = difficulty < MAX_DIFFICULTY and gate_failure_count(
        gate_failures, scenario, difficulty=difficulty
    ) > 0
    if has_current_fail:
        multiplier = max(multiplier, SCENARIO_TRAIN_GATE_FAIL_MULTIPLIER)
    if is_progress_waived(gate_failures, scenario, levels):
        return multiplier
    pack_max = gating_pack_max_difficulty(levels, scenario_names, gate_failures)
    pack_min = gating_pack_min_difficulty(levels, scenario_names, gate_failures)
    behind_pack = pack_max is not None and difficulty < pack_max
    # Pack min only counts as a bottleneck when someone is ahead (else every fail would 4×).
    at_bottleneck = (
        pack_min is not None
        and pack_max is not None
        and pack_min < pack_max
        and difficulty == pack_min
    )
    boards_ready = gating_board_at_claim_unlock(levels, scenario_names, gate_failures)
    # While openings still block claim unlock, every below-d4 failing drill gets 4× — not only the min.
    opening_straggler = (
        not boards_ready and difficulty < CLAIM_UNLOCK_BOARD_DIFFICULTY
    )
    if behind_pack and not has_current_fail:
        multiplier = max(multiplier, SCENARIO_TRAIN_GATE_FAIL_MULTIPLIER)
    stats = drill_stats if drill_stats is not None else {}
    high_demotes = (
        drill_stat_count(stats, scenario, "d5_demotes") >= SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD
        and boards_ready
    )
    if has_current_fail and (at_bottleneck or opening_straggler or high_demotes):
        multiplier = max(multiplier, SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER)
    return multiplier


def scenario_needs_maintenance(
    scenario: str,
    *,
    difficulty: int,
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> bool:
    """True for soft d5: imperfect after-evals at MAX while the drill is still labeled d5."""
    if _clamp(difficulty) < MAX_DIFFICULTY:
        return False
    failures = gate_failures if gate_failures is not None else {}
    return gate_failure_count(failures, scenario, difficulty=MAX_DIFFICULTY) > 0


def train_timesteps_for_difficulty(
    difficulty: int,
    *,
    learning: int = DEFAULT_SCENARIO_TRAIN_LEARNING,
    mastered: int = DEFAULT_SCENARIO_TRAIN_MASTERED,
    maintenance: int = DEFAULT_SCENARIO_TRAIN_MAINTENANCE,
    all_mastered: bool = False,
    game_phase: bool = False,
) -> int:
    """Timesteps for one scenario this cycle (budget amount before soft-d5 filtering).

    Below MAX: learning budget. At MAX with mastered>0: that rehearse budget; otherwise
    maintenance amount (``train_timesteps_for_scenario`` keeps healthy d5 at 0).
    """
    at_max = _clamp(difficulty) >= MAX_DIFFICULTY
    if all_mastered or (game_phase and at_max):
        return max(0, maintenance)
    if learning <= 0 and mastered <= 0:
        return 0
    if at_max:
        if mastered <= 0:
            return max(0, maintenance)
        return max(0, mastered)
    return max(0, learning)


def train_timesteps_for_scenario(
    scenario: str,
    levels: dict[str, int],
    scenario_names: list[str],
    *,
    learning: int = DEFAULT_SCENARIO_TRAIN_LEARNING,
    mastered: int = DEFAULT_SCENARIO_TRAIN_MASTERED,
    maintenance: int = DEFAULT_SCENARIO_TRAIN_MAINTENANCE,
    all_mastered: bool = False,
    game_phase: bool = False,
    gate_failures: dict[str, dict[str, int]] | None = None,
    drill_stats: dict[str, dict[str, int]] | None = None,
) -> int:
    """Per-scenario train budget; up to 4× while climbing; soft d5 + game-phase stragglers at 1×."""
    failures = gate_failures if gate_failures is not None else {}
    difficulty = get_difficulty(levels, scenario)
    base = train_timesteps_for_difficulty(
        difficulty,
        learning=learning,
        mastered=mastered,
        maintenance=maintenance,
        all_mastered=all_mastered,
        game_phase=game_phase,
    )
    if base <= 0:
        return 0
    at_max = _clamp(difficulty) >= MAX_DIFFICULTY
    # Healthy d5 stay eval-only; soft-d5 (gate fails at MAX) get maintenance — climbing too.
    if all_mastered or (game_phase and at_max) or (at_max and mastered <= 0):
        if not scenario_needs_maintenance(
            scenario, difficulty=difficulty, gate_failures=failures
        ):
            return 0
        return base
    if game_phase:
        return base
    multiplier = scenario_train_multiplier(
        scenario,
        levels,
        scenario_names,
        difficulty=difficulty,
        gate_failures=failures,
        drill_stats=drill_stats,
    )
    if multiplier <= 1:
        return base
    return base * multiplier


def gating_scenarios_mastered(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> bool:
    """True when every effective gating drill is at MAX_DIFFICULTY (empty set → mastered).

    Used for scenario_b / full-game unlock — non-gating and waived drills are ignored.
    """
    gating = _gating_scenario_names(scenario_names, levels, gate_failures)
    if not gating:
        return True
    return all(get_difficulty(levels, name) >= MAX_DIFFICULTY for name in gating)


def all_scenarios_mastered(levels: dict[str, int], scenario_names: list[str]) -> bool:
    """True when every catalog drill is at MAX_DIFFICULTY (empty catalog → mastered).

    Includes non-gating drills — used to turn off scenario train in game phase.
    """
    if not scenario_names:
        return True
    return all(get_difficulty(levels, name) >= MAX_DIFFICULTY for name in scenario_names)


def gating_scenarios_at_least(
    levels: dict[str, int],
    scenario_names: list[str],
    minimum: int,
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> bool:
    """True when every effective gating drill is at ``minimum`` or higher (empty set → True)."""
    gating = _gating_scenario_names(scenario_names, levels, gate_failures)
    if not gating:
        return True
    floor = _clamp(minimum)
    return all(get_difficulty(levels, name) >= floor for name in gating)


def all_scenarios_at_least(
    levels: dict[str, int],
    scenario_names: list[str],
    minimum: int,
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> bool:
    """Alias for [gating_scenarios_at_least] — phase gates ignore non-gating / waived drills."""
    return gating_scenarios_at_least(levels, scenario_names, minimum, gate_failures)


def scenarios_for_cycle(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> list[str]:
    """Drills to train/eval this cycle (after claim deferral filter).

    Every active drill runs — nothing is skipped for being ahead of the pack.
    Order: ascending curriculum difficulty (d0 first), catalog order within a level.
    """
    active = active_scenario_catalog(levels, scenario_names, gate_failures)
    return sorted(active, key=lambda name: (get_difficulty(levels, name), scenario_names.index(name)))


def active_scenario_catalog(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> list[str]:
    """Catalog for this cycle: board drills always; all claim drills after board unlock.

    Optional board drills (``gatesProgress=false``) train from d0 alongside gating board drills.
    Every ``family=CLAIM`` drill waits for [claims_unlocked] — optional claims are not special-cased.
    """
    catalog = require_catalog()
    claims_ok = claims_unlocked(levels, scenario_names, gate_failures)
    return [
        name
        for name in scenario_names
        if catalog.is_board(name)
        or (catalog.is_claim(name) and claims_ok)
    ]


def full_game_train_timesteps(
    base: int,
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
    *,
    game_phase_unlocked: bool = False,
) -> int:
    """Full-game train budget: zero until scenario_b is unlocked (live gate or persisted).

    ``scenario_names`` should be the **full** server catalog (board + claim), not the
    board-only filtered list — otherwise games could unlock before scenario_b completes.
    """
    if base <= 0:
        return 0
    if game_phase_unlocked:
        return base
    if not claims_unlocked(levels, scenario_names, gate_failures):
        return 0
    if not gating_scenarios_mastered(levels, scenario_names, gate_failures):
        return 0
    return base


def _eval_win_count(stats: dict[str, Any], *, expected_games: int) -> int | None:
    """US wins in a full-length after-eval batch, or ``None`` when the batch is short."""
    episodes = int(stats.get("episodes") or 0)
    if episodes < expected_games:
        return None
    return int(stats.get("wins") or 0)


def _eval_passes(stats: dict[str, Any], *, expected_games: int) -> bool:
    """True on a full batch with at least ``SCENARIO_EVAL_PASS_MIN_WINS`` US wins."""
    wins = _eval_win_count(stats, expected_games=expected_games)
    if wins is None:
        return False
    return wins >= SCENARIO_EVAL_PASS_MIN_WINS


def _eval_is_perfect(stats: dict[str, Any], *, expected_games: int) -> bool:
    """Alias for [_eval_passes] — kept for callers that mean “passed the eval batch”."""
    return _eval_passes(stats, expected_games=expected_games)


def _eval_non_win_count(stats: dict[str, Any], *, expected_games: int) -> int:
    """Losses + draws in a full-length after-eval batch (0 when the batch is short)."""
    wins = _eval_win_count(stats, expected_games=expected_games)
    if wins is None:
        return 0
    episodes = int(stats.get("episodes") or 0)
    return max(0, episodes - wins)


def maybe_bump_after_perfect_eval(
    levels: dict[str, int],
    scenario: str,
    stats: dict[str, Any],
    *,
    expected_games: int,
) -> tuple[dict[str, int], bool]:
    """If eval passed (≥ ``SCENARIO_EVAL_PASS_MIN_WINS`` / ``expected_games``), bump by 1."""
    current = get_difficulty(levels, scenario)
    if current >= MAX_DIFFICULTY:
        return levels, False
    if not _eval_passes(stats, expected_games=expected_games):
        return levels, False
    updated = dict(levels)
    updated[curriculum_key(scenario)] = current + 1
    return updated, True


def maybe_demote_after_failed_max_eval(
    levels: dict[str, int],
    scenario: str,
    stats: dict[str, Any],
    *,
    expected_games: int,
    max_wins: int = DEMOTE_FROM_MAX_MAX_WINS,
) -> tuple[dict[str, int], bool]:
    """Drop one level when a d5 drill has ``max_wins`` or fewer US wins in a full pass."""
    current = get_difficulty(levels, scenario)
    if current < MAX_DIFFICULTY:
        return levels, False
    wins = _eval_win_count(stats, expected_games=expected_games)
    if wins is None or wins > max_wins:
        return levels, False
    updated = dict(levels)
    updated[curriculum_key(scenario)] = current - 1
    return updated, True


def record_gate_failure(
    failures: dict[str, dict[str, int]],
    scenario: str,
    difficulty: int,
) -> dict[str, dict[str, int]]:
    """Increment imperfect after-eval count for ``scenario`` at ``difficulty``."""
    key = curriculum_key(scenario)
    updated = {name: dict(by_level) for name, by_level in failures.items()}
    by_level = updated.setdefault(key, {})
    level_key = str(_clamp(difficulty))
    by_level[level_key] = by_level.get(level_key, 0) + 1
    return updated


def record_train_cycles(
    drill_stats: dict[str, dict[str, int]],
    scenario: str,
    *,
    units: int = 1,
) -> dict[str, dict[str, int]]:
    """Increment train-cycle units for ``scenario`` (gate-fail 2× budget → ``units=2``)."""
    return _bump_drill_stat(drill_stats, scenario, "cycles", by=max(1, int(units)))


def apply_after_eval_curriculum(
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]],
    scenario: str,
    stats: dict[str, Any],
    *,
    expected_games: int,
    drill_stats: dict[str, dict[str, int]] | None = None,
) -> tuple[dict[str, int], dict[str, dict[str, int]], dict[str, dict[str, int]], str | None]:
    """After-eval curriculum: bump, demote false max, or count a gate failure.

    Returns ``(levels, gate_failures, drill_stats, action)`` where ``action`` is ``"bump"``,
    ``"demote"``, ``"fail"``, ``"waive"``, ``"recover"``, or ``None`` when the eval batch
    was too short. Demotes from d5 increment ``d5_demotes``. Train ``cycles`` are recorded
    separately when the drill actually trains (see ``record_train_cycles``).
    """
    counters = {} if drill_stats is None else drill_stats
    if int(stats.get("episodes") or 0) < expected_games:
        return levels, gate_failures, counters, None
    if _eval_passes(stats, expected_games=expected_games):
        was_waived = is_progress_waived(gate_failures, scenario, levels)
        difficulty = get_difficulty(levels, scenario)
        levels, bumped = maybe_bump_after_perfect_eval(
            levels, scenario, stats, expected_games=expected_games
        )
        if bumped:
            # Historical per-level fails stay (all-time); new difficulty starts at 0.
            return levels, gate_failures, counters, "bump"
        if was_waived:
            gate_failures = clear_gate_failures_at_difficulty(
                gate_failures, scenario, difficulty
            )
            return levels, gate_failures, counters, "recover"
        return levels, gate_failures, counters, None
    difficulty = get_difficulty(levels, scenario)
    prior_waived = is_progress_waived(gate_failures, scenario, levels)
    gate_failures = record_gate_failure(gate_failures, scenario, difficulty)
    newly_waived = not prior_waived and is_progress_waived(gate_failures, scenario, levels)
    levels, demoted = maybe_demote_after_failed_max_eval(
        levels, scenario, stats, expected_games=expected_games
    )
    if demoted:
        counters = _bump_drill_stat(counters, scenario, "d5_demotes")
        return levels, gate_failures, counters, "demote"
    if newly_waived:
        return levels, gate_failures, counters, "waive"
    return levels, gate_failures, counters, "fail"


def _clamp(level: int) -> int:
    return max(0, min(MAX_DIFFICULTY, level))
