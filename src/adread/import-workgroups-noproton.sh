#!/usr/bin/env bash
# import-workgroups-noproton.sh — plain-env variant (no Proton Pass)
#
# Reads AWS-prefixed Azure AD groups and creates matching secman workgroups,
# adding AD group members to each workgroup (additive / idempotent).
#
# Required env vars (export them before running, or put them in adread.env.local):
#   AZURE_TENANT_ID       — Azure AD tenant ID
#   AZURE_CLIENT_ID       — service principal client ID
#   AZURE_CLIENT_SECRET   — service principal secret
#   SECMAN_BACKEND_URL    — secman backend URL, e.g. http://localhost:8080
#   SECMAN_ADMIN_NAME     — secman ADMIN username
#   SECMAN_ADMIN_PASS     — secman ADMIN password
#
# Usage:
#   ./import-workgroups-noproton.sh              # real import
#   ./import-workgroups-noproton.sh --dry-run    # log what would be done, no writes
#
# Prerequisites: uv in PATH.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load local secrets file if present (gitignored — never commit it).
LOCAL_ENV="$SCRIPT_DIR/adread.env.local"
if [ -f "$LOCAL_ENV" ]; then
  set -a
  # shellcheck source=/dev/null
  source "$LOCAL_ENV"
  set +a
fi

exec uv run python "$SCRIPT_DIR/read.py" --import "$@"
