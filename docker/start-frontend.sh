#!/bin/bash
# =============================================================================
# Secman Docker - Start Frontend Container
# =============================================================================
# Starts the Nginx frontend on the secman-net Docker network.
# Reachable on https://localhost:8443 (self-signed certificate).
# Requires: secman-backend container running and healthy.
# =============================================================================

set -euo pipefail

CONTAINER_NAME="secman-frontend"
NETWORK_NAME="secman-net"

echo "[frontend] Starting $CONTAINER_NAME..."

# Ensure network exists
docker network inspect "$NETWORK_NAME" >/dev/null 2>&1 || {
  echo "[frontend] ✗ Network $NETWORK_NAME not found. Start the database first."
  exit 1
}

# Check backend is running
if ! docker ps --format '{{.Names}}' | grep -q '^secman-backend$'; then
  echo "[frontend] ✗ secman-backend container is not running. Start it first."
  exit 1
fi

# Stop and remove existing container if running
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

docker run -d \
  --name "$CONTAINER_NAME" \
  --network "$NETWORK_NAME" \
  --restart unless-stopped \
  -p 8443:8443 \
  secman-frontend

echo "[frontend] Container started. Waiting for Nginx to be ready..."

# Wait for Nginx HTTPS
for i in $(seq 1 15); do
  if curl -ksf https://localhost:8443/ >/dev/null 2>&1; then
    echo "[frontend] ✓ Frontend is ready at https://localhost:8443 (took ~${i}s)"
    exit 0
  fi
  sleep 1
done

echo "[frontend] ✗ Frontend did not become ready within 15s"
docker logs --tail 20 "$CONTAINER_NAME"
exit 1
