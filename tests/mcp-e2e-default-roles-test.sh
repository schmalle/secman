#!/bin/bash
# MCP E2E Test: Default User Roles
# Feature: 080-default-user-roles
#
# This script validates that creating a user via MCP add_user without
# specifying roles automatically assigns the default roles: USER, VULN, REQ.
#
# Test flow:
# 1. Create a user via MCP add_user WITHOUT specifying roles
# 2. Verify the response contains exactly USER, VULN, REQ roles
# 3. Clean up by deleting the test user
#
# Prerequisites:
# - curl, jq, op (1Password CLI) installed
# - Environment variables set with 1Password URIs:
#   SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY, SECMAN_TEST_DOMAIN
#
# Usage:
#   ./tests/mcp-e2e-default-roles-test.sh
#   DEBUG=1 ./tests/mcp-e2e-default-roles-test.sh  # Verbose output

set -euo pipefail

export SECMAN_USERNAME="op://test/secman/SECMAN_USERNAME"
export SECMAN_PASSWORD="op://test/secman/SECMAN_PASSWORD"
export SECMAN_API_KEY="op://test/secman/SECMAN_API_KEY"
export SECMAN_TEST_DOMAIN="op://test/secman/SECMAN_TEST_DOMAIN"

# Configuration
BASE_URL="${SECMAN_BASE_URL:-http://localhost:8080}"
TEST_USER_NAME="E2E_DEFAULT_ROLES_$(date +%s)"
TEST_USER_EMAIL=""  # Set after SECMAN_TEST_DOMAIN is resolved

# Expected default roles (sorted for comparison)
EXPECTED_ROLES="REQ USER VULN"

# State variables for cleanup
API_KEY=""
TEST_USER_ID=""
CLEANUP_DONE=false

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
log_debug()   { [[ "${DEBUG:-}" == "1" ]] && echo -e "${YELLOW}[DEBUG]${NC} $1" >&2 || true; }

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    local missing=()
    command -v curl &>/dev/null || missing+=("curl")
    command -v jq   &>/dev/null || missing+=("jq")
    command -v op   &>/dev/null || missing+=("op (1Password CLI)")

    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "Missing required tools: ${missing[*]}"
        exit 1
    fi

    for var in SECMAN_USERNAME SECMAN_PASSWORD SECMAN_API_KEY SECMAN_TEST_DOMAIN; do
        if [[ -z "${!var:-}" ]]; then
            log_error "$var environment variable not set"
            exit 1
        fi
    done

    log_success "Prerequisites check passed"
}

