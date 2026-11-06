"""High-level training phases (model stages after expert seed).

Owner: which checkpoint tree to train, gates between scenario_a / scenario_b / profiles,
fork-from-prior zip. Canonical profile → checkpoint routing (``active_targets`` /
``resolve_targets`` / ``TrainTarget`` paths) for train, summary, and play. Does **not**
own per-drill difficulty bumps (``scenario_curriculum``) or PPO loops
(``train_self_play``). Destructive rollbacks are ``./start_over.sh`` only — gates never
auto-delete later trees. See ``ai/OWNERSHIP.md`` / ``TRAINING_ROADMAP.md``.

Shell chain: dump → pack → seed → scenario_a → scenario_b → checkpoint (profiles).

  scenario_a — board drills only, baseline, gate = every board drill at d4
  scenario_b — board + claims, baseline, gate = every drill at MAX; games still off
  profiles   — baseline + shaped forked from frozen scenario_b (weights only); each stem
               re-climbs scenarios; full-game when that stem's gating drills reach MAX
               (``--force-promote`` can force it)
  games      — per profile stem after that unlock: games→scenarios (full-game first)

Position is a saved FSM, not derived from directories: ``pipeline_state``
(``checkpoints/pipeline.json``) holds the stage plus each forked profile's climbing/games
stage. Routing consults it first. Only ``advance_pipeline`` (train loop, after curriculum
updates), ``force_promote`` and profile forks move it forward; wipes rewind it.

Requesting a shaped profile before scenario_b is ready trains the incomplete phase first —
profiles never start from the expert seed.

``active_targets`` / ``resolve_targets("all")``:
  - Pre-split: always baseline reward on the current phase tree (``--profile`` ignored for routing).
  - Post-split train/eval: ``baseline`` as its own checkpoint tree (``checkpoints/baseline/``) plus
    ``profiles_for_all("train")`` shaped names (today: ``general``, ``spread``). Unforked
    profiles fork from **frozen** ``scenario_b`` via ``ensure_checkpoint_from_prior`` — never from
    live baseline. After split, scenario_a / scenario_b trees are not trained further.
  - Post-split summary: ``profiles_for_all("summary")`` — full ``REWARD_PROFILES`` so
    ``td`` checkpoints stay visible without training them on ``all``.

**Why baseline stays in post-split ``--profile all``:** same as general/td/defense — own zip,
own runs, own evals — with empty shaping (margin + terminal only). It is the unshaped control
forked once from scenario_b at split, then trained independently. Do **not** keep writing the
scenario_b tree after split, and do **not** drop baseline from ``active_targets``.

Eval-only ``all`` follows the train active set (forked profiles only).
"""

from __future__ import annotations

import re
import shutil
import time
from dataclasses import dataclass, replace
from pathlib import Path

from gridiron_ai.paths import CHECKPOINT_DIR, EVAL_DIR, EXPERT_SEED_ZIP, RUNS_DIR
from gridiron_ai.pipeline_state import (
    STAGES,
    ProfileSplit,
    ProfileState,
    StageName,
    load_pipeline,
    save_pipeline,
)
from gridiron_ai.reward import (
    AllProfilesPurpose,
    RewardProfileName,
    SHAPED_PROFILES,
    profiles_for_all,
)
from gridiron_ai.scenario_curriculum import (
    CLAIM_UNLOCK_BOARD_DIFFICULTY,
    MAX_DIFFICULTY,
    all_scenarios_at_least,
    claims_unlocked,
    gating_scenarios_mastered,
    board_scenarios_in_catalog,
    get_difficulty,
    load_drill_stats,
    load_gate_failures,
    load_levels,
    merge_drill_stats,
    path_for_drill_stats,
    path_for_gate_failures,
    path_for_stem,
)
from gridiron_ai.api.scenarios import require_catalog

PhaseName = StageName

