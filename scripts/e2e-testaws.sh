#!/bin/bash
# e2e-testaws.sh — AWS Secrets Manager counterpart to scripts/e2e-test.sh.
#
# Loads secrets from Secrets Manager, generates a fresh JWT_SECRET, then
# delegates to the existing scripts/e2e-test.2.sh harness. Cron-safe.

set -euo pipefail

# shellcheck source=lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"

secman_aws_export_envfile

export MICRONAUT_ENVIRONMENTS=dev
export SECMAN_DEBUG=true
export JWT_SECRET="$(openssl rand -base64 48)"

cd "${PROJECT_ROOT}"

exec ./scripts/e2e-test.2.sh
