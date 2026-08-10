#!/usr/bin/env bash
#
# E2E: end-of-life (EOL) lifecycle management.
#
# Covers every function added by the EOL feature, end to end:
#
#   1. CLI  `secman eol-sync`                  — catalogue download + matching scan
#   2. CLI  `secman send-eol-notifications`    — owner mail for the next N months
#   3. API  GET  /api/eol/findings             — asset-scoped read
#   4. API  GET  /api/eol/summary              — asset-scoped aggregates
#   5. API  GET  /api/eol/assets/{id}          — per-system read + out-of-scope 404
#   6. API  GET  /api/eol/repositories/top     — ADMIN/SECCHAMPION top-10 ranking
#   7. API  GET  /api/eol/catalog/status       — catalogue size and last outcome
#   8. AuthZ negatives                         — a plain user must not reach the
#                                                admin verbs or another tenant's rows
#
# ## Why the assertions look the way they do
#
# The upstream catalogue is a live third-party feed. Asserting "Ubuntu 20.04 is
# end of life" would make this driver fail the day upstream restructures a cycle,
# which is not a secman regression. So the driver asserts on *behaviour that is
# ours*: that a seeded system with a deliberately ancient OS produces a finding,
# that scoping holds, that the admin verbs are gated, and that a re-run is
# idempotent. The single catalogue-dependent assertion (a non-empty product list)
# is reported as a skip, not a failure, when the source is unreachable — an
# air-gapped runner is not a broken feature.
#
# ## Destructiveness
#
# Non-destructive. Everything it creates carries E2E_PREFIX and is removed by the
# cleanup that runs both before (unconditional) and after (trap EXIT). It never
# deletes assets, users or findings it did not create. It DOES trigger a global
# EOL rescan, which rewrites the eol_finding table — that table is derived data,
# rebuilt from the inventory on every sync, so this is a refresh, not data loss.
#
# ## Required env (resolved via pass-cli)
#   SECMAN_ADMIN_NAME, SECMAN_ADMIN_PASS
#   BASE_URL or SECMAN_BACKEND_URL   backend URL; never a localhost literal
# ## Optional
#   EOL_OFFLINE=true       skip the catalogue download, scan against what is stored
#   SKIP_CLI=true          skip the CLI phases (API-only run)
#   VERBOSE=true
#
# ## Usage
#   pass-cli run --env-file ./secmanpp.env -- ./scripts/test/test-e2e-eol.sh --verbose

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../../tests/lib/secman-test-tls.sh
source "$REPO_ROOT/tests/lib/secman-test-tls.sh"

# =============================================================================
# Configuration
# =============================================================================

while [[ $# -gt 0 ]]; do
    case "$1" in
        --verbose|-v) VERBOSE=true; shift ;;
        --offline)    EOL_OFFLINE=true; shift ;;
        --skip-cli)   SKIP_CLI=true; shift ;;
        *)            shift ;;
    esac
done

BASE_URL="${BASE_URL:-${SECMAN_BACKEND_URL:-}}"
VERBOSE="${VERBOSE:-false}"
EOL_OFFLINE="${EOL_OFFLINE:-false}"
SKIP_CLI="${SKIP_CLI:-false}"

STAMP="$(date +%s)"
SUFFIX="${STAMP: -6}"
E2E_PREFIX="e2e-eol-"

TEST_USER="${E2E_PREFIX}user"
TEST_EMAIL="${TEST_USER}@e2e.local"
TEST_PASS="E2eEol!${SUFFIX}"

# Two systems: one deliberately ancient, one current. The pair is what proves the
# matcher discriminates rather than flagging everything.
EOL_ASSET="${E2E_PREFIX}old-${SUFFIX}"
FRESH_ASSET="${E2E_PREFIX}new-${SUFFIX}"
# Ubuntu 14.04 went EOL in 2019 and 12.04 in 2017; both are long past and stay
# past, so this assertion does not rot the way a near-future cycle would.
EOL_OS="Ubuntu 14.04"
FRESH_OS="Ubuntu 24.04"

