"""Print a short status board from latest eval JSON + checkpoints (no backend).

Default: compact train + eval summary tables only. ``--verbose``: full per-profile
detail (eval blocks, legend). During ``scenario_a`` / ``scenario_b``, the compact board
always lists every curriculum drill (not only with ``--verbose``). Curriculum / steps
come from the active ``checkpoint_key`` via ``resolve_targets(..., purpose=\"summary\")``.
Scenario eval JSON: ``evals/baseline/``; shaped full-game eval JSON:
``evals/{general,spread,td,defense}/`` (see ``paths.py`` / ``common.sh``).
``--profile all`` → ``profiles_for_all(\"summary\")`` (baseline + all shaped, including
``td``). Train/eval ``all`` omits that specialist — see
``reward.profiles_for_all``.
"""

from __future__ import annotations

import argparse
import json
import logging
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from gridiron_ai.debug.eval_debug import format_eval_game_line
from gridiron_ai.train.metrics import format_scenario_eval_headline
from gridiron_ai.train.paths import EVAL_DIR
from gridiron_ai.model.reward import FULL_GAME_ONNX_OPPONENT_PROFILES, REWARD_PROFILES, SHAPED_PROFILES
from scripts.train_self_play import (
    CLIP_RANGE,
    DEFAULT_BATCH_SIZE,
    DEFAULT_ENT_COEF,
    DEFAULT_N_ENVS,
    DEFAULT_N_STEPS,
    DEFAULT_VF_COEF,
    N_EPOCHS,
    TARGET_KL,
    _DEFAULT_LEARNING_RATE,
)
from gridiron_ai.train.scenario_curriculum import (
    CLAIM_UNLOCK_BOARD_DIFFICULTY,
    GATE_PROGRESS_WAIVE_FAILURES,
    MAX_DIFFICULTY,
    SCENARIO_CYCLES_BEFORE_DRILL_STUCK,
    SCENARIO_CYCLES_BEFORE_OPENING_STUCK,
    SCENARIO_GATE_FAIL_SHOUT,
    SCENARIO_OPENING_RANK_STUCK,
    SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD,
    all_scenarios_mastered,
    board_scenarios_in_catalog,
    claims_unlocked,
    drill_stat_count,
    eval_pass_min_wins,
    gate_failure_count,
    get_difficulty,
    is_progress_waived,
    last_eval_metrics,
    last_eval_prior_metrics,
    last_eval_rank_history,
    last_eval_winrate_trail,
    load_gate_failures,
    load_last_eval,
    load_levels,
    path_for_gate_failures,
    path_for_last_eval,
    path_for_stem,
    scenario_train_budget_units,
    scenarios_for_cycle,
    train_multiplier_summary_lines,
)
from gridiron_ai.train.api.scenarios import (
    load_cached_catalog,
    require_catalog,
    scenario_phase_order,
    set_catalog,
    summary_scenario_order,
)
from gridiron_ai.train.persistence.scenario_focus import load_live_focus, path_for_focus
from gridiron_ai.train.pipeline_state import load_pipeline
from gridiron_ai.train.training_phases import (
    PHASE_SCENARIO_A,
    PHASE_SCENARIO_B,
    TrainTarget,
    game_phase_unlocked,
    long_running_stem,
    phase_status_lines,
    profiles_split_occurred,
    resolve_targets,
    runs_dir,
)

_PROFILE_BLURB = {
    "baseline": "margin + terminal only",
    "general": "shared ball / challenge shaping",
    "spread": "covering-radius Δ (field coverage)",
    "td": "TD amp + stronger ball hold",
    "defense": "KD / dugout / free the ball",
}
_STEPS_RE = re.compile(r"maskable_ppo_selfplay_(\d+)_steps\.zip$")
_DEFAULT_TREND_STEPS = 100_000
# Compact summary: vs-lookahead winrate samples over the last N PPO steps.
_DEFAULT_LA_TREND_STEPS = 10_000_000
_DEFAULT_LA_TREND_STRIDE = 1_000_000
# Half a percentage point — below what `_pct` rounds to whole percents.
_WINRATE_ARROW_EPS = 0.005
_TB_SCALAR_GUIDANCE = 2048
# Episode (gridiron_*) files are denser than PPO train/* — scan newest→oldest until
# train tags are populated; max is a safety ceiling only.
_TB_MAX_EVENT_FILES = 512
_TB_MIN_ENTROPY_POINTS = 32
_TB_SCALAR_TAGS = frozenset(
    {
        "train/entropy_loss",
        "train/approx_kl",
        "train/explained_variance",
        "train/value_loss",
        "train/clip_fraction",
    }
)
_TB_SCALAR_CACHE: dict[str, dict[str, list[tuple[int, float]]]] = {}


def _checkpoint_timeline(checkpoint_dir: Path) -> list[tuple[float, int]]:
    rows: list[tuple[float, int]] = []
    if not checkpoint_dir.is_dir():
        return rows
    for path in checkpoint_dir.glob("maskable_ppo_selfplay_*_steps.zip"):
        match = _STEPS_RE.search(path.name)
        if match:
            rows.append((path.stat().st_mtime, int(match.group(1))))
    rows.sort(key=lambda row: row[0])
    return rows


def _steps_at_time(timeline: list[tuple[float, int]], when: float) -> int:
    best = 0
    for mtime, steps in timeline:
        if mtime <= when:
            best = max(best, steps)
    return best


def _eval_training_steps(
    stats: dict[str, Any],
    *,
    path: Path,
    timeline: list[tuple[float, int]],
) -> int:
    raw = stats.get("training_steps")
    if raw is not None:
        try:
            return int(raw)
        except (TypeError, ValueError):
            pass
    return _steps_at_time(timeline, path.stat().st_mtime)


def _eval_history_by_steps(
    eval_dir: Path,
    prefix: str,
    *,
    checkpoint_dir: Path,
    current_steps: int,
    trend_steps: int,
) -> list[tuple[int, dict[str, Any]]]:
    if not eval_dir.is_dir() or current_steps <= 0:
        return []
    timeline = _checkpoint_timeline(checkpoint_dir)
    floor = max(0, current_steps - trend_steps)
    rows: list[tuple[int, dict[str, Any]]] = []
    for path in eval_dir.glob(f"{prefix}*.json"):
        stats = json.loads(path.read_text(encoding="utf-8"))
        at_steps = _eval_training_steps(stats, path=path, timeline=timeline)
        if at_steps <= 0 or at_steps < floor:
            continue
        rows.append((at_steps, stats))
    rows.sort(key=lambda row: row[0])
    return rows


def _latest_json(eval_dir: Path, prefix: str) -> tuple[Path | None, dict[str, Any] | None]:
    best_path: Path | None = None
    best_mtime = -1.0
    for path in eval_dir.glob(f"{prefix}*.json"):
        mtime = path.stat().st_mtime
        if mtime > best_mtime:
            best_mtime = mtime
            best_path = path
    if best_path is None:
        return None, None
    return best_path, json.loads(best_path.read_text(encoding="utf-8"))


def _latest_json_before(
    eval_dir: Path,
    prefix: str,
    before: float,
) -> tuple[Path | None, dict[str, Any] | None]:
    best_path: Path | None = None
    best_mtime = -1.0
    for path in eval_dir.glob(f"{prefix}*.json"):
        mtime = path.stat().st_mtime
        if mtime < before and mtime > best_mtime:
            best_mtime = mtime
            best_path = path
    if best_path is None:
        return None, None
    return best_path, json.loads(best_path.read_text(encoding="utf-8"))


_EVAL_DIFFICULTY_RE = re.compile(r"-d(\d+)_")


def _scenario_eval_difficulty(path: Path | None, stats: dict[str, Any] | None) -> int | None:
    """Difficulty recorded for a scenario eval artifact (JSON field, else filename)."""
    if stats is not None:
        raw = stats.get("scenario_difficulty")
        if raw is not None:
            try:
                return int(raw)
            except (TypeError, ValueError):
                pass
    if path is not None:
        match = _EVAL_DIFFICULTY_RE.search(path.name)
        if match:
            return int(match.group(1))
    return None


