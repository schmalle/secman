#!/bin/bash
set -e

echo "🚀 Building Secman application for multiple architectures..."

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

# Check if Docker buildx is available
if ! docker buildx version > /dev/null 2>&1; then
    print_error "Docker buildx is not available. Please install Docker Desktop or enable buildx."
    exit 1
fi

# Create buildx builder if it doesn't exist
if ! docker buildx ls | grep -q secman-builder; then
    print_status "Creating buildx builder..."
    docker buildx create --name secman-builder --driver docker-container --bootstrap
fi

# Use the builder
docker buildx use secman-builder

# Build for multiple architectures
PLATFORMS="linux/amd64,linux/arm64"
TAG_PREFIX="secman"
VERSION=${1:-latest}

print_status "Building backend for platforms: $PLATFORMS"
docker buildx build \
    --platform $PLATFORMS \
    --file docker/backend/Dockerfile \
    --tag $TAG_PREFIX/backend:$VERSION \
    --push \
    .

print_status "Building frontend for platforms: $PLATFORMS"
docker buildx build \
    --platform $PLATFORMS \
    --file docker/frontend/Dockerfile \
    --tag $TAG_PREFIX/frontend:$VERSION \
    --push \
    .

print_status "Building database for platforms: $PLATFORMS"
docker buildx build \
    --platform $PLATFORMS \
    --file docker/database/Dockerfile \
    --tag $TAG_PREFIX/database:$VERSION \
    --push \
    .

print_status "Multi-architecture build completed successfully!"
print_warning "Note: Images have been pushed to registry. Make sure you're logged in to the correct registry."

echo ""
echo "Built images:"
echo "  - $TAG_PREFIX/backend:$VERSION"
echo "  - $TAG_PREFIX/frontend:$VERSION"
echo "  - $TAG_PREFIX/database:$VERSION"