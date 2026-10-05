#!/usr/bin/env bash
# Read-only access report with the existing Proton Pass CLI wrapper.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for tool in java pass-cli; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Error: $tool is required" >&2; exit 2; }
done
exec "$SCRIPT_DIR/secman" user-access-overview "$@"
