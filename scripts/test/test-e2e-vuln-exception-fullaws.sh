#!/usr/bin/env bash
# test-e2e-vuln-exception-fullaws.sh — AWS Secrets Manager counterpart to
# scripts/test/test-e2e-vuln-exception-full.sh.
#
# The original test script reads SECMAN_MCP_KEY / SECMAN_ADMIN_EMAIL /
# SECMAN_ADMIN_NAME / SECMAN_ADMIN_PASS from the environment and aborts if any
# are missing. This wrapper resolves them from Secrets Manager and re-execs
# the original with the env populated, so the original's pre-flight check
# passes without pass-cli.
#
# Cron-safe.

set -euo pipefail

# shellcheck source=../lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/aws-secrets.sh"

secman_aws_export_envfile

cd "${PROJECT_ROOT}"

exec ./scripts/test/test-e2e-vuln-exception-full.sh "$@"