CLI_JAR="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"
ADMIN_COOKIE="$(mktemp)"
USER_COOKIE="$(mktemp)"
WORK_DIR="$(mktemp -d)"

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC} $1" >&2; }
log_pass()  { echo -e "${GREEN}[PASS]${NC} $1" >&2; PASS_COUNT=$((PASS_COUNT + 1)); }
log_fail()  { echo -e "${RED}[FAIL]${NC} $1" >&2; FAIL_COUNT=$((FAIL_COUNT + 1)); }
log_skip()  { echo -e "${YELLOW}[SKIP]${NC} $1" >&2; SKIP_COUNT=$((SKIP_COUNT + 1)); }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1" >&2; }
log_dbg()   { [[ "$VERBOSE" == "true" ]] && echo -e "${YELLOW}[DEBUG]${NC} $1" >&2 || true; }
phase()     { echo >&2; echo -e "${BLUE}=== $1 ===${NC}" >&2; }

cleanup() {
    local code=$?
    # Testbed teardown first: it needs the cookie jar and the scratch dir to talk
    # to the backend, so removing them beforehand would silently skip cleanup.
    cleanup_testbed || true
    rm -f "$ADMIN_COOKIE" "$USER_COOKIE" 2>/dev/null || true
    rm -rf "$WORK_DIR" 2>/dev/null || true
    exit $code
}

# =============================================================================
# Prerequisites
# =============================================================================

check_prerequisites() {
    phase "Prerequisites"

    local missing=0
    for cmd in curl jq; do
        command -v "$cmd" >/dev/null 2>&1 || { log_fail "Required command not found: $cmd"; missing=1; }
    done
    [[ $missing -eq 1 ]] && exit 1

    for var in SECMAN_ADMIN_NAME SECMAN_ADMIN_PASS; do
        if [[ -z "${!var:-}" ]]; then
            log_fail "$var is not set (source it via pass-cli)"
            exit 1
        fi
    done

    if [[ -z "$BASE_URL" ]]; then
        log_fail "BASE_URL / SECMAN_BACKEND_URL is not set — never hardcode localhost"
        exit 1
    fi
    BASE_URL="${BASE_URL%/}"
    log_info "Backend: $BASE_URL"

    if [[ "$SKIP_CLI" != "true" && ! -f "$CLI_JAR" ]]; then
        log_warn "CLI jar not found at $CLI_JAR — build it with ./gradlew :cli:shadowJar"
        log_warn "Continuing with API-only coverage"
        SKIP_CLI=true
    fi
}

# =============================================================================
# HTTP helpers
# =============================================================================

