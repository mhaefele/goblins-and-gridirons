"""Per-model scenario difficulty (0–5) and cycle filters.

Owner: curriculum *policy* next to a checkpoint stem — levels JSON, unlock/lead gates,
per-drill train budgets, which drills run this cycle.
Reads: process/cached ``ScenarioCatalog`` (family + difficultyProxy); levels from disk.
Writes via ``gridiron_ai.persistence`` (one module per sidecar). Does **not** place boards
(Kotlin) or own phase routing (``training_phases``). See ``ai/OWNERSHIP.md`` / ``TRAINING_ROADMAP.md``.

Sidecars beside the stem: ``.scenario_difficulty.json`` (name → level; bump after perfect
after-eval; demote when a drill labeled d5 fails after-eval with ``DEMOTE_FROM_MAX_MAX_WINS``
or fewer wins — default 14/20; 15–17/20 is a gate fail only); ``.scenario_gate_failures.json``
(imperfect after-evals per difficulty level; all-time = sum; current level cleared on waive
recover); ``.scenario_drill_stats.json`` (per-phase ``cycles`` / ``d5_demotes``; summary uses
``training_phases.load_drill_stats_cumulative``); ``.scenario_last_eval.json`` (up to 4 slim
snapshots per drill@d — ``history`` plus ``current``/``prior`` mirrors — for deltas / rank trails).
Phases (scenario_a board→d4 / scenario_b all→d5 / profiles games): ``training_phases``.
While ``LOOSE_BALL_RUN`` is below ``LOOSE_BALL_FOCUS_UNTIL`` (d4), the cycle keeps only that
drill (and bounce once past ``LOOSE_BALL_BOUNCE_PAUSE_THROUGH``) — other openings pause.
Bounce itself stays out through LBR d2 (contested exposure poisons free-pickup learning).
8× train units use ``SCENARIO_EVAL_GAMES_HEAVY`` (100) after-evals with scaled pass/demote.
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
(learning below MAX; healthy d5 skip train; soft-d5 maintenance). Climbing
(scenario_a / scenario_b): healthy d5 drift-eval every
``TrainMix.healthy_d5_eval_every`` (``gridiron_ai/train_mix.py``); game phase
evals all drills each cycle. Full-game CLI defaults live in ``train_mix`` too.
"""

from __future__ import annotations

from typing import Any, Callable

from gridiron_ai.api.harness_wire import ScenarioName
from gridiron_ai.persistence.scenario_difficulty import (  # noqa: F401 — re-export
    load_levels,
    path_for_stem,
    save_levels,
)
from gridiron_ai.persistence.scenario_drill_stats import (  # noqa: F401 — re-export
    load_drill_stats,
    path_for_drill_stats,
    save_drill_stats,
)
from gridiron_ai.persistence.scenario_gate_failures import (  # noqa: F401 — re-export
    load_gate_failures,
    path_for_gate_failures,
    save_gate_failures,
)
from gridiron_ai.persistence.scenario_last_eval import (  # noqa: F401 — re-export
    LAST_EVAL_HISTORY_MAX,
    LAST_EVAL_METRIC_KEYS,
    LAST_EVAL_OWN_SOURCES,
    LAST_EVAL_PEER_HISTORY_MAX,
    LAST_EVAL_SOURCE_AFTER,
    LAST_EVAL_SOURCE_BEFORE,
    LAST_EVAL_SOURCE_PEER,
    LastEvalEntryDict,
    LastEvalMetricsDict,
    LastEvalSource,
    LastEvalStore,
    extract_last_eval_metrics,
    is_own_train_snap,
    load_last_eval,
    normalize_last_eval_source,
    path_for_last_eval,
    save_last_eval,
    trim_last_eval_history,
)
from gridiron_ai.api.scenarios import require_catalog

