# Shared paths + ensure helpers for train.sh / start_over.sh.
# Source from ai/:  source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
#
# Artifact chain (each phase owns everything listed; start_over --phase X wipes X onward):
#   dump       expert/lookahead.jsonl.gz, expert/export-*.log
#   pack       expert/lookahead.npz
#   seed       expert/maskable_ppo_seed.zip
#   scenario_a checkpoints/scenario_a/, runs/scenario_a/
#   scenario_b checkpoints/scenario_b/, runs/scenario_b/
#              + shared scenario artifacts: scenario_catalog.json,
#                runs/train_eval_cycles.tsv, evals/baseline/ (pre-split + post-split baseline evals)
#   checkpoint checkpoints/{baseline,general,spread,td,defense}/, runs/{…}/,
#              evals/{general,spread,td,defense}/ (+ evals/baseline stays shared),
#              legacy root checkpoints/maskable_ppo_selfplay_final.zip
#
# After profiles split, scenario_a/scenario_b trees freeze; baseline is a real profile
# forked from scenario_b (same as general/…). Do not treat checkpoints/baseline as a
# disposable legacy alias for scenario_b.

: "${ROOT:=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
AI_ROOT="$ROOT/ai"
BACKEND_DIR="$ROOT/backend"
OUTPUT_DIR="$AI_ROOT/output"
EXPERT_DIR="$OUTPUT_DIR/expert"
JSONL="$EXPERT_DIR/lookahead.jsonl.gz"
NPZ="$EXPERT_DIR/lookahead.npz"
SEED_ZIP="$EXPERT_DIR/maskable_ppo_seed.zip"
CHECKPOINT_DIR="$OUTPUT_DIR/checkpoints"
RUNS_DIR="$OUTPUT_DIR/runs"
EVAL_DIR="$OUTPUT_DIR/evals"
SCENARIO_CATALOG="$OUTPUT_DIR/scenario_catalog.json"
CYCLE_LOG="$RUNS_DIR/train_eval_cycles.tsv"
LEGACY_ROOT_CHECKPOINT_ZIP="maskable_ppo_selfplay_final.zip"
SCENARIO_TRAINING_EVAL_PROFILE="baseline"
# Post-split unshaped profile tree (forked from scenario_b) — wiped with checkpoint phase.
BASELINE_CHECKPOINT_KEY="baseline"
BACKEND_PORT="${BACKEND_PORT:-18080}"
BASE_URL="${BASE_URL:-http://localhost:${BACKEND_PORT}}"
MVN="${MVN:-$(command -v mvnd 2>/dev/null || command -v mvn)}"
PYTHON="${PYTHON:-$AI_ROOT/.venv/bin/python}"
_LONG_RUNNING_STEM="maskable_ppo_selfplay_final"
# Pre-claim dump: 31000 games × 370/block ≈ 30162 board-drill games. Enum is 22 types now (1330/block);
# scale games so board-drill totals stay on that curve (every scenario gets the same 10 passes).
DUMP_GAMES="${DUMP_GAMES:-111352}"
DUMP_THREADS="${DUMP_THREADS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)}"

# Phases in dependency order (wipe phase N also wipes everything after).
PHASES=(dump pack seed scenario_a scenario_b checkpoint)

die() {
  echo "error: $*" >&2
  exit 1
}

log() {
  echo ""
  echo "==> $*"
}

ensure_python() {
  [[ -x "$PYTHON" ]] || die "missing $PYTHON — create the venv: cd ai && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"
}

ensure_mvn() {
  [[ -n "$MVN" ]] || die "mvn/mvnd not found on PATH"
}

require_dev_backend() {
  curl -sf "${BASE_URL}/health" >/dev/null 2>&1 \
    || die "backend not reachable at ${BASE_URL}/health — start ./dev.sh from repo root"
}

profile_names() {
  # Checkpoint wipe / CLI validate. Train/eval ``--profile all`` membership lives in
  # Python ``gridiron_ai.reward.profiles_for_all`` (baseline + general + spread today).
  local profile="${1:-all}"
  case "$profile" in
    all) printf '%s\n' baseline general spread td defense ;;
    baseline|general|spread|td|defense) printf '%s\n' "$profile" ;;
    *) die "unknown profile: $profile (baseline, general, spread, td, defense, or all)" ;;
  esac
}

