#!/usr/bin/env bash
# Contract test for the operator-facing MCP provisioning shell script.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SUBJECT="$REPO_ROOT/scripts/mcp-create-aws-risk-assessment.sh"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

export MOCK_MCP_CALLS="$WORK_DIR/mcp-calls"
export EXPECTED_PASSWORD="McpScript-${RANDOM}-${RANDOM}"
export MOCK_API_KEY="test-${RANDOM}-${RANDOM}"

curl() {
    local request tool content
    request="$(command cat)"
    tool="$(printf '%s' "$request" | jq -er '.params.name')"
    printf '%s\n' "$tool" >> "$MOCK_MCP_CALLS"

    case "$tool" in
        add_user)
            [[ "$(printf '%s' "$request" | jq -r '.params.arguments.password')" == "$EXPECTED_PASSWORD" ]]
            content='{"user":{"id":101}}'
            ;;
        create_use_case) content='{"id":202}' ;;
        add_requirement) content='{"id":303}' ;;
        import_user_mappings)
            printf '%s' "$request" | jq -e '.params.arguments.startRiskAssessment == false' >/dev/null
            content='{"created":1}'
            ;;
        create_risk_assessment)
            printf '%s' "$request" | jq -e \
                '.params.arguments.respondentEmail == "owner@example.org" and
                 .params.arguments.awsAccountId == "123456789012" and
                 (.params.arguments | has("assetId") | not)' >/dev/null
            content='{"id":505,"status":"STARTED"}'
            ;;
        list_risk_assessments)
            content='{"assessments":[{"id":505,"status":"STARTED"}]}'
            ;;
        *) return 91 ;;
    esac

    jq -nc --arg content "$content" \
        '{jsonrpc:"2.0",id:"mock",result:{content:[{type:"text",text:$content}],isError:false}}'
}
export -f curl

stdout_file="$WORK_DIR/stdout"
stderr_file="$WORK_DIR/stderr"
printf '%s\n%s\n' "$EXPECTED_PASSWORD" "$EXPECTED_PASSWORD" | \
    SECMAN_BACKEND_URL='https://secman.example.org' \
    SECMAN_MCP_KEY="$MOCK_API_KEY" \
    SECMAN_ADMIN_EMAIL='admin@example.org' \
    "$SUBJECT" \
        --email 'owner@example.org' \
        --username 'owner' \
        --aws-account-id '123456789012' \
        --use-case-name 'Shell contract use case' \
        --requirement-text 'Shell contract requirement' \
        >"$stdout_file" 2>"$stderr_file"

expected_calls="$(printf '%s\n' \
    add_user create_use_case add_requirement import_user_mappings \
    create_risk_assessment list_risk_assessments)"
[[ "$(<"$MOCK_MCP_CALLS")" == "$expected_calls" ]]
jq -e '
    .user.id == 101 and
    .useCase.id == 202 and
    .requirement.id == 303 and
    .awsAccount.awsAccountId == "123456789012" and
    .riskAssessment.id == 505 and
    .riskAssessment.status == "STARTED"
' "$stdout_file" >/dev/null
! command grep -F "$EXPECTED_PASSWORD" "$stdout_file" "$stderr_file" >/dev/null

set +e
SECMAN_BACKEND_URL='https://secman.example.org' \
SECMAN_MCP_KEY="$MOCK_API_KEY" \
SECMAN_ADMIN_EMAIL='admin@example.org' \
    "$SUBJECT" --email 'owner@example.org' --aws-account-id '1234' \
    >"$WORK_DIR/invalid-out" 2>"$WORK_DIR/invalid-err"
invalid_exit=$?
set -e
[[ "$invalid_exit" -eq 2 ]]
command grep -F 'exactly 12 digits' "$WORK_DIR/invalid-err" >/dev/null

echo "mcp-create-aws-risk-assessment script contract: PASS"
