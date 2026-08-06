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
gen() {
  # A portable random secret generator: openssl if present, else /dev/urandom.
  local kind="$1"
  if command -v openssl >/dev/null 2>&1; then
    case "$kind" in
      b64_32) openssl rand -base64 32 ;;
      hex_32) openssl rand -hex 32 ;;
      hex_8)  openssl rand -hex 8 ;;
      hex_12) openssl rand -hex 12 ;;
    esac
  else
    case "$kind" in
      b64_32) head -c 32 /dev/urandom | base64 ;;
      hex_32) head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' ;;
      hex_8)  head -c 8  /dev/urandom | od -An -tx1 | tr -d ' \n' ;;
      hex_12) head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n' ;;
    esac
  fi
}

if [[ ! -f "$ENV_FILE" ]]; then
  echo "[compose-up] docker/.env not found — creating it from .env.example with fresh secrets."
  cp "$ENV_EXAMPLE" "$ENV_FILE"

  JWT="$(gen b64_32)"
  ENC_PW="$(gen hex_32)"
  ENC_SALT="$(gen hex_8)"
  DB_PW="$(gen hex_12)"
  DB_ROOT_PW="$(gen hex_12)"

  # sed -i portability (GNU vs BSD)
  sedi() { if sed --version >/dev/null 2>&1; then sed -i "$@"; else sed -i '' "$@"; fi; }
  set_kv() { sedi "s|^$1=.*|$1=$2|" "$ENV_FILE"; }

  set_kv JWT_SECRET "$JWT"
  set_kv SECMAN_ENCRYPTION_PASSWORD "$ENC_PW"
  set_kv SECMAN_ENCRYPTION_SALT "$ENC_SALT"
  set_kv DB_PASSWORD "$DB_PW"
  set_kv DB_ROOT_PASSWORD "$DB_ROOT_PW"

  echo "[compose-up] Wrote $ENV_FILE. Review it (URLs, DB_CONNECT, SMTP, etc.) before production use."
  if [[ "$MODE" == "rds" ]]; then
    echo "[compose-up] RDS mode: edit DB_CONNECT/DB_USERNAME/DB_PASSWORD in docker/.env to match your RDS instance."
  fi
fi

# --- Launch ------------------------------------------------------------------
ARGS=(-f "$COMPOSE_FILE")
if [[ "$MODE" == "local-db" ]]; then
  ARGS+=(--profile local-db)
fi

echo "[compose-up] Mode: $MODE"
echo "[compose-up] Running: ${COMPOSE[*]} ${ARGS[*]} up ${PASSTHROUGH[*]:-}"
exec "${COMPOSE[@]}" "${ARGS[@]}" up ${PASSTHROUGH[@]+"${PASSTHROUGH[@]}"}