def _latest_scenario_eval(
    eval_dir: Path,
    scenario: str,
) -> tuple[Path | None, dict[str, Any] | None]:
    """Newest eval JSON for ``scenario``, regardless of difficulty."""
    slug = scenario.lower()
    best_path: Path | None = None
    best_mtime = -1.0
    for path in eval_dir.glob(f"eval_vs-lookahead-scenario-{slug}-*.json"):
        mtime = path.stat().st_mtime
        if mtime > best_mtime:
            best_mtime = mtime
            best_path = path
    if best_path is None:
        return None, None
    return best_path, json.loads(best_path.read_text(encoding="utf-8"))


def _training_steps(checkpoint_dir: Path) -> int:
    if not checkpoint_dir.is_dir():
        return 0
    # final.zip is saved every train segment; *_steps.zip names lag by up to STEP_CHECKPOINT_FREQ.
    from_final = _training_steps_from_final_zip(checkpoint_dir / "maskable_ppo_selfplay_final.zip")
    if from_final > 0:
        return from_final
    best = 0
    for path in checkpoint_dir.glob("maskable_ppo_selfplay_*_steps.zip"):
        match = _STEPS_RE.search(path.name)
        if match:
            best = max(best, int(match.group(1)))
    return best


def _training_steps_from_final_zip(zip_path: Path) -> int:
    if not zip_path.is_file():
        return 0
    try:
        from stable_baselines3.common.save_util import load_from_zip_file

        data, _params, _pytorch = load_from_zip_file(zip_path)
    except Exception:
        return 0
    if not isinstance(data, dict):
        return 0
    raw = data.get("num_timesteps")
    try:
        return int(raw) if raw is not None else 0
    except (TypeError, ValueError):
        return 0


def _fmt_steps(steps: int) -> str:
    if steps >= 1_000_000:
        return f"{steps / 1_000_000:.1f}M"
    if steps >= 10_000:
        return f"{steps // 1_000}k"
    if steps > 0:
        return str(steps)
    return "—"


def _pct(value: float | None) -> str:
    if value is None:
        return "—"
    return f"{100 * value:.0f}%"


def _pp_delta(before: float, after: float) -> str:
    delta_pp = 100 * (after - before)
    sign = "+" if delta_pp >= 0 else ""
    return f"{sign}{delta_pp:.0f}pp"


def _num_delta(before: float, after: float, *, digits: int = 1) -> str:
    delta = after - before
    sign = "+" if delta >= 0 else ""
    return f"{sign}{delta:.{digits}f}"


def _fmt_eval_block(label: str, stats: dict[str, Any]) -> list[str]:
    wins = int(stats.get("wins") or 0)
    losses = int(stats.get("losses") or 0)
    draws = int(stats.get("draws") or 0)
    steps_bit = ""
    raw_steps = stats.get("training_steps")
    if raw_steps is not None:
        try:
            steps_bit = f"  training_steps={int(raw_steps)}"
        except (TypeError, ValueError):
            pass
    return [
        f"  vs {label}:{steps_bit}",
        (
            f"    winrate={_pct(stats.get('winrate'))}  wins={wins} losses={losses} draws={draws}  "
            f"win_by_4_rate={_pct(stats.get('win_by_4_rate'))}"
        ),
        (
            f"    score_us={float(stats.get('score_us') or 0):.1f} "
            f"score_them={float(stats.get('score_them') or 0):.1f} "
            f"score_diff={float(stats.get('score_diff') or 0):+.1f}  "
            f"td_rate={_pct(stats.get('td_rate'))}  "
            f"touchdowns={float(stats.get('touchdowns') or 0):.2f} "
            f"touchdowns_them={float(stats.get('touchdowns_them') or 0):.2f}"
        ),
        (
            f"    ball_rate_us={_pct(stats.get('ball_rate_us'))} "
            f"ball_rate_them={_pct(stats.get('ball_rate_them'))}  "
            f"ball_opp_half_rate_us={_pct(stats.get('ball_opp_half_rate_us'))}  "
            f"ball_proximity_us={float(stats.get('ball_proximity_us') or 0):.2f}"
        ),
        (
            f"    truncation_rate={_pct(stats.get('truncation_rate'))}  "
            f"submit_error_rate={_pct(stats.get('submit_error_rate'))}  "
            f"turns={float(stats.get('turns') or 0):.1f} "
            f"total_actions={float(stats.get('total_actions') or 0):.1f}"
        ),
    ]


# First N games from a failing scenario eval JSON (``--verbose``).
_SCENARIO_FAIL_EVAL_GAMES = 10


def _fmt_scenario_fail_eval_games(target: TrainTarget) -> str | None:
    """First 10 train-format game lines for active drills whose latest eval failed.

    Active = same set as the train sandwich: ``scenarios_for_cycle`` with next-cycle
    train budget > 0 (healthy d5 skipped; soft-d5 / climbers included).
    """
    catalog = require_catalog()
    eval_dir = target.eval_dir()
    if not eval_dir.is_dir():
        return None
    levels = target.load_levels()
    gate_failures = load_gate_failures(path_for_gate_failures(target.save_stem()))
    drill_stats = target.load_drill_stats_cumulative()
    scenario_names = catalog.names()
    game_phase = game_phase_unlocked(target.checkpoint_key)
    proxies = catalog.proxy_sources()
    active = [
        name
        for name in scenarios_for_cycle(levels, scenario_names, gate_failures)
        if name not in proxies
        and scenario_train_budget_units(
            name,
            levels,
            scenario_names,
            game_phase=game_phase,
            gate_failures=gate_failures,
            drill_stats=drill_stats,
        )
        > 0
    ]
    lines = [
        f"  failed scenario evals (active drills; first {_SCENARIO_FAIL_EVAL_GAMES} "
        f"games from latest eval_*.json):"
    ]
    found = False
    for scenario in active:
        level = get_difficulty(levels, scenario)
        _path, stats = _latest_scenario_eval(eval_dir, scenario)
        if stats is None:
            continue
        eval_d = _scenario_eval_difficulty(_path, stats)
        # Skip just-promoted: latest artifact is a pass at the previous d.
        if eval_d is None or eval_d != level:
            continue
        episodes_n = int(stats.get("episodes") or 0)
        wins = int(stats.get("wins") or 0)
        if episodes_n <= 0 or wins >= eval_pass_min_wins(episodes_n):
            continue
        raw = stats.get("episodes_raw")
        if not isinstance(raw, list) or not raw:
            continue
        games = [ep for ep in raw if isinstance(ep, dict)][:_SCENARIO_FAIL_EVAL_GAMES]
        if not games:
            continue
        found = True
        short = catalog.short_name(scenario)
        d_bit = f"d{eval_d}"
        losses = int(stats.get("losses") or 0)
        draws = int(stats.get("draws") or 0)
        steps = stats.get("training_steps")
        steps_bit = f"  training_steps={int(steps)}" if steps is not None else ""
        lines.append(
            f"  {short} {d_bit}  W{wins}/L{losses}/D{draws} ({episodes_n} ep){steps_bit}  "
            f"games 1–{len(games)}:"
        )
        for index, episode in enumerate(games, start=1):
            # Same stdout as train after-eval (expected= / turn1= / ball_opp).
            block = format_eval_game_line(
                episode,
                game_num=index,
                games=episodes_n,
                truncated=bool(episode.get("truncated")),
                scenario=scenario,
            )
            lines.append(block)
    if not found:
        return None
    return "\n".join(lines)


def _fmt_trend(
    history: list[tuple[int, dict[str, Any]]],
    *,
    opponent: str,
    trend_steps: int,
    current_steps: int,
) -> str | None:
    if len(history) < 2:
        return None
    first_steps, first = history[0]
    last_steps, last = history[-1]
    win_before = float(first["winrate"])
    win_after = float(last["winrate"])
    diff_before = float(first["score_diff"])
    diff_after = float(last["score_diff"])
    floor = max(0, current_steps - trend_steps)
    window = (
        f"last {_fmt_steps(trend_steps)} steps "
        f"({_fmt_steps(floor)}→{_fmt_steps(current_steps)}, {len(history)} eval_vs-{opponent.lower()})"
    )
    return (
        f"  trend {window}: "
        f"winrate {_pct(win_before)}→{_pct(win_after)} ({_pp_delta(win_before, win_after)})  "
        f"score_diff {diff_before:+.1f}→{diff_after:+.1f} ({_num_delta(diff_before, diff_after)})  "
        f"@ steps {_fmt_steps(first_steps)}→{_fmt_steps(last_steps)}"
    )


def _suppress_tensorboard_info_logs() -> None:
    logging.getLogger("tensorboard").setLevel(logging.WARNING)


