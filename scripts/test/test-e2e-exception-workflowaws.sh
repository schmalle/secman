#!/bin/bash
# test-e2e-exception-workflowaws.sh — AWS Secrets Manager counterpart to
# scripts/test/test-e2e-exception-workflow.sh.
#
# Loads the secret block, then delegates to the same support script the
# pass-cli wrapper does.
#
# Cron-safe.

set -euo pipefail

# shellcheck source=../lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/aws-secrets.sh"

secman_aws_export_envfile

cd "${PROJECT_ROOT}"

exec ./scripts/test/test-e2e-exception-workflowsupport.sh "$@"
