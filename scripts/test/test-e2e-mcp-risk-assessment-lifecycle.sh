#!/usr/bin/env bash
# Holistic MCP risk-assessment lifecycle with a manually supplied respondent email.
# All fixture names use E2E_PREFIX. Cleanup is exact and safe to repeat.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../../tests/lib/secman-test-tls.sh
source "$REPO_ROOT/tests/lib/secman-test-tls.sh"

BASE_URL="${BASE_URL:-${SECMAN_BACKEND_URL:-}}"
E2E_PREFIX="e2e-mcp-ra-"
OWNER_USER="${E2E_PREFIX}owner"
CHAMPION_USER="${E2E_PREFIX}champion"
CHAMPION_EMAIL=""
USECASE_NAME="${E2E_PREFIX}usecase"
REQUIREMENT_NAME="${E2E_PREFIX}requirement"
ASSET_NAME="${E2E_PREFIX}aws-account"
ASSESSMENT_MARKER="${E2E_PREFIX}holistic-lifecycle"
STAMP="$(date +%s)"
SUFFIX="${STAMP: -6}"
AWS_ACCOUNT_ID="893${SUFFIX}000"
TEST_PASSWORD="E2eMcpRa!${SUFFIX}"

USER_EMAIL="${E2E_RISK_ASSESSMENT_USER_EMAIL:-}"
KEEP_DATA=false
CLEANUP_ONLY=false
VERBOSE=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --user-email) USER_EMAIL="${2:-}"; shift 2 ;;
        --keep-data) KEEP_DATA=true; shift ;;
        --cleanup-only) CLEANUP_ONLY=true; shift ;;
        --verbose|-v) VERBOSE=true; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

COOKIE_JAR="$(mktemp)"
PASS_COUNT=0
FAIL_COUNT=0
API_STATUS=""

log_info() { echo "[INFO] $*" >&2; }
log_pass() { echo "[PASS] $*" >&2; PASS_COUNT=$((PASS_COUNT + 1)); }
log_fail() { echo "[FAIL] $*" >&2; FAIL_COUNT=$((FAIL_COUNT + 1)); }
log_debug() { [[ "$VERBOSE" == true ]] && echo "[DEBUG] $*" >&2 || true; }

check_prerequisites() {
    for command_name in curl jq; do
        command -v "$command_name" >/dev/null || { log_fail "Required command missing: $command_name"; exit 1; }
    done
    for variable_name in BASE_URL SECMAN_ADMIN_NAME SECMAN_ADMIN_PASS SECMAN_ADMIN_EMAIL SECMAN_MCP_KEY; do
        [[ -n "${!variable_name:-}" ]] || { log_fail "$variable_name is required"; exit 1; }
    done
    if [[ "$CLEANUP_ONLY" != true ]]; then
        [[ -n "$USER_EMAIL" ]] || { log_fail "Pass --user-email or set E2E_RISK_ASSESSMENT_USER_EMAIL"; exit 2; }
        local email_regex='^[^[:space:],;:<>"\\]+@[^[:space:],;:<>"\\]+\.[^[:space:],;:<>"\\]+$'
        [[ "$USER_EMAIL" =~ $email_regex ]] || {
            log_fail "The supplied user email is not a single valid recipient"; exit 2;
        }
    fi
}

admin_login() {
    local status
    status="$(curl -sS -o /dev/null -w '%{http_code}' -c "$COOKIE_JAR" \
        -H 'Content-Type: application/json' -X POST "${BASE_URL}/api/auth/login" \
        --data "$(jq -nc --arg u "$SECMAN_ADMIN_NAME" --arg p "$SECMAN_ADMIN_PASS" \
            '{username:$u,password:$p}')")"
    [[ "$status" == 200 ]] || { log_fail "Admin login failed (HTTP $status)"; exit 1; }
}

api() {
    local method="$1" path="$2" body="${3:-}" output_file
    output_file="$(mktemp)"
    local args=(-sS -o "$output_file" -w '%{http_code}' -b "$COOKIE_JAR" -X "$method" "${BASE_URL}${path}")
    [[ -z "$body" ]] || args+=(-H 'Content-Type: application/json' --data "$body")
    API_STATUS="$(curl "${args[@]}")"
    command cat "$output_file"
    command rm -f "$output_file"
}

