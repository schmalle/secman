#!/usr/bin/env bash
# Creates an invitation and checks it in a browser without an authenticated session.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/../../tests/e2e/run-e2e.sh" assessment-guest-invitation.spec.ts "$@"