PHASE_SCENARIO_A: PhaseName = "scenario_a"
PHASE_SCENARIO_B: PhaseName = "scenario_b"
PHASE_PROFILES: PhaseName = "profiles"
# Post-split unshaped control — same fork rules as general/spread/td/defense.
BASELINE_CHECKPOINT_KEY = "baseline"
# Immutable copy written on ONNX export (``freeze_exported_checkpoint``); training keeps
# updating ``maskable_ppo_selfplay_final.zip`` without clobbering the last export.
_EXPORTED_ZIP_NAME = "maskable_ppo_selfplay_exported.zip"

_LONG_RUNNING_STEM = "maskable_ppo_selfplay_final"
_VECNORMALIZE_SUFFIX = "_vecnormalize.pkl"
_STEPS_GLOB = "maskable_ppo_selfplay_*_steps.zip"


@dataclass(frozen=True)
class TrainTarget:
    """One resolvable train/status unit: phase + checkpoint tree + reward profile."""

    phase: PhaseName
    # Directory key under checkpoints/ and runs/ (scenario_a, scenario_b, or a profile name).
    checkpoint_key: str
    reward_profile: RewardProfileName
    # Human-facing reason when a requested profile is deferred to an earlier phase.
    deferral_note: str | None = None

    def checkpoint_dir(self) -> Path:
        return CHECKPOINT_DIR / self.checkpoint_key

    def save_stem(self) -> Path:
        return long_running_stem(self.checkpoint_key)

    def zip_path(self) -> Path:
        return long_running_zip(self.checkpoint_key)

    def curriculum_path(self) -> Path:
        return path_for_stem(self.save_stem())

    def runs_path(self) -> Path:
        return runs_dir(self.checkpoint_key)

    def eval_dir(self) -> Path:
        return EVAL_DIR / self.reward_profile

    def has_artifacts(self) -> bool:
        if self.zip_path().is_file():
            return True
        directory = self.checkpoint_dir()
        return directory.is_dir() and any(directory.glob(_STEPS_GLOB))

    def load_levels(self) -> dict[str, int]:
        return load_levels(self.curriculum_path())

    def load_drill_stats_cumulative(self) -> dict[str, dict[str, int]]:
        return load_drill_stats_cumulative(self.checkpoint_key)


def drill_stats_lineage_keys(checkpoint_key: str) -> list[str]:
    """Prior phase trees whose per-phase drill-stats sum into cumulative totals."""
    if checkpoint_key == PHASE_SCENARIO_A:
        return []
    if checkpoint_key == PHASE_SCENARIO_B:
        return [PHASE_SCENARIO_A]
    if checkpoint_key == BASELINE_CHECKPOINT_KEY or checkpoint_key in SHAPED_PROFILES:
        return [PHASE_SCENARIO_A, PHASE_SCENARIO_B]
    return []


def load_drill_stats_cumulative(checkpoint_key: str) -> dict[str, dict[str, int]]:
    """Train units / d5 demotes summed across scenario_a → … → ``checkpoint_key``."""
    keys = [*drill_stats_lineage_keys(checkpoint_key), checkpoint_key]
    parts = [load_drill_stats(path_for_drill_stats(long_running_stem(key))) for key in keys]
    return merge_drill_stats(*parts)


def long_running_stem(checkpoint_key: str) -> Path:
    return CHECKPOINT_DIR / checkpoint_key / _LONG_RUNNING_STEM


def long_running_zip(checkpoint_key: str) -> Path:
    return Path(str(long_running_stem(checkpoint_key)) + ".zip")


# Eval pin: phase name → that phase’s end zip (scenario_a end == scenario_b start).
# No path args — every “eval this model” ask is relative to the pipeline phases.
EVAL_PHASE_CHOICES = ("seed", PHASE_SCENARIO_A, PHASE_SCENARIO_B)


