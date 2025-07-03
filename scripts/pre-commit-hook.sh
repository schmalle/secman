#!/bin/bash

# Pre-commit hook for Secman
# This script runs tests and takes screenshots before allowing commits

set -e

echo "🚀 Running pre-commit checks for Secman..."

# Change to project root
cd "$(dirname "$0")/.."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if a service is running
check_service() {
    local service_name="$1"
    local port="$2"
    local max_attempts=3
    local attempt=1

    while [ $attempt -le $max_attempts ]; do
        if curl -s "http://localhost:$port" > /dev/null 2>&1; then
            return 0
        fi
        print_warning "$service_name not ready (attempt $attempt/$max_attempts), waiting..."
        sleep 2
        attempt=$((attempt + 1))
    done
    return 1
}

# Check if we have Node.js and npm
check_dependencies() {
    print_status "Checking dependencies..."
    
    if ! command -v node &> /dev/null; then
        print_error "Node.js is required but not installed"
        exit 1
    fi
    
    if ! command -v npm &> /dev/null; then
        print_error "npm is required but not installed"
        exit 1
    fi
    
    if ! command -v sbt &> /dev/null; then
        print_error "sbt is required but not installed"
        exit 1
    fi
}

# Run backend tests
run_backend_tests() {
    print_status "Running backend tests..."
    
    cd src/backend
    if sbt test; then
        print_status "✅ Backend tests passed"
    else
        print_error "❌ Backend tests failed"
        exit 1
    fi
    cd ../..
}

# Run frontend check-in tests
run_frontend_tests() {
    print_status "Running frontend check-in tests..."
    
    cd src/frontend
    
    # Install dependencies if needed
    if [ ! -d "node_modules" ]; then
        print_status "Installing frontend dependencies..."
        npm install
    fi
    
    # Install Playwright if needed
    if [ ! -d "node_modules/@playwright" ]; then
        print_status "Installing Playwright..."
        npx playwright install chromium
    fi
    
    # Run check-in tests
    if npm run test:checkin; then
        print_status "✅ Frontend check-in tests passed"
    else
        print_error "❌ Frontend check-in tests failed"
        cd ../..
        exit 1
    fi
    
    cd ../..
}

# Start services for screenshot capture
start_services() {
    print_status "Starting services for screenshot capture..."
    
    # Check if backend is already running
    if check_service "Backend" 9000; then
        print_status "Backend already running"
        BACKEND_WAS_RUNNING=true
    else
        print_status "Starting backend..."
        cd src/backend
        sbt run &
        BACKEND_PID=$!
        cd ../..
        BACKEND_WAS_RUNNING=false
        
        # Wait for backend to start
        sleep 10
        if ! check_service "Backend" 9000; then
            print_error "Failed to start backend"
            cleanup_services
            exit 1
        fi
    fi
    
    # Check if frontend is already running
    if check_service "Frontend" 4321; then
        print_status "Frontend already running"
        FRONTEND_WAS_RUNNING=true
    else
        print_status "Starting frontend..."
        cd src/frontend
        npm run dev &
        FRONTEND_PID=$!
        cd ../..
        FRONTEND_WAS_RUNNING=false
        
        # Wait for frontend to start
        sleep 8
        if ! check_service "Frontend" 4321; then
            print_error "Failed to start frontend"
            cleanup_services
            exit 1
        fi
    fi
}

# Stop services if we started them
cleanup_services() {
    if [ "$BACKEND_WAS_RUNNING" = false ] && [ -n "$BACKEND_PID" ]; then
        print_status "Stopping backend..."
        kill $BACKEND_PID 2>/dev/null || true
        pkill -f "sbt.*run" 2>/dev/null || true
    fi
    
    if [ "$FRONTEND_WAS_RUNNING" = false ] && [ -n "$FRONTEND_PID" ]; then
        print_status "Stopping frontend..."
        kill $FRONTEND_PID 2>/dev/null || true
        pkill -f "npm.*dev" 2>/dev/null || true
    fi
}

# Install screenshot dependencies if needed
install_screenshot_deps() {
    if [ ! -d "node_modules" ] || [ ! -d "node_modules/playwright" ]; then
        print_status "Installing screenshot dependencies..."
        npm install playwright
        npx playwright install chromium
    fi
}

# Take screenshots
take_screenshots() {
    print_status "Taking UI screenshots..."
    
    install_screenshot_deps
    
    if node scripts/take-screenshots.js; then
        print_status "✅ Screenshots captured successfully"
        
        # Add screenshots to git staging
        git add pictures/
        print_status "📁 Screenshots added to commit"
    else
        print_warning "⚠️  Screenshot capture failed, but continuing with commit"
        # Don't fail the commit if screenshots fail
    fi
}

# Main execution
main() {
    print_status "Starting pre-commit hook execution..."
    
    # Trap to ensure cleanup on exit
    # x^trap cleanup_services EXIT
    
    # Check dependencies
    #check_dependencies
    
    # Run backend tests
    #run_backend_tests
    
    # Start services for frontend tests and screenshots
    # start_services
    
    # Run frontend check-in tests
    #run_frontend_tests
    
    # Take screenshots
    #take_screenshots
    
    # print_status "🎉 Pre-commit checks completed successfully!"
}

# Execute main function
main "$@"