def _scalar_from_summary_value(value: Any) -> float | None:
    """SB3 recent logs use ``tensor`` scalars; older files may use ``simple_value``."""
    if value.HasField("simple_value"):
        return float(value.simple_value)
    if value.HasField("tensor"):
        tensor = value.tensor
        if tensor.float_val:
            return float(tensor.float_val[0])
        if tensor.double_val:
            return float(tensor.double_val[0])
    return None


def _tb_event_files(checkpoint_key: str) -> list[Path]:
    root = runs_dir(checkpoint_key)
    files: list[Path] = []
    subdirs = [path for path in root.glob("MaskablePPO_*") if path.is_dir()]
    if not subdirs:
        fallback = root / "MaskablePPO_0"
        if fallback.is_dir():
            subdirs = [fallback]
    for subdir in subdirs:
        files.extend(subdir.glob("events.out.tfevents.*"))
    files.sort(key=lambda path: path.stat().st_mtime)
    return files


def _tb_train_series_ready(series: dict[str, list[tuple[int, float]]]) -> bool:
    if any(not series[tag] for tag in _TB_SCALAR_TAGS):
        return False
    return len(series["train/entropy_loss"]) >= _TB_MIN_ENTROPY_POINTS


def _load_tb_scalar_series(checkpoint_key: str) -> dict[str, list[tuple[int, float]]]:
    if checkpoint_key in _TB_SCALAR_CACHE:
        return _TB_SCALAR_CACHE[checkpoint_key]
    empty: dict[str, list[tuple[int, float]]] = {}
    try:
        from tensorboard.backend.event_processing.event_file_loader import EventFileLoader
    except ImportError:
        _TB_SCALAR_CACHE[checkpoint_key] = empty
        return empty
    paths = _tb_event_files(checkpoint_key)
    if not paths:
        _TB_SCALAR_CACHE[checkpoint_key] = empty
        return empty
    series: dict[str, list[tuple[int, float]]] = {tag: [] for tag in _TB_SCALAR_TAGS}
    scanned = 0
    for path in reversed(paths):
        scanned += 1
        for event in EventFileLoader(str(path)).Load():
            if not event.summary.value:
                continue
            step = event.step
            for value in event.summary.value:
                tag = value.tag
                if tag not in _TB_SCALAR_TAGS:
                    continue
                scalar = _scalar_from_summary_value(value)
                if scalar is not None:
                    series[tag].append((step, scalar))
        if _tb_train_series_ready(series):
            break
        if scanned >= _TB_MAX_EVENT_FILES:
            break
    for tag, rows in series.items():
        rows.sort(key=lambda row: row[0])
        if len(rows) > _TB_SCALAR_GUIDANCE:
            series[tag] = rows[-_TB_SCALAR_GUIDANCE:]
    _TB_SCALAR_CACHE[checkpoint_key] = series
    return series


def _read_tb_scalars(
    checkpoint_key: str,
    tag: str,
    *,
    since_step: int | None = None,
) -> list[tuple[int, float]]:
    events = _load_tb_scalar_series(checkpoint_key).get(tag, [])
    if since_step is not None:
        events = [event for event in events if event[0] >= since_step]
    return events