mcp_call() {
    local tool="$1" arguments="$2" delegated_email="$3" body response
    body="$(jq -nc --arg tool "$tool" --argjson args "$arguments" --arg id "e2e-${RANDOM}" \
        '{jsonrpc:"2.0",id:$id,method:"tools/call",params:{name:$tool,arguments:$args}}')"
    response="$(curl -sS -X POST "${BASE_URL}/mcp" \
        -H 'Content-Type: application/json' \
        -H "X-MCP-API-Key: ${SECMAN_MCP_KEY}" \
        -H "X-MCP-User-Email: ${delegated_email}" \
        --data "$body")"
    log_debug "$tool as $delegated_email -> $response"
    echo "$response"
}

mcp_payload() {
    echo "$1" | jq -c '.result.content[0].text | fromjson? // empty'
}

require_mcp_success() {
    local label="$1" response="$2"
    if echo "$response" | jq -e '.error == null and .result.isError != true' >/dev/null; then
        log_pass "$label"
    else
        log_fail "$label: $(echo "$response" | jq -c '.')"
        return 1
    fi
}

cleanup_fixture() {
    log_info "Removing ${E2E_PREFIX} fixture data"
    local body ids owner_email aws_account_ids

    body="$(api GET '/api/risk-assessments' || echo '[]')"
    ids="$(echo "$body" | jq -r --arg marker "$ASSESSMENT_MARKER" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select(.notes == $marker) | .id')"
    aws_account_ids="$(echo "$body" | jq -r --arg marker "$ASSESSMENT_MARKER" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select(.notes == $marker) | .awsAccount.awsAccountId // empty')"
    while IFS= read -r id; do
        [[ -z "$id" ]] || api DELETE "/api/risk-assessments/${id}" >/dev/null || true
    done <<< "$ids"

    body="$(api GET '/api/users' || echo '[]')"
    owner_email="$(echo "$body" | jq -r --arg username "$OWNER_USER" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select(.username == $username) | .email' | head -1)"

    if [[ -n "$owner_email" ]]; then
        body="$(api GET '/api/user-mappings/current?size=1000' || echo '{}')"
        ids="$(echo "$body" | jq -r --arg email "$owner_email" \
            '(.content // .mappings // [])[]? | select(.email == $email) | .id')"
        while IFS= read -r id; do
            [[ -z "$id" ]] || api DELETE "/api/user-mappings/${id}" >/dev/null || true
        done <<< "$ids"
    fi

    while IFS= read -r account_id; do
        [[ -z "$account_id" ]] || api DELETE "/api/admin/aws-accounts/${account_id}" >/dev/null || true
    done <<< "$aws_account_ids"

    body="$(api GET '/api/assets' || echo '[]')"
    ids="$(echo "$body" | jq -r --arg name "$ASSET_NAME" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select(.name == $name and .type == "AWS_ACCOUNT") | .id')"
    while IFS= read -r id; do
        [[ -z "$id" ]] || api DELETE "/api/assets/${id}" >/dev/null || true
    done <<< "$ids"

    body="$(api GET '/api/requirements' || echo '[]')"
    ids="$(echo "$body" | jq -r --arg name "$REQUIREMENT_NAME" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select(.shortreq == $name) | .id')"
    while IFS= read -r id; do
        [[ -z "$id" ]] || api DELETE "/api/requirements/${id}" >/dev/null || true
    done <<< "$ids"

    body="$(api GET '/api/usecases' || echo '[]')"
    ids="$(echo "$body" | jq -r --arg name "$USECASE_NAME" '.[]? | select(.name == $name) | .id')"
    while IFS= read -r id; do
        [[ -z "$id" ]] || api DELETE "/api/usecases/${id}" >/dev/null || true
    done <<< "$ids"

    body="$(api GET '/api/users' || echo '[]')"
    ids="$(echo "$body" | jq -r --arg owner "$OWNER_USER" --arg champion "$CHAMPION_USER" \
        '(if type == "array" then . else (.content // []) end)[]?
         | select(.username == $owner or .username == $champion) | .id')"
    while IFS= read -r id; do
        [[ -z "$id" ]] || api DELETE "/api/users/${id}" >/dev/null || true
    done <<< "$ids"
}

finish() {
    local exit_code=$?
    if [[ "$KEEP_DATA" != true && "$CLEANUP_ONLY" != true ]]; then cleanup_fixture || true; fi
    command rm -f "$COOKIE_JAR"
    if [[ $exit_code -ne 0 ]]; then exit "$exit_code"; fi
}
trap finish EXIT

