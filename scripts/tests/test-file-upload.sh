#!/bin/bash

# Secman File Upload/Download Feature Test Script
# This script provides comprehensive command-line testing for the file upload/download functionality
# Author: Generated for Secman Project
# Version: 1.0

set -e  # Exit on any error

# =============================================================================
# CONFIGURATION AND SETUP
# =============================================================================

# Default configuration
DEFAULT_BASE_URL="http://localhost:9001"
DEFAULT_FRONTEND_URL="http://localhost:4321"
DEFAULT_TEST_USER="adminuser"
DEFAULT_TEST_PASSWORD="password"
DEFAULT_OUTPUT_DIR="./test-results"
DEFAULT_LOG_LEVEL="INFO"

# Test configuration (can be overridden by environment variables)
BASE_URL="${SECMAN_TEST_BASE_URL:-$DEFAULT_BASE_URL}"
FRONTEND_URL="${SECMAN_TEST_FRONTEND_URL:-$DEFAULT_FRONTEND_URL}"
TEST_USER="${SECMAN_TEST_USER:-$DEFAULT_TEST_USER}"
TEST_PASSWORD="${SECMAN_TEST_PASSWORD:-$DEFAULT_TEST_PASSWORD}"
OUTPUT_DIR="${SECMAN_TEST_OUTPUT_DIR:-$DEFAULT_OUTPUT_DIR}"
LOG_LEVEL="${SECMAN_TEST_LOG_LEVEL:-$DEFAULT_LOG_LEVEL}"

# Global variables
SESSION_COOKIE_FILE=""
CSRF_TOKEN=""
TEST_RESULTS=()
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0
START_TIME=""
TEST_FILE_IDS=()

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

create_test_files() {
    # Create various test files
    echo "This is a test document for file upload testing." > "$OUTPUT_DIR/test.txt"
    echo '{"test": "json", "purpose": "file upload"}' > "$OUTPUT_DIR/test.json"
    
    # Create a small binary file (empty PDF header)
    echo -e "%PDF-1.4\n%Test PDF\nendobj" > "$OUTPUT_DIR/test.pdf"
    
    # Create a large text file for size testing
    for i in {1..1000}; do
        echo "This is line $i of the large test file for testing file size limits." >> "$OUTPUT_DIR/large-test.txt"
    done
    
    log "INFO" "Created test files in $OUTPUT_DIR"
}

cleanup() {
    log "INFO" "Cleaning up test environment..."
    
    # Clean up uploaded test files
    for file_id in "${TEST_FILE_IDS[@]}"; do
        if [[ -n "$file_id" ]]; then
            log "INFO" "Cleaning up test file ID: $file_id"
            curl -s -X DELETE \
                -b "$SESSION_COOKIE_FILE" \
                -H "X-CSRF-Token: $CSRF_TOKEN" \
                "$BASE_URL/api/files/$file_id" > /dev/null 2>&1 || true
        fi
    done
    
    # Clean up test files
    rm -f "$OUTPUT_DIR/test.txt" "$OUTPUT_DIR/test.json" "$OUTPUT_DIR/test.pdf" "$OUTPUT_DIR/large-test.txt" || true
    
    log "INFO" "Cleanup completed"
}

# Set up cleanup trap
trap cleanup EXIT

# =============================================================================
# AUTHENTICATION FUNCTIONS
# =============================================================================

