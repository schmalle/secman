#!/usr/bin/env bash
set -euo pipefail

# Resolve SecMan/MCP secrets via pass-cli when secmanpp.env is present,
# then run the Python smoke test. Arguments are passed through unchanged.
#
# Usage:
#   ./test-get-all-accessible-vulnerabilities.sh                  # full smoke-test suite
#   ./test-get-all-accessible-vulnerabilities.sh <email> [flags]  # ad-hoc query for one user
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

if [[ -f "$REPO_ROOT/secmanpp.env" ]] && command -v pass-cli >/dev/null 2>&1; then
  exec pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- \
    python3 "$REPO_ROOT/scripts/mcp/test_get_all_accessible_vulnerabilities.py" "$@"
fi

exec python3 "$REPO_ROOT/scripts/mcp/test_get_all_accessible_vulnerabilities.py" "$@"
