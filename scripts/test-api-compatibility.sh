#!/bin/bash

# API Compatibility Testing Script
# Tests that Kotlin backend responses are identical to Java backend responses

set -e

JAVA_BASE="http://localhost:9000"
KOTLIN_BASE="http://localhost:9001"
TEST_RESULTS_DIR="src/logs/compatibility-tests"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
TEST_LOG="$TEST_RESULTS_DIR/compatibility_test_$TIMESTAMP.log"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Create results directory
mkdir -p "$TEST_RESULTS_DIR"

echo "🔍 API Compatibility Testing - $(date)" | tee "$TEST_LOG"
echo "Java Backend: $JAVA_BASE" | tee -a "$TEST_LOG"
echo "Kotlin Backend: $KOTLIN_BASE" | tee -a "$TEST_LOG"
echo "=================================================" | tee -a "$TEST_LOG"

# Test counter
TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

# Function to check if backend is running
check_backend() {
    local name=$1
    local url=$2
    
    if curl -s "$url" > /dev/null 2>&1; then
        echo -e "${GREEN}✅ $name backend is running${NC}" | tee -a "$TEST_LOG"
        return 0
    else
        echo -e "${RED}❌ $name backend is NOT running${NC}" | tee -a "$TEST_LOG"
        return 1
    fi
}

# Function to test API endpoint compatibility
test_endpoint() {
    local endpoint=$1
    local method=${2:-GET}
    local auth_header=${3:-""}
    local body=${4:-""}
    
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    
    echo "Testing: $method $endpoint" | tee -a "$TEST_LOG"
    
    # Prepare curl options
    local curl_opts="-s -w %{http_code}"
    if [ "$method" = "POST" ] || [ "$method" = "PUT" ]; then
        curl_opts="$curl_opts -H 'Content-Type: application/json'"
        if [ -n "$body" ]; then
            curl_opts="$curl_opts -d '$body'"
        fi
    fi
    if [ -n "$auth_header" ]; then
        curl_opts="$curl_opts -H '$auth_header'"
    fi
    
    # Test Java backend
    local java_response=$(mktemp)
    local java_code=$(eval "curl $curl_opts -X $method '$JAVA_BASE$endpoint'" > "$java_response" 2>/dev/null; echo "${PIPESTATUS[0]}")
    
    # Test Kotlin backend  
    local kotlin_response=$(mktemp)
    local kotlin_code=$(eval "curl $curl_opts -X $method '$KOTLIN_BASE$endpoint'" > "$kotlin_response" 2>/dev/null; echo "${PIPESTATUS[0]}")
    
    # Compare responses
    if [ "$java_code" = "$kotlin_code" ]; then
        if diff -q "$java_response" "$kotlin_response" > /dev/null 2>&1; then
            echo -e "  ${GREEN}✅ PASS${NC} - Identical responses (HTTP $java_code)" | tee -a "$TEST_LOG"
            PASSED_TESTS=$((PASSED_TESTS + 1))
        else
            echo -e "  ${YELLOW}⚠️  DIFF${NC} - Status codes match ($java_code) but response bodies differ" | tee -a "$TEST_LOG"
            echo "    Java response length: $(wc -c < "$java_response") bytes" | tee -a "$TEST_LOG"
            echo "    Kotlin response length: $(wc -c < "$kotlin_response") bytes" | tee -a "$TEST_LOG"
            FAILED_TESTS=$((FAILED_TESTS + 1))
        fi
    else
        echo -e "  ${RED}❌ FAIL${NC} - Different status codes (Java: $java_code, Kotlin: $kotlin_code)" | tee -a "$TEST_LOG"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
    
    # Cleanup temp files
    rm -f "$java_response" "$kotlin_response"
}

# Function to get auth token (simplified - in real implementation would use proper login)
get_auth_token() {
    # This is a placeholder - real implementation would login and extract JWT token
    # For now, assuming session-based auth or no auth required for testing
    echo ""
}

# Check if both backends are running
echo "🔍 Checking backend availability..." | tee -a "$TEST_LOG"
if ! check_backend "Java" "$JAVA_BASE"; then
    echo -e "${RED}Cannot proceed - Java backend not available${NC}" | tee -a "$TEST_LOG"
    exit 1
fi

if ! check_backend "Kotlin" "$KOTLIN_BASE/health"; then
    echo -e "${RED}Cannot proceed - Kotlin backend not available${NC}" | tee -a "$TEST_LOG"
    exit 1
fi

echo "" | tee -a "$TEST_LOG"
echo "🧪 Running API compatibility tests..." | tee -a "$TEST_LOG"
echo "" | tee -a "$TEST_LOG"

# Test migrated endpoints
AUTH_HEADER=$(get_auth_token)

