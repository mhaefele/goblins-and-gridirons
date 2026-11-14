#!/usr/bin/env bash
# Ensure missing expert/seed artifacts, then train forever.
# User-facing CLI — flags here, not via a Python passthrough separator.
# Eval/train need ../dev.sh already up on :18080 — this script does not start it.
#
# Cascade when missing (presence only; stale files are not regenerated):
#   dump (jsonl.gz) → pack (npz) → seed zip
# Skipped when ``--from-scratch``, pipeline past scenario_a, or scenario_a zip exists.
# Python then forks scenario_a → scenario_b → shaped profile checkpoints.
#
# Usage: ./train.sh --help
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PROFILE="all"
train_cli_parse "$@"
PROFILE="$TRAIN_CLI_PROFILE"

echo "train: root=$ROOT profile=$PROFILE"
ensure_train_prereqs
require_dev_backend

log "Train (profile=$PROFILE)"
cd "$AI_ROOT"
exec "$PYTHON" -m scripts.train_self_play \
  --base-url "$BASE_URL" \
  --profile "$PROFILE" \
  --continue-on-submit-error \
  ${TRAIN_CLI_FORWARD[@]+"${TRAIN_CLI_FORWARD[@]}"}
