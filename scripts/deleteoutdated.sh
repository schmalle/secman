#!/bin/bash

export DB_CONNECT="jdbc:mariadb://127.0.0.1:3306/secman?useSsl=true"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "Error: CLI JAR not found at $JAR_PATH"
    echo ""
    echo "Build it first with:"
    echo "  ./gradlew :cli:shadowJar"
    exit 1
fi

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

# IMPORTANT: do NOT pass --username/--password here. The parent shell expands
# $SECMAN_ADMIN_NAME / $SECMAN_ADMIN_PASS into argv as the literal "pass://..."
# *before* `pass-cli run --` resolves them. pass-cli only rewrites env vars at
# exec time, not argv that the shell already substituted, so the backend
# receives the unresolved reference and returns 401. The CLI reads
# SECMAN_ADMIN_NAME / SECMAN_ADMIN_PASS / SECMAN_INSECURE from the environment
# directly — pass-cli does resolve those correctly.
pass-cli run -- java -Xmx4g -Xms2g -jar ./src/cli/build/libs/cli-0.1.0-all.jar delete-asset-not-seen 30 --dry-run --verbose
