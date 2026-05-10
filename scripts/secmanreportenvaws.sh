#!/bin/bash
# secmanreportenvaws.sh — AWS Secrets Manager counterpart to scripts/secmanreportenv.
#
# The original scripts/secmanreportenv only sets up the environment (Falcon,
# admin auth, AWS, DB) and verifies the CLI JAR exists, but does not run a
# command. This AWS variant mirrors that exactly — useful for sourcing when a
# report-style invocation needs the same env block, e.g.:
#
#   source ./scripts/secmanreportenvaws.sh
#   java -jar src/cli/build/libs/cli-0.1.0-all.jar <report command>
#
# Cron-safe via the shared lib.

set -euo pipefail

# shellcheck source=lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"

secman_aws_export_envfile
secman_aws_require_cli_jar

cd "${PROJECT_ROOT}"
