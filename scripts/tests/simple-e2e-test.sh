#!/bin/bash

# Simple Secman E2E Test Runner
# Usage: ./simple-e2e-test.sh [--username=USER] [--password=PASS]

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default configuration
DEFAULT_USERNAME="adminuser"
DEFAULT_PASSWORD="password"
TEST_USERNAME="$DEFAULT_USERNAME"
TEST_PASSWORD="$DEFAULT_PASSWORD"

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to show usage
show_usage() {
    echo "Simple Secman E2E Test Runner"
    echo
    echo "Usage: $0 [OPTIONS]"
    echo
    echo "Options:"
    echo "  --username=USER       Test username (default: $DEFAULT_USERNAME)"
    echo "  --password=PASS       Test password (default: $DEFAULT_PASSWORD)"
    echo "  --help               Show this help message"
    echo
    echo "Examples:"
    echo "  $0                                           # Run with default credentials"
    echo "  $0 --username=testuser --password=testpass   # Run with custom credentials"
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --username=*)
            TEST_USERNAME="${1#*=}"
            shift
            ;;
        --password=*)
            TEST_PASSWORD="${1#*=}"
            shift
            ;;
        --help)
            show_usage
            exit 0
            ;;
        *)
            print_error "Unknown option: $1"
            show_usage
            exit 1
            ;;
    esac
done

# Main execution
print_status "Starting Secman E2E Tests..."
print_status "Username: $TEST_USERNAME"
print_status "Password: [REDACTED]"
echo

# Change to frontend directory
cd ./../../src/frontend

# Set environment variables for tests
export PLAYWRIGHT_TEST_USERNAME="$TEST_USERNAME"
export PLAYWRIGHT_TEST_PASSWORD="$TEST_PASSWORD"

print_status "Installing dependencies if needed..."
if [ ! -d "node_modules" ]; then
    npm install
fi

print_status "Installing Playwright browsers..."
npx playwright install chromium --with-deps || true

print_status "Running Playwright tests..."

# Run existing tests
if npx playwright test --reporter=list 2>&1 | tee test-results.log; then
    print_success "Tests completed successfully!"
    exit_code=0
else
    print_error "Some tests failed"
    exit_code=1
fi

print_status "Generating HTML report..."
npx playwright show-report --port=9323 &
report_pid=$!

print_status "Test results:"
echo "- Log file: src/frontend/test-results.log"
echo "- HTML report: http://localhost:9323"
echo "- Report PID: $report_pid (kill $report_pid to stop report server)"

exit $exit_code
