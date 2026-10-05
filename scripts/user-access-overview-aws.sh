#!/usr/bin/env bash
# Read-only access report with the existing AWS Secrets Manager CLI wrapper.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/secmancliaws.sh" user-access-overview "$@"
