#!/usr/bin/env bash
#
# E2E: the AWS account owner is emailed when a mapping import discovers a new account.
#
# The feature under test is the *notification*, not the assessment. Importing a
# mapping for an AWS account SecMan has never seen starts a risk assessment for the
# mapped owner (see docs/AWS_ACCOUNT_RISK_ASSESSMENT.md) and mails that owner:
#
#     Subject: Risk assessment started for your AWS account <accountId>
#
# Why this needs its own driver. The send is deliberately best-effort:
# AwsAccountRiskAssessmentService only logs a warning when the notification throws,
# and EmailService.sendEmail returns false (no exception) when no SMTP config is
# active. An import therefore reports complete success while zero mail leaves the
# building, so "the assessment exists" proves nothing about delivery. The start
# notification also goes through sendEmail() rather than sendNotificationEmail(),
# so it writes NO row to email_notification_logs and /api/notification-logs cannot
# see it. The only machine-checkable evidence of a send is the backend log line
# EmailService emits at INFO on success:
#
#     Successfully sent email to <to> with subject: <subject>
#
# So this driver asserts on the backend log within a per-import byte window, and
# then prints an inbox checklist for a human to confirm actual delivery.
#
# Non-destructive by design. Two hazards this script refuses to walk into:
#   1. The recipient address usually belongs to a REAL user (often the tester's own
#      account). No user is ever created or deleted for it. None is needed: the
#      owner becomes the assessment's respondent only when a user with that email
#      happens to exist, and the mail is sent either way.
#   2. Activating a release ARCHIVES the previously ACTIVE one, and ARCHIVED is
#      terminal — the environment's real requirements baseline could never be
#      restored. So an existing ACTIVE release is REUSED. Seeding and activating a
#      release of our own happens only when none is ACTIVE, or on explicit opt-in
#      via ALLOW_RELEASE_ACTIVATION=true.
#
# Testbed (everything we create carries E2E_PREFIX so cleanup is exact):
#   User:      <prefix>champion (SECCHAMPION) — an assessor must exist
#   Accounts:  two never-before-seen 12-digit IDs (CLI phase, MCP phase)
#   Assets:    "AWS Account <id>", created by the feature itself
#   Seeded only when no ACTIVE release exists: <prefix>usecase, 2 tagged
#              requirements, release 98.<suffix>.0 set ACTIVE
#
# What it proves:
#   1. A CLI import of a new account sends the owner mail, to the given address
#   2. An MCP import over POST /mcp does the same
#   3. The subject names the right account and the send reports success
#   4. Neither run logs "No active email configuration" or a send failure
#   5. The assessment survives regardless (failure isolation)
#
# Cleanup runs both before (unconditional) and after (trap EXIT).
#
# Required env (resolved via pass-cli):
#   SECMAN_ADMIN_NAME
#   SECMAN_ADMIN_PASS
#   SECMAN_ADMIN_EMAIL
#   SECMAN_MCP_KEY                (MCP phase; absent -> phase skipped)
# Additional env:
#   RECIPIENT_EMAIL               the inbox the test mail must land in (required)
#   BASE_URL or SECMAN_BACKEND_URL  backend URL; never a localhost literal
#   BACKEND_LOG                   default .e2e-logs/backend.log
#   ALLOW_RELEASE_ACTIVATION      true -> may archive an existing ACTIVE release
#   ALLOW_PLACEHOLDER_RECIPIENT   true -> accept a reserved domain (local mail sink)
#   SKIP_CLI=true / SKIP_MCP=true / VERBOSE=true
#
# Usage:
#   pass-cli run --env-file ./secmanpp.env -- \
#     ./scripts/test/test-e2e-aws-account-owner-email.sh --email you@example.com --verbose
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../../tests/lib/secman-test-tls.sh
source "$REPO_ROOT/tests/lib/secman-test-tls.sh"

# =============================================================================
# Configuration
# =============================================================================

RECIPIENT_EMAIL="${RECIPIENT_EMAIL:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --email)        RECIPIENT_EMAIL="${2:-}"; shift 2 ;;
        --email=*)      RECIPIENT_EMAIL="${1#*=}"; shift ;;
        --backend-log)  BACKEND_LOG="${2:-}"; shift 2 ;;
        --backend-log=*) BACKEND_LOG="${1#*=}"; shift ;;
        --verbose|-v)   VERBOSE=true; shift ;;
        --skip-cli)     SKIP_CLI=true; shift ;;
        --skip-mcp)     SKIP_MCP=true; shift ;;
        *)              shift ;;
    esac
