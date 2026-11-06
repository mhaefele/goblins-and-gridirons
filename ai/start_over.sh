#!/usr/bin/env bash
# Wipe training artifacts from a phase onward, then hand off to ./train.sh
# (which regenerates anything still missing and starts training).
#
# No args → help. Requires --phase (or --undo). Optional --profile scopes the
# checkpoint wipe (and train) once the chain has branched; earlier phases always
# wipe the shared dump/pack/seed artifacts.
#
# Each wipe parks the previous artifacts in output/.start_over_last (one slot).
# Restore with: ./start_over.sh --undo
#
# Phases (each wipe also clears everything after it — see common.sh for full artifact list):
#   dump         expert jsonl + export logs
#   pack         packed lookahead.npz
#   seed         shared maskable_ppo_seed.zip
#   scenario_a   scenario_a checkpoints/runs + shared scenario evals/catalog/cycle log/legacy baseline
#   scenario_b   scenario_b checkpoints/runs (+ shared scenario artifacts when reached)
#   checkpoint   shaped profile checkpoints/runs/evals (general/spread/td/defense); --profile scopes
#
# Usage (from ai/):
#   ./start_over.sh --phase dump
#   ./start_over.sh --phase seed --profile general
#   ./start_over.sh --phase scenario_a
#   ./start_over.sh --phase scenario_b
#   ./start_over.sh --phase checkpoint --profile td
#   ./start_over.sh --phase pack --cycles 3
#   ./start_over.sh --undo
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PHASE=""
UNDO=false

usage() {
  sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
  echo ""
  echo "Train flags after wipe (same as ./train.sh — see ./train.sh --help):"
  exit 0
}

if [[ $# -eq 0 ]]; then
  usage
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage ;;
    --undo) UNDO=true; shift ;;
    --phase) PHASE="${2:?}"; shift 2 ;;
    --profile|--scenario|--cycles|--train-heuristic|--train-lookahead|--eval-heuristic|--eval-lookahead|--train-scenario-learning|--train-scenario-mastered|--force-promote|--skip-scenarios)
      break
      ;;
    --)
      die "'--' is not used — pass flags directly (see ./start_over.sh --help)"
      ;;
    *) die "unknown arg: $1 (see --help)" ;;
  esac
done

if [[ "$UNDO" == true ]]; then
  [[ -z "$PHASE" ]] || die "--undo does not take --phase"
  [[ $# -eq 0 ]] || die "--undo does not take train flags"
  undo_start_over
  exit 0
fi

[[ -n "$PHASE" ]] || die "missing --phase (or pass --undo; see --help)"
validate_phase "$PHASE"

train_cli_parse "$@"
TRAIN_PROFILE="$TRAIN_CLI_PROFILE"

profile_names "$TRAIN_PROFILE" >/dev/null

echo "start_over: root=$ROOT phase=$PHASE profile=$TRAIN_PROFILE"
printf 'Wipe from phase=%s (profile=%s) and retrain? [y/N] ' "$PHASE" "$TRAIN_PROFILE"
read -r _confirm
case "$_confirm" in
  y|Y|yes|YES) ;;
  *) die "aborted" ;;
esac

wipe_from_phase "$PHASE" "$TRAIN_PROFILE"

train_args=(--profile "$TRAIN_PROFILE" ${TRAIN_CLI_FORWARD[@]+"${TRAIN_CLI_FORWARD[@]}"})

log "Hand off to train.sh"
exec "$AI_ROOT/train.sh" "${train_args[@]}"
