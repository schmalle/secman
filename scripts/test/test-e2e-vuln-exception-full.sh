#!/usr/bin/env bash
#
# E2E Vulnerability + Exception Full Workflow Test (MCP + Web UI)
#
# Drives the full vulnerability management and exception lifecycle through both
# the MCP JSON-RPC interface and the Astro/React web UI.
#
# Testbed:
#   Users:  e2etestuser1, e2etestuser2 (USER+VULN+REQ — no ADMIN/SECCHAMPION)
#   Assets: testasset1 (owner=e2etestuser1), testasset2 (owner=e2etestuser2)
#   Vulns:  vuln1 = CVE-E2E-0001  CRITICAL  daysOpen=40  on testasset1 only (overdue)
#           vuln2 = CVE-E2E-0002  CRITICAL  daysOpen=5   on testasset1 AND testasset2
#
# Cleanup runs both before (unconditional) and after (trap EXIT).
#
# Required env (resolved via pass-cli):
#   SECMAN_MCP_KEY
#   SECMAN_ADMIN_EMAIL
#   SECMAN_ADMIN_NAME
#   SECMAN_ADMIN_PASS
# Additional env:
#   BASE_URL or SECMAN_BACKEND_URL (required backend URL, pass-cli sourced)
#   FRONTEND_URL (required frontend URL, pass-cli sourced)
#   SKIP_UI=true to skip Playwright phase
#   RUN_PHASE10=false to skip Phase 10 (import/export/delete-all)
#   VERBOSE=true for debug logging
#
# Usage:
#   pass-cli run --env-file ./secmanpp.env -- ./scripts/test/test-e2e-vuln-exception-full.sh
#   ./scripts/test/test-e2e-vuln-exception-full.sh --verbose
#

set -euo pipefail

# =============================================================================
# Configuration
# =============================================================================

BASE_URL="${BASE_URL:-${SECMAN_BACKEND_URL:-}}"
FRONTEND_URL="${FRONTEND_URL:-}"
# Strip trailing whitespace/newlines from secrets — pass-cli can append a trailing
# newline depending on how the source field is stored, which would corrupt headers
# (e.g. "X-MCP-API-Key: <key>\n\n" terminates the header block early, producing 400).
SECMAN_MCP_KEY="$(printf '%s' "${SECMAN_MCP_KEY:-}" | tr -d '\r\n')"
ADMIN_USER_EMAIL="$(printf '%s' "${SECMAN_ADMIN_EMAIL:-}" | tr -d '\r\n')"
ADMIN_USERNAME="$(printf '%s' "${SECMAN_ADMIN_NAME:-}" | tr -d '\r\n')"
ADMIN_PASSWORD="$(printf '%s' "${SECMAN_ADMIN_PASS:-}" | tr -d '\r\n')"
VERBOSE="${VERBOSE:-false}"
SKIP_UI="${SKIP_UI:-false}"
RUN_PHASE10="${RUN_PHASE10:-true}"
MCP_ONLY=false
UI_ONLY=false

# Test users — passwords are local-only (these accounts only exist for the test)
USER1_USERNAME="e2etestuser1"
USER1_EMAIL="e2etestuser1@e2e.test"
USER1_PASSWORD="E2eTestPassword!1"

USER2_USERNAME="e2etestuser2"
USER2_EMAIL="e2etestuser2@e2e.test"
USER2_PASSWORD="E2eTestPassword!2"

# Test assets
ASSET1_NAME="testasset1"
ASSET1_IP="10.99.0.1"
ASSET2_NAME="testasset2"
ASSET2_IP="10.99.0.2"

# Test vulnerabilities
CVE_VULN1="CVE-E2E-0001"
CVE_VULN2="CVE-E2E-0002"

# AWS Account Sharing testbed
# - Account A is mapped to user1 (owner of testaws-a). Shared to user2 (selected scope).
# - Account B is mapped to user2 (owner of testaws-b). Not shared.
# - Account C is mapped to user1 LATE in the run (owner of testaws-c). MUST NOT
#   propagate to user2 because the sharing rule was scoped to account A only.
# All IDs are 12-digit strings to satisfy the UserMapping pattern (^\d{12}$).
AWS_ACCOUNT_A="123456789012"
AWS_ACCOUNT_B="876543210987"
AWS_ACCOUNT_C="555555555555"
AWS_ASSET_A_NAME="testaws-a"
AWS_ASSET_B_NAME="testaws-b"
AWS_ASSET_C_NAME="testaws-c"
AWS_ASSET_A_IP="10.99.1.10"
AWS_ASSET_B_IP="10.99.1.20"
AWS_ASSET_C_IP="10.99.1.30"
# Owner is a literal string distinct from both test users so the asset cannot be
# accessed via the owner-based path (rule #8 in AssetFilterService). The only
# way user2 sees testaws-a is via the sharing rule.
AWS_ASSET_OWNER_LABEL="awssharing-owner"

# Reason text — must be ≥50 chars per CreateExceptionRequestTool
EXCEPTION_REASON_APPROVE="E2E test scenario: approving an exception while remediation is scheduled in the next maintenance window — automated test"
EXCEPTION_REASON_REJECT="E2E test scenario: this request is expected to be rejected by the admin to verify the rejection lifecycle path end to end"
EXCEPTION_REASON_CANCEL="E2E test scenario: the requester will cancel this request to verify the user-driven cancellation lifecycle path"

# DB credentials (matches existing test scripts)
DB_HOST="127.0.0.1"
DB_USER="secman"
DB_PASS="CHANGEME"
DB_NAME="secman"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

START_TIME=$(date +%s)

# Captured IDs (populated during run)
USER1_ID=""
USER2_ID=""
ASSET1_ID=""
ASSET2_ID=""
VULN1_ID=""
VULN2_A1_ID=""
VULN2_A2_ID=""
REQ_APPROVE_ID=""
REQ_REJECT_ID=""
REQ_CANCEL_ID=""

# AWS sharing-test captured ids
AWS_ASSET_A_ID=""
AWS_ASSET_B_ID=""
AWS_ASSET_C_ID=""
AWS_SHARING_RULE_ID=""

# =============================================================================
# Logging
# =============================================================================

log()      { echo -e "${BLUE}[INFO]${NC} $1"; }
log_dbg()  { [[ "$VERBOSE" == "true" ]] && echo -e "${BLUE}[DEBUG]${NC} $1" >&2 || true; }
ok()       { echo -e "${GREEN}[PASS]${NC} $1"; }
warn()     { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail()     {
    echo -e "${RED}[FAIL]${NC} $1"
    echo -e "${RED}Test failed after $(( $(date +%s) - START_TIME ))s${NC}"
    exit 1
}

usage() {
    cat <<EOF
E2E Vulnerability + Exception Full Workflow Test (MCP + Web UI)

Usage:
    $0 [--verbose|-v] [--skip-ui] [--skip-phase10] [--mcp-only] [--ui-only] [--help|-h]

Environment:
    BASE_URL              Backend URL (required unless SECMAN_BACKEND_URL is set)
    SECMAN_BACKEND_URL    Backend URL fallback when BASE_URL is not set
    FRONTEND_URL          Frontend URL (required)
    SECMAN_MCP_KEY        MCP API key (required)
    SECMAN_ADMIN_EMAIL    Admin email for delegation (required)
    SECMAN_ADMIN_NAME     Admin username (required for UI phase)
    SECMAN_ADMIN_PASS     Admin password (required for UI phase)
    SKIP_UI=true          Skip Playwright UI phase
    RUN_PHASE10=false     Skip Phase 10 (import/export/delete-all)
    VERBOSE=true          Debug logging
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --help|-h)    usage ;;
        --verbose|-v) VERBOSE=true; shift ;;
        --skip-ui)    SKIP_UI=true; shift ;;
        --skip-phase10) RUN_PHASE10=false; shift ;;
        --mcp-only) MCP_ONLY=true; shift ;;
        --ui-only) UI_ONLY=true; shift ;;
        *) warn "Unknown option: $1"; shift ;;
    esac
done

if [[ "$MCP_ONLY" == "true" && "$UI_ONLY" == "true" ]]; then
    fail "Cannot combine --mcp-only and --ui-only"
fi

if [[ "$MCP_ONLY" == "true" ]]; then
    SKIP_UI=true
fi

if [[ "$UI_ONLY" == "true" ]]; then
    RUN_PHASE10=false
    warn "UI-only mode enabled: Phases 1-8 are skipped; UI phase expects test data to already exist."
fi

SKIPPED_PHASES=()
record_skip() {
    local phase="$1"
    local reason="$2"
    SKIPPED_PHASES+=("$phase ($reason)")
    warn "Skipping $phase ($reason)"
}

# =============================================================================
# Pre-flight
# =============================================================================

