#!/usr/bin/env bash
# Export trained profile checkpoint(s) → ONNX on the backend classpath.
# Idempotent: re-run anytime; overwrites backend/src/main/resources/ai-opponent/{profile}/.
#
# Usage (from ai/):
#   ./export_opponent.sh              # all profiles that have a final.zip
#   ./export_opponent.sh general
#   ./export_opponent.sh all
#
# Does not start the backend or stop training.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PROFILE="${1:-all}"
case "$PROFILE" in
  all|general|spread|td|defense|baseline) ;;
  -h|--help)
    cat <<'EOF'
Usage: ./export_opponent.sh [profile]

  profile  all (default) | general | spread | td | defense | baseline

Writes (backend classpath, committed for other players):
  backend/src/main/resources/ai-opponent/{profile}/policy.onnx
  backend/src/main/resources/ai-opponent/{profile}/manifest.json

Only profiles with a final.zip are exported when profile=all.
EOF
    exit 0
    ;;
  *) die "unknown profile: $PROFILE (all, general, spread, td, defense, baseline)" ;;
esac

ensure_python

"$PYTHON" - <<'PY'
import importlib.util
import subprocess
import sys

missing = [m for m in ("onnx", "onnxruntime") if importlib.util.find_spec(m) is None]
if missing:
    print(f"installing {', '.join(missing)} …", flush=True)
    subprocess.check_call([sys.executable, "-m", "pip", "install", *missing], stdout=sys.stdout)
PY

log "Export ONNX opponent (profile=$PROFILE)"
cd "$AI_ROOT"
exec "$PYTHON" -m scripts.export_onnx_opponent --profile "$PROFILE"