# Resolve credentials from 1Password
resolve_credentials() {
    log_info "Resolving credentials from 1Password..."

    resolve_op_var() {
        local val="$1"
        if [[ "$val" == op://* ]]; then
            op read "$val" 2>/dev/null || { log_error "Failed to resolve $val from 1Password"; exit 1; }
        else
            echo "$val"
        fi
    }

    RESOLVED_USERNAME=$(resolve_op_var "$SECMAN_USERNAME")
    RESOLVED_PASSWORD=$(resolve_op_var "$SECMAN_PASSWORD")
    API_KEY=$(resolve_op_var "$SECMAN_API_KEY")
    RESOLVED_TEST_DOMAIN=$(resolve_op_var "$SECMAN_TEST_DOMAIN")

    TEST_USER_EMAIL="e2e-default-roles-$(date +%s)@${RESOLVED_TEST_DOMAIN}"

    log_success "Credentials resolved"
}

# MCP tool call helper
mcp_call() {
    local tool_name="$1"
    local arguments="$2"
    local user_email="${3:-${RESOLVED_USERNAME}}"
    local request_id="e2e-$(date +%s)-$RANDOM"

    log_debug "MCP call: $tool_name (as $user_email)"
    log_debug "Arguments: $arguments"

    local request_body
    request_body=$(jq -n \
        --arg id "$request_id" \
        --arg name "$tool_name" \
        --argjson args "$arguments" \
        '{jsonrpc: "2.0", id: $id, method: "tools/call", params: {name: $name, arguments: $args}}')

    local response
    response=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/mcp/tools/call" \
        -H "X-MCP-API-Key: ${API_KEY}" \
        -H "Content-Type: application/json" \
        -H "X-MCP-User-Email: ${user_email}" \
        -d "$request_body")

    local http_code
    http_code=$(echo "$response" | tail -n1)
    response=$(echo "$response" | sed '$d')

    log_debug "HTTP status: $http_code"
    log_debug "MCP response: $response"
    echo "$response"
}

# Step 1: Create user WITHOUT roles
create_test_user_without_roles() {
    log_info "Step 1: Creating user via MCP add_user WITHOUT specifying roles..."
    log_debug "Username: $TEST_USER_NAME / Email: $TEST_USER_EMAIL"

    local args
    args=$(jq -c -n \
        --arg username "$TEST_USER_NAME" \
        --arg email "$TEST_USER_EMAIL" \
        --arg password "TestPass123!" \
        '{username: $username, email: $email, password: $password}')

    local response
    response=$(mcp_call "add_user" "$args")

    # Check for JSON-RPC error
    local error_code
    error_code=$(echo "$response" | jq -r '.error.code // empty')
    if [[ -n "$error_code" ]]; then
        log_error "Failed to create user: $(echo "$response" | jq -r '.error.message')"
        exit 1
    fi

    TEST_USER_ID=$(echo "$response" | jq -r '.result.content.user.id // empty')
    if [[ -z "$TEST_USER_ID" || "$TEST_USER_ID" == "null" ]]; then
        log_error "Failed to get user ID from response"
        log_debug "Response: $response"
        exit 1
    fi

    log_success "Created user (ID: $TEST_USER_ID) without specifying roles"

    # Return the response for role verification
    echo "$response"
}

# Step 2: Verify default roles
verify_default_roles() {
    local response="$1"

    log_info "Step 2: Verifying default roles are USER, VULN, REQ..."

    # Extract roles array from response, sort for stable comparison
    local actual_roles
    actual_roles=$(echo "$response" | jq -r '.result.content.user.roles[]' 2>/dev/null | sort | tr '\n' ' ' | sed 's/ $//')

    log_debug "Expected roles: $EXPECTED_ROLES"
    log_debug "Actual roles:   $actual_roles"

    if [[ "$actual_roles" == "$EXPECTED_ROLES" ]]; then
        log_success "Default roles are correct: $actual_roles"
    else
        log_error "Role mismatch!"
        log_error "  Expected: $EXPECTED_ROLES"
        log_error "  Actual:   $actual_roles"
        exit 1
    fi

    # Verify role count is exactly 3
    local role_count
    role_count=$(echo "$response" | jq '.result.content.user.roles | length')
    if [[ "$role_count" -eq 3 ]]; then
        log_success "Role count is correct: $role_count"
    else
        log_error "Expected 3 roles, got $role_count"
        exit 1
    fi
}

# Cleanup function
cleanup() {
    if [[ "$CLEANUP_DONE" == "true" ]]; then
        return
    fi
    CLEANUP_DONE=true

    log_info "Step 3: Cleaning up test data..."

    if [[ -n "$TEST_USER_ID" && "$TEST_USER_ID" != "null" ]]; then
        local args
        args=$(jq -n --argjson userId "$TEST_USER_ID" '{userId: $userId}')
        local response
        response=$(mcp_call "delete_user" "$args" 2>/dev/null || true)
        local error_code
        error_code=$(echo "$response" | jq -r '.error.code // empty' 2>/dev/null || true)
        if [[ -z "$error_code" ]]; then
            log_info "Deleted test user (ID: $TEST_USER_ID)"
        else
            log_warn "Failed to delete user: $(echo "$response" | jq -r '.error.message' 2>/dev/null || echo 'unknown error')"
        fi
    fi

    log_success "Cleanup completed"
}

# Main
main() {
    echo ""
    echo "=== MCP E2E Test: Default User Roles (080-default-user-roles) ==="
    echo ""

    trap cleanup EXIT

    check_prerequisites
    resolve_credentials

    local create_response
    create_response=$(create_test_user_without_roles)
    verify_default_roles "$create_response"

    echo ""
    echo "=== TEST PASSED ==="
    echo ""
}

main "$@"
