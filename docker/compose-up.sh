#!/bin/bash
# =============================================================================
# Secman Docker Compose launcher
# =============================================================================
# Brings up the all-in-one Secman image via docker compose, in one of two
# database modes:
#
#   ./docker/compose-up.sh --local-db   Bundled MariaDB container (dev/demo)
#   ./docker/compose-up.sh --rds        External DB / Amazon RDS (uses your
#                                        DB_CONNECT from docker/.env)
#
# On first run it creates docker/.env from docker/.env.example and fills in
# freshly generated secrets (JWT, encryption password/salt, DB passwords).
# Review docker/.env before using it for anything real.
#
# Extra args are forwarded to `docker compose up`, e.g.:
#   ./docker/compose-up.sh --rds -d --build
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
ENV_EXAMPLE="$SCRIPT_DIR/.env.example"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

MODE=""
PASSTHROUGH=()

for arg in "$@"; do
  case "$arg" in
    --local-db) MODE="local-db" ;;
    --rds)      MODE="rds" ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) PASSTHROUGH+=("$arg") ;;
  esac
done

if [[ -z "$MODE" ]]; then
  echo "Usage: $0 --local-db | --rds  [extra docker compose args]" >&2
  echo "  --local-db  run a bundled MariaDB container alongside the app" >&2
  echo "  --rds       use an external database (set DB_CONNECT in docker/.env)" >&2
  exit 2
fi

# Pick a compose implementation (v2 plugin preferred, fall back to v1).
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "✗ Neither 'docker compose' nor 'docker-compose' is available." >&2
  exit 1
fi

# --- Generate docker/.env with fresh secrets on first run --------------------
# Shared with compose-up-split.sh so both flows read/write the same secrets.
source "$SCRIPT_DIR/generate-env.sh"
secman_ensure_env_file "$ENV_FILE" "$ENV_EXAMPLE" "$MODE"

# --- Launch ------------------------------------------------------------------
ARGS=(-f "$COMPOSE_FILE")
if [[ "$MODE" == "local-db" ]]; then
  ARGS+=(--profile local-db)
fi

echo "[compose-up] Mode: $MODE"
echo "[compose-up] Running: ${COMPOSE[*]} ${ARGS[*]} up ${PASSTHROUGH[*]:-}"
exec "${COMPOSE[@]}" "${ARGS[@]}" up ${PASSTHROUGH[@]+"${PASSTHROUGH[@]}"}
