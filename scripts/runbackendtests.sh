#!/bin/bash
# Run backend tests in a fresh, runner-owned MariaDB schema. Never aim
# Hibernate create-drop at the persistent development database.
#
# Usage:
#   ./scripts/runbackendtests.sh                                  # whole suite
#   ./scripts/runbackendtests.sh --tests "*VulnerabilityService*" # filter (any gradle test args)

set -euo pipefail

# Repo root = parent of this script's directory, so the script works from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

if [[ -z "${SECMAN_TEST_ISOLATED_DB:-}" ]]; then
    exec "$REPO_ROOT/scripts/test/run-isolated-e2e.sh" --database-only -- "$0" "$@"
fi

BASE_URL="${SECMAN_E2E_BACKEND_URL:-}"
# shellcheck source=test/lib/isolated-target.sh
source "$REPO_ROOT/scripts/test/lib/isolated-target.sh"
secman_test_require_isolated
[[ "$TEST_DB_URL" == "$DB_CONNECT" && "$TEST_DB_USERNAME" == "$DB_USERNAME" ]] || {
    echo "TEST_DB_* does not match the disposable database" >&2; exit 2;
}

echo "Running backendng tests against ${TEST_DB_URL} (user: ${TEST_DB_USERNAME})"
exec ./gradlew :backendng:test "$@"
