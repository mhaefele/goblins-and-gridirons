#!/usr/bin/env bash
# Start TensorBoard on ai/output/runs and open Google Chrome to it.
#
# Usage (from ai/):
#   ./tensor.sh
#   ./tensor.sh --profile td
#   ./tensor.sh --port 6007
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AI_ROOT="$ROOT/ai"
RUNS_DIR="$AI_ROOT/output/runs"
TENSORBOARD="${TENSORBOARD:-$AI_ROOT/.venv/bin/tensorboard}"
PORT="${PORT:-6006}"
PROFILE="all"
CHROME_APP="Google Chrome"

usage() {
  sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

die() {
  echo "error: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --profile) PROFILE="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    *) die "unknown arg: $1 (see --help)" ;;
  esac
done

[[ -x "$TENSORBOARD" ]] || die "missing $TENSORBOARD — cd ai && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"

case "$PROFILE" in
  all) LOGDIR="$RUNS_DIR" ;;
  baseline|general|spread|td|defense) LOGDIR="$RUNS_DIR/$PROFILE" ;;
  *) die "unknown profile: $PROFILE (baseline, general, spread, td, defense, or all)" ;;
esac

[[ -d "$LOGDIR" ]] || die "no TensorBoard logs at $LOGDIR — train first (./train.sh)"

URL="http://127.0.0.1:${PORT}/"

cleanup() {
  if [[ -n "${TB_PID:-}" ]] && kill -0 "$TB_PID" 2>/dev/null; then
    kill "$TB_PID" 2>/dev/null || true
    wait "$TB_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

echo "TensorBoard logdir=$LOGDIR port=$PORT"
"$TENSORBOARD" --logdir "$LOGDIR" --port "$PORT" --bind_all >/dev/null 2>&1 &
TB_PID=$!

ready=0
for _ in $(seq 1 50); do
  if curl -sf "$URL" >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! kill -0 "$TB_PID" 2>/dev/null; then
    die "TensorBoard exited before becoming ready — try ./tensor.sh --port $((PORT + 1))"
  fi
  sleep 0.2
done
[[ "$ready" -eq 1 ]] || die "TensorBoard did not respond on $URL within 10s"

if open -a "$CHROME_APP" "$URL" 2>/dev/null; then
  echo "opened $URL in $CHROME_APP (Ctrl+C here stops TensorBoard)"
else
  echo "warning: could not open $CHROME_APP — open $URL manually" >&2
fi

wait "$TB_PID"
