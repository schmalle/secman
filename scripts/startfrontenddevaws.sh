#!/bin/bash
# Frontend dev start using AWS Secrets Manager (alternative to startfrontenddev.sh + pass-cli).
#
# Cron-safe: bootstraps PATH and sources SDKMAN/nvm if installed, so node/npm and any
# SDKMAN-managed tools are visible even under cron's minimal environment.
#
# Configuration (env vars):
#   SECMAN_AWS_SECRET_ID  Secrets Manager secret name or ARN  (default: secman/dev)
#   AWS_REGION            AWS region for the secret           (default: eu-central-1)
#   AWS_PROFILE           Optional named profile from ~/.aws/credentials
#   SDKMAN_DIR            Override SDKMAN install location    (default: $HOME/.sdkman)
#   NVM_DIR               Override nvm install location       (default: $HOME/.nvm)
#
# Required tools: aws CLI v2, jq, node/npm. See docs/AWS.md.

set -euo pipefail

# --- Cron-safe environment bootstrap -----------------------------------------
: "${HOME:=$(getent passwd "$(id -u)" 2>/dev/null | cut -d: -f6)}"
export HOME

export PATH="${HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# SDKMAN — usually for java/gradle/etc., but harmless to source here in case
# anyone manages node via sdkman as well.
SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
if [ -s "${SDKMAN_DIR}/bin/sdkman-init.sh" ]; then
  set +u
  # shellcheck disable=SC1091
  source "${SDKMAN_DIR}/bin/sdkman-init.sh"
  set -u
fi

# nvm — common way to install Node on EC2. Sourcing it makes `node`/`npm` visible.
NVM_DIR="${NVM_DIR:-${HOME}/.nvm}"
if [ -s "${NVM_DIR}/nvm.sh" ]; then
  set +u
  # shellcheck disable=SC1091
  source "${NVM_DIR}/nvm.sh" --no-use
  # If a default alias exists, activate it; otherwise leave whatever PATH-resolved
  # node is in place.
  if [ -s "${NVM_DIR}/alias/default" ]; then
    nvm use default >/dev/null 2>&1 || true
  fi
  set -u
fi
# -----------------------------------------------------------------------------

SECRET_ID="${SECMAN_AWS_SECRET_ID:-secman/dev}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-central-1}}"

command -v aws >/dev/null 2>&1 || { echo "ERROR: aws CLI is required. See docs/AWS.md."; exit 1; }
command -v jq  >/dev/null 2>&1 || { echo "ERROR: jq is required. See docs/AWS.md.";       exit 1; }
command -v npm >/dev/null 2>&1 || {
  echo "ERROR: npm not found. Install Node.js (e.g. via nvm) or set NVM_DIR."
  exit 1
}

cd "${PROJECT_ROOT}/src/frontend"

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