# Matches backend TrainingScenarioController / TrainingScenarioPlacer.
MAX_DIFFICULTY = 5
# Claims stay out until every board drill is ≥ this level (scenario_a gate). Why: TRAINING_ROADMAP
# “Why board before claims” — shared heads; establish core openings before claim dialects.
CLAIM_UNLOCK_BOARD_DIFFICULTY = 4
# While LOOSE_BALL_RUN is below this, pause every other drill in the cycle (bounce may stay —
# see LOOSE_BALL_BOUNCE_PAUSE_THROUGH). Harness policy — not catalog membership.
LOOSE_BALL_FOCUS_UNTIL = CLAIM_UNLOCK_BOARD_DIFFICULTY
LOOSE_BALL_FOCUS_TARGET = "LOOSE_BALL_RUN"
# Inclusive: pause LOOSE_BALL_RUN difficultyProxy drills (bounce) while LBR is at this level or below.
LOOSE_BALL_BOUNCE_PAUSE_THROUGH = 2
# No heuristic/lookahead full-game train until every catalog drill is at MAX (scenario_b gate).
# Alias kept so callers/docs can say "full-game unlock == all mastered".
FULL_GAME_UNLOCK_DIFFICULTY = MAX_DIFFICULTY
# Imperfect after-evals at the current level before a gating claim stops blocking progress.
GATE_PROGRESS_WAIVE_FAILURES = 40
# Scenario after-eval batch size (train → after-eval).
SCENARIO_EVAL_GAMES = 20
# Bottleneck (8×) train drills get a longer after-eval (same pass/demote *rates* as the base batch).
SCENARIO_EVAL_GAMES_HEAVY = 100
# Wins needed to pass / bump (18–20 of SCENARIO_EVAL_GAMES); scaled for heavy batches.
SCENARIO_EVAL_PASS_MIN_WINS = 18
# At d5: demote when wins are at or below this (14 or fewer of SCENARIO_EVAL_GAMES).
DEMOTE_FROM_MAX_MAX_WINS = 14
# Extra train when the drill has imperfect after-evals at its current difficulty.
SCENARIO_TRAIN_GATE_FAIL_MULTIPLIER = 2
# Current-level fail at pack min / below-d4 opening / high demotes after board d4+.
SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER = 8
# d5_demotes at or above this → 8× with a current-level fail only once boards are d4+.
SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD = 5
# Stuck-watch / summarize STUCK! thresholds (shared — train peer-probes use the same predicate).
SCENARIO_GATE_FAIL_SHOUT = 5
SCENARIO_CYCLES_BEFORE_DRILL_STUCK = 8
SCENARIO_CYCLES_BEFORE_OPENING_STUCK = 5
SCENARIO_OPENING_RANK_STUCK = 8.0


def scenario_eval_games(train_units: int = 1) -> int:
    """After-eval batch size: 100 games for bottleneck (8×) drills, else ``SCENARIO_EVAL_GAMES``."""
    if train_units >= SCENARIO_TRAIN_BELOW_PACK_MAX_MULTIPLIER:
        return SCENARIO_EVAL_GAMES_HEAVY
    return SCENARIO_EVAL_GAMES


def eval_pass_min_wins(expected_games: int) -> int:
    """Pass threshold scaled from ``SCENARIO_EVAL_PASS_MIN_WINS`` / ``SCENARIO_EVAL_GAMES``."""
    return max(
        1,
        (SCENARIO_EVAL_PASS_MIN_WINS * expected_games + SCENARIO_EVAL_GAMES - 1)
        // SCENARIO_EVAL_GAMES,
    )


def eval_demote_max_wins(expected_games: int) -> int:
    """Demote ceiling scaled from ``DEMOTE_FROM_MAX_MAX_WINS`` / ``SCENARIO_EVAL_GAMES``."""
    return (DEMOTE_FROM_MAX_MAX_WINS * expected_games) // SCENARIO_EVAL_GAMES

# Per-scenario PPO timesteps each train cycle (not pooled — each scenario gets its own budget).
# Sized in whole rollouts at locked defaults (n_envs=30 × n_steps=128 = 3840).
_SCENARIO_ROLLOUT_TIMESTEPS = 3_840
DEFAULT_SCENARIO_TRAIN_LEARNING = _SCENARIO_ROLLOUT_TIMESTEPS  # level < MAX: one PPO update
# Healthy d5: no train (0). Soft-d5 uses DEFAULT_SCENARIO_TRAIN_MAINTENANCE instead.
DEFAULT_SCENARIO_TRAIN_MASTERED = 0
# Soft d5 (imperfect after-eval at MAX): one rollout; healthy d5 skip train (drift-eval
# cadence is owned by train_mix.HEALTHY_D5_EVAL_EVERY_CYCLES on scenario_a/b).
DEFAULT_SCENARIO_TRAIN_MAINTENANCE = _SCENARIO_ROLLOUT_TIMESTEPS