done

BASE_URL="${BASE_URL:-${SECMAN_BACKEND_URL:-}}"
BACKEND_LOG="${BACKEND_LOG:-$REPO_ROOT/.e2e-logs/backend.log}"
VERBOSE="${VERBOSE:-false}"
SKIP_CLI="${SKIP_CLI:-false}"
SKIP_MCP="${SKIP_MCP:-false}"
ALLOW_RELEASE_ACTIVATION="${ALLOW_RELEASE_ACTIVATION:-false}"
ALLOW_PLACEHOLDER_RECIPIENT="${ALLOW_PLACEHOLDER_RECIPIENT:-false}"

STAMP="$(date +%s)"
SUFFIX="${STAMP: -6}"
E2E_PREFIX="e2e-awsmail-"

CHAMPION_USER="${E2E_PREFIX}champion"
CHAMPION_EMAIL="${CHAMPION_USER}@e2e.local"
TEST_PASS="E2eAwsMail!${SUFFIX}"

# 884/885 prefixes keep these clear of real accounts AND of the 886/887 pair used
# by test-e2e-aws-account-risk-assessment.sh, so the two drivers never collide.
CLI_ACCOUNT="884${SUFFIX}000"
MCP_ACCOUNT="885${SUFFIX}000"

# Only used on the seeding path (no ACTIVE release present).
SEEDED_USECASE_NAME="${E2E_PREFIX}usecase"
SEEDED_RELEASE_VERSION="98.${SUFFIX}.0"
SEEDED_RELEASE_NAME="${E2E_PREFIX}release"

# Resolved at setup: the standard the assessments are measured against.
USECASE_NAME=""
RELEASE_VERSION=""
DID_SEED_RELEASE=false

DEADLINE_DAYS=7

CLI_JAR="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"
COOKIE_JAR="$(mktemp)"
WORK_DIR="$(mktemp -d)"

