#!/bin/bash

# Secman End-to-End Test Runner
# This script can be executed outside of any GitHub build process
# It sets up the environment, runs all tests, and generates reports

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BACKEND_PORT=9000
FRONTEND_PORT=4321
TEST_TIMEOUT=300 # 5 minutes
SETUP_TIMEOUT=120 # 2 minutes

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
        if curl -s $url >/dev/null 2>&1; then
            print_success "$service_name is ready!"
            return 0
        fi
        
        sleep 1
        count=$((count + 1))
        
        if [ $((count % 10)) -eq 0 ]; then
            print_status "$service_name not ready yet... ($count/$timeout seconds)"
        fi
    done
    
    print_error "$service_name failed to start within $timeout seconds"
    return 1
}

# Function to start backend
start_backend() {
    print_status "Starting backend server..."
    
    if check_port $BACKEND_PORT; then
        print_warning "Backend already running on port $BACKEND_PORT"
    else
        cd src/backend
        sbt run > backend.log 2>&1 &
        echo $! > backend.pid
        cd ../..
        
        if ! wait_for_service "http://localhost:$BACKEND_PORT/api/auth/status" "Backend" $SETUP_TIMEOUT; then
            print_error "Failed to start backend"
            cleanup_and_exit 1
        fi
    fi
}

# Function to start frontend
start_frontend() {
    print_status "Starting frontend server..."
    
    if check_port $FRONTEND_PORT; then
        print_warning "Frontend already running on port $FRONTEND_PORT"
    else
        cd src/frontend
        
        # Install dependencies if needed
        if [ ! -d "node_modules" ]; then
            print_status "Installing frontend dependencies..."
            npm install
        fi
        
        npm run dev > frontend.log 2>&1 &
        echo $! > frontend.pid
        cd ../..
        
        if ! wait_for_service "http://localhost:$FRONTEND_PORT" "Frontend" $SETUP_TIMEOUT; then
            print_error "Failed to start frontend"
            cleanup_and_exit 1
        fi
    fi
}

# Function to setup test environment
setup_test_environment() {
    print_status "Setting up test environment..."
    
    cd tests
    
    # Install test dependencies
    if [ ! -d "node_modules" ]; then
        print_status "Installing test dependencies..."
        npm install
    fi
    
    # Run setup script
    node scripts/setup-test-env.js
    
    cd ..
}

# Function to run API tests
run_api_tests() {
    print_status "Running API tests..."
    
    cd tests
    
    npm run test:api 2>&1 | tee api-test-results.log
    local api_exit_code=${PIPESTATUS[0]}
    
    cd ..
    
    if [ $api_exit_code -eq 0 ]; then
        print_success "API tests passed!"
    else
        print_error "API tests failed with exit code $api_exit_code"
        return $api_exit_code
    fi
}

# Function to run UI tests
run_ui_tests() {
    print_status "Running UI tests..."
    
    cd tests
    
    npx playwright test 2>&1 | tee ui-test-results.log
    local ui_exit_code=${PIPESTATUS[0]}
    
    cd ..
    
    if [ $ui_exit_code -eq 0 ]; then
        print_success "UI tests passed!"
    else
        print_error "UI tests failed with exit code $ui_exit_code"
        return $ui_exit_code
    fi
}

# Function to generate test report
generate_test_report() {
    print_status "Generating test report..."
    
    local timestamp=$(date '+%Y-%m-%d_%H-%M-%S')
    local report_dir="test-results-$timestamp"
    
    mkdir -p "$report_dir"
    
    # Copy test results
    [ -f "tests/api-test-results.log" ] && cp "tests/api-test-results.log" "$report_dir/"
    [ -f "tests/ui-test-results.log" ] && cp "tests/ui-test-results.log" "$report_dir/"
    [ -f "tests/test-results.json" ] && cp "tests/test-results.json" "$report_dir/"
    [ -f "tests/test-results.xml" ] && cp "tests/test-results.xml" "$report_dir/"
    [ -d "tests/playwright-report" ] && cp -r "tests/playwright-report" "$report_dir/"
    
    # Copy server logs
    [ -f "src/backend/backend.log" ] && cp "src/backend/backend.log" "$report_dir/"
    [ -f "src/frontend/frontend.log" ] && cp "src/frontend/frontend.log" "$report_dir/"
    
    # Create summary report
    cat > "$report_dir/test-summary.txt" << EOF
Secman End-to-End Test Results
==============================
Date: $(date)
Duration: $((SECONDS / 60)) minutes $((SECONDS % 60)) seconds

Test Results:
- API Tests: $([ $api_result -eq 0 ] && echo "PASSED" || echo "FAILED")
- UI Tests: $([ $ui_result -eq 0 ] && echo "PASSED" || echo "FAILED")

Overall Result: $([ $overall_result -eq 0 ] && echo "PASSED" || echo "FAILED")

Report Location: $report_dir/
EOF
    
    print_success "Test report generated in: $report_dir/"
}

