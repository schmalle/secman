#!/usr/bin/env bash
# Resolve production credentials from AWS Secrets Manager, then run asset sync.
set -euo pipefail

# Require an explicit target instead of the shared helper's development default.
: "${SECMAN_AWS_SECRET_ID:?Set SECMAN_AWS_SECRET_ID to the production secret name or ARN}"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/aws-secrets.sh"
secman_aws_load_secret

if ! printf '%s' "${SECMAN_AWS_SECRET_JSON}" | jq -es '
  length == 1 and (.[0] | type == "object" and
  (.SECMAN_BACKEND_BASE_URL | . == null or type == "string") and
  ([(.SECMAN_BACKEND_BASE_URL // .SECMAN_BACKEND_URL),
    .SECMAN_ADMIN_NAME, .SECMAN_ADMIN_PASS] |
   all(type == "string" and length > 0 and index("\u0000") == null)))
' >/dev/null 2>&1; then
  echo "ERROR: secret must contain non-empty string fields SECMAN_BACKEND_BASE_URL (or SECMAN_BACKEND_URL), SECMAN_ADMIN_NAME and SECMAN_ADMIN_PASS" >&2
  exit 1
fi

# The selected secret supplies all three settings; never mix in stale credentials.
# NUL-delimited reads preserve trailing newlines that shell substitution strips.
{
  IFS= read -r -d '' SECMAN_BACKEND_URL
  IFS= read -r -d '' SECMAN_ADMIN_NAME
  IFS= read -r -d '' SECMAN_ADMIN_PASS
} < <(printf '%s' "${SECMAN_AWS_SECRET_JSON}" | jq -j '
  (.SECMAN_BACKEND_BASE_URL // .SECMAN_BACKEND_URL), "\u0000",
  .SECMAN_ADMIN_NAME, "\u0000", .SECMAN_ADMIN_PASS, "\u0000"
')
export SECMAN_BACKEND_URL SECMAN_ADMIN_NAME SECMAN_ADMIN_PASS
unset SECMAN_AWS_SECRET_JSON

exec uv run --locked --project "${PROJECT_ROOT}/src/adread" \
  python "${PROJECT_ROOT}/src/adread/read.py" sync-workgroup-assets "$@"