def eval_zip_for_phase(phase: str) -> Path:
    """End-of-``phase`` MaskablePPO zip for ``eval.sh --phase`` (read-only)."""
    if phase == "seed":
        return EXPERT_SEED_ZIP
    if phase == PHASE_SCENARIO_A:
        return long_running_zip(PHASE_SCENARIO_A)
    if phase == PHASE_SCENARIO_B:
        return long_running_zip(PHASE_SCENARIO_B)
    raise SystemExit(f"eval --phase expects one of {', '.join(EVAL_PHASE_CHOICES)}; got {phase!r}")


def exported_freeze_zip(checkpoint_key: str) -> Path:
    """Last-exported freeze beside the rolling final zip (see ``freeze_exported_checkpoint``)."""
    return CHECKPOINT_DIR / checkpoint_key / _EXPORTED_ZIP_NAME


def freeze_exported_checkpoint(checkpoint_key: str) -> Path:
    """Copy rolling final zip (+ vecnormalize) to the export freeze; overwrites prior freeze."""
    src_zip = long_running_zip(checkpoint_key)
    if not src_zip.is_file():
        raise SystemExit(f"freeze_exported_checkpoint: missing {src_zip}")
    dest_zip = exported_freeze_zip(checkpoint_key)
    dest_zip.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src_zip, dest_zip)
    print(f"  froze export checkpoint {dest_zip} ← {src_zip}", flush=True)
    src_vn = Path(str(long_running_stem(checkpoint_key)) + _VECNORMALIZE_SUFFIX)
    dest_vn = Path(str(dest_zip.with_suffix("")) + _VECNORMALIZE_SUFFIX)
    if src_vn.is_file():
        shutil.copy2(src_vn, dest_vn)
        print(f"  froze export vecnormalize {dest_vn} ← {src_vn}", flush=True)
    return dest_zip


def _record_profile_fork(checkpoint_key: str, scenario_b_steps: int) -> None:
    """Save a new climbing profile; the first fork also fixes the split (later forks keep it)."""
    state = load_pipeline()
    split = state.split or ProfileSplit(at=time.time(), scenario_b_steps=scenario_b_steps or None)
    profiles = {**state.profiles, checkpoint_key: ProfileState()}
    save_pipeline(replace(state, split=split, profiles=profiles))


def _max_checkpoint_steps(checkpoint_key: str) -> int:
    best = 0
    directory = CHECKPOINT_DIR / checkpoint_key
    if not directory.is_dir():
        return 0
    for path in directory.glob(_STEPS_GLOB):
        match = re.search(r"_(\d+)_steps\.zip$", path.name)
        if match:
            best = max(best, int(match.group(1)))
    return best