# Function to cleanup
cleanup_and_exit() {
    local exit_code=${1:-0}
    
    print_status "Cleaning up..."
    
    # Stop servers if we started them
    if [ -f "src/backend/backend.pid" ]; then
        kill $(cat src/backend/backend.pid) 2>/dev/null || true
        rm -f src/backend/backend.pid
        print_status "Stopped backend server"
    fi
    
    if [ -f "src/frontend/frontend.pid" ]; then
        kill $(cat src/frontend/frontend.pid) 2>/dev/null || true
        rm -f src/frontend/frontend.pid
        print_status "Stopped frontend server"
    fi
    
    # Run cleanup script
    if [ -f "tests/scripts/cleanup-test-env.js" ]; then
        cd tests
        node scripts/cleanup-test-env.js
        cd ..
    fi
    
    exit $exit_code
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo
    echo "Options:"
    echo "  --api-only     Run only API tests"
    echo "  --ui-only      Run only UI tests"
    echo "  --no-setup     Skip environment setup"
    echo "  --no-cleanup   Skip cleanup after tests"
    echo "  --help         Show this help message"
    echo
    echo "Examples:"
    echo "  $0                    # Run all tests"
    echo "  $0 --api-only         # Run only API tests"
    echo "  $0 --ui-only          # Run only UI tests"
}

# Main function
main() {
    local api_only=false
    local ui_only=false
    local no_setup=false
    local no_cleanup=false
    
    # Parse command line arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --api-only)
                api_only=true
                shift
                ;;
            --ui-only)
                ui_only=true
                shift
                ;;
            --no-setup)
                no_setup=true
                shift
                ;;
            --no-cleanup)
                no_cleanup=true
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
    
    # Validate arguments
    if [[ "$api_only" == true && "$ui_only" == true ]]; then
        print_error "Cannot specify both --api-only and --ui-only"
        exit 1
    fi
    
    # Set trap for cleanup
    trap 'cleanup_and_exit 1' INT TERM
    
    print_status "Starting Secman End-to-End Tests..."
    print_status "Test configuration:"
    print_status "  Backend port: $BACKEND_PORT"
    print_status "  Frontend port: $FRONTEND_PORT"
    print_status "  API only: $api_only"
    print_status "  UI only: $ui_only"
    print_status "  Skip setup: $no_setup"
    print_status "  Skip cleanup: $no_cleanup"
    echo
    
    # Check requirements
    command -v node >/dev/null 2>&1 || { print_error "Node.js is required but not installed"; exit 1; }
    command -v npm >/dev/null 2>&1 || { print_error "npm is required but not installed"; exit 1; }
    command -v sbt >/dev/null 2>&1 || { print_error "sbt is required but not installed"; exit 1; }
    
    # Start services and setup environment
    if [[ "$no_setup" != true ]]; then
        start_backend
        start_frontend
        setup_test_environment
    fi
    
    # Run tests
    local api_result=0
    local ui_result=0
    
    if [[ "$ui_only" != true ]]; then
        run_api_tests || api_result=$?
    fi
    
    if [[ "$api_only" != true ]]; then
        run_ui_tests || ui_result=$?
    fi
    
    # Calculate overall result
    local overall_result=0
    if [[ $api_result -ne 0 || $ui_result -ne 0 ]]; then
        overall_result=1
    fi
    
    # Generate report
    generate_test_report
    
    # Show final results
    echo
    print_status "Test Results Summary:"
    if [[ "$ui_only" != true ]]; then
        if [[ $api_result -eq 0 ]]; then
            print_success "✅ API Tests: PASSED"
        else
            print_error "❌ API Tests: FAILED"
        fi
    fi
    
    if [[ "$api_only" != true ]]; then
        if [[ $ui_result -eq 0 ]]; then
            print_success "✅ UI Tests: PASSED"
        else
            print_error "❌ UI Tests: FAILED"
        fi
    fi
    
    echo
    if [[ $overall_result -eq 0 ]]; then
        print_success "🎉 ALL TESTS PASSED!"
    else
        print_error "💥 SOME TESTS FAILED"
    fi
    
    # Cleanup and exit
    if [[ "$no_cleanup" != true ]]; then
        cleanup_and_exit $overall_result
    else
        exit $overall_result
    fi
}

# Run main function
main "$@"