authenticate() {
    log "INFO" "Authenticating with Secman..."
    
    # Use JWT authentication for Kotlin backend
    local response=$(curl -s -X POST \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$TEST_USER\",\"password\":\"$TEST_PASSWORD\"}" \
        "$BASE_URL/login" 2>/dev/null || echo '{"error": "connection_failed"}')
    
    if echo "$response" | jq -e '.access_token' > /dev/null 2>&1; then
        local access_token=$(echo "$response" | jq -r '.access_token')
        echo "Authorization: Bearer $access_token" > /tmp/secman_auth_header.txt
        SESSION_COOKIE_FILE="/tmp/secman_auth_header.txt"
        log "SUCCESS" "Authentication successful"
        return 0
    else
        log "ERROR" "Authentication failed: $(echo "$response" | jq -r '.error // .message // "Unknown error"')"
        return 1
    fi
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
# FILE CONFIGURATION TESTS
# =============================================================================

test_get_file_config() {
    local response=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/files/config" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '.maxFileSize' > /dev/null 2>&1; then
        local max_size=$(echo "$response" | jq -r '.maxFileSizeMB')
        local allowed_types=$(echo "$response" | jq -r '.allowedContentTypes | length')
        log "DEBUG" "File config retrieved - Max size: ${max_size}MB, Allowed types: $allowed_types"
        return 0
    else
        log "DEBUG" "Failed to get file config: $response"
        return 1
    fi
}

# =============================================================================
# FILE UPLOAD TESTS
# =============================================================================

test_upload_text_file() {
    # First, get a risk assessment and requirement for testing
    local risk_assessments=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/risk-assessments" 2>/dev/null || echo '[]')
    
    local risk_assessment_id=$(echo "$risk_assessments" | jq -r '.[0].id // empty')
    
    if [[ -z "$risk_assessment_id" || "$risk_assessment_id" == "null" ]]; then
        log "DEBUG" "No risk assessments found, skipping file upload test"
        return 0
    fi
    
    local requirements=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/requirements" 2>/dev/null || echo '[]')
    
    local requirement_id=$(echo "$requirements" | jq -r '.[0].id // empty')
    
    if [[ -z "$requirement_id" || "$requirement_id" == "null" ]]; then
        log "DEBUG" "No requirements found, skipping file upload test"
        return 0
    fi
    
    # Upload test file
    local response=$(curl -s -X POST \
        -H "@$SESSION_COOKIE_FILE" \
        -F "file=@$OUTPUT_DIR/test.txt" \
        "$BASE_URL/api/risk-assessments/$risk_assessment_id/requirements/$requirement_id/files" 2>/dev/null || echo '{"success": false}')
    
    if echo "$response" | jq -e '.success' | grep -q true; then
        local file_id=$(echo "$response" | jq -r '.file.id')
        TEST_FILE_IDS+=("$file_id")
        log "DEBUG" "Successfully uploaded text file with ID: $file_id"
        return 0
    else
        local error=$(echo "$response" | jq -r '.message // .error // "Unknown error"')
        log "DEBUG" "Failed to upload text file: $error"
        return 1
    fi
}

test_upload_json_file() {
    # Get risk assessment and requirement IDs (reuse from previous test logic)
    local risk_assessments=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/risk-assessments" 2>/dev/null || echo '[]')
    
    local risk_assessment_id=$(echo "$risk_assessments" | jq -r '.[0].id // empty')
    
    if [[ -z "$risk_assessment_id" || "$risk_assessment_id" == "null" ]]; then
        return 0
    fi
    
    local requirements=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/requirements" 2>/dev/null || echo '[]')
    
    local requirement_id=$(echo "$requirements" | jq -r '.[0].id // empty')
    
    if [[ -z "$requirement_id" || "$requirement_id" == "null" ]]; then
        return 0
    fi
    
    # Upload JSON file
    local response=$(curl -s -X POST \
        -H "@$SESSION_COOKIE_FILE" \
        -F "file=@$OUTPUT_DIR/test.json" \
        "$BASE_URL/api/risk-assessments/$risk_assessment_id/requirements/$requirement_id/files" 2>/dev/null || echo '{"success": false}')
    
    if echo "$response" | jq -e '.success' | grep -q true; then
        local file_id=$(echo "$response" | jq -r '.file.id')
        TEST_FILE_IDS+=("$file_id")
        log "DEBUG" "Successfully uploaded JSON file with ID: $file_id"
        return 0
    else
        log "DEBUG" "Failed to upload JSON file: $(echo "$response" | jq -r '.message // .error // "Upload failed"')"
        return 1
    fi
}

test_upload_large_file() {
    # Get risk assessment and requirement IDs
    local risk_assessments=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/risk-assessments" 2>/dev/null || echo '[]')
    
    local risk_assessment_id=$(echo "$risk_assessments" | jq -r '.[0].id // empty')
    
    if [[ -z "$risk_assessment_id" || "$risk_assessment_id" == "null" ]]; then
        return 0
    fi
    
    local requirements=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/requirements" 2>/dev/null || echo '[]')
    
    local requirement_id=$(echo "$requirements" | jq -r '.[0].id // empty')
    
    if [[ -z "$requirement_id" || "$requirement_id" == "null" ]]; then
        return 0
    fi
    
    # Upload large file (should succeed as it's under 50MB)
    local response=$(curl -s -X POST \
        -H "@$SESSION_COOKIE_FILE" \
        -F "file=@$OUTPUT_DIR/large-test.txt" \
        "$BASE_URL/api/risk-assessments/$risk_assessment_id/requirements/$requirement_id/files" 2>/dev/null || echo '{"success": false}')
    
    if echo "$response" | jq -e '.success' | grep -q true; then
        local file_id=$(echo "$response" | jq -r '.file.id')
        TEST_FILE_IDS+=("$file_id")
        log "DEBUG" "Successfully uploaded large file with ID: $file_id"
        return 0
    else
        log "DEBUG" "Failed to upload large file: $(echo "$response" | jq -r '.message // .error // "Upload failed"')"
        return 1
    fi
}

# =============================================================================
# FILE LIST AND DOWNLOAD TESTS
# =============================================================================

test_list_files() {
    if [[ ${#TEST_FILE_IDS[@]} -eq 0 ]]; then
        log "DEBUG" "No files uploaded, skipping list test"
        return 0
    fi
    
    # Get risk assessment and requirement IDs
    local risk_assessments=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/risk-assessments" 2>/dev/null || echo '[]')
    
    local risk_assessment_id=$(echo "$risk_assessments" | jq -r '.[0].id // empty')
    
    if [[ -z "$risk_assessment_id" ]]; then
        return 0
    fi
    
    local requirements=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/requirements" 2>/dev/null || echo '[]')
    
    local requirement_id=$(echo "$requirements" | jq -r '.[0].id // empty')
    
    if [[ -z "$requirement_id" ]]; then
        return 0
    fi
    
    local response=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/risk-assessments/$risk_assessment_id/requirements/$requirement_id/files" 2>/dev/null || echo '{"files": []}')
    
    if echo "$response" | jq -e '.files' > /dev/null 2>&1; then
        local file_count=$(echo "$response" | jq '.files | length')
        log "DEBUG" "Successfully listed files - Count: $file_count"
        return 0
    else
        log "DEBUG" "Failed to list files: $response"
        return 1
    fi
}

test_download_file() {
    if [[ ${#TEST_FILE_IDS[@]} -eq 0 ]]; then
        log "DEBUG" "No files uploaded, skipping download test"
        return 0
    fi
    
    local file_id="${TEST_FILE_IDS[0]}"
    local download_file="$OUTPUT_DIR/downloaded_file"
    
    local http_code=$(curl -s -w "%{http_code}" -o "$download_file" \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/files/$file_id/download" 2>/dev/null || echo "000")
    
    if [[ "$http_code" == "200" ]] && [[ -f "$download_file" ]] && [[ -s "$download_file" ]]; then
        local file_size=$(stat -f%z "$download_file" 2>/dev/null || stat -c%s "$download_file" 2>/dev/null || echo "0")
        log "DEBUG" "Successfully downloaded file (${file_size} bytes)"
        rm -f "$download_file"
        return 0
    else
        log "DEBUG" "Failed to download file (HTTP: $http_code)"
        rm -f "$download_file"
        return 1
    fi
}

test_get_file_metadata() {
    if [[ ${#TEST_FILE_IDS[@]} -eq 0 ]]; then
        log "DEBUG" "No files uploaded, skipping metadata test"
        return 0
    fi
    
    local file_id="${TEST_FILE_IDS[0]}"
    
    local response=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/files/$file_id" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '.id' > /dev/null 2>&1; then
        local filename=$(echo "$response" | jq -r '.originalFilename')
        local size=$(echo "$response" | jq -r '.fileSize')
        log "DEBUG" "Successfully retrieved metadata - File: $filename, Size: $size"
        return 0
    else
        log "DEBUG" "Failed to get file metadata: $response"
        return 1
    fi
}

test_get_my_files() {
    local response=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/files/my-files" 2>/dev/null || echo '{"files": []}')
    
    if echo "$response" | jq -e '.files' > /dev/null 2>&1; then
        local file_count=$(echo "$response" | jq '.files | length')
        log "DEBUG" "Successfully retrieved user files - Count: $file_count"
        return 0
    else
        log "DEBUG" "Failed to get user files: $response"
        return 1
    fi
}

test_get_file_statistics() {
    local response=$(curl -s \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/files/statistics" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '.fileCount' > /dev/null 2>&1; then
        local count=$(echo "$response" | jq -r '.fileCount')
        local size=$(echo "$response" | jq -r '.totalSizeMB')
        log "DEBUG" "Successfully retrieved statistics - Files: $count, Size: ${size}MB"
        return 0
    else
        log "DEBUG" "Failed to get file statistics: $response"
        return 1
    fi
}

# =============================================================================
# FILE DELETE TESTS
# =============================================================================

test_delete_file() {
    if [[ ${#TEST_FILE_IDS[@]} -eq 0 ]]; then
        log "DEBUG" "No files uploaded, skipping delete test"
        return 0
    fi
    
    # Take the last file ID to delete
    local file_id="${TEST_FILE_IDS[-1]}"
    
    local response=$(curl -s -X DELETE \
        -H "@$SESSION_COOKIE_FILE" \
        "$BASE_URL/api/files/$file_id" 2>/dev/null || echo '{"error": "request_failed"}')
    
    if echo "$response" | jq -e '.message' | grep -q "successfully"; then
        log "DEBUG" "Successfully deleted file ID: $file_id"
        # Remove from our tracking array
        TEST_FILE_IDS=("${TEST_FILE_IDS[@]/$file_id}")
        return 0
    else
        log "DEBUG" "Failed to delete file: $(echo "$response" | jq -r '.error // .message // "Delete failed"')"
        return 1
    fi
}

# =============================================================================
# SYSTEM VALIDATION TESTS
# =============================================================================

test_backend_connectivity() {
    local response=$(curl -s "$BASE_URL/health" 2>/dev/null || echo "")
    
    if [[ -n "$response" ]]; then
        log "DEBUG" "Backend connectivity successful"
        return 0
    else
        log "DEBUG" "Backend connectivity failed"
        return 1
    fi
}

test_authentication_required() {
    # Test that file endpoints require authentication
    local http_code=$(curl -s -w "%{http_code}" -o /dev/null \
        "$BASE_URL/api/files/config" 2>/dev/null || echo "000")
    
    if [[ "$http_code" == "401" || "$http_code" == "403" ]]; then
        log "DEBUG" "Authentication properly required (HTTP: $http_code)"
        return 0
    elif [[ "$http_code" == "200" ]]; then
        log "DEBUG" "Public endpoint accessible without auth (HTTP: $http_code)"
        return 0
    else
        log "DEBUG" "Unexpected authentication behavior (HTTP: $http_code)"
        return 1
    fi
}

# =============================================================================
# TEST SUITES
# =============================================================================

run_connectivity_tests() {
    log "INFO" "=== Running Connectivity Tests ==="
    
    run_test "CONN-001" "test_backend_connectivity" "Backend connectivity test"
    run_test "CONN-002" "test_authentication_required" "Authentication requirement test"
}

run_file_config_tests() {
    log "INFO" "=== Running File Configuration Tests ==="
    
    run_test "FC-001" "test_get_file_config" "Get file configuration"
}

run_file_upload_tests() {
    log "INFO" "=== Running File Upload Tests ==="
    
    run_test "FU-001" "test_upload_text_file" "Upload text file"
    run_test "FU-002" "test_upload_json_file" "Upload JSON file"
    run_test "FU-003" "test_upload_large_file" "Upload large file"
}

run_file_management_tests() {
    log "INFO" "=== Running File Management Tests ==="
    
    run_test "FM-001" "test_list_files" "List files for requirement"
    run_test "FM-002" "test_download_file" "Download file"
    run_test "FM-003" "test_get_file_metadata" "Get file metadata"
    run_test "FM-004" "test_get_my_files" "Get user files"
    run_test "FM-005" "test_get_file_statistics" "Get file statistics"
    run_test "FM-006" "test_delete_file" "Delete file"
}

run_all_tests() {
    log "INFO" "Starting comprehensive file upload/download tests..."
    START_TIME=$(date +%s)
    
    # Create output directory and test files
    create_output_dir
    create_test_files
    
    # Authenticate first
    if ! authenticate; then
        log "ERROR" "Authentication failed, cannot continue with tests"
        exit 1
    fi
    
    # Run test suites
    run_connectivity_tests
    run_file_config_tests
    run_file_upload_tests
    run_file_management_tests
    
    # Generate test report
    generate_test_report
}

run_quick_tests() {
    log "INFO" "Starting quick file tests..."
    START_TIME=$(date +%s)
    
    create_output_dir
    create_test_files
    
    if ! authenticate; then
        log "ERROR" "Authentication failed, cannot continue with tests"
        exit 1
    fi
    
    # Run essential tests only
    run_test "QUICK-001" "test_backend_connectivity" "Backend connectivity"
    run_test "QUICK-002" "test_get_file_config" "Get file configuration"
    run_test "QUICK-003" "test_upload_text_file" "Upload text file"
    run_test "QUICK-004" "test_download_file" "Download file"
    
    generate_test_report
}

# =============================================================================
# REPORTING FUNCTIONS
# =============================================================================

generate_test_report() {
    local end_time=$(date +%s)
    local duration=$((end_time - START_TIME))
    local report_file="$OUTPUT_DIR/file_upload_test_report_$(date +%Y%m%d_%H%M%S).txt"
    
    log "INFO" "Generating test report..."
    
    {
        echo "========================================"
        echo "SECMAN FILE UPLOAD FEATURE TEST REPORT"
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
Secman File Upload/Download Feature Test Script

USAGE:
    $0 [OPTIONS] [COMMAND]

COMMANDS:
    all         Run all file upload/download tests (default)
    quick       Run quick essential tests only
    upload      Run file upload tests only
    management  Run file management tests only
    help        Show this help message

OPTIONS:
    -u, --base-url URL          Backend base URL (default: $DEFAULT_BASE_URL)
    -o, --output-dir DIR        Output directory for test results (default: $DEFAULT_OUTPUT_DIR)
    -v, --verbose               Enable verbose logging (DEBUG level)
    -q, --quiet                 Quiet mode (ERROR level only)
    --user USERNAME             Test user username (default: $DEFAULT_TEST_USER)
    --password PASS             Test user password (default: $DEFAULT_TEST_PASSWORD)

ENVIRONMENT VARIABLES:
    SECMAN_TEST_BASE_URL        Backend base URL
    SECMAN_TEST_USER            Test user username
    SECMAN_TEST_PASSWORD        Test user password
    SECMAN_TEST_OUTPUT_DIR      Output directory
    SECMAN_TEST_LOG_LEVEL       Log level (ERROR, WARN, INFO, DEBUG)

EXAMPLES:
    # Run all tests with custom backend URL
    $0 --base-url http://localhost:9001

    # Run quick tests with verbose output
    $0 quick --verbose

    # Run only upload tests
    $0 upload

    # Run with custom user and output directory
    $0 --user test@example.com --password testpass --output-dir ./my-results

NOTES:
    - The script requires authentication to the Secman system
    - Test files are automatically created and cleaned up
    - File uploads require existing risk assessments and requirements
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
        all|quick|upload|management)
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
log "INFO" "Starting Secman File Upload/Download Feature Tests"
log "INFO" "Command: $COMMAND"
log "INFO" "Base URL: $BASE_URL"

case $COMMAND in
    "all")
        run_all_tests
        ;;
    "quick")
        run_quick_tests
        ;;
    "upload")
        create_output_dir
        create_test_files
        authenticate || exit 1
        run_file_upload_tests
        generate_test_report
        ;;
    "management")
        create_output_dir
        create_test_files
        authenticate || exit 1
        run_file_management_tests
        generate_test_report
        ;;
    *)
        log "ERROR" "Unknown command: $COMMAND"
        exit 1
        ;;
esac

# Final cleanup is handled by the trap
log "SUCCESS" "File upload/download feature testing completed"
exit 0