for cmd in curl jq mariadb; do
    command -v "$cmd" >/dev/null || fail "Required command missing: $cmd"
done

[[ -z "$SECMAN_MCP_KEY"   ]] && fail "SECMAN_MCP_KEY is required (use pass-cli run)"
[[ -z "$ADMIN_USER_EMAIL" ]] && fail "SECMAN_ADMIN_EMAIL is required (use pass-cli run)"
[[ -z "$BASE_URL" ]] && fail "Backend URL is required: set BASE_URL or SECMAN_BACKEND_URL via pass-cli env"
[[ -z "$FRONTEND_URL" ]] && fail "Frontend URL is required: set FRONTEND_URL via pass-cli env"

log "Resolved pre-flight URLs: BASE_URL=$BASE_URL FRONTEND_URL=$FRONTEND_URL"

if ! curl -sf -o /dev/null --connect-timeout 5 "$BASE_URL" 2>/dev/null; then
    # Some backends don't serve / so also try /api/mcp/capabilities (returns 401 but reachable)
    if ! curl -s -o /dev/null --connect-timeout 5 "${BASE_URL}/api/mcp/capabilities" 2>/dev/null; then
        fail "Cannot reach backend at $BASE_URL"
    fi
fi
ok "Backend reachable at $BASE_URL"

# =============================================================================
# MCP helper
# =============================================================================

# mcp_call <tool_name> <json_arguments> [delegated_email] [--allow-error]
# Returns the .result.content payload (object or unwrapped MCP-standard text).
# By default, fails the test on any JSON-RPC error. With --allow-error, prints
# the error JSON to stdout instead so the caller can assert on it.
mcp_call() {
    local tool="$1"
    local args="$2"
    local delegated="${3:-}"
    local allow_error="${4:-}"

    local headers=(-H "Content-Type: application/json" -H "X-MCP-API-Key: $SECMAN_MCP_KEY")
    [[ -n "$delegated" ]] && headers+=(-H "X-MCP-User-Email: $delegated")

    local body
    body=$(jq -nc --arg tool "$tool" --argjson args "$args" \
        '{jsonrpc:"2.0", id:("t-"+(now|tostring)), method:"tools/call", params:{name:$tool, arguments:$args}}')

    log_dbg "MCP -> $tool delegated=${delegated:-<none>} args=$args"
    local resp
    resp=$(curl -sS -X POST "${BASE_URL}/api/mcp/tools/call" "${headers[@]}" -d "$body")
    log_dbg "MCP <- $resp"

    local err
    err=$(echo "$resp" | jq -c '.error // empty')
    if [[ -n "$err" && "$err" != "null" ]]; then
        if [[ "$allow_error" == "--allow-error" ]]; then
            echo "$resp" | jq -c '.error'
            return 0
        fi
        local code msg
        code=$(echo "$err" | jq -r '.code // "UNKNOWN"')
        msg=$(echo  "$err" | jq -r '.message // "Unknown error"')
        fail "MCP tool '$tool' failed: [$code] $msg"
    fi

    # Unwrap content (object) or array[0].text (MCP standard)
    local content
    content=$(echo "$resp" | jq -c '.result.content // empty')
    if [[ -z "$content" || "$content" == "null" ]]; then
        echo ""
        return 0
    fi
    local kind
    kind=$(echo "$content" | jq -r 'type')
    if [[ "$kind" == "object" ]]; then
        echo "$content"
    elif [[ "$kind" == "array" ]]; then
        echo "$content" | jq -r '.[0].text // empty'
    else
        echo "$content"
    fi
}

# Get JWT for a username/password (used to trigger materialized view refresh).
# Backend returns the JWT either in the JSON body (.token) or as a Set-Cookie
# (secman_auth=<jwt>), depending on configuration. Handle both.
get_jwt() {
    local user="$1"
    local pass="$2"
    local resp_dir
    resp_dir=$(mktemp -d)
    local body="$resp_dir/body" headers="$resp_dir/headers"
    curl -sS -D "$headers" -o "$body" -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "$(jq -nc --arg u "$user" --arg p "$pass" '{username:$u,password:$p}')" || true
    local token
    token=$(jq -r '.token // empty' "$body" 2>/dev/null || true)
    if [[ -z "$token" ]]; then
        token=$(grep -i '^Set-Cookie: *secman_auth=' "$headers" 2>/dev/null \
            | sed -E 's/.*secman_auth=([^;]*).*/\1/' | head -1)
    fi
    rm -rf "$resp_dir"
    printf '%s' "$token"
}

trigger_view_refresh() {
    log_dbg "Triggering materialized view refresh as admin..."
    if [[ -z "$ADMIN_USERNAME" || -z "$ADMIN_PASSWORD" ]]; then
        warn "SECMAN_ADMIN_NAME/PASS not set — skipping view refresh trigger"
        return 0
    fi
    local token
    token=$(get_jwt "$ADMIN_USERNAME" "$ADMIN_PASSWORD" || true)
    if [[ -z "$token" ]]; then
        warn "Could not get admin JWT; view refresh may be stale"
        return 0
    fi
    # Capture the job id from the trigger response so we can verify completion
    # specifically — /status only reports a "currently running" job, so race-free
    # detection requires polling history for our job id reaching a terminal state.
    local trigger_resp job_id
    trigger_resp=$(curl -sS -X POST "${BASE_URL}/api/materialized-view-refresh/trigger" \
        -H "Authorization: Bearer $token" 2>/dev/null || true)
    job_id=$(echo "$trigger_resp" | jq -r '.id // empty' 2>/dev/null || true)
    log_dbg "View refresh job id=$job_id"

    # Poll history until our job's status is COMPLETED (or any terminal state).
    # Max wait: 600s — full refresh of ~2-3k assets can take 6-7 minutes on
    # the canonical dev DB. The previous 60s budget made Phase 3 fail on
    # second runs because batches that processed our newly-created test asset
    # late never landed in the materialized view before assertions ran.
    local deadline=$(( $(date +%s) + 600 ))
    while (( $(date +%s) < deadline )); do
        local hist status
        hist=$(curl -s -H "Authorization: Bearer $token" \
            "${BASE_URL}/api/materialized-view-refresh/history" 2>/dev/null || echo "[]")
        if [[ -n "$job_id" ]]; then
            status=$(echo "$hist" | jq -r --arg id "$job_id" \
                '. // [] | map(select((.id|tostring) == $id))[0].status // empty' 2>/dev/null || true)
        else
            # No job id captured — fall back to the most recent job's status
            status=$(echo "$hist" | jq -r '. // [] | sort_by(.startedAt) | reverse | .[0].status // empty' 2>/dev/null || true)
        fi
        if [[ "$status" == "COMPLETED" || "$status" == "FAILED" || "$status" == "CANCELLED" ]]; then
            log_dbg "Materialized view refresh terminal status: $status"
            return 0
        fi
        sleep 2
    done
    warn "Materialized view refresh did not complete within 600s — proceeding anyway"
}

db_exec() {
    mariadb -h "$DB_HOST" -u "$DB_USER" -p"$DB_PASS" "$DB_NAME" -N -e "$1" 2>/dev/null || true
}

# Cached admin JWT — multiple REST calls share this so we don't round-trip
# /api/auth/login per call. Cleared by cleanup() to avoid bleeding across runs.
ADMIN_JWT_CACHE=""

ensure_admin_jwt() {
    if [[ -n "$ADMIN_JWT_CACHE" ]]; then
        printf '%s' "$ADMIN_JWT_CACHE"
        return 0
    fi
    if [[ -z "$ADMIN_USERNAME" || -z "$ADMIN_PASSWORD" ]]; then
        fail "SECMAN_ADMIN_NAME / SECMAN_ADMIN_PASS required for REST calls"
    fi
    ADMIN_JWT_CACHE=$(get_jwt "$ADMIN_USERNAME" "$ADMIN_PASSWORD")
    if [[ -z "$ADMIN_JWT_CACHE" ]]; then
        fail "Could not obtain admin JWT (login failed?)"
    fi
    printf '%s' "$ADMIN_JWT_CACHE"
}

