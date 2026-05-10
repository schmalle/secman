#!/bin/bash
# backendaws.sh — AWS Secrets Manager counterpart to scripts/backend.
#
# Replaces:  pass-cli run --env-file ./secmanpp.env -- gradle :backendng:run
#
# Cron-safe: relies on scripts/lib/aws-secrets.sh to bootstrap PATH, source
# SDKMAN/nvm, and load the secret. See docs/AWS.md.

set -euo pipefail

# shellcheck source=lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"

secman_aws_export_envfile

cd "${PROJECT_ROOT}"

if command -v gradle >/dev/null 2>&1; then
  exec gradle :backendng:run
else
  exec ./gradlew :backendng:run
fi