def _tb_trend(values: list[float]) -> str:
    if len(values) < 2:
        return "flat"
    chunk = max(1, len(values) // 4)
    early = sum(values[:chunk]) / len(values[:chunk])
    late = sum(values[-chunk:]) / len(values[-chunk:])
    delta = late - early
    if abs(delta) < 0.02:
        return "flat"
    return "↑" if delta > 0 else "↓"


def _tb_trend_with_qualifier(values: list[float], *, higher_is_better: bool) -> str:
    trend = _tb_trend(values)
    if trend == "flat":
        return "flat"
    good = (trend == "↑") if higher_is_better else (trend == "↓")
    return f"{trend} {'better' if good else 'worse'}"


def _policy_entropy_level(nats: float) -> str:
    if nats < 0.5:
        return "bad"
    if nats < 0.8:
        return "watch"
    if nats <= 2.5:
        return "ok"
    return "watch"


# PPO approx_kl oscillates hard across updates; a single low tick is not "no progress."
_APPROX_KL_LEVEL_WINDOW = 5


def _approx_kl_level(kl: float) -> str:
    # Too-low = under-updating (looks like "stable" but the policy is not moving).
    # STUCK! only for near-zero steps; the 1e-4…1e-3 band is watch (early/flat mesa noise).
    if kl < 0.0001:
        return "STUCK!"
    if kl < 0.001:
        return "watch"
    if kl <= 0.02:
        return "ok"
    if kl <= 0.05:
        return "watch"
    return "bad"


def _approx_kl_level_from_series(values: list[float]) -> str:
    """Grade from recent median so one sub-floor update does not shout STUCK!."""
    if not values:
        return "ok"
    window = values[-_APPROX_KL_LEVEL_WINDOW:]
    ordered = sorted(window)
    median = ordered[len(ordered) // 2]
    return _approx_kl_level(median)


def _explained_variance_level(value: float) -> str:
    if value < 0:
        return "bad"
    if value < 0.5:
        return "watch"
    return "ok"


def _value_loss_level(values: list[float]) -> str:
    if len(values) < 2:
        return "ok"
    latest = values[-1]
    median = sorted(values)[len(values) // 2]
    if median <= 0:
        return "ok"
    ratio = latest / median
    if ratio >= 3.0:
        return "bad"
    if ratio >= 1.75:
        return "watch"
    return "ok"


def _clip_fraction_level(value: float) -> str:
    # Near-zero alone is fine (updates inside the trust region). High = thrashing the clip.
    # Corroborate dead updates via approx_kl STUCK, not a second false alarm here.
    if value <= 0.05:
        return "ok"
    if value <= 0.2:
        return "watch"
    return "bad"


def _tb_scalar_cell(
    checkpoint_key: str,
    tag: str,
    *,
    since_step: int | None = None,
    digits: int = 2,
    higher_is_better: bool,
    level: Callable[[list[float]], str],
) -> str:
    series = _read_tb_scalars(checkpoint_key, tag, since_step=since_step)
    if not series:
        return "—"
    values = [value for _step, value in series]
    trend = _tb_trend_with_qualifier(values, higher_is_better=higher_is_better)
    return f"{values[-1]:.{digits}f} {level(values)} ({trend})"


def _train_health_cells(checkpoint_key: str, *, trend_steps: int) -> tuple[str, str, str, str, str, str]:
    entropy_all = _read_tb_scalars(checkpoint_key, "train/entropy_loss")
    if not entropy_all:
        return ("—", "—", "—", "—", "—", "—")
    current_step = entropy_all[-1][0]
    since_step = max(0, current_step - trend_steps)
    entropy = _read_tb_scalars(checkpoint_key, "train/entropy_loss", since_step=since_step)
    kl = _read_tb_scalars(checkpoint_key, "train/approx_kl")
    entropy_loss_vals = [value for _step, value in entropy]
    latest_entropy_loss = entropy_loss_vals[-1]
    policy_entropy = -latest_entropy_loss
    policy_entropy_vals = [-value for value in entropy_loss_vals]
    entropy_trend = _tb_trend(policy_entropy_vals)
    entropy_note = {
        "↑": "exploring more",
        "↓": "tightening (less random)",
        "flat": "stable randomness",
    }[entropy_trend]
    entropy_level = _policy_entropy_level(policy_entropy)
    kl_bit = "—"
    if kl:
        kl_values = [value for _step, value in kl]
        # Show latest; grade from recent median (see _approx_kl_level_from_series).
        kl_bit = f"{kl_values[-1]:.4f} {_approx_kl_level_from_series(kl_values)}"
    return (
        f"≈{policy_entropy:.2f} nats {entropy_level}",
        f"entropy_loss={latest_entropy_loss:.3f} ({entropy_note}, {entropy_level})",
        kl_bit,
        _tb_scalar_cell(
            checkpoint_key,
            "train/explained_variance",
            since_step=since_step,
            higher_is_better=True,
            level=lambda values: _explained_variance_level(values[-1]),
        ),
        _tb_scalar_cell(
            checkpoint_key,
            "train/value_loss",
            since_step=since_step,
            higher_is_better=False,
            level=_value_loss_level,
        ),
        _tb_scalar_cell(
            checkpoint_key,
            "train/clip_fraction",
            since_step=since_step,
            digits=3,
            higher_is_better=False,
            level=lambda values: _clip_fraction_level(values[-1]),
        ),
    )


def _fmt_train_health(checkpoint_key: str, *, trend_steps: int) -> str | None:
    policy_entropy, entropy_bit, kl_bit, ev_bit, vl_bit, clip_bit = _train_health_cells(
        checkpoint_key, trend_steps=trend_steps
    )
    if policy_entropy == "—":
        return None
    return (
        f"  train (TensorBoard, last {_fmt_steps(trend_steps)} steps): policy_entropy{policy_entropy} "
        f"{entropy_bit}  approx_kl={kl_bit} explained_variance={ev_bit} "
        f"value_loss={vl_bit} clip_fraction={clip_bit}"
    )


def _train_health_rows(targets: list[TrainTarget], *, trend_steps: int) -> list[tuple[str, ...]]:
    return [
        (
            _fmt_steps(_training_steps(target.checkpoint_dir())),
            target.reward_profile,
            *_train_health_cells(target.checkpoint_key, trend_steps=trend_steps),
        )
        for target in targets
    ]


def _fmt_padded_table(
    title: str,
    header: tuple[str, ...],
    rows: list[tuple[str, ...]],
) -> str:
    if not rows:
        return ""
    widths = [len(label) for label in header]
    for row in rows:
        for index, cell in enumerate(row):
            widths[index] = max(widths[index], len(cell))
    lines = [title, "  " + "  ".join(f"{header[i]:<{widths[i]}}" for i in range(len(header)))]
    for row in rows:
        lines.append("  " + "  ".join(f"{row[i]:<{widths[i]}}" for i in range(len(row))))
    return "\n".join(lines)


def _fmt_train_health_table(targets: list[TrainTarget], *, trend_steps: int) -> str:
    rows = _train_health_rows(targets, trend_steps=trend_steps)
    if not rows:
        return ""
    return _fmt_padded_table(
        f"train (TensorBoard, last {_fmt_steps(trend_steps)} steps)",
        ("steps", "profile", "policy_entropy", "entropy", "approx_kl", "explained_variance", "value_loss", "clip_fraction"),
        rows,
    )


# STUCK! ends with punctuation — no trailing \b (\! is already non-word).
# STUCK! ends with punctuation — no trailing \b (\! is already non-word).
_SHOUT_LEVEL = re.compile(r"(STUCK!|\bbad\b)")

_TRAIN_HEALTH_FIELDS = (
    ("policy_entropy", 2),
    ("entropy", 3),
    ("approx_kl", 4),
    ("explained_variance", 5),
    ("value_loss", 6),
    ("clip_fraction", 7),
)

# Scenario shout thresholds — loud when curriculum is spinning without progress.
# Stuck thresholds live on scenario_curriculum (train peer-probes share them).


def _collect_train_warnings(targets: list[TrainTarget], *, trend_steps: int) -> list[str]:
    """Shout-worthy train-health lines (STUCK! / bad) for the warnings section."""
    warnings: list[str] = []
    for row in _train_health_rows(targets, trend_steps=trend_steps):
        profile = row[1]
        for field, index in _TRAIN_HEALTH_FIELDS:
            if index >= len(row):
                continue
            cell = row[index]
            match = _SHOUT_LEVEL.search(cell)
            if match is None:
                continue
            level = match.group(1)
            if level == "STUCK!" and field == "approx_kl":
                warnings.append(
                    f"STUCK! {profile} approx_kl={cell.split()[0]} — "
                    "PPO barely moving the policy; burning timesteps with no progress. "
                    "Do: bump learning_rate in train_self_play (default 1e-3); confirm clip_fraction "
                    "is also ~0 (corroborates under-updating); if scenario openings are flat, "
                    "spend budget on stuck drills (2×/8×) rather than more raw steps — "
                    "raising entropy alone explores without larger updates"
                )
            else:
                warnings.append(f"{level} {profile} {field}={cell}")
    return warnings


def _collect_scenario_warnings(targets: list[TrainTarget]) -> list[str]:
    """Shout when drills are not clearing gates / openings stay unranked after many cycles."""
    catalog = require_catalog()
    warnings: list[str] = []
    seen_keys: set[str] = set()
    for target in targets:
        if target.checkpoint_key in seen_keys:
            continue
        seen_keys.add(target.checkpoint_key)
        levels = target.load_levels()
        if not levels:
            continue
        gate_failures = load_gate_failures(path_for_gate_failures(target.save_stem()))
        last_eval = load_last_eval(path_for_last_eval(target.save_stem()))
        drill_stats = target.load_drill_stats_cumulative()
        eval_dir = target.eval_dir()
        claims_on = claims_unlocked(levels, catalog.names(), gate_failures)
        for scenario in catalog.names():
            if catalog.is_claim(scenario) and not claims_on:
                continue
            level = get_difficulty(levels, scenario)
            short = catalog.short_name(scenario)
            tag = f"{short} d{level} [{target.checkpoint_key}]"
            cycles = drill_stat_count(drill_stats, scenario, "cycles")
            demotes = drill_stat_count(drill_stats, scenario, "d5_demotes")
            fails = gate_failure_count(gate_failures, scenario, difficulty=level)

            # Proxy drills skip after-eval — their gate_fails (if any) are noise.
            if scenario in catalog.proxy_sources():
                continue

            if is_progress_waived(gate_failures, scenario, levels):
                warnings.append(
                    f"STUCK! {tag} progress waived "
                    f"(claim; ≥{GATE_PROGRESS_WAIVE_FAILURES} gate fails at this level) — "
                    "drill is no longer blocking gates but is not learning"
                )
            elif fails >= SCENARIO_GATE_FAIL_SHOUT:
                if catalog.is_claim(scenario):
                    waive_bit = f"; claims auto-waive at {GATE_PROGRESS_WAIVE_FAILURES}"
                else:
                    waive_bit = "; boards never auto-waive"
                warnings.append(
                    f"STUCK! {tag} gate_fails={fails} at current level "
                    f"(shout ≥{SCENARIO_GATE_FAIL_SHOUT}{waive_bit}) — "
                    "burning scenario train without clearing"
                )

            if demotes >= SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD:
                warnings.append(
                    f"STUCK! {tag} d5_demotes={demotes} "
                    f"(≥{SCENARIO_TRAIN_HIGH_DEMOTE_THRESHOLD}) — repeatedly falling off d5"
                )

            opening = last_eval_metrics(last_eval, scenario, level)
            eval_path, stats = _latest_scenario_eval(eval_dir, scenario)
            eval_d = _scenario_eval_difficulty(eval_path, stats)
            if (
                stats is not None
                and eval_d == level
                and cycles >= SCENARIO_CYCLES_BEFORE_DRILL_STUCK
                and level < MAX_DIFFICULTY
            ):
                wins = int(stats.get("wins") or 0)
                games = int(stats.get("episodes") or 0)
                if games > 0 and wins < eval_pass_min_wins(games):
                    warnings.append(
                        f"STUCK! {tag} drill_rate={_pct(float(stats['winrate']))} "
                        f"W{wins}/{games} after {cycles} cycles — not reaching pass bar"
                    )

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
                and level < MAX_DIFFICULTY
            ):
                warnings.append(
                    f"STUCK! {tag} expected_best_rank={rank:.1f} after {cycles} cycles — "
                    "opening policy still not ranking the expected row"
                )
    return warnings


def _collect_all_warnings(targets: list[TrainTarget], *, trend_steps: int) -> list[str]:
    return [
        *_collect_train_warnings(targets, trend_steps=trend_steps),
        *_collect_scenario_warnings(targets),
    ]


def _fmt_warnings_section(warnings: list[str]) -> str:
    if not warnings:
        return ""
    lines = ["warnings"]
    for warning in warnings:
        lines.append(f"  ! {warning}")
    return "\n".join(lines)


def format_train_warnings_section(
    profile: str = "all",
    *,
    trend_steps: int | None = None,
) -> str:
    """Public entry for interrupt / CLI — resolve summary targets and format shouts."""
    steps = _DEFAULT_TREND_STEPS if trend_steps is None else trend_steps
    targets = resolve_targets(profile, purpose="summary")
    if not targets:
        return ""
    return _fmt_warnings_section(_collect_all_warnings(targets, trend_steps=steps))


def _fmt_model_config_table() -> str:
    header = (
        "profile",
        "lr",
        "ent_coef",
        "vf_coef",
        "n_epochs",
        "clip",
        "target_kl",
        "n_envs×steps",
        "batch",
        "shaping (train)",
    )
    rollout = f"{DEFAULT_N_ENVS}×{DEFAULT_N_STEPS}"
    target_kl_bit = "none" if TARGET_KL is None else f"{TARGET_KL:g}"
    rows: list[tuple[str, ...]] = [
        (
            "default",
            f"{_DEFAULT_LEARNING_RATE:g}",
            f"{DEFAULT_ENT_COEF:g}",
            f"{DEFAULT_VF_COEF:g}",
            str(N_EPOCHS),
            str(CLIP_RANGE),
            target_kl_bit,
            rollout,
            str(DEFAULT_BATCH_SIZE),
            _PROFILE_BLURB["baseline"],
        ),
    ]
    for profile in SHAPED_PROFILES:
        rows.append(
            (
                profile,
                "—",
                "—",
                "—",
                "—",
                "—",
                "—",
                "—",
                "—",
                _PROFILE_BLURB[profile],
            )
        )
    return _fmt_padded_table("config (PPO shared; profile rows = shaping only)", header, rows)


def _compact_column_guide(*, trend_steps: int) -> str:
    window = _fmt_steps(trend_steps)
    return "\n".join(
        [
            f"Train column guide — optimizer health from the last {window} PPO steps (parallel self-play batches, not eval games):",
            "  Level tags (in the table beside each value): ok = healthy band, watch = borderline, bad = concerning, STUCK! = no progress (shouts).",
            "  Trend tags (↑/↓/flat, better/worse) compare the last quarter of the window to the first.",
            "  steps",
            "    Total environment actions this checkpoint has been trained on (max over saved checkpoint zips).",
            "    One step = one submitted move in a training game.",
            "  profile",
            "    Which model variant: baseline (win/loss only), general / spread / td / defense (extra reward shaping), or scenario_b (frozen baseline snapshot at profiles split).",
            "  policy_entropy",
            "    How fan-out the move picker is over legal actions — higher means more random/exploratory, lower means it mostly repeats its favorite moves.",
            "    Reported in nats (information units; think bits but log base e).",
            "    Levels: ok 0.8–2.5 nats; watch 0.5–0.8 or above 2.5; bad below 0.5 (nearly deterministic).",
            "  entropy",
            "    Raw PPO entropy_loss (negative of policy_entropy above) plus a word trend over the window (same ↑/↓/flat logic as other columns, shown as text).",
            "    Level uses the same bands as policy_entropy.",
            "    exploring more (↑): good when stuck or early; watch if eval winrate is flat while this keeps rising.",
            "    tightening (↓): good when the policy is converging on wins; bad if eval collapses while randomness drops.",
            "    stable randomness (flat): usually fine.",
            "  approx_kl",
            "    Mean policy change per training update (Kullback–Leibler divergence).",
            "    Levels (recent median of last 5 updates): STUCK! below 0.0001 (near-zero steps); "
            "watch 0.0001–0.001 (anemic) or 0.02–0.05; ok 0.001–0.02; bad above 0.05. "
            "Cell shows the latest value. STUCK! → bump LR / focus stuck drills; see warnings text.",
            "  explained_variance",
            "    Critic quality: a separate value head predicts future score from each board state.",
            "    This is how well those predictions match actual returns (1 = perfect, 0 = no better than guessing the average, negative = worse than that).",
            "    Levels: ok at 0.5+; watch 0–0.5; bad below 0.",
            "    ↑ better; ↓ worse; flat OK if the level is already strong.",
            "  value_loss",
            "    Raw training error for that value head (MSE-style loss on predicted vs actual returns).",
            "    Absolute scale varies with reward normalization — level compares the latest value to the window median (ok near median, watch ~1.75×, bad ~3×+).",
            "    ↓ better; ↑ worse; flat OK.",
            "  clip_fraction",
            "    Fraction of policy updates PPO clipped because the step would have changed the policy too much (0–1).",
            "    Levels: ok up to 0.05; watch 0.05–0.2; bad above 0.2 (optimizer often hitting the clip cap).",
            "    Near 0 = updates inside the trust region (fine alone; with approx_kl STUCK! it corroborates under-updating).",
            "    ↓ better; ↑ worse; flat OK at low values.",
            "",
        ]
    )


def _legend() -> str:
    return "\n".join(
        [
            "Field glossary (names match eval JSON / TensorBoard):",
            "  steps                  PPO env timesteps trained (checkpoint max)",
            "  training_steps         PPO timesteps when this eval ran (in eval JSON)",
            "  winrate                wins / decided games (eval, shaping off; soft-mask bias off unless --eval-episode-mode TRAIN)",
            "  wins / losses / draws  raw full-game outcomes",
            "  win_by_4_rate          fraction winning by ≥4 points",
            "  score_us / score_them  mean points per game",
            "  score_diff             mean score_us − score_them",
            "  td_rate                games with ≥1 our touchdown",
            "  touchdowns / _them     mean TDs scored per game",
            "  ball_rate_us           turn-start possession when we act",
            "  ball_rate_them         turn-start possession when they act",
            "  ball_opp_half_rate_us  our carries past midfield",
            "  ball_proximity_us      carrier proximity to scoring end zone (0–1)",
            "  truncation_rate        games hitting 500-action cap",
            "  submit_error_rate      failed HTTP submits",
            "  turns / total_actions  match length + policy submits",
            "  drill_rate             own-train winrate trail (last ≤4 before/after; (b)/(a) tags; peer probes omitted)",
            "  W/L/D on drill rows      decided scenario episodes (DISTRACTED → D)",
            "  expected: rank→…→rank (n=pref/legal) p= logit_gap=   last ≤4 train-time eval "
            "best PASS ranks (oldest→newest; before+after); n= preferred/legal (latest only); "
            "p= best expected-row softmax; logit_gap= logit(argmax)−logit(best PASS) — not p−first_p",
            "  first_p                mean opening chosen-move p (can exceed expected p when argmax ≠ lesson)",
            "  d0–d5                 curriculum level (active phase checkpoint); "
            "dN↑/↓ when that level differs from latest eval @d (promoted/demoted since)",
            "  →                      live train focus (scenario_focus.json; pid still alive)",
            "  (optional)             gatesProgress=false — trained/eval'd, not a curriculum gate (oracle or placement not trusted yet)",
            "  (no scenario eval; tracks X)  difficultyProxy drill — train only; after-eval/gates ride on X",
            "  (waived)               claim hit fail threshold — temporarily excluded from gates "
            f"(claims only; threshold {GATE_PROGRESS_WAIVE_FAILURES}; boards never waive)",
            "  gate_fails             current-difficulty / all-time imperfect after-evals (omitted for proxy drills)",
            "  failed scenario evals  --verbose: first 10 train-format game lines "
            "(expected= / turn1= / ball_opp) for active drills (same as train sandwich) "
            "whose latest eval_*.json at the current curriculum d failed "
            "(skips just-promoted: pass at previous d)",
            f"  train 1×/2×/8×         next-cycle PPO budget multiplier (gating drills; see criteria under drill lists)",
            "  cycles                 train sandwich units cumulative (scenario_a→current; 2× gate fails / behind pack; 8× fail at pack min / below-d4 opening / ≥5 demotes after board d4+)",
            "  d5_demotes             times this drill was demoted from d5",
            "  policy_entropy         −train/entropy_loss — high = exploratory, low = peaked policy",
            "  entropy                entropy_loss plus trend over the train window",
            "  approx_kl              PPO policy change per update "
            "(profile board: TB series; drill rows: last train-segment update before after-eval; "
            "<1e-4 STUCK!; 1e-4…1e-3 watch; too large = thrashing)"
            "  explained_variance     critic: value-head prediction fit (−1..1; higher = better)",
            "  value_loss             critic: raw return-prediction training loss (lower = better)",
            "  clip_fraction          fraction of PPO updates clipped (high = trust-region limited)",
            "  trend                  eval + train deltas over last N PPO training_steps",
            "  summary steps          checkpoint max PPO timesteps per profile tree",
            "  summary scenario_b     last baseline eval before profiles split (fixed at fork)",
            "",
        ]
    )


def _gate_exclusion_bit(
    scenario: str,
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]],
) -> str:
    """Marker when a drill does not count toward claim unlock / phase / full-game gates."""
    catalog = require_catalog()
    if not catalog.gates_progress(scenario):
        return " (optional)"
    if is_progress_waived(gate_failures, scenario, levels):
        return " (waived)"
    return ""


