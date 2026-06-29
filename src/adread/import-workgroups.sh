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


#!/bin/bash
# secman - CLI wrapper for secman security management tool

export MICRONAUT_ENVIRONMENTS=dev
export SECMAN_BACKEND_URL="pass://Test/SECMAN//SECMAN_BACKEND_BASE_URL"
export DB_CONNECT="pass://Test/SECMAN/DB_CONNECT"
export SECMAN_DEBUG=true

export FALCON_CLIENT_ID="pass://Test/SECMAN/FALCON_CLIENT_ID"
export FALCON_CLIENT_SECRET="pass://Test/SECMAN/FALCON_CLIENT_SECRET"
export FALCON_CLOUD_REGION="pass://Test/SECMAN/FALCON_CLOUD_REGION"
export SECMAN_OPENROUTER_API_KEY="pass://Test/SECMAN/OPENROUTER_API_KEY"
export SECMAN_ADMIN_NAME="pass://Test/SECMAN/SECMAN_ADMIN_NAME"
export SECMAN_ADMIN_PASS="pass://Test/SECMAN/SECMAN_ADMIN_PASS"
export SECMAN_MCP_KEY="pass://Test/SECMAN/SECMAN_MCP_KEY"
export SECMAN_ADMIN_EMAIL="pass://Test/SECMAN/SECMAN_ADMIN_EMAIL"
export AWS_ACCESS_KEY_ID="pass://Test/SECMAN/SECMAN_AWS_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="pass://Test/SECMAN/SECMAN_AWS_SECRET_ACCESS_KEY"
export AWS_SESSION_TOKEN="pass://Test/SECMAN/SECMAN_AWS_ACCESS_TOKEN"
export SECMAN_BACKEND_URL="pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL"
export SECMAN_INSECURE="pass://Test/SECMAN/SECMAN_SSL_ACCEPT_ALL"
export FRONTEND_URL="pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL"
export SECMAN_BACKEND_URL="pass://Test/SECMAN/SECMAN_BACKEND_BASE_URL"
export AZURE_TENANT_ID="pass://Test/SECMAN/AZURE_TENANT_ID"
export AZURE_CLIENT_ID="pass://Test/SECMAN/AZURE_CLIENT_ID"
export AZURE_CLIENT_SECRET="pass://Test/SECMAN/AZURE_CLIENT_SECRET"

pass-cli run -- python3 ./read.py --insecure --import "$@"







