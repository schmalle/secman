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
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${SECMAN_E2E_BACKEND_URL:-}"
source "$SCRIPT_DIR/lib/isolated-target.sh"
secman_test_require_isolated
exec "$SCRIPT_DIR/provision-test-user.sh" "$@"
