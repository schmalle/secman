#!/bin/bash
# importaws.sh — AWS Secrets Manager counterpart to scripts/import.sh.
#
# Imports CrowdStrike "query servers" data into the backend. Cron-safe.

set -euo pipefail

# shellcheck source=lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"

secman_aws_require_cli_jar
secman_aws_export_envfile

cd "${PROJECT_ROOT}"

# Use the same import arguments after AWS has supplied the environment.
export SECMAN_IMPORT_ENV_READY=1
exec bash "$PROJECT_ROOT/scripts/import.sh" "$@"
