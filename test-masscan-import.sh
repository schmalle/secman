#!/bin/bash

# Test script for Masscan XML import feature
# Tests the end-to-end functionality of uploading a Masscan XML file

set -e

echo "=== Masscan Import E2E Test ==="
echo ""

# Configuration
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
TEST_USER="${TEST_USER:-user}"
TEST_PASS="${TEST_PASS:-user}"
TEST_FILE="testdata/masscan.xml"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if test file exists
if [ ! -f "$TEST_FILE" ]; then
    echo -e "${RED}ERROR: Test file not found: $TEST_FILE${NC}"
    exit 1
fi

echo -e "${YELLOW}1. Authenticating...${NC}"
# Authenticate and get JWT token
AUTH_RESPONSE=$(curl -s -X POST "$BACKEND_URL/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$TEST_USER\",\"password\":\"$TEST_PASS\"}")

TOKEN=$(echo "$AUTH_RESPONSE" | grep -o '"access_token":"[^"]*"' | sed 's/"access_token":"\([^"]*\)"/\1/')

if [ -z "$TOKEN" ]; then
    echo -e "${RED}ERROR: Failed to authenticate. Response: $AUTH_RESPONSE${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Authentication successful${NC}"
echo ""

echo -e "${YELLOW}2. Uploading Masscan XML file...${NC}"
# Upload the Masscan XML file
UPLOAD_RESPONSE=$(curl -s -X POST "$BACKEND_URL/api/import/upload-masscan-xml" \
    -H "Authorization: Bearer $TOKEN" \
    -F "xmlFile=@$TEST_FILE")

echo "Response: $UPLOAD_RESPONSE"
echo ""

# Check if upload was successful
if echo "$UPLOAD_RESPONSE" | grep -q '"assetsCreated"'; then
    ASSETS_CREATED=$(echo "$UPLOAD_RESPONSE" | grep -o '"assetsCreated":[0-9]*' | sed 's/"assetsCreated"://')
    ASSETS_UPDATED=$(echo "$UPLOAD_RESPONSE" | grep -o '"assetsUpdated":[0-9]*' | sed 's/"assetsUpdated"://')
    PORTS_IMPORTED=$(echo "$UPLOAD_RESPONSE" | grep -o '"portsImported":[0-9]*' | sed 's/"portsImported"://')

    echo -e "${GREEN}✓ Import successful!${NC}"
    echo "  - Assets created: $ASSETS_CREATED"
    echo "  - Assets updated: $ASSETS_UPDATED"
    echo "  - Ports imported: $PORTS_IMPORTED"
    echo ""

    echo -e "${YELLOW}3. Verifying asset was created...${NC}"
    # Get assets list
    ASSETS_RESPONSE=$(curl -s -X GET "$BACKEND_URL/api/assets" \
        -H "Authorization: Bearer $TOKEN")

    # Check if the test IP is in the response
    if echo "$ASSETS_RESPONSE" | grep -q "193.99.144.85"; then
        echo -e "${GREEN}✓ Asset with IP 193.99.144.85 found in database${NC}"
        echo ""
        echo -e "${GREEN}=== ALL TESTS PASSED ===${NC}"
        exit 0
    else
        echo -e "${RED}ERROR: Asset not found in database${NC}"
        exit 1
    fi
else
    echo -e "${RED}ERROR: Upload failed or unexpected response format${NC}"
    echo "Response: $UPLOAD_RESPONSE"
    exit 1
fi
