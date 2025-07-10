#!/bin/bash

# Comprehensive Secman End-to-End Test Runner
# Supports configurable credentials and comprehensive test coverage
# Usage: ./comprehensive-e2e-test.sh [--username=USER] [--password=PASS] [OPTIONS]

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
BACKEND_PORT=9000
FRONTEND_PORT=4321
TEST_TIMEOUT=600 # 10 minutes
SETUP_TIMEOUT=120 # 2 minutes
BACKEND_URL="http://localhost:$BACKEND_PORT"
FRONTEND_URL="http://localhost:$FRONTEND_PORT"

# Test configuration
TEST_USERNAME="$DEFAULT_USERNAME"
TEST_PASSWORD="$DEFAULT_PASSWORD"
RUN_SMOKE_TESTS=true
RUN_INTEGRATION_TESTS=true
RUN_PERFORMANCE_TESTS=true
SKIP_SETUP=false
SKIP_CLEANUP=false
VERBOSE=false
HEADLESS=true
PARALLEL_TESTS=true

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_verbose() {
    if [[ "$VERBOSE" == true ]]; then
        echo -e "${BLUE}[VERBOSE]${NC} $1"
    fi
}

# Function to show usage
show_usage() {
    echo "Comprehensive Secman End-to-End Test Runner"
    echo
    echo "Usage: $0 [OPTIONS]"
    echo
    echo "Credential Options:"
    echo "  --username=USER       Test username (default: $DEFAULT_USERNAME)"
    echo "  --password=PASS       Test password (default: $DEFAULT_PASSWORD)"
    echo
    echo "Test Selection Options:"
    echo "  --smoke-only          Run only smoke tests (quick validation)"
    echo "  --integration-only    Run only integration tests"
    echo "  --performance-only    Run only performance tests"
    echo "  --no-smoke           Skip smoke tests"
    echo "  --no-integration     Skip integration tests"
    echo "  --no-performance     Skip performance tests"
    echo
    echo "Environment Options:"
    echo "  --backend-url=URL     Backend URL (default: $BACKEND_URL)"
    echo "  --frontend-url=URL    Frontend URL (default: $FRONTEND_URL)"
    echo "  --skip-setup         Skip environment setup"
    echo "  --skip-cleanup       Skip cleanup after tests"
    echo
    echo "Execution Options:"
    echo "  --headed             Run tests in headed mode (visible browser)"
    echo "  --sequential         Run tests sequentially (not parallel)"
    echo "  --verbose            Enable verbose output"
    echo "  --help               Show this help message"
    echo
    echo "Examples:"
    echo "  $0                                           # Run all tests with default credentials"
    echo "  $0 --username=testuser --password=testpass   # Run with custom credentials"
    echo "  $0 --smoke-only --headed                     # Run smoke tests with visible browser"
    echo "  $0 --integration-only --verbose              # Run integration tests with verbose output"
    echo
    echo "Environment Variables:"
    echo "  SECMAN_TEST_USERNAME    Override default username"
    echo "  SECMAN_TEST_PASSWORD    Override default password"
    echo "  SECMAN_BACKEND_URL      Override backend URL"
    echo "  SECMAN_FRONTEND_URL     Override frontend URL"
}

# Function to parse command line arguments
parse_arguments() {
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
            --backend-url=*)
                BACKEND_URL="${1#*=}"
                shift
                ;;
            --frontend-url=*)
                FRONTEND_URL="${1#*=}"
                shift
                ;;
            --smoke-only)
                RUN_SMOKE_TESTS=true
                RUN_INTEGRATION_TESTS=false
                RUN_PERFORMANCE_TESTS=false
                shift
                ;;
            --integration-only)
                RUN_SMOKE_TESTS=false
                RUN_INTEGRATION_TESTS=true
                RUN_PERFORMANCE_TESTS=false
                shift
                ;;
            --performance-only)
                RUN_SMOKE_TESTS=false
                RUN_INTEGRATION_TESTS=false
                RUN_PERFORMANCE_TESTS=true
                shift
                ;;
            --no-smoke)
                RUN_SMOKE_TESTS=false
                shift
                ;;
            --no-integration)
                RUN_INTEGRATION_TESTS=false
                shift
                ;;
            --no-performance)
                RUN_PERFORMANCE_TESTS=false
                shift
                ;;
            --skip-setup)
                SKIP_SETUP=true
                shift
                ;;
            --skip-cleanup)
                SKIP_CLEANUP=true
                shift
                ;;
            --headed)
                HEADLESS=false
                shift
                ;;
            --sequential)
                PARALLEL_TESTS=false
                shift
                ;;
            --verbose)
                VERBOSE=true
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
}

