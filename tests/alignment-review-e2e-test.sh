#!/bin/bash
# E2E Test: Alignment Review Export/Import Workflow
# Feature: 080-alignment-review-excel
#
# This script validates the alignment review Excel export/import workflow:
# 1. Create test requirements
# 2. Create + activate baseline release
# 3. Modify a requirement
# 4. Create new release v2
# 5. Start alignment
# 6. Get reviewer token from admin endpoint
# 7. Export review Excel
# 8. Modify Excel to fill assessments (using python3 + openpyxl)
# 9. Import modified Excel
# 10. Verify reviews exist
# 11. Cleanup
#
# Prerequisites:
# - curl, jq, op (1Password CLI) installed
# - python3 + openpyxl (for Excel modification; test skips gracefully if missing)
# - Environment variables set with 1Password URIs:
#   SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY
#
# Usage:
#   ./tests/alignment-review-e2e-test.sh
#   DEBUG=1 ./tests/alignment-review-e2e-test.sh  # Verbose output

set -euo pipefail

export SECMAN_USERNAME="op://test/secman/SECMAN_USERNAME"
export SECMAN_PASSWORD="op://test/secman/SECMAN_PASSWORD"
export SECMAN_API_KEY="op://test/secman/SECMAN_API_KEY"

# Configuration
BASE_URL="${SECMAN_BASE_URL:-http://localhost:8080}"
TIMESTAMP=$(date +%s)
TEST_REQ_SHORTREQ_1="E2E_ALIGN_ALPHA_${TIMESTAMP}"
TEST_REQ_SHORTREQ_2="E2E_ALIGN_BETA_${TIMESTAMP}"
TEST_RELEASE_V1="98.1.${TIMESTAMP}"
TEST_RELEASE_V2="98.2.${TIMESTAMP}"

# State variables for cleanup
ADMIN_TOKEN=""
API_KEY=""
RELEASE_1_ID=""
RELEASE_2_ID=""
REQ_1_ID=""
REQ_2_ID=""
SESSION_ID=""
REVIEW_TOKEN=""
EXPORT_FILE=""
CHANGED_MODE_COUNT=""
CLEANUP_DONE=false

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_TOTAL=0

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]${NC} $1" >&2; }
log_success() { echo -e "${GREEN}[PASS]${NC} $1" >&2; }
log_error()   { echo -e "${RED}[FAIL]${NC} $1" >&2; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC} $1" >&2; }
log_debug()   { if [[ "${DEBUG:-}" == "1" ]]; then echo -e "${YELLOW}[DEBUG]${NC} $1" >&2; fi; }

assert_equals() {
    local expected="$1"
    local actual="$2"
    local message="$3"
    TESTS_TOTAL=$((TESTS_TOTAL + 1))

    if [[ "$expected" == "$actual" ]]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "$message (expected='$expected')"
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
        log_error "$message (expected='$expected', got='$actual')"
    fi
}

assert_not_empty() {
    local value="$1"
    local message="$2"
    TESTS_TOTAL=$((TESTS_TOTAL + 1))

    if [[ -n "$value" && "$value" != "null" ]]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "$message"
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
        log_error "$message (value was empty or null)"
    fi
}

assert_http_ok() {
    local http_code="$1"
    local message="$2"
    TESTS_TOTAL=$((TESTS_TOTAL + 1))

    if [[ "$http_code" == "200" ]]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "$message (HTTP 200)"
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
        log_error "$message (HTTP $http_code)"
    fi
}

assert_greater_than() {
    local actual="$1"
    local threshold="$2"
    local message="$3"
    TESTS_TOTAL=$((TESTS_TOTAL + 1))

    if [[ "$actual" -gt "$threshold" ]]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "$message (value=$actual > $threshold)"
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
        log_error "$message (value=$actual <= $threshold)"
    fi
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    local missing=()
    command -v curl &> /dev/null || missing+=("curl")
    command -v jq &> /dev/null || missing+=("jq")
    command -v op &> /dev/null || missing+=("op (1Password CLI)")

    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "Missing required tools: ${missing[*]}"
        exit 1
    fi

    [[ -z "${SECMAN_USERNAME:-}" ]] && { log_error "SECMAN_USERNAME not set"; exit 1; }
    [[ -z "${SECMAN_PASSWORD:-}" ]] && { log_error "SECMAN_PASSWORD not set"; exit 1; }
    [[ -z "${SECMAN_API_KEY:-}" ]] && { log_error "SECMAN_API_KEY not set"; exit 1; }

    # Check python3 + openpyxl (optional but needed for import test)
    if command -v python3 &> /dev/null && python3 -c "import openpyxl" 2>/dev/null; then
        log_success "python3 + openpyxl available"
        HAS_OPENPYXL=true
    else
        log_warn "python3 + openpyxl not available — import test will be skipped"
        HAS_OPENPYXL=false
    fi

    log_success "Prerequisites check passed"
}

