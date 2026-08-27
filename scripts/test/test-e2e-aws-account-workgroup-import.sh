#!/usr/bin/env bash
#
# E2E: AWS account mapping import -> workgroup linking -> asset access (CLI + REST + MCP)
#
# The feature under test: an AWS account whose display_name is "DevOps-x" belongs to
# the workgroup "aws-DevOps-x", created when missing. Linking an account to a workgroup
# grants every member of that workgroup access to the account's assets — unified asset
# access rule #9. It is an authorization change, not bookkeeping, which is why this
# driver's headline assertion is about visibility rather than about row counts.
#
# Why this exists when the unit tier is already green:
#   WorkgroupAccountLinkServiceTest has 17 tests covering the naming rule, the races,
#   dry-run and every error row — but all of them mock the repositories. Nothing below
#   the HTTP boundary proves the link actually reaches AssetRepository's native rule-#9
#   clause, and CLAUDE.md is explicit that SQL pre-filters are perf hints, never the
#   auth boundary. Phase "rule9" is the only test in the repo that drives that path.
#
# Testbed (every name carries a stable prefix so cleanup is exact and sweeps old runs):
#   Users:        <prefix>viewer (USER, workgroup member, no mapping, no sharing)
#                 <prefix>plain  (USER, no workgroup — authorization negatives)
#   Mappings:     owner emails <prefix>*-owner@e2e.local
#   Accounts:     12-digit synthetic ids 77<6-digit stamp>NNNN
#   Workgroups:   aws-E2eAwswg<stamp><Name>, created by the linker itself
#   Asset:        <prefix>host-rule9, cloudAccountId = the rule-9 account
#
# What it proves: 16 phases across CLI, REST and MCP, enumerated once in
# .claude/skills/aws-account-workgroup-import/SKILL.md §"What is under test".
# Each phase function below carries the reasoning specific to it. The three that
# matter most are phase_rule9_visibility (the only test that reaches the rule-#9
# clause in AssetRepository), phase_unlink_revokes (which is what stops a filter
# that returns everything from looking like a working rule 9) and
# phase_mcp_callable (a tool in LISTING but not CALLING is listed, then denied).
#
# Cleanup runs before (unconditional) and after (trap EXIT). It matches on stable
# name prefixes — never on this run's timestamped ids — so an interrupted earlier
# run is swept too. Never widen a filter to a bare substring: a `contains("77")`
# would delete real data for any genuine AWS account whose id contains those digits.
#
# Required env (resolved via pass-cli):
#   SECMAN_ADMIN_NAME
#   SECMAN_ADMIN_PASS
#   SECMAN_ADMIN_EMAIL
#   SECMAN_MCP_KEY          (MCP phases; absent -> those phases are WARN-skipped)
# Additional env:
#   BASE_URL or SECMAN_BACKEND_URL (backend URL; never a localhost literal)
#   SKIP_CLI=true            skip the CLI phases
#   SKIP_MCP=true            skip the MCP phases
#   SKIP_REST=true           skip the REST-upload phases
#   WITH_SUPPLEMENTARY=true  also run tests/bulk-user-mapping-test.sh and
#                            tests/mcp-e2e-workgroup-test.sh
#   VERBOSE=true             debug logging
#
# Usage:
#   pass-cli run --env-file ./secmanpp.env -- ./scripts/test/test-e2e-aws-account-workgroup-import.sh
#   ./scripts/test/test-e2e-aws-account-workgroup-import.sh --verbose --skip-mcp
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
        --verbose|-v)         VERBOSE=true ;;
        --skip-cli)           SKIP_CLI=true ;;
        --skip-mcp)           SKIP_MCP=true ;;
        --skip-rest)          SKIP_REST=true ;;
        --with-supplementary) WITH_SUPPLEMENTARY=true ;;
    esac
done

BASE_URL="${BASE_URL:-${SECMAN_BACKEND_URL:-}}"
VERBOSE="${VERBOSE:-false}"
SKIP_CLI="${SKIP_CLI:-false}"
SKIP_MCP="${SKIP_MCP:-false}"
SKIP_REST="${SKIP_REST:-false}"
WITH_SUPPLEMENTARY="${WITH_SUPPLEMENTARY:-false}"

STAMP="$(date +%s)"
SUFFIX="${STAMP: -6}"
E2E_PREFIX="e2e-awswg-"

# Display names are letters and digits only, so "aws-<name>" is always a legal
# Workgroup.name (letters, digits, spaces, hyphens; 1-100 chars). WG_PREFIX is the
# part that stays constant across runs — it is what cleanup matches on.
WG_PREFIX="aws-E2eAwswg"
DN_ALPHA="E2eAwswg${SUFFIX}Alpha"
DN_BETA="E2eAwswg${SUFFIX}Beta"
DN_CSV="E2eAwswg${SUFFIX}Csv"
DN_BULK="E2eAwswg${SUFFIX}Bulk"
DN_RESTCSV="E2eAwswg${SUFFIX}Restcsv"
DN_MCP="E2eAwswg${SUFFIX}Mcp"
DN_RULE9="E2eAwswg${SUFFIX}Rule9"
DN_RENAMED="E2eAwswg${SUFFIX}Renamed"
DN_ROUNDTRIP="E2eAwswg${SUFFIX}Roundtrip"
# Deliberately illegal: "_" and "." are outside Workgroup.name's charset.
DN_ILLEGAL="E2eAwswg_Bad.01"
# 260 chars > MAX_ACCOUNT_NAME_LENGTH (255): dropped, never truncated.
DN_TOOLONG="$(printf 'E%.0s' {1..260})"

# Synthetic 12-digit accounts: "77" + 6-digit stamp + 4 digits. The shape is a
# secondary guard only — cleanup keys on name prefixes, which cannot collide.
ACC_JSON_A="77${SUFFIX}0001"
ACC_JSON_B="77${SUFFIX}0002"
ACC_CLI_CSV="77${SUFFIX}0003"
ACC_BULK="77${SUFFIX}0004"
ACC_RESTCSV="77${SUFFIX}0005"
ACC_MCP="77${SUFFIX}0006"
ACC_RULE9="77${SUFFIX}0007"
ACC_RENAME="77${SUFFIX}0008"
ACC_ILLEGAL="77${SUFFIX}0009"
ACC_TOOLONG="77${SUFFIX}0010"
ACC_BLANK="77${SUFFIX}0011"
ACC_ROUNDTRIP="77${SUFFIX}0012"
ACC_XSURFACE="77${SUFFIX}0013"
ACC_SHORT="7712345"   # 7 digits — must be rejected before any workgroup lookup

VIEWER_USER="${E2E_PREFIX}viewer"
VIEWER_EMAIL="${VIEWER_USER}@e2e.local"
PLAIN_USER="${E2E_PREFIX}plain"
PLAIN_EMAIL="${PLAIN_USER}@e2e.local"
TEST_PASS="E2eAwsWg!${SUFFIX}"

RULE9_ASSET_NAME="${E2E_PREFIX}host-rule9"
# The asset owner must NOT be the viewer's username, or rule #8 (owner == username)
# would grant access and the rule-#9 assertion would pass for the wrong reason.
RULE9_ASSET_OWNER="${E2E_PREFIX}ownerstub"