def _curriculum_note(
    levels: dict[str, int],
    gate_failures: dict[str, dict[str, int]] | None = None,
    *,
    game_phase: bool,
) -> str:
    failures = gate_failures if gate_failures is not None else {}
    catalog = require_catalog()
    scenarios = catalog.names()
    required = [name for name in scenarios if catalog.gates_progress(name)]
    mastered_required = sum(
        1 for name in required if get_difficulty(levels, name) >= MAX_DIFFICULTY
    )
    climbing = sum(1 for name in scenarios if get_difficulty(levels, name) > 0)
    if not claims_unlocked(levels, scenarios, failures):
        # Gate set only — optional / waived board drills are not in the claim-unlock bar.
        board = board_scenarios_in_catalog(scenarios, levels, failures)
        board_at_claim = sum(
            1 for name in board if get_difficulty(levels, name) >= CLAIM_UNLOCK_BOARD_DIFFICULTY
        )
        return (
            f"curriculum board-only {board_at_claim}/{len(board)} at "
            f"d{CLAIM_UNLOCK_BOARD_DIFFICULTY}+ — claims deferred (scenario_a)"
        )
    if game_phase:
        if all_scenarios_mastered(levels, scenarios):
            return (
                f"curriculum {mastered_required}/{len(required)} at d{MAX_DIFFICULTY} — "
                f"game phase; full-game train on; soft-d5 maintenance"
            )
        split_note = "profile stem" if profiles_split_occurred() else "scenario_b unlocked"
        return (
            f"curriculum game phase ({split_note}); "
            f"{mastered_required}/{len(required)} gating at d{MAX_DIFFICULTY} — "
            f"full-game train on; straggler + soft-d5 scenario train"
        )
    if climbing:
        return (
            f"curriculum combined {mastered_required}/{len(required)} at d{MAX_DIFFICULTY} — "
            f"full-game train off until gating drills d{MAX_DIFFICULTY} (scenario_b)"
        )
    return (
        f"curriculum fresh — claims after board d{CLAIM_UNLOCK_BOARD_DIFFICULTY}; "
        f"full-game after gating drills d{MAX_DIFFICULTY}"
    )


