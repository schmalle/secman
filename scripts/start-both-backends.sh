#!/bin/bash

# Start both backends in parallel for gradual migration
echo "Starting both backends for parallel operation during migration..."

# Create logs directory
mkdir -p src/logs

# Build Kotlin backend first
echo "Building Kotlin backend..."
cd src/backendng
gradle shadowJar --no-daemon
cd ../..

# Start Java backend
echo "Starting Java Play backend on port 9000..."
cd src/backend
nohup sbt run > ../logs/java-backend.log 2>&1 &
echo $! > ../logs/java-backend.pid
cd ../..

# Wait a moment for Java backend to start
sleep 5

# Start Kotlin backend
echo "Starting Kotlin Micronaut backend on port 9001..."
cd src/backendng
nohup java -jar build/libs/secman-backend-ng-0.1-all.jar > ../logs/kotlin-backend.log 2>&1 &
echo $! > ../logs/kotlin-backend.pid
cd ../..

echo ""
echo "Both backends are starting..."
echo ""
echo "Java Play Backend:"
echo "  Port: http://localhost:9000"
echo "  PID: $(cat src/logs/java-backend.pid)"
echo "  Logs: tail -f src/logs/java-backend.log"
echo ""
echo "Kotlin Micronaut Backend:"
echo "  Port: http://localhost:9001"
echo "  PID: $(cat src/logs/kotlin-backend.pid)"
echo "  Logs: tail -f src/logs/kotlin-backend.log"
echo ""
echo "Frontend (if running): http://localhost:4321"
echo ""
echo "Health checks:"
echo "  curl http://localhost:9000"
echo "  curl http://localhost:9001/health"
echo ""
echo "To stop both backends: ./scripts/stop-both-backends.sh"