# Shared CLI for ./train.sh, ./eval.sh, and train flags forwarded by ./start_over.sh.
train_cli_usage() {
  cat <<'EOF'
Usage (from ai/; backend must be up — ./dev.sh from repo root):

  ./train.sh
  ./train.sh --profile td
  ./train.sh --scenario MARKED_RUN
  ./train.sh --scenario TD_RUN --cycles 1

Options:
  --profile baseline|general|spread|td|defense|all   profile (default: all)
  --scenario NAME                  one drill only (skips full-game train/eval)
  --cycles N                       stop after N train/eval cycles (default: forever)
  --train-heuristic N              PPO timesteps vs HEURISTIC per cycle (0 = skip)
  --train-lookahead N              PPO timesteps vs LOOKAHEAD per cycle (0 = skip)
  --train-onnx N                   PPO timesteps per ONNX profile seat (general,defense; 0 = skip)
  --eval-heuristic N               eval games vs HEURISTIC (train: every 4 visits/profile; eval-only: each pass; 0 = skip)
  --eval-lookahead N               eval games vs LOOKAHEAD (train: every 4 visits/profile; eval-only: each pass; 0 = skip)
  --eval-onnx N                    eval games per ONNX profile (general,defense; same cadence; 0 = skip)
  --eval-episode-mode EVAL|TRAIN   eval episodeMode (default EVAL; TRAIN = soft-mask search on multi-step claims)
  --skip-scenarios                   skip scenario train; on eval.sh skip scenario eval too
  --train-scenario-learning N        PPO timesteps per drill below max difficulty
  --train-scenario-mastered N        PPO timesteps per drill at max while pack climbs
  --force-promote                    persist next phase gate without live curriculum
                                     (already at profiles: sticky full-game unlock)

Mix defaults live in scripts/train_self_play.py / TRAINING_ROADMAP.md.
EOF
  exit "${1:-0}"
}

# Set by train_cli_parse; read in train.sh / eval.sh / start_over.sh.
TRAIN_CLI_PROFILE="all"
TRAIN_CLI_FORWARD=()

# Parse train/eval CLI flags into TRAIN_CLI_PROFILE and TRAIN_CLI_FORWARD for train_self_play.py.
train_cli_parse() {
  TRAIN_CLI_PROFILE="all"
  # bash 3.2 + set -u: TRAIN_CLI_FORWARD=() here unsets the global array.
  unset TRAIN_CLI_FORWARD
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help) train_cli_usage 0 ;;
      --profile)
        TRAIN_CLI_PROFILE="${2:?missing value for --profile}"
        profile_names "$TRAIN_CLI_PROFILE" >/dev/null
        shift 2
        ;;
      --force-promote|--skip-scenarios)
        TRAIN_CLI_FORWARD+=("$1")
        shift
        ;;
      --scenario|--cycles|--train-heuristic|--train-lookahead|--train-onnx|--eval-heuristic|--eval-lookahead|--eval-onnx|--eval-episode-mode|--train-scenario-learning|--train-scenario-mastered)
        TRAIN_CLI_FORWARD+=("$1" "${2:?missing value for $1}")
        shift 2
        ;;
      --)
        die "'--' is not used — pass flags directly (see ./train.sh --help)"
        ;;
      *) die "unknown arg: $1 (see ./train.sh --help)" ;;
    esac
  done
}

validate_phase() {
  local phase="$1"
  local p
  for p in "${PHASES[@]}"; do
    [[ "$p" == "$phase" ]] && return 0
  done
  die "unknown phase: $phase (one of: ${PHASES[*]})"
}

phase_index() {
  local phase="$1" i
  for i in "${!PHASES[@]}"; do
    if [[ "${PHASES[$i]}" == "$phase" ]]; then
      echo "$i"
      return 0
    fi
  done
  die "unknown phase: $phase"
}

step_dump() {
  log "Dump expert games (games=$DUMP_GAMES threads=$DUMP_THREADS → $JSONL)"
  echo "  note: full dump is long-running; override with DUMP_GAMES=… if you need a smaller run"
  ensure_mvn
  mkdir -p "$EXPERT_DIR"
  # Relative --out from backend/ so it lands in ai/output/expert/.
  local out_rel="../ai/output/expert/lookahead.jsonl.gz"
  local mvn_args=()
  [[ "${MVN##*/}" == mvnd ]] && mvn_args+=(--raw-streams)
  # Port 0 = ephemeral bind so dump does not fight ./dev.sh (:18080).
  (
    cd "$BACKEND_DIR"
    "$MVN" ${mvn_args[@]+"${mvn_args[@]}"} -Dmaven.test.skip=true spring-boot:run \
      -Dspring-boot.run.arguments="--export-expert --server.port=0 --games=${DUMP_GAMES} --threads=${DUMP_THREADS} --out=${out_rel}"
  )
  [[ -f "$JSONL" ]] || die "expected dump missing: $JSONL"
}

step_pack() {
  log "Pack expert dump → $NPZ"
  ensure_python
  [[ -f "$JSONL" ]] || die "expert dump not found: $JSONL"
  (
    cd "$AI_ROOT"
    "$PYTHON" -m scripts.import_expert --in "$JSONL" --out "$NPZ"
  )
}

step_clone_seed() {
  log "Behavioral-clone expert into seed zip → $SEED_ZIP"
  ensure_python
  [[ -f "$NPZ" ]] || die "expert npz not found: $NPZ"
  mkdir -p "$EXPERT_DIR"
  (
    cd "$AI_ROOT"
    "$PYTHON" -m scripts.clone_expert --in "$NPZ" --to "$SEED_ZIP"
  )
}