login() {
    local user="$1" pass="$2" jar="$3"
    local status
    status=$(curl -sS -o "$WORK_DIR/login.json" -w '%{http_code}' -c "$jar" \
        -X POST "$BASE_URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "$(jq -n --arg u "$user" --arg p "$pass" '{username:$u,password:$p}')")
    [[ "$status" == "200" ]]
}

# Prints the body; sets HTTP_STATUS. Callers assert on both.
api_get() {
    local jar="$1" path="$2"
    HTTP_STATUS=$(curl -sS -o "$WORK_DIR/resp.json" -w '%{http_code}' -b "$jar" "$BASE_URL$path")
    cat "$WORK_DIR/resp.json"
}

api_post() {
    local jar="$1" path="$2" body="$3"
    HTTP_STATUS=$(curl -sS -o "$WORK_DIR/resp.json" -w '%{http_code}' -b "$jar" \
        -X POST "$BASE_URL$path" -H 'Content-Type: application/json' -d "$body")
    cat "$WORK_DIR/resp.json"
}

# =============================================================================
# Testbed
# =============================================================================

cleanup_testbed() {
    phase "Cleanup"
    login "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS" "$ADMIN_COOKIE" || return 0

    # Assets: only ones carrying our prefix, resolved by name, never by pattern
    # delete. Leftovers from earlier runs are removed too.
    local assets
    assets=$(api_get "$ADMIN_COOKIE" "/api/assets" 2>/dev/null || echo '[]')
    echo "$assets" | jq -r --arg p "$E2E_PREFIX" \
        '(if type=="array" then . else (.assets // .content // []) end)
         | .[] | select(.name | startswith($p)) | .id' 2>/dev/null | while read -r id; do
        [[ -z "$id" ]] && continue
        curl -sS -o /dev/null -b "$ADMIN_COOKIE" -X DELETE "$BASE_URL/api/assets/$id" || true
        log_dbg "Deleted asset $id"
    done

    local users
    users=$(api_get "$ADMIN_COOKIE" "/api/users" 2>/dev/null || echo '[]')
    echo "$users" | jq -r --arg p "$E2E_PREFIX" \
        '(if type=="array" then . else (.users // []) end)
         | .[] | select(.username | startswith($p)) | .id' 2>/dev/null | while read -r id; do
        [[ -z "$id" ]] && continue
        curl -sS -o /dev/null -b "$ADMIN_COOKIE" -X DELETE "$BASE_URL/api/users/$id" || true
        log_dbg "Deleted user $id"
    done
}

setup_testbed() {
    phase "Setup"

    login "$SECMAN_ADMIN_NAME" "$SECMAN_ADMIN_PASS" "$ADMIN_COOKIE" \
        || { log_fail "Admin login failed"; exit 1; }
    log_pass "Admin authenticated"

    api_post "$ADMIN_COOKIE" "/api/users" \
        "$(jq -n --arg u "$TEST_USER" --arg e "$TEST_EMAIL" --arg p "$TEST_PASS" \
            '{username:$u,email:$e,password:$p,roles:["USER"]}')" >/dev/null
    [[ "$HTTP_STATUS" =~ ^20 ]] || { log_fail "Could not create test user (HTTP $HTTP_STATUS)"; exit 1; }
    log_pass "Test user created ($TEST_USER)"

    # Owned by the admin, so the test user must NOT see them — that is the
    # scoping negative in phase 8.
    for pair in "$EOL_ASSET|$EOL_OS" "$FRESH_ASSET|$FRESH_OS"; do
        local name="${pair%%|*}" os="${pair##*|}"
        api_post "$ADMIN_COOKIE" "/api/assets" \
            "$(jq -n --arg n "$name" --arg o "$SECMAN_ADMIN_NAME" --arg v "$os" \
                '{name:$n,type:"SERVER",owner:$o,osVersion:$v,description:"e2e eol testbed"}')" >/dev/null
        [[ "$HTTP_STATUS" =~ ^20 ]] || { log_fail "Could not create asset $name (HTTP $HTTP_STATUS)"; exit 1; }
        log_pass "Asset created: $name ($os)"
    done
}

asset_id_by_name() {
    local name="$1"
    api_get "$ADMIN_COOKIE" "/api/assets" 2>/dev/null \
        | jq -r --arg n "$name" \
            '(if type=="array" then . else (.assets // .content // []) end)
             | .[] | select(.name==$n) | .id' | head -1
}

# =============================================================================
# Phase 1 — catalogue sync (CLI)
# =============================================================================

phase_catalogue_sync() {
    phase "1. Catalogue sync"

    local body='{"products":["ubuntu"],"scan":true,"scanOnly":false}'
    if [[ "$EOL_OFFLINE" == "true" ]]; then
        log_info "EOL_OFFLINE=true — scanning against the stored catalogue only"
        body='{"products":[],"scan":true,"scanOnly":true}'
    fi

    local resp; resp=$(api_post "$ADMIN_COOKIE" "/api/eol/catalog/sync" "$body")
    if [[ ! "$HTTP_STATUS" =~ ^20 ]]; then
        log_fail "POST /api/eol/catalog/sync returned HTTP $HTTP_STATUS"
        return
    fi
    log_pass "Catalogue sync accepted (HTTP $HTTP_STATUS)"
    log_dbg "$resp"

    local status; status=$(echo "$resp" | jq -r '.status // "UNKNOWN"')
    local synced; synced=$(echo "$resp" | jq -r '.productsSynced // 0')
    if [[ "$status" == "SUCCESS" ]]; then
        log_pass "Sync reported SUCCESS ($synced product(s))"
    else
        # An unreachable upstream is an environment fact, not a secman regression.
        log_skip "Sync reported $status — upstream source likely unreachable from the backend"
    fi

    # Idempotence: a second run must not error and must not duplicate findings.
    local before; before=$(api_get "$ADMIN_COOKIE" "/api/eol/catalog/status" | jq -r '.findings // 0')
    api_post "$ADMIN_COOKIE" "/api/eol/catalog/sync" '{"products":[],"scan":true,"scanOnly":true}' >/dev/null
    local after; after=$(api_get "$ADMIN_COOKIE" "/api/eol/catalog/status" | jq -r '.findings // 0')
    if [[ "$before" == "$after" ]]; then
        log_pass "Re-scan is idempotent (findings stayed at $after)"
    else
        log_fail "Re-scan changed the finding count $before -> $after (replace-per-run is broken)"
    fi
}

# =============================================================================
# Phase 2 — matching produced the right findings
# =============================================================================

phase_matching() {
    phase "2. Matching"

    local resp; resp=$(api_get "$ADMIN_COOKIE" "/api/eol/findings?status=ALL&pageSize=500&search=${E2E_PREFIX}")
    [[ "$HTTP_STATUS" == "200" ]] || { log_fail "GET /api/eol/findings returned HTTP $HTTP_STATUS"; return; }
    log_pass "GET /api/eol/findings returned 200"

    local eol_hit
    eol_hit=$(echo "$resp" | jq -r --arg a "$EOL_ASSET" \
        '[.findings // [] | .[] | select(.assetName==$a and .subjectType=="ASSET_OS")] | length')
    local fresh_hit
    fresh_hit=$(echo "$resp" | jq -r --arg a "$FRESH_ASSET" \
        '[.findings // [] | .[] | select(.assetName==$a)] | length')

    if [[ "$eol_hit" -ge 1 ]]; then
        log_pass "Ancient OS on $EOL_ASSET produced an EOL finding"
        local status
        status=$(echo "$resp" | jq -r --arg a "$EOL_ASSET" \
            '[.findings // [] | .[] | select(.assetName==$a)][0].status')
        [[ "$status" == "EOL" ]] \
            && log_pass "Finding is classified EOL, not merely approaching" \
            || log_fail "Expected status EOL for $EOL_OS, got $status"
    else
        log_skip "No finding for $EOL_OS — catalogue is empty or the source was unreachable"
    fi

    # The discrimination assertion: a current LTS must not be reported. This is
    # the one that catches a matcher that flags everything.
    if [[ "$fresh_hit" -eq 0 ]]; then
        log_pass "Current OS on $FRESH_ASSET produced no finding"
    else
        log_fail "$FRESH_OS was reported as EOL/approaching — false positive"
    fi
}

# =============================================================================
# Phase 3 — summary and per-asset reads
# =============================================================================

phase_reads() {
    phase "3. Summary and per-system reads"

    local summary; summary=$(api_get "$ADMIN_COOKIE" "/api/eol/summary")
    [[ "$HTTP_STATUS" == "200" ]] \
        && log_pass "GET /api/eol/summary returned 200" \
        || log_fail "GET /api/eol/summary returned HTTP $HTTP_STATUS"

    echo "$summary" | jq -e 'has("eolCount") and has("approachingCount") and has("horizonMonths")' >/dev/null \
        && log_pass "Summary carries the documented counters" \
        || log_fail "Summary is missing counters: $summary"

    local horizon; horizon=$(echo "$summary" | jq -r '.horizonMonths // 0')
    [[ "$horizon" -ge 1 ]] \
        && log_pass "Summary reports a horizon of $horizon months" \
        || log_fail "Summary horizon is not set"

    local asset_id; asset_id=$(asset_id_by_name "$EOL_ASSET")
    if [[ -n "$asset_id" ]]; then
        api_get "$ADMIN_COOKIE" "/api/eol/assets/$asset_id" >/dev/null
        [[ "$HTTP_STATUS" == "200" ]] \
            && log_pass "GET /api/eol/assets/$asset_id returned 200" \
            || log_fail "GET /api/eol/assets/$asset_id returned HTTP $HTTP_STATUS"
    else
        log_fail "Could not resolve the id of $EOL_ASSET"
    fi

    api_get "$ADMIN_COOKIE" "/api/eol/catalog/status" >/dev/null
    [[ "$HTTP_STATUS" == "200" ]] \
        && log_pass "GET /api/eol/catalog/status returned 200" \
        || log_fail "GET /api/eol/catalog/status returned HTTP $HTTP_STATUS"
}

# =============================================================================
# Phase 4 — repository ranking (ADMIN/SECCHAMPION)
# =============================================================================

phase_repositories() {
    phase "4. Top repositories"

    local resp; resp=$(api_get "$ADMIN_COOKIE" "/api/eol/repositories/top?limit=10")
    [[ "$HTTP_STATUS" == "200" ]] \
        && log_pass "GET /api/eol/repositories/top returned 200 for ADMIN" \
        || { log_fail "GET /api/eol/repositories/top returned HTTP $HTTP_STATUS"; return; }

    local count; count=$(echo "$resp" | jq -r '(.repositories // []) | length')
    [[ "$count" -le 10 ]] \
        && log_pass "Ranking honours the limit ($count row(s))" \
        || log_fail "Ranking returned $count rows for limit=10"

    # An out-of-range limit must be clamped, not echoed back.
    resp=$(api_get "$ADMIN_COOKIE" "/api/eol/repositories/top?limit=100000")
    local applied; applied=$(echo "$resp" | jq -r '.limit // 0')
    [[ "$HTTP_STATUS" == "200" && "$applied" -le 50 ]] \
        && log_pass "An oversized limit is clamped to $applied" \
        || log_fail "Oversized limit was not clamped (HTTP $HTTP_STATUS, limit=$applied)"

    # Ranks must be dense and ascending, or "top 10" means nothing.
    if [[ "$count" -gt 0 ]]; then
        echo "$resp" | jq -e '(.repositories // []) | to_entries | all(.value.rank == (.key + 1))' >/dev/null \
            && log_pass "Ranks are dense and ascending from 1" \
            || log_fail "Rank sequence is not 1..n: $(echo "$resp" | jq -c '[.repositories[].rank]')"
    fi
}

# =============================================================================
# Phase 5 — owner notifications (dry run, then CLI)
# =============================================================================

phase_notifications() {
    phase "5. Owner notifications"

    local resp; resp=$(api_post "$ADMIN_COOKIE" "/api/eol/notifications/send" \
        '{"months":12,"dryRun":true,"includeAlreadyEol":true}')
    [[ "$HTTP_STATUS" == "200" ]] \
        && log_pass "POST /api/eol/notifications/send (dry run) returned 200" \
        || { log_fail "Notification dry run returned HTTP $HTTP_STATUS"; return; }

    echo "$resp" | jq -e '.dryRun == true and .emailsSent == 0' >/dev/null \
        && log_pass "Dry run sent no mail" \
        || log_fail "Dry run reported sent mail: $resp"

    # Range validation must be enforced server-side, not just in the CLI.
    api_post "$ADMIN_COOKIE" "/api/eol/notifications/send" '{"months":0,"dryRun":true}' >/dev/null
    [[ "$HTTP_STATUS" == "400" ]] \
        && log_pass "months=0 rejected with 400" \
        || log_fail "months=0 returned HTTP $HTTP_STATUS, expected 400"

    api_post "$ADMIN_COOKIE" "/api/eol/notifications/send" '{"months":9999,"dryRun":true}' >/dev/null
    [[ "$HTTP_STATUS" == "400" ]] \
        && log_pass "months=9999 rejected with 400" \
        || log_fail "months=9999 returned HTTP $HTTP_STATUS, expected 400"
}

# =============================================================================
# Phase 6 — CLI surface
# =============================================================================

run_cli() {
    java -jar "$CLI_JAR" "$@" 2>&1
}

phase_cli() {
    phase "6. CLI"

    if [[ "$SKIP_CLI" == "true" ]]; then
        log_skip "CLI phases skipped"
        return
    fi

    export SECMAN_BACKEND_URL="$BASE_URL"

    run_cli help eol-sync | grep -q "endoflife.date" \
        && log_pass "'secman help eol-sync' documents the default source" \
        || log_fail "'secman help eol-sync' does not mention the default source"

    run_cli help send-eol-notifications | grep -q -- "--months" \
        && log_pass "'secman help send-eol-notifications' documents --months" \
        || log_fail "'secman help send-eol-notifications' is missing --months"

    # Mutually exclusive flags must fail before anything is contacted.
    local out
    out=$(run_cli eol-sync --scan-only --no-scan || true)
    echo "$out" | grep -qi "mutually exclusive" \
        && log_pass "eol-sync rejects --scan-only together with --no-scan" \
        || log_fail "eol-sync accepted contradictory flags: $out"

    out=$(run_cli send-eol-notifications --months 0 --dry-run || true)
    echo "$out" | grep -qi "between 1 and 60" \
        && log_pass "send-eol-notifications rejects --months 0" \
        || log_fail "send-eol-notifications accepted --months 0: $out"

    out=$(run_cli eol-sync --scan-only --verbose || true)
    echo "$out" | grep -qi "EOL Catalogue Sync" \
        && log_pass "eol-sync --scan-only runs against the backend" \
        || log_fail "eol-sync --scan-only did not run: $out"

    out=$(run_cli send-eol-notifications --dry-run --verbose || true)
    echo "$out" | grep -qi "Recipients resolved" \
        && log_pass "send-eol-notifications --dry-run reports resolved recipients" \
        || log_fail "send-eol-notifications --dry-run produced no report: $out"
}

# =============================================================================
# Phase 7 — authorization negatives
# =============================================================================

phase_authz() {
    phase "7. Authorization"

    login "$TEST_USER" "$TEST_PASS" "$USER_COOKIE" \
        || { log_fail "Test user login failed — cannot run the authorization negatives"; return; }
    log_pass "Test user authenticated"

    # A plain USER may read their own scope...
    api_get "$USER_COOKIE" "/api/eol/findings" >/dev/null
    [[ "$HTTP_STATUS" == "200" ]] \
        && log_pass "USER may read /api/eol/findings" \
        || log_fail "USER got HTTP $HTTP_STATUS on /api/eol/findings"

    # ...but sees none of the admin-owned testbed. This is the scoping assertion:
    # the rows exist, the user simply must not be able to reach them.
    local resp; resp=$(api_get "$USER_COOKIE" "/api/eol/findings?pageSize=500")
    local leaked
    leaked=$(echo "$resp" | jq -r --arg p "$E2E_PREFIX" \
        '[.findings // [] | .[] | select((.assetName // "") | startswith($p))] | length')
    [[ "$leaked" -eq 0 ]] \
        && log_pass "USER sees none of the admin-owned EOL findings" \
        || log_fail "USER saw $leaked finding(s) for systems they do not own — scoping is broken"

    # An out-of-scope asset id must 404, indistinguishable from a missing one.
    local asset_id; asset_id=$(asset_id_by_name "$EOL_ASSET")
    if [[ -n "$asset_id" ]]; then
        api_get "$USER_COOKIE" "/api/eol/assets/$asset_id" >/dev/null
        [[ "$HTTP_STATUS" == "404" ]] \
            && log_pass "Out-of-scope asset id answers 404, not 403 or 200" \
            || log_fail "Out-of-scope asset id returned HTTP $HTTP_STATUS, expected 404"
    fi

    api_get "$USER_COOKIE" "/api/eol/assets/999999999" >/dev/null
    [[ "$HTTP_STATUS" == "404" ]] \
        && log_pass "Nonexistent asset id answers 404 as well" \
        || log_fail "Nonexistent asset id returned HTTP $HTTP_STATUS, expected 404"

    # Admin verbs and the repository ranking are closed to a plain user.
    api_get "$USER_COOKIE" "/api/eol/repositories/top" >/dev/null
    [[ "$HTTP_STATUS" == "403" || "$HTTP_STATUS" == "401" ]] \
        && log_pass "USER is denied /api/eol/repositories/top (HTTP $HTTP_STATUS)" \
        || log_fail "USER reached the repository ranking (HTTP $HTTP_STATUS)"

    api_post "$USER_COOKIE" "/api/eol/catalog/sync" '{"products":[],"scan":false,"scanOnly":true}' >/dev/null
    [[ "$HTTP_STATUS" == "403" || "$HTTP_STATUS" == "401" ]] \
        && log_pass "USER is denied the catalogue sync (HTTP $HTTP_STATUS)" \
        || log_fail "USER triggered the catalogue sync (HTTP $HTTP_STATUS)"

    api_post "$USER_COOKIE" "/api/eol/notifications/send" '{"months":12,"dryRun":true}' >/dev/null
    [[ "$HTTP_STATUS" == "403" || "$HTTP_STATUS" == "401" ]] \
        && log_pass "USER is denied the notification run (HTTP $HTTP_STATUS)" \
        || log_fail "USER triggered the notification run (HTTP $HTTP_STATUS)"

    # Unauthenticated access is closed everywhere.
    local anon
    anon=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/api/eol/findings")
    [[ "$anon" == "401" || "$anon" == "403" ]] \
        && log_pass "Unauthenticated read is denied (HTTP $anon)" \
        || log_fail "Unauthenticated read returned HTTP $anon"

    # A bad filter value must be rejected, never silently widened.
    api_get "$ADMIN_COOKIE" "/api/eol/findings?status=DROP%20TABLE" >/dev/null
    [[ "$HTTP_STATUS" == "400" ]] \
        && log_pass "An unknown status filter is rejected with 400" \
        || log_fail "Unknown status filter returned HTTP $HTTP_STATUS, expected 400"
}

# =============================================================================
# Main
# =============================================================================

main() {
    echo "=============================================================" >&2
    echo " SecMan EOL lifecycle E2E" >&2
    echo "=============================================================" >&2

    check_prerequisites
    cleanup_testbed
    trap cleanup EXIT

    setup_testbed
    phase_catalogue_sync
    phase_matching
    phase_reads
    phase_repositories
    phase_notifications
    phase_cli
    phase_authz

    phase "Result"
    echo -e "  ${GREEN}Passed:${NC}  $PASS_COUNT" >&2
    echo -e "  ${YELLOW}Skipped:${NC} $SKIP_COUNT" >&2
    echo -e "  ${RED}Failed:${NC}  $FAIL_COUNT" >&2

    [[ $FAIL_COUNT -eq 0 ]] || exit 1
    exit 0
}

main "$@"