def scenario_a_gate_met(
    levels: dict[str, int],
    scenario_names: list[str] | None = None,
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> bool:
    """Every board drill in the catalog at d4+."""
    catalog = scenario_names if scenario_names is not None else require_catalog().names()
    board = board_scenarios_in_catalog(catalog, levels, gate_failures)
    if not board:
        board = [name for name in require_catalog().board_names() if require_catalog().gates_progress(name)]
    return all_scenarios_at_least(levels, board, CLAIM_UNLOCK_BOARD_DIFFICULTY, gate_failures)


def scenario_b_gate_met(
    levels: dict[str, int],
    scenario_names: list[str] | None = None,
    gate_failures: dict[str, dict[str, int]] | None = None,
) -> bool:
    """Every gating catalog drill at MAX_DIFFICULTY."""
    catalog = scenario_names if scenario_names is not None else require_catalog().names()
    return gating_scenarios_mastered(levels, catalog, gate_failures)


def current_phase() -> PhaseName:
    """Saved climbing stage: scenario_a → scenario_b → profiles."""
    return load_pipeline().stage


def phase_passed(phase: PhaseName) -> bool:
    """True once the saved pipeline moved beyond ``phase`` (demotions never rewind it)."""
    return load_pipeline().passed(phase)


def profiles_split_occurred() -> bool:
    """True once baseline + shaped profile trees forked from frozen scenario_b."""
    return load_pipeline().split is not None


def game_phase_unlocked(checkpoint_key: str) -> bool:
    """Full-game first for this tree: profile stems at saved stage ``games``; scenario trees
    once scenario_b passed."""
    state = load_pipeline()
    if checkpoint_key in (PHASE_SCENARIO_A, PHASE_SCENARIO_B):
        return state.passed(PHASE_SCENARIO_B)
    return state.plays_games(checkpoint_key)


def advance_pipeline(target: TrainTarget) -> str | None:
    """Save the next stage / profile ``games`` transition when ``target``'s live gate passes.

    The train loop is the only caller, right after a cycle updated that tree's curriculum —
    the one place levels change. Returns a log line when something advanced.
    """
    state = load_pipeline()
    levels = target.load_levels()
    failures = _gate_failures_for_phase(target.checkpoint_key)
    names = require_catalog().names()
    if target.phase == PHASE_PROFILES:
        profile = state.profiles.get(target.checkpoint_key)
        if profile is None or profile.stage == "games":
            return None
        if not gating_scenarios_mastered(levels, names, failures):
            return None
        profiles = {**state.profiles, target.checkpoint_key: ProfileState(stage="games")}
        save_pipeline(replace(state, profiles=profiles))
        return f"pipeline: {target.checkpoint_key} climbing → games (gating drills d{MAX_DIFFICULTY})"
    if target.phase != state.stage:
        return None
    if not _live_phase_gate_met(target.phase, levels, names, failures):
        return None
    next_stage = STAGES[STAGES.index(state.stage) + 1]
    save_pipeline(replace(state, stage=next_stage))
    return f"pipeline: {target.phase} gate passed → {next_stage}"


def _require_phase_checkpoint(phase: PhaseName) -> Path:
    zip_path = long_running_zip(phase)
    if not zip_path.is_file():
        raise SystemExit(
            f"--force-promote: missing checkpoint {zip_path} (train through the prior stage first)"
        )
    return zip_path


def force_promote() -> list[str]:
    """Promote one pipeline stage without a live gate; fail if a checkpoint is missing.

    At profiles: fork every not-yet-forked profile first; once all are forked, force
    climbing stems to ``games`` (scenario drills may still be below MAX).
    """
    state = load_pipeline()
    if state.stage != PHASE_PROFILES:
        _require_phase_checkpoint(state.stage)
        save_pipeline(replace(state, stage=STAGES[STAGES.index(state.stage) + 1]))
        return [state.stage]
    _require_phase_checkpoint(PHASE_SCENARIO_B)
    unforked = [name for name in (BASELINE_CHECKPOINT_KEY, *SHAPED_PROFILES) if name not in state.profiles]
    if unforked:
        for name in unforked:
            ensure_checkpoint_from_prior(name, PHASE_PROFILES)
        return [f"profiles/{name}" for name in unforked]
    climbing = [name for name, profile in state.profiles.items() if profile.stage == "climbing"]
    if not climbing:
        raise SystemExit("--force-promote: already at profiles and every profile plays games")
    forced = {name: ProfileState(stage="games", forced=True) for name in climbing}
    save_pipeline(replace(state, profiles={**state.profiles, **forced}))
    return [f"game_phase/{name}" for name in climbing]


def _live_phase_gate_met(
    phase: PhaseName,
    levels: dict[str, int],
    scenario_names: list[str] | None,
    gate_failures: dict[str, dict[str, int]],
) -> bool:
    if phase == PHASE_SCENARIO_A:
        return scenario_a_gate_met(levels, scenario_names, gate_failures)
    return scenario_b_gate_met(levels, scenario_names, gate_failures)


def _gate_failures_for_phase(key: str) -> dict[str, dict[str, int]]:
    return load_gate_failures(path_for_gate_failures(long_running_stem(key)))


def _phase_gate_progress(phase: PhaseName, scenario_names: list[str]) -> str:
    """``n/total at dN+`` for a climbing phase stem (empty catalog → ``0/0``)."""
    levels = load_levels(path_for_stem(long_running_stem(phase)))
    failures = _gate_failures_for_phase(phase)
    if phase == PHASE_SCENARIO_A:
        board = board_scenarios_in_catalog(scenario_names, levels, failures)
        if not board:
            board = [
                name
                for name in require_catalog().board_names()
                if require_catalog().gates_progress(name)
            ]
        at = sum(
            1 for name in board if get_difficulty(levels, name) >= CLAIM_UNLOCK_BOARD_DIFFICULTY
        )
        return f"{at}/{len(board)} at d{CLAIM_UNLOCK_BOARD_DIFFICULTY}+"
    gating = [name for name in scenario_names if require_catalog().gates_progress(name)]
    at = sum(1 for name in gating if get_difficulty(levels, name) >= MAX_DIFFICULTY)
    return f"{at}/{len(gating)} at d{MAX_DIFFICULTY}"


def _cycle_order_bit(
    phase: PhaseName,
    scenario_names: list[str],
    game_unlocked: dict[str, bool],
) -> str:
    """What one train cycle does at the current pipeline stage.

    ``game_unlocked`` is profiles-stage only: active profile key → saved full-game unlock.
    """
    if phase == PHASE_SCENARIO_A:
        return (
            f"scenarios→games; board drills only until d{CLAIM_UNLOCK_BOARD_DIFFICULTY}+; "
            "full-game gated"
        )
    if phase == PHASE_SCENARIO_B:
        levels = load_levels(path_for_stem(long_running_stem(PHASE_SCENARIO_B)))
        failures = _gate_failures_for_phase(PHASE_SCENARIO_B)
        if not claims_unlocked(levels, scenario_names, failures):
            return (
                f"scenarios→games; claims deferred until board d{CLAIM_UNLOCK_BOARD_DIFFICULTY}+; "
                "full-game gated"
            )
        return (
            f"scenarios→games; board+claims; full-game gated until gating drills "
            f"d{MAX_DIFFICULTY}"
        )
    games = [key for key, unlocked in game_unlocked.items() if unlocked]
    climbing = [key for key, unlocked in game_unlocked.items() if not unlocked]
    if not climbing:
        return "games→scenarios; full-game first; soft-d5 scenario maintenance"
    if not games:
        return f"scenarios→games; full-game gated until stem gating d{MAX_DIFFICULTY}"
    return (
        f"games→scenarios for {', '.join(games)}; "
        f"scenarios→games for {', '.join(climbing)}"
    )


def phase_status_lines(*, scenario_names: list[str] | None = None) -> list[str]:
    """Pipeline progress + cycle order for summary footers (compact and verbose)."""
    names = scenario_names if scenario_names is not None else require_catalog().names()
    state = load_pipeline()
    phase = state.stage
    active = active_targets(purpose="train")

    def row(label: str, gate: str, status: str, *, current: bool) -> str:
        mark = "▶" if current else " "
        return f"  {mark} {label:<11} {gate:<24} {status}"

    if state.split is not None:
        a_status = "frozen"
        b_status = "frozen"
    else:
        a_passed = state.passed(PHASE_SCENARIO_A)
        a_status = "done" if a_passed else _phase_gate_progress(PHASE_SCENARIO_A, names)
        if not a_passed:
            b_status = "locked"
        elif state.passed(PHASE_SCENARIO_B):
            b_status = "done"
        else:
            b_status = _phase_gate_progress(PHASE_SCENARIO_B, names)

    lines = [
        "pipeline",
        row(
            "scenario_a",
            f"board → d{CLAIM_UNLOCK_BOARD_DIFFICULTY}+",
            a_status,
            current=phase == PHASE_SCENARIO_A,
        ),
        row(
            "scenario_b",
            f"gating → d{MAX_DIFFICULTY}",
            b_status,
            current=phase == PHASE_SCENARIO_B,
        ),
    ]
    game_unlocked: dict[str, bool] = {}
    if phase == PHASE_PROFILES:
        game_unlocked = {t.checkpoint_key: state.plays_games(t.checkpoint_key) for t in active}
    games = [key for key, unlocked in game_unlocked.items() if unlocked]
    if phase == PHASE_PROFILES:
        profile_names = ", ".join(t.reward_profile for t in active)
        climb = _profile_climb_status(names)
        climbing = len(games) < len(game_unlocked)
        lines.append(row("profiles", "each stem → d5", f"{profile_names} · {climb}", current=climbing))
    else:
        lines.append(row("profiles", "each stem → d5", "locked", current=False))
    games_status = f"{', '.join(games)} unlocked" if games else "locked"
    lines.append(row("games", "full-game first", games_status, current=bool(games)))
    lines.append(f"  cycle: {_cycle_order_bit(phase, names, game_unlocked)}")
    return lines


def _profile_climb_status(scenario_names: list[str]) -> str:
    """Gating drill progress on the baseline profile stem (each profile has own JSON)."""
    if BASELINE_CHECKPOINT_KEY not in load_pipeline().profiles:
        return "baseline not forked"
    gating = [name for name in scenario_names if require_catalog().gates_progress(name)]
    stem = long_running_stem(BASELINE_CHECKPOINT_KEY)
    levels = load_levels(path_for_stem(stem))
    at = sum(1 for name in gating if get_difficulty(levels, name) >= MAX_DIFFICULTY)
    return f"baseline {at}/{len(gating)} at d{MAX_DIFFICULTY}"


def active_targets(*, purpose: AllProfilesPurpose = "train") -> list[TrainTarget]:
    """Who ``--profile all`` expands to (train/eval rotation or summary board).

    Profile membership comes from ``profiles_for_all(purpose)`` — do not re-list
    shaped names at call sites.

    Pre-split: baseline reward on the current climbing phase tree only (purpose ignored).

    Post-split: baseline (own ``checkpoints/baseline/`` tree) then shaped names from that helper.
    Baseline is the unshaped control — see module docstring; do not remove it.
    """
    if not phase_passed(PHASE_SCENARIO_A):
        return [_baseline_target_on_phase(PHASE_SCENARIO_A)]
    if not phase_passed(PHASE_SCENARIO_B):
        return [_baseline_target_on_phase(PHASE_SCENARIO_B)]
    shaped = [name for name in profiles_for_all(purpose) if name != "baseline"]
    return [
        _baseline_profile_target(),
        *[
            TrainTarget(phase=PHASE_PROFILES, checkpoint_key=name, reward_profile=name)  # type: ignore[arg-type]
            for name in shaped
        ],
    ]


def _baseline_target_on_phase(
    phase: PhaseName,
    *,
    deferral_note: str | None = None,
) -> TrainTarget:
    """Pre-split: baseline reward trains the scenario_a / scenario_b phase tree."""
    return TrainTarget(
        phase=phase,
        checkpoint_key=phase,
        reward_profile="baseline",
        deferral_note=deferral_note,
    )


def _baseline_profile_target(*, deferral_note: str | None = None) -> TrainTarget:
    """Post-split: baseline is a real profile zip forked from frozen scenario_b."""
    return TrainTarget(
        phase=PHASE_PROFILES,
        checkpoint_key=BASELINE_CHECKPOINT_KEY,
        reward_profile="baseline",
        deferral_note=deferral_note,
    )


def resolve_targets(
    profile_arg: str,
    *,
    eval_only: bool = False,
    purpose: AllProfilesPurpose = "train",
) -> list[TrainTarget]:
    """Canonical profile → checkpoint answer for train / summary / play. Always a list.

    ``profile_arg \"all\"`` → ``active_targets(purpose=…)`` (membership via
    ``profiles_for_all``). Pre-split, shaped ``--profile`` values are ignored for routing
    (same baseline target + ``deferral_note``).
    """
    if eval_only:
        return _as_list(_eval_targets(profile_arg))

    if profile_arg == "all":
        return active_targets(purpose=purpose)

    if not phase_passed(PHASE_SCENARIO_A):
        note = None
        if profile_arg not in ("baseline", PHASE_SCENARIO_A):
            note = (
                f"Requested profile={profile_arg} but scenario_a is incomplete "
                f"(board drills → d{CLAIM_UNLOCK_BOARD_DIFFICULTY}); training baseline on scenario_a first"
            )
        return [_baseline_target_on_phase(PHASE_SCENARIO_A, deferral_note=note)]

    if not phase_passed(PHASE_SCENARIO_B):
        note = None
        if profile_arg not in ("baseline", PHASE_SCENARIO_B):
            note = (
                f"Requested profile={profile_arg} but scenario_b is incomplete "
                f"(all drills → d{MAX_DIFFICULTY}); training baseline on scenario_b first"
            )
        return [_baseline_target_on_phase(PHASE_SCENARIO_B, deferral_note=note)]

    if profile_arg == "baseline":
        return [_baseline_profile_target()]

    if profile_arg in (PHASE_SCENARIO_A, PHASE_SCENARIO_B):
        # Frozen phase trees post-split (eval / export / summary); no further train writes.
        return [
            TrainTarget(
                phase=profile_arg,  # type: ignore[arg-type]
                checkpoint_key=profile_arg,
                reward_profile="baseline",
            )
        ]

    if profile_arg in SHAPED_PROFILES:
        return [
            TrainTarget(
                phase=PHASE_PROFILES,
                checkpoint_key=profile_arg,
                reward_profile=profile_arg,  # type: ignore[arg-type]
            )
        ]

    raise SystemExit(f"unknown profile: {profile_arg}")


def resolve_train_target(
    profile_arg: str,
    *,
    eval_only: bool = False,
    purpose: AllProfilesPurpose = "train",
) -> TrainTarget | list[TrainTarget]:
    """Compat wrapper: single target when one result, else a list (``all`` / multi-eval)."""
    targets = resolve_targets(profile_arg, eval_only=eval_only, purpose=purpose)
    if len(targets) == 1 and profile_arg != "all":
        return targets[0]
    return targets


def ensure_checkpoint_from_prior(checkpoint_key: str, phase: PhaseName) -> None:
    """Create missing long-running zip (+ curriculum / vecnormalize) from the prior stage.

    Post-split profile keys (``baseline`` + shaped) always fork from **frozen** ``scenario_b``,
    never from each other — so late-added profiles share the same split weights.

    Profile forks are decided by saved state (``pipeline.json`` ``profiles``), not by the
    zip on disk; an untracked profile zip is refused rather than silently overwritten.

    Destructive rollbacks are explicit only (``./start_over.sh --phase …``) — never
    auto-delete later trees when a gate regresses.
    """
    dest_zip = long_running_zip(checkpoint_key)
    if phase == PHASE_PROFILES:
        if checkpoint_key in load_pipeline().profiles:
            return
        if dest_zip.is_file():
            raise SystemExit(
                f"{dest_zip} exists but pipeline.json has no fork for {checkpoint_key} — wipe it "
                f"(./start_over.sh --phase checkpoint --profile {checkpoint_key}) or fix pipeline.json"
            )
    elif dest_zip.is_file():
        return
    if phase == PHASE_SCENARIO_A:
        src_zip = EXPERT_SEED_ZIP
        label = "expert seed"
    elif phase == PHASE_SCENARIO_B:
        src_zip = long_running_zip(PHASE_SCENARIO_A)
        label = "scenario_a"
        if not src_zip.is_file():
            raise SystemExit(f"scenario_b needs scenario_a checkpoint at {src_zip}")
    else:
        src_zip = long_running_zip(PHASE_SCENARIO_B)
        label = "scenario_b"
        if not src_zip.is_file():
            raise SystemExit(f"profile {checkpoint_key} needs scenario_b checkpoint at {src_zip}")

    if not src_zip.is_file():
        raise SystemExit(f"missing prior artifact for {checkpoint_key}: {src_zip} ({label})")

    dest_stem = long_running_stem(checkpoint_key)
    dest_stem.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src_zip, dest_zip)
    print(f"  seeded {dest_zip} ← {src_zip} ({label})", flush=True)
    if phase == PHASE_PROFILES:
        _record_profile_fork(checkpoint_key, _max_checkpoint_steps(PHASE_SCENARIO_B))
        _copy_latest_steps_zip(src_zip.parent, dest_stem.parent)

    src_stem = Path(str(src_zip.with_suffix("")))
    # Expert seed has no curriculum / vecnormalize; phase→phase copies may.
    # Profile fork: weights + vecnormalize only — each profile re-earns scenario ladder.
    if phase != PHASE_PROFILES:
        _copy_sidecar(path_for_stem(src_stem), path_for_stem(dest_stem))
    _copy_sidecar(
        Path(str(src_stem) + _VECNORMALIZE_SUFFIX),
        Path(str(dest_stem) + _VECNORMALIZE_SUFFIX),
    )


