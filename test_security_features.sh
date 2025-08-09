#!/bin/bash

# Security Feature Test Script for Secman
# Tests the implemented security features

echo "================================"
echo "Security Feature Test Suite"
echo "================================"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Base URL
BASE_URL="http://localhost:8080"
FRONTEND_URL="http://localhost:4321"

# Test credentials
TEST_USER="normaluser"
TEST_PASS="password"
ADMIN_USER="adminuser"
ADMIN_PASS="password"

# Function to print test results
print_result() {
    if [ $1 -eq 0 ]; then
        echo -e "${GREEN}✓${NC} $2"
    else
        echo -e "${RED}✗${NC} $2"
    fi
}

# Function to get auth token
get_auth_token() {
    local username=$1
    local password=$2
    
    response=$(curl -s -X POST "$BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$username\",\"password\":\"$password\"}")
    
    echo "$response" | grep -o '"token":"[^"]*' | cut -d'"' -f4
}

echo ""
echo "1. Testing Security Headers"
echo "----------------------------"

# Test for security headers
response=$(curl -s -I "$BASE_URL/health")

# Check X-Frame-Options
echo "$response" | grep -q "X-Frame-Options: DENY"
print_result $? "X-Frame-Options header present"

# Check X-Content-Type-Options
echo "$response" | grep -q "X-Content-Type-Options: nosniff"
print_result $? "X-Content-Type-Options header present"

# Check X-XSS-Protection
echo "$response" | grep -q "X-XSS-Protection: 1; mode=block"
print_result $? "X-XSS-Protection header present"

# Check Content-Security-Policy
echo "$response" | grep -q "Content-Security-Policy:"
print_result $? "Content-Security-Policy header present"

# Check Referrer-Policy
echo "$response" | grep -q "Referrer-Policy:"
print_result $? "Referrer-Policy header present"

echo ""
echo "2. Testing Input Validation"
echo "----------------------------"

# Test SQL injection attempt in login
response=$(curl -s -X POST "$BASE_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin\" OR \"1\"=\"1","password":"password"}' \
    -w "\n%{http_code}")

http_code=$(echo "$response" | tail -n1)
if [ "$http_code" = "400" ] || [ "$http_code" = "401" ]; then
    print_result 0 "SQL injection in login blocked"
else
    print_result 1 "SQL injection in login NOT blocked (HTTP $http_code)"
fi

