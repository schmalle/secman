#!/bin/bash
# E2E Test: S3 List Bucket
# Feature: 065-s3-user-mapping-import (list-bucket subcommand)
#
# This script validates the end-to-end flow of listing S3 bucket objects
# via the CLI list-bucket command.
#
# Test flow:
# 1. Resolve credentials (1Password URIs for AWS keys, secman admin email)
# 2. Build CLI fat JAR
# 3. Upload a known test file to S3
# 4. Run list-bucket and verify output contains the test file
# 5. Run list-bucket with --prefix and verify filtered output
# 6. Test error case: list non-existent bucket (expect exit code 2)
# 7. Cleanup: delete test S3 file
#
# Prerequisites:
# - aws CLI, op (1Password CLI), java installed
# - Environment variables set (with 1Password URIs or direct values):
#   S3_TEST_BUCKET, S3_TEST_REGION, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY,
#   SECMAN_ADMIN_EMAIL
#
# Usage:
#   ./tests/s3-list-bucket-e2e-test.sh
#   DEBUG=1 ./tests/s3-list-bucket-e2e-test.sh  # Verbose output

set -euo pipefail

# 1Password URI defaults (override with direct values or your own URIs)
export S3_TEST_BUCKET="${S3_TEST_BUCKET:-op://test/secman-s3/S3_TEST_BUCKET}"
export S3_TEST_REGION="${S3_TEST_REGION:-op://test/secman-s3/S3_TEST_REGION}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-op://test/secman-s3/AWS_ACCESS_KEY_ID}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-op://test/secman-s3/AWS_SECRET_ACCESS_KEY}"
export SECMAN_ADMIN_EMAIL="${SECMAN_ADMIN_EMAIL:-op://test/secman/SECMAN_ADMIN_EMAIL}"

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$REPO_ROOT/src/cli/build/libs/cli-0.1.0-all.jar"
TIMESTAMP=$(date +%s)
S3_TEST_PREFIX="e2e-list-test"
S3_TEST_KEY="${S3_TEST_PREFIX}/test-file-${TIMESTAMP}.csv"

# State for cleanup
CLEANUP_DONE=false
RESOLVED_BUCKET=""
RESOLVED_REGION=""
LOCAL_CSV_FILE=""

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
    command -v aws  &>/dev/null || missing+=("aws (AWS CLI)")
    command -v op   &>/dev/null || missing+=("op (1Password CLI)")
    command -v java &>/dev/null || missing+=("java")

    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "Missing required tools: ${missing[*]}"
        exit 1
    fi

    for var in S3_TEST_BUCKET S3_TEST_REGION AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY SECMAN_ADMIN_EMAIL; do
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

    RESOLVED_BUCKET=$(resolve_op_var "$S3_TEST_BUCKET")
    RESOLVED_REGION=$(resolve_op_var "$S3_TEST_REGION")
    export AWS_ACCESS_KEY_ID=$(resolve_op_var "$AWS_ACCESS_KEY_ID")
    export AWS_SECRET_ACCESS_KEY=$(resolve_op_var "$AWS_SECRET_ACCESS_KEY")
    RESOLVED_ADMIN_EMAIL=$(resolve_op_var "$SECMAN_ADMIN_EMAIL")

    log_debug "Bucket: $RESOLVED_BUCKET"
    log_debug "Region: $RESOLVED_REGION"
    log_debug "Admin email: $RESOLVED_ADMIN_EMAIL"

    log_success "Credentials resolved"
}

# Run CLI JAR command
run_cli() {
    local exit_code=0
    SECMAN_ADMIN_EMAIL="$RESOLVED_ADMIN_EMAIL" \
    java -Xmx512m -jar "$JAR_PATH" "$@" || exit_code=$?
    return $exit_code
}

# Build CLI JAR
build_cli() {
    log_info "Step 1: Building CLI fat JAR..."

    if [[ -f "$JAR_PATH" ]]; then
        log_info "JAR already exists at $JAR_PATH, rebuilding..."
    fi

    (cd "$REPO_ROOT" && ./gradlew :cli:shadowJar -q)

    if [[ ! -f "$JAR_PATH" ]]; then
        log_error "JAR not found after build: $JAR_PATH"
        exit 1
    fi

    log_success "Step 1: CLI JAR built successfully"
}

# Upload a test file to S3
upload_test_file() {
    log_info "Step 2: Uploading test file to S3..."

    LOCAL_CSV_FILE=$(mktemp /tmp/s3-list-e2e-test-XXXXXX.csv)
    cat > "$LOCAL_CSV_FILE" <<EOF
email,type,value
test@example.com,DOMAIN,example.com
EOF

    aws s3 cp "$LOCAL_CSV_FILE" "s3://${RESOLVED_BUCKET}/${S3_TEST_KEY}" \
        --region "$RESOLVED_REGION" --quiet

    log_success "Step 2: Test file uploaded to s3://${RESOLVED_BUCKET}/${S3_TEST_KEY}"
}

