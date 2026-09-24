#!/usr/bin/env bash
# Run a database-mutating E2E command against a disposable local SecMan stack.
set -euo pipefail

DB_ONLY=false
if [[ "${1:-}" == "--database-only" ]]; then DB_ONLY=true; shift; fi
if [[ "${1:-}" == "--" ]]; then shift; fi
if [[ $# -eq 0 ]]; then
    echo "Usage: ./scripts/test/run-isolated-e2e.sh -- <test command> [args...]" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ -z "${SECMAN_E2E_SECRETS_READY:-}" ]]; then
    export SECMAN_E2E_SECRETS_READY=1
    if [[ "$DB_ONLY" == true ]]; then
        exec pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- "$0" --database-only -- "$@"
    fi
    exec pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- "$0" -- "$@"
fi
BACKEND_PORT=18080
FRONTEND_PORT=14321
SMTP_PORT=1925
BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}"
FRONTEND_URL="http://127.0.0.1:${FRONTEND_PORT}"
MARKER_TABLE=secman_e2e_owner
OWNER_LABEL=secman-e2e-runner-v1

for command_name in mariadb mariadb-dump openssl lsof pass-cli rsync jq; do
    command -v "$command_name" >/dev/null || {
        echo "Missing required command: $command_name" >&2; exit 2;
    }
done

# Fixed test ports must be free; never displace another stack.
if [[ "$DB_ONLY" == false ]]; then
    for port in "$BACKEND_PORT" "$FRONTEND_PORT" "$SMTP_PORT"; do
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
            echo "Test port $port is already occupied; no database was changed" >&2
            exit 2
        fi
    done
fi

db_admin() { mariadb --batch --skip-column-names "$@"; }
MANIFEST_DIR="$REPO_ROOT/.e2e-logs/isolated-manifests"
mkdir -p "$MANIFEST_DIR"

