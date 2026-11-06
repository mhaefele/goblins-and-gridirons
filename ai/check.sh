#!/usr/bin/env bash
# Static + import boot checks (Python has no real compile).
#   1) compileall — syntax / bytecode only (misses circular imports)
#   2) entrypoint import smokes — the link step (start_over / train -m paths)
#
# Usage (from ai/):
#   ./check.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

usage() {
  sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

case "${1:-}" in
  -h|--help) usage ;;
esac

ensure_python
cd "$AI_ROOT"

# Keep .pyc out of the tree (and avoid sandbox fights on tests/__pycache__).
export PYTHONPYCACHEPREFIX="${TMPDIR:-/tmp}/gridiron-ai-pycache"

log "compileall (syntax only)"
"$PYTHON" -m compileall -q gridiron_ai scripts tests

log "entrypoint imports + python -m … --help"
"$PYTHON" -m pytest tests/test_entrypoint_imports.py -q --tb=line

echo ""
echo "check: ok"
