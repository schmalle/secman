#!/bin/bash
# =============================================================================
# Secman Docker - Build All Containers
# =============================================================================
# Builds all three Docker images from the project root.
# Run from the repository root: ./docker/build-all.sh
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=========================================="
echo " Secman Docker - Building All Containers"
echo "=========================================="
echo ""

cd "$PROJECT_ROOT"

# 1. Database
echo "[1/3] Building secman-db..."
docker build -t secman-db -f docker/database/Dockerfile docker/database/
echo "  ✓ secman-db built"
echo ""

# 2. Backend
echo "[2/3] Building secman-backend (this may take a few minutes)..."
docker build -t secman-backend -f docker/backend/Dockerfile .
echo "  ✓ secman-backend built"
echo ""

# 3. Frontend
echo "[3/3] Building secman-frontend..."
docker build -t secman-frontend -f docker/frontend/Dockerfile .
echo "  ✓ secman-frontend built"
echo ""

echo "=========================================="
echo " All images built successfully!"
echo "=========================================="
echo ""
echo "Images:"
docker images --format "  {{.Repository}}:{{.Tag}}  {{.Size}}" | grep secman
echo ""
echo "Next: ./docker/start-all.sh"