def _fmt_wld_summary(stats: dict[str, Any] | None, *, arrow: str = "") -> str:
    if stats is None:
        return "—"
    wins = int(stats["wins"])
    losses = int(stats["losses"])
    draws = int(stats["draws"])
    return f"W{wins}/L{losses}/D{draws} ({_pct(stats['winrate'])}{arrow})"


def _winrate_arrow(before: float, after: float) -> str:
    delta = after - before
    if abs(delta) < _WINRATE_ARROW_EPS:
        return "→"
    return "↑" if delta > 0 else "↓"


def _winrate_arrow_for_prefix(
    eval_dir: Path,
    prefix: str,
    *,
    before: float | None = None,
) -> str:
    """↑/↓/→ vs the previous eval (by mtime)."""
    if not eval_dir.is_dir():
        return ""
    dated: list[tuple[float, Path]] = []
    for path in eval_dir.glob(f"{prefix}*.json"):
        mtime = path.stat().st_mtime
        if before is not None and mtime >= before:
            continue
        dated.append((mtime, path))
    if len(dated) < 2:
        return ""
    dated.sort(key=lambda row: row[0])
    try:
        before_rate = float(json.loads(dated[-2][1].read_text(encoding="utf-8"))["winrate"])
        after_rate = float(json.loads(dated[-1][1].read_text(encoding="utf-8"))["winrate"])
    except (KeyError, TypeError, ValueError, json.JSONDecodeError, OSError):
        return ""
    return _winrate_arrow(before_rate, after_rate)


def _la_trend_marks(current_steps: int, *, window: int, stride: int) -> list[int]:
    """Step marks from ``current - window`` … ``current``, stride apart (anchor on current)."""
    if current_steps <= 0 or window <= 0 or stride <= 0:
        return []
    n = window // stride
    marks = [current_steps - (n - i) * stride for i in range(n + 1)]
    return [mark for mark in marks if mark >= 0]


def _winrate_at_or_before(
    history: list[tuple[int, float]],
    mark: int,
) -> float | None:
    best: float | None = None
    for steps, winrate in history:
        if steps <= mark:
            best = winrate
        else:
            break
    return best


def _fmt_la_pct_series(rates: list[float | None]) -> str:
    if not rates or all(rate is None for rate in rates):
        return "—"
    parts: list[str] = []
    for rate in rates:
        parts.append("—" if rate is None else f"{100 * rate:.0f}")
    return " → ".join(parts) + "%"


def _lookahead_trend_row(
    target: TrainTarget,
    *,
    window: int,
    stride: int,
) -> tuple[str, str] | None:
    steps = _training_steps(target.checkpoint_dir())
    eval_dir = target.eval_dir()
    # Load one stride before the first mark so "at or before" has a prior eval.
    history_rows = _eval_history_by_steps(
        eval_dir,
        "eval_vs-lookahead_",
        checkpoint_dir=target.checkpoint_dir(),
        current_steps=steps,
        trend_steps=window + stride,
    )
    history = [
        (at_steps, float(stats["winrate"]))
        for at_steps, stats in history_rows
        if stats.get("winrate") is not None
    ]
    marks = _la_trend_marks(steps, window=window, stride=stride)
    if not marks:
        return None
    series = [_winrate_at_or_before(history, mark) for mark in marks]
    if all(rate is None for rate in series):
        return None
    return target.reward_profile, _fmt_la_pct_series(series)


def _fmt_lookahead_trend(
    targets: list[TrainTarget],
    *,
    window: int = _DEFAULT_LA_TREND_STEPS,
    stride: int = _DEFAULT_LA_TREND_STRIDE,
) -> str:
    rows: list[tuple[str, str]] = []
    for target in targets:
        row = _lookahead_trend_row(target, window=window, stride=stride)
        if row is not None:
            rows.append(row)
    if not rows:
        return ""
    name_width = max(len(name) for name, _series in rows)
    lines = [
        f"lookahead trend (last {_fmt_steps(window)} @ {_fmt_steps(stride)})",
    ]
    for name, series in rows:
        lines.append(f"  {name:<{name_width}}  {series}")
    return "\n".join(lines)


def _scenario_d5_total(levels: dict[str, int], scenarios: list[str]) -> str:
    at_d5 = sum(1 for name in scenarios if get_difficulty(levels, name) >= MAX_DIFFICULTY)
    return f"{at_d5}/{len(scenarios)}"


def _summary_opponent_headers() -> tuple[str, ...]:
    """Compact summary W/L/D columns — built-ins then ``FULL_GAME_ONNX_OPPONENT_PROFILES``."""
    return ("lookahead", "heuristic", *FULL_GAME_ONNX_OPPONENT_PROFILES)


def _summary_table_header() -> tuple[str, ...]:
    return ("profile", *_summary_opponent_headers(), "d5/total", "steps")


def _full_game_eval_prefixes() -> tuple[str, ...]:
    return (
        "eval_vs-lookahead_",
        "eval_vs-heuristic_",
        *(f"eval_vs-onnx-{name}_" for name in FULL_GAME_ONNX_OPPONENT_PROFILES),
    )


def _latest_full_game_eval_stats(
    eval_dir: Path,
    *,
    before: float | None = None,
) -> list[dict[str, Any] | None]:
    rows: list[dict[str, Any] | None] = []
    for prefix in _full_game_eval_prefixes():
        if before is None:
            _path, stats = _latest_json(eval_dir, prefix)
        else:
            _path, stats = _latest_json_before(eval_dir, prefix, before)
        rows.append(stats)
    return rows


def _profile_summary_row(target: TrainTarget) -> tuple[str, ...]:
    eval_dir = target.eval_dir()
    checkpoint_dir = target.checkpoint_dir()
    levels = target.load_levels()
    scenarios = require_catalog().names()
    eval_stats = _latest_full_game_eval_stats(eval_dir)
    arrows = [
        _winrate_arrow_for_prefix(eval_dir, prefix)
        for prefix in _full_game_eval_prefixes()
    ]
    # No checkpoint step count yet — fall back to training_steps stamped on latest evals.
    steps = _training_steps(checkpoint_dir)
    if steps <= 0:
        steps = _training_steps_from_eval(*eval_stats)
    return (
        target.reward_profile,
        *(_fmt_wld_summary(stats, arrow=arrow) for stats, arrow in zip(eval_stats, arrows)),
        _scenario_d5_total(levels, scenarios),
        _fmt_steps(steps),
    )


