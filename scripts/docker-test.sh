#!/bin/bash
set -e

# Docker-based testing script for Secman
# This script starts the test environment and runs all tests

echo "Starting Secman Docker test environment..."

# Set environment variables for testing
export DB_PASSWORD="testpass123"
export JWT_SECRET="testsecretkeythatislong256bitsforjwttokensignatureverification"
export COMPOSE_FILE="docker-compose.dev.yml"

# Navigate to compose directory
cd "$(dirname "$0")/../docker/compose"

# Copy env template if .env doesn't exist
if [ ! -f .env ]; then
    cp .env.example .env
    echo "Created .env file from template"
fi

# Start the test environment
echo "Starting Docker test environment..."
docker compose -f $COMPOSE_FILE up -d

# Wait for services to be healthy
echo "Waiting for services to be ready..."
sleep 30

# Check service health
echo "Checking service health..."
for i in {1..30}; do
    if docker compose -f $COMPOSE_FILE ps --filter "status=running" | grep -q "secman-.*-dev.*healthy"; then
        echo "Services are healthy!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "Timeout waiting for services to be healthy"
        docker compose -f $COMPOSE_FILE logs
        exit 1
    fi
    echo "Waiting for services... ($i/30)"
    sleep 5
done

# Run backend tests
echo "Running backend tests..."
if docker compose -f $COMPOSE_FILE exec -T backend gradle test; then
    echo "✓ Backend tests passed"
else
    echo "✗ Backend tests failed"
    BACKEND_FAILED=1
fi

# Run frontend tests
echo "Running frontend tests..."
if docker compose -f $COMPOSE_FILE exec -T frontend npm run test; then
    echo "✓ Frontend tests passed"
else
    echo "✗ Frontend tests failed"
    FRONTEND_FAILED=1
fi

# Cleanup
echo "Cleaning up test environment..."
docker compose -f $COMPOSE_FILE down -v

# Exit with appropriate code
if [ -n "$BACKEND_FAILED" ] || [ -n "$FRONTEND_FAILED" ]; then
    echo "Some tests failed!"
    exit 1
else
    echo "All tests passed!"
    exit 0
fi