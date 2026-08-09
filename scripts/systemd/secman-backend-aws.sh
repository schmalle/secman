#!/bin/bash
# =============================================================================
# EXAMPLE launcher — ExecStart target for scripts/systemd/secman-backend.service.example
#
# Fetches the secman JSON secret from AWS Secrets Manager, maps it onto the
# backend's environment variables, and execs the compiled shadow JAR.
#
# Why a launcher instead of systemd's EnvironmentFile=:
#   EnvironmentFile needs the DB password, JWT secret and encryption password to
#   exist as plaintext on disk. Here they only ever live in this process's
#   environment, which is inherited by the JVM via exec and never written out.
#
# Secret schema and the key -> env-var mapping: docs/AWS.md § Secret keys
# reference. The mapping itself is scripts/lib/aws-secrets.sh, shared with every
# other *aws.sh launcher, so this script stays in step with them for free.
#
# Requires on the host: aws CLI v2, jq, a JRE, and an instance role (or IRSA)
# granting secretsmanager:GetSecretValue on the secret.
#
# Run it by hand to smoke-test before enabling the unit:
#   sudo -u secman SECMAN_AWS_SECRET_ID=secman/prod ./scripts/systemd/secman-backend-aws.sh
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=../lib/aws-secrets.sh
source "${SCRIPT_DIR}/../lib/aws-secrets.sh"

# --- Base env from the shared mapping ----------------------------------------
# DB_CONNECT, DB_USERNAME, DB_PASSWORD, SECMAN_BACKEND_URL, SECMAN_MCP_KEY,
# FALCON_*, OPENROUTER, admin identities, ... See docs/AWS.md.
secman_aws_export_envfile

# --- Keys the shared mapping does not cover ----------------------------------
# secman_aws_export_envfile targets the dev/CLI launchers. A production backend
# additionally needs the crypto material, SMTP, and the public URLs. Add these
# keys to the same JSON secret. secman_aws_export skips absent/empty keys, so
# anything you leave out simply falls back to application.yml.

# Auth & crypto (docs/ENVIRONMENT.md § Auth & crypto).
secman_aws_export JWT_SECRET                 JWT_SECRET
secman_aws_export SECMAN_ENCRYPTION_PASSWORD SECMAN_ENCRYPTION_PASSWORD
secman_aws_export SECMAN_ENCRYPTION_SALT     SECMAN_ENCRYPTION_SALT

# URLs (CORS, e-mail links, OAuth callbacks).
secman_aws_export FRONTEND_URL               FRONTEND_URL

# SMTP.
secman_aws_export SMTP_HOST                  SMTP_HOST
secman_aws_export SMTP_PORT                  SMTP_PORT
secman_aws_export SMTP_USERNAME              SMTP_USERNAME
secman_aws_export SMTP_PASSWORD              SMTP_PASSWORD
secman_aws_export SMTP_FROM_ADDRESS          SMTP_FROM_ADDRESS
secman_aws_export SMTP_FROM_NAME             SMTP_FROM_NAME

# Drop the secret JSON from this process now that everything is exported, so it
# is not sitting in the environment the JVM inherits.
SECMAN_AWS_SECRET_JSON=""
unset SECMAN_AWS_SECRET_JSON

# --- Production posture ------------------------------------------------------
export MICRONAUT_ENVIRONMENTS="${MICRONAUT_ENVIRONMENTS:-prod}"
# HttpOnly+Secure auth cookie; only a local plain-HTTP dev box sets this false.
export SECMAN_AUTH_COOKIE_SECURE="${SECMAN_AUTH_COOKIE_SECURE:-true}"
# SECMAN_DEBUG logs request headers and JWT claims — never on in production.
export SECMAN_DEBUG=false

# --- Fail fast on missing required config ------------------------------------
# JwtSigningValidator / DatabaseCredentialValidator / DatasourceUrlValidator
# already refuse to boot on weak values; this just turns "absent key in the
# secret" into a readable journal line instead of a stack trace.
missing=()
for var in DB_CONNECT DB_USERNAME DB_PASSWORD JWT_SECRET \
           SECMAN_ENCRYPTION_PASSWORD SECMAN_ENCRYPTION_SALT; do
  if [ -z "${!var:-}" ]; then missing+=("$var"); fi
done
if [ ${#missing[@]} -gt 0 ]; then
  # Names only — never echo a secret value into the journal.
  echo "ERROR: secret '${SECMAN_AWS_SECRET_ID}' is missing required keys for: ${missing[*]}" >&2
  echo "       See docs/AWS.md § Secret keys reference and docs/ENVIRONMENT.md." >&2
  exit 78  # EX_CONFIG
fi

# JWT_SECRET must be stored, not generated per start the way startbackenddev.sh
# does — a fresh secret on every restart silently invalidates every session.
# Generate it once:  openssl rand -base64 48

# --- Locate the JAR ----------------------------------------------------------
JAR="${SECMAN_JAR:-${PROJECT_ROOT}/src/backendng/build/libs/backendng-0.1-all.jar}"
if [ ! -f "$JAR" ]; then
  echo "ERROR: backend JAR not found at ${JAR}" >&2
  echo "       Build it with: ./gradlew :backendng:clean :backendng:shadowJar -x test" >&2
  exit 72  # EX_OSFILE
fi

JAVA_BIN="${SECMAN_JAVA_BIN:-/usr/bin/java}"
if [ ! -x "$JAVA_BIN" ]; then
  echo "ERROR: java not executable at ${JAVA_BIN} (set SECMAN_JAVA_BIN)" >&2
  exit 72
fi

# Word-split SECMAN_JAVA_OPTS into an array so each flag is its own argv entry.
read -r -a java_opts <<< "${SECMAN_JAVA_OPTS:--Xms512m -Xmx2g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError}"

cd "${PROJECT_ROOT}"

echo "[secman-backend] starting ${JAR} (secret ${SECMAN_AWS_SECRET_ID}, region ${SECMAN_AWS_REGION})" >&2

# exec: the JVM replaces this shell, so systemd's SIGTERM reaches it directly
# and Micronaut shuts down gracefully.
# The ${x[@]+...} form keeps `set -u` happy on older bash if the array is empty.
exec "$JAVA_BIN" ${java_opts[@]+"${java_opts[@]}"} -jar "$JAR"
