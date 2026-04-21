#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Local dev auto-detection ---
# If a local frontend dev server is reachable on the configured port, override
# the backend URL to point at it. This prevents the scanner from accidentally
# targeting the production host (pass://Test/SECMAN/SECMAN_HOST) when an e2e
# run is clearly aimed at the local stack started by ./scriptpp/startfrontenddev.sh.
#
# Detection rules:
#   - SECMAN_BACKEND_URL is "preference-bearing" only when it starts with
#     http:// or https://. If it's unset OR still a pass:// URI, the user has
#     expressed no explicit preference and we may auto-substitute localhost.
#   - We probe TCP port reachability via bash's /dev/tcp (no curl/nc needed,
#     no HTTP-status pitfalls like 302/401 on the Astro root page).
LOCAL_FRONTEND_HOST="${SECMAN_LOCAL_FRONTEND_HOST:-localhost}"
LOCAL_FRONTEND_PORT="${SECMAN_LOCAL_FRONTEND_PORT:-4321}"
LOCAL_FRONTEND_URL="http://${LOCAL_FRONTEND_HOST}:${LOCAL_FRONTEND_PORT}"

probe_tcp() {
  # Returns 0 if TCP connect to $1:$2 succeeds, 1 otherwise. Pure bash.
  (exec 3<>/dev/tcp/"$1"/"$2") 2>/dev/null && {
    exec 3<&- 3>&-
    return 0
  }
  return 1
}

CURRENT_BACKEND_URL="${SECMAN_BACKEND_URL:-}"
USER_PROVIDED_HTTP=false
case "$CURRENT_BACKEND_URL" in
  http://*|https://*) USER_PROVIDED_HTTP=true ;;
esac

DETECTED_LOCAL=false
if [ "$USER_PROVIDED_HTTP" = false ]; then
  if probe_tcp "$LOCAL_FRONTEND_HOST" "$LOCAL_FRONTEND_PORT"; then
    SECMAN_BACKEND_URL="$LOCAL_FRONTEND_URL"
    SECMAN_INSECURE="true"
    DETECTED_LOCAL=true
  fi
fi

# --- Environment variables with Proton Pass URIs ---
export SECMAN_ADMIN_NAME="${SECMAN_ADMIN_NAME:-pass://Test/SECMAN/SECMAN_ADMIN_NAME}"
export SECMAN_ADMIN_PASS="${SECMAN_ADMIN_PASS:-pass://Test/SECMAN/SECMAN_ADMIN_PASS}"
export SECMAN_BACKEND_URL="${SECMAN_BACKEND_URL:-pass://Test/SECMAN/SECMAN_HOST}"
export SECMAN_INSECURE="${SECMAN_INSECURE:-pass://Test/SECMAN/SECMAN_SSL_ACCEPT_ALL}"

echo "=== Secman JavaScript Error Scanner (Proton Pass) ==="
if [ "$DETECTED_LOCAL" = true ]; then
  echo "Detected local frontend at $LOCAL_FRONTEND_URL — targeting it instead of production."
else
  case "$SECMAN_BACKEND_URL" in
    http://localhost*|http://127.0.0.1*) ;;
    pass://*) echo "No local frontend on $LOCAL_FRONTEND_HOST:$LOCAL_FRONTEND_PORT — falling back to Proton Pass-resolved host." ;;
    *) echo "Using explicitly provided SECMAN_BACKEND_URL=$SECMAN_BACKEND_URL." ;;
  esac
fi

# --- Determine if Proton Pass resolution is needed ---
# If any env var still contains a pass:// URI, we need pass-cli run to resolve it.
# If all values are already plain text, skip pass-cli run entirely.
NEEDS_PASS=false
for VAR in "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS" "$SECMAN_BACKEND_URL" "$SECMAN_INSECURE"; do
  case "$VAR" in
    pass://*) NEEDS_PASS=true; break ;;
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

if [ "$NEEDS_PASS" = true ]; then
  # Check Proton Pass CLI availability
  if ! command -v pass-cli &>/dev/null; then
    echo "ERROR: Proton Pass CLI (pass-cli) is not installed or not in PATH."
    echo "Install it with: brew install pass-cli"
    exit 1
  fi

  echo "Resolving credentials from Proton Pass..."
  echo ""

  # Uses pass-cli run to resolve pass:// references, then invokes the scanner.
  pass-cli run -- bash -c '
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
