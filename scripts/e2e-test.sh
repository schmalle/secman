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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BASE_URL="${BASE_URL:-http://localhost:4321}"
API_URL="${API_URL:-http://localhost:8080}"

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

# ── Phase 1 Summary ───────────────────────────────────────────

echo ""
echo "═══════════════════════════════════════════"
echo " Phase 1 (Smoke Tests): ${PASS} passed, ${FAIL} failed"
echo "═══════════════════════════════════════════"

if [ ${#ERRORS[@]} -gt 0 ]; then
  echo ""
  echo "Failures:"
  for ERR in "${ERRORS[@]}"; do
    echo "  ✗ ${ERR}"
  done
  echo ""
  echo "Skipping JS error scanner due to smoke test failures."
  exit 1
fi

# ── Phase 2: JS Error Scanner ────────────────────────────────

echo ""
echo "═══════════════════════════════════════════"
echo " Phase 2: JS Error Scanner"
echo "═══════════════════════════════════════════"
echo ""

SCANNER="${PROJECT_ROOT}/tests/js-error-scanner.sh"
if [ ! -x "$SCANNER" ]; then
  echo "FAIL: js-error-scanner — script not found or not executable at ${SCANNER}"
  exit 1
fi

# Bridge env vars for the scanner.
# SECMAN_BACKEND_URL points at the local frontend (Astro dev server) — the scanner
# navigates through the frontend, not directly to the backend API.
# Credentials (SECMAN_ADMIN_NAME/SECMAN_ADMIN_PASS) are left for the scanner's own
# 1Password resolution via op run.
export SECMAN_BACKEND_URL="${SECMAN_BACKEND_URL:-${BASE_URL}}"
export SECMAN_INSECURE="${SECMAN_INSECURE:-false}"

# Run the scanner. Its structured output ([HTTP xxx], [UNCAUGHT EXCEPTION], etc.)
# goes to stdout where the e2e-runner skill can parse it.
if "$SCANNER"; then
  echo ""
  echo "PASS: js-error-scanner — all pages clean"
  exit 0
else
  SCANNER_EXIT=$?
  echo ""
  echo "FAIL: js-error-scanner — found errors (exit code ${SCANNER_EXIT})"
  exit 1
fi
