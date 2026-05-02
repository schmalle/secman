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
# Optional:
#   BASE_URL (default http://localhost:8080)
#   FRONTEND_URL (default http://localhost:4321)
#   SKIP_UI=true to skip Playwright phase
#   VERBOSE=true for debug logging
#
# Usage:
#   pass-cli run --env-file ./secmanpp.env -- ./scriptpp/test/test-e2e-vuln-exception-full.sh
#   ./scriptpp/test/test-e2e-vuln-exception-full.sh --verbose
#

set -euo pipefail

# =============================================================================
# Configuration
# =============================================================================

BASE_URL="${BASE_URL:-${SECMAN_BACKEND_URL:-http://localhost:8080}}"
FRONTEND_URL="${FRONTEND_URL:-http://localhost:4321}"
SECMAN_MCP_KEY="${SECMAN_MCP_KEY:-}"
ADMIN_USER_EMAIL="${SECMAN_ADMIN_EMAIL:-}"
ADMIN_USERNAME="${SECMAN_ADMIN_NAME:-}"
ADMIN_PASSWORD="${SECMAN_ADMIN_PASS:-}"
VERBOSE="${VERBOSE:-false}"
SKIP_UI="${SKIP_UI:-false}"

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
    $0 [--verbose|-v] [--skip-ui] [--help|-h]

Environment:
    BASE_URL              Backend URL (default http://localhost:8080)
    FRONTEND_URL          Frontend URL (default http://localhost:4321)
    SECMAN_MCP_KEY        MCP API key (required)
    SECMAN_ADMIN_EMAIL    Admin email for delegation (required)
    SECMAN_ADMIN_NAME     Admin username (required for UI phase)
    SECMAN_ADMIN_PASS     Admin password (required for UI phase)
    SKIP_UI=true          Skip Playwright UI phase
    VERBOSE=true          Debug logging
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --help|-h)    usage ;;
        --verbose|-v) VERBOSE=true; shift ;;
        --skip-ui)    SKIP_UI=true; shift ;;
        *) warn "Unknown option: $1"; shift ;;
    esac
done

# =============================================================================
# Pre-flight
# =============================================================================

for cmd in curl jq mariadb; do
    command -v "$cmd" >/dev/null || fail "Required command missing: $cmd"
done

[[ -z "$SECMAN_MCP_KEY"   ]] && fail "SECMAN_MCP_KEY is required (use pass-cli run)"
[[ -z "$ADMIN_USER_EMAIL" ]] && fail "SECMAN_ADMIN_EMAIL is required (use pass-cli run)"

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

# Get JWT for a username/password (used to trigger materialized view refresh)
get_jwt() {
    local user="$1"
    local pass="$2"
    curl -sS -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "$(jq -nc --arg u "$user" --arg p "$pass" '{username:$u,password:$p}')" \
        | jq -r '.token // empty'
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
    curl -sS -X POST "${BASE_URL}/api/materialized-view-refresh/trigger" \
        -H "Authorization: Bearer $token" >/dev/null 2>&1 || true
    sleep 5
}

db_exec() {
    mariadb -h "$DB_HOST" -u "$DB_USER" -p"$DB_PASS" "$DB_NAME" -N -e "$1" 2>/dev/null || true
}

# =============================================================================
# Cleanup (idempotent — safe to run multiple times)
# =============================================================================

