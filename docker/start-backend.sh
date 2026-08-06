#!/bin/bash
# =============================================================================
# Secman Docker - Start Backend Container
# =============================================================================
# Starts the Micronaut backend on the secman-net Docker network.
# Requires: secman-db container running and healthy.
# =============================================================================

set -euo pipefail

CONTAINER_NAME="secman-backend"
NETWORK_NAME="secman-net"

# Required — no baked-in default. DB_PASSWORD/JWT_SECRET avoid shipping a
# well-known password; SECMAN_ENCRYPTION_PASSWORD/SALT avoid the equally
# well-known application.yml fallback ("SecManDefaultEncryptionPassword2024")
# that encrypts stored credentials (OAuth secrets, API keys) when unset.
: "${SECMAN_DB_PASSWORD:?Set SECMAN_DB_PASSWORD to the same value used by start-database.sh}"
: "${SECMAN_JWT_SECRET:?Set SECMAN_JWT_SECRET, e.g. export SECMAN_JWT_SECRET=\$(openssl rand -base64 32)}"
: "${SECMAN_ENCRYPTION_PASSWORD:?Set SECMAN_ENCRYPTION_PASSWORD, e.g. export SECMAN_ENCRYPTION_PASSWORD=\$(openssl rand -hex 32)}"
: "${SECMAN_ENCRYPTION_SALT:?Set SECMAN_ENCRYPTION_SALT (exactly 16 hex chars), e.g. export SECMAN_ENCRYPTION_SALT=\$(openssl rand -hex 8)}"
DB_PASSWORD="$SECMAN_DB_PASSWORD"
JWT_SECRET="$SECMAN_JWT_SECRET"

echo "[backend] Starting $CONTAINER_NAME..."

# Ensure network exists
docker network inspect "$NETWORK_NAME" >/dev/null 2>&1 || {
  echo "[backend] ✗ Network $NETWORK_NAME not found. Start the database first."
  exit 1
}

# Check database is running
if ! docker ps --format '{{.Names}}' | grep -q '^secman-db$'; then
  echo "[backend] ✗ secman-db container is not running. Start it first."
  exit 1
fi

# Stop and remove existing container if running
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

docker run -d \
  --name "$CONTAINER_NAME" \
  --network "$NETWORK_NAME" \
  --restart unless-stopped \
  -e DB_CONNECT="jdbc:mariadb://secman-db:3306/secman" \
  -e DB_USERNAME=secman \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e JWT_SECRET="$JWT_SECRET" \
  -e SECMAN_ENCRYPTION_PASSWORD="$SECMAN_ENCRYPTION_PASSWORD" \
  -e SECMAN_ENCRYPTION_SALT="$SECMAN_ENCRYPTION_SALT" \
  -e SECMAN_AUTH_COOKIE_SECURE=false \
  -e FRONTEND_URL="https://localhost:8443" \
  -e SECMAN_BACKEND_URL="https://localhost:8443" \
  -e FLYWAY_DATASOURCES_DEFAULT_ENABLED=false \
  -p 8080:8080 \
  secman-backend

echo "[backend] Container started. Waiting for backend to be ready..."

# Wait for the backend health endpoint
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/health >/dev/null 2>&1; then
    echo "[backend] ✓ Backend is ready (took ~${i}s)"
    exit 0
  fi
  sleep 1
done

echo "[backend] ✗ Backend did not become ready within 60s"
echo "[backend] Last 30 lines of logs:"
docker logs --tail 30 "$CONTAINER_NAME"
exit 1