def last_eval_entry(
    last_eval: LastEvalStore,
    scenario: ScenarioName,
    difficulty: int,
) -> LastEvalEntryDict | None:
    """History entry for ``scenario`` at ``difficulty``, or ``None`` when missing."""
    by_level = last_eval.get(curriculum_key(scenario), {})
    return by_level.get(str(_clamp(difficulty)))


def last_eval_history(
    last_eval: LastEvalStore,
    scenario: ScenarioName,
    difficulty: int,
    *,
    own_train_only: bool = False,
) -> list[LastEvalMetricsDict]:
    """Oldest→newest slim snapshots, or empty.

    ``own_train_only`` keeps before/after sandwich snaps (≤ ``LAST_EVAL_HISTORY_MAX``) and
    drops peer probes so trends ignore interference evals.
    """
    entry = last_eval_entry(last_eval, scenario, difficulty)
    if entry is None:
        return []
    history = entry.get("history")
    if isinstance(history, list) and history:
        snaps = [dict(snap) for snap in history]
    else:
        # Defensive: mirrors only (should not happen after parse/record).
        snaps = []
        prior = entry.get("prior")
        current = entry.get("current")
        if isinstance(prior, dict):
            snaps.append(dict(prior))
        if isinstance(current, dict):
            snaps.append(dict(current))
    if own_train_only:
        snaps = [snap for snap in snaps if is_own_train_snap(snap)]
        return snaps[-LAST_EVAL_HISTORY_MAX:]
    return trim_last_eval_history(snaps)


def last_eval_metrics(
    last_eval: LastEvalStore,
    scenario: ScenarioName,
    difficulty: int,
) -> LastEvalMetricsDict | None:
    """Slim **current** own-train metrics for ``scenario`` at ``difficulty``, or ``None``."""
    entry = last_eval_entry(last_eval, scenario, difficulty)
    if entry is None:
        return None
    current = entry.get("current")
    return current if isinstance(current, dict) else None


def last_eval_prior_metrics(
    last_eval: LastEvalStore,
    scenario: ScenarioName,
    difficulty: int,
) -> LastEvalMetricsDict | None:
    """Slim **prior** own-train metrics, or ``None`` when missing."""
    entry = last_eval_entry(last_eval, scenario, difficulty)
    if entry is None:
        return None
    prior = entry.get("prior")
    return prior if isinstance(prior, dict) else None


def last_eval_rank_history(
    last_eval: LastEvalStore,
    scenario: ScenarioName,
    difficulty: int,
) -> list[float]:
    """Opening expected-best ranks from own-train history (oldest→newest), skipping missing."""
    ranks: list[float] = []
    for snap in last_eval_history(last_eval, scenario, difficulty, own_train_only=True):
        rank = snap.get("opening_expected_rank_best_mean")
        if rank is not None:
            ranks.append(float(rank))
    return ranks


def last_eval_winrate_history(
    last_eval: LastEvalStore,
    scenario: ScenarioName,
    difficulty: int,
) -> list[float]:
    """Drill winrates from own-train history (oldest→newest), skipping missing."""
    rates: list[float] = []
    for snap in last_eval_history(last_eval, scenario, difficulty, own_train_only=True):
        rate = snap.get("winrate")
        if rate is not None:
            rates.append(float(rate))
    return rates


def last_eval_winrate_trail(
    last_eval: LastEvalStore,
    scenario: ScenarioName,
    difficulty: int,
) -> list[tuple[float, LastEvalSource]]:
    """Own-train winrates with ``before``/``after`` tags for ``61%(b)→38%(a)`` display."""
    trail: list[tuple[float, LastEvalSource]] = []
    for snap in last_eval_history(last_eval, scenario, difficulty, own_train_only=True):
        rate = snap.get("winrate")
        if rate is None:
            continue
        trail.append((float(rate), normalize_last_eval_source(snap.get("source"))))
    return trail


