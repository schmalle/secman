#!/bin/bash

# Secman Translation Feature Test Script
# This script provides comprehensive command-line testing for the translation functionality
# Author: Generated for Secman Project
# Version: 1.0

set -e  # Exit on any error

# =============================================================================
# CONFIGURATION AND SETUP
# =============================================================================

# Default configuration
DEFAULT_BASE_URL="http://localhost:9000"
DEFAULT_FRONTEND_URL="http://localhost:4321"
DEFAULT_TEST_USER="admin@secman.local"
DEFAULT_TEST_PASSWORD="admin123"
DEFAULT_TARGET_LANGUAGE="de"
DEFAULT_OUTPUT_DIR="./test-results"
DEFAULT_LOG_LEVEL="INFO"

# Test configuration (can be overridden by environment variables)
BASE_URL="${SECMAN_TEST_BASE_URL:-$DEFAULT_BASE_URL}"
FRONTEND_URL="${SECMAN_TEST_FRONTEND_URL:-$DEFAULT_FRONTEND_URL}"
TEST_USER="${SECMAN_TEST_USER:-$DEFAULT_TEST_USER}"
TEST_PASSWORD="${SECMAN_TEST_PASSWORD:-$DEFAULT_TEST_PASSWORD}"
TARGET_LANGUAGE="${SECMAN_TEST_LANGUAGE:-$DEFAULT_TARGET_LANGUAGE}"
OUTPUT_DIR="${SECMAN_TEST_OUTPUT_DIR:-$DEFAULT_OUTPUT_DIR}"
LOG_LEVEL="${SECMAN_TEST_LOG_LEVEL:-$DEFAULT_LOG_LEVEL}"

# Test OpenRouter API key (required for translation tests)
OPENROUTER_API_KEY="${SECMAN_OPENROUTER_API_KEY:-}"

# Global variables
SESSION_COOKIE=""
CSRF_TOKEN=""
TEST_RESULTS=()
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0
START_TIME=""
TEST_CONFIG_ID=""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# =============================================================================
# UTILITY FUNCTIONS
# =============================================================================

log() {
    local level=$1
    shift
    local message="$*"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    
    case $level in
        "ERROR")
            echo -e "${RED}[$timestamp] [ERROR] $message${NC}" >&2
            ;;
        "WARN")
            if [[ "$LOG_LEVEL" != "ERROR" ]]; then
                echo -e "${YELLOW}[$timestamp] [WARN] $message${NC}"
            fi
            ;;
        "INFO")
            if [[ "$LOG_LEVEL" =~ ^(INFO|DEBUG)$ ]]; then
                echo -e "${BLUE}[$timestamp] [INFO] $message${NC}"
            fi
            ;;
        "SUCCESS")
            echo -e "${GREEN}[$timestamp] [SUCCESS] $message${NC}"
            ;;
        "DEBUG")
            if [[ "$LOG_LEVEL" == "DEBUG" ]]; then
                echo -e "[$timestamp] [DEBUG] $message"
            fi
            ;;
    esac
}

create_output_dir() {
    mkdir -p "$OUTPUT_DIR"
    log "INFO" "Created output directory: $OUTPUT_DIR"
}

cleanup() {
    log "INFO" "Cleaning up test environment..."
    
    # Clean up test translation configuration if created
    if [[ -n "$TEST_CONFIG_ID" ]]; then
        log "INFO" "Cleaning up test translation configuration ID: $TEST_CONFIG_ID"
        curl -s -X DELETE \
            -H "Cookie: $SESSION_COOKIE" \
            -H "X-CSRF-Token: $CSRF_TOKEN" \
            "$BASE_URL/api/translation-config/$TEST_CONFIG_ID" > /dev/null 2>&1 || true
    fi
    
    log "INFO" "Cleanup completed"
}

# Set up cleanup trap
trap cleanup EXIT

# =============================================================================
# AUTHENTICATION FUNCTIONS
# =============================================================================