# Build any missing shared artifacts (dump → pack → seed). Profile zips are forked in Python
# from scenario_b — do not copy the expert seed onto shaped checkpoints here.
ensure_train_prereqs() {
  ensure_python

  if [[ ! -f "$SEED_ZIP" ]]; then
    if [[ ! -f "$NPZ" ]]; then
      if [[ ! -f "$JSONL" ]]; then
        step_dump
      else
        log "Reusing expert dump $JSONL"
      fi
      step_pack
    else
      log "Reusing packed expert $NPZ"
    fi
    step_clone_seed
  else
    log "Reusing seed zip $SEED_ZIP"
  fi
}

wipe_dump() {
  local log
  if [[ -f "$JSONL" ]]; then
    rm -f "$JSONL"
    echo "  removed $JSONL"
  fi
  shopt -s nullglob
  for log in "$EXPERT_DIR"/export-*.log; do
    rm -f "$log"
    echo "  removed $log"
  done
  shopt -u nullglob
}

wipe_pack() {
  if [[ -f "$NPZ" ]]; then
    rm -f "$NPZ"
    echo "  removed $NPZ"
  fi
}

wipe_seed() {
  if [[ -f "$SEED_ZIP" ]]; then
    rm -f "$SEED_ZIP"
    echo "  removed $SEED_ZIP"
  fi
}

_rm_tree() {
  local path="$1"
  if [[ -e "$path" ]]; then
    rm -rf "$path"
    echo "  removed $path"
  fi
}

_rm_file() {
  local path="$1"
  if [[ -f "$path" ]]; then
    rm -f "$path"
    echo "  removed $path"
  fi
}

wipe_phase_dir() {
  _rm_tree "$CHECKPOINT_DIR/$1"
  _rm_tree "$RUNS_DIR/$1"
}

# Scenario training shared artifacts (catalog + cycle log + pre/post-split baseline evals).
# Wiping evals/baseline is intentional on scenario start_over — that also drops the synthetic
# summary scenario_b row. Checkpoint migration must never call this.
wipe_scenario_training_shared() {
  _rm_tree "$EVAL_DIR/$SCENARIO_TRAINING_EVAL_PROFILE"
  _rm_file "$SCENARIO_CATALOG"
  _rm_file "$CYCLE_LOG"
}

wipe_scenario_a() {
  wipe_phase_dir scenario_a
}

wipe_scenario_b() {
  wipe_phase_dir scenario_b
}

wipe_profile_checkpoints() {
  local profile="${1:-all}"
  local name
  while IFS= read -r name; do
    wipe_phase_dir "$name"
    # baseline evals are shared with the synthetic pre-split summary row — never wipe
    # evals/baseline here; only wipe shaped profile eval dirs.
    if [[ "$name" != "$BASELINE_CHECKPOINT_KEY" ]]; then
      _rm_tree "$EVAL_DIR/$name"
    fi
  done < <(profile_names "$profile")
  _rm_file "$CHECKPOINT_DIR/$LEGACY_ROOT_CHECKPOINT_ZIP"
}

wipe_checkpoint() {
  local profile="${1:-all}"
  log "Wipe profile artifacts (profile=$profile)"
  wipe_profile_checkpoints "$profile"
}

# Wipe starting at $1 through checkpoint. Profile only scopes the checkpoint phase.
wipe_from_phase() {
  local phase="$1"
  local profile="${2:-all}"
  local start idx
  local wipe_scenario_shared=false
  local wipe_cycle_log=false
  validate_phase "$phase"
  start="$(phase_index "$phase")"
  log "Wipe from phase=$phase through checkpoint (profile scope for checkpoint=$profile)"
  for idx in "${!PHASES[@]}"; do
    if (( idx < start )); then
      continue
    fi
    case "${PHASES[$idx]}" in
      dump) wipe_dump ;;
      pack) wipe_pack ;;
      seed) wipe_seed ;;
      scenario_a)
        wipe_scenario_a
        wipe_scenario_shared=true
        wipe_cycle_log=true
        ;;
      scenario_b)
        wipe_scenario_b
        wipe_scenario_shared=true
        wipe_cycle_log=true
        ;;
      checkpoint)
        wipe_checkpoint "$profile"
        wipe_cycle_log=true
        ;;
    esac
  done
  if [[ "$wipe_scenario_shared" == true ]]; then
    log "Wipe scenario-training shared artifacts"
    wipe_scenario_training_shared
  elif [[ "$wipe_cycle_log" == true ]]; then
    _rm_file "$CYCLE_LOG"
  fi
  rewind_pipeline "$phase" "$profile"
}

# Saved pipeline position (checkpoints/pipeline.json) must forget the stages just wiped.
rewind_pipeline() {
  local phase="$1"
  local profile="$2"
  local stage
  case "$phase" in
    dump|pack|seed|scenario_a) stage=scenario_a ;;
    scenario_b) stage=scenario_b ;;
    checkpoint) stage=profiles ;;
  esac
  ensure_python
  (
    cd "$AI_ROOT"
    "$PYTHON" -m gridiron_ai.pipeline_state rewind "$stage" --profile "$profile"
  )
}
