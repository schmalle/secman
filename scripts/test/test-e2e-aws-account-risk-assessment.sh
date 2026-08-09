#!/usr/bin/env bash
#
# E2E: auto risk assessment for newly discovered AWS accounts (CLI + MCP)
#
# Verifies that importing an AWS account mapping for an account SecMan has never
# seen starts a risk assessment for that account's owner, measured against the
# current version of the security requirements (the ACTIVE release) and scoped by
# a use case tag.
#
# Testbed (all names carry E2E_PREFIX so cleanup is exact):
#   Users:        <prefix>champion (SECCHAMPION), <prefix>owner (USER)
#   Use case:     <prefix>usecase
#   Requirements: 2 tagged with that use case, + 1 untagged (must NOT appear)
#   Release:      version 99.<epoch-suffix>.0, set ACTIVE (snapshots the corpus)
#   Accounts:     six never-before-seen 12-digit IDs, one per phase
#
# What it proves:
#   1. Without --start-risk-assessment nothing is started — the opt-in default
#   2. CLI import starts an assessment pinned to the ACTIVE release
#   3. The questionnaire is exactly the release's use-case-tagged requirements
#   4. Importing MORE requirements does not change that questionnaire (pinning)
#   5. Re-importing is idempotent, and the skip is reported as a skip — not as a
#      failure, and not with a non-zero exit code
#   6. MCP import_user_mappings does the same over the streamable HTTP transport
#   7. Negatives: no ACTIVE release -> rejected; deadline outside 1..3650 ->
#      rejected on both surfaces; non-admin MCP -> ADMIN_REQUIRED
#
# Cleanup runs both before (unconditional) and after (trap EXIT), and sweeps every
# earlier run's leftovers too — it matches on the test owner-email prefix, not on
# this run's timestamped account ids.
#
# Required env (resolved via pass-cli):
#   SECMAN_ADMIN_NAME
#   SECMAN_ADMIN_PASS
#   SECMAN_ADMIN_EMAIL
#   SECMAN_MCP_KEY
# Additional env:
#   BASE_URL or SECMAN_BACKEND_URL (backend URL; never a localhost literal)
#   SKIP_CLI=true    skip the CLI phases (MCP only)
#   SKIP_MCP=true    skip the MCP phases (CLI only)
#   VERBOSE=true     debug logging
#
# Usage:
#   pass-cli run --env-file ./secmanpp.env -- ./scripts/test/test-e2e-aws-account-risk-assessment.sh
#   ./scripts/test/test-e2e-aws-account-risk-assessment.sh --verbose
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../../tests/lib/secman-test-tls.sh
source "$REPO_ROOT/tests/lib/secman-test-tls.sh"

# =============================================================================
# Configuration
# =============================================================================

for arg in "$@"; do
    case "$arg" in
        --verbose|-v) VERBOSE=true ;;
        --skip-cli)   SKIP_CLI=true ;;
        --skip-mcp)   SKIP_MCP=true ;;
    esac
done

BASE_URL="${BASE_URL:-${SECMAN_BACKEND_URL:-}}"
VERBOSE="${VERBOSE:-false}"
SKIP_CLI="${SKIP_CLI:-false}"
SKIP_MCP="${SKIP_MCP:-false}"

STAMP="$(date +%s)"
SUFFIX="${STAMP: -6}"
E2E_PREFIX="e2e-awsra-"

CHAMPION_USER="${E2E_PREFIX}champion"
CHAMPION_EMAIL="${CHAMPION_USER}@e2e.local"
OWNER_USER="${E2E_PREFIX}owner"
OWNER_EMAIL="${OWNER_USER}@e2e.local"
TEST_PASS="E2eAwsRa!${SUFFIX}"

USECASE_NAME="${E2E_PREFIX}usecase"
RELEASE_VERSION="99.${SUFFIX}.0"
RELEASE_NAME="${E2E_PREFIX}release"

# Synthetic accounts, one per phase. All share the shape 88[4-9] + 6-digit timestamp +
# "000", which is what the cleanup sweep matches on — keep any new account in that shape.
CLI_ACCOUNT="886${SUFFIX}000"
MCP_ACCOUNT="887${SUFFIX}000"
NO_FLAG_ACCOUNT="885${SUFFIX}000"   # default-is-off phase
DEADLINE_ACCOUNT="884${SUFFIX}000"  # deadline-out-of-range phase
MCP_NEG_ACCOUNT="888${SUFFIX}000"   # MCP negative (missing use case)
NO_RELEASE_ACCOUNT="889${SUFFIX}000" # no-ACTIVE-release negative

CLI_JAR="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"
COOKIE_JAR="$(mktemp)"
WORK_DIR="$(mktemp -d)"