def _training_steps_from_eval(*stats: dict[str, Any] | None) -> int:
    best = 0
    for row in stats:
        if row is None:
            continue
        raw = row.get("training_steps")
        if raw is None:
            continue
        try:
            best = max(best, int(raw))
        except (TypeError, ValueError):
            pass
    return best


def _scenario_b_pre_split_row(split_at: float) -> tuple[str, ...] | None:
    # Reads pre-split full-game evals under evals/baseline/ (timestamp < split_at).
    # Do not wipe those JSON files or pipeline.json's split — this row depends on them.
    eval_dir = EVAL_DIR / "baseline"
    if not eval_dir.is_dir():
        return None
    eval_stats = _latest_full_game_eval_stats(eval_dir, before=split_at)
    if all(stats is None for stats in eval_stats):
        return None
    checkpoint_dir = long_running_stem(PHASE_SCENARIO_B).parent
    arrows = [
        _winrate_arrow_for_prefix(eval_dir, prefix, before=split_at)
        for prefix in _full_game_eval_prefixes()
    ]
    levels = load_levels(path_for_stem(long_running_stem(PHASE_SCENARIO_B)))
    scenarios = require_catalog().names()
    split = load_pipeline().split
    steps = (split.scenario_b_steps if split is not None else None) or 0
    if steps <= 0:
        steps = _training_steps_from_eval(*eval_stats)
    if steps <= 0:
        steps = _steps_at_time(_checkpoint_timeline(checkpoint_dir), split_at)
    return (
        "scenario_b",
        *(_fmt_wld_summary(stats, arrow=arrow) for stats, arrow in zip(eval_stats, arrows)),
        _scenario_d5_total(levels, scenarios),
        _fmt_steps(steps),
    )


def _placeholder_summary_row(name: str) -> tuple[str, ...]:
    # opponents + d5/total + steps
    n_dashes = len(_summary_opponent_headers()) + 2
    return (name, *("—" for _ in range(n_dashes)))


def _row_or_placeholder(
    name: str,
    row: tuple[str, ...] | None,
) -> tuple[str, ...]:
    return row if row is not None else _placeholder_summary_row(name)


def _export_summary_row() -> tuple[str, ...] | None:
    """Initial export / expert-seed slot — real row once we eval that freeze."""
    # TODO(ai): fill from export-freeze + dedicated eval (evals/export or seed eval).
    return None


def _scenario_a_summary_row() -> tuple[str, ...] | None:
    """Frozen scenario_a slot — real row once we eval/export that tree as a profile."""
    # TODO(ai): fill from checkpoints/scenario_a + evals/scenario_a after first-class freeze eval.
    return None


def _scenario_b_summary_row(split_at: float | None) -> tuple[str, ...] | None:
    """Frozen scenario_b slot — today the pre-split snapshot; later first-class freeze+eval."""
    if split_at is None:
        return None
    # TODO(ai): prefer first-class scenario_b export-freeze + evals/scenario_b when those exist;
    # then delete the pre-split evals/baseline timestamp fallback below.
    return _scenario_b_pre_split_row(split_at)


def _curriculum_prefix_rows(
    *,
    split_at: float | None,
) -> list[tuple[str, ...]]:
    """export → scenario_a → scenario_b ahead of live profiles; — only when data is missing."""
    return [
        _row_or_placeholder("export", _export_summary_row()),
        _row_or_placeholder("scenario_a", _scenario_a_summary_row()),
        _row_or_placeholder("scenario_b", _scenario_b_summary_row(split_at)),
    ]


def _bottom_summary_rows(targets: list[TrainTarget]) -> list[tuple[str, ...]]:
    rows: list[tuple[str, ...]] = []
    split = load_pipeline().split
    split_at = split.at if split is not None else None
    post_split = split_at is not None and any(
        target.reward_profile in SHAPED_PROFILES or target.checkpoint_key == "baseline"
        for target in targets
    )
    if post_split:
        rows.extend(_curriculum_prefix_rows(split_at=split_at))
    rows.extend(_profile_summary_row(target) for target in targets)
    return rows


def _fmt_profiles_bottom_summary(targets: list[TrainTarget]) -> str:
    rows = _bottom_summary_rows(targets)
    if not rows:
        return ""
    return _fmt_padded_table(
        "summary",
        _summary_table_header(),
        rows,
    )


def _fmt_train_multiplier_blocks(targets: list[TrainTarget]) -> str:
    """Next-cycle train × lines for compact summary; omit targets with nothing to train."""
    names = require_catalog().names()
    multi = len(targets) > 1
    blocks: list[str] = []
    for target in targets:
        lines = train_multiplier_summary_lines(
            target.load_levels(),
            names,
            load_gate_failures(path_for_gate_failures(target.save_stem())),
            target.load_drill_stats_cumulative(),
            game_phase=game_phase_unlocked(target.checkpoint_key),
        )
        if not lines:
            continue
        if multi:
            lines = [
                f"  {target.checkpoint_key} train multipliers (next cycle):",
                *lines[1:],
            ]
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)


def _fmt_scenario_drills(target: TrainTarget) -> str:
    """All curriculum drills for one checkpoint (always shown in scenario_a / scenario_b)."""
    catalog = require_catalog()
    scenarios = catalog.names()
    if not scenarios:
        return ""
    levels = target.load_levels()
    save_stem = target.save_stem()
    gate_failures = load_gate_failures(path_for_gate_failures(save_stem))
    last_eval = load_last_eval(path_for_last_eval(save_stem))
    drill_stats = target.load_drill_stats_cumulative()
    eval_dir = target.eval_dir()
    claims_on = claims_unlocked(levels, scenarios, gate_failures)
    name_width = max((len(catalog.short_name(name)) for name in scenarios), default=0)
    scenario_numbers = {name: num for num, name in enumerate(summary_scenario_order(catalog), start=1)}
    proxy_sources = catalog.proxy_sources()
    focus = load_live_focus(path_for_focus(save_stem))
    focus_scenario = focus["scenario"] if focus is not None else None
    lines = [
        "scenario drills (curriculum d · latest eval @d; --scenario N or name):",
    ]
    phases = (
        scenario_phase_order(catalog.board_names(), catalog),
        scenario_phase_order(catalog.claim_names(), catalog),
    )
    first_phase = True
    for phase in phases:
        if not phase:
            continue
        if not first_phase:
            lines.append("")
        first_phase = False
        for scenario in phase:
            level = get_difficulty(levels, scenario)
            short = f"{catalog.short_name(scenario):<{name_width}}"
            gate_bit = _gate_exclusion_bit(scenario, levels, gate_failures)
            cycles = drill_stat_count(drill_stats, scenario, "cycles")
            demotes = drill_stat_count(drill_stats, scenario, "d5_demotes")
            cycle_bit = f" cycles={cycles}" if cycles else ""
            demote_bit = f" d5_demotes={demotes}" if demotes else ""
            num = scenario_numbers[scenario]
            leader = f"→ {num:2d}  " if scenario == focus_scenario else f"  {num:2d}  "
            prefix = f"{leader}{short}  d{level}  "
            if catalog.is_claim(scenario) and not claims_on:
                lines.append(f"{prefix}— (deferred){cycle_bit}{demote_bit}{gate_bit}")
                continue
            # Proxy drills train only — after-eval + gate_fails ride on the difficulty target.
            if scenario in proxy_sources:
                proxy = catalog.short_name(catalog.curriculum_key(scenario))
                lines.append(
                    f"{prefix}— (no scenario eval; tracks {proxy})"
                    f"{cycle_bit}{demote_bit}{gate_bit}"
                )
                continue
            eval_path, stats = _latest_scenario_eval(eval_dir, scenario)
            if stats:
                wins = int(stats["wins"])
                losses = int(stats.get("losses", 0))
                draws = int(stats.get("draws", 0))
                games = int(stats["episodes"])
                eval_d = _scenario_eval_difficulty(eval_path, stats)
                stale = eval_d is not None and eval_d != level
                passed = (
                    games > 0
                    and wins >= eval_pass_min_wins(games)
                    and games >= wins
                )
                bump = " ↑" if passed and not stale and level < MAX_DIFFICULTY else ""
                stale_bit = " (stale)" if stale else ""
                fails = gate_failure_count(gate_failures, scenario, difficulty=level)
                fails_total = gate_failure_count(gate_failures, scenario)
                fail_bit = f" gate_fails={fails}/{fails_total}" if fails_total else ""
                opening_stats = last_eval_metrics(last_eval, scenario, level) or stats
                opening_prior = last_eval_prior_metrics(last_eval, scenario, level)
                rank_history = last_eval_rank_history(last_eval, scenario, level)
                winrate_trail = last_eval_winrate_trail(last_eval, scenario, level)
                headline_stats = {
                    **opening_stats,
                    "winrate": stats["winrate"],
                    "wins": wins,
                    "losses": losses,
                    "draws": draws,
                    "episodes": games,
                    "scenario_difficulty": eval_d,
                }
                headline = format_scenario_eval_headline(
                    short_name=short,
                    difficulty=level,
                    stats=headline_stats,
                    prior=opening_prior,
                    rank_history=rank_history or None,
                    winrate_trail=winrate_trail or None,
                    eval_difficulty=eval_d,
                )
                # leader has ``→ NN  `` / ``  NN  ``; headline starts with padded short name + dN.
                lines.append(
                    f"{leader}{headline}"
                    f"{bump}{stale_bit}"
                    f"{fail_bit}{cycle_bit}{demote_bit}{gate_bit}"
                )
            else:
                fails = gate_failure_count(gate_failures, scenario, difficulty=level)
                fails_total = gate_failure_count(gate_failures, scenario)
                fail_bit = f" gate_fails={fails}/{fails_total}" if fails_total else ""
                lines.append(
                    f"{prefix}— (no eval yet){fail_bit}{cycle_bit}{demote_bit}{gate_bit}"
                )

    game_phase = game_phase_unlocked(target.checkpoint_key)
    lines.append(f"  {_curriculum_note(levels, gate_failures, game_phase=game_phase)}")
    return "\n".join(lines)