# Test: list bucket and verify test file appears
test_list_bucket() {
    log_info "Step 3: Running list-bucket..."

    local output
    local exit_code=0
    output=$(run_cli manage-user-mappings list-bucket \
        --bucket "$RESOLVED_BUCKET" \
        --aws-region "$RESOLVED_REGION" 2>&1) || exit_code=$?

    log_debug "list-bucket output:"
    log_debug "$output"

    if [[ $exit_code -ne 0 ]]; then
        log_error "Step 3: list-bucket failed with exit code $exit_code"
        log_error "Output: $output"
        exit 1
    fi

    if echo "$output" | grep -q "$S3_TEST_KEY"; then
        log_success "Step 3: list-bucket output contains test file"
    else
        log_error "Step 3: Test file '$S3_TEST_KEY' not found in list-bucket output"
        log_error "Output: $output"
        exit 1
    fi
}

# Test: list with prefix filter
test_list_bucket_prefix() {
    log_info "Step 4: Running list-bucket with --prefix..."

    local output
    local exit_code=0
    output=$(run_cli manage-user-mappings list-bucket \
        --bucket "$RESOLVED_BUCKET" \
        --prefix "$S3_TEST_PREFIX/" \
        --aws-region "$RESOLVED_REGION" 2>&1) || exit_code=$?

    log_debug "list-bucket (prefix) output:"
    log_debug "$output"

    if [[ $exit_code -ne 0 ]]; then
        log_error "Step 4: list-bucket with prefix failed with exit code $exit_code"
        log_error "Output: $output"
        exit 1
    fi

    if echo "$output" | grep -q "$S3_TEST_KEY"; then
        log_success "Step 4: Prefix-filtered output contains test file"
    else
        log_error "Step 4: Test file not found in prefix-filtered output"
        log_error "Output: $output"
        exit 1
    fi
}

# Test: error case - non-existent bucket
test_nonexistent_bucket() {
    log_info "Step 5: Testing non-existent bucket (expect exit code 2)..."

    local output
    local exit_code=0
    output=$(run_cli manage-user-mappings list-bucket \
        --bucket "nonexistent-bucket-e2e-${TIMESTAMP}" \
        --aws-region "$RESOLVED_REGION" 2>&1) || exit_code=$?

    log_debug "non-existent bucket output:"
    log_debug "$output"

    if [[ $exit_code -eq 2 ]]; then
        log_success "Step 5: Non-existent bucket returned exit code 2"
    else
        log_error "Step 5: Expected exit code 2, got $exit_code"
        log_error "Output: $output"
        exit 1
    fi
}

# Cleanup function
cleanup() {
    if [[ "$CLEANUP_DONE" == "true" ]]; then
        return
    fi
    CLEANUP_DONE=true

    log_info "Step 6: Cleaning up test data..."

    # Delete S3 test file
    if [[ -n "$RESOLVED_BUCKET" && -n "$S3_TEST_KEY" ]]; then
        aws s3 rm "s3://${RESOLVED_BUCKET}/${S3_TEST_KEY}" \
            --region "$RESOLVED_REGION" --quiet 2>/dev/null || \
            log_warn "Failed to delete S3 test file"
        log_info "Deleted S3 test file: s3://${RESOLVED_BUCKET}/${S3_TEST_KEY}"
    fi

    # Delete local temp file
    if [[ -n "$LOCAL_CSV_FILE" && -f "$LOCAL_CSV_FILE" ]]; then
        rm -f "$LOCAL_CSV_FILE"
        log_debug "Deleted local temp file: $LOCAL_CSV_FILE"
    fi

    log_success "Cleanup completed"
}

# Main
main() {
    echo ""
    echo "=== E2E Test: S3 List Bucket (065-s3-user-mapping-import) ==="
    echo ""

    trap cleanup EXIT

    check_prerequisites
    resolve_credentials
    build_cli
    upload_test_file
    test_list_bucket
    test_list_bucket_prefix
    test_nonexistent_bucket

    echo ""
    echo "============================================"
    echo "  S3 LIST BUCKET E2E TEST PASSED"
    echo "============================================"
    echo ""
    echo "S3 bucket:   ${RESOLVED_BUCKET}"
    echo "Test file:   s3://${RESOLVED_BUCKET}/${S3_TEST_KEY}"
    echo "Test prefix: ${S3_TEST_PREFIX}/"
    echo ""
    echo "Workflow completed:"
    echo "  1. Built CLI fat JAR"
    echo "  2. Uploaded test file to S3"
    echo "  3. Listed bucket and verified test file present"
    echo "  4. Listed with prefix filter and verified test file present"
    echo "  5. Verified non-existent bucket returns exit code 2"
    echo "  6. Cleaned up test data"
    echo ""
}

main "$@"