PASS_COUNT=0
FAIL_COUNT=0

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC} $1" >&2; }
log_pass()  { echo -e "${GREEN}[PASS]${NC} $1" >&2; PASS_COUNT=$((PASS_COUNT + 1)); }
log_fail()  { echo -e "${RED}[FAIL]${NC} $1" >&2; FAIL_COUNT=$((FAIL_COUNT + 1)); }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1" >&2; }
log_dbg()   { [[ "$VERBOSE" == "true" ]] && echo -e "${YELLOW}[DEBUG]${NC} $1" >&2 || true; }
phase()     { echo >&2; echo -e "${BLUE}=== $1 ===${NC}" >&2; }

# =============================================================================
# Prerequisites
# =============================================================================

check_prerequisites() {
    phase "Prerequisites"

    local missing=0
    for cmd in curl jq java; do
        if ! command -v "$cmd" >/dev/null 2>&1; then
            log_fail "Required command not found: $cmd"
            missing=1
        fi
    done
    [[ $missing -eq 1 ]] && exit 1

    for var in SECMAN_ADMIN_NAME SECMAN_ADMIN_PASS SECMAN_ADMIN_EMAIL; do
        if [[ -z "${!var:-}" ]]; then
            log_fail "$var is not set (source it via pass-cli)"
            exit 1
        fi
    done

    if [[ -z "$BASE_URL" ]]; then
        log_fail "BASE_URL / SECMAN_BACKEND_URL is not set — never hardcode localhost"
        exit 1
    fi

    if [[ "$SKIP_MCP" != "true" && -z "${SECMAN_MCP_KEY:-}" ]]; then
        log_warn "SECMAN_MCP_KEY not set — skipping the MCP phases"
        SKIP_MCP=true
    fi

    if [[ "$SKIP_CLI" != "true" && ! -f "$CLI_JAR" ]]; then
        log_info "CLI jar missing, building it (./gradlew :cli:shadowJar) …"
        (cd "$REPO_ROOT" && ./gradlew :cli:shadowJar -q) || {
            log_fail "CLI jar build failed"
            exit 1
        }
    fi

    log_info "Backend: $BASE_URL"
    log_pass "Prerequisites satisfied"
}

# =============================================================================
# HTTP helpers (REST, cookie auth)
# =============================================================================

admin_login() {
    local status
    status=$(curl -sS -o /dev/null -w '%{http_code}' -c "$COOKIE_JAR" \
        -H 'Content-Type: application/json' \
        -X POST "${BASE_URL}/api/auth/login" \
        --data "$(jq -nc --arg u "$SECMAN_ADMIN_NAME" --arg p "$SECMAN_ADMIN_PASS" \
            '{username:$u, password:$p}')")
    if [[ "$status" != "200" ]]; then
        log_fail "Admin login failed (HTTP $status)"
        exit 1
    fi
    log_dbg "Admin logged in"
}

# api <METHOD> <path> [json-body] -> body on stdout, HTTP code in API_STATUS
API_STATUS=""
api() {
    local method="$1" path="$2" body="${3:-}"
    local out; out="$(mktemp)"
    local args=(-sS -o "$out" -w '%{http_code}' -b "$COOKIE_JAR" -X "$method" "${BASE_URL}${path}")
    if [[ -n "$body" ]]; then
        args+=(-H 'Content-Type: application/json' --data "$body")
    fi
    API_STATUS="$(curl "${args[@]}")"
    cat "$out"
    rm -f "$out"
}

# =============================================================================
# MCP helper (streamable HTTP transport — the path the permission fix restored)
# =============================================================================

# mcp_call <tool> <json-args> [delegated-email] -> raw JSON-RPC response
mcp_call() {
    local tool="$1" args="$2" delegated="${3:-$SECMAN_ADMIN_EMAIL}"
    local body
    body=$(jq -nc --arg tool "$tool" --argjson args "$args" --arg id "e2e-${RANDOM}" \
        '{jsonrpc:"2.0", id:$id, method:"tools/call", params:{name:$tool, arguments:$args}}')
    log_dbg "MCP -> $tool as ${delegated}: $args"
    local resp
    resp=$(curl -sS -X POST "${BASE_URL}/mcp" \
        -H 'Content-Type: application/json' \
        -H "X-MCP-API-Key: ${SECMAN_MCP_KEY}" \
        -H "X-MCP-User-Email: ${delegated}" \
        --data "$body")
    log_dbg "MCP <- $resp"
    echo "$resp"
}

# The tool payload is JSON-encoded inside result.content[0].text.
mcp_payload() {
    echo "$1" | jq -c '.result.content[0].text | fromjson? // {}'
}

# =============================================================================
# Cleanup
# =============================================================================

