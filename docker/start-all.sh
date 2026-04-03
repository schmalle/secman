#!/bin/bash
# =============================================================================
# Secman Docker - Start All Containers
# =============================================================================
# Starts database, backend, and frontend in the correct order.
# Waits for each service to be healthy before starting the next.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=========================================="
echo " Secman Docker - Starting All Services"
echo "=========================================="
echo ""

# 1. Database
"$SCRIPT_DIR/start-database.sh"
echo ""

# 2. Backend
"$SCRIPT_DIR/start-backend.sh"
echo ""

# 3. Frontend
"$SCRIPT_DIR/start-frontend.sh"
echo ""

echo "=========================================="
echo " All services are running!"
echo "=========================================="
echo ""
echo "  Frontend:  https://localhost:8443"
echo "  Backend:   http://localhost:8080"
echo "  Database:  localhost:3307 (MariaDB)"
echo ""
echo "  Default admin credentials are generated"
echo "  on first startup. Check backend logs:"
echo "    docker logs secman-backend | grep -A5 'ADMIN'"
echo ""
echo "  Stop all:  ./docker/stop-all.sh"
echo ""
