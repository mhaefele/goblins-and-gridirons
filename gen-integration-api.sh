#!/usr/bin/env bash
# Boot Spring Boot long enough to fetch the integration OpenAPI group, then shut down.
#
# Defaults to port 18081 so it can run alongside ./dev.sh (:18080).
# Override: BACKEND_PORT, OUT, BACKEND_READY_TIMEOUT_S
#
# Usage: ./gen-integration-api.sh [output.json]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PORT="${BACKEND_PORT:-18081}"
BACKEND_READY_TIMEOUT_S="${BACKEND_READY_TIMEOUT_S:-180}"
KILL_GRACE_S="${KILL_GRACE_S:-2}"
OUT="${1:-${OUT:-$ROOT/integration-openapi.json}}"
OPENAPI_URL="http://localhost:${BACKEND_PORT}/v3/api-docs/integration"
BACKEND_PID=""

list_tree() {
  local pid=$1
  local children
  children="$(pgrep -P "$pid" 2>/dev/null || true)"
  for child in $children; do
    list_tree "$child"
  done
  printf '%s\n' "$pid"
}

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

mkdir -p "$(dirname "$OUT")"

echo "Starting backend on :${BACKEND_PORT} for integration OpenAPI..."
(
  cd "$ROOT/backend"
  exec mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=${BACKEND_PORT}"
) &
BACKEND_PID=$!

wait_for_url "$OPENAPI_URL" "integration OpenAPI" "$BACKEND_READY_TIMEOUT_S"

echo "Writing ${OPENAPI_URL} → ${OUT}"
if command -v jq >/dev/null 2>&1; then
  curl -sf "$OPENAPI_URL" | jq . >"$OUT"
else
  curl -sf "$OPENAPI_URL" -o "$OUT"
fi

echo "Done."