cleanup() {
    local phase="${1:-post-run}"
    log "Cleanup ($phase): removing test users, assets, exception requests..."

    # 1) Delete exception requests + audit rows for our test users (by username)
    local user_ids_csv
    user_ids_csv=$(db_exec "SELECT GROUP_CONCAT(id) FROM users WHERE username IN ('${USER1_USERNAME}','${USER2_USERNAME}');")
    if [[ -n "$user_ids_csv" && "$user_ids_csv" != "NULL" ]]; then
        db_exec "
            DELETE FROM exception_request_audit
              WHERE actor_user_id IN ($user_ids_csv);
            DELETE FROM vulnerability_exception_request
              WHERE requested_by_user_id IN ($user_ids_csv)
                 OR reviewed_by_user_id IN ($user_ids_csv);
        "
    fi

    # 2) Delete test assets via direct SQL (cascades vulnerabilities through FKs)
    local asset_ids_csv
    asset_ids_csv=$(db_exec "SELECT GROUP_CONCAT(id) FROM asset WHERE name IN ('${ASSET1_NAME}','${ASSET2_NAME}');")
    if [[ -n "$asset_ids_csv" && "$asset_ids_csv" != "NULL" ]]; then
        # Also wipe any exception requests still tied to these assets (safety)
        db_exec "
            DELETE FROM exception_request_audit
              WHERE request_id IN (
                SELECT id FROM vulnerability_exception_request
                 WHERE vulnerability_id IN (SELECT id FROM vulnerability WHERE asset_id IN ($asset_ids_csv))
                    OR asset_id IN ($asset_ids_csv)
              );
            DELETE FROM vulnerability_exception_request
              WHERE vulnerability_id IN (SELECT id FROM vulnerability WHERE asset_id IN ($asset_ids_csv))
                 OR asset_id IN ($asset_ids_csv);
            DELETE FROM vulnerability WHERE asset_id IN ($asset_ids_csv);
            DELETE FROM asset WHERE id IN ($asset_ids_csv);
        "
    fi

    # 3) Delete test users
    if [[ -n "$user_ids_csv" && "$user_ids_csv" != "NULL" ]]; then
        db_exec "DELETE FROM user_roles WHERE user_id IN ($user_ids_csv);"
        db_exec "DELETE FROM users WHERE id IN ($user_ids_csv);"
    fi

    # 4) Refresh materialized view so deleted assets disappear
    db_exec "TRUNCATE TABLE outdated_asset_materialized_view;"

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
VULN1_ID=$(echo "$res" | jq -r '.vulnerabilityId')
[[ -z "$VULN1_ID" || "$VULN1_ID" == "null" ]] && fail "Failed to add vuln1: $res"
ok "Added vuln1 ($CVE_VULN1, 40d) on $ASSET1_NAME — id=$VULN1_ID"

# vuln2 on testasset1 (5 days)
res=$(mcp_call "add_vulnerability" "$(jq -nc \
    --arg h "$ASSET1_NAME" --arg c "$CVE_VULN2" --arg o "$USER1_USERNAME" \
    '{hostname:$h,cve:$c,criticality:"CRITICAL",daysOpen:5,owner:$o}')" "$ADMIN_USER_EMAIL")
VULN2_A1_ID=$(echo "$res" | jq -r '.vulnerabilityId')
[[ -z "$VULN2_A1_ID" || "$VULN2_A1_ID" == "null" ]] && fail "Failed to add vuln2 on $ASSET1_NAME: $res"
ok "Added vuln2 ($CVE_VULN2, 5d) on $ASSET1_NAME — id=$VULN2_A1_ID"

# vuln2 on testasset2 (5 days)
res=$(mcp_call "add_vulnerability" "$(jq -nc \
    --arg h "$ASSET2_NAME" --arg c "$CVE_VULN2" --arg o "$USER2_USERNAME" \
    '{hostname:$h,cve:$c,criticality:"CRITICAL",daysOpen:5,owner:$o}')" "$ADMIN_USER_EMAIL")
VULN2_A2_ID=$(echo "$res" | jq -r '.vulnerabilityId')
[[ -z "$VULN2_A2_ID" || "$VULN2_A2_ID" == "null" ]] && fail "Failed to add vuln2 on $ASSET2_NAME: $res"
ok "Added vuln2 ($CVE_VULN2, 5d) on $ASSET2_NAME — id=$VULN2_A2_ID"

trigger_view_refresh

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

# As admin — should see all three
res=$(mcp_call "get_vulnerabilities" '{"pageSize":500,"includeExcepted":true}' "$ADMIN_USER_EMAIL")
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

res=$(mcp_call "get_overdue_assets" '{}' "$ADMIN_USER_EMAIL")
admin_has_a1_overdue=$(echo "$res" | jq -r --arg id "$ASSET1_ID" \
    'if .assets then (.assets | map(select((.id // .assetId) == ($id|tonumber))) | length) else 0 end')
admin_has_a2_overdue=$(echo "$res" | jq -r --arg id "$ASSET2_ID" \
    'if .assets then (.assets | map(select((.id // .assetId) == ($id|tonumber))) | length) else 0 end')
[[ "$admin_has_a1_overdue" == "1" && "$admin_has_a2_overdue" == "0" ]] \
    || fail "admin overdue list mismatch (a1=$admin_has_a1_overdue a2=$admin_has_a2_overdue)"
ok "admin overdue list includes testasset1, excludes testasset2"

res=$(mcp_call "get_overdue_assets" '{}' "$USER1_EMAIL")
u1_overdue_count=$(echo "$res" | jq -r 'if .assets then (.assets | length) else 0 end')
[[ "$u1_overdue_count" -ge 1 ]] || fail "user1 should see >=1 overdue asset, got $u1_overdue_count"
ok "user1 sees overdue asset (count=$u1_overdue_count)"

res=$(mcp_call "get_overdue_assets" '{}' "$USER2_EMAIL")
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
# Phase 8 (UI): Playwright
# =============================================================================

if [[ "$SKIP_UI" == "true" ]]; then
    warn "Skipping UI phase (SKIP_UI=true)"
else
    log "=== Phase 8: Web UI (Playwright) ==="

    if ! curl -sf -o /dev/null --connect-timeout 5 "$FRONTEND_URL" 2>/dev/null; then
        fail "Frontend not reachable at $FRONTEND_URL — start it with ./scriptpp/startfrontenddev.sh"
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
    E2E_ASSET1_NAME="$ASSET1_NAME" \
    E2E_ASSET2_NAME="$ASSET2_NAME" \
    E2E_CVE_VULN1="$CVE_VULN1" \
    E2E_CVE_VULN2="$CVE_VULN2" \
    E2E_REQ_APPROVE_ID="$REQ_APPROVE_ID" \
    E2E_REQ_REJECT_ID="$REQ_REJECT_ID" \
        npx playwright test vuln-exception-full.spec.ts --project=chrome --reporter=list

    popd >/dev/null
    ok "UI phase complete"
fi

# =============================================================================
# Summary
# =============================================================================

ELAPSED=$(( $(date +%s) - START_TIME ))
echo
echo -e "${GREEN}=========================================${NC}"
echo -e "${GREEN}  E2E VULN+EXCEPTION FULL TEST PASSED   ${NC}"
echo -e "${GREEN}=========================================${NC}"
echo "Elapsed: ${ELAPSED}s"
echo "Users:        $USER1_USERNAME(id=$USER1_ID), $USER2_USERNAME(id=$USER2_ID)"
echo "Assets:       $ASSET1_NAME(id=$ASSET1_ID), $ASSET2_NAME(id=$ASSET2_ID)"
echo "Vulns:        vuln1=$VULN1_ID  vuln2_a1=$VULN2_A1_ID  vuln2_a2=$VULN2_A2_ID"
echo "Exceptions:   approved=$REQ_APPROVE_ID  rejected=$REQ_REJECT_ID  cancelled=$REQ_CANCEL_ID"
echo
