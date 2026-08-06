#!/bin/bash
# secmancliaws.sh — AWS Secrets Manager counterpart to scripts/secmancli.
#
# General-purpose CLI wrapper. All env secrets (CrowdStrike, admin auth,
# AWS creds, MCP key, DB) come from Secrets Manager instead of pass-cli.
#
# Usage: ./scripts/secmancliaws.sh <command> [options]
# Cron-safe.

set -euo pipefail

# shellcheck source=lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"

secman_aws_require_cli_jar
secman_aws_export_envfile

cd "${PROJECT_ROOT}"

exec java -Xmx512m -jar "${CLI_JAR}" "$@"
