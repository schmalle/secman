#!/bin/bash
# MCP E2E Test: User-Asset-Workgroup Workflow
# Feature: 074-mcp-e2e-test
#
# This script validates the complete MCP workflow for user access control:
# 1. Create a TEST user with VULN role
# 2. Create an asset with a CRITICAL vulnerability (10 days old)
# 3. Create a workgroup and assign the asset
# 4. Assign the TEST user to the workgroup
# 5. Verify TEST user can see exactly one asset
# 6. Clean up all test data
#
# Prerequisites:
# - curl, jq, op (1Password CLI) installed
# - Environment variables set with 1Password URIs:
#   SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY
#
# Usage:
#   ./tests/mcp-e2e-workgroup-test.sh
#   DEBUG=1 ./tests/mcp-e2e-workgroup-test.sh  # Verbose output

set -euo pipefail

export SECMAN_USERNAME="op://test/secman/SECMAN_USERNAME"
export SECMAN_PASSWORD="op://test/secman/SECMAN_PASSWORD"
export SECMAN_API_KEY="op://test/secman/SECMAN_API_KEY"
export SECMAN_TEST_DOMAIN="op://test/secman/SECMAN_TEST_DOMAIN"

# Configuration
BASE_URL="${SECMAN_BASE_URL:-http://localhost:8080}"
TEST_USER_NAME="E2E_TEST_USER_$(date +%s)"
TEST_USER_EMAIL=""  # Set after SECMAN_TEST_DOMAIN is resolved
TEST_ASSET_NAME="E2E_TEST_ASSET_$(date +%s)"
TEST_WORKGROUP_NAME="E2E-TEST-WORKGROUP-$(date +%s)"
TEST_CVE="CVE-2024-$(printf '%05d' $((RANDOM % 99999)))"

# State variables for cleanup
ADMIN_TOKEN=""
API_KEY=""
TEST_USER_ID=""
TEST_ASSET_ID=""
TEST_WORKGROUP_ID=""
CLEANUP_DONE=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions - all write to stderr to avoid contaminating stdout (for command substitution)
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1" >&2
}

log_success() {
    echo -e "${GREEN}[PASS]${NC} $1" >&2
}

log_error() {
    echo -e "${RED}[FAIL]${NC} $1" >&2
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1" >&2
}

log_debug() {
    if [[ "${DEBUG:-}" == "1" ]]; then
        echo -e "${YELLOW}[DEBUG]${NC} $1" >&2
    fi
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    local missing=()

    if ! command -v curl &> /dev/null; then
        missing+=("curl")
    fi

    if ! command -v jq &> /dev/null; then
        missing+=("jq")
    fi

    if ! command -v op &> /dev/null; then
        missing+=("op (1Password CLI)")
    fi

    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "Missing required tools: ${missing[*]}"
        log_info "Install missing tools and try again."
        exit 1
    fi

    # Check environment variables
    if [[ -z "${SECMAN_USERNAME:-}" ]]; then
        log_error "SECMAN_USERNAME environment variable not set"
        exit 1
    fi

    if [[ -z "${SECMAN_PASSWORD:-}" ]]; then
        log_error "SECMAN_PASSWORD environment variable not set"
        exit 1
    fi

    if [[ -z "${SECMAN_API_KEY:-}" ]]; then
        log_error "SECMAN_API_KEY environment variable not set"
        log_info "Create an MCP API key via the UI with these settings:"
        log_info "  - Permissions: WORKGROUPS_WRITE, ASSETS_READ, VULNERABILITIES_READ"
        log_info "  - User Delegation: ENABLED"
        log_info "  - Allowed Domains: @<your-domain> (to match test user email)"
        exit 1
    fi

    if [[ -z "${SECMAN_TEST_DOMAIN:-}" ]]; then
        log_error "SECMAN_TEST_DOMAIN environment variable not set"
        exit 1
    fi

    log_success "Prerequisites check passed"
}

