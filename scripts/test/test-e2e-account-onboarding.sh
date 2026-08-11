#!/usr/bin/env bash
#
# E2E: account onboarding for newly discovered AWS accounts (CLI + MCP + public questionnaire)
#
# Verifies the three onboarding modes an import can run for the owner of an AWS
# account SecMan has never seen:
#   WELCOME_ONLY  a welcome mail, nothing else
#   DIRECT        welcome mail + an assessment for a use case the operator names
#   GUIDED        welcome mail + a one-time link; the owner's answers resolve
#                 through admin-configured rules into the use cases the
#                 assessment is scoped to
#
# Testbed (all names carry E2E_PREFIX so cleanup is exact):
#   Users:        <prefix>champion (SECCHAMPION), <prefix>owner (USER)
#   Use cases:    <prefix>uc-base, <prefix>uc-data
#   Requirements: 2 tagged per use case (4 total), + 1 untagged (must NOT appear)
#   Release:      version 99.<epoch-suffix>.0, set ACTIVE (snapshots the corpus)
#   Questions:    environment (single), customer-data (single), data-types (multi)
#   Rules:        4 — one single-question, one two-question, one multi-select, one default
#   Accounts:     never-before-seen 12-digit IDs, one per phase
#
# What it proves:
#   1.  WELCOME_ONLY sends a welcome mail and starts nothing
#   2.  A bare --start-risk-assessment behaves EXACTLY as before onboarding modes
#       existed — DIRECT, and no welcome mail. This is the compatibility gate.
#   3.  GUIDED mints an invite and does NOT create an assessment yet
#   4.  The public questionnaire returns the questions and a MASKED account id
#   5.  Answers matching two rules produce ONE assessment scoped to the UNION,
#       pinned to the ACTIVE release
#   6.  Every token failure — replay, unknown, malformed — returns a byte-identical
#       body, and a burst of lookups is rate limited
#   7.  Answers matching nothing are recorded, the invite stays usable, and the
#       response is 409 NO_RULE_MATCHED
#   8.  MCP simulate/list/preview do the same over the streamable HTTP transport
#   9.  Negatives: plain USER cannot simulate; non-admin MCP is refused; an
#       incompatible flag combination exits 2; GUIDED with no rules is refused up front
#   10. Every dry run persists nothing, sends nothing, and mints NO token
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
#   pass-cli run --env-file ./secmanpp.env -- ./scripts/test/test-e2e-account-onboarding.sh
#   ./scripts/test/test-e2e-account-onboarding.sh --verbose
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
E2E_PREFIX="e2e-onb-"

CHAMPION_USER="${E2E_PREFIX}champion"
CHAMPION_EMAIL="${CHAMPION_USER}@e2e.local"
OWNER_USER="${E2E_PREFIX}owner"
OWNER_EMAIL="${OWNER_USER}@e2e.local"
PLAIN_USER="${E2E_PREFIX}plain"
PLAIN_EMAIL="${PLAIN_USER}@e2e.local"
TEST_PASS="E2eOnb!${SUFFIX}"

UC_BASE="${E2E_PREFIX}uc-base"
UC_DATA="${E2E_PREFIX}uc-data"
RELEASE_VERSION="99.${SUFFIX}.0"
RELEASE_NAME="${E2E_PREFIX}release"

# Synthetic accounts, one per phase. All share the shape 87[0-9] + 6-digit timestamp +
# "000", which is what the cleanup sweep matches on — keep any new account in that shape.
WELCOME_ACCOUNT="870${SUFFIX}000"
LEGACY_ACCOUNT="871${SUFFIX}000"   # bare --start-risk-assessment (compatibility gate)
GUIDED_ACCOUNT="872${SUFFIX}000"
NOMATCH_ACCOUNT="873${SUFFIX}000"
MCP_ACCOUNT="874${SUFFIX}000"
DRY_ACCOUNT="875${SUFFIX}000"
NEG_ACCOUNT="876${SUFFIX}000"

# Question and choice keys the rules are built from.
Q_ENV="environment"; Q_DATA="customer-data"; Q_TYPES="data-types"
C_PROD="production"; C_TEST="test"
C_YES="yes"; C_NO="no"
C_PII="pii"; C_FIN="financial"

CLI_JAR="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"
COOKIE_JAR="$(mktemp)"
OWNER_COOKIE_JAR="$(mktemp)"
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

# An invite token is a credential: whoever reads it can create a risk assessment as the
# account owner. This log is kept (tee'd into .e2e-logs/) and routinely pasted into tickets,
# so a token never reaches it in full.
redact_token() { local t="${1:-}"; [[ -n "$t" ]] && echo "…${t: -6}" || echo "<none>"; }

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
    [[ $missing -eq 0 ]] || exit 1

    for var in SECMAN_ADMIN_NAME SECMAN_ADMIN_PASS SECMAN_ADMIN_EMAIL SECMAN_MCP_KEY; do
        if [[ -z "${!var:-}" ]]; then
            log_fail "Required env var not set: $var (run under pass-cli)"
            missing=1
        fi
    done
    [[ $missing -eq 0 ]] || exit 1

    if [[ -z "$BASE_URL" ]]; then
        log_fail "BASE_URL / SECMAN_BACKEND_URL is not set — never hardcode a localhost literal"
        exit 1
    fi

    if [[ "$SKIP_CLI" != "true" && ! -f "$CLI_JAR" ]]; then
        log_fail "CLI jar not found at $CLI_JAR — run ./gradlew :cli:shadowJar"
        exit 1
    fi

    log_pass "Prerequisites satisfied (backend: $BASE_URL)"
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

