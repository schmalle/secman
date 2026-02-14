#!/bin/bash
# E2E Test: S3 User Mapping Import
# Feature: 065-s3-user-mapping-import
#
# This script validates the end-to-end flow of importing user mappings
# from an AWS S3 bucket via the CLI import-s3 command.
#
# Test flow:
# 1. Resolve credentials (1Password URIs for AWS keys, secman admin email)
# 2. Build CLI fat JAR
# 3. Create test CSV with unique timestamped data
# 4. Upload test CSV to S3
# 5. Run import-s3 --dry-run and verify exit code 0
# 6. Run import-s3 (actual import) and verify exit code 0
# 7. Verify mappings via manage-user-mappings list
# 8. Cleanup: delete test mappings and S3 test file
#
# Prerequisites:
# - curl, jq, op (1Password CLI), aws CLI installed
# - Environment variables set (with 1Password URIs or direct values):
#   S3_TEST_BUCKET, S3_TEST_REGION, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY,
#   SECMAN_ADMIN_EMAIL
#
# Usage:
#   ./tests/s3-user-mapping-import-e2e-test.sh
#   DEBUG=1 ./tests/s3-user-mapping-import-e2e-test.sh  # Verbose output

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
BASE_URL="${SECMAN_BASE_URL:-http://localhost:8080}"
TIMESTAMP=$(date +%s)
TEST_EMAIL="e2e-s3-import-${TIMESTAMP}@test.secman.local"
S3_TEST_KEY="e2e-test/user-mappings-${TIMESTAMP}.csv"
TEST_DOMAIN="e2e-${TIMESTAMP}.test.local"
TEST_AWS_ACCOUNT="999900${TIMESTAMP: -6}"  # 12-digit account using last 6 digits of timestamp

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
    command -v jq   &>/dev/null || missing+=("jq")
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
    log_debug "Test email: $TEST_EMAIL"

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

# Create and upload test CSV to S3
upload_test_csv() {
    log_info "Step 2: Creating and uploading test CSV to S3..."

    LOCAL_CSV_FILE=$(mktemp /tmp/s3-e2e-test-XXXXXX.csv)
    cat > "$LOCAL_CSV_FILE" <<EOF
email,type,value
${TEST_EMAIL},DOMAIN,${TEST_DOMAIN}
${TEST_EMAIL},AWS_ACCOUNT,${TEST_AWS_ACCOUNT}
EOF

    log_debug "CSV content:"
    log_debug "$(cat "$LOCAL_CSV_FILE")"

    aws s3 cp "$LOCAL_CSV_FILE" "s3://${RESOLVED_BUCKET}/${S3_TEST_KEY}" \
        --region "$RESOLVED_REGION" --quiet

    log_success "Step 2: Test CSV uploaded to s3://${RESOLVED_BUCKET}/${S3_TEST_KEY}"
}

# Run dry-run import
test_dry_run() {
    log_info "Step 3: Running import-s3 --dry-run..."

    local output
    local exit_code=0
    output=$(run_cli manage-user-mappings import-s3 \
        --bucket "$RESOLVED_BUCKET" \
        --key "$S3_TEST_KEY" \
        --aws-region "$RESOLVED_REGION" \
        --format CSV \
        --dry-run 2>&1) || exit_code=$?

    log_debug "Dry-run output:"
    log_debug "$output"

    if [[ $exit_code -ne 0 ]]; then
        log_error "Step 3: Dry-run failed with exit code $exit_code"
        log_error "Output: $output"
        exit 1
    fi

    # Verify dry-run output mentions validation
    if echo "$output" | grep -qi "would create\|validation successful\|dry-run"; then
        log_success "Step 3: Dry-run completed successfully (exit code 0)"
    else
        log_error "Step 3: Dry-run output missing expected validation messages"
        log_error "Output: $output"
        exit 1
    fi
}

# Run actual import
test_actual_import() {
    log_info "Step 4: Running import-s3 (actual import)..."

    local output
    local exit_code=0
    output=$(run_cli manage-user-mappings import-s3 \
        --bucket "$RESOLVED_BUCKET" \
        --key "$S3_TEST_KEY" \
        --aws-region "$RESOLVED_REGION" \
        --format CSV 2>&1) || exit_code=$?

    log_debug "Import output:"
    log_debug "$output"

    if [[ $exit_code -ne 0 ]]; then
        log_error "Step 4: Import failed with exit code $exit_code"
        log_error "Output: $output"
        exit 1
    fi

    # Verify import output mentions created mappings
    if echo "$output" | grep -qi "created\|import successful"; then
        log_success "Step 4: Import completed successfully (exit code 0)"
    else
        log_error "Step 4: Import output missing expected success messages"
        log_error "Output: $output"
        exit 1
    fi
}

# Verify mappings were created
verify_mappings() {
    log_info "Step 5: Verifying mappings were created..."

    local output
    local exit_code=0
    output=$(run_cli manage-user-mappings list \
        --email "$TEST_EMAIL" \
        --format JSON 2>&1) || exit_code=$?

    log_debug "List output:"
    log_debug "$output"

    if [[ $exit_code -ne 0 ]]; then
        log_error "Step 5: List command failed with exit code $exit_code"
        log_error "Output: $output"
        exit 1
    fi

    # Check that test domain mapping exists
    if echo "$output" | grep -qi "$TEST_DOMAIN"; then
        log_success "Step 5a: Domain mapping for $TEST_DOMAIN found"
    else
        log_error "Step 5a: Domain mapping for $TEST_DOMAIN not found in output"
        log_error "Output: $output"
        exit 1
    fi

    # Check that AWS account mapping exists
    if echo "$output" | grep -qi "$TEST_AWS_ACCOUNT"; then
        log_success "Step 5b: AWS account mapping for $TEST_AWS_ACCOUNT found"
    else
        log_error "Step 5b: AWS account mapping for $TEST_AWS_ACCOUNT not found in output"
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

    # Delete test mappings
    if [[ -n "$TEST_EMAIL" ]]; then
        local exit_code=0
        run_cli manage-user-mappings remove \
            --email "$TEST_EMAIL" \
            --all 2>/dev/null || exit_code=$?
        if [[ $exit_code -eq 0 ]]; then
            log_info "Deleted test mappings for $TEST_EMAIL"
        else
            log_warn "Failed to delete test mappings (exit code $exit_code)"
        fi
    fi

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
    echo "=== E2E Test: S3 User Mapping Import (065-s3-user-mapping-import) ==="
    echo ""

    trap cleanup EXIT

    check_prerequisites
    resolve_credentials
    build_cli
    upload_test_csv
    test_dry_run
    test_actual_import
    verify_mappings

    echo ""
    echo "============================================"
    echo "  S3 USER MAPPING IMPORT E2E TEST PASSED"
    echo "============================================"
    echo ""
    echo "Test email:       $TEST_EMAIL"
    echo "S3 source:        s3://${RESOLVED_BUCKET}/${S3_TEST_KEY}"
    echo "Test domain:      $TEST_DOMAIN"
    echo "Test AWS account: $TEST_AWS_ACCOUNT"
    echo ""
    echo "Workflow completed:"
    echo "  1. Built CLI fat JAR"
    echo "  2. Uploaded test CSV to S3"
    echo "  3. Dry-run import passed validation"
    echo "  4. Actual import created mappings"
    echo "  5. Verified mappings via list command"
    echo "  6. Cleaned up test data"
    echo ""
}

main "$@"
