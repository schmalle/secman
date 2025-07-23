#!/bin/bash
set -e

# Local development build script for Secman
# Builds images for local architecture only

REGISTRY=${REGISTRY:-"secman"}
TAG=${TAG:-"latest"}

echo "Building Secman Docker images for local development..."
echo "Registry: $REGISTRY"
echo "Tag: $TAG"

# Build backend
echo "Building backend image..."
docker build \
    --tag $REGISTRY/secman-backend:$TAG \
    --file docker/backend/Dockerfile \
    .

# Build frontend (production)
echo "Building frontend production image..."
docker build \
    --tag $REGISTRY/secman-frontend:$TAG \
    --file docker/frontend/Dockerfile \
    --target production \
    .

# Build frontend (development)
echo "Building frontend development image..."
docker build \
    --tag $REGISTRY/secman-frontend:$TAG-dev \
    --file docker/frontend/Dockerfile \
    --target development \
    .

# Build database
echo "Building database image..."
docker build \
    --tag $REGISTRY/secman-database:$TAG \
    --file docker/database/Dockerfile \
    docker/database

echo "Local build completed successfully!"
echo "Images built:"
echo "  - $REGISTRY/secman-backend:$TAG"
echo "  - $REGISTRY/secman-frontend:$TAG"
echo "  - $REGISTRY/secman-frontend:$TAG-dev"
echo "  - $REGISTRY/secman-database:$TAG"

echo ""
echo "To start the application:"
echo "  Production: cd docker/compose && docker compose up -d"
echo "  Development: cd docker/compose && docker compose -f docker-compose.dev.yml up -d"