# Resolve credentials from 1Password
resolve_credentials() {
    log_info "Resolving credentials from 1Password..."

    # Check if credentials are 1Password URIs or plain values
    if [[ "${SECMAN_USERNAME}" == op://* ]]; then
        RESOLVED_USERNAME=$(op read "${SECMAN_USERNAME}" 2>/dev/null) || {
            log_error "Failed to resolve SECMAN_USERNAME from 1Password"
            exit 1
        }
    else
        RESOLVED_USERNAME="${SECMAN_USERNAME}"
    fi

    if [[ "${SECMAN_PASSWORD}" == op://* ]]; then
        RESOLVED_PASSWORD=$(op read "${SECMAN_PASSWORD}" 2>/dev/null) || {
            log_error "Failed to resolve SECMAN_PASSWORD from 1Password"
            exit 1
        }
    else
        RESOLVED_PASSWORD="${SECMAN_PASSWORD}"
    fi

    # API key is required for MCP calls
    if [[ "${SECMAN_API_KEY}" == op://* ]]; then
        API_KEY=$(op read "${SECMAN_API_KEY}" 2>/dev/null) || {
            log_error "Failed to resolve SECMAN_API_KEY from 1Password"
            exit 1
        }
    else
        API_KEY="${SECMAN_API_KEY}"
    fi

    # Resolve test domain
    if [[ "${SECMAN_TEST_DOMAIN}" == op://* ]]; then
        RESOLVED_TEST_DOMAIN=$(op read "${SECMAN_TEST_DOMAIN}" 2>/dev/null) || {
            log_error "Failed to resolve SECMAN_TEST_DOMAIN from 1Password"
            exit 1
        }
    else
        RESOLVED_TEST_DOMAIN="${SECMAN_TEST_DOMAIN}"
    fi

    # Now set the test user email using the resolved domain
    TEST_USER_EMAIL="e2e-test-$(date +%s)@${RESOLVED_TEST_DOMAIN}"

    log_success "Credentials resolved"
}

# Authenticate to secman
authenticate() {
    log_info "Authenticating to secman..."

    local response
    response=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"${RESOLVED_USERNAME}\",\"password\":\"${RESOLVED_PASSWORD}\"}")

    log_debug "Auth response: $response"

    ADMIN_TOKEN=$(echo "$response" | jq -r '.token // empty')

    if [[ -z "$ADMIN_TOKEN" ]]; then
        log_error "Authentication failed"
        log_debug "Response: $response"
        exit 1
    fi

    log_success "Authenticated successfully"
}

# MCP tool call helper
mcp_call() {
    local tool_name="$1"
    local arguments="$2"
    local user_email="${3:-${RESOLVED_USERNAME}}"
    local request_id="e2e-$(date +%s)-$RANDOM"

    log_debug "MCP call: $tool_name (as $user_email)"
    log_debug "Arguments: $arguments"

    # Build the JSON-RPC request properly using jq
    local request_body
    request_body=$(jq -n \
        --arg id "$request_id" \
        --arg name "$tool_name" \
        --argjson args "$arguments" \
        '{jsonrpc: "2.0", id: $id, method: "tools/call", params: {name: $name, arguments: $args}}') || {
        log_error "Failed to construct JSON request body"
        log_debug "Tool: $tool_name, Args: $arguments"
        echo "{}"
        return 1
    }

    log_debug "Request body: $request_body"

    local http_code
    local response
    response=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/mcp/tools/call" \
        -H "X-MCP-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -H "X-MCP-User-Email: ${user_email}" \
        -d "$request_body")

    # Extract HTTP status code from the last line
    http_code=$(echo "$response" | tail -n1)
    response=$(echo "$response" | sed '$d')

    log_debug "HTTP status: $http_code"
    log_debug "MCP response: $response"
    echo "$response"
}

# Create test user with VULN role
create_test_user() {
    log_info "Step 1: Creating TEST user with VULN role..."
    log_debug "Creating user: $TEST_USER_NAME / $TEST_USER_EMAIL"

    local response
    local args
    args=$(jq -c -n --arg username "$TEST_USER_NAME" --arg email "$TEST_USER_EMAIL" --arg password "TestPass123" \
        '{username: $username, email: $email, password: $password, roles: ["VULN"]}')
    log_debug "Constructed args: $args"
    response=$(mcp_call "add_user" "$args")
    log_debug "After mcp_call, response=$response"

    # Check for error (JSON-RPC format)
    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create test user: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    # Extract user ID from JSON-RPC result
    TEST_USER_ID=$(echo "$response" | jq -r '.result.content.user.id // empty')

    if [[ -z "$TEST_USER_ID" || "$TEST_USER_ID" == "null" ]]; then
        log_error "Failed to get test user ID from response"
        log_debug "Response: $response"
        exit 1
    fi

    log_success "Created TEST user (ID: $TEST_USER_ID, Email: $TEST_USER_EMAIL)"
}

# Create test asset with vulnerability
create_test_asset_with_vulnerability() {
    log_info "Step 2: Creating test asset with CRITICAL vulnerability (10 days old)..."

    local response
    local args
    args=$(jq -n --arg hostname "$TEST_ASSET_NAME" --arg cve "$TEST_CVE" \
        '{hostname: $hostname, cve: $cve, criticality: "CRITICAL", daysOpen: 10}')
    response=$(mcp_call "add_vulnerability" "$args")

    # Check for error (JSON-RPC format)
    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create test asset: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    # Extract asset ID from JSON-RPC result (assetId is returned as a number)
    TEST_ASSET_ID=$(echo "$response" | jq -r '.result.content.assetId // empty')

    if [[ -z "$TEST_ASSET_ID" || "$TEST_ASSET_ID" == "null" ]]; then
        log_error "Failed to get test asset ID from response"
        log_debug "Response: $response"
        exit 1
    fi

    log_success "Created test asset (ID: $TEST_ASSET_ID) with vulnerability $TEST_CVE"
}

# Create test workgroup
create_test_workgroup() {
    log_info "Step 3: Creating test workgroup..."

    local response
    local args
    args=$(jq -n --arg name "$TEST_WORKGROUP_NAME" \
        '{name: $name, description: "E2E test workgroup for MCP workflow validation"}')
    response=$(mcp_call "create_workgroup" "$args")

    # Check for error (JSON-RPC format)
    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create workgroup: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    # Extract workgroup ID from JSON-RPC result
    TEST_WORKGROUP_ID=$(echo "$response" | jq -r '.result.content.id // empty')

    if [[ -z "$TEST_WORKGROUP_ID" || "$TEST_WORKGROUP_ID" == "null" ]]; then
        log_error "Failed to get workgroup ID from response"
        log_debug "Response: $response"
        exit 1
    fi

    log_success "Created workgroup (ID: $TEST_WORKGROUP_ID)"
}

# Assign asset to workgroup
assign_asset_to_workgroup() {
    log_info "Step 4: Assigning asset to workgroup..."

    local response
    local args
    args=$(jq -n --argjson workgroupId "$TEST_WORKGROUP_ID" --argjson assetId "$TEST_ASSET_ID" \
        '{workgroupId: $workgroupId, assetIds: [$assetId]}')
    response=$(mcp_call "assign_assets_to_workgroup" "$args")

    # Check for error
    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to assign asset to workgroup: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    log_success "Asset assigned to workgroup"
}

# Assign user to workgroup
assign_user_to_workgroup() {
    log_info "Step 5: Assigning TEST user to workgroup..."

    local response
    local args
    args=$(jq -n --argjson workgroupId "$TEST_WORKGROUP_ID" --argjson userId "$TEST_USER_ID" \
        '{workgroupId: $workgroupId, userIds: [$userId]}')
    response=$(mcp_call "assign_users_to_workgroup" "$args")

    # Check for error
    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to assign user to workgroup: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    log_success "TEST user assigned to workgroup"
}

# Verify TEST user can see exactly one asset
verify_access_as_test_user() {
    log_info "Step 6: Switching to TEST user context and verifying asset access..."

    local response
    response=$(mcp_call "get_assets" "{}" "${TEST_USER_EMAIL}")

    # Check for error (JSON-RPC format)
    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to get assets as TEST user: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    # Extract asset count from JSON-RPC result
    local asset_count
    asset_count=$(echo "$response" | jq -r '.result.content.assets | length // 0')

    log_debug "TEST user can see $asset_count asset(s)"

    if [[ "$asset_count" -eq 1 ]]; then
        log_success "TEST user sees exactly 1 asset (expected)"

        # Verify it's our test asset
        local visible_asset_id
        visible_asset_id=$(echo "$response" | jq -r '.result.content.assets[0].id // empty')

        if [[ "$visible_asset_id" == "$TEST_ASSET_ID" ]]; then
            log_success "Verified: TEST user sees the correct test asset (ID: $TEST_ASSET_ID)"
        else
            log_warn "TEST user sees asset ID $visible_asset_id, expected $TEST_ASSET_ID"
        fi
    else
        log_error "TEST user sees $asset_count assets, expected exactly 1"
        log_debug "Response: $response"
        exit 1
    fi
}

# Cleanup function
cleanup() {
    if [[ "$CLEANUP_DONE" == "true" ]]; then
        return
    fi
    CLEANUP_DONE=true

    log_info "Step 7: Cleaning up test data..."

    local had_errors=false

    # Delete user first (cascades user_workgroups entries)
    if [[ -n "$TEST_USER_ID" && "$TEST_USER_ID" != "null" ]]; then
        local response
        local args
        args=$(jq -n --argjson userId "$TEST_USER_ID" '{userId: $userId}')
        response=$(mcp_call "delete_user" "$args" 2>/dev/null || true)
        local error_code
        error_code=$(echo "$response" | jq -r '.error.code // empty' 2>/dev/null || true)
        if [[ -z "$error_code" ]]; then
            log_info "Deleted user (ID: $TEST_USER_ID)"
        else
            log_warn "Failed to delete user: $(echo "$response" | jq -r '.error.message' 2>/dev/null || echo 'unknown error')"
            had_errors=true
        fi
    fi

    # Delete asset (cascades asset_workgroups entries and vulnerabilities)
    if [[ -n "$TEST_ASSET_ID" && "$TEST_ASSET_ID" != "null" ]]; then
        local response
        local args
        args=$(jq -n --argjson assetId "$TEST_ASSET_ID" '{assetId: $assetId, forceTimeout: true}')
        response=$(mcp_call "delete_asset" "$args" 2>/dev/null || true)
        local error_code
        error_code=$(echo "$response" | jq -r '.error.code // empty' 2>/dev/null || true)
        if [[ -z "$error_code" ]]; then
            log_info "Deleted asset (ID: $TEST_ASSET_ID)"
        else
            log_warn "Failed to delete asset: $(echo "$response" | jq -r '.error.message' 2>/dev/null || echo 'unknown error')"
            had_errors=true
        fi
    fi

    # Delete workgroup (user_workgroups and asset_workgroups entries already removed)
    if [[ -n "$TEST_WORKGROUP_ID" && "$TEST_WORKGROUP_ID" != "null" ]]; then
        local response
        local args
        args=$(jq -n --argjson workgroupId "$TEST_WORKGROUP_ID" '{workgroupId: $workgroupId}')
        response=$(mcp_call "delete_workgroup" "$args" 2>/dev/null || true)
        local error_code
        error_code=$(echo "$response" | jq -r '.error.code // empty' 2>/dev/null || true)
        if [[ -z "$error_code" ]]; then
            log_info "Deleted workgroup (ID: $TEST_WORKGROUP_ID)"
        else
            log_warn "Failed to delete workgroup: $(echo "$response" | jq -r '.error.message' 2>/dev/null || echo 'unknown error')"
            had_errors=true
        fi
    fi

    if [[ "$had_errors" == "true" ]]; then
        log_warn "Some cleanup tasks had errors - manual cleanup may be required"
    else
        log_success "Cleanup completed"
    fi
}

# Main function
main() {
    echo ""
    echo "=== MCP E2E Test: User-Asset-Workgroup Workflow ==="
    echo ""

    # Set trap for cleanup on exit (success or failure)
    trap cleanup EXIT

    # Execute test workflow
    check_prerequisites
    resolve_credentials
    authenticate
    create_test_user
    create_test_asset_with_vulnerability
    create_test_workgroup
    assign_asset_to_workgroup
    assign_user_to_workgroup
    verify_access_as_test_user

    echo ""
    echo "=== TEST PASSED ==="
    echo ""
}

# Run main
main "$@"
