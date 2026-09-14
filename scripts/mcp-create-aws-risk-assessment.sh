#!/usr/bin/env bash
# Create a user, one-requirement use case, AWS account, and open assessment through MCP.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SCRIPT_PATH="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"
ORIGINAL_ARGS=("$@")

USER_EMAIL=""
USERNAME=""
AWS_ACCOUNT_ID=""
USE_CASE_NAME=""
REQUIREMENT_TEXT=""
DEADLINE_DAYS=7

usage() {
    cat <<'EOF'
Usage: ./scripts/mcp-create-aws-risk-assessment.sh [options]

Creates through SecMan MCP:
  - a USER/RISK user with an interactively entered password;
  - a use case and one linked requirement;
  - an AWS account mapping;
  - an open risk assessment assigned to the new user.

Options:
  --email EMAIL              New user's email (prompted when omitted)
  --username USERNAME        New username (defaults to the email local part)
  --aws-account-id ID        New 12-digit AWS account id (prompted when omitted)
  --use-case-name NAME       Defaults to "AWS account <ID>"
  --requirement-text TEXT    Defaults to an account-specific review requirement
  --deadline-days DAYS       Assessment deadline, 1..3650 (default: 7)
  -h, --help                 Show this help

The password is always read twice from standard input and is never accepted as
a command-line argument. Proton Pass values are loaded from ./secmanpp.env.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --email) USER_EMAIL="${2:-}"; shift 2 ;;
        --username) USERNAME="${2:-}"; shift 2 ;;
        --aws-account-id) AWS_ACCOUNT_ID="${2:-}"; shift 2 ;;
        --use-case-name) USE_CASE_NAME="${2:-}"; shift 2 ;;
        --requirement-text) REQUIREMENT_TEXT="${2:-}"; shift 2 ;;
        --deadline-days) DEADLINE_DAYS="${2:-}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ -z "${SECMAN_BACKEND_URL:-}" || -z "${SECMAN_MCP_KEY:-}" || -z "${SECMAN_ADMIN_EMAIL:-}" ]]; then
    command -v pass-cli >/dev/null || {
        echo "pass-cli is required to resolve SecMan credentials" >&2
        exit 1
    }
    [[ -f "$REPO_ROOT/secmanpp.env" ]] || {
        echo "Missing Proton Pass environment file: $REPO_ROOT/secmanpp.env" >&2
        exit 1
    }
    exec pass-cli run --env-file "$REPO_ROOT/secmanpp.env" -- "$SCRIPT_PATH" "${ORIGINAL_ARGS[@]}"
fi

for command_name in curl jq; do
    command -v "$command_name" >/dev/null || {
        echo "Required command not found: $command_name" >&2
        exit 1
    }
done

if [[ -z "$USER_EMAIL" ]]; then
    read -r -p "New user email: " USER_EMAIL
fi
if [[ -z "$AWS_ACCOUNT_ID" ]]; then
    read -r -p "New 12-digit AWS account id: " AWS_ACCOUNT_ID
fi

