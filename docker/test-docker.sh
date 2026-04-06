#!/bin/bash
# =============================================================================
# Secman Docker - Integration Test Script
# =============================================================================
# This script:
#   1. Builds all three Docker images
#   2. Starts all containers (database → backend → frontend)
#   3. Waits for the default admin user to be created
#   4. Extracts the auto-generated admin password from backend logs
#   5. Logs in via the REST API and validates the JWT token
#   6. Performs basic health checks on all services
#   7. Optionally stops all containers (pass --keep to leave running)
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEEP_RUNNING=false

if [[ "${1:-}" == "--keep" ]]; then
  KEEP_RUNNING=true
fi

PASS=0
FAIL=0

pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

echo "=========================================="
echo " Secman Docker - Integration Test"
echo "=========================================="
echo ""

# --- Step 1: Build ---
echo "[step 1] Building Docker images..."
"$SCRIPT_DIR/build-all.sh"
echo ""

# --- Step 2: Start (clean state) ---
echo "[step 2] Stopping any existing containers..."
"$SCRIPT_DIR/stop-all.sh" --purge 2>/dev/null || true
echo ""

echo "[step 2] Starting all containers..."
"$SCRIPT_DIR/start-all.sh"
echo ""

# --- Step 3: Extract admin credentials ---
echo "[step 3] Extracting admin credentials from backend logs..."

ADMIN_PASSWORD=""
for i in $(seq 1 30); do
  ADMIN_PASSWORD=$(docker logs secman-backend 2>&1 | sed -n 's/.*Password: \([^ ]*\).*/\1/p' | tail -1 || true)
  if [[ -n "$ADMIN_PASSWORD" ]]; then
    break
  fi
  sleep 2
done

if [[ -z "$ADMIN_PASSWORD" ]]; then
  echo "  ✗ Could not extract admin password from logs."
  echo "  Backend logs (last 30 lines):"
  docker logs --tail 30 secman-backend
  echo ""
  fail "Admin password extraction"
else
  echo "  Found admin password: ${ADMIN_PASSWORD:0:4}****"
  pass "Admin password extraction"
fi
echo ""

# --- Step 4: Login test ---
echo "[step 4] Testing login via REST API..."

if [[ -n "$ADMIN_PASSWORD" ]]; then
  LOGIN_RESPONSE=$(curl -ks -D - -X POST https://localhost:8443/api/auth/login \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}" \
    -w "\n%{http_code}" 2>&1)

  HTTP_CODE=$(echo "$LOGIN_RESPONSE" | tail -1)
  RESPONSE_BODY=$(echo "$LOGIN_RESPONSE" | sed '$d')

  if [[ "$HTTP_CODE" == "200" ]]; then
    pass "Login (HTTP $HTTP_CODE)"

    # Extract JWT from Set-Cookie header (token is in HttpOnly cookie, not response body)
    TOKEN=$(echo "$LOGIN_RESPONSE" | sed -n 's/.*[Ss]et-[Cc]ookie:.*secman_auth=\([^;]*\).*/\1/p' | head -1 || true)
    if [[ -n "$TOKEN" ]]; then
      pass "JWT token received (cookie)"
    else
      fail "JWT token extraction from Set-Cookie header"
    fi
  else
    fail "Login (HTTP $HTTP_CODE)"
    echo "  Response: $RESPONSE_BODY"
  fi
else
  fail "Login (skipped - no password)"
fi
echo ""

# --- Step 5: Health checks ---
echo "[step 5] Running health checks..."

# Frontend serves HTML
FRONTEND_CODE=$(curl -ks -o /dev/null -w "%{http_code}" https://localhost:8443/)
if [[ "$FRONTEND_CODE" == "200" ]]; then
  pass "Frontend HTTPS (HTTP $FRONTEND_CODE)"
else
  fail "Frontend HTTPS (HTTP $FRONTEND_CODE)"
fi

# Backend health
BACKEND_CODE=$(curl -sf -o /dev/null -w "%{http_code}" http://localhost:8080/health 2>/dev/null || echo "000")
if [[ "$BACKEND_CODE" == "200" ]]; then
  pass "Backend health (HTTP $BACKEND_CODE)"
else
  fail "Backend health (HTTP $BACKEND_CODE)"
fi

# Database connectivity via backend
if [[ -n "${TOKEN:-}" ]]; then
  AUTH_STATUS=$(curl -ks -o /dev/null -w "%{http_code}" \
    -b "secman_auth=$TOKEN" \
    https://localhost:8443/api/auth/status)
  if [[ "$AUTH_STATUS" == "200" ]]; then
    pass "Auth status endpoint (HTTP $AUTH_STATUS)"
  else
    fail "Auth status endpoint (HTTP $AUTH_STATUS)"
  fi
fi

# Database direct check
DB_CHECK=$(docker exec secman-db mariadb -usecman -psecman-docker-pw -e "SHOW TABLES" secman 2>/dev/null | wc -l)
if [[ "$DB_CHECK" -gt 0 ]]; then
  pass "Database has tables ($((DB_CHECK - 1)) tables)"
else
  fail "Database table check"
fi

echo ""

# --- Step 6: Container status ---
echo "[step 6] Container status:"
docker ps --format "  {{.Names}}\t{{.Status}}\t{{.Ports}}" --filter "name=secman" 2>/dev/null || true
echo ""

# --- Summary ---
echo "=========================================="
echo " Test Results: $PASS passed, $FAIL failed"
echo "=========================================="

# --- Cleanup ---
if ! $KEEP_RUNNING; then
  echo ""
  echo "Cleaning up containers..."
  "$SCRIPT_DIR/stop-all.sh" --purge
else
  echo ""
  echo "Containers left running (--keep flag)."
  echo "  Frontend: https://localhost:8443"
  echo "  Stop:     ./docker/stop-all.sh"
fi

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