authenticate() {
    log "INFO" "Authenticating with Secman..."
    
    # First, get CSRF token from login page
    local response=$(curl -s -c /tmp/secman_cookies.txt "$FRONTEND_URL/login" || true)
    
    if [[ -z "$response" ]]; then
        log "ERROR" "Failed to connect to frontend at $FRONTEND_URL"
        return 1
    fi
    
    # Extract CSRF token (this might need adjustment based on actual implementation)
    CSRF_TOKEN=$(curl -s -b /tmp/secman_cookies.txt "$FRONTEND_URL/api/csrf" | jq -r '.token' 2>/dev/null || echo "")
    
    # Perform login
    local login_response=$(curl -s -b /tmp/secman_cookies.txt -c /tmp/secman_cookies.txt \
        -X POST \
        -H "Content-Type: application/json" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        -d "{\"email\":\"$TEST_USER\",\"password\":\"$TEST_PASSWORD\"}" \
        "$BASE_URL/api/auth/login" 2>/dev/null || echo '{"error": "connection_failed"}')
    
    if echo "$login_response" | jq -e '.error' > /dev/null 2>&1; then
        log "ERROR" "Authentication failed: $(echo "$login_response" | jq -r '.error')"
        return 1
    fi
    
    # Extract session cookie
    SESSION_COOKIE=$(grep -E "(PLAY_SESSION|secman_session)" /tmp/secman_cookies.txt | tail -1 | cut -f6,7 | tr '\\t' '=' 2>/dev/null || echo "")
    
    if [[ -z "$SESSION_COOKIE" ]]; then
        log "ERROR" "Failed to extract session cookie"
        return 1
    fi
    
    log "SUCCESS" "Authentication successful"
    return 0
}

# =============================================================================
# TEST EXECUTION FUNCTIONS
# =============================================================================

run_test() {
    local test_name="$1"
    local test_function="$2"
    local test_description="$3"
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    log "INFO" "Running test: $test_name - $test_description"
    
    if $test_function; then
        PASSED_TESTS=$((PASSED_TESTS + 1))
        TEST_RESULTS+=("PASS: $test_name - $test_description")
        log "SUCCESS" "Test passed: $test_name"
        return 0
    else
        FAILED_TESTS=$((FAILED_TESTS + 1))
        TEST_RESULTS+=("FAIL: $test_name - $test_description")
        log "ERROR" "Test failed: $test_name"
        return 1
    fi
}

# =============================================================================
# TRANSLATION CONFIGURATION TESTS
# =============================================================================

test_create_translation_config() {
    if [[ -z "$OPENROUTER_API_KEY" ]]; then
        log "WARN" "OPENROUTER_API_KEY not set, using dummy key for test"
        local api_key="test-key-12345"
    else
        local api_key="$OPENROUTER_API_KEY"
    fi
    
    local config_data='{
        "apiKey": "'$api_key'",
        "baseUrl": "https://openrouter.ai/api/v1",
        "modelName": "anthropic/claude-3-haiku",
        "maxTokens": 4000,
        "temperature": 0.3,
        "isActive": true
    }'
    
    local response=$(curl -s -X POST \
        -H "Content-Type: application/json" \
        -H "Cookie: $SESSION_COOKIE" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        -d "$config_data" \
        "$BASE_URL/api/translation-config" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '.id' > /dev/null 2>&1; then
        TEST_CONFIG_ID=$(echo "$response" | jq -r '.id')
        log "DEBUG" "Created translation config with ID: $TEST_CONFIG_ID"
        return 0
    else
        log "DEBUG" "Failed to create translation config: $response"
        return 1
    fi
}

