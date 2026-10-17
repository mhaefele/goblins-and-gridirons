#!/usr/bin/env bash
# One-screen status: latest evals, training steps, scenario curriculum, key stats.
# Reads ai/output/evals/* and ai/output/checkpoints/* — no backend required.
#
# Usage (from ai/):
#   ./summary.sh
#   ./summary.sh --verbose
#   ./summary.sh --profile td
#   ./summary.sh --no-legend
#   ./summary.sh --trend-steps 200000
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AI_ROOT="$ROOT/ai"
PYTHON="${PYTHON:-$AI_ROOT/.venv/bin/python}"

usage() {
  sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

die() {
  echo "error: $*" >&2
  exit 1
}

[[ -x "$PYTHON" ]] || die "missing $PYTHON — cd ai && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"

cd "$AI_ROOT"
exec "$PYTHON" -m scripts.summarize_models "$@"
