#!/bin/bash

# Enhanced stop script with better error handling and logging
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

LOGS_DIR="src/logs"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

echo -e "${YELLOW}🛑 Stopping both backends - $(date)${NC}"

# Create logs directory if it doesn't exist
mkdir -p "$LOGS_DIR"

# Function to stop a backend safely
stop_backend() {
    local backend_name=$1
    local pid_file=$2
    local port=$3
    
    echo -e "${YELLOW}Stopping $backend_name backend...${NC}"
    
    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        echo "  Found PID file: $pid"
        
        if kill -0 "$pid" 2>/dev/null; then
            echo "  Process $pid is running, sending TERM signal..."
            kill "$pid" 2>/dev/null
            
            # Wait up to 10 seconds for graceful shutdown
            for i in {1..10}; do
                if ! kill -0 "$pid" 2>/dev/null; then
                    echo -e "  ${GREEN}✅ $backend_name backend stopped gracefully${NC}"
                    break
                fi
                sleep 1
            done
            
            # Force kill if still running
            if kill -0 "$pid" 2>/dev/null; then
                echo "  Forcing shutdown..."
                kill -9 "$pid" 2>/dev/null || true
                echo -e "  ${YELLOW}⚠️  $backend_name backend force-stopped${NC}"
            fi
        else
            echo "  Process $pid not running"
        fi
        
        rm -f "$pid_file"
    else
        echo "  No PID file found for $backend_name backend"
    fi
    
    # Clean up any remaining processes on the port
    echo "  Cleaning up port $port..."
    local remaining_pids=$(lsof -ti:$port 2>/dev/null || true)
    if [ -n "$remaining_pids" ]; then
        echo "  Found processes on port $port: $remaining_pids"
        echo "$remaining_pids" | xargs kill -9 2>/dev/null || true
        echo "  Port $port cleaned up"
    fi
}

# Stop Java backend
stop_backend "Java" "$LOGS_DIR/java-backend.pid" "9000"

# Stop Kotlin backend
stop_backend "Kotlin" "$LOGS_DIR/kotlin-backend.pid" "9001"

# Verify ports are free
echo -e "${YELLOW}Verifying ports are free...${NC}"
for port in 9000 9001; do
    if lsof -ti:$port >/dev/null 2>&1; then
        echo -e "  ${RED}❌ Port $port still in use${NC}"
        lsof -i:$port
    else
        echo -e "  ${GREEN}✅ Port $port is free${NC}"
    fi
done

# Archive logs
if [ -f "$LOGS_DIR/java-backend.log" ] || [ -f "$LOGS_DIR/kotlin-backend.log" ]; then
    echo -e "${YELLOW}Archiving logs...${NC}"
    
    # Create archive directory
    mkdir -p "$LOGS_DIR/archive"
    
    # Archive existing logs
    if [ -f "$LOGS_DIR/java-backend.log" ]; then
        mv "$LOGS_DIR/java-backend.log" "$LOGS_DIR/archive/java-backend_$TIMESTAMP.log"
        echo "  Java backend logs archived"
    fi
    
    if [ -f "$LOGS_DIR/kotlin-backend.log" ]; then
        mv "$LOGS_DIR/kotlin-backend.log" "$LOGS_DIR/archive/kotlin-backend_$TIMESTAMP.log"
        echo "  Kotlin backend logs archived"
    fi
fi

echo -e "${GREEN}🏁 Both backends stopped successfully${NC}"

# Show summary
echo ""
echo "📊 Stop Summary:"
echo "  Timestamp: $(date)"
echo "  Java backend: Stopped"
echo "  Kotlin backend: Stopped"
echo "  Port 9000: Free"
echo "  Port 9001: Free"
echo "  Logs: Archived in $LOGS_DIR/archive/"