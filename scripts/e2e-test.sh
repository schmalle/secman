#!/usr/bin/env bash
# e2e-test.sh — Example E2E test script.
#
# The e2e-runner skill expects this script to:
#   1. Exit 0 if all tests pass
#   2. Exit non-zero if any test fails
#   3. Print structured output so failures can be parsed
#
# Output format (one line per test):
#   PASS: <test-name>
#   FAIL: <test-name> — <error-message>
#
# You can use any testing tool (curl, httpie, playwright, etc.)
# as long as the output follows this convention.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:4321}"
API_URL="${API_URL:-http://localhost:9000}"

PASS=0
FAIL=0
ERRORS=()

# ── Helper ────────────────────────────────────────────────────

assert_http() {
  local NAME="$1"
  local URL="$2"
  local EXPECTED_STATUS="${3:-200}"

  local STATUS
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "$URL" 2>/dev/null || echo "000")

  if [ "$STATUS" = "$EXPECTED_STATUS" ]; then
    echo "PASS: ${NAME}"
    PASS=$((PASS + 1))
  else
    local MSG="expected HTTP ${EXPECTED_STATUS}, got ${STATUS}"
    echo "FAIL: ${NAME} — ${MSG}"
    ERRORS+=("${NAME}: ${MSG}")
    FAIL=$((FAIL + 1))
  fi
}

assert_body_contains() {
  local NAME="$1"
  local URL="$2"
  local NEEDLE="$3"

  local BODY
  BODY=$(curl -s --connect-timeout 5 "$URL" 2>/dev/null || echo "")

  # Use grep -c to avoid SIGPIPE issues with large bodies and pipefail
  local MATCH_COUNT
  MATCH_COUNT=$(echo "$BODY" | grep -c "$NEEDLE" 2>/dev/null || true)

  if [ "$MATCH_COUNT" -gt 0 ]; then
    echo "PASS: ${NAME}"
    PASS=$((PASS + 1))
  else
    local MSG="response body does not contain '${NEEDLE}'"
    echo "FAIL: ${NAME} — ${MSG}"
    ERRORS+=("${NAME}: ${MSG}")
    FAIL=$((FAIL + 1))
  fi
}

# ── Tests ─────────────────────────────────────────────────────

echo "═══════════════════════════════════════════"
echo " E2E Test Suite — $(date)"
echo "═══════════════════════════════════════════"
echo ""

# Backend health endpoint (public, no auth required)
assert_http "backend-health" "${API_URL}/health"
assert_body_contains "backend-health-json" "${API_URL}/health" '"status":"UP"'

# Frontend serves HTML
assert_http "frontend-serves" "${BASE_URL}/"
assert_body_contains "frontend-has-astro" "${BASE_URL}/" 'data-astro-cid'

# Public API endpoints (no auth required)
assert_http "api-login-endpoint" "${API_URL}/api/auth/login" "405"
assert_http "api-identity-providers" "${API_URL}/api/identity-providers/enabled"
assert_http "api-maintenance-banners" "${API_URL}/api/maintenance-banners/active"

# Auth-protected endpoints should return 401 without token
assert_http "api-auth-requires-token" "${API_URL}/api/auth/status" "401"
assert_http "api-assets-requires-token" "${API_URL}/api/assets" "401"

# ── Summary ───────────────────────────────────────────────────

echo ""
echo "═══════════════════════════════════════════"
echo " Results: ${PASS} passed, ${FAIL} failed"
echo "═══════════════════════════════════════════"

if [ ${#ERRORS[@]} -gt 0 ]; then
  echo ""
  echo "Failures:"
  for ERR in "${ERRORS[@]}"; do
    echo "  ✗ ${ERR}"
  done
  exit 1
fi

exit 0