email_regex='^[^[:space:],;:<>"\\]+@[^[:space:],;:<>"\\]+\.[^[:space:],;:<>"\\]+$'
[[ "$USER_EMAIL" =~ $email_regex ]] || {
    echo "Email must be one valid recipient address" >&2
    exit 2
}
[[ "$AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ ]] || {
    echo "AWS account id must contain exactly 12 digits" >&2
    exit 2
}
[[ "$DEADLINE_DAYS" =~ ^[0-9]+$ ]] && (( DEADLINE_DAYS >= 1 && DEADLINE_DAYS <= 3650 )) || {
    echo "Deadline days must be an integer from 1 through 3650" >&2
    exit 2
}

USERNAME="${USERNAME:-${USER_EMAIL%%@*}}"
USE_CASE_NAME="${USE_CASE_NAME:-AWS account $AWS_ACCOUNT_ID}"
REQUIREMENT_TEXT="${REQUIREMENT_TEXT:-Review the security controls for AWS account $AWS_ACCOUNT_ID}"

for named_value in USERNAME USE_CASE_NAME REQUIREMENT_TEXT; do
    [[ -n "${!named_value}" ]] || {
        echo "$named_value must not be empty" >&2
        exit 2
    }
done
(( ${#USERNAME} <= 255 && ${#USE_CASE_NAME} <= 255 )) || {
    echo "Username and use-case name must not exceed 255 characters" >&2
    exit 2
}

read -r -s -p "Password for $USER_EMAIL: " USER_PASSWORD
printf '\n' >&2
read -r -s -p "Repeat password: " USER_PASSWORD_CONFIRMATION
printf '\n' >&2
(( ${#USER_PASSWORD} >= 8 && ${#USER_PASSWORD} <= 200 )) || {
    echo "Password must contain 8 to 200 characters" >&2
    exit 2
}
[[ "$USER_PASSWORD" == "$USER_PASSWORD_CONFIRMATION" ]] || {
    echo "Passwords do not match" >&2
    exit 2
}
unset USER_PASSWORD_CONFIRMATION
trap 'unset USER_PASSWORD USER_PASSWORD_CONFIRMATION user_arguments 2>/dev/null || true' EXIT

BASE_URL="${SECMAN_BACKEND_URL%/}"
CURL_TLS_ARGS=()
case "$(printf '%s' "${SECMAN_INSECURE:-false}" | tr '[:upper:]' '[:lower:]')" in
    true|1|yes) CURL_TLS_ARGS=(-k) ;;
esac

mcp_payload() {
    jq -c '
        if (.result.content | type) == "array" then
            (.result.content[0].text | fromjson)
        else
            .result.content
        end
    '
}

mcp_call() {
    local tool="$1" arguments="$2" request response error_message
    request="$(printf '%s' "$arguments" | jq -c --arg tool "$tool" --arg id "shell-${RANDOM}" \
        '{jsonrpc:"2.0",id:$id,method:"tools/call",params:{name:$tool,arguments:.}}')"
    response="$(printf '%s' "$request" | curl "${CURL_TLS_ARGS[@]}" -sS -X POST "${BASE_URL}/mcp" \
        -H 'Content-Type: application/json' \
        -H "X-MCP-API-Key: ${SECMAN_MCP_KEY}" \
        -H "X-MCP-User-Email: ${SECMAN_ADMIN_EMAIL}" \
        --data-binary @-)"
    if ! printf '%s' "$response" | jq -e '.error == null and .result.isError != true' >/dev/null; then
        error_message="$(printf '%s' "$response" | jq -r '
            .error.message //
            (if (.result.content | type) == "array" then .result.content[0].text
             else .result.content.message end) //
            "MCP call failed"
        ')"
        echo "$tool failed: $error_message" >&2
        return 1
    fi
    printf '%s' "$response" | mcp_payload
}

echo "Creating user through MCP..." >&2
user_arguments="$(printf '%s' "$USER_PASSWORD" | jq -Rs \
    --arg username "$USERNAME" --arg email "$USER_EMAIL" \
    '{username:$username,email:$email,password:.,roles:["USER","RISK"]}')"
user_result="$(mcp_call add_user "$user_arguments")"
unset USER_PASSWORD user_arguments
USER_ID="$(printf '%s' "$user_result" | jq -er '.user.id')"

echo "Creating use case and requirement through MCP..." >&2
use_case_result="$(mcp_call create_use_case "$(jq -nc --arg name "$USE_CASE_NAME" '{name:$name}')")"
USE_CASE_ID="$(printf '%s' "$use_case_result" | jq -er '.id')"
requirement_result="$(mcp_call add_requirement "$(jq -nc \
    --arg text "$REQUIREMENT_TEXT" --argjson use_case_id "$USE_CASE_ID" \
    '{shortreq:$text,details:"Created by the MCP AWS risk-assessment provisioning script",useCaseIds:[$use_case_id]}')")"
REQUIREMENT_ID="$(printf '%s' "$requirement_result" | jq -er '.id')"

echo "Creating AWS account mapping through MCP..." >&2
mcp_call import_user_mappings "$(jq -nc --arg email "$USER_EMAIL" --arg account "$AWS_ACCOUNT_ID" \
    '{mappings:[{email:$email,awsAccountId:$account}],startRiskAssessment:false}')" >/dev/null

if END_DATE="$(date -v+"${DEADLINE_DAYS}"d +%F 2>/dev/null)"; then
    :
else
    END_DATE="$(date -d "+${DEADLINE_DAYS} days" +%F)"
fi

echo "Starting the risk assessment through MCP..." >&2
assessment_result="$(mcp_call create_risk_assessment "$(jq -nc \
    --arg aws_account_id "$AWS_ACCOUNT_ID" --argjson use_case_id "$USE_CASE_ID" \
    --arg assessor "$SECMAN_ADMIN_EMAIL" --arg respondent "$USER_EMAIL" --arg end_date "$END_DATE" \
    --arg notes "Created by scripts/mcp-create-aws-risk-assessment.sh" \
    '{awsAccountId:$aws_account_id,useCaseIds:[$use_case_id],assessorEmail:$assessor,
      respondentEmail:$respondent,endDate:$end_date,notes:$notes}')")"
ASSESSMENT_ID="$(printf '%s' "$assessment_result" | jq -er '.id')"

open_result="$(mcp_call list_risk_assessments "$(jq -nc \
    --arg use_case "$USE_CASE_NAME" '{status:"STARTED",useCaseName:$use_case,page:0,pageSize:20}')")"
printf '%s' "$open_result" | jq -e --argjson id "$ASSESSMENT_ID" \
    '.assessments | any(.id == $id and .status == "STARTED")' >/dev/null || {
    echo "Assessment $ASSESSMENT_ID was created but is missing from the open-by-use-case result" >&2
    exit 1
}

jq -n \
    --argjson userId "$USER_ID" \
    --arg email "$USER_EMAIL" \
    --arg username "$USERNAME" \
    --argjson useCaseId "$USE_CASE_ID" \
    --arg useCaseName "$USE_CASE_NAME" \
    --argjson requirementId "$REQUIREMENT_ID" \
    --arg requirement "$REQUIREMENT_TEXT" \
    --arg awsAccountId "$AWS_ACCOUNT_ID" \
    --argjson riskAssessmentId "$ASSESSMENT_ID" \
    --arg status "STARTED" \
    --arg endDate "$END_DATE" \
    '{user:{id:$userId,email:$email,username:$username},
      useCase:{id:$useCaseId,name:$useCaseName},
      requirement:{id:$requirementId,shortreq:$requirement},
      awsAccount:{awsAccountId:$awsAccountId},
      riskAssessment:{id:$riskAssessmentId,status:$status,endDate:$endDate}}'
