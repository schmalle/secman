#!/bin/bash

# Start Java Play Framework Backend
echo "Starting Java Play Backend on port 9000..."

cd src/backend
nohup sbt run > ../logs/java-backend.log 2>&1 &
echo $! > ../logs/java-backend.pid

echo "Java backend started. PID: $(cat ../logs/java-backend.pid)"
echo "Logs: tail -f src/logs/java-backend.log"
echo "Port: http://localhost:9000"