test_get_translation_configs() {
    local response=$(curl -s \
        -H "Cookie: $SESSION_COOKIE" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        "$BASE_URL/api/translation-config" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '. | length' > /dev/null 2>&1; then
        local count=$(echo "$response" | jq '. | length')
        log "DEBUG" "Retrieved $count translation configurations"
        return 0
    else
        log "DEBUG" "Failed to get translation configs: $response"
        return 1
    fi
}

test_get_active_translation_config() {
    local response=$(curl -s \
        -H "Cookie: $SESSION_COOKIE" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        "$BASE_URL/api/translation-config/active" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '.id' > /dev/null 2>&1; then
        log "DEBUG" "Retrieved active translation config"
        return 0
    elif echo "$response" | jq -e '.message' | grep -q "No active"; then
        log "DEBUG" "No active translation config found (expected for clean test environment)"
        return 0
    else
        log "DEBUG" "Failed to get active translation config: $response"
        return 1
    fi
}

test_update_translation_config() {
    if [[ -z "$TEST_CONFIG_ID" ]]; then
        log "DEBUG" "No test config ID available for update test"
        return 1
    fi
    
    local update_data='{
        "modelName": "anthropic/claude-3-sonnet",
        "maxTokens": 8000,
        "temperature": 0.1
    }'
    
    local response=$(curl -s -X PUT \
        -H "Content-Type: application/json" \
        -H "Cookie: $SESSION_COOKIE" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        -d "$update_data" \
        "$BASE_URL/api/translation-config/$TEST_CONFIG_ID" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '.id' > /dev/null 2>&1; then
        log "DEBUG" "Updated translation config successfully"
        return 0
    else
        log "DEBUG" "Failed to update translation config: $response"
        return 1
    fi
}

test_translation_config_test_endpoint() {
    if [[ -z "$TEST_CONFIG_ID" ]]; then
        log "DEBUG" "No test config ID available for test endpoint"
        return 1
    fi
    
    local response=$(curl -s -X POST \
        -H "Cookie: $SESSION_COOKIE" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        "$BASE_URL/api/translation-config/$TEST_CONFIG_ID/test" 2>/dev/null || echo '{"error": "request_failed"}')
    
    # Accept both success and API key error as valid responses
    if echo "$response" | jq -e '.success' > /dev/null 2>&1; then
        log "DEBUG" "Translation config test successful"
        return 0
    elif echo "$response" | jq -e '.error' | grep -i "api.*key\|auth\|credential" > /dev/null 2>&1; then
        log "DEBUG" "Translation config test failed due to API key (expected for dummy key)"
        return 0
    else
        log "DEBUG" "Failed to test translation config: $response"
        return 1
    fi
}

# =============================================================================
# REQUIREMENTS TRANSLATION TESTS
# =============================================================================

test_get_requirements() {
    local response=$(curl -s \
        -H "Cookie: $SESSION_COOKIE" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        "$BASE_URL/api/requirements" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '. | length' > /dev/null 2>&1; then
        local count=$(echo "$response" | jq '. | length')
        log "DEBUG" "Retrieved $count requirements for translation testing"
        return 0
    else
        log "DEBUG" "Failed to get requirements: $response"
        return 1
    fi
}

test_export_translated_docx() {
    local temp_file="$OUTPUT_DIR/translated_export_${TARGET_LANGUAGE}.docx"
    
    # Test the translated export endpoint
    local http_code=$(curl -s -w "%{http_code}" -o "$temp_file" \
        -H "Cookie: $SESSION_COOKIE" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        "$BASE_URL/api/requirements/export/docx/translated/$TARGET_LANGUAGE" 2>/dev/null || echo "000")
    
    if [[ "$http_code" == "200" ]] && [[ -f "$temp_file" ]] && [[ -s "$temp_file" ]]; then
        local file_size=$(stat -f%z "$temp_file" 2>/dev/null || stat -c%s "$temp_file" 2>/dev/null || echo "0")
        log "DEBUG" "Successfully exported translated DOCX (${file_size} bytes)"
        return 0
    else
        log "DEBUG" "Failed to export translated DOCX (HTTP: $http_code)"
        rm -f "$temp_file"
        return 1
    fi
}

test_export_translated_docx_by_usecase() {
    # First, get available use cases
    local usecases_response=$(curl -s \
        -H "Cookie: $SESSION_COOKIE" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        "$BASE_URL/api/usecases" 2>/dev/null || echo '[]')
    
    local usecase_count=$(echo "$usecases_response" | jq '. | length' 2>/dev/null || echo "0")
    
    if [[ "$usecase_count" == "0" ]]; then
        log "DEBUG" "No use cases available for testing usecase-specific translation export"
        return 0  # Not a failure, just no data to test with
    fi
    
    local first_usecase_id=$(echo "$usecases_response" | jq -r '.[0].id' 2>/dev/null || echo "")
    
    if [[ -z "$first_usecase_id" || "$first_usecase_id" == "null" ]]; then
        log "DEBUG" "Could not extract valid usecase ID"
        return 1
    fi
    
    local temp_file="$OUTPUT_DIR/translated_usecase_${first_usecase_id}_${TARGET_LANGUAGE}.docx"
    
    local http_code=$(curl -s -w "%{http_code}" -o "$temp_file" \
        -H "Cookie: $SESSION_COOKIE" \
        -H "X-CSRF-Token: $CSRF_TOKEN" \
        "$BASE_URL/api/requirements/export/docx/usecase/$first_usecase_id/translated/$TARGET_LANGUAGE" 2>/dev/null || echo "000")
    
    if [[ "$http_code" == "200" ]] && [[ -f "$temp_file" ]] && [[ -s "$temp_file" ]]; then
        local file_size=$(stat -f%z "$temp_file" 2>/dev/null || stat -c%s "$temp_file" 2>/dev/null || echo "0")
        log "DEBUG" "Successfully exported translated DOCX for usecase $first_usecase_id (${file_size} bytes)"
        return 0
    else
        log "DEBUG" "Failed to export translated DOCX for usecase (HTTP: $http_code)"
        rm -f "$temp_file"
        return 1
    fi
}

# =============================================================================
# SYSTEM VALIDATION TESTS
# =============================================================================

test_backend_connectivity() {
    local response=$(curl -s "$BASE_URL/api/health" 2>/dev/null || curl -s "$BASE_URL/" 2>/dev/null || echo "")
    
    if [[ -n "$response" ]]; then
        log "DEBUG" "Backend connectivity successful"
        return 0
    else
        log "DEBUG" "Backend connectivity failed"
        return 1
    fi
}

test_frontend_connectivity() {
    local response=$(curl -s "$FRONTEND_URL/" 2>/dev/null || echo "")
    
    if [[ -n "$response" ]]; then
        log "DEBUG" "Frontend connectivity successful"
        return 0
    else
        log "DEBUG" "Frontend connectivity failed"
        return 1
    fi
}

test_authentication_required() {
    # Test that translation endpoints require authentication
    local http_code=$(curl -s -w "%{http_code}" -o /dev/null \
        "$BASE_URL/api/translation-config" 2>/dev/null || echo "000")
    
    if [[ "$http_code" == "401" || "$http_code" == "403" || "$http_code" == "302" ]]; then
        log "DEBUG" "Authentication properly required (HTTP: $http_code)"
        return 0
    else
        log "DEBUG" "Authentication not properly enforced (HTTP: $http_code)"
        return 1
    fi
}

# =============================================================================
# TEST SUITES
# =============================================================================

run_connectivity_tests() {
    log "INFO" "=== Running Connectivity Tests ==="
    
    run_test "CONN-001" "test_backend_connectivity" "Backend connectivity test"
    run_test "CONN-002" "test_frontend_connectivity" "Frontend connectivity test"
    run_test "CONN-003" "test_authentication_required" "Authentication requirement test"
}

run_translation_config_tests() {
    log "INFO" "=== Running Translation Configuration Tests ==="
    
    run_test "TC-001" "test_create_translation_config" "Create translation configuration"
    run_test "TC-002" "test_get_translation_configs" "Get all translation configurations"
    run_test "TC-003" "test_get_active_translation_config" "Get active translation configuration"
    run_test "TC-004" "test_update_translation_config" "Update translation configuration"
    run_test "TC-005" "test_translation_config_test_endpoint" "Test translation configuration endpoint"
}

run_translation_tests() {
    log "INFO" "=== Running Translation Tests ==="
    
    run_test "TR-001" "test_get_requirements" "Get requirements for translation"
    run_test "TR-002" "test_export_translated_docx" "Export all requirements with translation"
    run_test "TR-003" "test_export_translated_docx_by_usecase" "Export usecase requirements with translation"
}

run_all_tests() {
    log "INFO" "Starting comprehensive translation feature tests..."
    START_TIME=$(date +%s)
    
    # Create output directory
    create_output_dir
    
    # Authenticate first
    if ! authenticate; then
        log "ERROR" "Authentication failed, cannot continue with tests"
        exit 1
    fi
    
    # Run test suites
    run_connectivity_tests
    run_translation_config_tests
    run_translation_tests
    
    # Generate test report
    generate_test_report
}

run_quick_tests() {
    log "INFO" "Starting quick translation tests..."
    START_TIME=$(date +%s)
    
    create_output_dir
    
    if ! authenticate; then
        log "ERROR" "Authentication failed, cannot continue with tests"
        exit 1
    fi
    
    # Run essential tests only
    run_test "QUICK-001" "test_backend_connectivity" "Backend connectivity"
    run_test "QUICK-002" "test_get_translation_configs" "Get translation configs"
    run_test "QUICK-003" "test_get_requirements" "Get requirements"
    
    generate_test_report
}

# =============================================================================
# REPORTING FUNCTIONS
# =============================================================================

generate_test_report() {
    local end_time=$(date +%s)
    local duration=$((end_time - START_TIME))
    local report_file="$OUTPUT_DIR/translation_test_report_$(date +%Y%m%d_%H%M%S).txt"
    
    log "INFO" "Generating test report..."
    
    {
        echo "========================================"
        echo "SECMAN TRANSLATION FEATURE TEST REPORT"
        echo "========================================"
        echo
        echo "Test Execution Summary:"
        echo "- Start Time: $(date -d @$START_TIME 2>/dev/null || date -r $START_TIME 2>/dev/null || echo "Unknown")"
        echo "- End Time: $(date)"
        echo "- Duration: ${duration} seconds"
        echo "- Total Tests: $TOTAL_TESTS"
        echo "- Passed: $PASSED_TESTS"
        echo "- Failed: $FAILED_TESTS"
        echo "- Success Rate: $(( TOTAL_TESTS > 0 ? (PASSED_TESTS * 100) / TOTAL_TESTS : 0 ))%"
        echo
        echo "Configuration:"
        echo "- Base URL: $BASE_URL"
        echo "- Frontend URL: $FRONTEND_URL"
        echo "- Target Language: $TARGET_LANGUAGE"
        echo "- Test User: $TEST_USER"
        echo "- Output Directory: $OUTPUT_DIR"
        echo
        echo "Test Results:"
        echo "-------------"
        
        for result in "${TEST_RESULTS[@]}"; do
            echo "$result"
        done
        
        echo
        echo "========================================"
    } > "$report_file"
    
    log "SUCCESS" "Test report generated: $report_file"
    
    # Also display summary to console
    echo
    log "INFO" "=== TEST EXECUTION SUMMARY ==="
    log "INFO" "Total Tests: $TOTAL_TESTS"
    log "SUCCESS" "Passed: $PASSED_TESTS"
    if [[ $FAILED_TESTS -gt 0 ]]; then
        log "ERROR" "Failed: $FAILED_TESTS"
    else
        log "SUCCESS" "Failed: $FAILED_TESTS"
    fi
    log "INFO" "Duration: ${duration} seconds"
    log "INFO" "Report saved to: $report_file"
}

# =============================================================================
# MAIN SCRIPT LOGIC
# =============================================================================

show_help() {
    cat << EOF
Secman Translation Feature Test Script

USAGE:
    $0 [OPTIONS] [COMMAND]

COMMANDS:
    all         Run all translation tests (default)
    quick       Run quick essential tests only
    config      Run translation configuration tests only
    translate   Run translation functionality tests only
    help        Show this help message

OPTIONS:
    -u, --base-url URL          Backend base URL (default: $DEFAULT_BASE_URL)
    -f, --frontend-url URL      Frontend URL (default: $DEFAULT_FRONTEND_URL)
    -l, --language LANG         Target language for translation tests (default: $DEFAULT_TARGET_LANGUAGE)
    -o, --output-dir DIR        Output directory for test results (default: $DEFAULT_OUTPUT_DIR)
    -v, --verbose               Enable verbose logging (DEBUG level)
    -q, --quiet                 Quiet mode (ERROR level only)
    --user EMAIL                Test user email (default: $DEFAULT_TEST_USER)
    --password PASS             Test user password (default: $DEFAULT_TEST_PASSWORD)

ENVIRONMENT VARIABLES:
    SECMAN_TEST_BASE_URL        Backend base URL
    SECMAN_TEST_FRONTEND_URL    Frontend URL
    SECMAN_TEST_USER            Test user email
    SECMAN_TEST_PASSWORD        Test user password
    SECMAN_TEST_LANGUAGE        Target translation language
    SECMAN_TEST_OUTPUT_DIR      Output directory
    SECMAN_TEST_LOG_LEVEL       Log level (ERROR, WARN, INFO, DEBUG)
    SECMAN_OPENROUTER_API_KEY   OpenRouter API key for translation tests

EXAMPLES:
    # Run all tests with custom backend URL
    $0 --base-url http://staging.secman.local:9000

    # Run quick tests in German
    $0 quick --language de

    # Run only configuration tests with verbose output
    $0 config --verbose

    # Run with custom user and output directory
    $0 --user test@example.com --password testpass --output-dir ./my-results

NOTES:
    - The script requires authentication to the Secman system
    - Translation tests require a valid OpenRouter API key
    - Test results and exported files are saved to the output directory
    - The script will clean up any test data it creates

EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -u|--base-url)
            BASE_URL="$2"
            shift 2
            ;;
        -f|--frontend-url)
            FRONTEND_URL="$2"
            shift 2
            ;;
        -l|--language)
            TARGET_LANGUAGE="$2"
            shift 2
            ;;
        -o|--output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        -v|--verbose)
            LOG_LEVEL="DEBUG"
            shift
            ;;
        -q|--quiet)
            LOG_LEVEL="ERROR"
            shift
            ;;
        --user)
            TEST_USER="$2"
            shift 2
            ;;
        --password)
            TEST_PASSWORD="$2"
            shift 2
            ;;
        -h|--help|help)
            show_help
            exit 0
            ;;
        all|quick|config|translate)
            COMMAND="$1"
            shift
            ;;
        *)
            log "ERROR" "Unknown option: $1"
            echo "Use '$0 --help' for usage information."
            exit 1
            ;;
    esac
done

# Set default command
COMMAND="${COMMAND:-all}"

# Validate dependencies
command -v curl >/dev/null 2>&1 || { log "ERROR" "curl is required but not installed."; exit 1; }
command -v jq >/dev/null 2>&1 || { log "ERROR" "jq is required but not installed."; exit 1; }

# Main execution
log "INFO" "Starting Secman Translation Feature Tests"
log "INFO" "Command: $COMMAND"
log "INFO" "Base URL: $BASE_URL"
log "INFO" "Target Language: $TARGET_LANGUAGE"

case $COMMAND in
    "all")
        run_all_tests
        ;;
    "quick")
        run_quick_tests
        ;;
    "config")
        create_output_dir
        authenticate || exit 1
        run_translation_config_tests
        generate_test_report
        ;;
    "translate")
        create_output_dir
        authenticate || exit 1
        run_translation_tests
        generate_test_report
        ;;
    *)
        log "ERROR" "Unknown command: $COMMAND"
        exit 1
        ;;
esac

# Final cleanup is handled by the trap
log "SUCCESS" "Translation feature testing completed"
exit 0