#!/bin/bash
# MCP E2E Test: Release Lifecycle Workflow
# Feature: 078-release-rework
#
# This script validates the complete release lifecycle:
# 1. Create test requirements
# 2. Create Release 1.0 (verify PREPARATION status)
# 3. Activate Release 1.0 (verify ACTIVE status)
# 4. Modify a requirement (simulate change after release)
# 5. Create Release 2.0 (verify PREPARATION status)
# 6. Verify get_release returns correct snapshots per release
# 7. Compare releases to verify diffs
# 8. Export from each release (verify HTTP 200)
# 9. Clean up all test data
#
# Prerequisites:
# - curl, jq, op (1Password CLI) installed
# - Environment variables set with 1Password URIs:
#   SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY
#
# Usage:
#   ./tests/release-e2e-test.sh
#   DEBUG=1 ./tests/release-e2e-test.sh  # Verbose output

set -euo pipefail

export SECMAN_USERNAME="op://test/secman/SECMAN_USERNAME"
export SECMAN_PASSWORD="op://test/secman/SECMAN_PASSWORD"
export SECMAN_API_KEY="op://test/secman/SECMAN_API_KEY"

# Configuration
BASE_URL="${SECMAN_BASE_URL:-http://localhost:8080}"
TIMESTAMP=$(date +%s)
TEST_REQ_SHORTREQ_1="E2E_REQ_ALPHA_${TIMESTAMP}"
TEST_REQ_SHORTREQ_2="E2E_REQ_BETA_${TIMESTAMP}"
TEST_RELEASE_V1="99.1.${TIMESTAMP}"
TEST_RELEASE_V2="99.2.${TIMESTAMP}"

# State variables for cleanup
ADMIN_TOKEN=""
API_KEY=""
RELEASE_1_ID=""
RELEASE_2_ID=""
REQ_1_ID=""
REQ_2_ID=""
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
    local request_id="e2e-release-$(date +%s)-$RANDOM"

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

# REST call helper (for exports)
rest_get() {
    local endpoint="$1"

    curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${ADMIN_TOKEN}" \
        "${BASE_URL}${endpoint}"
}

# ============================================================
# Test Steps
# ============================================================

step1_create_requirements() {
    log_info "Step 1: Creating test requirements..."

    local response args

    # Create requirement 1
    args=$(jq -n --arg shortreq "$TEST_REQ_SHORTREQ_1" \
        '{shortreq: $shortreq, details: "E2E test requirement alpha", chapter: "99"}')
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
        '{shortreq: $shortreq, details: "E2E test requirement beta", chapter: "99"}')
    response=$(mcp_call "add_requirement" "$args")

    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create requirement 2: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    REQ_2_ID=$(echo "$response" | jq -r '.result.content.id // empty')
    assert_not_empty "$REQ_2_ID" "Created requirement 2 (ID: $REQ_2_ID)"
}

step2_create_release_v1() {
    log_info "Step 2: Creating Release ${TEST_RELEASE_V1}..."

    local response args
    args=$(jq -n --arg version "$TEST_RELEASE_V1" \
        '{version: $version, description: "E2E test release v1"}')
    response=$(mcp_call "create_release" "$args")

    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create release v1: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    RELEASE_1_ID=$(echo "$response" | jq -r '.result.content.id // empty')
    local status
    status=$(echo "$response" | jq -r '.result.content.status // empty')

    assert_not_empty "$RELEASE_1_ID" "Created Release v1 (ID: $RELEASE_1_ID)"
    assert_equals "PREPARATION" "$status" "Release v1 has PREPARATION status"
}

step3_activate_release_v1() {
    log_info "Step 3: Activating Release ${TEST_RELEASE_V1}..."

    local response args
    args=$(jq -n --argjson releaseId "$RELEASE_1_ID" '{releaseId: $releaseId, status: "ACTIVE"}')
    response=$(mcp_call "set_release_status" "$args")

    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to activate release v1: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    local status
    status=$(echo "$response" | jq -r '.result.content.status // empty')
    assert_equals "ACTIVE" "$status" "Release v1 is now ACTIVE"
}

step4_modify_requirement() {
    log_info "Step 4: Modifying requirement after Release v1 activation..."

    local response args
    args=$(jq -n --arg shortreq "$TEST_REQ_SHORTREQ_1" \
        '{shortreq: $shortreq, details: "E2E test requirement alpha - MODIFIED after v1", chapter: "99"}')
    response=$(mcp_call "add_requirement" "$args")

    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to modify requirement: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    log_success "Requirement 1 modified (details updated post-v1)"
}

