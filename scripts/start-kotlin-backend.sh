#!/bin/bash

# Start Kotlin Micronaut Backend
echo "Starting Kotlin Micronaut Backend on port 8080..."

# Change to the backendng directory
cd "$(dirname "$0")/../src/backendng"

# Create logs directory if it doesn't exist
mkdir -p ../logs

# Start the Kotlin backend
nohup java -jar build/libs/secman-backend-ng-0.1-all.jar > ../logs/kotlin-backend.log 2>&1 &
echo $! > ../logs/kotlin-backend.pid

echo "Kotlin backend started. PID: $(cat ../logs/kotlin-backend.pid)"
echo "Logs: tail -f ../logs/kotlin-backend.log"
echo "Port: http://localhost:8080"