def _fmt_compact_board(
    targets: list[TrainTarget],
    *,
    trend_steps: int,
    include_guide: bool = False,
) -> str:
    sections: list[str] = []
    if include_guide:
        sections.append(_compact_column_guide(trend_steps=trend_steps))
    # scenario_a / scenario_b: drill board right above config (always, not only --verbose).
    for target in targets:
        if target.phase in (PHASE_SCENARIO_A, PHASE_SCENARIO_B):
            drills = _fmt_scenario_drills(target)
            if drills:
                sections.append(drills)
    sections.append(_fmt_model_config_table())
    train = _fmt_train_health_table(targets, trend_steps=trend_steps)
    warnings = _fmt_warnings_section(_collect_all_warnings(targets, trend_steps=trend_steps))
    summary = _fmt_profiles_bottom_summary(targets)
    la_trend = _fmt_lookahead_trend(targets)
    # Warnings right under train health so STUCK!/bad are hard to miss.
    tables = "\n\n".join(part for part in (train, warnings, summary, la_trend) if part)
    if tables:
        sections.append(tables)
    multipliers = _fmt_train_multiplier_blocks(targets)
    if multipliers:
        sections.append(multipliers)
    return "\n\n".join(section for section in sections if section)


def _summarize_target(target: TrainTarget, *, trend_steps: int) -> str:
    profile = target.reward_profile
    checkpoint_key = target.checkpoint_key
    eval_dir = target.eval_dir()
    checkpoint_dir = target.checkpoint_dir()
    levels = target.load_levels()
    save_stem = target.save_stem()
    gate_failures = load_gate_failures(path_for_gate_failures(save_stem))
    drill_stats = target.load_drill_stats_cumulative()
    steps = _training_steps(checkpoint_dir)
    key_bit = f" [{checkpoint_key}]" if checkpoint_key != profile else ""

    lines = [
        f"{'─' * 72}",
        f"{profile:<9}{key_bit} steps={_fmt_steps(steps)}  ·  {_PROFILE_BLURB.get(profile, '')}",
    ]
    if target.deferral_note:
        lines.append(f"  note: {target.deferral_note}")

    train_line = _fmt_train_health(checkpoint_key, trend_steps=trend_steps)
    if train_line:
        lines.append(train_line)

    full_game_eval_prefixes: list[tuple[str, str]] = [
        ("eval_vs-lookahead_", "LOOKAHEAD"),
        ("eval_vs-heuristic_", "HEURISTIC"),
    ]
    for onnx_name in FULL_GAME_ONNX_OPPONENT_PROFILES:
        full_game_eval_prefixes.append((f"eval_vs-onnx-{onnx_name}_", f"ONNX {onnx_name}"))
    for prefix, label in full_game_eval_prefixes:
        _path, stats = _latest_json(eval_dir, prefix)
        if stats:
            lines.extend(_fmt_eval_block(label, stats))
        else:
            lines.append(f"  vs {label}: — (no eval yet)")

    la_history = _eval_history_by_steps(
        eval_dir,
        "eval_vs-lookahead_",
        checkpoint_dir=checkpoint_dir,
        current_steps=steps,
        trend_steps=trend_steps,
    )
    trend = _fmt_trend(
        la_history,
        opponent="lookahead",
        trend_steps=trend_steps,
        current_steps=steps,
    )
    if trend:
        lines.append(trend)

    # scenario_a / scenario_b drills live on the compact board (always). Verbose profiles
    # still list drills here so shaped-checkpoint detail stays self-contained.
    if target.phase not in (PHASE_SCENARIO_A, PHASE_SCENARIO_B):
        drills = _fmt_scenario_drills(target)
        if drills:
            lines.append(drills)
        catalog = require_catalog()
        scenarios = catalog.names()
        game_phase = game_phase_unlocked(checkpoint_key)
        lines.extend(
            train_multiplier_summary_lines(
                levels, scenarios, gate_failures, drill_stats, game_phase=game_phase
            )
        )
    fail_games = _fmt_scenario_fail_eval_games(target)
    if fail_games:
        lines.append(fail_games)
    return "\n".join(lines)


def _newest_eval_stamp() -> str:
    newest = 0.0
    for profile in REWARD_PROFILES:
        eval_dir = EVAL_DIR / profile
        if not eval_dir.is_dir():
            continue
        for path in eval_dir.glob("eval_*.json"):
            newest = max(newest, path.stat().st_mtime)
    if newest <= 0:
        return "no eval artifacts found"
    when = datetime.fromtimestamp(newest, tz=timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    return f"newest eval artifact {when}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--profile",
        default="all",
        choices=("all", *REWARD_PROFILES),
        help="One profile, or all with training steps (default: all)",
    )
    parser.add_argument("--no-legend", action="store_true", help="Skip column guide / field glossary")
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Full per-profile detail; default is train + summary tables only",
    )
    parser.add_argument(
        "--trend-steps",
        type=int,
        default=_DEFAULT_TREND_STEPS,
        help=f"PPO-step window for eval/train trends (default: {_DEFAULT_TREND_STEPS:,})",
    )
    args = parser.parse_args()

    _TB_SCALAR_CACHE.clear()
    _suppress_tensorboard_info_logs()

    cached = load_cached_catalog()
    if cached is None:
        raise SystemExit(
            "No scenario catalog cache at output/scenario_catalog.json — "
            "run ./train.sh or train_self_play once against a live backend"
        )
    set_catalog(cached)

    targets = resolve_targets(args.profile, purpose="summary")

    if not targets:
        print("(no resolvable targets)")
        return

    footer = "\n".join(phase_status_lines())

    if args.verbose:
        print("Goblins & Gridirons model summary")
        print(_newest_eval_stamp())
        if not args.no_legend:
            print()
            print(_legend())
        for target in targets:
            print(_summarize_target(target, trend_steps=args.trend_steps))
        compact = _fmt_compact_board(targets, trend_steps=args.trend_steps)
        if compact:
            print()
            print(compact)
        print()
        print(footer)
        return

    compact = _fmt_compact_board(
        targets,
        trend_steps=args.trend_steps,
        include_guide=not args.no_legend,
    )
    if compact:
        print(compact)
        print()
    print(footer)


if __name__ == "__main__":
    main()
