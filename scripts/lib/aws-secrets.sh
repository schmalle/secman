#!/bin/bash
# scripts/lib/aws-secrets.sh — shared bootstrap for the *aws.sh launchers.
#
# This file is sourced (not executed) by every scripts/*aws.sh launcher. It:
#
#   1. Builds a cron-safe PATH (cron does not source ~/.bashrc / ~/.profile).
#   2. Sources SDKMAN (and nvm, if installed) so SDKMAN-managed java/gradle and
#      nvm-managed node/npm are visible — even under cron / systemd.
#   3. Fetches a single JSON secret from AWS Secrets Manager and exposes a
#      `secman_aws_export_envfile` function that maps secret keys to env vars,
#      mirroring what `pass-cli run --env-file secmanpp.env` does.
#   4. Locates the project root and the CLI shadow JAR for callers that need
#      them.
#
# Public API (functions that callers should use):
#
#   secman_aws_load_secret           Fetches the JSON secret. Stores it in
#                                    SECMAN_AWS_SECRET_JSON. Idempotent.
#   secman_aws_export_envfile        Exports the full pass-cli-equivalent set
#                                    of env vars. Safe to call multiple times.
#   secman_aws_extract <key>         Echoes one value from the secret JSON.
#
# Public variables set by this file:
#   PROJECT_ROOT                     Absolute path to the secman repo root.
#   CLI_JAR                          Path to src/cli/build/libs/cli-0.1.0-all.jar
#
# Configuration env vars consumed:
#   SECMAN_AWS_SECRET_ID             Secrets Manager secret name or ARN.
#                                    Default: secman/dev
#   AWS_REGION / AWS_DEFAULT_REGION  Region for the secret. Default: eu-central-1
#   SDKMAN_DIR                       SDKMAN install root. Default: $HOME/.sdkman
#   NVM_DIR                          nvm install root. Default: $HOME/.nvm
#
# See docs/AWS.md for the canonical secret schema and IAM permissions.

# Guard: only run the bootstrap once per shell, even if multiple launchers
# source this file in the same process.
if [ "${SECMAN_AWS_LIB_LOADED:-}" = "1" ]; then
  return 0 2>/dev/null || exit 0
fi
SECMAN_AWS_LIB_LOADED=1

# --- Cron-safe environment ---------------------------------------------------
: "${HOME:=$(getent passwd "$(id -u)" 2>/dev/null | cut -d: -f6)}"
export HOME

export PATH="${HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH:-}"

# Source SDKMAN. SDKMAN's init script touches unset variables, so we relax `-u`
# while sourcing if the caller had it on.
SDKMAN_DIR="${SDKMAN_DIR:-${HOME}/.sdkman}"
if [ -s "${SDKMAN_DIR}/bin/sdkman-init.sh" ]; then
  case "$-" in *u*) _secman_aws_had_u=1; set +u ;; *) _secman_aws_had_u=0 ;; esac
  # shellcheck disable=SC1091
  source "${SDKMAN_DIR}/bin/sdkman-init.sh"
  [ "${_secman_aws_had_u:-0}" = "1" ] && set -u
  unset _secman_aws_had_u
fi

# Source nvm if present (lets callers find node/npm under cron).
NVM_DIR="${NVM_DIR:-${HOME}/.nvm}"
if [ -s "${NVM_DIR}/nvm.sh" ]; then
  case "$-" in *u*) _secman_aws_had_u=1; set +u ;; *) _secman_aws_had_u=0 ;; esac
  # shellcheck disable=SC1091
  source "${NVM_DIR}/nvm.sh" --no-use
  if [ -s "${NVM_DIR}/alias/default" ]; then
    nvm use default >/dev/null 2>&1 || true
  fi
  [ "${_secman_aws_had_u:-0}" = "1" ] && set -u
  unset _secman_aws_had_u
fi

# --- Project layout ----------------------------------------------------------
# BASH_SOURCE[0] is this file; the project root is two levels up
# (scripts/lib/aws-secrets.sh -> ../..).
_SECMAN_AWS_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${_SECMAN_AWS_LIB_DIR}/../.." && pwd)"
CLI_JAR="${PROJECT_ROOT}/src/cli/build/libs/cli-0.1.0-all.jar"
export PROJECT_ROOT CLI_JAR

# --- AWS Secrets Manager fetch ----------------------------------------------
SECMAN_AWS_SECRET_ID="${SECMAN_AWS_SECRET_ID:-secman/dev}"
SECMAN_AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-central-1}}"
SECMAN_AWS_SECRET_JSON=""

secman_aws_require_tools() {
  command -v aws >/dev/null 2>&1 || { echo "ERROR: aws CLI is required. See docs/AWS.md."; return 1; }
  command -v jq  >/dev/null 2>&1 || { echo "ERROR: jq is required. See docs/AWS.md.";       return 1; }
}