# How long to wait for the send line to reach the log. The SMTP socket timeout is
# 30s (EmailService.createMailProperties), so a slow-but-successful send can take
# that long to be reported.
LOG_WAIT_SECONDS=40

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

    # The whole point of the run is that a human can check this mailbox afterwards,
    # so an unusable address is a setup error worth catching before anything is created.
    if [[ -z "$RECIPIENT_EMAIL" ]]; then
        log_fail "No recipient address. Pass --email <you@example.com> or set RECIPIENT_EMAIL."
        log_info "The test mail is delivered to that address for real; use a mailbox you can read."
        exit 1
    fi
    if [[ ! "$RECIPIENT_EMAIL" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
        log_fail "Recipient address does not look like an email: '$RECIPIENT_EMAIL'"
        exit 1
    fi

    # A syntactically valid placeholder is the dangerous input here, not an invalid
    # one. Most relays accept mail for example.com and the log then reads
    # "Successfully sent" — a green run that nobody can verify, which is exactly the
    # false pass this driver exists to prevent. RFC 2606 reserves these names.
    local domain; domain="$(lower "${RECIPIENT_EMAIL##*@}")"
    case "$domain" in
        example.com|example.org|example.net|example.edu|*.example|\
        *.test|*.invalid|*.localhost|*.local|localhost|e2e.local)
            if [[ "$ALLOW_PLACEHOLDER_RECIPIENT" == "true" ]]; then
                log_warn "Recipient domain '$domain' is a reserved placeholder — allowed by ALLOW_PLACEHOLDER_RECIPIENT"
                log_warn "The inbox check at the end cannot be performed; the log assertion is all you get"
            else
                log_fail "Recipient domain '$domain' is a reserved placeholder domain"
                log_info "Mail to it goes nowhere a human can read, so a passing run would prove nothing."
                log_info "Use a real mailbox you can open. If you are deliberately targeting a local"
                log_info "mail sink (MailHog, Mailpit), re-run with ALLOW_PLACEHOLDER_RECIPIENT=true."
                exit 1
            fi
            ;;
    esac

    log_pass "Recipient: $RECIPIENT_EMAIL"

    if [[ ! -r "$BACKEND_LOG" ]]; then
        log_fail "Backend log not readable at $BACKEND_LOG"
        log_info "The send assertion reads it. Start the backend as:"
        log_info "  mkdir -p .e2e-logs && nohup ./scripts/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &"
        log_info "or point --backend-log at wherever it writes."
        exit 1
    fi

    if [[ "$SKIP_MCP" != "true" && -z "${SECMAN_MCP_KEY:-}" ]]; then
        log_warn "SECMAN_MCP_KEY not set — skipping the MCP phase"
        SKIP_MCP=true
    fi

    if [[ "$SKIP_CLI" != "true" && ! -f "$CLI_JAR" ]]; then
        log_info "CLI jar missing, building it (./gradlew :cli:shadowJar) …"
        (cd "$REPO_ROOT" && ./gradlew :cli:shadowJar -q) || { log_fail "CLI jar build failed"; exit 1; }
    fi

    log_info "Backend:     $BASE_URL"
    log_info "Backend log: $BACKEND_LOG"
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

# api <METHOD> <path> [json-body] -> body on stdout, HTTP code via api_status
#
# The status goes through a FILE, not a variable. `body=$(api GET /x)` runs api in
# a subshell, so a plain `API_STATUS=…` assignment inside it is discarded and the
# caller reads an empty string — which silently passes every `=~ ^20` test as a
# failure. A file survives the subshell, so both call styles behave the same:
#     api POST /x "$body" >/dev/null ;  [[ "$(api_status)" =~ ^20 ]]
#     resp=$(api GET /x)            ;  [[ "$(api_status)" =~ ^20 ]]
API_STATUS_FILE="$(mktemp)"
api() {
    local method="$1" path="$2" body="${3:-}"
    local out; out="$(mktemp)"
    local args=(-sS -o "$out" -w '%{http_code}' -b "$COOKIE_JAR" -X "$method" "${BASE_URL}${path}")
    [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
    curl "${args[@]}" > "$API_STATUS_FILE" || printf '000' > "$API_STATUS_FILE"
    cat "$out"
    rm -f "$out"
}

api_status() { cat "$API_STATUS_FILE" 2>/dev/null; }

# api_create <METHOD> <path> <body> -> body on stdout; 0 when the write succeeded.
#
# Tolerates out-of-sync id sequences, which long-lived dev databases grow. Most core
# entities (UseCase, Requirement, Release, RiskAssessment, Asset, User) declare a
# bare @GeneratedValue, so Hibernate allocates ids from a per-entity sequence table
# instead of the column's AUTO_INCREMENT. Where rows were loaded out of band that
# sequence sits behind the real max(id), and the insert fails with
#   Duplicate entry '<n>' for key 'PRIMARY'
# Each failed attempt advances the sequence, so a bounded retry walks past the ids
# already taken.
#
# Only a PRIMARY-key duplicate is retried. A unique-name conflict reports a
# different constraint and must still surface as a failure rather than be papered
# over — otherwise a genuine "this name already exists" bug would look like flakiness.
ID_COLLISION_RETRIES=10
api_create() {
    local method="$1" path="$2" body="$3" attempt=1 resp=""
    while (( attempt <= ID_COLLISION_RETRIES )); do
        resp="$(api "$method" "$path" "$body")"
        [[ "$(api_status)" =~ ^20 ]] && { printf '%s' "$resp"; return 0; }
        if [[ "$(api_status)" == "409" ]] && printf '%s' "$resp" | grep -q '"constraint":"PRIMARY"'; then
            log_dbg "id-sequence collision on $path (attempt $attempt/$ID_COLLISION_RETRIES) — retrying"
            attempt=$((attempt + 1))
            continue
        fi
        printf '%s' "$resp"; return 1
    done
    log_warn "Gave up after $ID_COLLISION_RETRIES id-sequence collisions on $path"
    printf '%s' "$resp"; return 1
}

# =============================================================================
# MCP helper (streamable HTTP transport)
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
# Backend log window
#
# A stale line from an earlier run would make a broken send look healthy, so every
# assertion is scoped to the bytes appended after the import started.
# =============================================================================

LOG_OFFSET=0

mark_log() {
    LOG_OFFSET=$(wc -c < "$BACKEND_LOG" | tr -d ' ')
    log_dbg "Log window opens at byte $LOG_OFFSET"
}

log_window() {
    tail -c "+$((LOG_OFFSET + 1))" "$BACKEND_LOG" 2>/dev/null || true
}

# log_line_has <literal> [literal…] -> 0 when ONE line of the window contains all of them.
#
# Fixed-string (-F) because the recipient address may legitimately contain regex
# metacharacters — dots, and '+' in gmail-style tagged addresses.
#
# Case-insensitive (-i) because SecMan normalises the address on the way in: a mapping
# imported for "Markus@…" is stored and logged as "markus@…". Matching case-sensitively
# reports a send that plainly happened as a failure, and the cause table then blames the
# wrong component — a false negative is worse than no check at all.
#
# Several literals rather than one, because EmailService words the same success two ways:
#   sendEmail                 -> "Successfully sent email to X with subject: Y"
#   sendEmailWithInlineImages -> "Successfully sent email with inline images to X with subject: Y"
# Chained greps match either without a regex, so switching a mail to the templated
# logo variant does not silently break the assertion.
log_line_has() {
    local out; out="$(log_window)"
    local needle
    for needle in "$@"; do
        out="$(printf '%s' "$out" | grep -iF "$needle")" || return 1
    done
    [[ -n "$out" ]]
}

# wait_for_log <literal> [literal…] -> 0 when such a line appears within LOG_WAIT_SECONDS
wait_for_log() {
    local waited=0
    while (( waited < LOG_WAIT_SECONDS )); do
        log_line_has "$@" && return 0
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# =============================================================================
# Cleanup — only ever touches what this run created
# =============================================================================

cleanup() {
    local phase_label="${1:-post-run}"
    log_info "Cleanup ($phase_label) …"

    # Assessments first (FK: aws_account_risk_assessment -> risk_assessment).
    # Matched on the account ids this run generated, which appear in the auto-written notes.
    local assessments
    assessments=$(api GET "/api/risk-assessments" || echo '[]')
    echo "$assessments" | jq -r --arg a1 "$CLI_ACCOUNT" --arg a2 "$MCP_ACCOUNT" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select((.notes // "") | contains($a1) or contains($a2)) | .id' 2>/dev/null \
        | while read -r ra_id; do
            [[ -n "$ra_id" ]] && api DELETE "/api/risk-assessments/${ra_id}" >/dev/null || true
        done

    # Mappings for the test accounts.
    #
    # Reported rather than swallowed when the delete fails: DELETE /api/user-mappings/{id}
    # is @Secured("ADMIN") but UserMappingService.deleteMapping asserts
    # `mapping.email == callingUser.email`, so an admin can only delete mappings carrying
    # their OWN address — and the IllegalArgumentException escapes as HTTP 500 because the
    # controller catches only NoSuchElementException. Whenever the recipient is not the
    # admin running the test, these rows cannot be removed through the API. Leaving that
    # silent would make the skill's "cleans up after itself" promise a lie.
    local mappings m_id leftover=0
    mappings=$(api GET "/api/user-mappings/current?size=1000" || echo '{}')
    while read -r m_id; do
        [[ -n "$m_id" ]] || continue
        api DELETE "/api/user-mappings/${m_id}" >/dev/null 2>&1 || true
        [[ "$(api_status)" =~ ^2 ]] || leftover=$((leftover + 1))
    done < <(echo "$mappings" | jq -r --arg a1 "$CLI_ACCOUNT" --arg a2 "$MCP_ACCOUNT" \
        '(.content // .mappings // [])[]? | select(.awsAccountId == $a1 or .awsAccountId == $a2) | .id' 2>/dev/null)

    if (( leftover > 0 )); then
        log_warn "$leftover user mapping(s) for $CLI_ACCOUNT/$MCP_ACCOUNT could not be deleted"
        log_warn "  DELETE /api/user-mappings/{id} rejects mappings whose email is not the caller's"
        log_warn "  (UserMappingService.deleteMapping ownership check) — remove them by hand if needed."
    fi

    # The "AWS Account <id>" assets the feature creates as the assessment basis.
    local acct asset_id
    for acct in "$CLI_ACCOUNT" "$MCP_ACCOUNT"; do
        asset_id=$(api GET "/api/assets/by-name/AWS%20Account%20${acct}" | jq -r '.id // empty' 2>/dev/null || true)
        [[ -n "$asset_id" ]] && api DELETE "/api/assets/${asset_id}" >/dev/null || true
    done

    # Release, use case and requirements are matched by their PREFIXED NAME, never by
    # status or by this run's version. Only artifacts this driver seeded can carry the
    # prefix, so an environment's own ACTIVE release can never be caught here — and a
    # leftover from an earlier run (different version suffix) still is.
    local releases
    releases=$(api GET "/api/releases" || echo '[]')
    echo "$releases" | jq -r --arg n "$SEEDED_RELEASE_NAME" \
        '(if type == "array" then . else (.content // []) end)[]? | select(.name == $n) | .id' 2>/dev/null \
        | while read -r r_id; do
            [[ -n "$r_id" ]] || continue
            api DELETE "/api/releases/${r_id}?force=true" >/dev/null 2>&1 \
                || api DELETE "/api/releases/${r_id}" >/dev/null 2>&1 || true
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
    echo "$ucs" | jq -r --arg n "$SEEDED_USECASE_NAME" \
        '(if type == "array" then . else (.content // []) end)[]? | select(.name == $n) | .id' 2>/dev/null \
        | while read -r u_id; do
            [[ -n "$u_id" ]] && api DELETE "/api/usecases/${u_id}" >/dev/null || true
        done

    # Users: prefix-scoped ONLY. The recipient address is deliberately excluded —
    # it very likely belongs to a real account, and nothing here ever created it.
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
# Preflight: is mail even wired up?
# =============================================================================

preflight_email_config() {
    phase "Preflight: active SMTP configuration"

    local cfg
    cfg=$(api GET "/api/email-config/active")
    if [[ ! "$(api_status)" =~ ^20 ]]; then
        log_fail "No active email configuration (GET /api/email-config/active -> HTTP $(api_status))"
        log_info "Without one, EmailService.sendEmail logs 'No active email configuration found'"
        log_info "and returns false. The import would still report success and no mail would be"
        log_info "sent — the test cannot distinguish that from a real regression."
        log_info "Configure SMTP under Admin -> Email Configuration, then re-run."
        exit 1
    fi

    local host port from
    host=$(echo "$cfg" | jq -r '.smtpHost // empty')
    port=$(echo "$cfg" | jq -r '.smtpPort // empty')
    from=$(echo "$cfg" | jq -r '.fromEmail // empty')
    [[ -n "$host" ]] || { log_fail "Active email config has no smtpHost: $cfg"; exit 1; }

    log_pass "Active SMTP config: ${host}:${port}, from ${from:-<unset>}"
    log_info "Mail will arrive from '${from:-<unset>}' — check spam if it does not show up."
}

# =============================================================================
# Setup
# =============================================================================

resolve_standard() {
    # An assessment can only start against an ACTIVE release that has requirements
    # tagged with the chosen use case. Reuse beats seeding here: activating a fresh
    # release would ARCHIVE the existing one, and ARCHIVED is terminal.
    local releases active_id
    releases=$(api GET "/api/releases" || echo '[]')
    active_id=$(echo "$releases" | jq -r \
        '[(if type == "array" then . else (.content // []) end)[]? | select(.status == "ACTIVE")][0].id // empty')

    if [[ -n "$active_id" ]]; then
        RELEASE_VERSION=$(echo "$releases" | jq -r --arg i "$active_id" \
            '[(if type == "array" then . else (.content // []) end)[]? | select((.id|tostring) == $i)][0].version // empty')
        log_info "Reusing the environment's ACTIVE release $RELEASE_VERSION (id=$active_id) — nothing is archived"

        USECASE_NAME=$(api GET "/api/releases/${active_id}/requirements?pageSize=1000" \
            | jq -r '[.data[]?.usecases[]?.name] | unique | .[0] // empty')

        if [[ -n "$USECASE_NAME" ]]; then
            log_pass "Use case '$USECASE_NAME' is tagged in the ACTIVE release"
            return 0
        fi

        log_warn "ACTIVE release $RELEASE_VERSION has no use-case-tagged requirements"
        if [[ "$ALLOW_RELEASE_ACTIVATION" != "true" ]]; then
            log_fail "Cannot start an assessment against it, and activating a release of our own"
            log_info "would ARCHIVE $RELEASE_VERSION irreversibly (ARCHIVED is a terminal state)."
            log_info "Either tag some requirements in that release with a use case, or re-run with"
            log_info "ALLOW_RELEASE_ACTIVATION=true if archiving it is acceptable here."
            exit 1
        fi
        log_warn "ALLOW_RELEASE_ACTIVATION=true — $RELEASE_VERSION will be ARCHIVED and cannot be restored"
    else
        log_info "No ACTIVE release in this environment — seeding one is safe (nothing to archive)"
    fi

    seed_standard
}

seed_standard() {
    local uc usecase_id
    uc=$(api_create POST "/api/usecases" "$(jq -nc --arg n "$SEEDED_USECASE_NAME" '{name:$n}')")
    usecase_id=$(echo "$uc" | jq -r '.id // empty')
    [[ -n "$usecase_id" ]] || { log_fail "Could not create use case (HTTP $(api_status)): $uc"; exit 1; }

    local i
    for i in 1 2; do
        api_create POST "/api/requirements" "$(jq -nc --arg s "${E2E_PREFIX}tagged-req-${i}" --argjson u "[$usecase_id]" \
            '{shortreq:$s, details:"E2E owner-email requirement", chapter:"1", usecaseIds:$u}')" >/dev/null \
            || { log_fail "Could not create requirement $i (HTTP $(api_status))"; exit 1; }
    done

    # Creating a release snapshots the whole corpus; ACTIVE makes it the standard.
    local rel release_id
    rel=$(api_create POST "/api/releases" "$(jq -nc --arg v "$SEEDED_RELEASE_VERSION" --arg n "$SEEDED_RELEASE_NAME" \
        '{version:$v, name:$n, description:"E2E AWS account owner-email baseline"}')")
    release_id=$(echo "$rel" | jq -r '.id // empty')
    [[ -n "$release_id" ]] || { log_fail "Could not create release (HTTP $(api_status)): $rel"; exit 1; }

    api PUT "/api/releases/${release_id}/status" '{"status":"ACTIVE"}' >/dev/null
    [[ "$(api_status)" =~ ^20 ]] || { log_fail "Could not activate release (HTTP $(api_status))"; exit 1; }

    DID_SEED_RELEASE=true
    USECASE_NAME="$SEEDED_USECASE_NAME"
    RELEASE_VERSION="$SEEDED_RELEASE_VERSION"
    log_pass "Seeded use case '$USECASE_NAME' + 2 requirements, release $RELEASE_VERSION set ACTIVE"
}

setup_testbed() {
    phase "Setup: assessor and requirements standard"

    # An assessor is mandatory: the import is rejected outright when no SECCHAMPION
    # exists. Round-robin may well pick a pre-existing champion instead of this one —
    # that is fine, the mail is identical either way.
    api_create POST "/api/users" "$(jq -nc --arg u "$CHAMPION_USER" --arg e "$CHAMPION_EMAIL" --arg p "$TEST_PASS" \
        '{username:$u, email:$e, password:$p, roles:["SECCHAMPION"]}')" >/dev/null || true
    if [[ "$(api_status)" =~ ^20 ]]; then
        log_pass "SECCHAMPION user created: $CHAMPION_USER"
    else
        log_warn "Could not create $CHAMPION_USER (HTTP $(api_status)) — continuing if another SECCHAMPION exists"
    fi

    # Deliberately NOT creating a user for the recipient address. The mail is sent
    # whether or not one exists; the only difference is whether the assessment gets a
    # respondent. Creating one risks colliding with a real account, and deleting one
    # in cleanup would be worse.
    local existing
    existing=$(api GET "/api/users" | jq -r --arg e "$RECIPIENT_EMAIL" \
        '[(if type == "array" then . else (.content // []) end)[]? | select((.email // "") | ascii_downcase == ($e | ascii_downcase))][0].username // empty')
    if [[ -n "$existing" ]]; then
        log_info "Recipient matches existing user '$existing' — it becomes the assessment respondent (untouched by cleanup)"
    else
        log_info "No user has this address — the assessment is created with no respondent, and the mail is still sent"
    fi

    resolve_standard
}

# =============================================================================
# Assertions
# =============================================================================

SUBJECT_PREFIX="Risk assessment started for your AWS account"

# assert_owner_mail <account> <label> — the send evidence for one import
assert_owner_mail() {
    local account="$1" label="$2"
    local window

    # The success marker, the address and the account id must all be on the SAME line,
    # so a send to somebody else, or for a different account, cannot satisfy this.
    if wait_for_log "Successfully sent email" \
                    "to ${RECIPIENT_EMAIL} with subject: ${SUBJECT_PREFIX} ${account}"; then
        log_pass "$label: backend reports the owner mail sent to $RECIPIENT_EMAIL for account $account"
    else
        log_fail "$label: no successful send to $RECIPIENT_EMAIL for account $account within ${LOG_WAIT_SECONDS}s"
        window=$(log_window)

        # Distinguish the three ways this fails — they need different fixes.
        if echo "$window" | grep -q "No active email configuration found"; then
            log_info "  Cause: SMTP config went inactive mid-run (EmailService.sendEmail bailed out)"
        elif log_line_has "Failed to send email" "to ${RECIPIENT_EMAIL}"; then
            log_info "  Cause: SMTP rejected the message —"
            echo "$window" | grep -iF "Failed to send email" | grep -iF "to ${RECIPIENT_EMAIL}" | head -3 >&2
        elif echo "$window" | grep -qi "owner notification to ${RECIPIENT_EMAIL} failed"; then
            log_info "  Cause: sendStartNotification threw; the assessment was still created (failure isolation)"
            echo "$window" | grep "owner notification to" | head -3 >&2
        elif echo "$window" | grep -q "Started risk assessment"; then
            log_info "  Cause: assessment started but no send was attempted — check the info.error == null guard"
            log_info "  in AwsAccountRiskAssessmentService.startAssessmentsForNewAccounts"
        else
            log_info "  Cause: no assessment was started at all — check the import output above"
        fi
        return 1
    fi

    # A send that succeeded must not also have logged a config or delivery problem
    # for this recipient; that would mean a retry masked a real defect.
    window=$(log_window)
    if echo "$window" | grep -q "No active email configuration found"; then
        log_fail "$label: log also reports 'No active email configuration found' — some notification was dropped"
    else
        log_pass "$label: no dropped-mail warnings in the window"
    fi
}

# assert_assessment_exists <account> <label> — result in the global ASSESSMENT_ROW.
# Returning through a global rather than stdout keeps the call out of a subshell, so
# the PASS/FAIL counters it increments survive.
ASSESSMENT_ROW=""
assert_assessment_exists() {
    local account="$1" label="$2" count owner
    ASSESSMENT_ROW=$(mcp_payload "$(mcp_call "list_aws_account_risk_assessments" \
        "$(jq -nc --arg a "$account" '{awsAccountId:$a}')")")
    count=$(echo "$ASSESSMENT_ROW" | jq -r '.count // 0')

    if [[ "$count" == "1" ]]; then
        log_pass "$label: exactly one risk assessment tracked for $account"
    else
        log_fail "$label: expected 1 tracked assessment for $account, found $count"
    fi

    # Failure isolation: even a mail that never left must leave the assessment standing.
    owner=$(echo "$ASSESSMENT_ROW" | jq -r '.assessments[0].ownerEmail // empty')
    if [[ "$(lower "$owner")" == "$(lower "$RECIPIENT_EMAIL")" ]]; then
        log_pass "$label: assessment owner is $RECIPIENT_EMAIL"
    else
        log_fail "$label: assessment owner is '${owner:-<none>}', expected $RECIPIENT_EMAIL"
    fi
}

# =============================================================================
# Phases
# =============================================================================

CLI_ROW=""
MCP_ROW=""

phase_cli_import() {
    phase "CLI: import a brand-new AWS account and mail its owner"

    cat > "$WORK_DIR/mappings.csv" <<EOF
email,type,value
${RECIPIENT_EMAIL},AWS_ACCOUNT,${CLI_ACCOUNT}
EOF

    mark_log

    local out rc
    set +e
    out=$(java -jar "$CLI_JAR" manage-user-mappings import \
        --file "$WORK_DIR/mappings.csv" \
        --start-risk-assessment \
        --risk-usecase "$USECASE_NAME" \
        --risk-deadline-days "$DEADLINE_DAYS" \
        --username "$SECMAN_ADMIN_NAME" \
        --password "$SECMAN_ADMIN_PASS" \
        --backend-url "$BASE_URL" 2>&1)
    rc=$?
    set -e
    log_dbg "$out"

    if [[ $rc -eq 0 ]]; then
        log_pass "CLI import exited 0"
    else
        log_fail "CLI import exited $rc"
        echo "$out" >&2
        return
    fi

    assert_assessment_exists "$CLI_ACCOUNT" "CLI"
    CLI_ROW="$ASSESSMENT_ROW"
    assert_owner_mail "$CLI_ACCOUNT" "CLI" || true
}

phase_mcp_import() {
    phase "MCP: import_user_mappings over POST /mcp and mail its owner"

    mark_log

    local resp payload
    resp=$(mcp_call "import_user_mappings" "$(jq -nc \
        --arg e "$RECIPIENT_EMAIL" --arg a "$MCP_ACCOUNT" --arg uc "$USECASE_NAME" \
        --argjson d "$DEADLINE_DAYS" \
        '{mappings:[{email:$e, awsAccountId:$a}],
          startRiskAssessment:true, riskAssessmentUseCase:$uc, riskAssessmentDeadlineDays:$d}')")

    if echo "$resp" | jq -e '.error' >/dev/null 2>&1; then
        log_fail "import_user_mappings returned a JSON-RPC error: $(echo "$resp" | jq -c '.error')"
        return
    fi

    payload=$(mcp_payload "$resp")
    local started
    started=$(echo "$payload" | jq -r '.riskAssessments | length')
    [[ "$started" == "1" ]] && log_pass "MCP started 1 risk assessment" \
        || log_fail "MCP started $started assessments, expected 1"

    local err
    err=$(echo "$payload" | jq -r '.riskAssessments[0].error // empty')
    [[ -z "$err" ]] || log_fail "MCP reported an assessment error: $err"

    assert_assessment_exists "$MCP_ACCOUNT" "MCP"
    MCP_ROW="$ASSESSMENT_ROW"
    assert_owner_mail "$MCP_ACCOUNT" "MCP" || true
}

# =============================================================================
# Inbox checklist
#
# The log proves SMTP accepted the message; only a human can prove it arrived.
# Values come from the tracking rows, not from assumptions, so the checklist is
# still right when round-robin picked a different assessor.
# =============================================================================

print_inbox_checklist() {
    phase "Inbox check — confirm delivery to $RECIPIENT_EMAIL"

    local deadline
    deadline=$(date -v"+${DEADLINE_DAYS}d" "+%Y-%m-%d" 2>/dev/null \
        || date -d "+${DEADLINE_DAYS} days" "+%Y-%m-%d" 2>/dev/null || echo "today + ${DEADLINE_DAYS} days")

    {
        echo
        echo "Expect one mail per import, each with these fields in the body:"
        echo
        local label account row assessor
        for label in CLI MCP; do
            if [[ "$label" == "CLI" ]]; then account="$CLI_ACCOUNT"; row="$CLI_ROW"; else account="$MCP_ACCOUNT"; row="$MCP_ROW"; fi
            [[ -n "$row" ]] || continue
            assessor=$(echo "$row" | jq -r '.assessments[0].assessor // "<unknown>"' 2>/dev/null || echo "<unknown>")
            echo "  [$label] Subject: ${SUBJECT_PREFIX} ${account}"
            echo "          Use case:             ${USECASE_NAME}"
            echo "          Requirements version: ${RELEASE_VERSION}"
            echo "          Assessor:             ${assessor}"
            echo "          Deadline:             ${deadline}"
            echo
        done
        echo "Both a plain-text and an HTML part should be present (multipart/alternative)."
        echo "Check spam/junk if nothing arrives — the log already confirmed SMTP accepted it."
        echo
        echo "NOTE: cleanup runs next and deletes the assessments, mappings and account"
        echo "assets. The mails already in the mailbox are unaffected."
    } >&2
}

# =============================================================================
# Main
# =============================================================================

main() {
    echo -e "${BLUE}AWS account owner notification E2E${NC}" >&2
    echo "Accounts: CLI=$CLI_ACCOUNT MCP=$MCP_ACCOUNT" >&2

    check_prerequisites
    admin_login
    preflight_email_config

    cleanup "pre-run"
    trap on_exit EXIT

    setup_testbed

    [[ "$SKIP_CLI" == "true" ]] && log_warn "SKIP_CLI=true — CLI phase skipped" || phase_cli_import
    [[ "$SKIP_MCP" == "true" ]] && log_warn "SKIP_MCP=true — MCP phase skipped" || phase_mcp_import

    print_inbox_checklist

    phase "Summary"
    echo "  Standard used: release $RELEASE_VERSION, use case '$USECASE_NAME'" >&2
    if [[ "$DID_SEED_RELEASE" == "true" ]]; then
        echo "  Release was seeded by this run and is removed by cleanup" >&2
    else
        echo "  Release belongs to this environment and was left untouched" >&2
    fi
    echo -e "  ${GREEN}Passed: $PASS_COUNT${NC}" >&2
    echo -e "  ${RED}Failed: $FAIL_COUNT${NC}" >&2

    [[ $FAIL_COUNT -eq 0 ]] || exit 1
}

main "$@"