CLI_JAR="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"
FIXTURES="$REPO_ROOT/testdata/user-mappings"
COOKIE_JAR="$(mktemp)"
USER_COOKIE_JAR="$(mktemp)"
STATUS_FILE="$(mktemp)"
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
        command -v "$cmd" >/dev/null 2>&1 || { log_fail "Required command not found: $cmd"; missing=1; }
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
        (cd "$REPO_ROOT" && ./gradlew :cli:shadowJar -q) || { log_fail "CLI jar build failed"; exit 1; }
    fi

    if [[ ! -d "$FIXTURES" ]]; then
        log_fail "Fixture directory missing: $FIXTURES"
        exit 1
    fi

    log_info "Backend: $BASE_URL"
    log_info "Run stamp: $STAMP (accounts 77${SUFFIX}NNNN, workgroups ${WG_PREFIX}${SUFFIX}*)"
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
    [[ "$status" == "200" ]] || { log_fail "Admin login failed (HTTP $status)"; exit 1; }
    log_dbg "Admin logged in"
}

# user_login <username> <password> -> fills USER_COOKIE_JAR; 0 on success
user_login() {
    local status
    status=$(curl -sS -o /dev/null -w '%{http_code}' -c "$USER_COOKIE_JAR" \
        -H 'Content-Type: application/json' \
        -X POST "${BASE_URL}/api/auth/login" \
        --data "$(jq -nc --arg u "$1" --arg p "$2" '{username:$u, password:$p}')")
    [[ "$status" == "200" ]]
}

# api <METHOD> <path> [json-body] -> body on stdout, HTTP code via api_status
api() { _req "$COOKIE_JAR" "$@"; }
# uapi — same, as the logged-in non-admin user
uapi() { _req "$USER_COOKIE_JAR" "$@"; }

# The HTTP code of the most recent api/uapi/upload call.
#
# It lives in a file rather than a variable because the common shape here is
# `resp=$(api GET /x)`, and command substitution runs the call in a subshell: a
# variable assigned there never reaches the caller, so every later `$API_STATUS`
# read would silently report the *previous* request's code. That produced four
# false failures on this driver's first run before it was caught.
api_status() { cat "$STATUS_FILE" 2>/dev/null || echo "000"; }

