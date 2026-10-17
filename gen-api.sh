#!/usr/bin/env bash
# Boot Spring Boot long enough to regenerate frontend OpenAPI types, then shut down.
#
# Defaults to port 18081 so it can run alongside ./dev.sh (:18080) and
# Cursor/Playwright (:8080). Override with BACKEND_PORT if needed.
#
# Usage: ./gen-api.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PORT="${BACKEND_PORT:-18081}"
BACKEND_READY_TIMEOUT_S="${BACKEND_READY_TIMEOUT_S:-180}"
KILL_GRACE_S="${KILL_GRACE_S:-2}"
OPENAPI_URL="http://localhost:${BACKEND_PORT}/v3/api-docs"
BACKEND_PID=""

# Descendants first, then root — snapshot before signaling so reparenting can't hide kids.
list_tree() {
  local pid=$1
  local children
  children="$(pgrep -P "$pid" 2>/dev/null || true)"
  for child in $children; do
    list_tree "$child"
  done
  printf '%s\n' "$pid"
}

# SIGTERM the tree, brief grace, then SIGKILL survivors (Maven/Spring are slow to exit).
kill_tree() {
  local root=$1
  local -a pids=()
  local pid alive waited=0

  while IFS= read -r pid; do
    [[ -n "$pid" ]] && pids+=("$pid")
  done < <(list_tree "$root")

  for pid in "${pids[@]}"; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  while (( waited < KILL_GRACE_S )); do
    alive=0
    for pid in "${pids[@]}"; do
      if kill -0 "$pid" 2>/dev/null; then
        alive=1
        break
      fi
    done
    (( alive == 0 )) && break
    sleep 1
    waited=$((waited + 1))
  done

  for pid in "${pids[@]}"; do
    kill -KILL "$pid" 2>/dev/null || true
  done
}

cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$BACKEND_PID" ]]; then
    echo "Shutting down backend..."
    kill_tree "$BACKEND_PID"
    wait 2>/dev/null || true
  fi
}

wait_for_url() {
  local url=$1
  local label=$2
  local timeout_s=$3
  local elapsed=0

  echo "Waiting for ${label}..."
  while ! curl -sf "$url" >/dev/null 2>&1; do
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      echo "Backend exited before becoming ready." >&2
      exit 1
    fi
    if (( elapsed >= timeout_s )); then
      echo "Timed out after ${timeout_s}s waiting for ${label}." >&2
      exit 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "${label} is ready."
}

trap cleanup EXIT INT TERM

echo "Starting backend on :${BACKEND_PORT} for OpenAPI generation..."
(
  cd "$ROOT/backend"
  exec mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=${BACKEND_PORT}"
) &
BACKEND_PID=$!

wait_for_url "$OPENAPI_URL" "OpenAPI docs" "$BACKEND_READY_TIMEOUT_S"

echo "Generating frontend/src/api/generated/openapi.ts from ${OPENAPI_URL}"
(
  cd "$ROOT/frontend"
  export OPENAPI_URL
  npm run gen:api
)

echo "Done."
