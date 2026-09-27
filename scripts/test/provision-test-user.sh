#!/usr/bin/env bash
# Provision the e2ejs test user (`secmanuser` by default) so the skill's
# normal-user pass can run. Idempotent: exits 0 if the account already exists.
#
# The isolated runner injects the target and Proton Pass credentials.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../tests/lib/secman-test-tls.sh
source "$(cd "$SCRIPT_DIR/../.." && pwd)/tests/lib/secman-test-tls.sh"

BASE_URL="${SECMAN_E2E_BACKEND_URL:-}"
# shellcheck source=lib/isolated-target.sh
source "$SCRIPT_DIR/lib/isolated-target.sh"
secman_test_require_isolated
export SECMAN_HOST="${SECMAN_E2E_BACKEND_URL:-}"
export SECMAN_USER_NAME="${SECMAN_USER_NAME:-${SECMAN_USER_USER:-}}"

provision() {
    : "${SECMAN_HOST:?SECMAN_HOST not resolved by pass-cli}"
    : "${SECMAN_ADMIN_NAME:?SECMAN_ADMIN_NAME not resolved}"
    : "${SECMAN_ADMIN_PASS:?SECMAN_ADMIN_PASS not resolved}"
    : "${SECMAN_USER_NAME:?SECMAN_USER_NAME not resolved}"
    : "${SECMAN_USER_PASS:?SECMAN_USER_PASS not resolved}"

    # SECMAN_HOST in the vault is the bare hostname (e.g. secman.covestro.net).
    # Build a full base URL — assume https unless SECMAN_HOST already has a scheme.
    local base_url
    if [[ "$SECMAN_HOST" == http://* || "$SECMAN_HOST" == https://* ]]; then
        base_url="${SECMAN_HOST%/}"
    else
        base_url="https://${SECMAN_HOST%/}"
    fi

    local cookie_jar
    cookie_jar="$(mktemp)"
    trap 'rm -f "$cookie_jar"' RETURN

    echo "→ Logging in as ${SECMAN_ADMIN_NAME} at ${base_url} …"
    local login_status
    login_status=$(curl -sS -k -o /dev/null -w '%{http_code}' \
        -c "$cookie_jar" \
        -H 'Content-Type: application/json' \
        -X POST "${base_url}/api/auth/login" \
        --data "$(printf '{"username":"%s","password":"%s"}' \
                   "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS")")
    if [[ "$login_status" != "200" ]]; then
        echo "✗ Admin login failed (HTTP $login_status)" >&2
        return 1
    fi

    echo "→ Checking if user '${SECMAN_USER_NAME}' already exists …"
    local exists_payload
    exists_payload=$(curl -sS -k -b "$cookie_jar" \
        "${base_url}/api/users")
    if echo "$exists_payload" | grep -q "\"username\":\"${SECMAN_USER_NAME}\""; then
        echo "✓ User '${SECMAN_USER_NAME}' already exists. Nothing to do."
        return 0
    fi

    echo "→ Creating user '${SECMAN_USER_NAME}' (role USER) …"
    local create_status
    local create_body
    create_body=$(mktemp)
    create_status=$(curl -sS -k -o "$create_body" -w '%{http_code}' \
        -b "$cookie_jar" \
        -H 'Content-Type: application/json' \
        -X POST "${base_url}/api/users" \
        --data "$(printf '{"username":"%s","email":"%s@e2e.local","password":"%s","roles":["USER"]}' \
                   "$SECMAN_USER_NAME" "$SECMAN_USER_NAME" "$SECMAN_USER_PASS")")
    if [[ "$create_status" != "200" && "$create_status" != "201" ]]; then
        echo "✗ Create user failed (HTTP $create_status):" >&2
        cat "$create_body" >&2
        rm -f "$create_body"
        return 1
    fi
    rm -f "$create_body"

    echo "✓ User '${SECMAN_USER_NAME}' provisioned."
}

provision
