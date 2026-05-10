#!/bin/bash
# Frontend dev start using AWS Secrets Manager (alternative to startfrontenddev.sh + pass-cli).
#
# Configuration (env vars):
#   SECMAN_AWS_SECRET_ID  Secrets Manager secret name or ARN  (default: secman/dev)
#   AWS_REGION            AWS region for the secret           (default: eu-central-1)
#   AWS_PROFILE           Optional named profile from ~/.aws/credentials
#
# Required tools: aws CLI v2, jq, node/npm. See AWS.md.

set -euo pipefail

SECRET_ID="${SECMAN_AWS_SECRET_ID:-secman/dev}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-central-1}}"

command -v aws >/dev/null 2>&1 || { echo "ERROR: aws CLI is required. See AWS.md."; exit 1; }
command -v jq  >/dev/null 2>&1 || { echo "ERROR: jq is required. See AWS.md.";       exit 1; }
command -v npm >/dev/null 2>&1 || { echo "ERROR: npm is required.";                   exit 1; }

# Resolve the project's frontend directory relative to this script so it works from
# anywhere on the filesystem.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/../src/frontend"

echo "Fetching secret '${SECRET_ID}' from AWS Secrets Manager (region ${REGION})..."
SECRET_JSON=$(aws secretsmanager get-secret-value \
  --secret-id "${SECRET_ID}" \
  --region "${REGION}" \
  --query SecretString \
  --output text)

if [ -z "${SECRET_JSON}" ] || [ "${SECRET_JSON}" = "None" ]; then
  echo "ERROR: empty SecretString for ${SECRET_ID}"; exit 1
fi

extract() { printf '%s' "${SECRET_JSON}" | jq -r --arg k "$1" '.[$k] // empty'; }

maybe_export() {
  local var="$1" key="$2" val
  val="$(extract "$key")"
  if [ -n "$val" ]; then export "$var=$val"; fi
}

maybe_export SECMAN_DOMAIN  SECMAN_BACKEND_BASE_URL
maybe_export SECMAN_HOST    SECMAN_HOST

exec npm run dev
