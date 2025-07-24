#!/bin/bash

# Emergency Rollback Script
# Immediately switches all traffic to Java backend and stops Kotlin backend

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

LOGS_DIR="src/logs"
FRONTEND_API_CONFIG="src/frontend/src/utils/api-config.ts"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
ROLLBACK_LOG="$LOGS_DIR/emergency_rollback_$TIMESTAMP.log"

echo -e "${RED}🚨 EMERGENCY ROLLBACK INITIATED - $(date)${NC}" | tee "$ROLLBACK_LOG"
echo -e "${RED}Switching all traffic to Java backend and stopping Kotlin backend${NC}" | tee -a "$ROLLBACK_LOG"
echo "=================================================" | tee -a "$ROLLBACK_LOG"

# Create logs directory
mkdir -p "$LOGS_DIR"

# Function to update frontend API configuration
rollback_frontend_config() {
    echo -e "${YELLOW}📝 Rolling back frontend API configuration...${NC}" | tee -a "$ROLLBACK_LOG"
    
    if [ ! -f "$FRONTEND_API_CONFIG" ]; then
        echo -e "${RED}❌ Frontend API config file not found: $FRONTEND_API_CONFIG${NC}" | tee -a "$ROLLBACK_LOG"
        return 1
    fi
    
    # Create backup
    cp "$FRONTEND_API_CONFIG" "$FRONTEND_API_CONFIG.rollback_backup_$TIMESTAMP"
    echo "  Created backup: $FRONTEND_API_CONFIG.rollback_backup_$TIMESTAMP" | tee -a "$ROLLBACK_LOG"
    
    # Switch all APIs back to Java backend (set all to false)
    sed -i.tmp '
        s/auth: true,/auth: false,/g
        s/users: true,/users: false,/g
        s/requirements: true,/requirements: false,/g
        s/standards: true,/standards: false,/g
        s/risks: true,/risks: false,/g
        s/responses: true,/responses: false,/g
        s/health: true,/health: false,/g
    ' "$FRONTEND_API_CONFIG"
    
    # Remove temp file
    rm -f "$FRONTEND_API_CONFIG.tmp"
    
    echo -e "  ${GREEN}✅ Frontend configuration rolled back to Java backend${NC}" | tee -a "$ROLLBACK_LOG"
}

# Function to verify Java backend is running
check_java_backend() {
    echo -e "${YELLOW}🔍 Checking Java backend status...${NC}" | tee -a "$ROLLBACK_LOG"
    
    if curl -s "http://localhost:9000" > /dev/null 2>&1; then
        echo -e "  ${GREEN}✅ Java backend is running and responding${NC}" | tee -a "$ROLLBACK_LOG"
        return 0
    else
        echo -e "  ${RED}❌ Java backend is not responding${NC}" | tee -a "$ROLLBACK_LOG"
        return 1
    fi
}

# Function to start Java backend if not running
start_java_backend() {
    echo -e "${YELLOW}🚀 Starting Java backend...${NC}" | tee -a "$ROLLBACK_LOG"
    
    cd src/backend
    nohup sbt run > "../logs/java-backend-emergency.log" 2>&1 &
    echo $! > "../logs/java-backend.pid"
    cd - > /dev/null
    
    # Wait for startup
    echo "  Waiting for Java backend to start..." | tee -a "$ROLLBACK_LOG"
    for i in {1..30}; do
        if curl -s "http://localhost:9000" > /dev/null 2>&1; then
            echo -e "  ${GREEN}✅ Java backend started successfully${NC}" | tee -a "$ROLLBACK_LOG"
            return 0
        fi
        sleep 2
    done
    
    echo -e "  ${RED}❌ Java backend failed to start within 60 seconds${NC}" | tee -a "$ROLLBACK_LOG"
    return 1
}

# Function to stop Kotlin backend
stop_kotlin_backend() {
    echo -e "${YELLOW}🛑 Stopping Kotlin backend...${NC}" | tee -a "$ROLLBACK_LOG"
    
    if [ -f "$LOGS_DIR/kotlin-backend.pid" ]; then
        local pid=$(cat "$LOGS_DIR/kotlin-backend.pid")
        echo "  Found Kotlin backend PID: $pid" | tee -a "$ROLLBACK_LOG"
        
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null
            
            # Wait for graceful shutdown
            for i in {1..10}; do
                if ! kill -0 "$pid" 2>/dev/null; then
                    echo -e "  ${GREEN}✅ Kotlin backend stopped gracefully${NC}" | tee -a "$ROLLBACK_LOG"
                    break
                fi
                sleep 1
            done
            
            # Force kill if still running
            if kill -0 "$pid" 2>/dev/null; then
                kill -9 "$pid" 2>/dev/null
                echo -e "  ${YELLOW}⚠️  Kotlin backend force-stopped${NC}" | tee -a "$ROLLBACK_LOG"
            fi
        fi
        
        rm -f "$LOGS_DIR/kotlin-backend.pid"
    else
        echo "  No Kotlin backend PID file found" | tee -a "$ROLLBACK_LOG"
    fi
    
    # Kill any remaining processes on port 9001
    local remaining_pids=$(lsof -ti:9001 2>/dev/null || true)
    if [ -n "$remaining_pids" ]; then
        echo "  Cleaning up remaining processes on port 9001..." | tee -a "$ROLLBACK_LOG"
        echo "$remaining_pids" | xargs kill -9 2>/dev/null || true
    fi
    
    echo -e "  ${GREEN}✅ Kotlin backend completely stopped${NC}" | tee -a "$ROLLBACK_LOG"
}

