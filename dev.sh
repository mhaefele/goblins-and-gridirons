#!/usr/bin/env bash
# Start backend + frontend for local development. Ctrl+C stops both.
#
#   --no-ui   backend only — skip Vite and do not open Chrome
#
# Uses ports distinct from Cursor agent / Playwright defaults (8080, 5173, 4173)
# so a manual ./dev.sh can run alongside those without fighting for the bind.
set -euo pipefail

NO_UI=false
for arg in "$@"; do
  case "$arg" in
    --no-ui) NO_UI=true ;;
    -h|--help)
      sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown option: ${arg} (try --help)" >&2
      exit 1
      ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_PORT="${BACKEND_PORT:-18080}"
FRONTEND_PORT="${FRONTEND_PORT:-15173}"
BACKEND_READY_TIMEOUT_S="${BACKEND_READY_TIMEOUT_S:-180}"
KILL_GRACE_S="${KILL_GRACE_S:-2}"
BACKEND_PID=""
FRONTEND_PID=""
# Maven Daemon when installed (warm JVM); override with MVN=mvn.
MVN="${MVN:-$(command -v mvnd 2>/dev/null || command -v mvn)}"

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

# Kill whatever is still LISTENing on a port (orphans from a prior Ctrl+C / crash).
free_port() {
  local port=$1
  local pids
  pids="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true)"
  if [[ -z "$pids" ]]; then
    return 0
  fi
  echo "Port ${port} in use by PID(s) $(echo "$pids" | tr '\n' ' ')— reclaiming..."
  for pid in $pids; do
    kill_tree "$pid"
  done
}

# Reap a background job and print a clear failure (compile / bind / crash).
fail_if_exited() {
  local pid=$1
  local label=$2
  local status=0

  if kill -0 "$pid" 2>/dev/null; then
    return 0
  fi
  wait "$pid" 2>/dev/null || status=$?
  echo "${label} exited with status ${status} before becoming ready (build or startup failed)." >&2
  exit 1
}

cleanup() {
  trap - EXIT INT TERM
  echo ""
  echo "Shutting down..."
  [[ -n "$FRONTEND_PID" ]] && kill_tree "$FRONTEND_PID"
  [[ -n "$BACKEND_PID" ]] && kill_tree "$BACKEND_PID"
  wait 2>/dev/null || true
  echo "Stopped."
}

wait_for_url() {
  local url=$1
  local label=$2
  local timeout_s=$3
  local pid=$4
  local elapsed=0

  echo "Waiting for ${label}..."
  while ! curl -sf "$url" >/dev/null 2>&1; do
    fail_if_exited "$pid" "$label"
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

free_port "$BACKEND_PORT"
if [[ "$NO_UI" == false ]]; then
  free_port "$FRONTEND_PORT"
fi

# mvnd prefixes forked stdout as "[INFO] [stdout] …"; --raw-streams keeps Spring's own lines.
MVN_ARGS=()
[[ "${MVN##*/}" == mvnd ]] && MVN_ARGS+=(--raw-streams)

echo "Starting backend  →  http://localhost:${BACKEND_PORT} (${MVN##*/})"
(
  cd "$ROOT/backend"
  # spring-boot:run normally test-compiles; skip for faster dev boot (run `mvn test` separately).
  exec "$MVN" "${MVN_ARGS[@]}" -Dmaven.test.skip=true spring-boot:run -Dspring-boot.run.arguments="--server.port=${BACKEND_PORT}"
) &
BACKEND_PID=$!

wait_for_url \
  "http://localhost:${BACKEND_PORT}/health" \
  "Spring Boot" \
  "$BACKEND_READY_TIMEOUT_S" \
  "$BACKEND_PID"

if [[ "$NO_UI" == false ]]; then
  echo "Starting frontend →  http://localhost:${FRONTEND_PORT}"
  (
    cd "$ROOT/frontend"
    export GRIDIRON_API_PROXY="http://localhost:${BACKEND_PORT}"
    exec npm run dev -- --port "${FRONTEND_PORT}" --strictPort
  ) &
  FRONTEND_PID=$!

  # Vite should bind almost immediately; fail fast if --strictPort / npm dies.
  for _ in 1 2 3 4 5; do
    fail_if_exited "$FRONTEND_PID" "Vite"
    if curl -sf "http://localhost:${FRONTEND_PORT}" >/dev/null 2>&1; then
      break
    fi
    sleep 0.4
  done
  fail_if_exited "$FRONTEND_PID" "Vite"
fi

echo ""
if [[ "$NO_UI" == false ]]; then
  APP_URL="http://localhost:${FRONTEND_PORT}"
  echo "App:              ${APP_URL}"
  echo "Pitch align debug: ${APP_URL}/pitch-align"
fi
echo "API / OpenAPI:    http://localhost:${BACKEND_PORT}  (docs: /v3/api-docs)"
if [[ "$NO_UI" == false ]]; then
  echo "(Cursor/tests keep using 8080 / 5173 / 4173 — override via BACKEND_PORT / FRONTEND_PORT.)"
  echo "Ctrl+C to stop both."
else
  echo "Ctrl+C to stop."
fi
echo ""

if [[ "$NO_UI" == false && "$(uname -s)" == "Darwin" ]]; then
  open -a "Google Chrome" "http://localhost:${FRONTEND_PORT}" 2>/dev/null || true
fi

# Supervise: if either child dies (rebuild crash, OOM, etc.), stop the other and exit non-zero.
while true; do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    status=0
    wait "$BACKEND_PID" 2>/dev/null || status=$?
    echo "Backend exited (status ${status})." >&2
    exit 1
  fi
  if [[ "$NO_UI" == false ]] && ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    status=0
    wait "$FRONTEND_PID" 2>/dev/null || status=$?
    echo "Frontend exited (status ${status})." >&2
    exit 1
  fi
  sleep 1
done
