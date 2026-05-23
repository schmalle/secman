#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/secman-test-tls.sh
source "$SCRIPT_DIR/../lib/secman-test-tls.sh"

# --- Check Proton Pass CLI availability ---
if ! command -v pass-cli &>/dev/null; then
  echo "ERROR: Proton Pass CLI (pass-cli) is not installed or not in PATH."
  echo "Install it with: brew install pass-cli"
  exit 1
fi

# --- Environment variables resolved via pass-cli (Proton Pass) ---
# Pass-CLI URIs follow: pass://<vault>/<item>/<field>
# Field name in vault differs from env var name in some cases (notably SECMAN_USER_USER -> SECMAN_USER_NAME).
export SECMAN_BACKEND_URL="pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL"
export SECMAN_BASE_URL="pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL"
export SECMAN_ADMIN_NAME="pass://Test/SECMAN/SECMAN_ADMIN_NAME"
export SECMAN_ADMIN_PASS="pass://Test/SECMAN/SECMAN_ADMIN_PASS"
export SECMAN_USER_USER="pass://Test/SECMAN/SECMAN_USER_NAME"
export SECMAN_USER_PASS="pass://Test/SECMAN/SECMAN_USER_PASS"

echo "=== Secman Playwright E2E Tests ==="
echo "Resolving credentials from Proton Pass (vault: Test, item: SECMAN)..."
echo ""

# --- Run Playwright with pass-cli resolving Proton Pass URIs ---
cd "$SCRIPT_DIR"
pass-cli run -- npx playwright test "$@"