# Health endpoint (Kotlin only, skip comparison)
echo "Testing health endpoint (Kotlin only):" | tee -a "$TEST_LOG"
if curl -s "$KOTLIN_BASE/health" > /dev/null; then
    echo -e "  ${GREEN}✅ Kotlin health endpoint responding${NC}" | tee -a "$TEST_LOG"
else
    echo -e "  ${RED}❌ Kotlin health endpoint not responding${NC}" | tee -a "$TEST_LOG"
fi
echo "" | tee -a "$TEST_LOG"

# Authentication endpoints
echo "Testing Authentication APIs:" | tee -a "$TEST_LOG"
# Note: Login requires valid credentials, so we test the endpoint structure only
test_endpoint "/login" "POST" "" '{"username":"test","password":"test"}'
test_endpoint "/status" "GET"
echo "" | tee -a "$TEST_LOG"

# User Management APIs
echo "Testing User Management APIs:" | tee -a "$TEST_LOG"
test_endpoint "/api/users" "GET" "$AUTH_HEADER"
# Note: Create/update/delete tests would need proper auth and test data
echo "" | tee -a "$TEST_LOG"

# Requirements APIs
echo "Testing Requirements APIs:" | tee -a "$TEST_LOG"
test_endpoint "/requirements" "GET" "$AUTH_HEADER"
test_endpoint "/requirements/export/docx" "GET" "$AUTH_HEADER"
test_endpoint "/requirements/export/excel" "GET" "$AUTH_HEADER"
echo "" | tee -a "$TEST_LOG"

# Standards APIs
echo "Testing Standards APIs:" | tee -a "$TEST_LOG"
test_endpoint "/api/standards" "GET" "$AUTH_HEADER"
echo "" | tee -a "$TEST_LOG"

# Risk Assessment APIs
echo "Testing Risk Assessment APIs:" | tee -a "$TEST_LOG"
test_endpoint "/api/risk-assessments" "GET" "$AUTH_HEADER"
echo "" | tee -a "$TEST_LOG"

# Risk APIs
echo "Testing Risk APIs:" | tee -a "$TEST_LOG"
test_endpoint "/api/risks" "GET" "$AUTH_HEADER"
echo "" | tee -a "$TEST_LOG"

# Performance comparison test
echo "🚀 Performance Comparison Test:" | tee -a "$TEST_LOG"
echo "Testing response times for /requirements endpoint..." | tee -a "$TEST_LOG"

# Test Java backend performance
java_time=$(curl -o /dev/null -s -w %{time_total} "$JAVA_BASE/requirements" 2>/dev/null || echo "0")
kotlin_time=$(curl -o /dev/null -s -w %{time_total} "$KOTLIN_BASE/requirements" 2>/dev/null || echo "0")

echo "  Java backend: ${java_time}s" | tee -a "$TEST_LOG"
echo "  Kotlin backend: ${kotlin_time}s" | tee -a "$TEST_LOG"

if [ "$(echo "$kotlin_time < $java_time" | bc -l 2>/dev/null)" = "1" ]; then
    improvement=$(echo "scale=1; ($java_time - $kotlin_time) / $java_time * 100" | bc -l 2>/dev/null || echo "0")
    echo -e "  ${GREEN}🚀 Kotlin is ${improvement}% faster${NC}" | tee -a "$TEST_LOG"
else
    slowdown=$(echo "scale=1; ($kotlin_time - $java_time) / $java_time * 100" | bc -l 2>/dev/null || echo "0")
    echo -e "  ${YELLOW}⚠️  Kotlin is ${slowdown}% slower${NC}" | tee -a "$TEST_LOG"
fi

echo "" | tee -a "$TEST_LOG"
echo "=================================================" | tee -a "$TEST_LOG"
echo "🏁 Test Summary:" | tee -a "$TEST_LOG"
echo "  Total Tests: $TOTAL_TESTS" | tee -a "$TEST_LOG"
echo -e "  ${GREEN}Passed: $PASSED_TESTS${NC}" | tee -a "$TEST_LOG"
echo -e "  ${RED}Failed: $FAILED_TESTS${NC}" | tee -a "$TEST_LOG"

if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "  ${GREEN}🎉 All tests passed! APIs are compatible.${NC}" | tee -a "$TEST_LOG"
    exit 0
else
    success_rate=$(echo "scale=1; $PASSED_TESTS * 100 / $TOTAL_TESTS" | bc -l)
    echo -e "  ${YELLOW}⚠️  Success rate: ${success_rate}%${NC}" | tee -a "$TEST_LOG"
    echo -e "  ${RED}❌ Some tests failed. Check compatibility issues.${NC}" | tee -a "$TEST_LOG"
    exit 1
fi