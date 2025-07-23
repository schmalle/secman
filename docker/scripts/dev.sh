#!/bin/bash
set -e

echo "🚀 Starting Secman development environment..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

# Change to project directory
cd "$(dirname "$0")/../.."

# Check if docker-compose is available
if ! command -v docker-compose &> /dev/null; then
    print_error "docker-compose is not installed. Please install Docker Compose."
    exit 1
fi

# Check if .env file exists
if [ ! -f .env ]; then
    print_warning ".env file not found. Creating from .env.example..."
    cp .env.example .env
    print_warning "Please review and update .env file with your settings before running again."
    exit 1
fi

# Parse command line arguments
COMMAND=${1:-up}
shift || true

case $COMMAND in
    up)
        print_status "Starting development environment..."
        docker-compose -f docker-compose.dev.yml up --build "$@"
        ;;
    down)
        print_status "Stopping development environment..."
        docker-compose -f docker-compose.dev.yml down "$@"
        ;;
    restart)
        print_status "Restarting development environment..."
        docker-compose -f docker-compose.dev.yml restart "$@"
        ;;
    logs)
        print_status "Showing logs..."
        docker-compose -f docker-compose.dev.yml logs -f "$@"
        ;;
    build)
        print_status "Building images..."
        docker-compose -f docker-compose.dev.yml build "$@"
        ;;
    status)
        print_status "Showing container status..."
        docker-compose -f docker-compose.dev.yml ps
        ;;
    clean)
        print_warning "Cleaning up containers, volumes, and images..."
        docker-compose -f docker-compose.dev.yml down -v --rmi all
        ;;
    *)
        echo "Usage: $0 {up|down|restart|logs|build|status|clean} [additional docker-compose args]"
        echo ""
        echo "Commands:"
        echo "  up       - Start the development environment"
        echo "  down     - Stop the development environment"
        echo "  restart  - Restart services"
        echo "  logs     - Show logs"
        echo "  build    - Build images"
        echo "  status   - Show container status"
        echo "  clean    - Clean up everything (containers, volumes, images)"
        echo ""
        echo "Examples:"
        echo "  $0 up -d                    # Start in detached mode"
        echo "  $0 logs backend             # Show only backend logs"
        echo "  $0 down --volumes           # Stop and remove volumes"
        exit 1
        ;;
esac

if [ "$COMMAND" = "up" ] && [ "$1" != "-d" ]; then
    echo ""
    print_info "Development environment started!"
    print_info "Frontend: http://localhost:4321"
    print_info "Backend API: http://localhost:8080"
    print_info "Database: localhost:3306"
    echo ""
    print_info "Press Ctrl+C to stop all services"
fi