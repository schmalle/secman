#!/bin/bash
# release-e2e-testaws.sh — AWS Secrets Manager counterpart to scripts/release-e2e-test.sh.
#
# The original release-e2e-test.sh treats SECMAN_ADMIN_NAME / SECMAN_ADMIN_PASS /
# SECMAN_MCP_KEY as either plain text or pass:// URIs. This wrapper resolves them
# from Secrets Manager, exports the plain-text values, and re-execs the original
# script — which then takes the plain-text path and skips its pass-cli branch.
#
# Cron-safe.

set -euo pipefail

# shellcheck source=lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"

secman_aws_export_envfile

# release-e2e-test.sh reads SECMAN_BASE_URL (note: BASE, not BACKEND) and falls
# back to http://localhost:8080. Bridge SECMAN_BACKEND_URL → SECMAN_BASE_URL when
# only the former is in the secret.
if [ -z "${SECMAN_BASE_URL:-}" ] && [ -n "${SECMAN_BACKEND_URL:-}" ]; then
  export SECMAN_BASE_URL="${SECMAN_BACKEND_URL}"
fi

cd "${PROJECT_ROOT}"

exec ./scripts/release-e2e-test.sh "$@"