# Resolve credentials from 1Password
resolve_credentials() {
    log_info "Resolving credentials..."

    if [[ "${SECMAN_USERNAME}" == op://* ]]; then
        RESOLVED_USERNAME=$(op read "${SECMAN_USERNAME}" 2>/dev/null) || { log_error "Failed to resolve SECMAN_USERNAME"; exit 1; }
    else
        RESOLVED_USERNAME="${SECMAN_USERNAME}"
    fi

    if [[ "${SECMAN_PASSWORD}" == op://* ]]; then
        RESOLVED_PASSWORD=$(op read "${SECMAN_PASSWORD}" 2>/dev/null) || { log_error "Failed to resolve SECMAN_PASSWORD"; exit 1; }
    else
        RESOLVED_PASSWORD="${SECMAN_PASSWORD}"
    fi

    if [[ "${SECMAN_API_KEY}" == op://* ]]; then
        API_KEY=$(op read "${SECMAN_API_KEY}" 2>/dev/null) || { log_error "Failed to resolve SECMAN_API_KEY"; exit 1; }
    else
        API_KEY="${SECMAN_API_KEY}"
    fi

    log_success "Credentials resolved"
}

# Authenticate
authenticate() {
    log_info "Authenticating..."

    local response
    response=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"${RESOLVED_USERNAME}\",\"password\":\"${RESOLVED_PASSWORD}\"}")

    ADMIN_TOKEN=$(echo "$response" | jq -r '.token // empty')

    if [[ -z "$ADMIN_TOKEN" ]]; then
        log_error "Authentication failed"
        log_debug "Response: $response"
        exit 1
    fi

    log_success "Authenticated"
}

# MCP tool call helper
mcp_call() {
    local tool_name="$1"
    local arguments="$2"
    local user_email="${3:-${RESOLVED_USERNAME}}"
    local request_id="e2e-align-$(date +%s)-$RANDOM"

    log_debug "MCP call: $tool_name (as $user_email)"
    log_debug "Arguments: $arguments"

    local request_body
    request_body=$(jq -n \
        --arg id "$request_id" \
        --arg name "$tool_name" \
        --argjson args "$arguments" \
        '{jsonrpc: "2.0", id: $id, method: "tools/call", params: {name: $name, arguments: $args}}')

    local response
    response=$(curl -s -X POST "${BASE_URL}/api/mcp/tools/call" \
        -H "X-MCP-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -H "X-MCP-User-Email: ${user_email}" \
        -d "$request_body")

    log_debug "MCP response: $response"
    echo "$response"
}

# ============================================================
# Test Steps
# ============================================================

step1_create_requirements() {
    log_info "Step 1: Creating test requirements..."

    local response args

    # Create requirement 1
    args=$(jq -n --arg shortreq "$TEST_REQ_SHORTREQ_1" \
        '{shortreq: $shortreq, details: "E2E alignment test requirement alpha", chapter: "98"}')
    response=$(mcp_call "add_requirement" "$args")

    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create requirement 1: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    REQ_1_ID=$(echo "$response" | jq -r '.result.content.id // empty')
    assert_not_empty "$REQ_1_ID" "Created requirement 1 (ID: $REQ_1_ID)"

    # Create requirement 2
    args=$(jq -n --arg shortreq "$TEST_REQ_SHORTREQ_2" \
        '{shortreq: $shortreq, details: "E2E alignment test requirement beta", chapter: "98"}')
    response=$(mcp_call "add_requirement" "$args")

    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create requirement 2: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    REQ_2_ID=$(echo "$response" | jq -r '.result.content.id // empty')
    assert_not_empty "$REQ_2_ID" "Created requirement 2 (ID: $REQ_2_ID)"
}

step2_create_activate_baseline() {
    log_info "Step 2: Creating and activating baseline release ${TEST_RELEASE_V1}..."

    local response args

    # Create release v1
    args=$(jq -n --arg version "$TEST_RELEASE_V1" \
        '{version: $version, description: "E2E alignment test baseline"}')
    response=$(mcp_call "create_release" "$args")

    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create release v1: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    RELEASE_1_ID=$(echo "$response" | jq -r '.result.content.id // empty')
    assert_not_empty "$RELEASE_1_ID" "Created baseline release v1 (ID: $RELEASE_1_ID)"

    # Activate release v1
    args=$(jq -n --argjson releaseId "$RELEASE_1_ID" '{releaseId: $releaseId, status: "ACTIVE"}')
    response=$(mcp_call "set_release_status" "$args")

    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to activate release v1: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    local status
    status=$(echo "$response" | jq -r '.result.content.status // empty')
    assert_equals "ACTIVE" "$status" "Baseline release v1 is now ACTIVE"
}

step3_modify_requirement() {
    log_info "Step 3: Modifying requirement after baseline activation..."

    local response args
    args=$(jq -n --arg shortreq "$TEST_REQ_SHORTREQ_1" \
        '{shortreq: $shortreq, details: "E2E alignment test requirement alpha - MODIFIED after v1", chapter: "98"}')
    response=$(mcp_call "add_requirement" "$args")

    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to modify requirement: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    log_success "Requirement 1 modified (details updated post-v1)"
}

step4_create_release_v2() {
    log_info "Step 4: Creating Release ${TEST_RELEASE_V2}..."

    local response args
    args=$(jq -n --arg version "$TEST_RELEASE_V2" \
        '{version: $version, description: "E2E alignment test release v2"}')
    response=$(mcp_call "create_release" "$args")

    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create release v2: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    RELEASE_2_ID=$(echo "$response" | jq -r '.result.content.id // empty')
    assert_not_empty "$RELEASE_2_ID" "Created Release v2 (ID: $RELEASE_2_ID)"
}

step5_start_alignment() {
    log_info "Step 5: Starting alignment on Release v2 (review changed)..."

    local response http_code
    response=$(curl -s -w "\n%{http_code}" -X POST \
        "${BASE_URL}/api/releases/${RELEASE_2_ID}/alignment/start" \
        -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{"reviewAll": false}')

    http_code=$(echo "$response" | tail -1)
    local body
    body=$(echo "$response" | sed '$d')

    log_debug "Start alignment response: $body"

    TESTS_TOTAL=$((TESTS_TOTAL + 1))
    if [[ "$http_code" == "201" ]]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "Alignment started (HTTP 201)"
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
        log_error "Failed to start alignment (HTTP $http_code)"
        log_debug "Body: $body"
        return
    fi

    SESSION_ID=$(echo "$body" | jq -r '.session.id // empty')
    assert_not_empty "$SESSION_ID" "Got session ID: $SESSION_ID"

    local changed
    changed=$(echo "$body" | jq -r '.changedRequirements // 0')
    assert_greater_than "$changed" 0 "Alignment (changed mode) detected $changed changed requirement(s)"

    local review_scope
    review_scope=$(echo "$body" | jq -r '.session.reviewScope // empty')
    assert_equals "CHANGED" "$review_scope" "Review scope is CHANGED"

    CHANGED_MODE_COUNT="$changed"
}

step5b_restart_with_review_all() {
    log_info "Step 5b: Cancel and restart alignment with reviewAll=true..."

    if [[ -z "$SESSION_ID" || "$SESSION_ID" == "null" ]]; then
        log_error "No session ID — skipping"
        return
    fi

    # Cancel existing alignment
    local response http_code body
    response=$(curl -s -w "\n%{http_code}" -X POST \
        "${BASE_URL}/api/alignment/${SESSION_ID}/cancel" \
        -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{"notes": "E2E test: restarting with review-all mode"}')

    http_code=$(echo "$response" | tail -1)
    body=$(echo "$response" | sed '$d')

    TESTS_TOTAL=$((TESTS_TOTAL + 1))
    if [[ "$http_code" == "200" ]]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "Cancelled previous alignment (HTTP 200)"
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
        log_error "Failed to cancel alignment (HTTP $http_code)"
        log_debug "Body: $body"
        return
    fi

    # Start alignment with reviewAll=true
    response=$(curl -s -w "\n%{http_code}" -X POST \
        "${BASE_URL}/api/releases/${RELEASE_2_ID}/alignment/start" \
        -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        -H "Content-Type: application/json" \
        -d '{"reviewAll": true}')

    http_code=$(echo "$response" | tail -1)
    body=$(echo "$response" | sed '$d')

    log_debug "Start alignment (review all) response: $body"

    TESTS_TOTAL=$((TESTS_TOTAL + 1))
    if [[ "$http_code" == "201" ]]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "Alignment started with reviewAll=true (HTTP 201)"
    else
        TESTS_FAILED=$((TESTS_FAILED + 1))
        log_error "Failed to start review-all alignment (HTTP $http_code)"
        log_debug "Body: $body"
        return
    fi

    SESSION_ID=$(echo "$body" | jq -r '.session.id // empty')
    assert_not_empty "$SESSION_ID" "Got new session ID: $SESSION_ID"

    local review_scope
    review_scope=$(echo "$body" | jq -r '.session.reviewScope // empty')
    assert_equals "ALL" "$review_scope" "Review scope is ALL"

    local all_count
    all_count=$(echo "$body" | jq -r '.changedRequirements // 0')
    assert_greater_than "$all_count" 0 "Review-all mode includes $all_count requirement(s)"

    # Verify that review-all includes at least as many as changed mode
    if [[ -n "$CHANGED_MODE_COUNT" && "$CHANGED_MODE_COUNT" != "0" ]]; then
        TESTS_TOTAL=$((TESTS_TOTAL + 1))
        if [[ "$all_count" -ge "$CHANGED_MODE_COUNT" ]]; then
            TESTS_PASSED=$((TESTS_PASSED + 1))
            log_success "Review-all count ($all_count) >= changed count ($CHANGED_MODE_COUNT)"
        else
            TESTS_FAILED=$((TESTS_FAILED + 1))
            log_error "Review-all count ($all_count) < changed count ($CHANGED_MODE_COUNT)"
        fi
    fi
}

step6_get_reviewer_token() {
    log_info "Step 6: Getting reviewer token from admin endpoint..."

    if [[ -z "$SESSION_ID" || "$SESSION_ID" == "null" ]]; then
        log_error "No session ID — skipping"
        return
    fi

    local response
    response=$(curl -s -X GET \
        "${BASE_URL}/api/alignment/${SESSION_ID}/reviewers" \
        -H "Authorization: Bearer ${ADMIN_TOKEN}")

    log_debug "Reviewers response: $response"

    REVIEW_TOKEN=$(echo "$response" | jq -r '.reviewers[0].reviewToken // empty')
    assert_not_empty "$REVIEW_TOKEN" "Got reviewer token"
}

step7_export_review() {
    log_info "Step 7: Exporting review Excel..."

    if [[ -z "$REVIEW_TOKEN" || "$REVIEW_TOKEN" == "null" ]]; then
        log_error "No review token — skipping"
        return
    fi

    EXPORT_FILE=$(mktemp /tmp/review_export_XXXXXX.xlsx)

    local http_code
    http_code=$(curl -s -o "$EXPORT_FILE" -w "%{http_code}" \
        "${BASE_URL}/api/alignment/review/${REVIEW_TOKEN}/export")

    assert_http_ok "$http_code" "Export review Excel"

    local file_size
    file_size=$(wc -c < "$EXPORT_FILE" | tr -d ' ')
    assert_greater_than "$file_size" 0 "Export file has content (${file_size} bytes)"
}

step8_modify_and_import_excel() {
    log_info "Step 8: Modifying and importing Excel..."

    if [[ "$HAS_OPENPYXL" != "true" ]]; then
        log_warn "Skipping import test — python3 + openpyxl not available"
        return
    fi

    if [[ -z "$EXPORT_FILE" || ! -f "$EXPORT_FILE" ]]; then
        log_error "No export file — skipping"
        return
    fi

    local modified_file
    modified_file=$(mktemp /tmp/review_modified_XXXXXX.xlsx)

    # Use python3 to fill in assessments
    python3 -c "
import openpyxl
wb = openpyxl.load_workbook('${EXPORT_FILE}')
ws = wb.active
# Find column indices
headers = {cell.value: cell.column - 1 for cell in ws[1] if cell.value}
assessment_col = None
comment_col = None
for name, idx in headers.items():
    if name and name.lower() == 'assessment':
        assessment_col = idx
    if name and name.lower() == 'comment':
        comment_col = idx
if assessment_col is None:
    print('ERROR: Assessment column not found')
    exit(1)
# Fill all rows with OK + comment
for row in ws.iter_rows(min_row=2, max_row=ws.max_row):
    row[assessment_col].value = 'OK'
    if comment_col is not None:
        row[comment_col].value = 'E2E test auto-review'
wb.save('${modified_file}')
print(f'Modified {ws.max_row - 1} rows')
" 2>&1

    if [[ $? -ne 0 ]]; then
        log_error "Failed to modify Excel file"
        rm -f "$modified_file"
        return
    fi

    # Import the modified Excel
    local response http_code
    response=$(curl -s -w "\n%{http_code}" -X POST \
        "${BASE_URL}/api/alignment/review/${REVIEW_TOKEN}/import" \
        -F "file=@${modified_file};type=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

    http_code=$(echo "$response" | tail -1)
    local body
    body=$(echo "$response" | sed '$d')

    log_debug "Import response: $body"

    assert_http_ok "$http_code" "Import reviews from Excel"

    local imported
    imported=$(echo "$body" | jq -r '.imported // 0')
    assert_greater_than "$imported" 0 "Imported $imported review(s)"

    local errors_count
    errors_count=$(echo "$body" | jq -r '.errors | length // 0')
    assert_equals "0" "$errors_count" "No import errors"

    rm -f "$modified_file"
}

step9_verify_reviews() {
    log_info "Step 9: Verifying reviews exist..."

    if [[ -z "$REVIEW_TOKEN" || "$REVIEW_TOKEN" == "null" ]]; then
        log_error "No review token — skipping"
        return
    fi

    local response
    response=$(curl -s -X GET \
        "${BASE_URL}/api/alignment/review/${REVIEW_TOKEN}")

    log_debug "Review page response: $(echo "$response" | jq -c '.snapshots | length')"

    # Count snapshots that have existingReview populated
    local reviewed_count
    reviewed_count=$(echo "$response" | jq '[.snapshots[] | select(.existingReview != null)] | length')

    if [[ "$HAS_OPENPYXL" == "true" ]]; then
        assert_greater_than "$reviewed_count" 0 "Found $reviewed_count reviewed requirement(s) after import"
    else
        log_info "Import was skipped, so reviewed count may be 0 (got $reviewed_count)"
    fi
}

# Cleanup function
cleanup() {
    if [[ "$CLEANUP_DONE" == "true" ]]; then
        return
    fi
    CLEANUP_DONE=true

    log_info "Step 10: Cleaning up test data..."

    local had_errors=false

    # Remove temp export file
    if [[ -n "$EXPORT_FILE" && -f "$EXPORT_FILE" ]]; then
        rm -f "$EXPORT_FILE"
    fi

    # Delete releases (v2 first since v1 might be ACTIVE)
    for release_id_var in RELEASE_2_ID RELEASE_1_ID; do
        local release_id="${!release_id_var}"
        if [[ -n "$release_id" && "$release_id" != "null" ]]; then
            local response args
            args=$(jq -n --argjson releaseId "$release_id" '{releaseId: $releaseId}')
            response=$(mcp_call "delete_release" "$args" 2>/dev/null || true)
            local error_code
            error_code=$(echo "$response" | jq -r '.error.code // empty' 2>/dev/null || true)
            if [[ -z "$error_code" ]]; then
                log_info "Deleted release (ID: $release_id)"
            else
                log_warn "Failed to delete release $release_id: $(echo "$response" | jq -r '.error.message' 2>/dev/null || echo 'unknown')"
                had_errors=true
            fi
        fi
    done

    # Delete requirements via REST API
    for req_id_var in REQ_1_ID REQ_2_ID; do
        local req_id="${!req_id_var}"
        if [[ -n "$req_id" && "$req_id" != "null" ]]; then
            local http_code
            http_code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
                -H "Authorization: Bearer ${ADMIN_TOKEN}" \
                "${BASE_URL}/api/requirements/${req_id}")
            if [[ "$http_code" == "200" || "$http_code" == "204" ]]; then
                log_info "Deleted requirement (ID: $req_id)"
            else
                log_warn "Failed to delete requirement $req_id (HTTP $http_code)"
                had_errors=true
            fi
        fi
    done

    if [[ "$had_errors" == "true" ]]; then
        log_warn "Some cleanup tasks had errors — manual cleanup may be required"
    else
        log_success "Cleanup completed"
    fi
}

# Main function
main() {
    echo ""
    echo "=== E2E Test: Alignment Review Export/Import Workflow ==="
    echo "=== Feature: 080-alignment-review-excel ==="
    echo ""

    trap cleanup EXIT

    check_prerequisites
    resolve_credentials
    authenticate

    step1_create_requirements
    step2_create_activate_baseline
    step3_modify_requirement
    step4_create_release_v2
    step5_start_alignment
    step5b_restart_with_review_all
    step6_get_reviewer_token
    step7_export_review
    step8_modify_and_import_excel
    step9_verify_reviews

    echo ""
    echo "=== Test Summary ==="
    echo -e "  Total:  ${TESTS_TOTAL}"
    echo -e "  Passed: ${GREEN}${TESTS_PASSED}${NC}"
    echo -e "  Failed: ${RED}${TESTS_FAILED}${NC}"
    echo ""

    if [[ "$TESTS_FAILED" -eq 0 ]]; then
        echo -e "${GREEN}=== ALL TESTS PASSED ===${NC}"
    else
        echo -e "${RED}=== $TESTS_FAILED TEST(S) FAILED ===${NC}"
        exit 1
    fi
    echo ""
}

main "$@"
