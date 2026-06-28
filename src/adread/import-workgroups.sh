#!/usr/bin/env bash
# import-workgroups.sh — Proton Pass variant
#
# Reads AWS-prefixed Azure AD groups and creates matching secman workgroups,
# adding AD group members to each workgroup (additive / idempotent).
#
# Secrets are injected from Proton Pass via adread.env (pass:// references).
# The six entries in adread.env that must exist in Proton Pass:
#   Test/SECMAN/AZURE_TENANT_ID    — Azure AD tenant ID
#   Test/SECMAN/AZURE_CLIENT_ID    — service principal client ID
#   Test/SECMAN/AZURE_CLIENT_SECRET — service principal secret
#   Test/SECMAN/SECMAN_BACKEND_URL — secman backend URL, e.g. http://localhost:8080
#   Test/SECMAN/SECMAN_ADMIN_NAME  — secman ADMIN username
#   Test/SECMAN/SECMAN_ADMIN_PASS  — secman ADMIN password
#
# Usage:
#   ./import-workgroups.sh              # real import
#   ./import-workgroups.sh --dry-run    # log what would be done, no writes
#
# Prerequisites: pass-cli installed and logged in, uv in PATH.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec pass-cli run --env-file "$SCRIPT_DIR/adread.env" -- \
  uv run python "$SCRIPT_DIR/read.py" --import "$@"