secman_aws_load_secret() {
  if [ -n "${SECMAN_AWS_SECRET_JSON:-}" ]; then return 0; fi
  secman_aws_require_tools || return 1
  echo "[aws-secrets] Fetching secret '${SECMAN_AWS_SECRET_ID}' from AWS Secrets Manager (region ${SECMAN_AWS_REGION})..." >&2
  SECMAN_AWS_SECRET_JSON=$(aws secretsmanager get-secret-value \
    --secret-id "${SECMAN_AWS_SECRET_ID}" \
    --region "${SECMAN_AWS_REGION}" \
    --query SecretString \
    --output text) || {
      echo "ERROR: failed to read secret '${SECMAN_AWS_SECRET_ID}' (region ${SECMAN_AWS_REGION})" >&2
      return 1
    }
  if [ -z "${SECMAN_AWS_SECRET_JSON}" ] || [ "${SECMAN_AWS_SECRET_JSON}" = "None" ]; then
    echo "ERROR: empty SecretString for ${SECMAN_AWS_SECRET_ID}" >&2
    return 1
  fi
}

# Echo one value from the secret JSON, or empty if absent.
secman_aws_extract() {
  printf '%s' "${SECMAN_AWS_SECRET_JSON}" | jq -r --arg k "$1" '.[$k] // empty'
}

# Helper: export an env var from a secret key. Skips if the key is missing or
# empty, so optional fields don't clobber whatever's already set.
secman_aws_export() {
  local var="$1" key="$2" val
  val="$(secman_aws_extract "$key")"
  if [ -n "$val" ]; then export "$var=$val"; fi
}

# Export the full set of env vars that secmanpp.env normally provides via
# pass-cli. Mirrors the `key -> env var` mapping the original launchers used.
secman_aws_export_envfile() {
  secman_aws_load_secret || return 1

  # Backend connection / target URL
  secman_aws_export DB_CONNECT                    DB_CONNECT
  secman_aws_export SECMAN_BACKEND_URL            SECMAN_BACKEND_BASE_URL
  secman_aws_export SECMAN_DOMAIN                 SECMAN_BACKEND_BASE_URL
  secman_aws_export SECMAN_HOST                   SECMAN_HOST
  secman_aws_export SECMAN_INSECURE               SECMAN_SSL_ACCEPT_ALL

  # Database (used by some CLI paths that build the JDBC URL themselves)
  secman_aws_export SECMAN_DB_HOST                SECMAN_DB_HOST
  secman_aws_export SECMAN_DB_PORT                SECMAN_DB_PORT
  secman_aws_export SECMAN_DB_NAME                SECMAN_DB_NAME
  secman_aws_export DB_USERNAME                   SECMAN_DB_USER
  secman_aws_export DB_PASSWORD                   SECMAN_DB_PASSWORD

  # Admin + test user identities
  secman_aws_export SECMAN_ADMIN_NAME             SECMAN_ADMIN_NAME
  secman_aws_export SECMAN_ADMIN_PASS             SECMAN_ADMIN_PASS
  secman_aws_export SECMAN_ADMIN_EMAIL            SECMAN_ADMIN_EMAIL
  secman_aws_export SECMAN_USER_NAME              SECMAN_USER_NAME
  secman_aws_export SECMAN_USER_PASS              SECMAN_USER_PASS

  # Auth keys
  secman_aws_export SECMAN_MCP_KEY                SECMAN_MCP_KEY

  # Integrations
  secman_aws_export FALCON_CLIENT_ID              FALCON_CLIENT_ID
  secman_aws_export FALCON_CLIENT_SECRET          FALCON_CLIENT_SECRET
  secman_aws_export FALCON_CLOUD_REGION           FALCON_CLOUD_REGION
  secman_aws_export SECMAN_OPENROUTER_API_KEY     OPENROUTER_API_KEY

  # Application-side AWS credentials. Only override the ambient identity if the
  # secret carries them — leave empty in the secret to fall back to an EC2
  # instance role.
  secman_aws_export AWS_ACCESS_KEY_ID             SECMAN_AWS_ACCESS_KEY_ID
  secman_aws_export AWS_SECRET_ACCESS_KEY         SECMAN_AWS_SECRET_ACCESS_KEY
  secman_aws_export AWS_SESSION_TOKEN             SECMAN_AWS_ACCESS_TOKEN
}

# Fail if the CLI shadow JAR has not been built yet.
secman_aws_require_cli_jar() {
  if [ ! -f "${CLI_JAR}" ]; then
    cat >&2 <<EOF
ERROR: CLI JAR not found at ${CLI_JAR}

Build it first with:
  ./gradlew :cli:shadowJar
EOF
    return 1
  fi
}