def record_last_eval(
    last_eval: LastEvalStore,
    scenario: ScenarioName,
    difficulty: int,
    stats: dict[str, Any],
    *,
    source: LastEvalSource = LAST_EVAL_SOURCE_AFTER,
    peer: ScenarioName | None = None,
) -> LastEvalStore:
    """Copy of ``last_eval`` with ``stats`` appended (tagged ``source`` / optional ``peer``).

    Own-train before/after update ``current``/``prior``. Peer probes append for interference
    notes only — they do not move the own-train mirrors or displace the trend window.
    """
    updated: LastEvalStore = {
        name: {
            level: {
                "history": [dict(snap) for snap in (entry.get("history") or [])],
                "current": dict(entry.get("current") or {}),
                "prior": dict(entry["prior"]) if entry.get("prior") is not None else None,
            }
            for level, entry in by_level.items()
        }
        for name, by_level in last_eval.items()
    }
    key = curriculum_key(scenario)
    by_level = dict(updated.get(key, {}))
    level_key = str(_clamp(difficulty))
    previous = by_level.get(level_key) or {}
    old_current = previous.get("current")
    new_slim = extract_last_eval_metrics(stats, source=source, peer=peer)
    old_history = [dict(snap) for snap in (previous.get("history") or [])]
    if not old_history:
        # Migrate legacy current/prior into history once.
        if previous.get("prior") is not None:
            old_history.append(dict(previous["prior"]))
        if isinstance(old_current, dict):
            old_history.append(dict(old_current))
    history = trim_last_eval_history(old_history + [new_slim])
    own = [snap for snap in history if is_own_train_snap(snap)]
    if is_own_train_snap(new_slim):
        current = new_slim
        prior = own[-2] if len(own) >= 2 else None
    else:
        current = dict(old_current) if isinstance(old_current, dict) else (
            dict(own[-1]) if own else dict(new_slim)
        )
        prior = (
            dict(previous["prior"])
            if previous.get("prior") is not None
            else (dict(own[-2]) if len(own) >= 2 else None)
        )
    by_level[level_key] = {
        "history": history,
        "current": current,
        "prior": prior,
    }
    updated[key] = by_level
    return updated


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


def scenario_stuck_watch_reasons(
    scenario: ScenarioName,
    *,
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]],
    drill_stats: dict[str, dict[str, int]],
    last_eval: LastEvalStore,
    latest_eval_stats: dict[str, Any] | None = None,
    latest_eval_difficulty: int | None = None,
) -> list[str]:
    """Machine reasons a drill is on the stuck watch list (same bars as summarize STUCK!).

    Empty → not watched. Proxy drills never watch (no trusted after-eval).
    ``latest_eval_stats`` is the newest on-disk eval JSON for drill-rate stuck (optional).
    """
    catalog = require_catalog()
    if scenario in catalog.proxy_sources():
        return []
    difficulty = get_difficulty(levels, scenario)
    reasons: list[str] = []
    cycles = drill_stat_count(drill_stats, scenario, "cycles")
    demotes = drill_stat_count(drill_stats, scenario, "d5_demotes")
    fails = gate_failure_count(gate_failures, scenario, difficulty=difficulty)

    if is_progress_waived(gate_failures, scenario, levels):
        reasons.append("progress_waived")
    elif fails >= SCENARIO_GATE_FAIL_SHOUT:
        reasons.append("gate_fails")

    if demotes >= SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD:
        reasons.append("d5_demotes")

    if (
        latest_eval_stats is not None
        and latest_eval_difficulty == difficulty
        and cycles >= SCENARIO_CYCLES_BEFORE_DRILL_STUCK
        and difficulty < MAX_DIFFICULTY
    ):
        wins = int(latest_eval_stats.get("wins") or 0)
        games = int(latest_eval_stats.get("episodes") or 0)
        if games > 0 and wins < eval_pass_min_wins(games):
            reasons.append("drill_rate")

    opening = last_eval_metrics(last_eval, scenario, difficulty)
    rank = None
    if opening is not None:
        raw_rank = opening.get("opening_expected_rank_best_mean")
        try:
            rank = float(raw_rank) if raw_rank is not None else None
        except (TypeError, ValueError):
            rank = None
    if (
        rank is not None
        and rank >= SCENARIO_OPENING_RANK_STUCK
        and cycles >= SCENARIO_CYCLES_BEFORE_OPENING_STUCK
        and difficulty < MAX_DIFFICULTY
    ):
        reasons.append("opening_rank")
    return reasons


