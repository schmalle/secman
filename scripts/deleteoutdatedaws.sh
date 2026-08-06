#!/bin/bash
# deleteoutdatedaws.sh — AWS Secrets Manager counterpart to scripts/deleteoutdated.sh.
#
# Runs the secman CLI's `delete-asset-not-seen` action in dry-run mode against
# assets older than 30 days. Resolves the same env vars via Secrets Manager
# instead of pass-cli. Cron-safe.

set -euo pipefail

# shellcheck source=lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"

secman_aws_require_cli_jar
secman_aws_export_envfile

export MICRONAUT_ENVIRONMENTS=dev
export SECMAN_DEBUG=true

cd "${PROJECT_ROOT}"

# Mirror the parent script: SECMAN_ADMIN_NAME / SECMAN_ADMIN_PASS / SECMAN_INSECURE
# are read from the environment by the CLI, never passed via argv (so a literal
# placeholder cannot leak into the request).
exec java -Xmx4g -Xms2g -jar "${CLI_JAR}" delete-asset-not-seen 30 --dry-run --verbose