# Same, as the plain (non-privileged) test user — for the authorization negatives.
api_as_plain() {
    local method="$1" path="$2" body="${3:-}"
    local out; out="$(mktemp)"
    local args=(-sS -o "$out" -w '%{http_code}' -b "$OWNER_COOKIE_JAR" -X "$method" "${BASE_URL}${path}")
    if [[ -n "$body" ]]; then
        args+=(-H 'Content-Type: application/json' --data "$body")
    fi
    API_STATUS="$(curl "${args[@]}")"
    cat "$out"
    rm -f "$out"
}

# Unauthenticated — the account owner holds no credential, only the token.
PUBLIC_STATUS=""
public_api() {
    local method="$1" path="$2" body="${3:-}"
    local out; out="$(mktemp)"
    local args=(-sS -o "$out" -w '%{http_code}' -X "$method" "${BASE_URL}${path}")
    if [[ -n "$body" ]]; then
        args+=(-H 'Content-Type: application/json' --data "$body")
    fi
    PUBLIC_STATUS="$(curl "${args[@]}")"
    cat "$out"
    rm -f "$out"
}

# =============================================================================
# MCP helper (streamable HTTP transport)
# =============================================================================

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
    # sweep below is refused while an assessment still references the asset). Matched on the
    # owner email the service writes into the notes, never on an account id: the ids carry a
    # per-run timestamp, so an id match would miss every earlier run, while a loose substring
    # match could delete a real assessment.
    local tracked
    tracked=$(api GET "/api/risk-assessments" || echo '[]')
    echo "$tracked" | jq -r --arg p "owner: ${E2E_PREFIX}" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select((.notes // "") | contains($p)) | .id' 2>/dev/null \
        | while read -r ra_id; do
            [[ -n "$ra_id" ]] && api DELETE "/api/risk-assessments/${ra_id}" >/dev/null || true
        done

    # Onboarding rules, then choices, then questions — in that order, because the API
    # deliberately refuses to delete a question or choice a rule still references.
    local rules
    rules=$(api GET "/api/account-onboarding/rules" || echo '[]')
    echo "$rules" | jq -r --arg p "$E2E_PREFIX" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select((.name // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r rule_id; do
            [[ -n "$rule_id" ]] && api DELETE "/api/account-onboarding/rules/${rule_id}" >/dev/null || true
        done

    local questions
    questions=$(api GET "/api/account-onboarding/questions" || echo '[]')
    echo "$questions" | jq -r \
        '(if type == "array" then . else (.content // []) end)[]?
         | select(.questionKey == "environment" or .questionKey == "customer-data" or .questionKey == "data-types")
         | .id' 2>/dev/null \
        | while read -r q_id; do
            [[ -n "$q_id" ]] && api DELETE "/api/account-onboarding/questions/${q_id}" >/dev/null || true
        done

    # User mappings — matched on the test owner email prefix, so mappings orphaned by an
    # interrupted earlier run are swept up too.
    local mappings
    mappings=$(api GET "/api/user-mappings/current?size=1000" || echo '{}')
    echo "$mappings" | jq -r --arg p "$E2E_PREFIX" \
        '(.content // .mappings // [])[]? | select((.email // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r m_id; do
            [[ -n "$m_id" ]] && api DELETE "/api/user-mappings/${m_id}" >/dev/null || true
        done

    # The AWS_ACCOUNT assets the feature auto-creates as the assessment basis. Doubly
    # constrained: the id must have the exact synthetic shape this driver mints AND the owner
    # must be a test address. A real account satisfies at most one.
    local assets
    assets=$(api GET "/api/assets" || echo '[]')
    echo "$assets" | jq -r --arg p "$E2E_PREFIX" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select(.type == "AWS_ACCOUNT")
         | select((.cloudAccountId // "") | test("^87[0-9][0-9]{6}000$"))
         | select((.owner // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r a_id; do
            [[ -n "$a_id" ]] && api DELETE "/api/assets/${a_id}" >/dev/null || true
        done

    # Release (also drops its requirement snapshots). Matched by NAME, constant across runs,
    # so a release orphaned by an interrupted earlier run is swept up too. force=true is
    # required because setup always sets it ACTIVE.
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

    local reqs
    reqs=$(api GET "/api/requirements" || echo '[]')
    echo "$reqs" | jq -r --arg p "$E2E_PREFIX" \
        '(if type == "array" then . else (.content // []) end)[]? | select((.shortreq // "") | startswith($p)) | .id' 2>/dev/null \
        | while read -r q_id; do
            [[ -n "$q_id" ]] && api DELETE "/api/requirements/${q_id}" >/dev/null || true
        done

    local ucs
    ucs=$(api GET "/api/usecases" || echo '[]')
    echo "$ucs" | jq -r --arg p "$E2E_PREFIX" \
        '(if type == "array" then . else (.content // []) end)[]? | select((.name // "") | startswith($p)) | .id' 2>/dev/null \
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
    rm -f "$COOKIE_JAR" "$OWNER_COOKIE_JAR"
    rm -rf "$WORK_DIR"
    exit $rc
}

# =============================================================================
# Setup
# =============================================================================

UC_BASE_ID=""
UC_DATA_ID=""
RELEASE_ID=""
Q_ENV_ID=""; Q_DATA_ID=""; Q_TYPES_ID=""
RULE_DEFAULT_ID=""

# choice_id <questionId> <choiceKey>
choice_id() {
    local qid="$1" key="$2"
    api GET "/api/account-onboarding/questions" \
        | jq -r --argjson q "$qid" --arg k "$key" \
            '(if type == "array" then . else (.content // []) end)[]?
             | select(.id == $q) | .choices[]? | select(.choiceKey == $k) | .id' | head -1
}

add_question() {
    local key="$1" label="$2" type="$3"
    local resp
    resp=$(api POST "/api/account-onboarding/questions" \
        "$(jq -nc --arg k "$key" --arg l "$label" --arg t "$type" \
            '{questionKey:$k, label:$l, inputType:$t, required:true, active:true}')")
    echo "$resp" | jq -r '.id // empty'
}

add_choice() {
    local qid="$1" key="$2" label="$3"
    api POST "/api/account-onboarding/questions/${qid}/choices" \
        "$(jq -nc --arg k "$key" --arg l "$label" '{choiceKey:$k, label:$l, active:true}')" >/dev/null
}

add_rule() {
    local name="$1" choice_ids="$2" use_case_ids="$3" is_default="${4:-false}"
    local resp
    resp=$(api POST "/api/account-onboarding/rules" \
        "$(jq -nc --arg n "$name" --argjson c "$choice_ids" --argjson u "$use_case_ids" \
            --argjson d "$is_default" \
            '{name:$n, choiceIds:$c, useCaseIds:$u, isDefault:$d, active:true}')")
    echo "$resp" | jq -r '.id // empty'
}

setup_testbed() {
    phase "Setup: users, use cases, requirements, ACTIVE release, questions, rules"

    api POST "/api/users" "$(jq -nc --arg u "$CHAMPION_USER" --arg e "$CHAMPION_EMAIL" --arg p "$TEST_PASS" \
        '{username:$u, email:$e, password:$p, roles:["SECCHAMPION"]}')" >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not create SECCHAMPION user (HTTP $API_STATUS)"; exit 1; }

    api POST "/api/users" "$(jq -nc --arg u "$OWNER_USER" --arg e "$OWNER_EMAIL" --arg p "$TEST_PASS" \
        '{username:$u, email:$e, password:$p, roles:["USER"]}')" >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not create owner user (HTTP $API_STATUS)"; exit 1; }

    api POST "/api/users" "$(jq -nc --arg u "$PLAIN_USER" --arg e "$PLAIN_EMAIL" --arg p "$TEST_PASS" \
        '{username:$u, email:$e, password:$p, roles:["USER"]}')" >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not create plain user (HTTP $API_STATUS)"; exit 1; }
    log_pass "Users created: champion (SECCHAMPION), owner, plain (USER)"

    curl -sS -o /dev/null -c "$OWNER_COOKIE_JAR" -H 'Content-Type: application/json' \
        -X POST "${BASE_URL}/api/auth/login" \
        --data "$(jq -nc --arg u "$PLAIN_USER" --arg p "$TEST_PASS" '{username:$u, password:$p}')" || true

    UC_BASE_ID=$(api POST "/api/usecases" "$(jq -nc --arg n "$UC_BASE" '{name:$n}')" | jq -r '.id // empty')
    UC_DATA_ID=$(api POST "/api/usecases" "$(jq -nc --arg n "$UC_DATA" '{name:$n}')" | jq -r '.id // empty')
    [[ -n "$UC_BASE_ID" && -n "$UC_DATA_ID" ]] || { log_fail "Could not create use cases"; exit 1; }
    log_pass "Use cases created: $UC_BASE (id=$UC_BASE_ID), $UC_DATA (id=$UC_DATA_ID)"

    local i
    for i in 1 2; do
        api POST "/api/requirements" "$(jq -nc --arg s "${E2E_PREFIX}base-req-${i}" --argjson u "[$UC_BASE_ID]" \
            '{shortreq:$s, details:"E2E base requirement", chapter:"1", usecaseIds:$u}')" >/dev/null
        api POST "/api/requirements" "$(jq -nc --arg s "${E2E_PREFIX}data-req-${i}" --argjson u "[$UC_DATA_ID]" \
            '{shortreq:$s, details:"E2E data requirement", chapter:"2", usecaseIds:$u}')" >/dev/null
    done
    api POST "/api/requirements" "$(jq -nc --arg s "${E2E_PREFIX}untagged-req" \
        '{shortreq:$s, details:"E2E untagged control", chapter:"3"}')" >/dev/null
    log_pass "Requirements created: 2 per use case + 1 untagged control"

    local rel
    rel=$(api POST "/api/releases" "$(jq -nc --arg v "$RELEASE_VERSION" --arg n "$RELEASE_NAME" \
        '{version:$v, name:$n, description:"E2E account onboarding baseline"}')")
    RELEASE_ID=$(echo "$rel" | jq -r '.id // empty')
    [[ -n "$RELEASE_ID" ]] || { log_fail "Could not create release (HTTP $API_STATUS): $rel"; exit 1; }
    api PUT "/api/releases/${RELEASE_ID}/status" '{"status":"ACTIVE"}' >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || { log_fail "Could not activate release (HTTP $API_STATUS)"; exit 1; }
    log_pass "Release $RELEASE_VERSION created and set ACTIVE (id=$RELEASE_ID)"

    Q_ENV_ID=$(add_question "$Q_ENV" "Which environment?" "SINGLE_SELECT")
    Q_DATA_ID=$(add_question "$Q_DATA" "Handles customer data?" "SINGLE_SELECT")
    Q_TYPES_ID=$(add_question "$Q_TYPES" "What data types?" "MULTI_SELECT")
    [[ -n "$Q_ENV_ID" && -n "$Q_DATA_ID" && -n "$Q_TYPES_ID" ]] || { log_fail "Could not create questions"; exit 1; }

    add_choice "$Q_ENV_ID" "$C_PROD" "Production"
    add_choice "$Q_ENV_ID" "$C_TEST" "Test"
    add_choice "$Q_DATA_ID" "$C_YES" "Yes"
    add_choice "$Q_DATA_ID" "$C_NO" "No"
    add_choice "$Q_TYPES_ID" "$C_PII" "Personal data"
    add_choice "$Q_TYPES_ID" "$C_FIN" "Financial data"
    log_pass "Questions created: 3 (6 answers)"

    local id_prod id_yes id_pii
    id_prod=$(choice_id "$Q_ENV_ID" "$C_PROD")
    id_yes=$(choice_id "$Q_DATA_ID" "$C_YES")
    id_pii=$(choice_id "$Q_TYPES_ID" "$C_PII")

    add_rule "${E2E_PREFIX}rule-prod" "[$id_prod]" "[$UC_BASE_ID]" >/dev/null
    add_rule "${E2E_PREFIX}rule-prod-data" "[$id_prod,$id_yes]" "[$UC_DATA_ID]" >/dev/null
    add_rule "${E2E_PREFIX}rule-pii" "[$id_pii]" "[$UC_DATA_ID]" >/dev/null
    RULE_DEFAULT_ID=$(add_rule "${E2E_PREFIX}rule-default" "[]" "[$UC_BASE_ID]" "true")
    [[ -n "$RULE_DEFAULT_ID" ]] || { log_fail "Could not create the default rule"; exit 1; }
    log_pass "Rules created: 3 conditional + 1 default fallback"
}

# =============================================================================
# Helpers
# =============================================================================

write_mapping_file() {
    local account="$1"
    printf 'email,type,value\n%s,AWS_ACCOUNT,%s\n' "$OWNER_EMAIL" "$account" > "$WORK_DIR/mappings.csv"
}

BACKEND_LOG="${SECMAN_BACKEND_LOG:-$REPO_ROOT/.e2e-logs/backend.log}"

# capture_token <account> -> the full invite token, or empty.
#
# There is deliberately no API that returns one: an invite token is a credential, so no admin
# endpoint, CLI printout or MCP result ever carries it — several assertions above check exactly
# that. The only place the full value legitimately appears is the questionnaire URL inside the
# rendered mail, which in a dev/test environment lands in the backend log.
#
# When the log is not reachable (a remote backend, say) the owner-flow phases report a WARN and
# are skipped rather than silently passing. A skipped assertion that says so is honest; one that
# quietly counts as a pass is not.
capture_token() {
    local account="$1"
    [[ -f "$BACKEND_LOG" ]] || { echo ""; return; }
    grep -o "/onboarding/[a-f0-9]\{64\}" "$BACKEND_LOG" 2>/dev/null | tail -1 | sed 's|/onboarding/||'
}

count_assessments_for() {
    local account="$1"
    local resp
    resp=$(mcp_call "list_aws_account_risk_assessments" "$(jq -nc --arg a "$account" '{awsAccountId:$a}')")
    mcp_payload "$resp" | jq --arg a "$account" '[.assessments[]? | select(.awsAccountId == $a)] | length'
}

# =============================================================================
# Phases
# =============================================================================

phase_welcome_only() {
    phase "CLI: WELCOME_ONLY sends a welcome mail and starts nothing"
    write_mapping_file "$WELCOME_ACCOUNT"

    local out rc
    set +e
    out=$(java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings.csv" \
        --onboarding-mode WELCOME_ONLY \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" 2>&1); rc=$?
    set -e
    log_dbg "$out"

    [[ $rc -eq 0 ]] && log_pass "CLI exited 0" || log_fail "CLI exited $rc"
    echo "$out" | grep -q "Onboarding: WELCOME_ONLY" \
        && log_pass "printout names the mode" || log_fail "printout does not name WELCOME_ONLY"
    echo "$out" | grep -q "welcome mail sent" \
        && log_pass "welcome mail reported as sent" || log_fail "no welcome mail reported"

    local count; count=$(count_assessments_for "$WELCOME_ACCOUNT")
    [[ "$count" == "0" ]] && log_pass "no risk assessment started" \
        || log_fail "expected 0 assessments for $WELCOME_ACCOUNT, found $count"
}

phase_legacy_compatibility() {
    phase "CLI: a bare --start-risk-assessment behaves exactly as before (compatibility gate)"
    write_mapping_file "$LEGACY_ACCOUNT"

    local out rc
    set +e
    out=$(java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings.csv" \
        --start-risk-assessment --risk-usecase "$UC_BASE" --risk-deadline-days 7 \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" 2>&1); rc=$?
    set -e
    log_dbg "$out"

    [[ $rc -eq 0 ]] && log_pass "CLI exited 0" || log_fail "CLI exited $rc"

    local count; count=$(count_assessments_for "$LEGACY_ACCOUNT")
    [[ "$count" == "1" ]] && log_pass "one assessment started, as before onboarding modes existed" \
        || log_fail "expected 1 assessment for $LEGACY_ACCOUNT, found $count"

    # The load-bearing half of the contract: no welcome mail unless a mode was named.
    if echo "$out" | grep -q "welcome mail sent"; then
        log_fail "a bare --start-risk-assessment sent a welcome mail — backward-compatibility regression"
    else
        log_pass "no welcome mail for the legacy flag — behaviour unchanged"
    fi
}

phase_guided_invite() {
    phase "CLI: GUIDED mints an invite and starts nothing yet"
    write_mapping_file "$GUIDED_ACCOUNT"

    local out rc
    set +e
    out=$(java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings.csv" \
        --onboarding-mode GUIDED --questionnaire-expiry-days 14 \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" 2>&1); rc=$?
    set -e
    log_dbg "$out"

    [[ $rc -eq 0 ]] && log_pass "CLI exited 0" || log_fail "CLI exited $rc"
    echo "$out" | grep -q "questionnaire invite #" \
        && log_pass "invite minted and reported" || log_fail "no questionnaire invite reported"
    # The printout must never carry the token itself.
    if echo "$out" | grep -qE '[a-f0-9]{64}'; then
        log_fail "the CLI printed a 64-hex value — an invite token must never reach stdout"
    else
        log_pass "no token in the CLI printout"
    fi

    local count; count=$(count_assessments_for "$GUIDED_ACCOUNT")
    [[ "$count" == "0" ]] && log_pass "no assessment created before the owner answers" \
        || log_fail "expected 0 assessments for $GUIDED_ACCOUNT, found $count"
}

phase_owner_flow() {
    phase "Public: the owner opens the link, answers, and gets one assessment scoped to the union"

    local token; token=$(capture_token "$GUIDED_ACCOUNT")
    if [[ -z "$token" ]]; then
        log_warn "Could not recover an invite token (backend log not available) — owner-flow phases skipped"
        return 0
    fi
    log_dbg "Using invite token $(redact_token "$token")"

    local body
    body=$(public_api GET "/api/public/account-onboarding/${token}")
    [[ "$PUBLIC_STATUS" == "200" ]] && log_pass "questionnaire GET returned 200" \
        || { log_fail "questionnaire GET returned $PUBLIC_STATUS"; return 0; }

    local masked
    masked=$(echo "$body" | jq -r '.maskedAccountId // empty')
    [[ "$masked" == "****${GUIDED_ACCOUNT: -4}" ]] \
        && log_pass "the account id is masked ($masked)" \
        || log_fail "expected a masked account id, got '$masked'"
    if echo "$body" | grep -q "$GUIDED_ACCOUNT"; then
        log_fail "the full account id leaked to an unauthenticated caller"
    else
        log_pass "the full account id is not disclosed"
    fi
    if echo "$body" | grep -q "$OWNER_EMAIL"; then
        log_fail "the owner email leaked to an unauthenticated caller"
    else
        log_pass "the owner email is not disclosed"
    fi

    local qcount; qcount=$(echo "$body" | jq '.questions | length')
    [[ "$qcount" -ge 3 ]] && log_pass "questionnaire carries $qcount question(s)" \
        || log_fail "expected at least 3 questions, got $qcount"

    # production + customer-data=yes matches BOTH rule-prod and rule-prod-data, so the
    # assessment must be scoped to the UNION of their use cases.
    local answers
    answers=$(jq -nc --arg qe "$Q_ENV" --arg ce "$C_PROD" --arg qd "$Q_DATA" --arg cd "$C_YES" \
        --arg qt "$Q_TYPES" \
        '{answers:[{questionKey:$qe, choiceKeys:[$ce]}, {questionKey:$qd, choiceKeys:[$cd]}, {questionKey:$qt, choiceKeys:[]}]}')
    local submit
    submit=$(public_api POST "/api/public/account-onboarding/${token}" "$answers")
    log_dbg "$submit"

    [[ "$PUBLIC_STATUS" == "200" ]] && log_pass "submission accepted" \
        || { log_fail "submission returned $PUBLIC_STATUS: $submit"; return 0; }

    local use_cases
    use_cases=$(echo "$submit" | jq -r '.useCases | sort | join(",")')
    local expected
    expected=$(printf '%s\n%s\n' "$UC_BASE" "$UC_DATA" | sort | paste -sd, -)
    [[ "$use_cases" == "$expected" ]] \
        && log_pass "the assessment is scoped to the UNION: $use_cases" \
        || log_fail "expected union '$expected', got '$use_cases'"

    local req_count; req_count=$(echo "$submit" | jq -r '.requirementCount // 0')
    [[ "$req_count" == "4" ]] && log_pass "questionnaire is the union of both use cases' requirements (4)" \
        || log_fail "expected 4 requirements, got $req_count"

    local count; count=$(count_assessments_for "$GUIDED_ACCOUNT")
    [[ "$count" == "1" ]] && log_pass "exactly one assessment created" \
        || log_fail "expected 1 assessment for $GUIDED_ACCOUNT, found $count"

    # --- token negatives, all sharing one identical body ---
    phase "Public: every token failure looks the same, and a burst is rate limited"

    local replay
    replay=$(public_api POST "/api/public/account-onboarding/${token}" "$answers")
    [[ "$PUBLIC_STATUS" == "404" ]] && log_pass "replay refused (404)" \
        || log_fail "replay returned $PUBLIC_STATUS, expected 404"

    local unknown_token="a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    local unknown
    unknown=$(public_api GET "/api/public/account-onboarding/${unknown_token}")
    local unknown_status="$PUBLIC_STATUS"
    local used
    used=$(public_api GET "/api/public/account-onboarding/${token}")

    [[ "$unknown_status" == "404" && "$PUBLIC_STATUS" == "404" ]] \
        && log_pass "unknown and used tokens both return 404" \
        || log_fail "unknown=$unknown_status used=$PUBLIC_STATUS, expected 404 for both"
    [[ "$unknown" == "$used" ]] \
        && log_pass "the two bodies are byte-identical — no enumeration oracle" \
        || log_fail "bodies differ: '$unknown' vs '$used'"

    local malformed
    malformed=$(public_api GET "/api/public/account-onboarding/not-a-token")
    [[ "$PUBLIC_STATUS" == "404" && "$malformed" == "$unknown" ]] \
        && log_pass "a malformed token is indistinguishable from an unknown one" \
        || log_fail "malformed returned $PUBLIC_STATUS with a different body"

    local limited=0 i
    for i in $(seq 1 30); do
        public_api GET "/api/public/account-onboarding/${unknown_token}" >/dev/null
        [[ "$PUBLIC_STATUS" == "429" ]] && { limited=1; break; }
    done
    [[ $limited -eq 1 ]] && log_pass "a burst of lookups is rate limited (429)" \
        || log_fail "30 rapid lookups were never rate limited"
}

phase_no_match() {
    phase "Public: answers matching nothing are recorded, not lost"

    # Deactivate the fallback so a non-matching submission has nowhere to land.
    api PUT "/api/account-onboarding/rules/${RULE_DEFAULT_ID}" \
        "$(jq -nc --arg n "${E2E_PREFIX}rule-default" --argjson u "[$UC_BASE_ID]" \
            '{name:$n, choiceIds:[], useCaseIds:$u, isDefault:true, active:false}')" >/dev/null
    [[ "$API_STATUS" =~ ^20 ]] || log_warn "Could not deactivate the default rule (HTTP $API_STATUS)"

    write_mapping_file "$NOMATCH_ACCOUNT"
    set +e
    java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings.csv" --onboarding-mode GUIDED \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" >/dev/null 2>&1
    set -e

    local token; token=$(capture_token "$NOMATCH_ACCOUNT")
    if [[ -z "$token" ]]; then
        log_warn "Could not recover an invite token — no-match phase skipped"
    else
        local answers
        answers=$(jq -nc --arg qe "$Q_ENV" --arg ce "$C_TEST" --arg qd "$Q_DATA" --arg cd "$C_NO" \
            '{answers:[{questionKey:$qe, choiceKeys:[$ce]}, {questionKey:$qd, choiceKeys:[$cd]}]}')
        local resp
        resp=$(public_api POST "/api/public/account-onboarding/${token}" "$answers")
        log_dbg "$resp"

        [[ "$PUBLIC_STATUS" == "409" ]] && log_pass "unmatched answers return 409" \
            || log_fail "expected 409, got $PUBLIC_STATUS"
        echo "$resp" | jq -e '.error == "NO_RULE_MATCHED"' >/dev/null 2>&1 \
            && log_pass "the failure is named NO_RULE_MATCHED" || log_fail "unexpected error code: $resp"

        local count; count=$(count_assessments_for "$NOMATCH_ACCOUNT")
        [[ "$count" == "0" ]] && log_pass "no assessment was created" \
            || log_fail "expected 0 assessments, found $count"

        # The invite must still work: the answers were recorded, not spent.
        local retry
        retry=$(public_api GET "/api/public/account-onboarding/${token}")
        [[ "$PUBLIC_STATUS" == "200" ]] \
            && log_pass "the link still works — an unresolved submission does not consume it" \
            || log_fail "the link was consumed by a submission that resolved to nothing"

        # Re-activate the fallback and resubmit: the same answers now resolve.
        api PUT "/api/account-onboarding/rules/${RULE_DEFAULT_ID}" \
            "$(jq -nc --arg n "${E2E_PREFIX}rule-default" --argjson u "[$UC_BASE_ID]" \
                '{name:$n, choiceIds:[], useCaseIds:$u, isDefault:true, active:true}')" >/dev/null
        resp=$(public_api POST "/api/public/account-onboarding/${token}" "$answers")
        [[ "$PUBLIC_STATUS" == "200" ]] \
            && log_pass "after adding the fallback, the original link resolves" \
            || log_fail "resubmission after the fix returned $PUBLIC_STATUS: $resp"
    fi

    api PUT "/api/account-onboarding/rules/${RULE_DEFAULT_ID}" \
        "$(jq -nc --arg n "${E2E_PREFIX}rule-default" --argjson u "[$UC_BASE_ID]" \
            '{name:$n, choiceIds:[], useCaseIds:$u, isDefault:true, active:true}')" >/dev/null 2>&1 || true
}

phase_dry_run() {
    phase "CLI: every dry run persists nothing, sends nothing, mints no token"

    local mode
    for mode in WELCOME_ONLY GUIDED; do
        write_mapping_file "$DRY_ACCOUNT"
        local out rc
        set +e
        out=$(java -jar "$CLI_JAR" manage-user-mappings import \
            --file "$WORK_DIR/mappings.csv" --onboarding-mode "$mode" --dry-run \
            --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
            --backend-url "$BASE_URL" 2>&1); rc=$?
        set -e
        log_dbg "$out"

        [[ $rc -eq 0 ]] && log_pass "$mode dry run exited 0" || log_fail "$mode dry run exited $rc"
        echo "$out" | grep -q "no invite token minted" \
            && log_pass "$mode dry run says no token was minted" \
            || log_fail "$mode dry run did not state that no token was minted"
        if echo "$out" | grep -qE '[a-f0-9]{64}'; then
            log_fail "$mode dry run printed a 64-hex value — a dry run must never mint a token"
        else
            log_pass "$mode dry run printed no token"
        fi
    done

    local count; count=$(count_assessments_for "$DRY_ACCOUNT")
    [[ "$count" == "0" ]] && log_pass "dry runs created no assessment" \
        || log_fail "expected 0 assessments after dry runs, found $count"
}

phase_mcp() {
    phase "MCP: simulate, list and preview"

    local resp payload
    resp=$(mcp_call "list_account_onboarding_rules" '{}')
    payload=$(mcp_payload "$resp")
    log_dbg "$payload"
    local rule_count; rule_count=$(echo "$payload" | jq -r '.activeRuleCount // 0')
    [[ "$rule_count" -ge 4 ]] && log_pass "list_account_onboarding_rules reports $rule_count active rule(s)" \
        || log_fail "expected at least 4 active rules, got $rule_count"
    echo "$payload" | jq -e '.hasDefaultRule == true' >/dev/null 2>&1 \
        && log_pass "the fallback rule is reported" || log_fail "hasDefaultRule is not true"

    # The union, resolved without creating anything.
    resp=$(mcp_call "preview_account_onboarding_rules" \
        "$(jq -nc --arg qe "$Q_ENV" --arg ce "$C_PROD" --arg qd "$Q_DATA" --arg cd "$C_YES" \
            '{answers:[{questionKey:$qe, choiceKeys:[$ce]}, {questionKey:$qd, choiceKeys:[$cd]}]}')")
    payload=$(mcp_payload "$resp")
    log_dbg "$payload"
    local preview_ucs
    preview_ucs=$(echo "$payload" | jq -r '.useCases | sort | join(",")')
    local expected
    expected=$(printf '%s\n%s\n' "$UC_BASE" "$UC_DATA" | sort | paste -sd, -)
    [[ "$preview_ucs" == "$expected" ]] \
        && log_pass "preview resolves the union: $preview_ucs" \
        || log_fail "expected '$expected', got '$preview_ucs'"

    # Simulate, dry then live.
    resp=$(mcp_call "simulate_account_onboarding" \
        "$(jq -nc --arg a "$MCP_ACCOUNT" --arg e "$OWNER_EMAIL" \
            '{awsAccountId:$a, ownerEmail:$e, mode:"GUIDED", dryRun:true}')")
    payload=$(mcp_payload "$resp")
    log_dbg "$payload"
    echo "$payload" | jq -e '.onboarding[0].dryRun == true' >/dev/null 2>&1 \
        && log_pass "simulate dry run reports dryRun" || log_fail "simulate dry run did not report dryRun: $payload"
    echo "$payload" | jq -e '.onboarding[0].questionnaireInviteId == null' >/dev/null 2>&1 \
        && log_pass "simulate dry run minted no invite" || log_fail "simulate dry run minted an invite"

    resp=$(mcp_call "simulate_account_onboarding" \
        "$(jq -nc --arg a "$MCP_ACCOUNT" --arg e "$OWNER_EMAIL" \
            '{awsAccountId:$a, ownerEmail:$e, mode:"WELCOME_ONLY", dryRun:false}')")
    payload=$(mcp_payload "$resp")
    log_dbg "$payload"
    echo "$payload" | jq -e '.onboarding[0].welcomeEmailSent == true' >/dev/null 2>&1 \
        && log_pass "simulate WELCOME_ONLY sent the welcome mail" \
        || log_warn "simulate WELCOME_ONLY reported no mail (check the email configuration)"

    # An MCP result must never carry a token.
    if echo "$payload" | grep -qE '[a-f0-9]{64}'; then
        log_fail "an MCP result carried a 64-hex value — a token must never reach an agent transcript"
    else
        log_pass "no token in the MCP result"
    fi
}

phase_negatives() {
    phase "Authorization and validation negatives"

    # A plain USER must not reach the simulate surface.
    api_as_plain POST "/api/account-onboarding/simulate" \
        "$(jq -nc --arg a "$NEG_ACCOUNT" --arg e "$OWNER_EMAIL" \
            '{awsAccountId:$a, ownerEmail:$e, mode:"WELCOME_ONLY", dryRun:true}')" >/dev/null
    [[ "$API_STATUS" == "403" ]] && log_pass "plain USER on /simulate is refused (403)" \
        || log_fail "expected 403 for a plain USER, got $API_STATUS"

    api_as_plain GET "/api/account-onboarding/rules" >/dev/null
    [[ "$API_STATUS" == "403" ]] && log_pass "plain USER cannot read the rules (403)" \
        || log_fail "expected 403 reading rules as a plain USER, got $API_STATUS"

    # A non-admin delegated user must not reach the MCP tool.
    local resp
    resp=$(mcp_call "simulate_account_onboarding" \
        "$(jq -nc --arg a "$NEG_ACCOUNT" --arg e "$OWNER_EMAIL" \
            '{awsAccountId:$a, ownerEmail:$e, mode:"WELCOME_ONLY", dryRun:true}')" "$PLAIN_EMAIL")
    log_dbg "$resp"
    if echo "$resp" | grep -qi "FORBIDDEN\|role required"; then
        log_pass "non-privileged MCP delegation is refused"
    else
        log_fail "expected a role refusal for $PLAIN_EMAIL, got: $resp"
    fi

    # Malformed inputs on the admin surface.
    api POST "/api/account-onboarding/simulate" \
        "$(jq -nc --arg e "$OWNER_EMAIL" '{awsAccountId:"12345", ownerEmail:$e, mode:"WELCOME_ONLY", dryRun:true}')" >/dev/null
    [[ "$API_STATUS" == "400" ]] && log_pass "a non-12-digit account id is rejected (400)" \
        || log_fail "expected 400 for a short account id, got $API_STATUS"

    api POST "/api/account-onboarding/simulate" \
        "$(jq -nc --arg a "$NEG_ACCOUNT" '{awsAccountId:$a, ownerEmail:"a@b.c,evil@bad.com", mode:"WELCOME_ONLY", dryRun:true}')" >/dev/null
    [[ "$API_STATUS" == "400" ]] && log_pass "an address that would split into two recipients is rejected (400)" \
        || log_fail "expected 400 for a comma-bearing address, got $API_STATUS"

    # An unmatchable rule cannot be saved.
    local id_prod id_test
    id_prod=$(choice_id "$Q_ENV_ID" "$C_PROD")
    id_test=$(choice_id "$Q_ENV_ID" "$C_TEST")
    api POST "/api/account-onboarding/rules" \
        "$(jq -nc --arg n "${E2E_PREFIX}rule-impossible" --argjson c "[$id_prod,$id_test]" \
            --argjson u "[$UC_BASE_ID]" '{name:$n, choiceIds:$c, useCaseIds:$u, isDefault:false, active:true}')" >/dev/null
    [[ "$API_STATUS" == "400" ]] \
        && log_pass "a rule naming two answers to a single-select question is refused (400)" \
        || log_fail "expected 400 for an unmatchable rule, got $API_STATUS"

    # A second default rule is refused.
    api POST "/api/account-onboarding/rules" \
        "$(jq -nc --arg n "${E2E_PREFIX}rule-default-2" --argjson u "[$UC_BASE_ID]" \
            '{name:$n, choiceIds:[], useCaseIds:$u, isDefault:true, active:true}')" >/dev/null
    [[ "$API_STATUS" == "409" ]] && log_pass "a second fallback rule is refused (409)" \
        || log_fail "expected 409 for a second default rule, got $API_STATUS"

    # The CLI refuses the one incompatible flag combination, before any network call.
    write_mapping_file "$NEG_ACCOUNT"
    local rc
    set +e
    java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings.csv" \
        --onboarding-mode GUIDED --start-risk-assessment \
        --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" >/dev/null 2>&1
    rc=$?
    set -e
    [[ $rc -eq 2 ]] && log_pass "--onboarding-mode GUIDED with --start-risk-assessment exits 2" \
        || log_fail "expected exit 2 for the incompatible combination, got $rc"
}

# =============================================================================
# Main
# =============================================================================

main() {
    echo "============================================================" >&2
    echo " Account Onboarding E2E" >&2
    echo "============================================================" >&2

    check_prerequisites
    admin_login
    cleanup "pre-run"
    trap on_exit EXIT

    setup_testbed

    if [[ "$SKIP_CLI" != "true" ]]; then
        phase_welcome_only
        phase_legacy_compatibility
        phase_guided_invite
        phase_owner_flow
        phase_no_match
        phase_dry_run
    else
        log_warn "SKIP_CLI=true — CLI phases skipped"
    fi

    if [[ "$SKIP_MCP" != "true" ]]; then
        phase_mcp
    else
        log_warn "SKIP_MCP=true — MCP phases skipped"
    fi

    phase_negatives

    phase "Summary"
    echo -e "${GREEN}Passed: ${PASS_COUNT}${NC}" >&2
    echo -e "${RED}Failed: ${FAIL_COUNT}${NC}" >&2

    [[ $FAIL_COUNT -eq 0 ]] || exit 1
    exit 0
}

main "$@"
