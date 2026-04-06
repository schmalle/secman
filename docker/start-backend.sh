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

# Configurable via environment
DB_PASSWORD="${SECMAN_DB_PASSWORD:-secman-docker-pw}"
JWT_SECRET="${SECMAN_JWT_SECRET:-docker-secman-jwt-secret-must-be-at-least-256-bits-long-for-hs256}"

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
