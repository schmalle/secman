#!/bin/bash
#
# Test Backend Connection and Vulnerability Endpoint
#

echo "========================================="
echo "Testing Backend Connection"
echo "========================================="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Test 1: Check if backend is running
echo -e "${YELLOW}Test 1:${NC} Checking if backend is running..."
HEALTH_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health 2>/dev/null || echo "000")

if [ "$HEALTH_RESPONSE" = "200" ] || [ "$HEALTH_RESPONSE" = "404" ] || [ "$HEALTH_RESPONSE" = "401" ]; then
    echo -e "${GREEN}✓ PASS${NC}: Backend is running on port 8080"
else
    echo -e "${RED}✗ FAIL${NC}: Backend is NOT running (response code: $HEALTH_RESPONSE)"
    echo ""
    echo "Please start the backend:"
    echo "  cd /Users/flake/sources/misc/secman/src/backendng"
    echo "  ./gradlew run"
    exit 1
fi

# Test 2: Check if import endpoint exists (will return 401 without auth)
echo -e "${YELLOW}Test 2:${NC} Checking if import endpoint exists..."
IMPORT_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/import/upload-vulnerability-xlsx 2>/dev/null || echo "000")

if [ "$IMPORT_RESPONSE" = "401" ]; then
    echo -e "${GREEN}✓ PASS${NC}: Import endpoint exists (returns 401 as expected without auth)"
elif [ "$IMPORT_RESPONSE" = "400" ] || [ "$IMPORT_RESPONSE" = "405" ]; then
    echo -e "${GREEN}✓ PASS${NC}: Import endpoint exists (returns $IMPORT_RESPONSE)"
else
    echo -e "${RED}✗ FAIL${NC}: Import endpoint may not exist (response code: $IMPORT_RESPONSE)"
fi

# Test 3: Check if frontend is running
echo -e "${YELLOW}Test 3:${NC} Checking if frontend is running..."
FRONTEND_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:4321/ 2>/dev/null || echo "000")

if [ "$FRONTEND_RESPONSE" = "200" ]; then
    echo -e "${GREEN}✓ PASS${NC}: Frontend is running on port 4321"
else
    echo -e "${RED}✗ FAIL${NC}: Frontend is NOT running (response code: $FRONTEND_RESPONSE)"
    echo ""
    echo "Please start the frontend:"
    echo "  cd /Users/flake/sources/misc/secman/src/frontend"
    echo "  npm run dev"
    exit 1
fi

echo ""
echo "========================================="
echo -e "${GREEN}All Connection Tests Passed!${NC}"
echo "========================================="
echo ""
echo "Next steps:"
echo "1. Open http://localhost:4321/import in your browser"
echo "2. Open browser DevTools (F12) and go to Console tab"
echo "3. Click the 'Vulnerabilities' tab"
echo "4. Select the test file and click 'Import Vulnerabilities'"
echo "5. Check the console for [VulnerabilityImport] and [VulnerabilityService] logs"
echo ""
echo "The logs will show:"
echo "  - If the button click is registered"
echo "  - If the file and scan date are present"
echo "  - If the API call is made"
echo "  - The response from the server"
