#!/bin/bash
# =============================================================================
# Secman Docker - Stop All Containers
# =============================================================================
# Stops and removes all secman containers. Data volume is preserved.
# Use --purge to also remove the data volume and network.
# =============================================================================

set -euo pipefail

PURGE=false
if [[ "${1:-}" == "--purge" ]]; then
  PURGE=true
fi

echo "Stopping secman containers..."

docker rm -f secman-frontend 2>/dev/null && echo "  ✓ secman-frontend stopped" || echo "  - secman-frontend not running"
docker rm -f secman-backend 2>/dev/null && echo "  ✓ secman-backend stopped" || echo "  - secman-backend not running"
docker rm -f secman-db 2>/dev/null && echo "  ✓ secman-db stopped" || echo "  - secman-db not running"

if $PURGE; then
  echo ""
  echo "Purging data volume and network..."
  docker volume rm secman-db-data 2>/dev/null && echo "  ✓ Volume secman-db-data removed" || echo "  - Volume not found"
  docker network rm secman-net 2>/dev/null && echo "  ✓ Network secman-net removed" || echo "  - Network not found"
  echo ""
  echo "All secman resources purged."
else
  echo ""
  echo "Containers stopped. Database data preserved in volume 'secman-db-data'."
  echo "Use './docker/stop-all.sh --purge' to also remove data and network."
fi
