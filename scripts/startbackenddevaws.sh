#!/bin/bash
# Backend dev start using AWS Secrets Manager (alternative to startbackenddev.sh + pass-cli).
#
# Resolves the secrets that ./scripts/startbackenddev.sh normally fetches via pass-cli,
# but reads them from a single JSON-shaped secret in AWS Secrets Manager.
#
# Cron-safe: the script does not rely on the caller's PATH or shell rc files. It sources
# SDKMAN if installed (under SDKMAN_DIR or ~/.sdkman) so java/gradle become visible even
# under the minimal cron environment, and changes the working directory to the project
# root so relative paths and Gradle project discovery work regardless of where cron
# launches it from.
#
# Configuration (env vars):
#   SECMAN_AWS_SECRET_ID  Secrets Manager secret name or ARN  (default: secman/dev)
#   AWS_REGION            AWS region for the secret           (default: eu-central-1)
#   AWS_PROFILE           Optional named profile from ~/.aws/credentials
#   SDKMAN_DIR            Override SDKMAN install location    (default: $HOME/.sdkman)
#
# AWS authentication: any standard credential provider works (instance profile on EC2,
# AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY env vars, AWS_PROFILE, SSO, etc.).
#
# Required tools: aws CLI v2, jq, openssl, gradle (or rely on ./gradlew).
# See docs/AWS.md for installation instructions on Amazon Linux.

set -euo pipefail

# --- Cron-safe environment bootstrap -----------------------------------------
# Cron runs with a minimal PATH (typically /usr/bin:/bin) and does NOT source
# ~/.bashrc, ~/.profile, etc. Anything installed under /usr/local/bin or via
# SDKMAN/nvm must be brought in explicitly.

# Ensure HOME is set (some cron daemons pass it, some don't).
: "${HOME:=$(getent passwd "$(id -u)" 2>/dev/null | cut -d: -f6)}"
export HOME

# Pre-pend common locations so aws/jq/openssl/node are findable regardless of
# whether cron set PATH. Keep the caller's PATH at the end so explicit overrides
# still take effect.
export PATH="${HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"

# Resolve and switch to the project root (one level above scripts/) so Gradle
# discovers the build and ./gradlew is reachable.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

# SDKMAN ships java/gradle/etc. under $SDKMAN_DIR/candidates/<tool>/current/bin
# but only after sdkman-init.sh is sourced. Source it if we can find it.
SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
if [ -s "${SDKMAN_DIR}/bin/sdkman-init.sh" ]; then
  # sdkman-init.sh references unset vars; relax `set -u` while sourcing.
  set +u
  # shellcheck disable=SC1091
  source "${SDKMAN_DIR}/bin/sdkman-init.sh"
  set -u
fi
# -----------------------------------------------------------------------------

SECRET_ID="${SECMAN_AWS_SECRET_ID:-secman/dev}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-central-1}}"

command -v aws >/dev/null 2>&1 || { echo "ERROR: aws CLI is required. See docs/AWS.md."; exit 1; }
command -v jq  >/dev/null 2>&1 || { echo "ERROR: jq is required. See docs/AWS.md.";       exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "ERROR: openssl is required.";          exit 1; }
command -v java >/dev/null 2>&1 || {
  echo "ERROR: java not found. Install via SDKMAN ('sdk install java 25-amzn') or set SDKMAN_DIR."
  exit 1
}

echo "Fetching secret '${SECRET_ID}' from AWS Secrets Manager (region ${REGION})..."
SECRET_JSON=$(aws secretsmanager get-secret-value \
  --secret-id "${SECRET_ID}" \
  --region "${REGION}" \
  --query SecretString \
  --output text)

if [ -z "${SECRET_JSON}" ] || [ "${SECRET_JSON}" = "None" ]; then
  echo "ERROR: empty SecretString for ${SECRET_ID}"; exit 1
fi

# Extract a key from the JSON secret. Returns empty string if the key is absent.
extract() { printf '%s' "${SECRET_JSON}" | jq -r --arg k "$1" '.[$k] // empty'; }

# Conditional export: only set the env var when the secret actually contains the key.
# Skipping AWS creds (which the application uses to call CrowdStrike, S3 etc.) lets the
# script fall back to the EC2 instance role when no static keys are stored in the secret.
maybe_export() {
  local var="$1" key="$2" val
  val="$(extract "$key")"
  if [ -n "$val" ]; then export "$var=$val"; fi
}

export MICRONAUT_ENVIRONMENTS=dev
export SECMAN_DEBUG=true

maybe_export DB_CONNECT                 DB_CONNECT
maybe_export SECMAN_BACKEND_URL         SECMAN_BACKEND_BASE_URL
maybe_export FALCON_CLIENT_ID           FALCON_CLIENT_ID
maybe_export FALCON_CLIENT_SECRET       FALCON_CLIENT_SECRET
maybe_export FALCON_CLOUD_REGION        FALCON_CLOUD_REGION
maybe_export SECMAN_OPENROUTER_API_KEY  OPENROUTER_API_KEY
maybe_export SECMAN_ADMIN_NAME          SECMAN_ADMIN_NAME
maybe_export SECMAN_ADMIN_PASS          SECMAN_ADMIN_PASS
maybe_export SECMAN_MCP_KEY             SECMAN_MCP_KEY
maybe_export SECMAN_ADMIN_EMAIL         SECMAN_ADMIN_EMAIL
maybe_export SECMAN_INSECURE            SECMAN_SSL_ACCEPT_ALL

# Application-side AWS credentials (used by backend for CrowdStrike, S3 etc.).
# Only override the ambient AWS identity when the secret carries these keys.
maybe_export AWS_ACCESS_KEY_ID          SECMAN_AWS_ACCESS_KEY_ID
maybe_export AWS_SECRET_ACCESS_KEY      SECMAN_AWS_SECRET_ACCESS_KEY
maybe_export AWS_SESSION_TOKEN          SECMAN_AWS_ACCESS_TOKEN

export JWT_SECRET=$(openssl rand -base64 48)

if command -v gradle >/dev/null 2>&1; then
  exec gradle :backendng:clean backendng:run
else
  exec ./gradlew :backendng:clean :backendng:run
fi
