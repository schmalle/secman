#!/bin/bash
set -e

echo "🔧 Building Secman application for local development..."

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
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Change to project directory
cd "$(dirname "$0")/../.."

# Check if .env file exists, if not copy from example
if [ ! -f .env ]; then
    print_warning ".env file not found. Creating from .env.example..."
    cp .env.example .env
    print_warning "Please review and update .env file with your settings."
fi

# Build images locally
print_status "Building backend development image..."
docker build -f docker/backend/Dockerfile.dev -t secman/backend:dev .

print_status "Building frontend development image..."
docker build -f docker/frontend/Dockerfile.dev -t secman/frontend:dev .

print_status "Building database image..."
docker build -f docker/database/Dockerfile -t secman/database:dev .

print_status "Local development build completed successfully!"

echo ""
echo "Built images:"
echo "  - secman/backend:dev"
echo "  - secman/frontend:dev"
echo "  - secman/database:dev"
echo ""
echo "To start the development environment, run:"
echo "  docker-compose -f docker-compose.dev.yml up"