#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Environment variables with 1Password URIs ---
export SECMAN_USERNAME="${SECMAN_USERNAME:-op://test/secman/SECMAN_USERNAME}"
export SECMAN_PASSWORD="${SECMAN_PASSWORD:-op://test/secman/SECMAN_PASSWORD}"
export SECMAN_BACKEND_URL="${SECMAN_BACKEND_URL:-op://test/secman/SECMAN_HOST}"
export SECMAN_INSECURE="${SECMAN_INSECURE:-op://test/secman/SECMAN_SSL_ACCEPT_ALL}"

echo "=== Secman JavaScript Error Scanner ==="

# --- Determine if 1Password resolution is needed ---
# If any env var still contains an op:// URI, we need op run to resolve it.
# If all values are already plain text, skip op run entirely.
NEEDS_OP=false
for VAR in "$SECMAN_USERNAME" "$SECMAN_PASSWORD" "$SECMAN_BACKEND_URL" "$SECMAN_INSECURE"; do
  case "$VAR" in
    op://*) NEEDS_OP=true; break ;;
  esac
done

run_scanner() {
  INSECURE_LOWER="$(printf "%s" "$SECMAN_INSECURE" | tr "[:upper:]" "[:lower:]")"
  case "$INSECURE_LOWER" in
      true|1|yes)
          export NODE_TLS_REJECT_UNAUTHORIZED=0
          ;;
  esac
  node "$SCRIPT_DIR/js-error-scanner.mjs"
}

if [ "$NEEDS_OP" = true ]; then
  # Check 1Password CLI availability
  if ! command -v op &>/dev/null; then
    echo "ERROR: 1Password CLI (op) is not installed or not in PATH."
    echo "Install it with: brew install 1password-cli"
    exit 1
  fi

  echo "Resolving credentials from 1Password..."
  echo ""

  # Uses op run to resolve op:// references, then invokes the scanner.
  op run -- bash -c '
INSECURE_LOWER="$(printf "%s" "$SECMAN_INSECURE" | tr "[:upper:]" "[:lower:]")"
case "$INSECURE_LOWER" in
    true|1|yes)
        export NODE_TLS_REJECT_UNAUTHORIZED=0
        ;;
esac
node "'"$SCRIPT_DIR"'/js-error-scanner.mjs"
'
else
  echo "Using pre-resolved credentials from environment."
  echo ""
  run_scanner
fi