# Test XSS attempt in login
response=$(curl -s -X POST "$BASE_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"<script>alert(1)</script>","password":"password"}' \
    -w "\n%{http_code}")

http_code=$(echo "$response" | tail -n1)
if [ "$http_code" = "400" ] || [ "$http_code" = "401" ]; then
    print_result 0 "XSS attempt in login blocked"
else
    print_result 1 "XSS attempt in login NOT blocked (HTTP $http_code)"
fi

echo ""
echo "3. Testing File Upload Security"
echo "----------------------------"

# Get auth token
TOKEN=$(get_auth_token "$TEST_USER" "$TEST_PASS")

if [ -z "$TOKEN" ]; then
    echo -e "${RED}Failed to get auth token. Skipping file upload tests.${NC}"
else
    # Create a test HTML file (dangerous)
    echo "<script>alert('XSS')</script>" > test_xss.html
    
    # Try to upload HTML file (should be blocked)
    response=$(curl -s -X POST "$BASE_URL/api/risk-assessments/1/requirements/1/files" \
        -H "Authorization: Bearer $TOKEN" \
        -F "file=@test_xss.html" \
        -w "\n%{http_code}")
    
    http_code=$(echo "$response" | tail -n1)
    if [ "$http_code" = "400" ] || [ "$http_code" = "403" ]; then
        print_result 0 "HTML file upload blocked"
    else
        print_result 1 "HTML file upload NOT blocked (HTTP $http_code)"
    fi
    
    # Clean up
    rm -f test_xss.html
    
    # Create a test executable file
    echo "#!/bin/bash" > test_script.sh
    
    # Try to upload shell script (should be blocked)
    response=$(curl -s -X POST "$BASE_URL/api/risk-assessments/1/requirements/1/files" \
        -H "Authorization: Bearer $TOKEN" \
        -F "file=@test_script.sh" \
        -w "\n%{http_code}")
    
    http_code=$(echo "$response" | tail -n1)
    if [ "$http_code" = "400" ] || [ "$http_code" = "403" ]; then
        print_result 0 "Shell script upload blocked"
    else
        print_result 1 "Shell script upload NOT blocked (HTTP $http_code)"
    fi
    
    # Clean up
    rm -f test_script.sh
    
    # Test path traversal in filename
    echo "test content" > "test.pdf"
    
    response=$(curl -s -X POST "$BASE_URL/api/risk-assessments/1/requirements/1/files" \
        -H "Authorization: Bearer $TOKEN" \
        -F "file=@test.pdf;filename=../../etc/passwd" \
        -w "\n%{http_code}")
    
    http_code=$(echo "$response" | tail -n1)
    if [ "$http_code" = "400" ] || [ "$http_code" = "403" ]; then
        print_result 0 "Path traversal in filename blocked"
    else
        print_result 1 "Path traversal in filename NOT blocked (HTTP $http_code)"
    fi
    
    # Clean up
    rm -f test.pdf
fi

echo ""
echo "4. Testing Authorization Checks"
echo "--------------------------------"

# Get normal user token
USER_TOKEN=$(get_auth_token "$TEST_USER" "$TEST_PASS")

if [ -z "$USER_TOKEN" ]; then
    echo -e "${RED}Failed to get user token. Skipping authorization tests.${NC}"
else
    # Try to access a file without authorization (assuming file ID 999999 doesn't exist or isn't accessible)
    response=$(curl -s -X GET "$BASE_URL/api/files/999999/download" \
        -H "Authorization: Bearer $USER_TOKEN" \
        -w "\n%{http_code}")
    
    http_code=$(echo "$response" | tail -n1)
    if [ "$http_code" = "403" ] || [ "$http_code" = "404" ]; then
        print_result 0 "Unauthorized file access blocked"
    else
        print_result 1 "Unauthorized file access NOT blocked (HTTP $http_code)"
    fi
    
    # Try to delete a file without permission
    response=$(curl -s -X DELETE "$BASE_URL/api/files/999999" \
        -H "Authorization: Bearer $USER_TOKEN" \
        -w "\n%{http_code}")
    
    http_code=$(echo "$response" | tail -n1)
    if [ "$http_code" = "403" ] || [ "$http_code" = "404" ] || [ "$http_code" = "400" ]; then
        print_result 0 "Unauthorized file deletion blocked"
    else
        print_result 1 "Unauthorized file deletion NOT blocked (HTTP $http_code)"
    fi
fi

echo ""
echo "5. Testing ID Validation"
echo "------------------------"

if [ -n "$USER_TOKEN" ]; then
    # Test negative ID
    response=$(curl -s -X GET "$BASE_URL/api/files/-1/download" \
        -H "Authorization: Bearer $USER_TOKEN" \
        -w "\n%{http_code}")
    
    http_code=$(echo "$response" | tail -n1)
    if [ "$http_code" = "400" ] || [ "$http_code" = "404" ]; then
        print_result 0 "Negative ID validation working"
    else
        print_result 1 "Negative ID validation NOT working (HTTP $http_code)"
    fi
    
    # Test extremely large ID
    response=$(curl -s -X GET "$BASE_URL/api/files/99999999999999999999/download" \
        -H "Authorization: Bearer $USER_TOKEN" \
        -w "\n%{http_code}")
    
    http_code=$(echo "$response" | tail -n1)
    if [ "$http_code" = "400" ] || [ "$http_code" = "404" ]; then
        print_result 0 "Large ID validation working"
    else
        print_result 1 "Large ID validation NOT working (HTTP $http_code)"
    fi
fi

echo ""
echo "6. Testing CORS Configuration"
echo "-----------------------------"

# Test CORS from unauthorized origin
response=$(curl -s -I -X OPTIONS "$BASE_URL/api/auth/login" \
    -H "Origin: http://evil.com" \
    -H "Access-Control-Request-Method: POST")

# Check if evil origin is allowed (it shouldn't be)
echo "$response" | grep -q "Access-Control-Allow-Origin: http://evil.com"
if [ $? -eq 0 ]; then
    print_result 1 "CORS allows unauthorized origin"
else
    print_result 0 "CORS blocks unauthorized origin"
fi

echo ""
echo "================================"
echo "Security Test Suite Complete"
echo "================================"