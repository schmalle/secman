#!/bin/bash
# secmansercaaws.sh — AWS Secrets Manager counterpart to scripts/secmanserverca.
#
# The original wraps `pass-cli run --env-file ./secmanpp.env -- ./scripts/secmanclisupport`.
# This variant resolves the same env from Secrets Manager and runs the same
# support script directly. Cron-safe.

set -euo pipefail

# shellcheck source=lib/aws-secrets.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"

secman_aws_export_envfile

cd "${PROJECT_ROOT}"

exec ./scripts/secmanclisupport