check_prerequisites
if [[ "$CLEANUP_ONLY" != true ]]; then CHAMPION_EMAIL="${CHAMPION_USER}@${USER_EMAIL##*@}"; fi
admin_login
cleanup_fixture
if [[ "$CLEANUP_ONLY" == true ]]; then
    log_pass "Fixture cleanup completed"
    exit 0
fi

# Refuse to overwrite or later delete a real user supplied by mistake.
existing_users="$(api GET '/api/users')"
if echo "$existing_users" | jq -e --arg email "$USER_EMAIL" \
    '(if type == "array" then . else (.content // []) end)[]? | select((.email | ascii_downcase) == ($email | ascii_downcase))' >/dev/null; then
    log_fail "A user with $USER_EMAIL already exists; choose an unused test mailbox"
    exit 2
fi

response="$(mcp_call add_user "$(jq -nc --arg u "$CHAMPION_USER" --arg e "$CHAMPION_EMAIL" --arg p "$TEST_PASSWORD" \
    '{username:$u,email:$e,password:$p,roles:["USER","SECCHAMPION"]}')" "$SECMAN_ADMIN_EMAIL")"
require_mcp_success "Created assessor through MCP" "$response"

response="$(mcp_call add_user "$(jq -nc --arg u "$OWNER_USER" --arg e "$USER_EMAIL" --arg p "$TEST_PASSWORD" \
    '{username:$u,email:$e,password:$p,roles:["USER","RISK"]}')" "$SECMAN_ADMIN_EMAIL")"
require_mcp_success "Created respondent with manually supplied email through MCP" "$response"

response="$(mcp_call create_use_case "$(jq -nc --arg name "$USECASE_NAME" '{name:$name}')" "$SECMAN_ADMIN_EMAIL")"
require_mcp_success "Created dedicated use case through MCP" "$response"
USECASE_ID="$(mcp_payload "$response" | jq -r '.id')"

response="$(mcp_call add_requirement "$(jq -nc --arg short "$REQUIREMENT_NAME" --argjson id "$USECASE_ID" \
    '{shortreq:$short,details:"Holistic MCP risk assessment requirement",chapter:"E2E",useCaseIds:[$id]}')" "$SECMAN_ADMIN_EMAIL")"
require_mcp_success "Created and linked the single requirement through MCP" "$response"
REQUIREMENT_ID="$(mcp_payload "$response" | jq -r '.id')"

response="$(mcp_call import_user_mappings "$(jq -nc --arg email "$USER_EMAIL" --arg account "$AWS_ACCOUNT_ID" \
    '{mappings:[{email:$email,awsAccountId:$account}],startRiskAssessment:false}')" "$SECMAN_ADMIN_EMAIL")"
require_mcp_success "Added AWS account mapping for the new user through MCP" "$response"

END_DATE="$(date -v+7d +%F 2>/dev/null || date -d '+7 days' +%F)"
response="$(mcp_call create_risk_assessment "$(jq -nc \
    --arg account "$AWS_ACCOUNT_ID" --argjson usecase "$USECASE_ID" \
    --arg assessor "$CHAMPION_EMAIL" --arg respondent "$USER_EMAIL" --arg end "$END_DATE" --arg notes "$ASSESSMENT_MARKER" \
    '{awsAccountId:$account,useCaseIds:[$usecase],assessorEmail:$assessor,respondentEmail:$respondent,endDate:$end,notes:$notes}')" "$SECMAN_ADMIN_EMAIL")"
require_mcp_success "Started risk assessment through MCP" "$response"
ASSESSMENT_ID="$(mcp_payload "$response" | jq -r '.id')"

response="$(mcp_call notify_risk_assessment_respondent \
    "$(jq -nc --argjson id "$ASSESSMENT_ID" '{assessmentId:$id,dryRun:true}')" "$SECMAN_ADMIN_EMAIL")"
require_mcp_success "Previewed outstanding-answer notification through MCP" "$response"
[[ "$(mcp_payload "$response" | jq -r '.unansweredCount')" == 1 ]] \
    && log_pass "Notification preview reports the one outstanding answer" \
    || log_fail "Notification preview did not report exactly one outstanding answer"

