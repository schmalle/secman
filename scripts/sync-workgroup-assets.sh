#!/usr/bin/env bash
# Resolve the existing importer credentials, then run its AWS asset sync command.
set -euo pipefail

SECMAN_SYNC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export SECMAN_BACKEND_URL="${SECMAN_BACKEND_URL:-pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL}"
export SECMAN_ADMIN_NAME="${SECMAN_ADMIN_NAME:-pass://Test/SECMAN/SECMAN_ADMIN_NAME}"
export SECMAN_ADMIN_PASS="${SECMAN_ADMIN_PASS:-pass://Test/SECMAN/SECMAN_ADMIN_PASS}"

exec pass-cli run -- uv run --locked --project "$SECMAN_SYNC_ROOT/src/adread" \
  python "$SECMAN_SYNC_ROOT/src/adread/read.py" sync-workgroup-assets --use-system-ca "$@"