# Function to verify rollback success
verify_rollback() {
    echo -e "${YELLOW}🔍 Verifying rollback success...${NC}" | tee -a "$ROLLBACK_LOG"
    
    # Check Java backend is responding
    if curl -s "http://localhost:9000" > /dev/null 2>&1; then
        echo -e "  ${GREEN}✅ Java backend responding on port 9000${NC}" | tee -a "$ROLLBACK_LOG"
    else
        echo -e "  ${RED}❌ Java backend not responding${NC}" | tee -a "$ROLLBACK_LOG"
        return 1
    fi
    
    # Check Kotlin backend is stopped
    if curl -s "http://localhost:9001/health" > /dev/null 2>&1; then
        echo -e "  ${RED}❌ Kotlin backend still responding on port 9001${NC}" | tee -a "$ROLLBACK_LOG"
        return 1
    else
        echo -e "  ${GREEN}✅ Kotlin backend stopped (port 9001 free)${NC}" | tee -a "$ROLLBACK_LOG"
    fi
    
    # Check frontend configuration
    if grep -q "auth: false," "$FRONTEND_API_CONFIG" && grep -q "users: false," "$FRONTEND_API_CONFIG"; then
        echo -e "  ${GREEN}✅ Frontend configuration rolled back${NC}" | tee -a "$ROLLBACK_LOG"
    else
        echo -e "  ${RED}❌ Frontend configuration not properly rolled back${NC}" | tee -a "$ROLLBACK_LOG"
        return 1
    fi
    
    return 0
}

# Main rollback sequence
echo -e "${BLUE}Step 1: Rolling back frontend configuration${NC}" | tee -a "$ROLLBACK_LOG"
rollback_frontend_config

echo -e "${BLUE}Step 2: Ensuring Java backend is running${NC}" | tee -a "$ROLLBACK_LOG"
if ! check_java_backend; then
    if ! start_java_backend; then
        echo -e "${RED}❌ CRITICAL: Could not start Java backend${NC}" | tee -a "$ROLLBACK_LOG"
        echo -e "${RED}Manual intervention required!${NC}" | tee -a "$ROLLBACK_LOG"
        exit 1
    fi
fi

echo -e "${BLUE}Step 3: Stopping Kotlin backend${NC}" | tee -a "$ROLLBACK_LOG"
stop_kotlin_backend

echo -e "${BLUE}Step 4: Verifying rollback${NC}" | tee -a "$ROLLBACK_LOG"
if verify_rollback; then
    echo "" | tee -a "$ROLLBACK_LOG"
    echo -e "${GREEN}🎉 EMERGENCY ROLLBACK COMPLETED SUCCESSFULLY${NC}" | tee -a "$ROLLBACK_LOG"
    echo "=================================================" | tee -a "$ROLLBACK_LOG"
    echo "📊 Rollback Summary:" | tee -a "$ROLLBACK_LOG"
    echo "  Timestamp: $(date)" | tee -a "$ROLLBACK_LOG"
    echo "  Frontend: All traffic routed to Java backend" | tee -a "$ROLLBACK_LOG"
    echo "  Java backend: Running on port 9000" | tee -a "$ROLLBACK_LOG"
    echo "  Kotlin backend: Stopped" | tee -a "$ROLLBACK_LOG"
    echo "  Rollback log: $ROLLBACK_LOG" | tee -a "$ROLLBACK_LOG"
    echo "  Configuration backup: $FRONTEND_API_CONFIG.rollback_backup_$TIMESTAMP" | tee -a "$ROLLBACK_LOG"
    echo "" | tee -a "$ROLLBACK_LOG"
    echo -e "${GREEN}✅ System is now running on Java backend only${NC}" | tee -a "$ROLLBACK_LOG"
    echo -e "${YELLOW}⚠️  Remember to investigate the issue before attempting migration again${NC}" | tee -a "$ROLLBACK_LOG"
else
    echo "" | tee -a "$ROLLBACK_LOG"
    echo -e "${RED}❌ ROLLBACK VERIFICATION FAILED${NC}" | tee -a "$ROLLBACK_LOG"
    echo -e "${RED}Manual intervention required!${NC}" | tee -a "$ROLLBACK_LOG"
    echo "Check the rollback log: $ROLLBACK_LOG" | tee -a "$ROLLBACK_LOG"
    exit 1
fi