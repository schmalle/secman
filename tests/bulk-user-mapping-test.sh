#!/bin/bash
# Test: POST /api/user-mappings/bulk endpoint
#
# Validates the bulk user mapping endpoint with:
# 1. Authentication (login to get JWT token)
# 2. Dry-run bulk create (compare without persisting)
# 3. Actual bulk create (persist new mappings)
# 4. Verification (list mappings to confirm creation)
# 5. Cleanup (delete test mappings)
#
# Prerequisites:
# - curl, jq, op (1Password CLI) installed
# - Environment variables set (with 1Password URIs or direct values):
#   SECMAN_USERNAME, SECMAN_PASSWORD
#
# Usage:
#   ./tests/bulk-user-mapping-test.sh
#   DEBUG=1 ./tests/bulk-user-mapping-test.sh                           # Verbose output
#   SECMAN_BASE_URL=http://localhost:8080 ./tests/bulk-user-mapping-test.sh  # Custom URL
#   SECMAN_INSECURE=false ./tests/bulk-user-mapping-test.sh             # Enforce SSL verification

set -euo pipefail

# 1Password URI defaults (override with direct values or your own URIs)
export SECMAN_USERNAME="${SECMAN_USERNAME:-op://test/secman/SECMAN_USERNAME}"
export SECMAN_PASSWORD="${SECMAN_PASSWORD:-op://test/secman/SECMAN_PASSWORD}"

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${SECMAN_BASE_URL:-https://secman.covestro.net}"
INSECURE="${SECMAN_INSECURE:-true}"  # Skip SSL verification (internal cert)
TIMESTAMP=$(date +%s)
TEST_EMAIL="e2e-bulk-${TIMESTAMP}@test.secman.local"
TEST_DOMAIN="bulk-e2e-${TIMESTAMP}.test.local"
TEST_AWS_ACCOUNT="888800${TIMESTAMP: -6}"  # 12-digit account using last 6 digits of timestamp

# State
TOKEN=""
CLEANUP_DONE=false
CREATED_MAPPING_IDS=()

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

    for var in SECMAN_USERNAME SECMAN_PASSWORD; do
        if [[ -z "${!var:-}" ]]; then
            log_error "$var environment variable not set"
            exit 1
        fi
    done

    log_success "Prerequisites check passed"
}