response="$(mcp_call list_risk_assessments "$(jq -nc --arg usecase "$USECASE_NAME" '{status:"STARTED",useCaseName:$usecase}')" "$USER_EMAIL")"
require_mcp_success "Respondent listed open risk assessments by use case" "$response"
[[ "$(mcp_payload "$response" | jq --argjson id "$ASSESSMENT_ID" '[.assessments[] | select(.id == $id)] | length')" == 1 ]] \
    && log_pass "Open-by-use-case result contains exactly the created assessment" \
    || log_fail "Created assessment missing or duplicated in open-by-use-case result"

response="$(mcp_call get_risk_assessment_questionnaire "$(jq -nc --argjson id "$ASSESSMENT_ID" '{assessmentId:$id}')" "$USER_EMAIL")"
require_mcp_success "Respondent viewed questionnaire through MCP" "$response"
questionnaire="$(mcp_payload "$response")"
[[ "$(echo "$questionnaire" | jq '.requirementCount')" == 1 && \
   "$(echo "$questionnaire" | jq -r '.requirements[0].id')" == "$REQUIREMENT_ID" ]] \
    && log_pass "Questionnaire contains only the dedicated requirement" \
    || log_fail "Questionnaire is not exactly the one-requirement use case"

response="$(mcp_call save_risk_assessment_answers "$(jq -nc --argjson id "$ASSESSMENT_ID" --argjson req "$REQUIREMENT_ID" \
    '{assessmentId:$id,answers:[{requirementId:$req,answerType:"NO",comment:"Holistic MCP answer"}]}')" "$SECMAN_ADMIN_EMAIL")"
if echo "$response" | jq -e \
    '((.error.message // "") | contains("Only the assigned respondent")) or
     (.result.isError == true and ((.result.content[0].text // "") | contains("Only the assigned respondent")))' >/dev/null; then
    log_pass "Inner respondent boundary denies a privileged non-respondent"
else
    log_fail "Privileged non-respondent was not denied by the respondent boundary"
fi

response="$(mcp_call save_risk_assessment_answers "$(jq -nc --argjson id "$ASSESSMENT_ID" --argjson req "$REQUIREMENT_ID" \
    '{assessmentId:$id,answers:[{requirementId:$req,answerType:"NO",comment:"Holistic MCP answer"}]}')" "$USER_EMAIL")"
require_mcp_success "Respondent saved an answer through MCP" "$response"

response="$(mcp_call get_risk_assessment_answers "$(jq -nc --argjson id "$ASSESSMENT_ID" '{assessmentId:$id}')" "$USER_EMAIL")"
require_mcp_success "Respondent read the dedicated assessment answers through MCP" "$response"
[[ "$(mcp_payload "$response" | jq -r '.answers[0].comment')" == "Holistic MCP answer" ]] \
    && log_pass "Dedicated answer read returns the saved response" \
    || log_fail "Dedicated answer read did not return the saved response"

response="$(mcp_call submit_risk_assessment "$(jq -nc --argjson id "$ASSESSMENT_ID" '{assessmentId:$id}')" "$USER_EMAIL")"
require_mcp_success "Respondent completed the questionnaire through MCP" "$response"
[[ "$(mcp_payload "$response" | jq -r '.status')" == COMPLETED ]] \
    && log_pass "Assessment transitioned to COMPLETED" || log_fail "Assessment did not become COMPLETED"

response="$(mcp_call evaluate_risk_assessment "$(jq -nc --argjson id "$ASSESSMENT_ID" '{assessmentId:$id}')" "$CHAMPION_EMAIL")"
require_mcp_success "Assigned assessor evaluated the completed questionnaire through MCP" "$response"
evaluation="$(mcp_payload "$response")"
[[ "$(echo "$evaluation" | jq -r '.verdict')" == NON_COMPLIANT && "$(echo "$evaluation" | jq '.findings | length')" == 1 ]] \
    && log_pass "Evaluation reports the one non-compliant answer" \
    || log_fail "Evaluation summary does not match the submitted answer"

response="$(mcp_call list_risk_assessments "$(jq -nc --arg usecase "$USECASE_NAME" '{status:"COMPLETED",useCaseName:$usecase}')" "$SECMAN_ADMIN_EMAIL")"
require_mcp_success "Evaluator listed completed assessments by use case" "$response"

echo >&2
echo "Passed: $PASS_COUNT" >&2
echo "Failed: $FAIL_COUNT" >&2
[[ "$FAIL_COUNT" -eq 0 ]]
