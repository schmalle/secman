#!/usr/bin/env bash
# Reversible MCP lifecycle for one requirement and one use case.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SCRIPT_PATH="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"
ORIGINAL_ARGS=("$@")

DELEGATED_EMAIL="${SECMAN_MCP_USER_EMAIL:-${SECMAN_ADMIN_EMAIL:-}}"
VERBOSE=false

usage() {
    cat <<'EOF'
Usage: ./scripts/test/test-e2e-mcp-requirement-use-case-lifecycle.sh [options]

Creates, verifies, and removes one uniquely marked requirement and use case
through SecMan MCP. It lists each resource before and after deletion and proves
the structured requirement-to-use-case assignment.

Options:
  --user-email EMAIL  Delegated ADMIN, REQ, or SECCHAMPION identity
  --verbose, -v       Print non-secret fixture IDs and MCP result payloads
  --help, -h          Show this help

Required environment (normally resolved from ./secmanpp.env by pass-cli):
  SECMAN_HOST or SECMAN_BACKEND_URL
  SECMAN_MCP_KEY
  SECMAN_MCP_USER_EMAIL or SECMAN_ADMIN_EMAIL

The key needs REQUIREMENTS_READ, REQUIREMENTS_WRITE, and REQUIREMENTS_DELETE.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --user-email) DELEGATED_EMAIL="${2:-}"; shift 2 ;;
        --verbose|-v) VERBOSE=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ -z "${SECMAN_HOST:-${SECMAN_BACKEND_URL:-}}" || -z "${SECMAN_MCP_KEY:-}" || -z "$DELEGATED_EMAIL" ]]; then
    command -v pass-cli >/dev/null || {
        echo "pass-cli is required to resolve SecMan MCP credentials" >&2
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

email_regex='^[^[:space:],;:< >"\\]+@[^[:space:],;:< >"\\]+\.[^[:space:],;:< >"\\]+$'
[[ "$DELEGATED_EMAIL" =~ $email_regex ]] || {
    echo "--user-email must be one valid email address" >&2
    exit 2
}

BASE_URL="${SECMAN_HOST:-${SECMAN_BACKEND_URL}}"
BASE_URL="${BASE_URL%/}"
if [[ "$BASE_URL" =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]]; then
    CURL_PROTOCOL='=https'
elif [[ "$BASE_URL" =~ ^http://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]{1,5})?$ ]]; then
    # Plain HTTP is accepted only for an explicitly configured loopback dev
    # endpoint. Remote targets must use HTTPS.
    CURL_PROTOCOL='=http'
else
    echo "SecMan MCP tests require HTTPS, except for a configured loopback development URL" >&2
    exit 2
fi
MCP_URL="$BASE_URL/mcp"

# Test environments may use a private CA. The opt-out keeps normal TLS
# verification available without changing the request construction below.
# shellcheck source=../../tests/lib/secman-test-tls.sh
source "$REPO_ROOT/tests/lib/secman-test-tls.sh"

RUN_ID="$(date -u +%Y%m%d%H%M%S)-$$-$RANDOM"
FIXTURE_PREFIX="e2e-mcp-req-uc-$RUN_ID"
REQUIREMENT_NAME="$FIXTURE_PREFIX requirement"
USE_CASE_NAME="$FIXTURE_PREFIX use-case"
REQUIREMENT_ID=""
USE_CASE_ID=""
PASS_COUNT=0

log_info() { printf '[INFO] %s\n' "$*" >&2; }
log_pass() { printf '[PASS] %s\n' "$*" >&2; PASS_COUNT=$((PASS_COUNT + 1)); }
log_debug() { [[ "$VERBOSE" == true ]] && printf '[DEBUG] %s\n' "$*" >&2 || true; }

raw_mcp_call() {
    local tool_name="$1"
    local arguments="$2"
    local request_body
    request_body="$(jq -nc --arg id "req-uc-$RANDOM" --arg tool "$tool_name" --argjson args "$arguments" \
        '{jsonrpc:"2.0",id:$id,method:"tools/call",params:{name:$tool,arguments:$args}}')"

    curl --proto "$CURL_PROTOCOL" --silent --show-error --fail-with-body \
        --connect-timeout 10 --max-time 60 \
        -X POST "$MCP_URL" \
        -H 'Content-Type: application/json' \
        -H "X-MCP-API-Key: $SECMAN_MCP_KEY" \
        -H "X-MCP-User-Email: $DELEGATED_EMAIL" \
        --data "$request_body"
}

require_success() {
    local label="$1"
    local response="$2"
    local payload
    if ! jq -e '.error == null and .result.isError != true' >/dev/null <<< "$response"; then
        printf '[FAIL] %s: %s\n' "$label" "$(jq -c '.error // .result.content // .' <<< "$response")" >&2
        return 1
    fi
    payload="$(jq -ce '.result.content[0].text | fromjson' <<< "$response")" || {
        printf '[FAIL] %s returned no structured payload\n' "$label" >&2
        return 1
    }
    log_debug "$label payload=$payload"
    printf '%s\n' "$payload"
}

call_tool() {
    local label="$1"
    local tool_name="$2"
    local arguments="$3"
    local response
    response="$(raw_mcp_call "$tool_name" "$arguments")" || {
        printf '[FAIL] %s could not reach SecMan MCP\n' "$label" >&2
        return 1
    }
    require_success "$label" "$response"
}

cleanup_fixture() {
    local saved_exit_code=$?
    trap - EXIT
    set +e

    if [[ -n "$REQUIREMENT_ID" ]]; then
        raw_mcp_call set_requirement_use_cases \
            "$(jq -nc --argjson requirementId "$REQUIREMENT_ID" \
                '{requirementId:$requirementId,useCaseIds:[]}')" >/dev/null 2>&1
        raw_mcp_call delete_requirement \
            "$(jq -nc --argjson requirementId "$REQUIREMENT_ID" \
                '{requirementId:$requirementId,confirm:true}')" >/dev/null 2>&1
    fi
    if [[ -n "$USE_CASE_ID" ]]; then
        raw_mcp_call delete_use_case \
            "$(jq -nc --argjson useCaseId "$USE_CASE_ID" \
                '{useCaseId:$useCaseId,confirm:true}')" >/dev/null 2>&1
    fi

    exit "$saved_exit_code"
}
trap cleanup_fixture EXIT

log_info "Running marker-scoped MCP lifecycle as $DELEGATED_EMAIL"

payload="$(call_tool "Create requirement" add_requirement \
    "$(jq -nc --arg name "$REQUIREMENT_NAME" \
        '{shortreq:$name,details:"Reversible MCP requirement/use-case lifecycle fixture",chapter:"E2E"}')")"
REQUIREMENT_ID="$(jq -er '.id | select(type == "number" and . > 0)' <<< "$payload")"
log_pass "Created requirement $REQUIREMENT_ID"

payload="$(call_tool "List created requirement" get_requirements \
    "$(jq -nc --arg search "$REQUIREMENT_NAME" \
        '{search:$search,detailed:true,limit:10,offset:0}')")"
[[ "$(jq --argjson id "$REQUIREMENT_ID" --arg name "$REQUIREMENT_NAME" \
    '[.requirements[] | select(.id == $id and .shortreq == $name)] | length' <<< "$payload")" == 1 ]] || {
    echo "[FAIL] Requirement listing did not return exactly the created requirement" >&2
    exit 1
}
log_pass "Listed the created requirement"

payload="$(call_tool "Create use case" create_use_case \
    "$(jq -nc --arg name "$USE_CASE_NAME" '{name:$name}')")"
USE_CASE_ID="$(jq -er '.id | select(type == "number" and . > 0)' <<< "$payload")"
log_pass "Created use case $USE_CASE_ID"

payload="$(call_tool "List created use case" list_use_cases \
    "$(jq -nc --arg search "$USE_CASE_NAME" '{search:$search,page:0,pageSize:10}')")"
[[ "$(jq --argjson id "$USE_CASE_ID" --arg name "$USE_CASE_NAME" \
    '[.useCases[] | select(.id == $id and .name == $name)] | length' <<< "$payload")" == 1 ]] || {
    echo "[FAIL] Use-case listing did not return exactly the created use case" >&2
    exit 1
}
log_pass "Listed the created use case"

payload="$(call_tool "Assign use case to requirement" set_requirement_use_cases \
    "$(jq -nc --argjson requirementId "$REQUIREMENT_ID" --argjson useCaseId "$USE_CASE_ID" \
        '{requirementId:$requirementId,useCaseIds:[$useCaseId]}')")"
[[ "$(jq --argjson id "$USE_CASE_ID" '[.useCases[] | select(.id == $id)] | length' <<< "$payload")" == 1 ]] || {
    echo "[FAIL] Assignment response did not contain the created use case" >&2
    exit 1
}
log_pass "Assigned the use case to the requirement"

payload="$(call_tool "Verify requirement assignment" get_requirements \
    "$(jq -nc --arg search "$REQUIREMENT_NAME" '{search:$search,detailed:true,limit:10,offset:0}')")"
[[ "$(jq --argjson requirementId "$REQUIREMENT_ID" --argjson useCaseId "$USE_CASE_ID" \
    '[.requirements[] | select(.id == $requirementId) | .useCaseAssignments[] | select(.id == $useCaseId)] | length' \
    <<< "$payload")" == 1 ]] || {
    echo "[FAIL] Requirement listing did not show the structured use-case assignment" >&2
    exit 1
}
log_pass "Verified the structured assignment through listing"

call_tool "Remove requirement assignment" set_requirement_use_cases \
    "$(jq -nc --argjson requirementId "$REQUIREMENT_ID" \
        '{requirementId:$requirementId,useCaseIds:[]}')" >/dev/null
log_pass "Removed the assignment before deletion"

call_tool "Delete use case" delete_use_case \
    "$(jq -nc --argjson useCaseId "$USE_CASE_ID" '{useCaseId:$useCaseId,confirm:true}')" >/dev/null
log_pass "Deleted the created use case"
USE_CASE_ID=""

payload="$(call_tool "List use cases after deletion" list_use_cases \
    "$(jq -nc --arg search "$USE_CASE_NAME" '{search:$search,page:0,pageSize:10}')")"
[[ "$(jq --arg name "$USE_CASE_NAME" '[.useCases[] | select(.name == $name)] | length' <<< "$payload")" == 0 ]] || {
    echo "[FAIL] Deleted use case is still returned by listing" >&2
    exit 1
}
log_pass "Listed again and confirmed the use case is absent"

call_tool "Delete requirement" delete_requirement \
    "$(jq -nc --argjson requirementId "$REQUIREMENT_ID" \
        '{requirementId:$requirementId,confirm:true}')" >/dev/null
log_pass "Deleted the created requirement"
REQUIREMENT_ID=""

payload="$(call_tool "List requirements after deletion" get_requirements \
    "$(jq -nc --arg search "$REQUIREMENT_NAME" '{search:$search,detailed:true,limit:10,offset:0}')")"
[[ "$(jq --arg name "$REQUIREMENT_NAME" '[.requirements[] | select(.shortreq == $name)] | length' <<< "$payload")" == 0 ]] || {
    echo "[FAIL] Deleted requirement is still returned by listing" >&2
    exit 1
}
log_pass "Listed again and confirmed the requirement is absent"

trap - EXIT
printf '\nMCP requirement/use-case lifecycle passed. Passed: %d, Failed: 0\n' "$PASS_COUNT"