# Function to check if port is in use
check_port() {
    local port=$1
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Function to wait for service to be ready
wait_for_service() {
    local url=$1
    local service_name=$2
    local timeout=${3:-60}
    
    print_status "Waiting for $service_name to be ready..."
    
    local count=0
    while [ $count -lt $timeout ]; do
        if curl -s "$url" >/dev/null 2>&1; then
            print_success "$service_name is ready!"
            return 0
        fi
        
        sleep 1
        count=$((count + 1))
        
        if [ $((count % 15)) -eq 0 ]; then
            print_status "$service_name not ready yet... ($count/$timeout seconds)"
        fi
    done
    
    print_error "$service_name failed to start within $timeout seconds"
    return 1
}

# Function to start backend
start_backend() {
    print_status "Starting backend server..."
    
    local backend_port="${BACKEND_URL##*:}"
    
    if check_port "$backend_port"; then
        print_warning "Backend already running on port $backend_port"
    else
        cd src/backend
        print_verbose "Starting SBT with: sbt run"
        sbt run > backend.log 2>&1 &
        echo $! > backend.pid
        cd ../..
        
        if ! wait_for_service "$BACKEND_URL/api/auth/status" "Backend" $SETUP_TIMEOUT; then
            print_error "Failed to start backend"
            cleanup_and_exit 1
        fi
    fi
}

# Function to start frontend
start_frontend() {
    print_status "Starting frontend server..."
    
    local frontend_port="${FRONTEND_URL##*:}"
    
    if check_port "$frontend_port"; then
        print_warning "Frontend already running on port $frontend_port"
    else
        cd src/frontend
        
        # Install dependencies if needed
        if [ ! -d "node_modules" ]; then
            print_status "Installing frontend dependencies..."
            npm install
        fi
        
        # Install playwright browsers if needed
        print_status "Ensuring Playwright browsers are installed..."
        npx playwright install chromium --with-deps || true
        
        print_verbose "Starting frontend with: npm run dev"
        npm run dev > frontend.log 2>&1 &
        echo $! > frontend.pid
        cd ../..
        
        if ! wait_for_service "$FRONTEND_URL" "Frontend" $SETUP_TIMEOUT; then
            print_error "Failed to start frontend"
            cleanup_and_exit 1
        fi
    fi
}

# Function to setup test environment
setup_test_environment() {
    print_status "Setting up test environment..."
    
    # Set environment variables for tests
    export PLAYWRIGHT_TEST_USERNAME="$TEST_USERNAME"
    export PLAYWRIGHT_TEST_PASSWORD="$TEST_PASSWORD"
    export PLAYWRIGHT_BACKEND_URL="$BACKEND_URL"
    export PLAYWRIGHT_FRONTEND_URL="$FRONTEND_URL"
    export PLAYWRIGHT_HEADLESS="$HEADLESS"
    export PLAYWRIGHT_PARALLEL="$PARALLEL_TESTS"
    
    print_verbose "Test environment configured:"
    print_verbose "  Username: $TEST_USERNAME"
    print_verbose "  Password: [REDACTED]"
    print_verbose "  Backend URL: $BACKEND_URL"
    print_verbose "  Frontend URL: $FRONTEND_URL"
    print_verbose "  Headless: $HEADLESS"
    print_verbose "  Parallel: $PARALLEL_TESTS"
    
    # Validate credentials by attempting login
    print_status "Validating test credentials..."
    if ! validate_credentials; then
        print_error "Test credentials validation failed"
        cleanup_and_exit 1
    fi
    
    print_success "Test environment setup complete"
}

# Function to validate credentials
validate_credentials() {
    local response=$(curl -s -X POST "$BACKEND_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}" \
        -w "%{http_code}" \
        -o /dev/null)
    
    if [[ "$response" == "200" ]]; then
        print_success "Credentials validated successfully"
        return 0
    else
        print_error "Credential validation failed (HTTP: $response)"
        return 1
    fi
}

# Function to run smoke tests
run_smoke_tests() {
    print_status "Running smoke tests (basic functionality)..."
    
    cd src/frontend
    
    local smoke_args=""
    [[ "$HEADLESS" == false ]] && smoke_args="$smoke_args --headed"
    [[ "$PARALLEL_TESTS" == false ]] && smoke_args="$smoke_args --workers=1"
    [[ "$VERBOSE" == true ]] && smoke_args="$smoke_args --reporter=line"
    
    print_verbose "Running existing tests as smoke tests"
    
    if npx playwright test $smoke_args 2>&1 | tee smoke-test-results.log; then
        print_success "Smoke tests passed!"
        cd ../..
        return 0
    else
        print_error "Smoke tests failed"
        cd ../..
        return 1
    fi
}

# Function to run integration tests
run_integration_tests() {
    print_status "Running integration tests (using checkin config)..."
    
    cd src/frontend
    
    local integration_args=""
    [[ "$HEADLESS" == false ]] && integration_args="$integration_args --headed"
    [[ "$PARALLEL_TESTS" == false ]] && integration_args="$integration_args --workers=1"
    [[ "$VERBOSE" == true ]] && integration_args="$integration_args --reporter=line"
    
    print_verbose "Running: npm run test:checkin $integration_args"
    
    if npm run test:checkin 2>&1 | tee integration-test-results.log; then
        print_success "Integration tests passed!"
        cd ../..
        return 0
    else
        print_error "Integration tests failed"
        cd ../..
        return 1
    fi
}

# Function to run performance tests
run_performance_tests() {
    print_status "Running performance tests (comprehensive tests)..."
    
    cd src/frontend
    
    local performance_args=""
    [[ "$HEADLESS" == false ]] && performance_args="$performance_args --headed"
    [[ "$PARALLEL_TESTS" == false ]] && performance_args="$performance_args --workers=1"
    [[ "$VERBOSE" == true ]] && performance_args="$performance_args --reporter=line"
    
    print_verbose "Running: npm run test"
    
    if npm run test 2>&1 | tee performance-test-results.log; then
        print_success "Performance tests passed!"
        cd ../..
        return 0
    else
        print_error "Performance tests failed"
        cd ../..
        return 1
    fi
}

# Function to generate comprehensive test report
generate_test_report() {
    print_status "Generating comprehensive test report..."
    
    local timestamp=$(date '+%Y-%m-%d_%H-%M-%S')
    local report_dir="test-results-$timestamp"
    
    mkdir -p "$report_dir"
    
    # Copy test results
    [ -f "src/frontend/smoke-test-results.log" ] && cp "src/frontend/smoke-test-results.log" "$report_dir/"
    [ -f "src/frontend/integration-test-results.log" ] && cp "src/frontend/integration-test-results.log" "$report_dir/"
    [ -f "src/frontend/performance-test-results.log" ] && cp "src/frontend/performance-test-results.log" "$report_dir/"
    [ -d "src/frontend/playwright-report" ] && cp -r "src/frontend/playwright-report" "$report_dir/"
    [ -d "src/frontend/test-results" ] && cp -r "src/frontend/test-results" "$report_dir/"
    
    # Copy server logs
    [ -f "src/backend/backend.log" ] && cp "src/backend/backend.log" "$report_dir/"
    [ -f "src/frontend/frontend.log" ] && cp "src/frontend/frontend.log" "$report_dir/"
    
    # Create comprehensive summary report
    cat > "$report_dir/comprehensive-test-summary.txt" << EOF
Secman Comprehensive End-to-End Test Results
============================================
Date: $(date)
Duration: $((SECONDS / 60)) minutes $((SECONDS % 60)) seconds

Test Configuration:
- Username: $TEST_USERNAME
- Backend URL: $BACKEND_URL  
- Frontend URL: $FRONTEND_URL
- Headless Mode: $HEADLESS
- Parallel Execution: $PARALLEL_TESTS

Test Results:
- Smoke Tests: $([ "$RUN_SMOKE_TESTS" == true ] && [ $smoke_result -eq 0 ] && echo "PASSED" || [ "$RUN_SMOKE_TESTS" == true ] && echo "FAILED" || echo "SKIPPED")
- Integration Tests: $([ "$RUN_INTEGRATION_TESTS" == true ] && [ $integration_result -eq 0 ] && echo "PASSED" || [ "$RUN_INTEGRATION_TESTS" == true ] && echo "FAILED" || echo "SKIPPED")
- Performance Tests: $([ "$RUN_PERFORMANCE_TESTS" == true ] && [ $performance_result -eq 0 ] && echo "PASSED" || [ "$RUN_PERFORMANCE_TESTS" == true ] && echo "FAILED" || echo "SKIPPED")

Overall Result: $([ $overall_result -eq 0 ] && echo "PASSED" || echo "FAILED")

Report Location: $report_dir/
HTML Report: $report_dir/playwright-report/index.html
EOF
    
    print_success "Comprehensive test report generated in: $report_dir/"
    
    # Open HTML report if in headed mode
    if [[ "$HEADLESS" == false && -f "$report_dir/playwright-report/index.html" ]]; then
        print_status "Opening HTML report in browser..."
        open "$report_dir/playwright-report/index.html" 2>/dev/null || true
    fi
}

# Function to cleanup
cleanup_and_exit() {
    local exit_code=${1:-0}
    
    if [[ "$SKIP_CLEANUP" == true ]]; then
        print_status "Skipping cleanup as requested"
        exit $exit_code
    fi
    
    print_status "Cleaning up..."
    
    # Stop servers if we started them
    if [ -f "src/backend/backend.pid" ]; then
        local backend_pid=$(cat src/backend/backend.pid)
        if kill -0 "$backend_pid" 2>/dev/null; then
            kill "$backend_pid" 2>/dev/null || true
            print_verbose "Stopped backend server (PID: $backend_pid)"
        fi
        rm -f src/backend/backend.pid
    fi
    
    if [ -f "src/frontend/frontend.pid" ]; then
        local frontend_pid=$(cat src/frontend/frontend.pid)
        if kill -0 "$frontend_pid" 2>/dev/null; then
            kill "$frontend_pid" 2>/dev/null || true
            print_verbose "Stopped frontend server (PID: $frontend_pid)"
        fi
        rm -f src/frontend/frontend.pid
    fi
    
    # Clean up test artifacts
    rm -f src/frontend/*-test-results.log
    
    print_status "Cleanup complete"
    exit $exit_code
}

# Main function
main() {
    # Parse command line arguments
    parse_arguments "$@"
    
    # Override with environment variables if set
    TEST_USERNAME="${SECMAN_TEST_USERNAME:-$TEST_USERNAME}"
    TEST_PASSWORD="${SECMAN_TEST_PASSWORD:-$TEST_PASSWORD}"
    BACKEND_URL="${SECMAN_BACKEND_URL:-$BACKEND_URL}"
    FRONTEND_URL="${SECMAN_FRONTEND_URL:-$FRONTEND_URL}"
    
    # Set trap for cleanup
    trap 'cleanup_and_exit 1' INT TERM
    
    print_status "Starting Secman Comprehensive End-to-End Tests..."
    echo
    print_status "Test Configuration:"
    print_status "  Username: $TEST_USERNAME"
    print_status "  Backend URL: $BACKEND_URL"
    print_status "  Frontend URL: $FRONTEND_URL"
    print_status "  Smoke Tests: $RUN_SMOKE_TESTS"
    print_status "  Integration Tests: $RUN_INTEGRATION_TESTS"
    print_status "  Performance Tests: $RUN_PERFORMANCE_TESTS"
    print_status "  Headless Mode: $HEADLESS"
    print_status "  Parallel Tests: $PARALLEL_TESTS"
    print_status "  Skip Setup: $SKIP_SETUP"
    print_status "  Skip Cleanup: $SKIP_CLEANUP"
    echo
    
    # Check requirements
    command -v node >/dev/null 2>&1 || { print_error "Node.js is required but not installed"; exit 1; }
    command -v npm >/dev/null 2>&1 || { print_error "npm is required but not installed"; exit 1; }
    command -v sbt >/dev/null 2>&1 || { print_error "sbt is required but not installed"; exit 1; }
    command -v curl >/dev/null 2>&1 || { print_error "curl is required but not installed"; exit 1; }
    
    # Start services and setup environment
    if [[ "$SKIP_SETUP" != true ]]; then
        start_backend
        start_frontend
        setup_test_environment
    fi
    
    # Run test suites
    local smoke_result=0
    local integration_result=0
    local performance_result=0
    
    if [[ "$RUN_SMOKE_TESTS" == true ]]; then
        run_smoke_tests || smoke_result=$?
    fi
    
    if [[ "$RUN_INTEGRATION_TESTS" == true ]]; then
        run_integration_tests || integration_result=$?
    fi
    
    if [[ "$RUN_PERFORMANCE_TESTS" == true ]]; then
        run_performance_tests || performance_result=$?
    fi
    
    # Calculate overall result
    local overall_result=0
    if [[ $smoke_result -ne 0 || $integration_result -ne 0 || $performance_result -ne 0 ]]; then
        overall_result=1
    fi
    
    # Generate comprehensive report
    generate_test_report
    
    # Show final results
    echo
    print_status "Test Results Summary:"
    echo "===================="
    
    if [[ "$RUN_SMOKE_TESTS" == true ]]; then
        if [[ $smoke_result -eq 0 ]]; then
            print_success "✅ Smoke Tests: PASSED"
        else
            print_error "❌ Smoke Tests: FAILED"
        fi
    fi
    
    if [[ "$RUN_INTEGRATION_TESTS" == true ]]; then
        if [[ $integration_result -eq 0 ]]; then
            print_success "✅ Integration Tests: PASSED"
        else
            print_error "❌ Integration Tests: FAILED"
        fi
    fi
    
    if [[ "$RUN_PERFORMANCE_TESTS" == true ]]; then
        if [[ $performance_result -eq 0 ]]; then
            print_success "✅ Performance Tests: PASSED"
        else
            print_error "❌ Performance Tests: FAILED"
        fi
    fi
    
    echo
    if [[ $overall_result -eq 0 ]]; then
        print_success "🎉 ALL TESTS PASSED!"
    else
        print_error "💥 SOME TESTS FAILED"
    fi
    
    # Cleanup and exit
    cleanup_and_exit $overall_result
}

# Run main function
main "$@"