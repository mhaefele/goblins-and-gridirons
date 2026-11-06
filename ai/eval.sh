#!/usr/bin/env bash
# Eval train-rotation profiles once — no training, no eval JSON artifacts (stdout only).
# ``--profile all`` → baseline + general + spread + defense (see reward.profiles_for_all); pin td.
# ``--phase seed|scenario_a|scenario_b`` → load that phase’s end zip (read-only; one pass).
# Usage: ./eval.sh --help
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PROFILE="all"
train_cli_parse "$@"
PROFILE="$TRAIN_CLI_PROFILE"

ensure_python
require_dev_backend

log "Eval (profile=$PROFILE)"
cd "$AI_ROOT"
exec "$PYTHON" -m scripts.train_self_play \
  --base-url "$BASE_URL" \
  --profile "$PROFILE" \
  --eval-only \
  ${TRAIN_CLI_FORWARD[@]+"${TRAIN_CLI_FORWARD[@]}"}