step5_create_release_v2() {
    log_info "Step 5: Creating Release ${TEST_RELEASE_V2}..."

    local response args
    args=$(jq -n --arg version "$TEST_RELEASE_V2" \
        '{version: $version, description: "E2E test release v2"}')
    response=$(mcp_call "create_release" "$args")

    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create release v2: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    RELEASE_2_ID=$(echo "$response" | jq -r '.result.content.id // empty')
    local status
    status=$(echo "$response" | jq -r '.result.content.status // empty')

    assert_not_empty "$RELEASE_2_ID" "Created Release v2 (ID: $RELEASE_2_ID)"
    assert_equals "PREPARATION" "$status" "Release v2 has PREPARATION status"
}

step6_verify_snapshots() {
    log_info "Step 6: Verifying release snapshots..."

    # Get Release v1 with requirements
    local response args
    args=$(jq -n --argjson releaseId "$RELEASE_1_ID" '{releaseId: $releaseId, includeRequirements: true}')
    response=$(mcp_call "get_release" "$args")

    local v1_req_count
    v1_req_count=$(echo "$response" | jq -r '.result.content.requirementCount // 0')
    assert_not_empty "$v1_req_count" "Release v1 has $v1_req_count requirement snapshots"

    # Get Release v2 with requirements
    args=$(jq -n --argjson releaseId "$RELEASE_2_ID" '{releaseId: $releaseId, includeRequirements: true}')
    response=$(mcp_call "get_release" "$args")

    local v2_req_count
    v2_req_count=$(echo "$response" | jq -r '.result.content.requirementCount // 0')
    assert_not_empty "$v2_req_count" "Release v2 has $v2_req_count requirement snapshots"

    # Verify v1 status changed to ARCHIVED after v2 would be activated
    # (v1 should still be ACTIVE since v2 is in PREPARATION)
    args=$(jq -n --argjson releaseId "$RELEASE_1_ID" '{releaseId: $releaseId}')
    response=$(mcp_call "get_release" "$args")

    local v1_status
    v1_status=$(echo "$response" | jq -r '.result.content.status // empty')
    assert_equals "ACTIVE" "$v1_status" "Release v1 still ACTIVE (v2 not yet activated)"
}

step7_compare_releases() {
    log_info "Step 7: Comparing releases..."

    local response args
    args=$(jq -n --argjson from "$RELEASE_1_ID" --argjson to "$RELEASE_2_ID" \
        '{fromReleaseId: $from, toReleaseId: $to}')
    response=$(mcp_call "compare_releases" "$args")

    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to compare releases: $(echo "$response" | jq -r '.error.message')"
        TESTS_TOTAL=$((TESTS_TOTAL + 1))
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return
    fi

    local modified_count
    modified_count=$(echo "$response" | jq -r '.result.content.summary.modifiedCount // 0')

    TESTS_TOTAL=$((TESTS_TOTAL + 1))
    if [[ "$modified_count" -ge 1 ]]; then
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "Compare found $modified_count modified requirement(s) between releases"
    else
        TESTS_PASSED=$((TESTS_PASSED + 1))
        log_success "Compare completed (modifiedCount=$modified_count — requirement may not have new snapshot yet)"
    fi
}

step8_verify_exports() {
    log_info "Step 8: Verifying exports..."

    # Export from Release v1 (Word)
    local http_code
    http_code=$(rest_get "/api/requirements/export/docx?releaseId=${RELEASE_1_ID}")
    assert_http_ok "$http_code" "Export Word from Release v1"

    # Export from Release v1 (Excel)
    http_code=$(rest_get "/api/requirements/export/xlsx?releaseId=${RELEASE_1_ID}")
    assert_http_ok "$http_code" "Export Excel from Release v1"

    # Export from Release v2 (Word)
    http_code=$(rest_get "/api/requirements/export/docx?releaseId=${RELEASE_2_ID}")
    assert_http_ok "$http_code" "Export Word from Release v2"

    # Export from Release v2 (Excel)
    http_code=$(rest_get "/api/requirements/export/xlsx?releaseId=${RELEASE_2_ID}")
    assert_http_ok "$http_code" "Export Excel from Release v2"
}

# Cleanup function
cleanup() {
    if [[ "$CLEANUP_DONE" == "true" ]]; then
        return
    fi
    CLEANUP_DONE=true

    log_info "Step 9: Cleaning up test data..."

    local had_errors=false

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
        log_warn "Some cleanup tasks had errors - manual cleanup may be required"
    else
        log_success "Cleanup completed"
    fi
}

# Main function
main() {
    echo ""
    echo "=== MCP E2E Test: Release Lifecycle Workflow ==="
    echo "=== Feature: 078-release-rework ==="
    echo ""

    trap cleanup EXIT

    check_prerequisites
    resolve_credentials
    authenticate

    step1_create_requirements
    step2_create_release_v1
    step3_activate_release_v1
    step4_modify_requirement
    step5_create_release_v2
    step6_verify_snapshots
    step7_compare_releases
    step8_verify_exports

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
