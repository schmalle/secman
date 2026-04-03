#!/bin/bash
# =============================================================================
# Secman Docker - Start Database Container
# =============================================================================
# Starts the MariaDB container on the secman-net Docker network.
# Data is persisted in the 'secman-db-data' Docker volume.
# =============================================================================

set -euo pipefail

CONTAINER_NAME="secman-db"
NETWORK_NAME="secman-net"
VOLUME_NAME="secman-db-data"

# Configurable via environment
DB_ROOT_PASSWORD="${SECMAN_DB_ROOT_PASSWORD:-secman-root-pw}"
DB_PASSWORD="${SECMAN_DB_PASSWORD:-secman-docker-pw}"

echo "[database] Starting $CONTAINER_NAME..."

# Create network if it doesn't exist
docker network inspect "$NETWORK_NAME" >/dev/null 2>&1 || \
  docker network create "$NETWORK_NAME"

# Create volume if it doesn't exist
docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1 || \
  docker volume create "$VOLUME_NAME"

# Stop and remove existing container if running
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

docker run -d \
  --name "$CONTAINER_NAME" \
  --network "$NETWORK_NAME" \
  --restart unless-stopped \
  -v "$VOLUME_NAME":/var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD="$DB_ROOT_PASSWORD" \
  -e MYSQL_DATABASE=secman \
  -e MYSQL_USER=secman \
  -e MYSQL_PASSWORD="$DB_PASSWORD" \
  -p 3307:3306 \
  secman-db

echo "[database] Container started. Waiting for MariaDB to be ready..."

# Wait for MariaDB to accept connections
for i in $(seq 1 30); do
  if docker exec "$CONTAINER_NAME" mariadb -usecman -p"$DB_PASSWORD" -e "SELECT 1" secman >/dev/null 2>&1; then
    echo "[database] ✓ MariaDB is ready (took ~${i}s)"
    exit 0
  fi
  sleep 1
done

echo "[database] ✗ MariaDB did not become ready within 30s"
docker logs --tail 20 "$CONTAINER_NAME"
exit 1
