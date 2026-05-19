#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

## TLS/host policy enforcement
CURRENT_BACKEND_URL="${SECMAN_BACKEND_URL:-}"
if [ -n "$CURRENT_BACKEND_URL" ] && [ "$CURRENT_BACKEND_URL" != "https://secman.covestro.net" ] && [[ "$CURRENT_BACKEND_URL" != pass://* ]]; then
  echo "ERROR: SECMAN_BACKEND_URL must be https://secman.covestro.net (or pass:// URI)."
  exit 2
fi
if [ "${SECMAN_INSECURE:-}" = "true" ] || [ "${SECMAN_INSECURE:-}" = "1" ] || [ "${SECMAN_INSECURE:-}" = "yes" ]; then
  echo "ERROR: SECMAN_INSECURE must not be true for e2ejs runs."
  exit 2
fi

# --- Local dev auto-detection (disabled for policy compliance) ---
# If a local frontend dev server is reachable on the configured port, override
# the backend URL to point at it. This prevents the scanner from accidentally
# targeting the production host (pass://Test/SECMAN/SECMAN_HOST) when an e2e
# run is clearly aimed at the local stack started by ./scripts/startfrontenddev.sh.
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

# --- Environment variables with Proton Pass URIs ---
# Host + TLS flag (shared by both runs).
export SECMAN_BACKEND_URL="${SECMAN_BACKEND_URL:-pass://Test/SECMAN/SECMAN_HOST}"
export SECMAN_INSECURE="${SECMAN_INSECURE:-false}"

# Admin credentials.
export SECMAN_ADMIN_NAME="${SECMAN_ADMIN_NAME:-pass://Test/SECMAN/SECMAN_ADMIN_NAME}"
export SECMAN_ADMIN_PASS="${SECMAN_ADMIN_PASS:-pass://Test/SECMAN/SECMAN_ADMIN_PASS}"

# Normal-user credentials. The vault field name for the username is
# SECMAN_USER_NAME (not SECMAN_USER_USER); the env var the scanner consumes
# is SECMAN_USER_USER for parity with tests/e2e/run-e2e.sh.
export SECMAN_USER_USER="${SECMAN_USER_USER:-pass://Test/SECMAN/SECMAN_USER_NAME}"
export SECMAN_USER_PASS="${SECMAN_USER_PASS:-pass://Test/SECMAN/SECMAN_USER_PASS}"

echo "=== Secman JavaScript Error Scanner (Proton Pass, dual-role) ==="
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
NEEDS_PASS=false
for VAR in \
  "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS" \
  "$SECMAN_USER_USER"  "$SECMAN_USER_PASS" \
  "$SECMAN_BACKEND_URL" "$SECMAN_INSECURE"; do
  case "$VAR" in
    pass://*) NEEDS_PASS=true; break ;;
  esac
done

# Inner script run inside `pass-cli run` (or directly when creds are pre-resolved).
# Executes the scanner twice — once as admin, once as the normal user — and
# aggregates exit codes (any failure -> overall failure).
INNER_SCRIPT='
set -u
SCRIPT_DIR='"'"$SCRIPT_DIR"'"'

INSECURE_LOWER="$(printf "%s" "$SECMAN_INSECURE" | tr "[:upper:]" "[:lower:]")"
case "$INSECURE_LOWER" in
    true|1|yes) export NODE_TLS_REJECT_UNAUTHORIZED=0 ;;
esac

OVERALL=0

run_role() {
    local role="$1" user="$2" pass="$3"
    echo ""
    echo "----------------------------------------------------------------"
    echo " Run as ${role} (user=${user})"
    echo "----------------------------------------------------------------"
    SECMAN_LOGIN_USER="$user" \
    SECMAN_LOGIN_PASS="$pass" \
    SECMAN_RUN_LABEL="$role" \
    SECMAN_SCAN_JSON_OUT="$SCRIPT_DIR/../.e2e-logs/js-scan-${role}.json" \
        node "$SCRIPT_DIR/js-error-scanner.mjs"
    local rc=$?
    echo "[$role] scanner exit code: $rc"
    if [ $rc -ne 0 ] && [ $OVERALL -eq 0 ]; then
        OVERALL=$rc
    elif [ $rc -eq 2 ]; then
        # Fatal trumps page-error.
        OVERALL=2
    fi
}

run_role "admin" "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS"
run_role "user"  "$SECMAN_USER_USER"  "$SECMAN_USER_PASS"

echo ""
echo "================================================================"
echo " Combined exit code: $OVERALL"
echo "================================================================"
exit $OVERALL
'

if [ "$NEEDS_PASS" = true ]; then
  if ! command -v pass-cli &>/dev/null; then
    echo "ERROR: Proton Pass CLI (pass-cli) is not installed or not in PATH."
    echo "Install it with: brew install pass-cli"
    exit 1
  fi

  echo "Resolving credentials from Proton Pass..."
  echo ""

  # Single pass-cli run resolves all four credentials + host + TLS flag at once,
  # so the user is prompted at most once. The inner script then loops over both
  # roles using the already-resolved values.
  pass-cli run -- env SECMAN_INSECURE=false bash -c "$INNER_SCRIPT"
else
  echo "Using pre-resolved credentials from environment."
  echo ""
  bash -c "$INNER_SCRIPT"
fi
