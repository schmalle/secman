#!/bin/bash
set -e

# Multi-architecture build script for Secman
# Supports ARM64 and x64 architectures

REGISTRY=${REGISTRY:-"secman"}
TAG=${TAG:-"latest"}
PLATFORM=${PLATFORM:-"linux/amd64,linux/arm64"}

echo "Building Secman Docker images with multi-architecture support..."
echo "Registry: $REGISTRY"
echo "Tag: $TAG"
echo "Platforms: $PLATFORM"

# Create builder if it doesn't exist
if ! docker buildx inspect secman-builder >/dev/null 2>&1; then
    echo "Creating buildx builder instance..."
    docker buildx create --name secman-builder --driver docker-container --bootstrap
fi

# Use the builder
docker buildx use secman-builder

# Build and push backend
echo "Building backend image..."
docker buildx build \
    --platform $PLATFORM \
    --tag $REGISTRY/secman-backend:$TAG \
    --file docker/backend/Dockerfile \
    --push \
    .

# Build and push frontend (production)
echo "Building frontend production image..."
docker buildx build \
    --platform $PLATFORM \
    --tag $REGISTRY/secman-frontend:$TAG \
    --file docker/frontend/Dockerfile \
    --target production \
    --push \
    .

# Build and push frontend (development)
echo "Building frontend development image..."
docker buildx build \
    --platform $PLATFORM \
    --tag $REGISTRY/secman-frontend:$TAG-dev \
    --file docker/frontend/Dockerfile \
    --target development \
    --push \
    .

# Build and push database
echo "Building database image..."
docker buildx build \
    --platform $PLATFORM \
    --tag $REGISTRY/secman-database:$TAG \
    --file docker/database/Dockerfile \
    docker/database

echo "Multi-architecture build completed successfully!"
echo "Images built:"
echo "  - $REGISTRY/secman-backend:$TAG"
echo "  - $REGISTRY/secman-frontend:$TAG"
echo "  - $REGISTRY/secman-frontend:$TAG-dev"
echo "  - $REGISTRY/secman-database:$TAG"