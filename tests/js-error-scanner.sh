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
export SECMAN_USERNAME="${SECMAN_USERNAME:-op://test/secman/SECMAN_USERNAME}"
export SECMAN_PASSWORD="${SECMAN_PASSWORD:-op://test/secman/SECMAN_PASSWORD}"
export SECMAN_BACKEND_URL="${SECMAN_BACKEND_URL:-op://test/secman/SECMAN_HOST}"
export SECMAN_INSECURE="${SECMAN_INSECURE:-op://test/secman/SECMAN_SSL_ACCEPT_ALL}"

echo "=== Secman JavaScript Error Scanner ==="
echo "Resolving credentials from 1Password..."
echo ""

# --- Run Node.js scanner with op resolving 1Password URIs ---
# Uses op run to resolve op:// references, then invokes the scanner.
# SSL flag is detected inside the subshell after op has resolved the value.
op run -- bash -c '
INSECURE_LOWER="$(printf "%s" "$SECMAN_INSECURE" | tr "[:upper:]" "[:lower:]")"
case "$INSECURE_LOWER" in
    true|1|yes)
        export NODE_TLS_REJECT_UNAUTHORIZED=0
        ;;
esac
node "'"$SCRIPT_DIR"'/js-error-scanner.mjs"
'