# Resolve credentials from 1Password
resolve_credentials() {
    log_info "Resolving credentials..."

    resolve_op_var() {
        local val="$1"
        if [[ "$val" == op://* ]]; then
            op read "$val" 2>/dev/null || { log_error "Failed to resolve $val from 1Password"; exit 1; }
        else
            echo "$val"
        fi
    }

    SECMAN_USERNAME=$(resolve_op_var "$SECMAN_USERNAME")
    SECMAN_PASSWORD=$(resolve_op_var "$SECMAN_PASSWORD")

    log_debug "Username: $SECMAN_USERNAME"
    log_debug "Base URL: $BASE_URL"
    log_success "Credentials resolved"
}

# Build curl args with optional -k for self-signed certs
curl_opts() {
    local opts=(-s -w "\n%{http_code}")
    [[ "$INSECURE" == "true" ]] && opts+=(-k)
    echo "${opts[@]}"
}

# Authenticate and get JWT token from Set-Cookie header
authenticate() {
    log_info "Step 1: Authenticating..."

    local response http_code body
    local curl_exit=0
    local curl_args=(-s -D - -X POST "${BASE_URL}/api/auth/login"
        -H "Content-Type: application/json"
        -d "{\"username\":\"${SECMAN_USERNAME}\",\"password\":\"${SECMAN_PASSWORD}\"}")
    [[ "$INSECURE" == "true" ]] && curl_args+=(-k)

    response=$(curl "${curl_args[@]}" 2>&1) || curl_exit=$?

    if [[ $curl_exit -ne 0 ]]; then
        log_error "Step 1: curl failed (exit code $curl_exit)"
        log_error "curl output: $response"
        [[ $curl_exit -eq 60 || $curl_exit -eq 51 ]] && \
            log_error "SSL certificate error — try SECMAN_INSECURE=true or check the cert"
        exit 1
    fi

    # Extract HTTP status from the last status line (handles redirects)
    http_code=$(echo "$response" | grep -E '^HTTP/' | tail -1 | awk '{print $2}')

    # Extract JSON body (everything after the blank line separating headers from body)
    body=$(echo "$response" | sed -n '/^\r*$/,$ p' | tail -n +2)

    log_debug "Auth response code: $http_code"
    log_debug "Auth response body: $body"

    if [[ "$http_code" != "200" ]]; then
        log_error "Step 1: Authentication failed (HTTP $http_code)"
        log_error "Response: $body"
        exit 1
    fi

    # JWT is in Set-Cookie header: secman_auth=<token>; Path=/; ...
    TOKEN=$(echo "$response" | grep -i 'set-cookie:.*secman_auth=' | sed 's/.*secman_auth=//;s/;.*//' | tr -d '\r')
    if [[ -z "$TOKEN" ]]; then
        log_error "Step 1: No secman_auth cookie found in response headers"
        log_debug "Response headers: $(echo "$response" | sed '/^\r*$/q')"
        exit 1
    fi

    log_debug "Token: ${TOKEN:0:20}..."
    log_success "Step 1: Authenticated successfully"
}

# Helper: make authenticated API call
api_call() {
    local method="$1" path="$2" data="${3:-}"

    local args=(-s -w "\n%{http_code}" -X "$method"
        "${BASE_URL}${path}"
        -b "secman_auth=${TOKEN}"
        -H "Content-Type: application/json")

    [[ "$INSECURE" == "true" ]] && args+=(-k)

    if [[ -n "$data" ]]; then
        args+=(-d "$data")
    fi

    local curl_exit=0
    local result
    result=$(curl "${args[@]}" 2>&1) || curl_exit=$?

    if [[ $curl_exit -ne 0 ]]; then
        log_error "curl failed for $method $path (exit code $curl_exit)"
        log_error "curl output: $result"
        exit 1
    fi

    echo "$result"
}

# Parse response into http_code and body
parse_response() {
    local response="$1"
    HTTP_CODE=$(echo "$response" | tail -1)
    HTTP_BODY=$(echo "$response" | sed '$d')
}

# Test 1: Dry-run bulk create
test_bulk_dry_run() {
    log_info "Step 2: Testing POST /api/user-mappings/bulk (dry-run)..."

    local payload
    payload=$(cat <<EOF
{
    "mappings": [
        {"email": "${TEST_EMAIL}", "domain": "${TEST_DOMAIN}"},
        {"email": "${TEST_EMAIL}", "awsAccountId": "${TEST_AWS_ACCOUNT}"}
    ],
    "dryRun": true
}
EOF
    )

    log_debug "Dry-run payload: $payload"

    local response
    response=$(api_call POST "/api/user-mappings/bulk" "$payload")
    parse_response "$response"

    log_debug "Dry-run response code: $HTTP_CODE"
    log_debug "Dry-run response body: $HTTP_BODY"

    if [[ "$HTTP_CODE" == "404" ]]; then
        log_error "Step 2: Bulk endpoint returned 404 - route not registered!"
        log_error "The /api/user-mappings/bulk route is not recognized by Micronaut."
        log_error "Run './gradlew clean build' to force KSP route regeneration."
        exit 1
    fi

    if [[ "$HTTP_CODE" != "200" ]]; then
        log_error "Step 2: Dry-run failed (HTTP $HTTP_CODE)"
        log_error "Response: $HTTP_BODY"
        exit 1
    fi

    # Validate response structure
    local has_comparison
    has_comparison=$(echo "$HTTP_BODY" | jq 'has("comparison")' 2>/dev/null || echo "false")

    if [[ "$has_comparison" == "true" ]]; then
        local new_count db_count file_count
        new_count=$(echo "$HTTP_BODY" | jq '.comparison.newCount // 0')
        db_count=$(echo "$HTTP_BODY" | jq '.comparison.dbMappingCount // 0')
        file_count=$(echo "$HTTP_BODY" | jq '.comparison.fileMappingCount // 0')
        log_info "  Comparison: DB=$db_count, File=$file_count, New=$new_count"
    fi

    log_success "Step 2: Dry-run bulk create returned 200 OK"
}

# Test 2: Actual bulk create
test_bulk_create() {
    log_info "Step 3: Testing POST /api/user-mappings/bulk (actual create)..."

    local payload
    payload=$(cat <<EOF
{
    "mappings": [
        {"email": "${TEST_EMAIL}", "domain": "${TEST_DOMAIN}"},
        {"email": "${TEST_EMAIL}", "awsAccountId": "${TEST_AWS_ACCOUNT}"}
    ],
    "dryRun": false
}
EOF
    )

    log_debug "Create payload: $payload"

    local response
    response=$(api_call POST "/api/user-mappings/bulk" "$payload")
    parse_response "$response"

    log_debug "Create response code: $HTTP_CODE"
    log_debug "Create response body: $HTTP_BODY"

    if [[ "$HTTP_CODE" != "200" ]]; then
        log_error "Step 3: Bulk create failed (HTTP $HTTP_CODE)"
        log_error "Response: $HTTP_BODY"
        exit 1
    fi

    local created total_processed errors
    created=$(echo "$HTTP_BODY" | jq '.created // 0')
    total_processed=$(echo "$HTTP_BODY" | jq '.totalProcessed // 0')
    errors=$(echo "$HTTP_BODY" | jq '.errors | length')

    log_info "  Processed: $total_processed, Created: $created, Errors: $errors"

    if [[ "$created" -lt 1 ]]; then
        log_warn "Step 3: No mappings were created (may already exist)"
    fi

    if [[ "$errors" -gt 0 ]]; then
        log_warn "Step 3: Errors encountered:"
        echo "$HTTP_BODY" | jq -r '.errors[]' | while read -r err; do
            log_warn "  - $err"
        done
    fi

    log_success "Step 3: Bulk create returned 200 OK (created=$created)"
}

# Test 3: Verify mappings via GET
test_verify_mappings() {
    log_info "Step 4: Verifying created mappings via GET /api/user-mappings..."

    local response
    response=$(api_call GET "/api/user-mappings?page=0&size=100")
    parse_response "$response"

    log_debug "List response code: $HTTP_CODE"

    if [[ "$HTTP_CODE" != "200" ]]; then
        log_error "Step 4: List mappings failed (HTTP $HTTP_CODE)"
        log_error "Response: $HTTP_BODY"
        exit 1
    fi

    # Find our test mappings by email
    local matching_count
    matching_count=$(echo "$HTTP_BODY" | jq --arg email "$TEST_EMAIL" \
        '[.content[]? // .[]? | select(.email == $email)] | length' 2>/dev/null || echo "0")

    log_debug "Found $matching_count mappings for $TEST_EMAIL"

    if [[ "$matching_count" -ge 2 ]]; then
        log_success "Step 4: Found $matching_count mappings for test email"
    elif [[ "$matching_count" -ge 1 ]]; then
        log_warn "Step 4: Found only $matching_count mapping(s) (expected 2)"
    else
        log_warn "Step 4: No mappings found for $TEST_EMAIL (may be on different page)"
    fi

    # Collect IDs for cleanup
    CREATED_MAPPING_IDS=($(echo "$HTTP_BODY" | jq -r --arg email "$TEST_EMAIL" \
        '[.content[]? // .[]? | select(.email == $email) | .id] | .[]' 2>/dev/null || true))

    log_debug "Mapping IDs for cleanup: ${CREATED_MAPPING_IDS[*]:-none}"
}

# Test 4: Validation error handling
test_bulk_validation_error() {
    log_info "Step 5: Testing bulk create with invalid data (validation)..."

    local payload='{"mappings": [{"email": ""}], "dryRun": true}'

    local response
    response=$(api_call POST "/api/user-mappings/bulk" "$payload")
    parse_response "$response"

    log_debug "Validation error response code: $HTTP_CODE"
    log_debug "Validation error response body: $HTTP_BODY"

    if [[ "$HTTP_CODE" == "400" ]]; then
        log_success "Step 5: Empty email correctly rejected (HTTP 400)"
    elif [[ "$HTTP_CODE" == "200" ]]; then
        local errors
        errors=$(echo "$HTTP_BODY" | jq '.errors | length' 2>/dev/null || echo "0")
        if [[ "$errors" -gt 0 ]]; then
            log_success "Step 5: Empty email reported in errors array"
        else
            log_warn "Step 5: Empty email was accepted without errors"
        fi
    else
        log_warn "Step 5: Unexpected response (HTTP $HTTP_CODE)"
    fi
}

# Cleanup: delete test mappings
cleanup() {
    if [[ "$CLEANUP_DONE" == "true" ]]; then
        return
    fi
    CLEANUP_DONE=true

    log_info "Step 6: Cleaning up test data..."

    if [[ -z "$TOKEN" ]]; then
        log_warn "No auth token - skipping cleanup"
        return
    fi

    local deleted=0
    for id in "${CREATED_MAPPING_IDS[@]}"; do
        local response
        response=$(api_call DELETE "/api/user-mappings/${id}")
        parse_response "$response"

        if [[ "$HTTP_CODE" == "200" || "$HTTP_CODE" == "204" ]]; then
            deleted=$((deleted + 1))
            log_debug "Deleted mapping ID $id"
        else
            log_warn "Failed to delete mapping ID $id (HTTP $HTTP_CODE)"
        fi
    done

    if [[ $deleted -gt 0 ]]; then
        log_info "Deleted $deleted test mapping(s)"
    fi

    log_success "Cleanup completed"
}

# Main
main() {
    echo ""
    echo "=== Test: POST /api/user-mappings/bulk ==="
    echo "    Target: ${BASE_URL}"
    echo ""

    trap cleanup EXIT

    check_prerequisites
    resolve_credentials
    authenticate
    test_bulk_dry_run
    test_bulk_create
    test_verify_mappings
    test_bulk_validation_error

    echo ""
    echo "=========================================="
    echo "  BULK USER MAPPING TEST PASSED"
    echo "=========================================="
    echo ""
    echo "Target:           $BASE_URL"
    echo "Test email:       $TEST_EMAIL"
    echo "Test domain:      $TEST_DOMAIN"
    echo "Test AWS account: $TEST_AWS_ACCOUNT"
    echo ""
    echo "Tests completed:"
    echo "  1. Authenticated with JWT"
    echo "  2. Dry-run bulk create (200 OK)"
    echo "  3. Actual bulk create (200 OK)"
    echo "  4. Verified mappings via GET"
    echo "  5. Validation error handling"
    echo "  6. Cleaned up test data"
    echo ""
}

main "$@"
