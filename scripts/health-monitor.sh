#!/bin/bash

# Health Monitoring Script
# Continuously monitors both backends and alerts on issues

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

JAVA_BASE="http://localhost:9000"
KOTLIN_BASE="http://localhost:9001"
LOGS_DIR="src/logs"
MONITOR_LOG="$LOGS_DIR/health_monitor.log"
CHECK_INTERVAL=${1:-30}  # Default 30 seconds, can be overridden

echo -e "${BLUE}🔍 Health Monitor Started - $(date)${NC}"
echo "Monitoring interval: ${CHECK_INTERVAL} seconds"
echo "Java backend: $JAVA_BASE"
echo "Kotlin backend: $KOTLIN_BASE"
echo "Monitor log: $MONITOR_LOG"
echo "Press Ctrl+C to stop monitoring"
echo "================================================="

# Create logs directory
mkdir -p "$LOGS_DIR"

# Initialize log file
echo "Health Monitor Started - $(date)" >> "$MONITOR_LOG"

# Counters for health statistics
java_up_count=0
java_down_count=0
kotlin_up_count=0
kotlin_down_count=0
total_checks=0

# Function to check backend health
check_backend() {
    local name=$1
    local url=$2
    local timeout=${3:-5}
    
    if curl -s --max-time "$timeout" "$url" > /dev/null 2>&1; then
        return 0  # Healthy
    else
        return 1  # Unhealthy
    fi
}

# Function to get response time
get_response_time() {
    local url=$1
    local timeout=${2:-5}
    
    curl -o /dev/null -s --max-time "$timeout" -w %{time_total} "$url" 2>/dev/null || echo "timeout"
}

# Function to log health status
log_status() {
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] $1" >> "$MONITOR_LOG"
}

# Function to display current status
display_status() {
    local java_status=$1
    local kotlin_status=$2
    local java_time=$3
    local kotlin_time=$4
    
    printf "\r$(date '+%H:%M:%S') | "
    
    if [ "$java_status" = "UP" ]; then
        printf "${GREEN}Java: ✅ ${java_time}s${NC} | "
    else
        printf "${RED}Java: ❌ DOWN${NC} | "
    fi
    
    if [ "$kotlin_status" = "UP" ]; then
        printf "${GREEN}Kotlin: ✅ ${kotlin_time}s${NC} | "
    else
        printf "${RED}Kotlin: ❌ DOWN${NC} | "
    fi
    
    local java_uptime=$(echo "scale=1; $java_up_count * 100 / $total_checks" | bc -l 2>/dev/null || echo "0")
    local kotlin_uptime=$(echo "scale=1; $kotlin_up_count * 100 / $total_checks" | bc -l 2>/dev/null || echo "0")
    
    printf "Uptime: J:${java_uptime}%% K:${kotlin_uptime}%%"
}

# Trap for graceful exit
cleanup() {
    echo ""
    echo -e "${YELLOW}Health Monitor Stopped - $(date)${NC}"
    log_status "Health Monitor Stopped"
    
    # Display final statistics
    echo "================================================="
    echo "📊 Final Statistics:"
    echo "  Total checks: $total_checks"
    echo "  Java backend:"
    echo "    Up: $java_up_count checks"
    echo "    Down: $java_down_count checks"
    if [ $total_checks -gt 0 ]; then
        java_uptime=$(echo "scale=2; $java_up_count * 100 / $total_checks" | bc -l 2>/dev/null || echo "0")
        echo "    Uptime: ${java_uptime}%"
    fi
    echo "  Kotlin backend:"
    echo "    Up: $kotlin_up_count checks"
    echo "    Down: $kotlin_down_count checks"
    if [ $total_checks -gt 0 ]; then
        kotlin_uptime=$(echo "scale=2; $kotlin_up_count * 100 / $total_checks" | bc -l 2>/dev/null || echo "0")
        echo "    Uptime: ${kotlin_uptime}%"
    fi
    echo "  Monitor log: $MONITOR_LOG"
    exit 0
}

trap cleanup SIGINT SIGTERM

# Initial status
echo "Starting health checks..."
log_status "Health Monitor initialized"

# Main monitoring loop
while true; do
    total_checks=$((total_checks + 1))
    
    # Check Java backend
    if check_backend "Java" "$JAVA_BASE"; then
        java_status="UP"
        java_up_count=$((java_up_count + 1))
        java_time=$(get_response_time "$JAVA_BASE")
    else
        java_status="DOWN"
        java_down_count=$((java_down_count + 1))
        java_time="N/A"
        log_status "ALERT: Java backend DOWN"
    fi
    
    # Check Kotlin backend
    if check_backend "Kotlin" "$KOTLIN_BASE/health"; then
        kotlin_status="UP"
        kotlin_up_count=$((kotlin_up_count + 1))
        kotlin_time=$(get_response_time "$KOTLIN_BASE/health")
    else
        kotlin_status="DOWN"
        kotlin_down_count=$((kotlin_down_count + 1))
        kotlin_time="N/A"
        log_status "ALERT: Kotlin backend DOWN"
    fi
    
    # Display current status (overwrites previous line)
    display_status "$java_status" "$kotlin_status" "$java_time" "$kotlin_time"
    
    # Log significant events
    if [ "$java_status" = "DOWN" ] && [ "$kotlin_status" = "DOWN" ]; then
        log_status "CRITICAL: Both backends DOWN!"
        echo ""
        echo -e "${RED}🚨 CRITICAL ALERT: Both backends are down!${NC}"
        echo -e "${YELLOW}Consider running emergency procedures${NC}"
    elif [ "$java_status" = "DOWN" ]; then
        log_status "WARNING: Java backend DOWN, Kotlin still running"
    elif [ "$kotlin_status" = "DOWN" ]; then
        log_status "WARNING: Kotlin backend DOWN, Java still running"
    fi
    
    # Performance warning
    if [ "$java_time" != "N/A" ] && [ "$java_time" != "timeout" ]; then
        if [ "$(echo "$java_time > 5" | bc -l 2>/dev/null)" = "1" ]; then
            log_status "PERFORMANCE: Java backend slow response: ${java_time}s"
        fi
    fi
    
    if [ "$kotlin_time" != "N/A" ] && [ "$kotlin_time" != "timeout" ]; then
        if [ "$(echo "$kotlin_time > 5" | bc -l 2>/dev/null)" = "1" ]; then
            log_status "PERFORMANCE: Kotlin backend slow response: ${kotlin_time}s"
        fi
    fi
    
    sleep "$CHECK_INTERVAL"
done