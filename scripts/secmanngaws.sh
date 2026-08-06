#!/bin/bash
# secmanngaws.sh — AWS Secrets Manager counterpart to scripts/secmanng.
#
# Same as secmancliaws.sh but additionally:
#   - Honours SECMAN_INSECURE → -Dsecman.ssl.insecure=true
#   - Disables Micronaut's datasource init failure timeout
#
# Cron-safe.

set -euo pipefail

# shellcheck source=lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"

secman_aws_require_cli_jar
secman_aws_export_envfile

cd "${PROJECT_ROOT}"

SSL_FLAG=""
INSECURE_LOWER="$(printf '%s' "${SECMAN_INSECURE:-}" | tr '[:upper:]' '[:lower:]')"
case "${INSECURE_LOWER}" in
  true|1|yes) SSL_FLAG="-Dsecman.ssl.insecure=true" ;;
esac

exec java ${SSL_FLAG} \
  -Ddatasources.default.initialization-fail-timeout=-1 \
  -jar "${CLI_JAR}" "$@"
