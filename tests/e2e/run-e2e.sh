#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Check 1Password CLI availability ---
if ! command -v op &>/dev/null; then
  echo "ERROR: 1Password CLI (op) is not installed or not in PATH."
  echo "Install it with: brew install 1password-cli"
  exit 1
fi

# --- Environment variables with 1Password URIs ---
#export SECMAN_BASE_URL="${SECMAN_BASE_URL:-http://localhost:4321}"


export SECMAN_BACKEND_URL="op://test/secman/SECMAN_HOST"
export SECMAN_ADMIN_NAME="op://test/secman/SECMAN_ADMIN_NAME"
export SECMAN_ADMIN_PASS="op://test/secman/SECMAN_ADMIN_PASS"
export SECMAN_USER_USER="op://test/secman/SECMAN_USER_USER"
export SECMAN_USER_PASS="op://test/secman/SECMAN_USER_PASS"

echo "=== Secman Playwright E2E Tests ==="
echo "Base URL: ${SECMAN_BASE_URL}"
echo "Resolving credentials from 1Password..."
echo ""

# --- Run Playwright with op resolving 1Password URIs ---
cd "$SCRIPT_DIR"
op run -- npx playwright test "$@"