def scenario_is_stuck_watch(
    scenario: ScenarioName,
    *,
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]],
    drill_stats: dict[str, dict[str, int]],
    last_eval: LastEvalStore,
    latest_eval_stats: dict[str, Any] | None = None,
    latest_eval_difficulty: int | None = None,
) -> bool:
    """True when [scenario_stuck_watch_reasons] is non-empty."""
    return bool(
        scenario_stuck_watch_reasons(
            scenario,
            levels=levels,
            gate_failures=gate_failures,
            drill_stats=drill_stats,
            last_eval=last_eval,
            latest_eval_stats=latest_eval_stats,
            latest_eval_difficulty=latest_eval_difficulty,
        )
    )


def stuck_watch_scenarios(
    scenario_names: list[str],
    *,
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]],
    drill_stats: dict[str, dict[str, int]],
    last_eval: LastEvalStore,
) -> list[tuple[ScenarioName, int]]:
    """``(scenario, difficulty)`` pairs on the stuck watch list (catalog order).

    Train uses this without on-disk eval JSON — gate_fails / opening_rank / demotes /
    waived are enough to catch LBR-style thrash; drill_rate stuck still needs summarize.
    """
    catalog = require_catalog()
    out: list[tuple[ScenarioName, int]] = []
    for name in scenario_names:
        if catalog.is_claim(name) and not claims_unlocked(levels, scenario_names, gate_failures):
            continue
        if scenario_is_stuck_watch(
            name,
            levels=levels,
            gate_failures=gate_failures,
            drill_stats=drill_stats,
            last_eval=last_eval,
        ):
            out.append((name, get_difficulty(levels, name)))
    return out


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


def curriculum_key(scenario: ScenarioName) -> ScenarioName:
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