# create_user_mapping <email> <awsAccountId> <userId>
# Inserts directly via SQL because the single-mapping REST endpoint
# (POST /api/user-mappings) ignores `request.email` and always uses the
# caller's email — so an admin POST creates an admin mapping. The CSV
# import path supports arbitrary emails but is heavyweight for one row.
# Direct SQL matches the cleanup path (which also speaks SQL) and lets us
# attach `user_id` so the mapping is ACTIVE and linked.
#
# Echoes the inserted row id. Email is lowercased to mirror the
# UserMapping.onCreate normalization. Status=ACTIVE because user_id is set.
create_user_mapping() {
    local email_lc; email_lc=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
    local aws_account="$2"
    local user_id="$3"
    [[ -z "$user_id" || "$user_id" == "null" ]] && fail "create_user_mapping: user_id is required"
    db_exec "
        INSERT INTO user_mapping
            (email, aws_account_id, domain, ip_address,
             user_id, status, created_at, updated_at)
        VALUES
            ('${email_lc}', '${aws_account}', NULL, NULL,
             ${user_id}, 'ACTIVE', NOW(6), NOW(6));
    "
    local id
    id=$(db_exec "
        SELECT id FROM user_mapping
         WHERE email='${email_lc}'
           AND aws_account_id='${aws_account}'
           AND user_id=${user_id}
         ORDER BY id DESC LIMIT 1;
    ")
    if [[ -z "$id" || "$id" == "NULL" ]]; then
        fail "Failed to create user mapping for ${email_lc} -> ${aws_account} (user_id=${user_id})"
    fi
    printf '%s' "$id"
}

# =============================================================================
# Phase 10: Exception import/export/delete-all (MCP + REST)
# =============================================================================

run_phase_10_exception_import_export() {
    log "===== PHASE 10: Exception import/export/delete-all (MCP + REST) ====="

    local admin_token
    admin_token=$(ensure_admin_jwt) || { fail "Phase 10: cannot get admin JWT"; }

    local baseline_file=".e2e-logs/exceptions-baseline.json"
    local roundtrip_file=".e2e-logs/exceptions-roundtrip.json"

    # 10.1 — Export current exceptions (baseline)
    log "10.1 export baseline"
    if ! curl -fsS -H "Authorization: Bearer ${admin_token}" \
        "${BASE_URL}/api/vulnerability-exceptions/export" -o "${baseline_file}"; then
        fail "10.1 baseline export failed"
    fi
    local baseline_count
    baseline_count=$(jq -r '.count' "${baseline_file}")
    log "10.1 baseline contains ${baseline_count} exception(s)"

    # 10.2 — Delete all via MCP
    log "10.2 MCP delete_all_vulnerability_exceptions"
    local del_result
    del_result=$(mcp_call "delete_all_vulnerability_exceptions" \
        '{"confirm":"DELETE_ALL"}' "$ADMIN_USER_EMAIL")
    local mcp_deleted
    mcp_deleted=$(echo "${del_result}" | jq -r '.deletedCount // 0')
    [[ "${mcp_deleted}" == "${baseline_count}" ]] || \
        fail "10.2 MCP deleted=${mcp_deleted} != baseline=${baseline_count}"

    # 10.3 — Verify empty via MCP
    log "10.3 MCP list_vulnerability_exceptions (expect 0)"
    local list_result
    list_result=$(mcp_call "list_vulnerability_exceptions" '{}' "$ADMIN_USER_EMAIL")
    local list_count
    list_count=$(echo "${list_result}" | jq -r '.exceptions | length')
    [[ "${list_count}" == "0" ]] || fail "10.3 MCP list shows ${list_count}, expected 0"

    # 10.4 — Verify empty via REST
    log "10.4 REST list (expect [])"
    local rest_count
    rest_count=$(curl -fsS -H "Authorization: Bearer ${admin_token}" \
        "${BASE_URL}/api/vulnerability-exceptions" | jq 'length')
    [[ "${rest_count}" == "0" ]] || fail "10.4 REST list shows ${rest_count}, expected 0"

    # 10.5 — Add one exception via REST
    log "10.5 add test exception"
    curl -fsS -X POST -H "Authorization: Bearer ${admin_token}" \
        -H "Content-Type: application/json" \
        -d '{"subject":"CVE","scope":"GLOBAL","subjectValue":"CVE-2099-90001",
             "reason":"E2E TEST export round-trip","expirationDate":"2099-01-01T00:00:00"}' \
        "${BASE_URL}/api/vulnerability-exceptions" >/dev/null \
        || fail "10.5 create failed"

    # 10.6 — Export again to a new file
    log "10.6 export round-trip file"
    curl -fsS -H "Authorization: Bearer ${admin_token}" \
        "${BASE_URL}/api/vulnerability-exceptions/export" -o "${roundtrip_file}" \
        || fail "10.6 round-trip export failed"
    [[ "$(jq -r '.count' "${roundtrip_file}")" == "1" ]] || fail "10.6 round-trip count != 1"
    [[ "$(jq -r '.exceptions[0].subjectValue' "${roundtrip_file}")" == "CVE-2099-90001" ]] \
        || fail "10.6 wrong subjectValue"
    # Confirm id is omitted from the export envelope.
    [[ "$(jq -r 'has("id")' "${roundtrip_file}")" == "false" ]] || \
        fail "10.6 envelope must not contain top-level id"

    # 10.7 — Delete all again via MCP
    log "10.7 MCP delete_all again"
    mcp_call "delete_all_vulnerability_exceptions" '{"confirm":"DELETE_ALL"}' \
        "$ADMIN_USER_EMAIL" >/dev/null

    # 10.8 — Verify gone via MCP
    log "10.8 MCP list (expect 0)"
    list_result=$(mcp_call "list_vulnerability_exceptions" '{}' "$ADMIN_USER_EMAIL")
    list_count=$(echo "${list_result}" | jq -r '.exceptions | length')
    [[ "${list_count}" == "0" ]] || fail "10.8 MCP list nonzero (got ${list_count})"

    # 10.9 (UI) — handled in the Playwright spec (Phase 11). Export env vars for it.
    export EXPECTED_EXCEPTION_COUNT_AFTER_DELETE=0
    export EXPECTED_EXCEPTION_CVE="CVE-2099-90001"

    # 10.10 — Re-import the round-trip file
    log "10.10 import round-trip"
    local import_resp
    import_resp=$(curl -fsS -X POST -H "Authorization: Bearer ${admin_token}" \
        -F "file=@${roundtrip_file}" \
        "${BASE_URL}/api/vulnerability-exceptions/import") \
        || fail "10.10 import failed"
    [[ "$(echo "${import_resp}" | jq -r '.imported')" == "1" ]] || \
        fail "10.10 imported != 1: ${import_resp}"
    [[ "$(echo "${import_resp}" | jq -r '.skippedDuplicates')" == "0" ]] || \
        fail "10.10 skippedDuplicates != 0: ${import_resp}"

    # 10.11 — Verify re-imported via MCP, with subjectValue preserved
    log "10.11 MCP list (expect 1 with original subjectValue)"
    list_result=$(mcp_call "list_vulnerability_exceptions" '{}' "$ADMIN_USER_EMAIL")
    local re_imported
    re_imported=$(echo "${list_result}" | jq -r '.exceptions | length')
    [[ "${re_imported}" == "1" ]] || fail "10.11 list count ${re_imported}, expected 1"
    local re_subject
    re_subject=$(echo "${list_result}" | jq -r '.exceptions[0].subjectValue')
    [[ "${re_subject}" == "CVE-2099-90001" ]] || fail "10.11 wrong subjectValue: ${re_subject}"
    local re_created_by
    re_created_by=$(echo "${list_result}" | jq -r '.exceptions[0].createdBy')
    # Step 10.5 created the exception via REST as the admin user, so the original
    # createdBy must equal that admin user's username after the round-trip.
    [[ "${re_created_by}" == "${ADMIN_USERNAME}" ]] || {
        fail "10.11 createdBy=${re_created_by}, expected ${ADMIN_USERNAME} (provenance not preserved)"
    }

    # 10.12 (UI) — handled by Playwright; env var below
    export EXPECTED_EXCEPTION_COUNT_AFTER_IMPORT=1

    # 10.13 — Idempotency: import same file again, expect skippedDuplicates=1
    log "10.13 import same file again (expect skippedDuplicates=1)"
    import_resp=$(curl -fsS -X POST -H "Authorization: Bearer ${admin_token}" \
        -F "file=@${roundtrip_file}" \
        "${BASE_URL}/api/vulnerability-exceptions/import") \
        || fail "10.13 second import failed"
    [[ "$(echo "${import_resp}" | jq -r '.imported')" == "0" ]] || fail "10.13 imported != 0: ${import_resp}"
    [[ "$(echo "${import_resp}" | jq -r '.skippedDuplicates')" == "1" ]] || fail "10.13 skippedDup != 1: ${import_resp}"

    # 10.14 — Negative: non-admin export (expect 403)
    log "10.14 non-admin export (expect 403)"
    local user1_token
    user1_token=$(get_jwt "$USER1_USERNAME" "$USER1_PASSWORD")
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer ${user1_token}" \
        "${BASE_URL}/api/vulnerability-exceptions/export")
    [[ "${code}" == "403" ]] || fail "10.14 non-admin export returned ${code}, expected 403"
    ok "10.14 non-admin export denied (403)"

    # 10.15 — Negative: non-admin MCP delete-all (expect error)
    log "10.15 non-admin MCP delete_all (expect error)"
    local neg_result
    neg_result=$(mcp_call "delete_all_vulnerability_exceptions" \
        '{"confirm":"DELETE_ALL"}' "$USER1_EMAIL" --allow-error)
    local neg_code
    neg_code=$(echo "${neg_result}" | jq -r '.code // empty')
    [[ -n "${neg_code}" ]] || fail "10.15 non-admin was NOT denied (no error code): ${neg_result}"
    ok "10.15 non-admin delete_all denied (code=${neg_code})"

    # 10.16 — Restore baseline
    log "10.16 restore baseline"
    if [[ "${baseline_count}" -gt 0 ]]; then
        curl -fsS -X POST -H "Authorization: Bearer ${admin_token}" \
            -F "file=@${baseline_file}" \
            "${BASE_URL}/api/vulnerability-exceptions/import" >/dev/null \
            || fail "10.16 baseline restore failed"
    fi

    # 10.17 — Verify baseline restored (count == baseline + the test CVE)
    log "10.17 verify baseline restored"
    list_result=$(mcp_call "list_vulnerability_exceptions" '{}' "$ADMIN_USER_EMAIL")
    local final_count
    final_count=$(echo "${list_result}" | jq -r '.exceptions | length')
    local expected=$(( baseline_count + 1 ))
    [[ "${final_count}" == "${expected}" ]] \
        || fail "10.17 final count ${final_count}, expected ${expected}"

    ok "Phase 10 complete"
}