cleanup() {
    local phase_label="${1:-post-run}"
    log_info "Cleanup ($phase_label) …"

    # Assessments first (FK: aws_account_risk_assessment -> risk_assessment, and the asset
    # sweep below is refused while a risk assessment still references the asset).
    #
    # Matched on the OWNER EMAIL the service writes into the notes
    # ("... (owner: e2e-awsra-owner@e2e.local, use case: ...)"), never on the account id.
    # The account ids carry a per-run timestamp, so an id match would miss every earlier run;
    # a loose `contains("886")` — what this used to do — would match a *real* assessment for
    # any genuine AWS account whose id contains 886 and delete it. The owner prefix is both
    # stable across runs and impossible for real data to collide with.
    local tracked
    tracked=$(api GET "/api/risk-assessments" || echo '[]')
    echo "$tracked" | jq -r --arg p "owner: ${E2E_PREFIX}" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select((.notes // "") | contains($p)) | .id' 2>/dev/null \
        | while read -r ra_id; do
            [[ -n "$ra_id" ]] && api DELETE "/api/risk-assessments/${ra_id}" >/dev/null || true
        done

    # User mappings — matched on the test owner email prefix rather than this run's two
    # account ids, so mappings orphaned by an interrupted earlier run are swept up too.
    local mappings
    mappings=$(api GET "/api/user-mappings/current?size=1000" || echo '{}')
    echo "$mappings" | jq -r --arg p "$E2E_PREFIX" \
        '(.content // .mappings // [])[]? | select((.email // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r m_id; do
            [[ -n "$m_id" ]] && api DELETE "/api/user-mappings/${m_id}" >/dev/null || true
        done

    # The AWS_ACCOUNT assets the feature auto-creates as the assessment basis
    # ("AWS Account <id>"). Nothing else in this driver creates them and nothing else
    # removed them, so before this sweep every run left one behind for good.
    #
    # Doubly constrained, because an asset can only be matched through the account id:
    # the id must have the exact synthetic shape this driver mints (88[6-9] + 6 digits +
    # "000") AND the owner must be a test address. A real account satisfies at most one.
    local assets
    assets=$(api GET "/api/assets" || echo '[]')
    echo "$assets" | jq -r --arg p "$E2E_PREFIX" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select(.type == "AWS_ACCOUNT")
         | select((.cloudAccountId // "") | test("^88[4-9][0-9]{6}000$"))
         | select((.owner // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r a_id; do
            [[ -n "$a_id" ]] && api DELETE "/api/assets/${a_id}" >/dev/null || true
        done

    # Release (also drops its requirement snapshots).
    #
    # Matched by NAME ($RELEASE_NAME, constant across runs), never by this run's own
    # $RELEASE_VERSION (a fresh timestamp each run). A version-only match can only ever
    # catch the release THIS run just created, so any release orphaned by an earlier
    # interrupted run (different version, same name) would never be swept up, and the
    # e2e-awsra-release rows would pile up release over release exactly like the
    # e2e-awsmail-release pile this cleanup pattern was copied from.
    #
    # force=true is required: setup_testbed() always sets its release ACTIVE, and the
    # backend refuses to delete an ACTIVE release unless force=true actually bypasses
    # that guard (see DELETE /api/releases/{id} in ReleaseController.kt).
    local releases
    releases=$(api GET "/api/releases" || echo '[]')
    echo "$releases" | jq -r --arg n "$RELEASE_NAME" \
        '(if type == "array" then . else (.content // []) end)[]? | select(.name == $n) | .id' 2>/dev/null \
        | while read -r r_id; do
            [[ -n "$r_id" ]] || continue
            api DELETE "/api/releases/${r_id}?force=true" >/dev/null 2>&1 || true
            if [[ ! "$API_STATUS" =~ ^2 ]]; then
                log_warn "Could not delete release id=${r_id} (name=${RELEASE_NAME}, HTTP ${API_STATUS}) — left in place"
            fi
        done

    # Requirements, use case, users — all prefix-scoped.
    local reqs
    reqs=$(api GET "/api/requirements" || echo '[]')
    echo "$reqs" | jq -r --arg p "$E2E_PREFIX" \
        '(if type == "array" then . else (.content // []) end)[]? | select((.shortreq // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r q_id; do
            [[ -n "$q_id" ]] && api DELETE "/api/requirements/${q_id}" >/dev/null || true
        done

    local ucs
    ucs=$(api GET "/api/usecases" || echo '[]')
    echo "$ucs" | jq -r --arg n "$USECASE_NAME" \
        '(if type == "array" then . else (.content // []) end)[]? | select(.name == $n) | .id' 2>/dev/null \
        | while read -r u_id; do
            [[ -n "$u_id" ]] && api DELETE "/api/usecases/${u_id}" >/dev/null || true
        done

    local users
    users=$(api GET "/api/users" || echo '[]')
    echo "$users" | jq -r --arg p "$E2E_PREFIX" \
        '(if type == "array" then . else (.content // []) end)[]? | select((.username // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r usr_id; do
            [[ -n "$usr_id" ]] && api DELETE "/api/users/${usr_id}" >/dev/null || true
        done

    log_dbg "Cleanup ($phase_label) done"
}

on_exit() {
    local rc=$?
    cleanup "post-run" || true
    rm -f "$COOKIE_JAR"
    rm -rf "$WORK_DIR"
    exit $rc
}

# =============================================================================
# Setup
# =============================================================================

USECASE_ID=""
RELEASE_ID=""
TAGGED_REQ_COUNT=2

setup_testbed() {
    phase "Setup: users, use case, requirements, ACTIVE release"

    api POST "/api/users" "$(jq -nc --arg u "$CHAMPION_USER" --arg e "$CHAMPION_EMAIL" --arg p "$TEST_PASS" \
        '{username:$u, email:$e, password:$p, roles:["SECCHAMPION"]}')" >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not create SECCHAMPION user (HTTP $API_STATUS)"; exit 1; }

    api POST "/api/users" "$(jq -nc --arg u "$OWNER_USER" --arg e "$OWNER_EMAIL" --arg p "$TEST_PASS" \
        '{username:$u, email:$e, password:$p, roles:["USER"]}')" >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not create owner user (HTTP $API_STATUS)"; exit 1; }
    log_pass "Users created: $CHAMPION_USER (SECCHAMPION), $OWNER_USER (USER)"

    local uc
    uc=$(api POST "/api/usecases" "$(jq -nc --arg n "$USECASE_NAME" '{name:$n}')")
    USECASE_ID=$(echo "$uc" | jq -r '.id // empty')
    [[ -n "$USECASE_ID" ]] || { log_fail "Could not create use case (HTTP $API_STATUS): $uc"; exit 1; }
    log_pass "Use case created: $USECASE_NAME (id=$USECASE_ID)"

    # Two requirements tagged with the use case, one untagged control.
    local i
    for i in 1 2; do
        api POST "/api/requirements" "$(jq -nc --arg s "${E2E_PREFIX}tagged-req-${i}" --argjson u "[$USECASE_ID]" \
            '{shortreq:$s, details:"E2E tagged requirement", chapter:"1", usecaseIds:$u}')" >/dev/null
        [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not create tagged requirement $i"; exit 1; }
    done
    api POST "/api/requirements" "$(jq -nc --arg s "${E2E_PREFIX}untagged-req" \
        '{shortreq:$s, details:"E2E untagged control requirement", chapter:"1"}')" >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not create untagged requirement"; exit 1; }
    log_pass "Requirements created: $TAGGED_REQ_COUNT tagged + 1 untagged control"

    # Creating a release snapshots the whole corpus; ACTIVE makes it THE standard.
    local rel
    rel=$(api POST "/api/releases" "$(jq -nc --arg v "$RELEASE_VERSION" --arg n "$RELEASE_NAME" \
        '{version:$v, name:$n, description:"E2E AWS account risk assessment baseline"}')")
    RELEASE_ID=$(echo "$rel" | jq -r '.id // empty')
    [[ -n "$RELEASE_ID" ]] || { log_fail "Could not create release (HTTP $API_STATUS): $rel"; exit 1; }

    api PUT "/api/releases/${RELEASE_ID}/status" '{"status":"ACTIVE"}' >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not activate release (HTTP $API_STATUS)"; exit 1; }
    log_pass "Release $RELEASE_VERSION created and set ACTIVE (id=$RELEASE_ID)"
}

# =============================================================================
# Assertions
# =============================================================================

# find_tracked <account> -> the tracking row JSON (via the MCP read tool), or {}
find_tracked() {
    local account="$1"
    local resp
    resp=$(mcp_call "list_aws_account_risk_assessments" "$(jq -nc --arg a "$account" '{awsAccountId:$a}')")
    mcp_payload "$resp" | jq -c --arg a "$account" '.assessments[]? | select(.awsAccountId == $a)' | head -1
}

assert_assessment_pinned() {
    local account="$1" label="$2"
    local row
    row=$(find_tracked "$account")

    if [[ -z "$row" || "$row" == "{}" ]]; then
        log_fail "$label: no tracked risk assessment found for account $account"
        return 1
    fi

    local ra_id release_version use_case respondent status
    ra_id=$(echo "$row" | jq -r '.riskAssessmentId // empty')
    release_version=$(echo "$row" | jq -r '.releaseVersion // empty')
    use_case=$(echo "$row" | jq -r '.useCase // empty')
    respondent=$(echo "$row" | jq -r '.respondent // empty')
    status=$(echo "$row" | jq -r '.status // empty')

    [[ -n "$ra_id" ]] && log_pass "$label: assessment #$ra_id created" \
        || log_fail "$label: assessment id missing"

    [[ "$release_version" == "$RELEASE_VERSION" ]] \
        && log_pass "$label: pinned to the ACTIVE requirements release $RELEASE_VERSION" \
        || log_fail "$label: expected release $RELEASE_VERSION, got '${release_version}'"

    [[ "$use_case" == "$USECASE_NAME" ]] \
        && log_pass "$label: scoped to use case $USECASE_NAME" \
        || log_fail "$label: expected use case $USECASE_NAME, got '${use_case}'"

    [[ "$respondent" == "$OWNER_EMAIL" ]] \
        && log_pass "$label: owner $OWNER_EMAIL is the respondent" \
        || log_fail "$label: expected respondent $OWNER_EMAIL, got '${respondent}'"

    [[ "$status" == "STARTED" ]] \
        && log_pass "$label: assessment is open (STARTED)" \
        || log_fail "$label: expected status STARTED, got '${status}'"

    echo "$ra_id"
}

# questionnaire_shortreqs <assessmentId> -> sorted shortreq list
questionnaire_shortreqs() {
    local ra_id="$1"
    api GET "/api/responses/assessment/${ra_id}/authenticated" \
        | jq -r '[.requirements[]?.shortreq] | sort | join(",")'
}

assert_questionnaire_scoped() {
    local ra_id="$1"
    local actual
    actual=$(questionnaire_shortreqs "$ra_id")
    local expected="${E2E_PREFIX}tagged-req-1,${E2E_PREFIX}tagged-req-2"

    if [[ "$actual" == "$expected" ]]; then
        log_pass "Questionnaire is exactly the release's use-case-tagged requirements"
    else
        log_fail "Questionnaire mismatch. expected='$expected' actual='$actual'"
    fi
}

# =============================================================================
# Phase: CLI import
# =============================================================================

CLI_ASSESSMENT_ID=""

run_cli() {
    java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings.csv" \
        --start-risk-assessment \
        --risk-usecase "$USECASE_NAME" \
        --risk-deadline-days 7 \
        --username "$SECMAN_ADMIN_NAME" \
        --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" \
        "$@"
}

phase_cli_import() {
    phase "CLI: import a brand-new AWS account with --start-risk-assessment"

    cat > "$WORK_DIR/mappings.csv" <<EOF
email,type,value
${OWNER_EMAIL},AWS_ACCOUNT,${CLI_ACCOUNT}
EOF

    local out rc
    set +e
    out=$(run_cli 2>&1); rc=$?
    set -e
    log_dbg "$out"

    if [[ $rc -eq 0 ]]; then
        log_pass "CLI import exited 0"
    else
        log_fail "CLI import exited $rc"
        echo "$out" >&2
        return
    fi

    if echo "$out" | grep -q "requirements ${RELEASE_VERSION}"; then
        log_pass "CLI output names the pinned requirements version"
    else
        log_fail "CLI output does not name the pinned requirements version"
    fi

    CLI_ASSESSMENT_ID=$(assert_assessment_pinned "$CLI_ACCOUNT" "CLI" | tail -1)
    [[ -n "$CLI_ASSESSMENT_ID" ]] && assert_questionnaire_scoped "$CLI_ASSESSMENT_ID"
}

phase_pinning_is_stable() {
    phase "Pinning: importing more requirements must not change the questionnaire"

    [[ -z "$CLI_ASSESSMENT_ID" ]] && { log_warn "No CLI assessment — skipping"; return; }

    local before after
    before=$(questionnaire_shortreqs "$CLI_ASSESSMENT_ID")

    api POST "/api/requirements" "$(jq -nc --arg s "${E2E_PREFIX}tagged-req-3-late" --argjson u "[$USECASE_ID]" \
        '{shortreq:$s, details:"Added AFTER the assessment started", chapter:"1", usecaseIds:$u}')" >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not create the late requirement"; return; }

    after=$(questionnaire_shortreqs "$CLI_ASSESSMENT_ID")

    if [[ "$before" == "$after" ]]; then
        log_pass "Questionnaire unchanged after a later requirement import (pinning holds)"
    else
        log_fail "Questionnaire drifted after a later import. before='$before' after='$after'"
    fi
}

# tracked_count <account> -> number of tracked assessments for the account.
#
# Prefers the MCP read tool (it reads the tracking table directly). With SKIP_MCP the CLI
# phases still need the count, so it falls back to the REST list and counts the assessments
# whose auto-generated notes name the account — the same string the service writes.
tracked_count() {
    local account="$1"
    if [[ "$SKIP_MCP" != "true" ]]; then
        mcp_payload "$(mcp_call "list_aws_account_risk_assessments" \
            "$(jq -nc --arg a "$account" '{awsAccountId:$a}')")" | jq -r '.count // 0'
        return
    fi
    api GET "/api/risk-assessments" \
        | jq -r --arg n "for account ${account} " \
            '[(if type == "array" then . else (.content // []) end)[]?
              | select((.notes // "") | contains($n))] | length'
}

# delete_mappings_for <account> — remove every user_mapping row naming the account,
# which is what makes the account look brand-new to the next import again.
delete_mappings_for() {
    local account="$1"
    api GET "/api/user-mappings/current?size=1000" \
        | jq -r --arg a "$account" '(.content // .mappings // [])[]? | select(.awsAccountId == $a) | .id' \
        | while read -r m_id; do
            [[ -n "$m_id" ]] && api DELETE "/api/user-mappings/${m_id}" >/dev/null || true
        done
}

phase_idempotency() {
    phase "Idempotency: re-importing the same account starts no second assessment"

    # Part 1 — the plain re-import. The account is no longer new (its mapping exists), so
    # detection finds nothing and the assessment starter is never even reached.
    local out rc
    set +e
    out=$(run_cli 2>&1); rc=$?
    set -e
    log_dbg "$out"

    [[ $rc -eq 0 ]] && log_pass "Plain re-import exited 0" || log_fail "Plain re-import exited $rc"

    local count
    count=$(tracked_count "$CLI_ACCOUNT")
    if [[ "$count" == "1" ]]; then
        log_pass "Still exactly one tracked assessment for $CLI_ACCOUNT"
    else
        log_fail "Expected 1 tracked assessment for $CLI_ACCOUNT, found $count"
    fi

    # Part 2 — the path that actually reaches the idempotency guard. Dropping the mapping
    # makes the account brand-new again while its assessment stays open, so the import does
    # get as far as createAssessment and has to skip there.
    #
    # This is the case that used to be reported as a per-account FAILURE and exit 1: the
    # guard returned its message in `error`. A skip is a no-op, not a failure — the operator
    # must see a successful run.
    delete_mappings_for "$CLI_ACCOUNT"

    set +e
    out=$(run_cli 2>&1); rc=$?
    set -e
    log_dbg "$out"

    if [[ $rc -eq 0 ]]; then
        log_pass "Re-import over an open assessment exited 0 (a skip is not a failure)"
    else
        log_fail "Re-import over an open assessment exited $rc — a skip must not fail the run"
        echo "$out" >&2
    fi

    if echo "$out" | grep -qi "skipped"; then
        log_pass "Re-import reported the open assessment as skipped"
    else
        log_fail "Re-import did not report a skip: $out"
    fi

    if echo "$out" | grep -q "❌"; then
        log_fail "Re-import rendered the skip as a failure (❌)"
    else
        log_pass "The skip is not rendered as a failure"
    fi

    count=$(tracked_count "$CLI_ACCOUNT")
    if [[ "$count" == "1" ]]; then
        log_pass "Still exactly one tracked assessment after the skip"
    else
        log_fail "Expected 1 tracked assessment after the skip, found $count"
    fi
}

phase_default_is_off() {
    phase "Default: without --start-risk-assessment nothing is started"

    cat > "$WORK_DIR/mappings-noflag.csv" <<EOF
email,type,value
${OWNER_EMAIL},AWS_ACCOUNT,${NO_FLAG_ACCOUNT}
EOF

    local out rc
    set +e
    out=$(java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings-noflag.csv" \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" 2>&1); rc=$?
    set -e
    log_dbg "$out"

    [[ $rc -eq 0 ]] && log_pass "Import without the flag exited 0" \
        || { log_fail "Import without the flag exited $rc"; echo "$out" >&2; return; }

    # The mapping must be imported — the flag governs the assessment, not the import.
    local mapped
    mapped=$(api GET "/api/user-mappings/current?size=1000" \
        | jq -r --arg a "$NO_FLAG_ACCOUNT" \
            '[(.content // .mappings // [])[]? | select(.awsAccountId == $a)] | length')
    [[ "$mapped" == "1" ]] && log_pass "The mapping itself was imported" \
        || log_fail "Expected the mapping to be imported, found $mapped row(s)"

    # …and nothing else may have happened.
    local count
    count=$(tracked_count "$NO_FLAG_ACCOUNT")
    [[ "$count" == "0" ]] && log_pass "No risk assessment started (opt-in default holds)" \
        || log_fail "Expected 0 assessments for $NO_FLAG_ACCOUNT, found $count"

    # Not even the AWS_ACCOUNT basis asset, which only the assessment path creates.
    local assets
    assets=$(api GET "/api/assets" \
        | jq -r --arg a "$NO_FLAG_ACCOUNT" \
            '[(if type == "array" then . else (.content // []) end)[]?
              | select((.cloudAccountId // "") == $a)] | length')
    [[ "$assets" == "0" ]] && log_pass "No AWS_ACCOUNT asset created either" \
        || log_fail "Expected no asset for $NO_FLAG_ACCOUNT, found $assets"

    # A use case without the enabling flag is a no-op, and the CLI must say so rather than
    # let the operator believe assessments ran.
    set +e
    out=$(java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings-noflag.csv" \
        --risk-usecase "$USECASE_NAME" \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" 2>&1); rc=$?
    set -e
    log_dbg "$out"

    if echo "$out" | grep -qi "risk-usecase is ignored"; then
        log_pass "--risk-usecase without --start-risk-assessment warns that it is ignored"
    else
        log_fail "No warning for --risk-usecase without --start-risk-assessment: $out"
    fi
}

phase_deadline_bounds() {
    phase "Negative: a deadline outside 1..3650 is rejected before anything is imported"

    cat > "$WORK_DIR/mappings-deadline.csv" <<EOF
email,type,value
${OWNER_EMAIL},AWS_ACCOUNT,${DEADLINE_ACCOUNT}
EOF

    local out rc
    set +e
    out=$(java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings-deadline.csv" \
        --start-risk-assessment --risk-usecase "$USECASE_NAME" \
        --risk-deadline-days 100000 \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" 2>&1); rc=$?
    set -e
    log_dbg "$out"

    [[ $rc -ne 0 ]] && log_pass "CLI refused a 100000-day deadline (exit $rc)" \
        || log_fail "CLI accepted a 100000-day deadline"

    if echo "$out" | grep -qi "at most 3650"; then
        log_pass "The error names the maximum"
    else
        log_fail "The error does not name the maximum: $out"
    fi

    # Fail-fast: the rejected run must not have imported the mapping.
    local mapped
    mapped=$(api GET "/api/user-mappings/current?size=1000" \
        | jq -r --arg a "$DEADLINE_ACCOUNT" \
            '[(.content // .mappings // [])[]? | select(.awsAccountId == $a)] | length')
    [[ "$mapped" == "0" ]] && log_pass "The rejected run imported nothing" \
        || log_fail "The rejected run still imported $mapped mapping(s)"

    # The same bound has to hold on the MCP surface, which does not go through picocli.
    if [[ "$SKIP_MCP" != "true" ]]; then
        local resp payload
        resp=$(mcp_call "import_user_mappings" "$(jq -nc \
            --arg e "$OWNER_EMAIL" --arg a "$DEADLINE_ACCOUNT" --arg uc "$USECASE_NAME" \
            '{mappings:[{email:$e, awsAccountId:$a}],
              startRiskAssessment:true, riskAssessmentUseCase:$uc,
              riskAssessmentDeadlineDays:100000}')")
        payload=$(mcp_payload "$resp")
        if echo "$resp$payload" | grep -qi "at most 3650"; then
            log_pass "MCP rejects the same deadline with the same message"
        else
            log_fail "MCP did not reject a 100000-day deadline: $resp"
        fi
    fi
}

# =============================================================================
# Phase: MCP
# =============================================================================

phase_mcp_import() {
    phase "MCP: import_user_mappings over the streamable HTTP transport"

    local resp payload
    resp=$(mcp_call "import_user_mappings" "$(jq -nc \
        --arg e "$OWNER_EMAIL" --arg a "$MCP_ACCOUNT" --arg uc "$USECASE_NAME" \
        '{mappings:[{email:$e, awsAccountId:$a}],
          startRiskAssessment:true, riskAssessmentUseCase:$uc, riskAssessmentDeadlineDays:7}')")

    if echo "$resp" | jq -e '.error' >/dev/null 2>&1; then
        log_fail "MCP import_user_mappings returned a JSON-RPC error: $(echo "$resp" | jq -c '.error')"
        return
    fi
    log_pass "import_user_mappings reachable over POST /mcp (permission map wired)"

    payload=$(mcp_payload "$resp")
    local created assessments release_version
    created=$(echo "$payload" | jq -r '.created // 0')
    assessments=$(echo "$payload" | jq -r '.riskAssessments | length')
    release_version=$(echo "$payload" | jq -r '.riskAssessments[0].releaseVersion // empty')

    [[ "$created" == "1" ]] && log_pass "MCP created 1 mapping" || log_fail "MCP created=$created, expected 1"
    [[ "$assessments" == "1" ]] && log_pass "MCP started 1 risk assessment" \
        || log_fail "MCP started $assessments assessments, expected 1"
    [[ "$release_version" == "$RELEASE_VERSION" ]] \
        && log_pass "MCP result reports the pinned version $RELEASE_VERSION" \
        || log_fail "MCP result release version '$release_version', expected $RELEASE_VERSION"

    local mcp_ra_id
    mcp_ra_id=$(assert_assessment_pinned "$MCP_ACCOUNT" "MCP" | tail -1)
    [[ -n "$mcp_ra_id" ]] && assert_questionnaire_scoped "$mcp_ra_id"
}

phase_mcp_negatives() {
    phase "MCP negatives"

    local resp payload
    resp=$(mcp_call "list_aws_account_risk_assessments" '{}' "$OWNER_EMAIL")
    payload=$(mcp_payload "$resp")
    if echo "$resp$payload" | grep -q "ADMIN_REQUIRED\|FORBIDDEN\|No permission"; then
        log_pass "Non-admin caller is denied list_aws_account_risk_assessments"
    else
        log_fail "Non-admin caller was NOT denied: $resp"
    fi

    resp=$(mcp_call "import_user_mappings" "$(jq -nc --arg e "$OWNER_EMAIL" --arg a "$MCP_NEG_ACCOUNT" \
        '{mappings:[{email:$e, awsAccountId:$a}], startRiskAssessment:true}')")
    payload=$(mcp_payload "$resp")
    if echo "$resp$payload" | grep -q "riskAssessmentUseCase is required"; then
        log_pass "startRiskAssessment without a use case is rejected"
    else
        log_fail "Missing use case was not rejected: $resp"
    fi
}

phase_no_active_release() {
    phase "Negative: no ACTIVE release must block the import up front"

    # Archive the release by activating nothing else is possible (ACTIVE is terminal),
    # so delete it — that is also the only way to leave the system with none ACTIVE.
    api DELETE "/api/releases/${RELEASE_ID}?force=true" >/dev/null || true
    if [[ ! "$API_STATUS" =~ ^20 ]]; then
        api DELETE "/api/releases/${RELEASE_ID}" >/dev/null || true
    fi

    local still_active
    still_active=$(api GET "/api/releases" \
        | jq -r '[(if type == "array" then . else (.content // []) end)[]? | select(.status == "ACTIVE")] | length')
    if [[ "$still_active" != "0" ]]; then
        log_warn "Another ACTIVE release exists in this environment — skipping the no-release negative"
        return
    fi

    cat > "$WORK_DIR/mappings-neg.csv" <<EOF
email,type,value
${OWNER_EMAIL},AWS_ACCOUNT,${NO_RELEASE_ACCOUNT}
EOF

    local out rc
    set +e
    out=$(java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings-neg.csv" \
        --start-risk-assessment --risk-usecase "$USECASE_NAME" \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" 2>&1); rc=$?
    set -e
    log_dbg "$out"

    if [[ $rc -ne 0 ]]; then
        log_pass "CLI refused the import with no ACTIVE release (exit $rc)"
    else
        log_fail "CLI accepted the import despite no ACTIVE release"
    fi

    if echo "$out" | grep -qi "ACTIVE release"; then
        log_pass "Error message names the missing ACTIVE release"
    else
        log_fail "Error message does not mention the ACTIVE release: $out"
    fi

    # Nothing may have been imported — validation is fail-fast.
    local mapped
    mapped=$(api GET "/api/user-mappings/current?size=1000" \
        | jq -r --arg a "$NO_RELEASE_ACCOUNT" '[(.content // .mappings // [])[]? | select(.awsAccountId == $a)] | length')
    if [[ "$mapped" == "0" ]]; then
        log_pass "No mappings were imported by the rejected run"
    else
        log_fail "The rejected run still imported $mapped mapping(s)"
    fi
}

# =============================================================================
# Main
# =============================================================================

main() {
    echo "============================================================" >&2
    echo " AWS Account Risk Assessment E2E" >&2
    echo "============================================================" >&2

    check_prerequisites
    admin_login
    cleanup "pre-run"
    trap on_exit EXIT

    setup_testbed

    if [[ "$SKIP_CLI" != "true" ]]; then
        phase_default_is_off
        phase_deadline_bounds
        phase_cli_import
        phase_pinning_is_stable
        phase_idempotency
    else
        log_warn "SKIP_CLI=true — CLI phases skipped"
    fi

    if [[ "$SKIP_MCP" != "true" ]]; then
        phase_mcp_import
        phase_mcp_negatives
    else
        log_warn "SKIP_MCP=true — MCP phases skipped"
    fi

    if [[ "$SKIP_CLI" != "true" ]]; then
        phase_no_active_release
    fi

    phase "Summary"
    echo -e "${GREEN}Passed: ${PASS_COUNT}${NC}" >&2
    echo -e "${RED}Failed: ${FAIL_COUNT}${NC}" >&2

    [[ $FAIL_COUNT -eq 0 ]] || exit 1
    exit 0
}

main "$@"