def scenarios_for_healthy_d5_eval(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> list[str]:
    """Healthy d5 drills for climbing drift checks (at MAX, no soft-d5 gate fails).

    Soft-d5 already train → after-eval each cycle; proxies resolve via ``scenarios_for_eval``.
    """
    failures = gate_failures if gate_failures is not None else {}
    healthy: list[str] = []
    for name in scenarios_for_eval(levels, scenario_names, failures):
        difficulty = get_difficulty(levels, name)
        if difficulty < MAX_DIFFICULTY:
            continue
        if scenario_needs_maintenance(
            name, difficulty=difficulty, gate_failures=failures
        ):
            continue
        healthy.append(name)
    return healthy


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
    # Pack min only counts as a bottleneck when someone is ahead (else every fail would 8×).
    at_bottleneck = (
        pack_min is not None
        and pack_max is not None
        and pack_min < pack_max
        and difficulty == pack_min
    )
    boards_ready = gating_board_at_claim_unlock(levels, scenario_names, gate_failures)
    # While openings still block claim unlock, every below-d4 failing drill gets 8× — not only the min.
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
    """Per-scenario train budget; up to 8× while climbing; soft d5 + game-phase stragglers at 1×."""
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
    # Healthy d5 skip train (drift-eval on climbing cadence); soft-d5 get maintenance.
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


def scenario_train_budget_units(
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
    """Effective × of one PPO rollout next cycle (0 = skip).

    Same rules as ``train_timesteps_for_scenario`` (healthy d5 → 0; soft-d5 / stragglers → 1+).
    """
    failures = gate_failures if gate_failures is not None else {}
    difficulty = get_difficulty(levels, scenario)
    timesteps = train_timesteps_for_scenario(
        scenario,
        levels,
        scenario_names,
        learning=learning,
        mastered=mastered,
        maintenance=maintenance,
        all_mastered=all_mastered,
        game_phase=game_phase,
        gate_failures=failures,
        drill_stats=drill_stats,
    )
    if timesteps <= 0:
        return 0
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
    return max(1, timesteps // base)


def train_multiplier_drill_label(
    scenario: str,
    levels: dict[str, int],
    *,
    difficulty: int,
    gate_failures: dict[str, dict[str, int]],
) -> str:
    """``name (dN)`` plus soft/proxy/optional when 1× is not plain pack-level learning."""
    catalog = require_catalog()
    notes: list[str] = []
    if scenario_needs_maintenance(scenario, difficulty=difficulty, gate_failures=gate_failures):
        notes.append("soft")
    entry = catalog.get(scenario)
    if entry is not None and entry.difficulty_proxy:
        notes.append("proxy")
    elif not catalog.gates_progress(scenario):
        notes.append("optional")
    detail = f"d{difficulty}"
    if notes:
        detail = f"{detail}; {', '.join(notes)}"
    return f"{catalog.short_name(scenario)} ({detail})"


def format_train_multiplier_lines(
    by_units: dict[int, list[str]],
    *,
    header: str = "  train multipliers (next cycle):",
    include_zero: bool = True,
) -> list[str]:
    """Render ``{units: [drill labels…]}`` as the shared 1×/2×/8× listing."""
    if not by_units:
        return []
    lines = [header]
    for units in sorted(by_units):
        lines.append(f"    {units}×: {', '.join(by_units[units])}")
    if include_zero:
        lines.append("    0×: all others")
    return lines


def train_multiplier_groups(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
    drill_stats: dict[str, dict[str, int]] | None = None,
    *,
    learning: int = DEFAULT_SCENARIO_TRAIN_LEARNING,
    mastered: int = DEFAULT_SCENARIO_TRAIN_MASTERED,
    maintenance: int = DEFAULT_SCENARIO_TRAIN_MAINTENANCE,
    game_phase: bool = False,
) -> dict[int, list[str]]:
    """Next-cycle train × → drill labels from ``scenario_train_budget_units``."""
    failures = gate_failures if gate_failures is not None else {}
    stats = drill_stats if drill_stats is not None else {}
    catalog = require_catalog()
    claims_on = claims_unlocked(levels, scenario_names, failures)
    mastered_pack = all_scenarios_mastered(levels, scenario_names)
    by_units: dict[int, list[str]] = {}
    for scenario in active_scenario_catalog(levels, scenario_names, failures):
        if catalog.is_claim(scenario) and not claims_on:
            continue
        units = scenario_train_budget_units(
            scenario,
            levels,
            scenario_names,
            learning=learning,
            mastered=mastered,
            maintenance=maintenance,
            all_mastered=mastered_pack,
            game_phase=game_phase,
            gate_failures=failures,
            drill_stats=stats,
        )
        if units <= 0:
            continue
        difficulty = get_difficulty(levels, scenario)
        by_units.setdefault(units, []).append(
            train_multiplier_drill_label(
                scenario, levels, difficulty=difficulty, gate_failures=failures
            )
        )
    return by_units


def train_multiplier_summary_lines(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
    drill_stats: dict[str, dict[str, int]] | None = None,
    *,
    learning: int = DEFAULT_SCENARIO_TRAIN_LEARNING,
    mastered: int = DEFAULT_SCENARIO_TRAIN_MASTERED,
    maintenance: int = DEFAULT_SCENARIO_TRAIN_MAINTENANCE,
    game_phase: bool = False,
) -> list[str]:
    """Next-cycle train × listing (0× = everyone else)."""
    return format_train_multiplier_lines(
        train_multiplier_groups(
            levels,
            scenario_names,
            gate_failures,
            drill_stats,
            learning=learning,
            mastered=mastered,
            maintenance=maintenance,
            game_phase=game_phase,
        )
    )


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
    """Drills to train/eval this cycle (after claim deferral + loose-ball focus).

    Every active drill runs — nothing is skipped for being ahead of the pack.
    Order: ascending curriculum difficulty (d0 first), catalog order within a level.
    """
    active = active_scenario_catalog(levels, scenario_names, gate_failures)
    return sorted(active, key=lambda name: (get_difficulty(levels, name), scenario_names.index(name)))


def loose_ball_focus_active(
    levels: dict[str, int],
    scenario_names: list[str],
) -> bool:
    """True while ``LOOSE_BALL_FOCUS_TARGET`` is in the catalog and below ``LOOSE_BALL_FOCUS_UNTIL``."""
    if LOOSE_BALL_FOCUS_TARGET not in scenario_names:
        return False
    return get_difficulty(levels, LOOSE_BALL_FOCUS_TARGET) < LOOSE_BALL_FOCUS_UNTIL


def loose_ball_bounce_paused(
    levels: dict[str, int],
    scenario_names: list[str],
) -> bool:
    """True while LBR is in the catalog and at ``LOOSE_BALL_BOUNCE_PAUSE_THROUGH`` or below."""
    if LOOSE_BALL_FOCUS_TARGET not in scenario_names:
        return False
    return get_difficulty(levels, LOOSE_BALL_FOCUS_TARGET) <= LOOSE_BALL_BOUNCE_PAUSE_THROUGH


def active_scenario_catalog(
    levels: dict[str, int],
    scenario_names: list[str],
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> list[str]:
    """Catalog for this cycle: board drills always; all claim drills after board unlock.

    Optional board drills (``gatesProgress=false``) train from d0 alongside gating board drills.
    Every ``family=CLAIM`` drill waits for [claims_unlocked] — optional claims are not special-cased.

    While [loose_ball_focus_active], only ``LOOSE_BALL_FOCUS_TARGET`` and drills whose
    catalog ``difficultyProxy`` points at it stay in the cycle — pause everything else so
    shared heads learn ball location before other openings fight the prior.

    While [loose_ball_bounce_paused], drop LBR difficultyProxy drills (bounce) even under focus —
    contested exposure without a PASS/soft-mask lesson flattens free-pickup learning at d0–d2.
    """
    catalog = require_catalog()
    claims_ok = claims_unlocked(levels, scenario_names, gate_failures)
    active = [
        name
        for name in scenario_names
        if catalog.is_board(name)
        or (catalog.is_claim(name) and claims_ok)
    ]
    if loose_ball_focus_active(levels, scenario_names):
        active = [
            name
            for name in active
            if catalog.curriculum_key(name) == LOOSE_BALL_FOCUS_TARGET
        ]
    if loose_ball_bounce_paused(levels, scenario_names):
        active = [
            name
            for name in active
            if catalog.curriculum_key(name) != LOOSE_BALL_FOCUS_TARGET
            or name == LOOSE_BALL_FOCUS_TARGET
        ]
    return active


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
    """True on a full batch with at least ``eval_pass_min_wins(expected_games)`` US wins."""
    wins = _eval_win_count(stats, expected_games=expected_games)
    if wins is None:
        return False
    return wins >= eval_pass_min_wins(expected_games)


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
    """If eval passed (≥ pass-min / ``expected_games``), bump by 1."""
    current = get_difficulty(levels, scenario)
    if current >= MAX_DIFFICULTY:
        return levels, False
    if not _eval_passes(stats, expected_games=expected_games):
        return levels, False
    updated = dict(levels)
    updated[curriculum_key(scenario)] = current + 1
    return updated, True


def probe_catalog_scenario_keys() -> list[str]:
    """Deduped curriculum keys for an expert floor probe (skip proxy sources)."""
    catalog = require_catalog()
    proxies = catalog.proxy_sources()
    keys: list[str] = []
    seen: set[str] = set()
    for name in catalog.names():
        if name in proxies:
            continue
        key = curriculum_key(name)
        if key in seen:
            continue
        seen.add(key)
        keys.append(key)
    return keys


def climb_until_fail_levels(
    scenarios: list[str],
    eval_at: Callable[[str, int], dict[str, Any]],
    *,
    expected_games: int = SCENARIO_EVAL_GAMES,
) -> dict[str, int]:
    """Eval each drill at d0, d1, … until the batch fails (or d5 passes).

    Leaves the stored level at the **first failing** difficulty (frontier for train),
    or ``MAX_DIFFICULTY`` when every level passes. ``eval_at(scenario, difficulty)`` must
    return stats with ``episodes`` / ``wins`` like a normal after-eval.
    """
    levels: dict[str, int] = {}
    for scenario in scenarios:
        key = curriculum_key(scenario)
        if key in levels:
            continue
        difficulty = 0
        while True:
            stats = eval_at(key, difficulty)
            if not _eval_passes(stats, expected_games=expected_games):
                levels[key] = difficulty
                break
            if difficulty >= MAX_DIFFICULTY:
                levels[key] = MAX_DIFFICULTY
                break
            difficulty += 1
    return levels


def maybe_demote_after_failed_max_eval(
    levels: dict[str, int],
    scenario: str,
    stats: dict[str, Any],
    *,
    expected_games: int,
    max_wins: int | None = None,
) -> tuple[dict[str, int], bool]:
    """Drop one level when a d5 drill has ``max_wins`` or fewer US wins in a full pass."""
    current = get_difficulty(levels, scenario)
    if current < MAX_DIFFICULTY:
        return levels, False
    ceiling = eval_demote_max_wins(expected_games) if max_wins is None else max_wins
    wins = _eval_win_count(stats, expected_games=expected_games)
    if wins is None or wins > ceiling:
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
