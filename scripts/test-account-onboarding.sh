#!/usr/bin/env bash
# Manual delivery rehearsal using the saved web-UI policy. This sends real email.
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: ./scripts/test-account-onboarding.sh recipient@example.com" >&2
    echo "Uses the saved AWS Account Onboarding settings and sends a simulated owner email." >&2
    exit 2
fi

recipient="$1"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# Validate before building or authenticating. No shell evaluation of the supplied address.
account_id="$(python3 - "$recipient" <<'PY'
import re
import secrets
import sys

if not re.fullmatch(r'[^\s@,;:<>"\\]+@[^\s@,;:<>"\\]+\.[^\s@,;:<>"\\]+', sys.argv[1]):
    sys.exit('Supply one valid email address.')
# The simulation does not contact AWS or import account mappings.
print(f'999{secrets.randbelow(1_000_000_000):09d}')
PY
)"

echo "Building the CLI for this checkout (no tests)..."
./gradlew :cli:shadowJar --console=plain
echo "Simulating account $account_id using the onboarding policy saved in the web UI."
echo "This sends real email; DIRECT mode also retains a simulated risk assessment."
exec ./scripts/secman manage-user-mappings simulate-onboarding \
    --aws-account-id "$account_id" \
    --owner-email "$recipient" \
    --use-default-settings
