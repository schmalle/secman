#!/bin/bash
# =============================================================================
# Secman Docker Compose launcher — split (three-container) topology
# =============================================================================
# Brings up the database, backend, and frontend as three separate containers
# via docker compose, building the images locally first.
#
#   ./docker/compose-up-split.sh
#
# On first run it creates docker/.env from docker/.env.example and fills in
# freshly generated secrets (JWT, encryption password/salt, DB passwords) —
# the same .env file used by ./docker/compose-up.sh (the all-in-one flow).
# Review docker/.env before using it for anything real.
#
# Extra args are forwarded to `docker compose up`, e.g.:
#   ./docker/compose-up-split.sh -d --build
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
ENV_EXAMPLE="$SCRIPT_DIR/.env.example"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.split.yaml"

# Pick a compose implementation (v2 plugin preferred, fall back to v1).
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "✗ Neither 'docker compose' nor 'docker-compose' is available." >&2
  exit 1
fi

# --- Ensure docker/.env exists with fresh secrets (shared with compose-up.sh)
source "$SCRIPT_DIR/generate-env.sh"
secman_ensure_env_file "$ENV_FILE" "$ENV_EXAMPLE"

# --- Build the backend fat JAR (the backend image copies a pre-built jar —
# see docker/backend/Dockerfile) --------------------------------------------
echo "[compose-up-split] Building backend JAR..."
( cd "$PROJECT_ROOT" && ./gradlew :backendng:shadowJar -x test --no-daemon )
cp "$PROJECT_ROOT"/src/backendng/build/libs/*-all.jar "$SCRIPT_DIR/backend/app.jar"

echo "[compose-up-split] Running: ${COMPOSE[*]} -f $COMPOSE_FILE --env-file $ENV_FILE up --build ${*:-}"
exec "${COMPOSE[@]}" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up --build "$@"