# The shared body of api()/uapi(). Split out only so the two differ by cookie jar
# alone — a second copy would drift in exactly the header that matters.
_req() {
    local jar="$1" method="$2" path="$3" body="${4:-}"
    local out; out="$(mktemp)"
    local args=(-sS -o "$out" -w '%{http_code}' -b "$jar" -X "$method" "${BASE_URL}${path}")
    [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
    curl "${args[@]}" > "$STATUS_FILE"
    cat "$out"
    rm -f "$out"
}

# upload <path> <part-name> <file> -> body on stdout, code via api_status
upload() {
    local path="$1" part="$2" file="$3"
    local out; out="$(mktemp)"
    curl -sS -o "$out" -w '%{http_code}' -b "$COOKIE_JAR" \
        -X POST "${BASE_URL}${path}" -F "${part}=@${file}" > "$STATUS_FILE"
    cat "$out"
    rm -f "$out"
}

# =============================================================================
# MCP helper (streamable HTTP transport)
# =============================================================================

# mcp_raw <method> <params-json> [delegated-email|--no-delegation] -> JSON-RPC response
mcp_raw() {
    local method="$1" params="$2" delegated="${3:-$SECMAN_ADMIN_EMAIL}"
    local body headers=(-H 'Content-Type: application/json' -H "X-MCP-API-Key: ${SECMAN_MCP_KEY}")
    [[ "$delegated" != "--no-delegation" ]] && headers+=(-H "X-MCP-User-Email: ${delegated}")
    body=$(jq -nc --arg m "$method" --argjson p "$params" --arg id "e2e-${RANDOM}" \
        '{jsonrpc:"2.0", id:$id, method:$m, params:$p}')
    log_dbg "MCP -> $method ${delegated}: $params"
    local resp
    resp=$(curl -sS -X POST "${BASE_URL}/mcp" "${headers[@]}" --data "$body")
    log_dbg "MCP <- $resp"
    echo "$resp"
}

# mcp_call <tool> <json-args> [delegated-email|--no-delegation]
mcp_call() {
    mcp_raw "tools/call" "$(jq -nc --arg t "$1" --argjson a "$2" '{name:$t, arguments:$a}')" "${3:-$SECMAN_ADMIN_EMAIL}"
}

# The tool payload is JSON-encoded inside result.content[0].text.
mcp_payload() { echo "$1" | jq -c '.result.content[0].text | fromjson? // {}'; }

# =============================================================================
# Cleanup
#
# Everything is matched on a prefix that is CONSTANT across runs, so leftovers
# from an interrupted earlier run are swept too. Nothing is matched on this run's
# account ids: those carry a timestamp, so an id match would miss every earlier
# run, and a loosened substring match would reach real data.
# =============================================================================

cleanup() {
    local phase_label="${1:-post-run}"
    log_info "Cleanup ($phase_label) …"

    # 1. User mappings — by owner-email prefix.
    api GET "/api/user-mappings/current?size=2000" 2>/dev/null \
        | jq -r --arg p "$E2E_PREFIX" \
            '(.content // .mappings // [])[]? | select((.email // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r m_id; do
            [[ -n "$m_id" ]] && api DELETE "/api/user-mappings/${m_id}" >/dev/null 2>&1 || true
        done || true

    # 2. Assets — by asset NAME prefix. This driver names its own asset, so unlike
    #    the risk-assessment driver it never has to match on a cloud account id.
    api GET "/api/assets" 2>/dev/null \
        | jq -r --arg p "$E2E_PREFIX" \
            '(if type == "array" then . else (.content // []) end)[]?
             | select((.name // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r a_id; do
            [[ -n "$a_id" ]] && api DELETE "/api/assets/${a_id}" >/dev/null 2>&1 || true
        done || true

    # 3. Users — by username prefix. Removed before their workgroups so the
    #    membership rows go with the user rather than being orphaned.
    api GET "/api/users" 2>/dev/null \
        | jq -r --arg p "$E2E_PREFIX" \
            '(if type == "array" then . else (.content // []) end)[]?
             | select((.username // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r u_id; do
            [[ -n "$u_id" ]] && api DELETE "/api/users/${u_id}" >/dev/null 2>&1 || true
        done || true

    # 4. Workgroups the linker created — by the "aws-E2eAwswg" name prefix.
    #    Deleting a workgroup takes its workgroup_aws_account rows with it, which
    #    is what actually revokes rule-#9 access for anything this run linked.
    api GET "/api/workgroups" 2>/dev/null \
        | jq -r --arg p "$WG_PREFIX" \
            '(if type == "array" then . else (.content // []) end)[]?
             | select((.name // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r w_id; do
            [[ -n "$w_id" ]] && api DELETE "/api/workgroups/${w_id}" >/dev/null 2>&1 || true
        done || true

    # 5. The XLSX fixtures' own rows (see sweep_xlsx_fixture_mappings).
    sweep_xlsx_fixture_mappings

    log_dbg "Cleanup ($phase_label) done"
}

# Cleanup must run on the failure path too. A driver that leaves its rows behind
# when it dies makes the NEXT run's pre-run cleanup do the work, which hides the
# fact that this one died at all.
on_exit() {
    local rc=$?
    cleanup "post-run" || true
    rm -f "$COOKIE_JAR" "$USER_COOKIE_JAR" "$STATUS_FILE"
    rm -rf "$WORK_DIR"
    exit $rc
}

# =============================================================================
# Shared assertions
# =============================================================================

# workgroup_id_by_name <name> -> id, or empty. Case-insensitive, exactly like
# WorkgroupAccountLinkService.findByNameIgnoreCase — a case-sensitive lookup here
# would report a false failure for a link that actually worked.
workgroup_id_by_name() {
    api GET "/api/workgroups" \
        | jq -r --arg n "$(echo "$1" | tr '[:upper:]' '[:lower:]')" \
            '(if type == "array" then . else (.content // []) end)[]?
             | select(((.name // "") | ascii_downcase) == $n) | .id' 2>/dev/null | head -1 || true
}

# workgroup_has_account <workgroupId> <accountId> -> 0 if linked
workgroup_has_account() {
    local n
    n=$(api GET "/api/workgroups/$1/aws-accounts" \
        | jq -r --arg a "$2" '[(if type == "array" then . else [] end)[]? | select(.awsAccountId == $a)] | length' 2>/dev/null || true)
    [[ "${n:-0}" -gt 0 ]]
}

# assert_linked <account> <displayName> <label>
#
# Every assertion here returns 0 even when it fails: the failure is recorded in
# FAIL_COUNT, and the run must continue so one broken phase does not hide the
# state of the other fifteen. Under `set -e` a non-zero return would abort.
assert_linked() {
    local account="$1" display="$2" label="$3"
    local wg_name="aws-${display}" wg_id
    wg_id="$(workgroup_id_by_name "$wg_name")"

    if [[ -z "$wg_id" ]]; then
        log_fail "$label: workgroup '$wg_name' was not created"
    elif workgroup_has_account "$wg_id" "$account"; then
        log_pass "$label: $account linked to $wg_name (id=$wg_id)"
    else
        log_fail "$label: workgroup '$wg_name' exists (id=$wg_id) but $account is not in it"
    fi
    return 0
}

# assert_not_linked <displayName> <label> — asserts the workgroup was never created
assert_not_linked() {
    local wg_id; wg_id="$(workgroup_id_by_name "aws-$1")"
    if [[ -z "$wg_id" ]]; then
        log_pass "$2: no workgroup created for '$1'"
    else
        log_fail "$2: workgroup 'aws-$1' was created (id=$wg_id) but must not have been"
    fi
}

# mapping_display_name <account> -> the stored aws_account_name, or empty
mapping_display_name() {
    api GET "/api/user-mappings/current?size=2000" \
        | jq -r --arg a "$1" \
            '(.content // .mappings // [])[]? | select(.awsAccountId == $a) | .awsAccountName // empty' 2>/dev/null | head -1 || true
}

# summary_counters <json> -> "processed linked workgroupsCreated alreadyLinked failed"
summary_counters() {
    echo "$1" | jq -r '[.processed // 0, .linked // 0, .workgroupsCreated // 0,
                        .alreadyLinked // 0, .failed // 0] | @tsv' 2>/dev/null || true
}

# assert_eq <actual> <expected> <label>
assert_eq() {
    if [[ "$1" == "$2" ]]; then
        log_pass "$3 ($1)"
    else
        log_fail "$3: expected '$2', got '$1'"
    fi
}

# run_cli <args…> -> stdout+stderr on stdout, exit code in CLI_RC
CLI_RC=0

# Runs the CLI jar with the admin credentials and backend URL every subcommand
# needs. `set +e` around it is deliberate: a non-zero exit is frequently the thing
# under test (link-workgroups returns 1 when any account failed), so it has to
# reach CLI_RC rather than kill the run.
run_cli() {
    local out
    set +e
    out=$(java -jar "$CLI_JAR" "$@" \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" 2>&1)
    CLI_RC=$?
    set -e
    log_dbg "$out"
    echo "$out"
}

# =============================================================================
# Setup
# =============================================================================

VIEWER_ID=""
PLAIN_ID=""

# Two plain USERs and one asset carrying a cloud account id. Nothing here links
# anything: the point is to establish a state where the asset is provably
# unreachable, so that phase 10's "now it is visible" means something.
setup_testbed() {
    phase "Setup: users and the rule-9 asset"

    local u
    u=$(api POST "/api/users" "$(jq -nc --arg u "$VIEWER_USER" --arg e "$VIEWER_EMAIL" --arg p "$TEST_PASS" \
        '{username:$u, email:$e, password:$p, roles:["USER"]}')")
    VIEWER_ID=$(echo "$u" | jq -r '.id // empty')
    [[ -n "$VIEWER_ID" ]] || { log_fail "Could not create viewer user (HTTP $(api_status)): $u"; exit 1; }

    u=$(api POST "/api/users" "$(jq -nc --arg u "$PLAIN_USER" --arg e "$PLAIN_EMAIL" --arg p "$TEST_PASS" \
        '{username:$u, email:$e, password:$p, roles:["USER"]}')")
    PLAIN_ID=$(echo "$u" | jq -r '.id // empty')
    [[ -n "$PLAIN_ID" ]] || { log_fail "Could not create plain user (HTTP $(api_status)): $u"; exit 1; }
    log_pass "Users created: $VIEWER_USER (id=$VIEWER_ID), $PLAIN_USER (id=$PLAIN_ID)"

    # The asset the rule-9 phase looks for. Owner is a stub string, never the
    # viewer's username: rule #8 grants access on owner == username, and that
    # would make the rule-9 assertion pass without rule 9 doing anything.
    api PUT "/api/assets/import" "$(jq -nc --arg n "$RULE9_ASSET_NAME" --arg o "$RULE9_ASSET_OWNER" --arg c "$ACC_RULE9" \
        '{name:$n, type:"SERVER", owner:$o, cloudAccountId:$c, description:"E2E rule #9 visibility probe"}')" >/dev/null
    [[ "$(api_status)" =~ ^20 ]] || { log_fail "Could not create the rule-9 asset (HTTP $(api_status))"; exit 1; }
    log_pass "Asset $RULE9_ASSET_NAME created with cloudAccountId=$ACC_RULE9"
}

# =============================================================================
# Fixture builders — per-run files with synthetic ids
#
# The shipped fixtures carry FIXED account ids (706840063453 and friends), so
# using them destructively would collide between runs and leave real-looking rows
# behind. They are exercised read-only in phase 1; everything that writes uses a
# file generated here.
# =============================================================================

# cloud_custodian_json <file> <account> <display> [<account2> <display2> …]
cloud_custodian_json() {
    local file="$1"; shift
    local entries="[]"
    while [[ $# -ge 2 ]]; do
        entries=$(jq -c --arg a "$1" --arg d "$2" --arg o "${E2E_PREFIX}$(echo "$2" | tr '[:upper:]' '[:lower:]')-owner@e2e.local" \
            '. + [{account_id:$a, email:("aws-root-" + $a + "@e2e.local"), display_name:$d,
                   name:$a, org_id:"o-e2e00000001", status:"ACTIVE",
                   vars:{"cov:owner":$o}}]' <<<"$entries")
        shift 2
    done
    jq -nc --argjson acc "$entries" '{accounts:$acc}' > "$file"
}

# cli_csv <file> <account> <display> [...] — dialect: email,type,value,display_name
cli_csv() {
    local file="$1"; shift
    echo "email,type,value,display_name" > "$file"
    while [[ $# -ge 2 ]]; do
        echo "${E2E_PREFIX}$(echo "$2" | tr '[:upper:]' '[:lower:]')-owner@e2e.local,AWS_ACCOUNT,$1,$2" >> "$file"
        shift 2
    done
}

# rest_csv <file> <account> <display> [...] — dialect: account_id,owner_email,domain,display_name
rest_csv() {
    local file="$1"; shift
    echo "account_id,owner_email,domain,display_name" > "$file"
    while [[ $# -ge 2 ]]; do
        echo "$1,${E2E_PREFIX}$(echo "$2" | tr '[:upper:]' '[:lower:]')-owner@e2e.local,,$2" >> "$file"
        shift 2
    done
}

# cli_link_field "<cli output>" "<label-regex>" -> the integer the printer reported.
#
# WorkgroupLinkPrinter omits any counter line that is zero, and swaps the verb in a
# dry run ("Accounts would be linked:"), so the label is a regex and a missing line
# means zero — which is exactly what the printer intends it to mean.
cli_link_field() {
    local n
    n=$(echo "$1" | grep -oE "$2:[[:space:]]*[0-9]+" | head -1 | grep -oE '[0-9]+$' || true)
    echo "${n:-0}"
}

# Counts of the state the shipped fixtures would touch. Phase 1 compares these
# before and after its dry run; the absolute numbers are irrelevant, only that
# they did not move.
fixture_mapping_count() {
    api GET "/api/user-mappings/current?size=2000" \
        | jq -r '[(.content // .mappings // [])[]?
                  | select(.awsAccountId == "706840063453" or .awsAccountId == "156674634739"
                           or .awsAccountId == "421337195204" or .awsAccountId == "900112233445")] | length' \
            2>/dev/null || echo "?"
}

fixture_workgroup_count() {
    api GET "/api/workgroups" \
        | jq -r '[(if type == "array" then . else (.content // []) end)[]?
                  | select((.name // "") | test("^aws-(Legacy-alpha|DevOps-beta|Data_Platform)"; "i"))] | length' \
            2>/dev/null || echo "?"
}

FIXTURE_MAPPINGS_BEFORE=""; FIXTURE_MAPPINGS_AFTER=""
FIXTURE_WORKGROUPS_BEFORE=""; FIXTURE_WORKGROUPS_AFTER=""

# =============================================================================
# Phase 1 — the shipped fixtures still parse (dry run, nothing persisted)
# =============================================================================

phase_fixture_dryrun() {
    phase "1. Shipped testdata fixtures (dry run)"

    # docs/AWS_ACCOUNT_WORKGROUP_LINKING.md documents this exact outcome for the
    # sample: 4 pairs, 3 linkable across 2 workgroups, and Data_Platform.01 as an
    # error because "_" and "." are outside Workgroup.name's charset.
    FIXTURE_MAPPINGS_BEFORE="$(fixture_mapping_count)"
    FIXTURE_WORKGROUPS_BEFORE="$(fixture_workgroup_count)"

    local out processed failed linked already
    out=$(run_cli manage-user-mappings import -f "$FIXTURES/accounts-with-display-name.json" --dry-run)

    FIXTURE_MAPPINGS_AFTER="$(fixture_mapping_count)"
    FIXTURE_WORKGROUPS_AFTER="$(fixture_workgroup_count)"

    processed=$(cli_link_field "$out" "Accounts processed")
    failed=$(cli_link_field "$out" "Failed")
    linked=$(cli_link_field "$out" "Accounts (would be )?linked")

    assert_eq "$processed" "4" "JSON fixture: 4 (account, display name) pairs processed"
    assert_eq "$failed" "1" "JSON fixture: Data_Platform.01 reported as the single error"

    # linked + alreadyLinked == 3, because an environment that already has an
    # aws-DevOps-beta workgroup reports those rows as alreadyLinked instead.
    already=$(cli_link_field "$out" "Already linked")
    assert_eq "$(( linked + already ))" "3" "JSON fixture: 3 accounts linkable"

    # A dry run must not CHANGE anything — which is not the same as "nothing is
    # there afterwards". These fixtures carry fixed account ids and the linking doc
    # invites people to run them, so on a working dev box they are usually already
    # imported and already linked (the summary above then reports them as
    # alreadyLinked, which is why that counter is folded into the check). Comparing
    # before and after is the assertion that holds either way.
    assert_eq "$FIXTURE_MAPPINGS_AFTER" "$FIXTURE_MAPPINGS_BEFORE" \
        "JSON fixture dry run changed no mapping (was $FIXTURE_MAPPINGS_BEFORE)"
    assert_eq "$FIXTURE_WORKGROUPS_AFTER" "$FIXTURE_WORKGROUPS_BEFORE" \
        "JSON fixture dry run created no workgroup (was $FIXTURE_WORKGROUPS_BEFORE)"

    # The CSV fixture is the same data in the CLI dialect and must agree.
    out=$(run_cli manage-user-mappings import -f "$FIXTURES/mappings-with-display-name.csv" --dry-run)
    assert_eq "$(cli_link_field "$out" "Accounts processed")" "4" "CSV fixture: 4 pairs processed (DOMAIN row is not a candidate)"
    assert_eq "$(cli_link_field "$out" "Failed")" "1" "CSV fixture: same single error row"
}

# =============================================================================
# Phase 2 — CLI import, Cloud Custodian JSON
# =============================================================================

phase_cli_json_import() {
    phase "2. CLI import — Cloud Custodian JSON with display_name"

    # Two accounts, two display names, one of them shared by neither — plus a
    # second account on the SAME display name, which must land in one workgroup.
    cloud_custodian_json "$WORK_DIR/accounts.json" \
        "$ACC_JSON_A" "$DN_ALPHA" \
        "$ACC_JSON_B" "$DN_BETA" \
        "$ACC_XSURFACE" "$DN_BETA"

    local out
    out=$(run_cli manage-user-mappings import -f "$WORK_DIR/accounts.json")
    assert_eq "$CLI_RC" "0" "CLI JSON import exited 0"

    assert_linked "$ACC_JSON_A" "$DN_ALPHA" "CLI JSON"
    assert_linked "$ACC_JSON_B" "$DN_BETA" "CLI JSON"
    assert_linked "$ACC_XSURFACE" "$DN_BETA" "CLI JSON (two accounts share one display name)"

    # The display name must be persisted, or the correction path in phase 9 has
    # nothing to work from.
    assert_eq "$(mapping_display_name "$ACC_JSON_A")" "$DN_ALPHA" "aws_account_name persisted for $ACC_JSON_A"
}

# =============================================================================
# Phase 3 — CLI import, the CLI CSV dialect
# =============================================================================

phase_cli_csv_import() {
    phase "3. CLI import — CSV dialect email,type,value,display_name"

    cli_csv "$WORK_DIR/mappings.csv" "$ACC_CLI_CSV" "$DN_CSV"
    # A DOMAIN row with an empty display name must import and link nothing.
    echo "${E2E_PREFIX}domain-owner@e2e.local,DOMAIN,e2e-awswg.local," >> "$WORK_DIR/mappings.csv"

    local out
    out=$(run_cli manage-user-mappings import -f "$WORK_DIR/mappings.csv")
    assert_eq "$CLI_RC" "0" "CLI CSV import exited 0"

    assert_linked "$ACC_CLI_CSV" "$DN_CSV" "CLI CSV"
    assert_eq "$(cli_link_field "$out" "Accounts processed")" "1" "DOMAIN row with blank display name is not a link candidate"
}

# =============================================================================
# Phase 4 — REST POST /api/user-mappings/bulk
# =============================================================================

phase_rest_bulk() {
    phase "4. REST POST /api/user-mappings/bulk"

    local body resp links
    body=$(jq -nc --arg e "${E2E_PREFIX}bulk-owner@e2e.local" --arg a "$ACC_BULK" --arg d "$DN_BULK" \
        '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}], dryRun:false}')
    resp=$(api POST "/api/user-mappings/bulk" "$body")
    assert_eq "$(api_status)" "200" "bulk import accepted"

    links=$(echo "$resp" | jq -c '.workgroupLinks // {}')
    assert_eq "$(echo "$links" | jq -r '.processed // 0')" "1" "bulk: one pair processed"
    assert_linked "$ACC_BULK" "$DN_BULK" "REST bulk"

    # A bulk import with no displayName anywhere must not report linking at all —
    # this is what keeps the pre-existing Excel and plain-CSV clients unaffected.
    body=$(jq -nc --arg e "${E2E_PREFIX}nolink-owner@e2e.local" --arg a "77${SUFFIX}0014" \
        '{mappings:[{email:$e, awsAccountId:$a}], dryRun:false}')
    resp=$(api POST "/api/user-mappings/bulk" "$body")
    assert_eq "$(echo "$resp" | jq -r '.workgroupLinks // "null"')" "null" "bulk without displayName reports no linking"
}

# =============================================================================
# Phase 5 — REST CSV upload (the OTHER dialect)
# =============================================================================

phase_rest_csv_upload() {
    phase "5. REST POST /api/import/upload-user-mappings-csv"

    # Deliberately the other dialect. Feeding the CLI's header shape here (or this
    # one to the CLI) is a live foot-gun that only an end-to-end run catches: the
    # parser requires account_id + owner_email, case-insensitive, any order.
    rest_csv "$WORK_DIR/rest.csv" "$ACC_RESTCSV" "$DN_RESTCSV"

    local resp
    resp=$(upload "/api/import/upload-user-mappings-csv" "csvFile" "$WORK_DIR/rest.csv")
    assert_eq "$(api_status)" "200" "REST CSV upload accepted"
    assert_eq "$(echo "$resp" | jq -r '.imported // 0')" "1" "REST CSV imported one mapping"
    assert_linked "$ACC_RESTCSV" "$DN_RESTCSV" "REST CSV"

    # The CLI dialect must be REJECTED here rather than silently importing zero rows.
    cli_csv "$WORK_DIR/wrong-dialect.csv" "77${SUFFIX}0015" "E2eAwswg${SUFFIX}Wrong"
    resp=$(upload "/api/import/upload-user-mappings-csv" "csvFile" "$WORK_DIR/wrong-dialect.csv")
    if [[ "$(api_status)" == "400" ]] && echo "$resp" | grep -qi "missing required column"; then
        log_pass "REST CSV rejects the CLI dialect with a missing-column error"
    else
        log_fail "REST CSV did not reject the CLI dialect (HTTP $(api_status)): $resp"
    fi
}

# The rows the XLSX fixtures import, deleted so phase 6 starts from a known state.
#
# Matched on BOTH the address and the account id, and every pair is unmistakably
# synthetic: RFC 2606 reserved domains (example.com, company.org) against
# sequential test account ids. A real mapping would have to match both halves to
# be caught, which is what keeps this from becoming the kind of over-broad
# cleanup filter that deletes production rows.
sweep_xlsx_fixture_mappings() {
    api GET "/api/user-mappings/current?size=2000" \
        | jq -r '(.content // .mappings // [])[]?
                 | select((.email // "") | test("^(user[0-9]+@example\\.com|admin@company\\.org|valid[0-9]+@example\\.com)$"))
                 | select((.awsAccountId // "") | test("^(123456789012|987654321098|555555555555|111111111111)$"))
                 | .id' 2>/dev/null \
        | while read -r m_id; do
            [[ -n "$m_id" ]] && api DELETE "/api/user-mappings/${m_id}" >/dev/null 2>&1 || true
        done || true
}

# =============================================================================
# Phase 6 — REST XLSX upload (re-adopts the orphaned fixture suite)
# =============================================================================

phase_rest_xlsx_upload() {
    phase "6. REST POST /api/import/upload-user-mappings (XLSX)"

    # These fixtures lost their Playwright spec and have had no consumer since;
    # testdata/user-mappings/README.md documents the expected outcome per file.
    # Those counts assume the rows are not already present, so clear them first —
    # otherwise the second run of the day reports 0 imported / 5 skipped and looks
    # like a regression when nothing changed.
    sweep_xlsx_fixture_mappings

    local resp

    resp=$(upload "/api/import/upload-user-mappings" "xlsxFile" "$FIXTURES/valid-mappings.xlsx")
    assert_eq "$(api_status)" "200" "valid-mappings.xlsx accepted"
    assert_eq "$(echo "$resp" | jq -r '.imported // -1')" "3" "valid-mappings.xlsx imported 3"

    # The Excel path has no display_name column, so it must link NOTHING. That is a
    # deliberate property, not an oversight — asserting it stops someone "fixing" it.
    assert_eq "$(echo "$resp" | jq -r '.workgroupLinks // "null"')" "null" "XLSX path reports no workgroup linking"

    resp=$(upload "/api/import/upload-user-mappings" "xlsxFile" "$FIXTURES/mixed-valid-invalid.xlsx")
    assert_eq "$(echo "$resp" | jq -r '.imported // -1')" "3" "mixed-valid-invalid.xlsx imported 3"
    assert_eq "$(echo "$resp" | jq -r '.skipped // -1')" "2" "mixed-valid-invalid.xlsx skipped 2"

    resp=$(upload "/api/import/upload-user-mappings" "xlsxFile" "$FIXTURES/empty-file.xlsx")
    assert_eq "$(echo "$resp" | jq -r '.imported // -1')" "0" "empty-file.xlsx imported 0"

    resp=$(upload "/api/import/upload-user-mappings" "xlsxFile" "$FIXTURES/invalid-aws-accounts.xlsx")
    assert_eq "$(echo "$resp" | jq -r '.imported // -1')" "0" "invalid-aws-accounts.xlsx imported none"
    if [[ "$(echo "$resp" | jq -r '.errors | length')" -gt 0 ]]; then
        log_pass "invalid-aws-accounts.xlsx reported per-row errors"
    else
        log_fail "invalid-aws-accounts.xlsx reported no errors"
    fi

    # Non-Excel content must be refused before parsing (A08: validate then parse).
    resp=$(upload "/api/import/upload-user-mappings" "xlsxFile" "$FIXTURES/wrong-format.txt")
    assert_eq "$(api_status)" "400" "wrong-format.txt rejected as a bad file type"

    resp=$(upload "/api/import/upload-user-mappings" "xlsxFile" "$FIXTURES/missing-columns.xlsx")
    assert_eq "$(api_status)" "400" "missing-columns.xlsx rejected on the header check"

    sweep_xlsx_fixture_mappings
}

# =============================================================================
# Phase 7 — MCP import_user_mappings
# =============================================================================

phase_mcp_import() {
    phase "7. MCP import_user_mappings"

    local resp payload
    resp=$(mcp_call "import_user_mappings" "$(jq -nc \
        --arg e "${E2E_PREFIX}mcp-owner@e2e.local" --arg a "$ACC_MCP" --arg d "$DN_MCP" \
        '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}]}')")

    if echo "$resp" | jq -e '.error' >/dev/null 2>&1; then
        log_fail "MCP import_user_mappings returned a JSON-RPC error: $(echo "$resp" | jq -c .error)"
        return
    fi
    payload=$(mcp_payload "$resp")
    assert_eq "$(echo "$payload" | jq -r '.workgroupLinks.processed // 0')" "1" "MCP: one pair processed"
    assert_linked "$ACC_MCP" "$DN_MCP" "MCP import"
}

# =============================================================================
# Phase 8 — the surfaces agree
# =============================================================================

phase_cross_surface() {
    phase "8. Cross-surface agreement"

    # Every surface re-imports a pair that is ALREADY linked. The one correct
    # answer is processed=1, linked=0, alreadyLinked=1, failed=0 — the same on all
    # of them. A surface with its own copy of the rule would drift here first.
    local expect="1	0	0	1	0"

    if [[ "$SKIP_REST" != "true" ]]; then
        local resp
        resp=$(api POST "/api/user-mappings/bulk" "$(jq -nc \
            --arg e "${E2E_PREFIX}bulk-owner@e2e.local" --arg a "$ACC_BULK" --arg d "$DN_BULK" \
            '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}]}')")
        assert_eq "$(summary_counters "$(echo "$resp" | jq -c '.workgroupLinks // {}')")" "$expect" \
            "REST bulk re-import: processed/linked/created/alreadyLinked/failed"
    fi

    if [[ "$SKIP_MCP" != "true" ]]; then
        local payload
        payload=$(mcp_payload "$(mcp_call "import_user_mappings" "$(jq -nc \
            --arg e "${E2E_PREFIX}bulk-owner@e2e.local" --arg a "$ACC_BULK" --arg d "$DN_BULK" \
            '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}]}')")")
        assert_eq "$(summary_counters "$(echo "$payload" | jq -c '.workgroupLinks // {}')")" "$expect" \
            "MCP re-import agrees with REST bulk"
    fi

    if [[ "$SKIP_CLI" != "true" ]]; then
        cli_csv "$WORK_DIR/xsurface.csv" "$ACC_BULK" "$DN_BULK"
        local out
        out=$(run_cli manage-user-mappings import -f "$WORK_DIR/xsurface.csv")
        assert_eq "$CLI_RC" "0" "CLI re-import of an already-linked account exits 0"
        assert_eq "$(cli_link_field "$out" "Accounts processed")" "1" "CLI re-import agrees on processed"
        assert_eq "$(cli_link_field "$out" "Failed")" "0" "CLI re-import reports no failure for alreadyLinked"
    fi
}

# =============================================================================
# Phase 9 — the correction path (no file involved)
# =============================================================================

phase_correction_path() {
    phase "9. Correction path — link from stored display names"

    # Break the link the import made, leaving the stored aws_account_name behind.
    # That is exactly the state the correction path exists for: mappings imported
    # before display names were captured, or a workgroup missing at the time.
    local wg_id
    wg_id="$(workgroup_id_by_name "aws-${DN_ALPHA}")"
    if [[ -z "$wg_id" ]]; then
        log_fail "Correction path: workgroup aws-${DN_ALPHA} missing, phase 2 must run first"
        return
    fi
    api DELETE "/api/workgroups/${wg_id}/aws-accounts/${ACC_JSON_A}" >/dev/null
    assert_eq "$(api_status)" "204" "Unlinked $ACC_JSON_A to set up the correction path"

    if [[ "$SKIP_CLI" != "true" ]]; then
        # Dry run first: it must report the work without doing any of it.
        local out
        out=$(run_cli manage-user-mappings link-workgroups --dry-run)
        assert_eq "$CLI_RC" "0" "link-workgroups --dry-run exited 0"
        if workgroup_has_account "$wg_id" "$ACC_JSON_A"; then
            log_fail "link-workgroups --dry-run actually created the assignment"
        else
            log_pass "link-workgroups --dry-run changed nothing"
        fi

        out=$(run_cli manage-user-mappings link-workgroups)
        assert_eq "$CLI_RC" "0" "link-workgroups exited 0"
        assert_linked "$ACC_JSON_A" "$DN_ALPHA" "CLI link-workgroups (from stored names, no file)"

        # Second run: everything is alreadyLinked, and that must not be a failure.
        out=$(run_cli manage-user-mappings link-workgroups)
        assert_eq "$CLI_RC" "0" "link-workgroups is idempotent — alreadyLinked never fails the run"
        assert_eq "$(cli_link_field "$out" "Accounts linked")" "0" "second link-workgroups run linked nothing new"
    fi

    if [[ "$SKIP_REST" != "true" ]]; then
        local resp
        resp=$(api POST "/api/user-mappings/link-workgroup-accounts" '{"dryRun":true}')
        assert_eq "$(api_status)" "200" "REST link-workgroup-accounts (dry run) accepted"
        assert_eq "$(echo "$resp" | jq -r '.dryRun // false')" "true" "REST correction reports dryRun"
    fi

    if [[ "$SKIP_MCP" != "true" ]]; then
        local payload
        payload=$(mcp_payload "$(mcp_call "link_workgroup_aws_accounts" '{"dryRun":true}')")
        if [[ "$(echo "$payload" | jq -r '.processed // -1')" -ge 0 ]]; then
            log_pass "MCP link_workgroup_aws_accounts returned a summary"
        else
            log_fail "MCP link_workgroup_aws_accounts returned no summary: $payload"
        fi
    fi
}

# =============================================================================
# Phase 10 — RULE #9: the link is what grants asset access
#
# The headline. Every other rule that could reach this asset is deliberately shut
# off: the viewer is not ADMIN/SECCHAMPION (no short-circuit), has no UserMapping
# for the account (#5), no sharing (#7), is not the owner (#8), and the asset is
# in no workgroup directly (#2). If it becomes visible, rule #9 did it.
# =============================================================================

RULE9_WG_ID=""

# 1 when the viewer's own /api/assets response contains the probe asset, 0 when it
# does not. Read through the viewer's cookie jar, never the admin's — the whole
# question is what THIS user is allowed to see.
viewer_sees_rule9_asset() {
    uapi GET "/api/assets" \
        | jq -r --arg n "$RULE9_ASSET_NAME" \
            '[(if type == "array" then . else (.content // []) end)[]? | select(.name == $n)] | length' 2>/dev/null || true
}

# The headline. Ordered so the negative is established first: invisible with no
# link, still invisible when linked but not a member, visible only once both hold.
phase_rule9_visibility() {
    phase "10. Rule #9 — workgroup AWS account grants asset access"

    if ! user_login "$VIEWER_USER" "$TEST_PASS"; then
        log_fail "Viewer login failed — cannot test rule #9"
        return
    fi

    assert_eq "$(viewer_sees_rule9_asset)" "0" "Before any link: viewer cannot see $RULE9_ASSET_NAME"

    # Import creates the workgroup and links the account.
    cloud_custodian_json "$WORK_DIR/rule9.json" "$ACC_RULE9" "$DN_RULE9"
    if [[ "$SKIP_CLI" != "true" ]]; then
        run_cli manage-user-mappings import -f "$WORK_DIR/rule9.json" >/dev/null
    else
        api POST "/api/user-mappings/bulk" "$(jq -nc \
            --arg e "${E2E_PREFIX}rule9-owner@e2e.local" --arg a "$ACC_RULE9" --arg d "$DN_RULE9" \
            '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}]}')" >/dev/null
    fi
    assert_linked "$ACC_RULE9" "$DN_RULE9" "Rule 9 setup"

    RULE9_WG_ID="$(workgroup_id_by_name "aws-${DN_RULE9}")"
    [[ -n "$RULE9_WG_ID" ]] || { log_fail "Rule 9: workgroup aws-${DN_RULE9} not found"; return; }

    # Linked, but the viewer is not a member yet.
    assert_eq "$(viewer_sees_rule9_asset)" "0" "Linked but not a member: still invisible"

    api POST "/api/workgroups/${RULE9_WG_ID}/users" "$(jq -nc --argjson u "[$VIEWER_ID]" '{userIds:$u}')" >/dev/null
    [[ "$(api_status)" =~ ^20 ]] || { log_fail "Could not add viewer to workgroup (HTTP $(api_status))"; return; }

    assert_eq "$(viewer_sees_rule9_asset)" "1" "Member of a workgroup holding the account: asset IS visible"
}

# =============================================================================
# Phase 11 — unlinking revokes it again
# =============================================================================

phase_unlink_revokes() {
    phase "11. Unlinking the account revokes access"

    if [[ -z "$RULE9_WG_ID" ]]; then
        log_warn "Rule 9 workgroup unknown — skipping the revocation phase"
        return
    fi

    # Same user, same membership. Only the account link changes, which is what
    # isolates rule #9 from every other way this asset could have been reachable.
    api DELETE "/api/workgroups/${RULE9_WG_ID}/aws-accounts/${ACC_RULE9}" >/dev/null
    assert_eq "$(api_status)" "204" "DELETE .../aws-accounts/$ACC_RULE9 returned 204"
    assert_eq "$(viewer_sees_rule9_asset)" "0" "After unlinking: asset is invisible again"

    api DELETE "/api/workgroups/${RULE9_WG_ID}/aws-accounts/${ACC_RULE9}" >/dev/null
    assert_eq "$(api_status)" "404" "Removing an absent link returns 404"
}

# =============================================================================
# Phase 12 — /api/workgroups/{id}/aws-accounts round trip
# =============================================================================

phase_wg_account_roundtrip() {
    phase "12. Workgroup AWS account round trip (REST + MCP)"

    local wg_id; wg_id="$(workgroup_id_by_name "aws-${DN_ALPHA}")"
    [[ -n "$wg_id" ]] || { log_fail "Round trip: workgroup aws-${DN_ALPHA} missing"; return; }

    api POST "/api/workgroups/${wg_id}/aws-accounts" "$(jq -nc --arg a "$ACC_ROUNDTRIP" '{awsAccountId:$a}')" >/dev/null
    assert_eq "$(api_status)" "201" "Admin added $ACC_ROUNDTRIP"

    api POST "/api/workgroups/${wg_id}/aws-accounts" "$(jq -nc --arg a "$ACC_ROUNDTRIP" '{awsAccountId:$a}')" >/dev/null
    assert_eq "$(api_status)" "409" "Adding the same account twice is a conflict"

    api POST "/api/workgroups/${wg_id}/aws-accounts" '{"awsAccountId":"123"}' >/dev/null
    assert_eq "$(api_status)" "400" "A non-12-digit account id is rejected"

    if [[ "$SKIP_MCP" != "true" ]]; then
        local payload
        payload=$(mcp_payload "$(mcp_call "list_workgroup_aws_accounts" "$(jq -nc --argjson w "$wg_id" '{workgroupId:$w}')")")
        if echo "$payload" | jq -e --arg a "$ACC_ROUNDTRIP" \
            '[(.accounts // .awsAccounts // [])[]? | select((.awsAccountId // .) == $a)] | length > 0' >/dev/null 2>&1; then
            log_pass "MCP list_workgroup_aws_accounts sees $ACC_ROUNDTRIP"
        else
            log_fail "MCP list_workgroup_aws_accounts did not list $ACC_ROUNDTRIP: $payload"
        fi

        mcp_call "remove_workgroup_aws_account" \
            "$(jq -nc --argjson w "$wg_id" --arg a "$ACC_ROUNDTRIP" '{workgroupId:$w, awsAccountId:$a}')" >/dev/null
        if workgroup_has_account "$wg_id" "$ACC_ROUNDTRIP"; then
            log_fail "MCP remove_workgroup_aws_account left $ACC_ROUNDTRIP in place"
        else
            log_pass "MCP remove_workgroup_aws_account removed $ACC_ROUNDTRIP"
        fi
    else
        api DELETE "/api/workgroups/${wg_id}/aws-accounts/${ACC_ROUNDTRIP}" >/dev/null
        assert_eq "$(api_status)" "204" "REST removed $ACC_ROUNDTRIP"
    fi
}

# =============================================================================
# Phase 13 — authorization negatives
# =============================================================================

phase_authz_negatives() {
    phase "13. Authorization negatives"

    if [[ "$SKIP_REST" != "true" ]]; then
        if ! user_login "$PLAIN_USER" "$TEST_PASS"; then
            log_fail "Plain-user login failed — cannot test the REST negatives"
        else
            uapi POST "/api/user-mappings/bulk" "$(jq -nc --arg e "$PLAIN_EMAIL" --arg a "77${SUFFIX}0016" \
                '{mappings:[{email:$e, awsAccountId:$a, displayName:"E2eAwswgNope"}]}')" >/dev/null
            assert_eq "$(api_status)" "403" "Non-admin refused on POST /api/user-mappings/bulk"

            uapi POST "/api/user-mappings/link-workgroup-accounts" '{"dryRun":true}' >/dev/null
            assert_eq "$(api_status)" "403" "Non-admin refused on POST /api/user-mappings/link-workgroup-accounts"

            uapi POST "/api/import/upload-user-mappings-csv" >/dev/null
            case "$(api_status)" in
                401|403) log_pass "Non-admin refused on the CSV upload endpoint (HTTP $(api_status))" ;;
                2*)      log_fail "CSV upload endpoint ACCEPTED a non-admin (HTTP $(api_status))" ;;
                *)       log_warn "CSV upload endpoint answered a non-admin with HTTP $(api_status) — not a 2xx, but not an auth refusal either" ;;
            esac
            assert_not_linked "E2eAwswgNope" "Refused non-admin bulk import"
        fi
    fi

    # canBindAccount: a workgroup MEMBER may only bind an account they already
    # reach through their own mapping or a sharing rule. The viewer reaches
    # $ACC_ROUNDTRIP through neither, so membership alone must not be enough.
    if [[ -n "$RULE9_WG_ID" ]] && user_login "$VIEWER_USER" "$TEST_PASS"; then
        uapi POST "/api/workgroups/${RULE9_WG_ID}/aws-accounts" \
            "$(jq -nc --arg a "$ACC_ROUNDTRIP" '{awsAccountId:$a}')" >/dev/null
        assert_eq "$(api_status)" "403" "A member cannot bind an account they have no access to"
    fi

    if [[ "$SKIP_MCP" != "true" ]]; then
        # Delegation is mandatory on tools/call — the API key alone is not identity.
        local resp
        resp=$(mcp_call "link_workgroup_aws_accounts" '{"dryRun":true}' "--no-delegation")
        if echo "$resp" | grep -qi "delegation"; then
            log_pass "MCP without X-MCP-User-Email is refused (delegation required)"
        else
            log_fail "MCP accepted a call with no delegation header: $resp"
        fi

        # Which gate refuses matters. The API key carries an allowed-domain list that
        # is checked before delegation is resolved, so on most keys a @e2e.local
        # delegate is rejected on the domain and the ADMIN check never runs. That is
        # still a refusal — but it is a weaker result than the one being aimed at, so
        # it is reported as such rather than quietly counted as an ADMIN denial.
        resp=$(mcp_call "link_workgroup_aws_accounts" '{"dryRun":true}' "$PLAIN_EMAIL")
        if echo "$resp" | grep -qiE "admin_required|not authorized|not an admin"; then
            log_pass "MCP link_workgroup_aws_accounts refuses a non-admin delegate (ADMIN check)"
        elif echo "$resp" | jq -e '.error' >/dev/null 2>&1; then
            log_pass "MCP refused the non-admin delegate, but on the API key's domain allowlist, not the ADMIN check — the ADMIN path is UNTESTED on this key"
        else
            log_fail "MCP allowed a non-admin delegate: $resp"
        fi
    fi
}

# =============================================================================
# Phase 14 — every tool is both LISTED and CALLABLE
#
# A tool in McpToolPermissions.LISTING but missing from .CALLING is listed and then
# silently denied. Six workgroup tools shipped in exactly that state. tools/list
# alone cannot see it; only calling can.
# =============================================================================

phase_mcp_callable() {
    phase "14. MCP tools are listed AND callable"

    local listed tool
    listed=$(mcp_raw "tools/list" '{}' | jq -r '[.result.tools[]?.name] | join(" ")' || true)
    log_dbg "tools/list: $listed"

    local wg_id; wg_id="$(workgroup_id_by_name "aws-${DN_ALPHA}")"

    for tool in import_user_mappings link_workgroup_aws_accounts list_workgroup_aws_accounts \
                add_workgroup_aws_account remove_workgroup_aws_account list_user_mappings; do
        if ! grep -qw "$tool" <<<"$listed"; then
            log_fail "$tool is missing from tools/list"
            continue
        fi

        local args resp
        case "$tool" in
            import_user_mappings)          args='{"mappings":[{"email":"'"${E2E_PREFIX}probe@e2e.local"'","awsAccountId":"'"77${SUFFIX}0017"'"}],"dryRun":true}' ;;
            link_workgroup_aws_accounts)   args='{"dryRun":true}' ;;
            list_workgroup_aws_accounts)   args="{\"workgroupId\":${wg_id:-0}}" ;;
            add_workgroup_aws_account)     args="{\"workgroupId\":${wg_id:-0},\"awsAccountId\":\"77${SUFFIX}0018\"}" ;;
            remove_workgroup_aws_account)  args="{\"workgroupId\":${wg_id:-0},\"awsAccountId\":\"77${SUFFIX}0018\"}" ;;
            list_user_mappings)            args='{}' ;;
        esac

        resp=$(mcp_call "$tool" "$args")
        # A JSON-RPC `error` here means the transport refused the call outright —
        # which is the LISTING-without-CALLING signature. A tool-level failure
        # inside `result` is a different thing and not what this phase is about.
        if echo "$resp" | jq -e '.error' >/dev/null 2>&1; then
            log_fail "$tool is listed but not callable: $(echo "$resp" | jq -c '.error')"
        else
            log_pass "$tool is listed and callable"
        fi
    done
}

# =============================================================================
# Phase 15 — a renamed account keeps its old link
# =============================================================================

phase_renamed_account() {
    phase "15. Renamed account gains the new link and keeps the old"

    api POST "/api/user-mappings/bulk" "$(jq -nc \
        --arg e "${E2E_PREFIX}rename-owner@e2e.local" --arg a "$ACC_RENAME" --arg d "$DN_ALPHA" \
        '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}]}')" >/dev/null
    assert_linked "$ACC_RENAME" "$DN_ALPHA" "Renamed account, first name"

    api POST "/api/user-mappings/bulk" "$(jq -nc \
        --arg e "${E2E_PREFIX}rename-owner@e2e.local" --arg a "$ACC_RENAME" --arg d "$DN_RENAMED" \
        '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}]}')" >/dev/null
    assert_linked "$ACC_RENAME" "$DN_RENAMED" "Renamed account, second name"

    # Removing the old assignment would revoke a workgroup's access to that
    # account's assets, which a rename must never do unasked.
    local old_wg; old_wg="$(workgroup_id_by_name "aws-${DN_ALPHA}")"
    if [[ -n "$old_wg" ]] && workgroup_has_account "$old_wg" "$ACC_RENAME"; then
        log_pass "The assignment under the previous name is left in place"
    else
        log_fail "Renaming removed the account's assignment under its old name"
    fi
}

# =============================================================================
# Phase 16 — error rows
# =============================================================================

phase_error_rows() {
    phase "16. Error rows"

    local resp links

    # "_" and "." are outside Workgroup.name's charset — reported, nothing created.
    resp=$(api POST "/api/user-mappings/bulk" "$(jq -nc \
        --arg e "${E2E_PREFIX}illegal-owner@e2e.local" --arg a "$ACC_ILLEGAL" --arg d "$DN_ILLEGAL" \
        '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}]}')")
    links=$(echo "$resp" | jq -c '.workgroupLinks // {}')
    assert_eq "$(echo "$links" | jq -r '.failed // 0')" "1" "Illegal display name is one failed row"
    assert_eq "$(echo "$links" | jq -r '.workgroupsCreated // 0')" "0" "Illegal display name created no workgroup"
    assert_not_linked "$DN_ILLEGAL" "Illegal display name"

    # A short account id is rejected before any workgroup lookup happens.
    resp=$(api POST "/api/user-mappings/bulk" "$(jq -nc \
        --arg e "${E2E_PREFIX}short-owner@e2e.local" --arg a "$ACC_SHORT" --arg d "E2eAwswg${SUFFIX}Short" \
        '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}]}')")
    assert_not_linked "E2eAwswg${SUFFIX}Short" "Short account id"

    # Over 255 characters the name is dropped rather than truncated — a truncated
    # name would resolve to a different, wrong workgroup.
    resp=$(api POST "/api/user-mappings/bulk" "$(jq -nc \
        --arg e "${E2E_PREFIX}toolong-owner@e2e.local" --arg a "$ACC_TOOLONG" --arg d "$DN_TOOLONG" \
        '{mappings:[{email:$e, awsAccountId:$a, displayName:$d}]}')")
    links=$(echo "$resp" | jq -c '.workgroupLinks // {}')
    assert_eq "$(echo "$links" | jq -r '.linked // 0')" "0" "A >255-char display name links nothing"
    assert_eq "$(mapping_display_name "$ACC_TOOLONG")" "" "A >255-char display name is dropped, not truncated"

    # A blank display name is not a linking candidate, but the mapping still imports.
    resp=$(api POST "/api/user-mappings/bulk" "$(jq -nc \
        --arg e "${E2E_PREFIX}blank-owner@e2e.local" --arg a "$ACC_BLANK" \
        '{mappings:[{email:$e, awsAccountId:$a, displayName:"  "}]}')")
    assert_eq "$(echo "$resp" | jq -r '.workgroupLinks.linked // 0')" "0" "A blank display name links nothing"
    local mapped
    mapped=$(api GET "/api/user-mappings/current?size=2000" \
        | jq -r --arg a "$ACC_BLANK" '[(.content // .mappings // [])[]? | select(.awsAccountId == $a)] | length' || true)
    assert_eq "$mapped" "1" "The mapping with a blank display name still imported"
}

# =============================================================================
# Supplementary — the adjacent drivers no other skill runs
# =============================================================================

phase_supplementary() {
    phase "S. Supplementary drivers"

    local script
    for script in tests/bulk-user-mapping-test.sh tests/mcp-e2e-workgroup-test.sh; do
        if [[ ! -x "$REPO_ROOT/$script" ]]; then
            log_warn "$script is not executable — skipped"
            continue
        fi
        log_info "Running $script …"
        if (cd "$REPO_ROOT" && SECMAN_BASE_URL="$BASE_URL" "./$script" >"$WORK_DIR/$(basename "$script").log" 2>&1); then
            log_pass "$script passed"
        else
            log_fail "$script failed — see $WORK_DIR/$(basename "$script").log"
        fi
    done
}

# =============================================================================
# Main
# =============================================================================

main() {
    echo "============================================================" >&2
    echo " AWS Account Import -> Workgroup Linking E2E" >&2
    echo "============================================================" >&2

    check_prerequisites
    admin_login
    cleanup "pre-run"
    trap on_exit EXIT

    setup_testbed

    if [[ "$SKIP_CLI" != "true" ]]; then
        phase_fixture_dryrun
        phase_cli_json_import
        phase_cli_csv_import
    else
        log_warn "SKIP_CLI=true — the CLI phases were not run"
    fi

    if [[ "$SKIP_REST" != "true" ]]; then
        phase_rest_bulk
        phase_rest_csv_upload
        phase_rest_xlsx_upload
    else
        log_warn "SKIP_REST=true — the REST upload phases were not run"
    fi

    if [[ "$SKIP_MCP" != "true" ]]; then
        phase_mcp_import
    else
        log_warn "SKIP_MCP=true — the MCP phases were not run"
    fi

    phase_cross_surface
    phase_correction_path
    phase_rule9_visibility
    phase_unlink_revokes
    phase_wg_account_roundtrip
    phase_authz_negatives

    if [[ "$SKIP_MCP" != "true" ]]; then
        phase_mcp_callable
    fi

    phase_renamed_account
    phase_error_rows

    if [[ "$WITH_SUPPLEMENTARY" == "true" ]]; then
        phase_supplementary
    fi

    phase "Summary"
    echo -e "${GREEN}Passed: ${PASS_COUNT}${NC}" >&2
    echo -e "${RED}Failed: ${FAIL_COUNT}${NC}" >&2

    [[ $FAIL_COUNT -eq 0 ]] || exit 1
    exit 0
}

main "$@"
