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

# Override DB_CONNECT to the local-loopback default the original script uses
# when running near the database. If the secret carried its own DB_CONNECT,
# secman_aws_export_envfile already exported it; we only set the fallback when
# it's still empty.
export DB_CONNECT="${DB_CONNECT:-jdbc:mariadb://127.0.0.1:3306/secman?useSsl=true}"

# Same caveat as deleteoutdatedaws.sh: --username/--password arrive via env
# (the CLI reads SECMAN_ADMIN_NAME / SECMAN_ADMIN_PASS) or as literal CLI args.
# Pass them as args using the *resolved* values so the CLI sees real credentials,
# not pass:// placeholders.
exec java -Xmx4g -Xms2g -jar "${CLI_JAR}" \
  query servers \
  --device-type SERVER \
  --severity CRITICAL,HIGH \
  --min-days-open 1 \
  --save \
  --username "${SECMAN_ADMIN_NAME}" \
  --password "${SECMAN_ADMIN_PASS}" \
  --last-seen-days 30
