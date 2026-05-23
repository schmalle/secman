#!/usr/bin/env bash
# provision-test-useraws.sh — AWS Secrets Manager counterpart to
# scripts/test/provision-test-user.sh.
#
# Provisions the e2ejs `secmanuser` test account by calling /api/users on the
# admin-authenticated backend. Idempotent: exits 0 if the user already exists.
#
# Loads SECMAN_HOST / SECMAN_ADMIN_NAME / SECMAN_ADMIN_PASS / SECMAN_USER_NAME /
# SECMAN_USER_PASS from AWS Secrets Manager. Cron-safe.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../../tests/lib/secman-test-tls.sh
source "$(cd "$SCRIPT_DIR/../.." && pwd)/tests/lib/secman-test-tls.sh"
# shellcheck source=../lib/aws-secrets.sh
source "$SCRIPT_DIR/../lib/aws-secrets.sh"

secman_aws_export_envfile

: "${SECMAN_HOST:?SECMAN_HOST not present in secret}"
: "${SECMAN_ADMIN_NAME:?SECMAN_ADMIN_NAME not present in secret}"
: "${SECMAN_ADMIN_PASS:?SECMAN_ADMIN_PASS not present in secret}"
: "${SECMAN_USER_NAME:?SECMAN_USER_NAME not present in secret}"
: "${SECMAN_USER_PASS:?SECMAN_USER_PASS not present in secret}"

# SECMAN_HOST in the vault is typically the bare hostname. Build a full base
# URL — assume https unless SECMAN_HOST already has a scheme.
if [[ "$SECMAN_HOST" == http://* || "$SECMAN_HOST" == https://* ]]; then
  base_url="${SECMAN_HOST%/}"
else
  base_url="https://${SECMAN_HOST%/}"
fi

cookie_jar="$(mktemp)"
trap 'rm -f "$cookie_jar"' EXIT

echo "→ Logging in as ${SECMAN_ADMIN_NAME} at ${base_url} …"
login_status=$(curl -sS -k -o /dev/null -w '%{http_code}' \
  -c "$cookie_jar" \
  -H 'Content-Type: application/json' \
  -X POST "${base_url}/api/auth/login" \
  --data "$(printf '{"username":"%s","password":"%s"}' \
             "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS")")
if [[ "$login_status" != "200" ]]; then
  echo "✗ Admin login failed (HTTP $login_status)" >&2
  exit 1
fi

echo "→ Checking if user '${SECMAN_USER_NAME}' already exists …"
exists_payload=$(curl -sS -k -b "$cookie_jar" "${base_url}/api/users")
if echo "$exists_payload" | grep -q "\"username\":\"${SECMAN_USER_NAME}\""; then
  echo "✓ User '${SECMAN_USER_NAME}' already exists. Nothing to do."
  exit 0
fi

echo "→ Creating user '${SECMAN_USER_NAME}' (role USER) …"
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
  exit 1
fi
rm -f "$create_body"

echo "✓ User '${SECMAN_USER_NAME}' provisioned."