# Only schemas carrying both our ownership table and matching manifest are eligible.
# A live runner PID prevents another invocation from deleting an active test.
clean_abandoned() {
    local schema marker owner_label owner_pid owner_token manifest
    while IFS= read -r schema; do
        [[ "$schema" =~ ^secman_e2e_[a-f0-9]{16}$ ]] || continue
        manifest="$MANIFEST_DIR/$schema.json"
        marker="$(db_admin -e "SELECT CONCAT(owner_label, ':', owner_pid, ':', owner_token) FROM \`$schema\`.\`$MARKER_TABLE\` LIMIT 1" 2>/dev/null || true)"
        IFS=: read -r owner_label owner_pid owner_token <<< "$marker"
        if [[ "$owner_label" != "$OWNER_LABEL" || ! "$owner_pid" =~ ^[0-9]+$ ||
              ! "$owner_token" =~ ^[a-f0-9]{32}$ || ! -f "$manifest" ]] ||
            ! jq -e --arg schema "$schema" --arg label "$owner_label" \
                --arg token "$owner_token" --argjson pid "$owner_pid" \
                '.schema==$schema and .ownerLabel==$label and .ownerToken==$token and .ownerPid==$pid' \
                "$manifest" >/dev/null 2>&1; then
            echo "Preserving unverified database $schema" >&2
            continue
        fi
        if kill -0 "$owner_pid" 2>/dev/null; then
            echo "Preserving active test database $schema (runner $owner_pid)" >&2
            continue
        fi
        db_admin -e "DROP DATABASE \`$schema\`" 
        db_admin -e "DROP USER IF EXISTS '$schema'@'localhost', '$schema'@'127.0.0.1'"
        rm -f "$manifest"
        echo "Removed abandoned test database $schema" >&2
    done < <(db_admin -e "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME LIKE 'secman_e2e_%'")
}

clean_abandoned

RUN_ID="$(openssl rand -hex 8)"
DB_NAME="secman_e2e_${RUN_ID}"
DB_PASSWORD="$(openssl rand -hex 24)"
OWNER_TOKEN="$(openssl rand -hex 16)"
LOG_DIR="$REPO_ROOT/.e2e-logs/isolated-$RUN_ID"
MANIFEST_FILE="$MANIFEST_DIR/$DB_NAME.json"
mkdir -p "$LOG_DIR"

CREATED_DB=false
FRONTEND_COPY=""
smtp_pid=""
finish() {
    local result="$1" marker
    trap - EXIT
    if declare -f stop_own_listener >/dev/null; then
        if [[ -n "$FRONTEND_COPY" ]]; then
            stop_own_listener "$FRONTEND_PORT" "$FRONTEND_COPY" || result=1
        fi
        stop_own_listener "$BACKEND_PORT" "$REPO_ROOT/src/backendng" || result=1
    fi
    if [[ -n "$smtp_pid" ]] && kill -0 "$smtp_pid" 2>/dev/null; then
        kill "$smtp_pid" || result=1
    fi
    if [[ -n "$FRONTEND_COPY" && -d "$FRONTEND_COPY" &&
          "$(basename "$FRONTEND_COPY")" == secman-frontend-e2e.* ]]; then
        rm -rf "$FRONTEND_COPY"
    fi
    if [[ "$CREATED_DB" == true ]]; then
        marker="$(db_admin -e "SELECT owner_token FROM \`$DB_NAME\`.\`$MARKER_TABLE\` LIMIT 1" 2>/dev/null || true)"
        if [[ "$marker" == "$OWNER_TOKEN" ]] &&
            jq -e --arg schema "$DB_NAME" --arg token "$OWNER_TOKEN" \
                '.schema==$schema and .ownerToken==$token' "$MANIFEST_FILE" >/dev/null 2>&1; then
            if db_admin -e "DROP DATABASE \`$DB_NAME\`" &&
                db_admin -e "DROP USER IF EXISTS '$DB_NAME'@'localhost', '$DB_NAME'@'127.0.0.1'"; then
                rm -f "$MANIFEST_FILE"
            else
                result=1
            fi
        else
            echo "Ownership marker changed; preserving $DB_NAME for review" >&2
            result=1
        fi
    else
        rm -f "$MANIFEST_FILE"
    fi
    exit "$result"
}
trap 'finish $?' EXIT

umask 077
jq -nc --arg schema "$DB_NAME" --arg label "$OWNER_LABEL" \
    --arg token "$OWNER_TOKEN" --argjson pid "$$" \
    '{schema:$schema,ownerLabel:$label,ownerToken:$token,ownerPid:$pid}' > "$MANIFEST_FILE"
db_admin -e "CREATE DATABASE \`$DB_NAME\`"
CREATED_DB=true
db_admin -e "CREATE TABLE \`$DB_NAME\`.\`$MARKER_TABLE\` (owner_label VARCHAR(64) NOT NULL, owner_token CHAR(32) NOT NULL, owner_pid BIGINT NOT NULL); INSERT INTO \`$DB_NAME\`.\`$MARKER_TABLE\` VALUES ('$OWNER_LABEL', '$OWNER_TOKEN', $$)"
db_admin -e "CREATE USER '$DB_NAME'@'localhost' IDENTIFIED BY '$DB_PASSWORD'; CREATE USER '$DB_NAME'@'127.0.0.1' IDENTIFIED BY '$DB_PASSWORD'; GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_NAME'@'localhost', '$DB_NAME'@'127.0.0.1'"

# Copy structure and migration metadata only. No application rows are copied.
# This also avoids Flyway's empty-schema inspection on MariaDB installations
# whose system routine tables have not yet been upgraded.
mariadb-dump --no-data --skip-triggers --skip-routines secman | db_admin "$DB_NAME"
mariadb-dump --no-create-info --skip-triggers secman flyway_schema_history | db_admin "$DB_NAME"

export DB_CONNECT="jdbc:mariadb://127.0.0.1:3306/$DB_NAME"
export DB_USERNAME="$DB_NAME" DB_PASSWORD
export SECMAN_TEST_DB_CONNECT="$DB_CONNECT" SECMAN_TEST_DB_USERNAME="$DB_USERNAME" SECMAN_TEST_DB_PASSWORD="$DB_PASSWORD"
export TEST_DB_URL="$DB_CONNECT" TEST_DB_USERNAME="$DB_NAME" TEST_DB_PASSWORD="$DB_PASSWORD"
export DB_HOST=127.0.0.1 DB_USER="$DB_NAME" DB_PASS="$DB_PASSWORD" DB_NAME
export SECMAN_TEST_ISOLATED_DB="$DB_NAME" SECMAN_TEST_OWNER_TOKEN="$OWNER_TOKEN"
export SECMAN_BACKEND_PORT="$BACKEND_PORT" SECMAN_FRONTEND_PORT="$FRONTEND_PORT"
export SECMAN_BACKEND_HOST=127.0.0.1
export SECMAN_E2E_BACKEND_URL="$BACKEND_URL" SECMAN_E2E_FRONTEND_URL="$FRONTEND_URL"
export SECMAN_E2E_FRONTEND_HOST=127.0.0.1
export BASE_URL="$BACKEND_URL" FRONTEND_URL="$FRONTEND_URL"
export SECMAN_BASE_URL="$BACKEND_URL" SECMAN_HOST="$BACKEND_URL"
export SECMAN_BACKEND_URL="$BACKEND_URL"
export SECMAN_INSECURE=false
export BACKEND_LOG="$LOG_DIR/backend.log"
if [[ "$DB_ONLY" == false ]]; then
    db_admin "$DB_NAME" -e "INSERT INTO email_configs
        (created_at, updated_at, from_email, from_name, imap_enabled, is_active,
         name, smtp_host, smtp_port, smtp_ssl, smtp_tls, provider)
        VALUES (NOW(), NOW(), 'noreply@e2e.test', 'SecMan E2E', b'0', b'1',
                'isolated loopback sink', '127.0.0.1', $SMTP_PORT, b'0', b'0', 'SMTP')"
    : "${SECMAN_ADMIN_NAME:?isolated stack needs an admin username from pass-cli}"
    : "${SECMAN_ADMIN_PASS:?isolated stack needs an admin password from pass-cli}"
    : "${SECMAN_ADMIN_EMAIL:?isolated stack needs an admin email from pass-cli}"
    export SECMAN_E2E_ADMIN_NAME="$SECMAN_ADMIN_NAME"
    export SECMAN_E2E_ADMIN_EMAIL="$SECMAN_ADMIN_EMAIL"
    export SECMAN_E2E_ADMIN_PASS="$SECMAN_ADMIN_PASS"
    FRONTEND_COPY="$(mktemp -d "${TMPDIR:-/tmp}/secman-frontend-e2e.XXXXXXXX")"
    FRONTEND_COPY="$(cd "$FRONTEND_COPY" && pwd -P)"
    rsync -a --exclude node_modules --exclude .astro --exclude dist \
        "$REPO_ROOT/src/frontend/" "$FRONTEND_COPY/"
    ln -s "$REPO_ROOT/src/frontend/node_modules" "$FRONTEND_COPY/node_modules"
    export SECMAN_E2E_FRONTEND_DIR="$FRONTEND_COPY"
    export SECMAN_E2E_ORIGINAL_FRONTEND_DIR="$REPO_ROOT/src/frontend"
    export SMTP_HOST=127.0.0.1 SMTP_PORT="$SMTP_PORT"
    export SMTP_USERNAME='' SMTP_PASSWORD='' SMTP_ENABLE_TLS=false
fi

stop_own_listener() {
    local port="$1" expected_cwd="$2" pid cwd
    pid="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    [[ -n "$pid" ]] || return 0
    cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
    if [[ -d "$cwd" ]]; then cwd="$(cd "$cwd" && pwd -P)"; fi
    if [[ "$cwd" != "$expected_cwd" ]]; then
        echo "Refusing to stop unexpected process $pid on port $port ($cwd)" >&2
        return 1
    fi
    kill "$pid"
}

wait_for_listener() {
    local port="$1" timeout="$2" log="$3" process_id="$4" elapsed
    for ((elapsed=0; elapsed<timeout; elapsed++)); do
        if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then return 0; fi
        if [[ -n "$process_id" ]] && ! kill -0 "$process_id" 2>/dev/null; then
            echo "Service exited before binding port $port; inspect $log" >&2
            return 1
        fi
        sleep 1
    done
    echo "Service did not bind port $port; inspect $log" >&2
    return 1
}

create_test_mcp_key() {
    local cookie response status domains attempt
    cookie="$(mktemp)"
    response="$(mktemp)"
    for ((attempt=0; attempt<30; attempt++)); do
        status="$(curl -sS -o "$response" -w '%{http_code}' -c "$cookie" \
            -H 'Content-Type: application/json' -X POST "$BACKEND_URL/api/auth/login" \
            --data "$(jq -nc --arg u "$SECMAN_ADMIN_NAME" --arg p "$SECMAN_ADMIN_PASS" \
                '{username:$u,password:$p}')")"
        [[ "$status" == 200 ]] && break
        sleep 1
    done
    if [[ "$status" != 200 ]]; then
        rm -f "$cookie" "$response"
        echo "Isolated admin login failed (HTTP $status)" >&2
        return 1
    fi
    domains="@e2e.test,@e2e.local,@example.test,@schmall.io,@${SECMAN_ADMIN_EMAIL##*@}"
    if [[ -n "${E2E_RISK_ASSESSMENT_USER_EMAIL:-}" ]]; then
        domains+=",@${E2E_RISK_ASSESSMENT_USER_EMAIL##*@}"
    fi
    status="$(curl -sS -o "$response" -w '%{http_code}' -b "$cookie" \
        -H 'Content-Type: application/json' -X POST "$BACKEND_URL/api/mcp/admin/api-keys" \
        --data "$(jq -nc --arg domains "$domains" \
            '{name:"isolated-e2e",delegationEnabled:true,allowedDelegationDomains:$domains,
              permissions:["REQUIREMENTS_READ","REQUIREMENTS_WRITE","REQUIREMENTS_DELETE",
                "ASSESSMENTS_READ","ASSESSMENTS_EXECUTE","ASSESSMENTS_WRITE","FILES_READ",
                "TAGS_READ","SYSTEM_INFO","USER_ACTIVITY","TRANSLATION_USE","AUDIT_READ",
                "ASSETS_READ","ASSETS_WRITE","SCANS_READ","VULNERABILITIES_READ",
                "INTEGRATIONS_READ","INTEGRATIONS_WRITE","WORKGROUPS_WRITE","NOTIFICATIONS_SEND"]}')")"
    if [[ "$status" != 201 ]]; then
        rm -f "$cookie" "$response"
        echo "Isolated MCP key creation failed (HTTP $status)" >&2
        return 1
    fi
    SECMAN_MCP_KEY="$(jq -er '.apiKey' "$response")"
    export SECMAN_MCP_KEY
    rm -f "$cookie" "$response"
}

cd "$REPO_ROOT"
if [[ "$DB_ONLY" == false ]]; then
    nohup python3 "$REPO_ROOT/scripts/test/smtp-sink.py" > "$LOG_DIR/smtp.log" 2>&1 &
    smtp_pid=$!
    wait_for_listener "$SMTP_PORT" 10 "$LOG_DIR/smtp.log" "$smtp_pid"
    nohup ./scripts/startbackenddev.sh > "$LOG_DIR/backend.log" 2>&1 &
    backend_pid=$!
    wait_for_listener "$BACKEND_PORT" 120 "$LOG_DIR/backend.log" "$backend_pid"
    create_test_mcp_key
    nohup ./scripts/startfrontenddev.sh > "$LOG_DIR/frontend.log" 2>&1 &
    wait_for_listener "$FRONTEND_PORT" 60 "$LOG_DIR/frontend.log" ""
fi

"$@"