def runs_dir(checkpoint_key: str) -> Path:
    return RUNS_DIR / checkpoint_key


def _as_list(resolved: TrainTarget | list[TrainTarget]) -> list[TrainTarget]:
    return resolved if isinstance(resolved, list) else [resolved]


def _copy_sidecar(src: Path, dest: Path) -> None:
    if src.is_file():
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dest)
        print(f"  seeded {dest} ← {src}", flush=True)


def _copy_latest_steps_zip(src_dir: Path, dest_dir: Path) -> None:
    """Copy the highest ``*_steps.zip`` so summary can show steps before the next train save."""
    best_steps = 0
    best_path: Path | None = None
    for path in src_dir.glob(_STEPS_GLOB):
        match = re.search(r"_(\d+)_steps\.zip$", path.name)
        if match:
            steps = int(match.group(1))
            if steps > best_steps:
                best_steps = steps
                best_path = path
    if best_path is None:
        return
    dest = dest_dir / best_path.name
    if dest.is_file():
        return
    dest_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(best_path, dest)
    print(f"  seeded {dest} ← {best_path}", flush=True)


def _eval_targets(profile_arg: str) -> TrainTarget | list[TrainTarget]:
    """Eval-only routing — does not catch up incomplete phases or fork profiles.

    ``all``: ``active_targets(purpose=\"eval\")`` limited to forked profiles; before any
    fork, baseline on the farthest scenario tree the saved stage has reached.
    """
    state = load_pipeline()
    farthest_tree = PHASE_SCENARIO_B if state.passed(PHASE_SCENARIO_A) else PHASE_SCENARIO_A
    if profile_arg == "all":
        forked = [t for t in active_targets(purpose="eval") if t.checkpoint_key in state.profiles]
        if forked:
            return forked
        target = _baseline_target_on_phase(farthest_tree)
        if not target.zip_path().is_file():
            raise SystemExit(f"No checkpoint at {target.zip_path()} — train before eval")
        return [target]
    if profile_arg in (PHASE_SCENARIO_A, PHASE_SCENARIO_B):
        return TrainTarget(
            phase=profile_arg,  # type: ignore[arg-type]
            checkpoint_key=profile_arg,
            reward_profile="baseline",
        )
    if profile_arg == "baseline":
        if BASELINE_CHECKPOINT_KEY in state.profiles:
            return _baseline_profile_target()
        return _baseline_target_on_phase(farthest_tree)
    return TrainTarget(
        phase=PHASE_PROFILES if profile_arg in SHAPED_PROFILES else PHASE_SCENARIO_B,
        checkpoint_key=profile_arg,
        reward_profile=profile_arg if profile_arg != PHASE_SCENARIO_B else "baseline",  # type: ignore[arg-type]
    )