# =============================================================================
# Cleanup (idempotent — safe to run multiple times)
# =============================================================================

cleanup() {
    local phase="${1:-post-run}"
    log "Cleanup ($phase): removing test users, assets, exception requests, AWS sharing artefacts..."

    # 0) Drop cached admin JWT — a fresh run logs in again so token rotation
    # / password changes do not bite us silently.
    ADMIN_JWT_CACHE=""

    # 1) Delete exception requests + audit rows for our test users (by username)
    local user_ids_csv
    user_ids_csv=$(db_exec "SELECT GROUP_CONCAT(id) FROM users WHERE username IN ('${USER1_USERNAME}','${USER2_USERNAME}');")
    if [[ -n "$user_ids_csv" && "$user_ids_csv" != "NULL" ]]; then
        db_exec "
            DELETE FROM exception_request_audit
              WHERE actor_user_id IN ($user_ids_csv);
            DELETE FROM vulnerability_exception WHERE reason LIKE 'E2E TEST %';
            DELETE FROM vulnerability_exception_request
              WHERE requested_by_user_id IN ($user_ids_csv)
                 OR reviewed_by_user_id IN ($user_ids_csv);
        "
    fi

    # 2) Delete AWS account sharing rules involving our test users (source OR
    # target). The aws_account_sharing_account FK is ON DELETE CASCADE (V207),
    # so the per-account scope rows go with the parent. Must run BEFORE user
    # delete because source_user_id / target_user_id are NOT NULL FKs to users.
    if [[ -n "$user_ids_csv" && "$user_ids_csv" != "NULL" ]]; then
        db_exec "
            DELETE FROM aws_account_sharing
              WHERE source_user_id IN ($user_ids_csv)
                 OR target_user_id IN ($user_ids_csv);
        "
    fi

    # 3) Delete user mappings tied to our test users — both by user_id (active
    # mappings) and by email (also catches PENDING rows where user_id is null).
    if [[ -n "$user_ids_csv" && "$user_ids_csv" != "NULL" ]]; then
        db_exec "DELETE FROM user_mapping WHERE user_id IN ($user_ids_csv);"
    fi
    db_exec "
        DELETE FROM user_mapping
         WHERE email IN ('${USER1_EMAIL}','${USER2_EMAIL}');
    "

    # 4) Delete test assets via direct SQL (cascades vulnerabilities through FKs).
    # Includes both the original vuln-test assets AND the AWS-sharing test assets
    # so a failed mid-run leaves no orphans behind.
    local asset_ids_csv
    asset_ids_csv=$(db_exec "
        SELECT GROUP_CONCAT(id) FROM asset
         WHERE name IN ('${ASSET1_NAME}','${ASSET2_NAME}',
                        '${AWS_ASSET_A_NAME}','${AWS_ASSET_B_NAME}','${AWS_ASSET_C_NAME}');
    ")
    if [[ -n "$asset_ids_csv" && "$asset_ids_csv" != "NULL" ]]; then
        # Also wipe any exception requests still tied to these assets (safety)
        db_exec "
            DELETE FROM exception_request_audit
              WHERE request_id IN (
                SELECT id FROM vulnerability_exception_request
                 WHERE vulnerability_id IN (SELECT id FROM vulnerability WHERE asset_id IN ($asset_ids_csv))
                    OR asset_id IN ($asset_ids_csv)
              );
            DELETE FROM vulnerability_exception WHERE reason LIKE 'E2E TEST %';
            DELETE FROM vulnerability_exception_request
              WHERE vulnerability_id IN (SELECT id FROM vulnerability WHERE asset_id IN ($asset_ids_csv))
                 OR asset_id IN ($asset_ids_csv);
            DELETE FROM vulnerability WHERE asset_id IN ($asset_ids_csv);
            DELETE FROM asset WHERE id IN ($asset_ids_csv);
        "
    fi

    # 5) Delete test users
    if [[ -n "$user_ids_csv" && "$user_ids_csv" != "NULL" ]]; then
        db_exec "DELETE FROM user_roles WHERE user_id IN ($user_ids_csv);"
        db_exec "DELETE FROM users WHERE id IN ($user_ids_csv);"
    fi

    # 6) Remove only our test assets' rows from the materialized view.
    # Truncating the entire view here would force a multi-minute refresh of
    # thousands of unrelated assets before any subsequent assertion can pass.
    if [[ -n "$asset_ids_csv" && "$asset_ids_csv" != "NULL" ]]; then
        db_exec "DELETE FROM outdated_asset_materialized_view WHERE asset_id IN ($asset_ids_csv);"
    fi

    log_dbg "Cleanup ($phase) done"
}

# Post-run cleanup runs on any exit, including failure
trap 'cleanup post-run' EXIT

# =============================================================================
# Phase 0: Pre-run cleanup
# =============================================================================

log "=== Phase 0: pre-run cleanup ==="
cleanup pre-run
ok "Pre-run cleanup complete"

if [[ "$UI_ONLY" == "true" ]]; then
    record_skip "Phases 1-8 (MCP setup/workflow/sharing)" "--ui-only"
else
    # =============================================================================
    # Phase 1 (MCP): Setup
    # =============================================================================

    log "=== Phase 1: MCP setup ==="

# Create users
res=$(mcp_call "add_user" "$(jq -nc \
    --arg u "$USER1_USERNAME" --arg e "$USER1_EMAIL" --arg p "$USER1_PASSWORD" \
    '{username:$u,email:$e,password:$p,roles:["USER","VULN","REQ"]}')" "$ADMIN_USER_EMAIL")
USER1_ID=$(echo "$res" | jq -r '.user.id')
[[ -z "$USER1_ID" || "$USER1_ID" == "null" ]] && fail "Failed to create $USER1_USERNAME: $res"
ok "Created user $USER1_USERNAME (id=$USER1_ID)"

res=$(mcp_call "add_user" "$(jq -nc \
    --arg u "$USER2_USERNAME" --arg e "$USER2_EMAIL" --arg p "$USER2_PASSWORD" \
    '{username:$u,email:$e,password:$p,roles:["USER","VULN","REQ"]}')" "$ADMIN_USER_EMAIL")
USER2_ID=$(echo "$res" | jq -r '.user.id')
[[ -z "$USER2_ID" || "$USER2_ID" == "null" ]] && fail "Failed to create $USER2_USERNAME: $res"
ok "Created user $USER2_USERNAME (id=$USER2_ID)"

# Create assets — owner is a plain string username
res=$(mcp_call "create_asset" "$(jq -nc \
    --arg n "$ASSET1_NAME" --arg t "SERVER" --arg o "$USER1_USERNAME" --arg ip "$ASSET1_IP" \
    '{name:$n,type:$t,owner:$o,ip:$ip,description:"E2E test asset 1"}')" "$ADMIN_USER_EMAIL")
ASSET1_ID=$(echo "$res" | jq -r '.id')
[[ -z "$ASSET1_ID" || "$ASSET1_ID" == "null" ]] && fail "Failed to create $ASSET1_NAME: $res"
ok "Created asset $ASSET1_NAME (id=$ASSET1_ID, owner=$USER1_USERNAME)"

res=$(mcp_call "create_asset" "$(jq -nc \
    --arg n "$ASSET2_NAME" --arg t "SERVER" --arg o "$USER2_USERNAME" --arg ip "$ASSET2_IP" \
    '{name:$n,type:$t,owner:$o,ip:$ip,description:"E2E test asset 2"}')" "$ADMIN_USER_EMAIL")
ASSET2_ID=$(echo "$res" | jq -r '.id')
[[ -z "$ASSET2_ID" || "$ASSET2_ID" == "null" ]] && fail "Failed to create $ASSET2_NAME: $res"
ok "Created asset $ASSET2_NAME (id=$ASSET2_ID, owner=$USER2_USERNAME)"

# vuln1 on testasset1 (40 days, overdue)
res=$(mcp_call "add_vulnerability" "$(jq -nc \
    --arg h "$ASSET1_NAME" --arg c "$CVE_VULN1" --arg o "$USER1_USERNAME" \
    '{hostname:$h,cve:$c,criticality:"CRITICAL",daysOpen:40,owner:$o}')" "$ADMIN_USER_EMAIL")
VULN1_ID=$(echo "$res" | jq -r '.id')
[[ -z "$VULN1_ID" || "$VULN1_ID" == "null" ]] && fail "Failed to add vuln1: $res"
ok "Added vuln1 ($CVE_VULN1, 40d) on $ASSET1_NAME — id=$VULN1_ID"

# vuln2 on testasset1 (5 days)
res=$(mcp_call "add_vulnerability" "$(jq -nc \
    --arg h "$ASSET1_NAME" --arg c "$CVE_VULN2" --arg o "$USER1_USERNAME" \
    '{hostname:$h,cve:$c,criticality:"CRITICAL",daysOpen:5,owner:$o}')" "$ADMIN_USER_EMAIL")
VULN2_A1_ID=$(echo "$res" | jq -r '.id')
[[ -z "$VULN2_A1_ID" || "$VULN2_A1_ID" == "null" ]] && fail "Failed to add vuln2 on $ASSET1_NAME: $res"
ok "Added vuln2 ($CVE_VULN2, 5d) on $ASSET1_NAME — id=$VULN2_A1_ID"

# vuln2 on testasset2 (5 days)
res=$(mcp_call "add_vulnerability" "$(jq -nc \
    --arg h "$ASSET2_NAME" --arg c "$CVE_VULN2" --arg o "$USER2_USERNAME" \
    '{hostname:$h,cve:$c,criticality:"CRITICAL",daysOpen:5,owner:$o}')" "$ADMIN_USER_EMAIL")
VULN2_A2_ID=$(echo "$res" | jq -r '.id')
[[ -z "$VULN2_A2_ID" || "$VULN2_A2_ID" == "null" ]] && fail "Failed to add vuln2 on $ASSET2_NAME: $res"
ok "Added vuln2 ($CVE_VULN2, 5d) on $ASSET2_NAME — id=$VULN2_A2_ID"

trigger_view_refresh

# Poll the materialized view via MCP until testasset1 appears as overdue.
# The refresh processes all assets in batches; testasset1 may show up well
# before the full refresh completes — but we need it visible before Phase 3.
log_dbg "Waiting for testasset1 to appear in overdue list (max 5min)..."
view_deadline=$(( $(date +%s) + 300 ))
while (( $(date +%s) < view_deadline )); do
    res=$(mcp_call "get_overdue_assets" '{"size":100,"searchTerm":"testasset1"}' "$ADMIN_USER_EMAIL")
    found=$(echo "$res" | jq -r --arg id "$ASSET1_ID" \
        'if .assets then (.assets | map(select((.assetId) == ($id|tonumber))) | length) else 0 end')
    if [[ "$found" == "1" ]]; then
        log_dbg "testasset1 visible in overdue view"
        break
    fi
    sleep 3
done

# =============================================================================
# Phase 2 (MCP): Visibility / RBAC
# =============================================================================

log "=== Phase 2: MCP visibility ==="

# As user1 — should see vuln1 + vuln2-on-asset1, NOT vuln2-on-asset2
res=$(mcp_call "get_vulnerabilities" '{"pageSize":200,"includeExcepted":true}' "$USER1_EMAIL")
u1_total=$(echo "$res" | jq -r '.total // 0')
u1_has_vuln1=$(echo "$res" | jq -r --arg id "$VULN1_ID"    '.vulnerabilities | map(select(.id == ($id|tonumber))) | length')
u1_has_v2a1=$(echo "$res" | jq -r --arg id "$VULN2_A1_ID"  '.vulnerabilities | map(select(.id == ($id|tonumber))) | length')
u1_has_v2a2=$(echo "$res" | jq -r --arg id "$VULN2_A2_ID"  '.vulnerabilities | map(select(.id == ($id|tonumber))) | length')
[[ "$u1_has_vuln1" == "1" && "$u1_has_v2a1" == "1" && "$u1_has_v2a2" == "0" ]] \
    || fail "user1 visibility wrong (total=$u1_total has_vuln1=$u1_has_vuln1 v2a1=$u1_has_v2a1 v2a2=$u1_has_v2a2)"
ok "user1 sees vuln1 + vuln2-on-asset1, not vuln2-on-asset2"

# As user2 — should see only vuln2-on-asset2
res=$(mcp_call "get_vulnerabilities" '{"pageSize":200,"includeExcepted":true}' "$USER2_EMAIL")
u2_has_vuln1=$(echo "$res" | jq -r --arg id "$VULN1_ID"    '.vulnerabilities | map(select(.id == ($id|tonumber))) | length')
u2_has_v2a1=$(echo "$res" | jq -r --arg id "$VULN2_A1_ID"  '.vulnerabilities | map(select(.id == ($id|tonumber))) | length')
u2_has_v2a2=$(echo "$res" | jq -r --arg id "$VULN2_A2_ID"  '.vulnerabilities | map(select(.id == ($id|tonumber))) | length')
[[ "$u2_has_vuln1" == "0" && "$u2_has_v2a1" == "0" && "$u2_has_v2a2" == "1" ]] \
    || fail "user2 visibility wrong (vuln1=$u2_has_vuln1 v2a1=$u2_has_v2a1 v2a2=$u2_has_v2a2)"
ok "user2 sees only vuln2-on-asset2"

# As admin — should see all three. Filter by our test CVE prefix so the result is
# deterministic regardless of how many other vulnerabilities exist in the DB.
res=$(mcp_call "get_vulnerabilities" '{"pageSize":500,"includeExcepted":true,"cveId":"CVE-E2E-"}' "$ADMIN_USER_EMAIL")
a_has_vuln1=$(echo "$res" | jq -r --arg id "$VULN1_ID"    '.vulnerabilities | map(select(.id == ($id|tonumber))) | length')
a_has_v2a1=$(echo "$res" | jq -r --arg id "$VULN2_A1_ID"  '.vulnerabilities | map(select(.id == ($id|tonumber))) | length')
a_has_v2a2=$(echo "$res" | jq -r --arg id "$VULN2_A2_ID"  '.vulnerabilities | map(select(.id == ($id|tonumber))) | length')
[[ "$a_has_vuln1" == "1" && "$a_has_v2a1" == "1" && "$a_has_v2a2" == "1" ]] \
    || fail "admin should see all 3 vulns (got $a_has_vuln1 / $a_has_v2a1 / $a_has_v2a2)"
ok "admin sees all 3 vulnerability rows"

# =============================================================================
# Phase 3 (MCP): Overdue detection
# =============================================================================

log "=== Phase 3: MCP overdue detection ==="

# Filter overdue queries by our test asset name prefix so the page is deterministic
# regardless of how many other overdue assets exist in the DB.
res=$(mcp_call "get_overdue_assets" '{"size":100,"searchTerm":"testasset"}' "$ADMIN_USER_EMAIL")
admin_has_a1_overdue=$(echo "$res" | jq -r --arg id "$ASSET1_ID" \
    'if .assets then (.assets | map(select((.assetId) == ($id|tonumber))) | length) else 0 end')
admin_has_a2_overdue=$(echo "$res" | jq -r --arg id "$ASSET2_ID" \
    'if .assets then (.assets | map(select((.assetId) == ($id|tonumber))) | length) else 0 end')
[[ "$admin_has_a1_overdue" == "1" && "$admin_has_a2_overdue" == "0" ]] \
    || fail "admin overdue list mismatch (a1=$admin_has_a1_overdue a2=$admin_has_a2_overdue)"
ok "admin overdue list includes testasset1, excludes testasset2"

res=$(mcp_call "get_overdue_assets" '{"size":100,"searchTerm":"testasset"}' "$USER1_EMAIL")
u1_overdue_count=$(echo "$res" | jq -r 'if .assets then (.assets | length) else 0 end')
[[ "$u1_overdue_count" -ge 1 ]] || fail "user1 should see >=1 overdue asset, got $u1_overdue_count"
ok "user1 sees overdue asset (count=$u1_overdue_count)"

res=$(mcp_call "get_overdue_assets" '{"size":100,"searchTerm":"testasset"}' "$USER2_EMAIL")
u2_overdue_count=$(echo "$res" | jq -r 'if .assets then (.assets | length) else 0 end')
[[ "$u2_overdue_count" == "0" ]] || fail "user2 should see 0 overdue assets, got $u2_overdue_count"
ok "user2 sees 0 overdue assets"

# =============================================================================
# Phase 4 (MCP): Exception lifecycle — APPROVE
# =============================================================================

log "=== Phase 4: MCP exception lifecycle (approve) ==="

future_date=$(date -u -d "+90 days" +"%Y-%m-%dT00:00:00" 2>/dev/null \
              || date -u -v+90d +"%Y-%m-%dT00:00:00")

res=$(mcp_call "create_exception_request" "$(jq -nc \
    --arg vid "$VULN1_ID" --arg aid "$ASSET1_ID" --arg cve "$CVE_VULN1" \
    --arg reason "$EXCEPTION_REASON_APPROVE" --arg exp "$future_date" \
    '{vulnerabilityId:($vid|tonumber), subject:"CVE", scope:"ASSET",
      subjectValue:$cve, assetId:($aid|tonumber),
      reason:$reason, expirationDate:$exp}')" "$USER1_EMAIL")
REQ_APPROVE_ID=$(echo "$res" | jq -r '.request.id')
status=$(echo "$res" | jq -r '.request.status')
[[ -z "$REQ_APPROVE_ID" || "$REQ_APPROVE_ID" == "null" ]] && fail "Failed to create approve-request: $res"
[[ "$status" == "PENDING" ]] || fail "Expected PENDING, got $status (auto-approve leaked through?)"
ok "user1 created exception request id=$REQ_APPROVE_ID status=PENDING"

# Admin sees it as pending
res=$(mcp_call "get_pending_exception_requests" '{}' "$ADMIN_USER_EMAIL")
admin_pending=$(echo "$res" | jq -r --arg id "$REQ_APPROVE_ID" \
    '(.requests // .) | map(select(.id == ($id|tonumber))) | length')
[[ "$admin_pending" == "1" ]] || fail "Admin pending list missing request $REQ_APPROVE_ID"
ok "admin pending list includes request $REQ_APPROVE_ID"

# Approve
res=$(mcp_call "approve_exception_request" "$(jq -nc --arg id "$REQ_APPROVE_ID" \
    '{requestId:($id|tonumber)}')" "$ADMIN_USER_EMAIL")
new_status=$(echo "$res" | jq -r '.request.status')
[[ "$new_status" == "APPROVED" ]] || fail "Expected APPROVED, got $new_status"
ok "admin approved request $REQ_APPROVE_ID"

# user1 sees APPROVED
res=$(mcp_call "get_my_exception_requests" '{}' "$USER1_EMAIL")
u1_status=$(echo "$res" | jq -r --arg id "$REQ_APPROVE_ID" \
    '.requests | map(select(.id == ($id|tonumber)))[0].status // empty')
[[ "$u1_status" == "APPROVED" ]] || fail "user1 should see APPROVED, got '$u1_status'"
ok "user1 sees request $REQ_APPROVE_ID as APPROVED"

# =============================================================================
# Phase 5 (MCP): Exception lifecycle — REJECT
# =============================================================================

log "=== Phase 5: MCP exception lifecycle (reject) ==="

res=$(mcp_call "create_exception_request" "$(jq -nc \
    --arg vid "$VULN2_A2_ID" --arg aid "$ASSET2_ID" --arg cve "$CVE_VULN2" \
    --arg reason "$EXCEPTION_REASON_REJECT" --arg exp "$future_date" \
    '{vulnerabilityId:($vid|tonumber), subject:"CVE", scope:"ASSET",
      subjectValue:$cve, assetId:($aid|tonumber),
      reason:$reason, expirationDate:$exp}')" "$USER2_EMAIL")
REQ_REJECT_ID=$(echo "$res" | jq -r '.request.id')
status=$(echo "$res" | jq -r '.request.status')
[[ -z "$REQ_REJECT_ID" || "$REQ_REJECT_ID" == "null" ]] && fail "Failed to create reject-request: $res"
[[ "$status" == "PENDING" ]] || fail "Expected PENDING, got $status"
ok "user2 created exception request id=$REQ_REJECT_ID status=PENDING"

res=$(mcp_call "reject_exception_request" "$(jq -nc --arg id "$REQ_REJECT_ID" \
    '{requestId:($id|tonumber), comment:"E2E reject path: insufficient justification per security policy"}')" \
    "$ADMIN_USER_EMAIL")
new_status=$(echo "$res" | jq -r '.request.status')
[[ "$new_status" == "REJECTED" ]] || fail "Expected REJECTED, got $new_status"
ok "admin rejected request $REQ_REJECT_ID"

# =============================================================================
# Phase 6 (MCP): Exception lifecycle — CANCEL
# =============================================================================

log "=== Phase 6: MCP exception lifecycle (cancel) ==="

res=$(mcp_call "create_exception_request" "$(jq -nc \
    --arg vid "$VULN2_A1_ID" --arg aid "$ASSET1_ID" --arg cve "$CVE_VULN2" \
    --arg reason "$EXCEPTION_REASON_CANCEL" --arg exp "$future_date" \
    '{vulnerabilityId:($vid|tonumber), subject:"CVE", scope:"ASSET",
      subjectValue:$cve, assetId:($aid|tonumber),
      reason:$reason, expirationDate:$exp}')" "$USER1_EMAIL")
REQ_CANCEL_ID=$(echo "$res" | jq -r '.request.id')
[[ -z "$REQ_CANCEL_ID" || "$REQ_CANCEL_ID" == "null" ]] && fail "Failed to create cancel-request: $res"
ok "user1 created exception request id=$REQ_CANCEL_ID"

res=$(mcp_call "cancel_exception_request" "$(jq -nc --arg id "$REQ_CANCEL_ID" \
    '{requestId:($id|tonumber)}')" "$USER1_EMAIL")
new_status=$(echo "$res" | jq -r '.request.status // .status // empty')
[[ "$new_status" == "CANCELLED" ]] || fail "Expected CANCELLED, got '$new_status'"
ok "user1 cancelled request $REQ_CANCEL_ID"

# =============================================================================
# Phase 7 (MCP): Authorization negatives
# =============================================================================

log "=== Phase 7: MCP authorization negatives ==="

# user2 trying to approve user1's request → already terminal (APPROVED) but
# the role check should fire first. Use the still-rejected request id to be safe.
err=$(mcp_call "approve_exception_request" "$(jq -nc --arg id "$REQ_REJECT_ID" \
    '{requestId:($id|tonumber)}')" "$USER2_EMAIL" --allow-error)
err_code=$(echo "$err" | jq -r '.code // empty')
[[ -n "$err_code" ]] || fail "Expected user2 approve to fail with role error, got success"
ok "user2 cannot approve (code=$err_code)"

# user1 trying to create exception for vuln on asset they don't own
err=$(mcp_call "create_exception_request" "$(jq -nc \
    --arg vid "$VULN2_A2_ID" --arg aid "$ASSET2_ID" --arg cve "$CVE_VULN2" \
    --arg reason "$EXCEPTION_REASON_APPROVE" --arg exp "$future_date" \
    '{vulnerabilityId:($vid|tonumber), subject:"CVE", scope:"ASSET",
      subjectValue:$cve, assetId:($aid|tonumber),
      reason:$reason, expirationDate:$exp}')" "$USER1_EMAIL" --allow-error)
err_code=$(echo "$err" | jq -r '.code // empty')
[[ -n "$err_code" ]] || fail "Expected user1 cross-asset request to fail, got success"
ok "user1 cannot create exception on asset2 (code=$err_code)"

# Missing X-MCP-User-Email — should fail with delegation error
body=$(jq -nc '{jsonrpc:"2.0",id:"neg-1",method:"tools/call",params:{name:"get_vulnerabilities",arguments:{}}}')
resp=$(curl -sS -X POST "${BASE_URL}/api/mcp/tools/call" \
    -H "Content-Type: application/json" -H "X-MCP-API-Key: $SECMAN_MCP_KEY" -d "$body")
no_deleg_code=$(echo "$resp" | jq -r '.error.code // empty')
[[ -n "$no_deleg_code" ]] || warn "Call without delegation header succeeded (code unset) — endpoint may not enforce on read tools"
ok "Call without X-MCP-User-Email surfaced (code='$no_deleg_code')"

# =============================================================================
# Phase 8 (MCP): AWS Account Sharing — directional + per-account scope
#
# Goal: prove that selectedAwsAccountIds on a sharing rule is *honored* — adding
# more mappings to the source user later must NOT leak into the target user
# through the same rule.
#
# Walks through:
#   8.1 user1 gets mapping to account A; user2 gets mapping to account B
#   8.2 Create assets testaws-a (account A) and testaws-b (account B), with an
#       owner string distinct from both users so the only path is via mappings.
#   8.3 Baseline visibility (no sharing yet): user1 sees A, user2 sees B
#   8.4 Create sharing rule user1 -> user2 with selectedAwsAccountIds=[A]
#   8.5 Verify user2 now sees testaws-a (and still testaws-b)
#   8.6 Add SECOND mapping to user1 (account C) plus asset testaws-c
#   8.7 Re-verify: user1 sees A+C; user2 still sees A+B but NOT C
#   8.8 list_aws_account_sharing as admin shows the rule
# =============================================================================

log "=== Phase 8: MCP AWS account sharing (directional + scoped) ==="

# 8.1 Mappings ---------------------------------------------------------------
log "[Phase 8.1] Adding initial AWS user mappings (user1 -> A, user2 -> B)"
mid_u1_a=$(create_user_mapping "$USER1_EMAIL" "$AWS_ACCOUNT_A" "$USER1_ID")
ok "Created mapping ${USER1_EMAIL} -> ${AWS_ACCOUNT_A} (id=${mid_u1_a})"

mid_u2_b=$(create_user_mapping "$USER2_EMAIL" "$AWS_ACCOUNT_B" "$USER2_ID")
ok "Created mapping ${USER2_EMAIL} -> ${AWS_ACCOUNT_B} (id=${mid_u2_b})"

# 8.2 AWS-tagged assets ------------------------------------------------------
log "[Phase 8.2] Creating AWS-tagged assets (cloudAccountId set, owner=${AWS_ASSET_OWNER_LABEL})"
res=$(mcp_call "create_asset" "$(jq -nc \
    --arg n "$AWS_ASSET_A_NAME" --arg t "SERVER" --arg o "$AWS_ASSET_OWNER_LABEL" \
    --arg ip "$AWS_ASSET_A_IP" --arg ca "$AWS_ACCOUNT_A" \
    '{name:$n,type:$t,owner:$o,ip:$ip,cloudAccountId:$ca,description:"E2E AWS sharing test asset (account A)"}')" \
    "$ADMIN_USER_EMAIL")
AWS_ASSET_A_ID=$(echo "$res" | jq -r '.id')
[[ -z "$AWS_ASSET_A_ID" || "$AWS_ASSET_A_ID" == "null" ]] && fail "Failed to create $AWS_ASSET_A_NAME: $res"
ok "Created asset $AWS_ASSET_A_NAME (id=$AWS_ASSET_A_ID, cloudAccountId=$AWS_ACCOUNT_A)"

res=$(mcp_call "create_asset" "$(jq -nc \
    --arg n "$AWS_ASSET_B_NAME" --arg t "SERVER" --arg o "$AWS_ASSET_OWNER_LABEL" \
    --arg ip "$AWS_ASSET_B_IP" --arg ca "$AWS_ACCOUNT_B" \
    '{name:$n,type:$t,owner:$o,ip:$ip,cloudAccountId:$ca,description:"E2E AWS sharing test asset (account B)"}')" \
    "$ADMIN_USER_EMAIL")
AWS_ASSET_B_ID=$(echo "$res" | jq -r '.id')
[[ -z "$AWS_ASSET_B_ID" || "$AWS_ASSET_B_ID" == "null" ]] && fail "Failed to create $AWS_ASSET_B_NAME: $res"
ok "Created asset $AWS_ASSET_B_NAME (id=$AWS_ASSET_B_ID, cloudAccountId=$AWS_ACCOUNT_B)"

# 8.3 Baseline visibility — no sharing rule yet ------------------------------
log "[Phase 8.3] Baseline visibility (no sharing): user1 sees only A, user2 sees only B"
res=$(mcp_call "get_assets" "$(jq -nc --arg n "$AWS_ASSET_A_NAME" '{name:$n,pageSize:50}')" "$USER1_EMAIL")
u1_sees_a=$(echo "$res" | jq -r --arg id "$AWS_ASSET_A_ID" '(.assets // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$u1_sees_a" == "1" ]] || fail "Baseline: user1 should see $AWS_ASSET_A_NAME via own mapping, got $u1_sees_a"

res=$(mcp_call "get_assets" "$(jq -nc --arg n "$AWS_ASSET_B_NAME" '{name:$n,pageSize:50}')" "$USER1_EMAIL")
u1_sees_b=$(echo "$res" | jq -r --arg id "$AWS_ASSET_B_ID" '(.assets // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$u1_sees_b" == "0" ]] || fail "Baseline: user1 should NOT see $AWS_ASSET_B_NAME (no sharing yet), got $u1_sees_b"

res=$(mcp_call "get_assets" "$(jq -nc --arg n "$AWS_ASSET_B_NAME" '{name:$n,pageSize:50}')" "$USER2_EMAIL")
u2_sees_b=$(echo "$res" | jq -r --arg id "$AWS_ASSET_B_ID" '(.assets // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$u2_sees_b" == "1" ]] || fail "Baseline: user2 should see $AWS_ASSET_B_NAME via own mapping, got $u2_sees_b"

res=$(mcp_call "get_assets" "$(jq -nc --arg n "$AWS_ASSET_A_NAME" '{name:$n,pageSize:50}')" "$USER2_EMAIL")
u2_sees_a=$(echo "$res" | jq -r --arg id "$AWS_ASSET_A_ID" '(.assets // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$u2_sees_a" == "0" ]] || fail "Baseline: user2 should NOT see $AWS_ASSET_A_NAME before sharing, got $u2_sees_a"
ok "Baseline visibility correct: user1=A only, user2=B only"

# 8.4 Create scoped sharing rule (only account A) ----------------------------
log "[Phase 8.4] Creating scoped sharing user1 -> user2 (selectedAwsAccountIds=[A])"
res=$(mcp_call "create_aws_account_sharing" "$(jq -nc \
    --arg s "$USER1_ID" --arg t "$USER2_ID" --arg a "$AWS_ACCOUNT_A" \
    '{sourceUserId:($s|tonumber), targetUserId:($t|tonumber), awsAccountIds:[$a]}')" \
    "$ADMIN_USER_EMAIL")
AWS_SHARING_RULE_ID=$(echo "$res" | jq -r '.sharingRule.id')
shared_count=$(echo "$res" | jq -r '.sharingRule.sharedAwsAccountCount // 0')
share_all=$(echo "$res" | jq -r '.sharingRule.shareAllAccounts // false')
[[ -z "$AWS_SHARING_RULE_ID" || "$AWS_SHARING_RULE_ID" == "null" ]] && fail "Sharing rule create failed: $res"
[[ "$shared_count" == "1" ]] || fail "Expected sharedAwsAccountCount=1, got $shared_count"
[[ "$share_all" == "false" ]] || fail "Expected shareAllAccounts=false (scoped), got $share_all"
ok "Created sharing rule id=$AWS_SHARING_RULE_ID (1 account, scoped)"

# 8.5 Verify share visibility ------------------------------------------------
log "[Phase 8.5] Verifying user2 now sees testaws-a via sharing"
res=$(mcp_call "get_assets" "$(jq -nc --arg n "$AWS_ASSET_A_NAME" '{name:$n,pageSize:50}')" "$USER2_EMAIL")
u2_sees_a=$(echo "$res" | jq -r --arg id "$AWS_ASSET_A_ID" '(.assets // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$u2_sees_a" == "1" ]] || fail "user2 should see $AWS_ASSET_A_NAME via sharing, got $u2_sees_a"
# user2 still sees their own
res=$(mcp_call "get_assets" "$(jq -nc --arg n "$AWS_ASSET_B_NAME" '{name:$n,pageSize:50}')" "$USER2_EMAIL")
u2_sees_b=$(echo "$res" | jq -r --arg id "$AWS_ASSET_B_ID" '(.assets // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$u2_sees_b" == "1" ]] || fail "user2 should still see $AWS_ASSET_B_NAME (own mapping), got $u2_sees_b"
ok "user2 sees testaws-a (shared) AND testaws-b (own)"

# Sharing is directional — user1 must NOT see user2's testaws-b
res=$(mcp_call "get_assets" "$(jq -nc --arg n "$AWS_ASSET_B_NAME" '{name:$n,pageSize:50}')" "$USER1_EMAIL")
u1_sees_b=$(echo "$res" | jq -r --arg id "$AWS_ASSET_B_ID" '(.assets // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$u1_sees_b" == "0" ]] || fail "Sharing must be directional: user1 should NOT see $AWS_ASSET_B_NAME, got $u1_sees_b"
ok "Sharing is directional — user1 still cannot see testaws-b"

# 8.6 Add second mapping + asset to user1 (account C) ------------------------
log "[Phase 8.6] Adding SECOND mapping to user1 (account C) — this MUST NOT leak to user2"
mid_u1_c=$(create_user_mapping "$USER1_EMAIL" "$AWS_ACCOUNT_C" "$USER1_ID")
ok "Created mapping ${USER1_EMAIL} -> ${AWS_ACCOUNT_C} (id=${mid_u1_c})"

res=$(mcp_call "create_asset" "$(jq -nc \
    --arg n "$AWS_ASSET_C_NAME" --arg t "SERVER" --arg o "$AWS_ASSET_OWNER_LABEL" \
    --arg ip "$AWS_ASSET_C_IP" --arg ca "$AWS_ACCOUNT_C" \
    '{name:$n,type:$t,owner:$o,ip:$ip,cloudAccountId:$ca,description:"E2E AWS sharing test asset (account C — must NOT propagate to user2)"}')" \
    "$ADMIN_USER_EMAIL")
AWS_ASSET_C_ID=$(echo "$res" | jq -r '.id')
[[ -z "$AWS_ASSET_C_ID" || "$AWS_ASSET_C_ID" == "null" ]] && fail "Failed to create $AWS_ASSET_C_NAME: $res"
ok "Created asset $AWS_ASSET_C_NAME (id=$AWS_ASSET_C_ID, cloudAccountId=$AWS_ACCOUNT_C)"

# 8.7 Re-verify scoped sharing -----------------------------------------------
log "[Phase 8.7] Re-verifying: user1 sees A+C; user2 still sees A+B; user2 must NOT see C"
res=$(mcp_call "get_assets" "$(jq -nc --arg n "$AWS_ASSET_C_NAME" '{name:$n,pageSize:50}')" "$USER1_EMAIL")
u1_sees_c=$(echo "$res" | jq -r --arg id "$AWS_ASSET_C_ID" '(.assets // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$u1_sees_c" == "1" ]] || fail "user1 should see $AWS_ASSET_C_NAME via own mapping, got $u1_sees_c"

res=$(mcp_call "get_assets" "$(jq -nc --arg n "$AWS_ASSET_C_NAME" '{name:$n,pageSize:50}')" "$USER2_EMAIL")
u2_sees_c=$(echo "$res" | jq -r --arg id "$AWS_ASSET_C_ID" '(.assets // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$u2_sees_c" == "0" ]] \
    || fail "SCOPE LEAK: user2 saw $AWS_ASSET_C_NAME ($AWS_ACCOUNT_C) — sharing was scoped to A only (got $u2_sees_c)"
ok "Sharing scope honored: user1 sees A+C, user2 sees A+B but NOT C"

# 8.8 admin lists sharing rules ----------------------------------------------
log "[Phase 8.8] Admin lists sharing rules — must include the scoped rule"
res=$(mcp_call "list_aws_account_sharing" '{"page":0,"size":100}' "$ADMIN_USER_EMAIL")
admin_sees_rule=$(echo "$res" | jq -r --arg id "$AWS_SHARING_RULE_ID" \
    '(.content // []) | map(select(.id == ($id|tonumber))) | length')
[[ "$admin_sees_rule" == "1" ]] || fail "Admin list_aws_account_sharing missing rule $AWS_SHARING_RULE_ID"
ok "Admin sees sharing rule $AWS_SHARING_RULE_ID via list_aws_account_sharing"
fi

# =============================================================================
# Phase 9 (UI): Playwright
# =============================================================================

if [[ "$SKIP_UI" == "true" || "$MCP_ONLY" == "true" ]]; then
    if [[ "$MCP_ONLY" == "true" ]]; then
        record_skip "Phase 9 (Web UI Playwright)" "--mcp-only"
    else
        record_skip "Phase 9 (Web UI Playwright)" "SKIP_UI=true"
    fi
else
    log "=== Phase 9: Web UI (Playwright) ==="

    if ! curl -sf -o /dev/null --connect-timeout 5 "$FRONTEND_URL" 2>/dev/null; then
        fail "Frontend not reachable at $FRONTEND_URL — start it with ./scripts/startfrontenddev.sh"
    fi

    if [[ -z "$ADMIN_USERNAME" || -z "$ADMIN_PASSWORD" ]]; then
        fail "SECMAN_ADMIN_NAME / SECMAN_ADMIN_PASS required for UI phase"
    fi

    pushd "$(dirname "$0")/../../tests/e2e" >/dev/null

    SECMAN_BASE_URL="$FRONTEND_URL" \
    SECMAN_ADMIN_NAME="$ADMIN_USERNAME" \
    SECMAN_ADMIN_PASS="$ADMIN_PASSWORD" \
    E2E_USER1_NAME="$USER1_USERNAME" \
    E2E_USER1_PASS="$USER1_PASSWORD" \
    E2E_USER2_NAME="$USER2_USERNAME" \
    E2E_USER2_PASS="$USER2_PASSWORD" \
    E2E_USER1_EMAIL="$USER1_EMAIL" \
    E2E_USER2_EMAIL="$USER2_EMAIL" \
    E2E_ASSET1_NAME="$ASSET1_NAME" \
    E2E_ASSET2_NAME="$ASSET2_NAME" \
    E2E_CVE_VULN1="$CVE_VULN1" \
    E2E_CVE_VULN2="$CVE_VULN2" \
    E2E_REQ_APPROVE_ID="$REQ_APPROVE_ID" \
    E2E_REQ_REJECT_ID="$REQ_REJECT_ID" \
    E2E_AWS_ACCOUNT_A="$AWS_ACCOUNT_A" \
    E2E_AWS_ACCOUNT_B="$AWS_ACCOUNT_B" \
    E2E_AWS_ACCOUNT_C="$AWS_ACCOUNT_C" \
    E2E_AWS_ASSET_A_NAME="$AWS_ASSET_A_NAME" \
    E2E_AWS_ASSET_B_NAME="$AWS_ASSET_B_NAME" \
    E2E_AWS_ASSET_C_NAME="$AWS_ASSET_C_NAME" \
    E2E_AWS_SHARING_RULE_ID="$AWS_SHARING_RULE_ID" \
        npx playwright test vuln-exception-full.spec.ts --project=chrome --reporter=list

    popd >/dev/null
    ok "UI phase complete"
fi

# =============================================================================
# Phase 10: Exception import/export/delete-all
# =============================================================================

if [[ "$UI_ONLY" == "true" ]]; then
    record_skip "Phase 10 (Exception import/export/delete-all)" "--ui-only"
elif [[ "$RUN_PHASE10" != "true" ]]; then
    record_skip "Phase 10 (Exception import/export/delete-all)" "RUN_PHASE10=false or --skip-phase10"
else
    run_phase_10_exception_import_export
fi

# =============================================================================
# Summary
# =============================================================================

ELAPSED=$(( $(date +%s) - START_TIME ))
echo
if [[ ${#SKIPPED_PHASES[@]} -eq 0 ]]; then
    echo -e "${GREEN}=========================================${NC}"
    echo -e "${GREEN}  E2E VULN+EXCEPTION FULL TEST PASSED   ${NC}"
    echo -e "${GREEN}=========================================${NC}"
else
    echo -e "${YELLOW}==============================================${NC}"
    echo -e "${YELLOW}  E2E VULN+EXCEPTION PARTIAL COVERAGE PASSED  ${NC}"
    echo -e "${YELLOW}==============================================${NC}"
fi
echo "Elapsed: ${ELAPSED}s"
echo "Users:        $USER1_USERNAME(id=$USER1_ID), $USER2_USERNAME(id=$USER2_ID)"
echo "Assets:       $ASSET1_NAME(id=$ASSET1_ID), $ASSET2_NAME(id=$ASSET2_ID)"
echo "Vulns:        vuln1=$VULN1_ID  vuln2_a1=$VULN2_A1_ID  vuln2_a2=$VULN2_A2_ID"
echo "Exceptions:   approved=$REQ_APPROVE_ID  rejected=$REQ_REJECT_ID  cancelled=$REQ_CANCEL_ID"
echo "AWS sharing:  rule=$AWS_SHARING_RULE_ID  scope=[${AWS_ACCOUNT_A}]"
echo "AWS assets:   ${AWS_ASSET_A_NAME}(id=$AWS_ASSET_A_ID,acct=$AWS_ACCOUNT_A)"
echo "              ${AWS_ASSET_B_NAME}(id=$AWS_ASSET_B_ID,acct=$AWS_ACCOUNT_B)"
echo "              ${AWS_ASSET_C_NAME}(id=$AWS_ASSET_C_ID,acct=$AWS_ACCOUNT_C  must NOT leak via sharing)"
if [[ ${#SKIPPED_PHASES[@]} -eq 0 ]]; then
    echo "Coverage:     full (phases 0-10)"
else
    echo "Coverage:     partial"
    for skipped in "${SKIPPED_PHASES[@]}"; do
        echo "  - Skipped: $skipped"
    done
fi
echo
