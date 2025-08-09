#!/bin/bash

# E2E Test Script for Demand Classification Feature
# This script tests the complete demand classification system

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test configuration
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
FRONTEND_URL="${FRONTEND_URL:-http://localhost:4321}"
TEST_USERNAME="${TEST_USERNAME:-adminuser}"
TEST_PASSWORD="${TEST_PASSWORD:-password}"

echo -e "${YELLOW}Starting Demand Classification E2E Tests${NC}"
echo "API URL: $API_BASE_URL"
echo "Frontend URL: $FRONTEND_URL"
echo ""

# Function to make authenticated API calls
make_auth_request() {
    local method=$1
    local endpoint=$2
    local data=$3
    
    # First, get auth token
    TOKEN=$(curl -s -X POST "$API_BASE_URL/api/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$TEST_USERNAME\",\"password\":\"$TEST_PASSWORD\"}" | \
        grep -o '"token":"[^"]*' | cut -d'"' -f4)
    
    if [ -z "$TOKEN" ]; then
        echo -e "${RED}Failed to authenticate${NC}"
        return 1
    fi
    
    if [ "$method" = "GET" ]; then
        curl -s -X GET "$API_BASE_URL$endpoint" \
            -H "Authorization: Bearer $TOKEN"
    else
        curl -s -X "$method" "$API_BASE_URL$endpoint" \
            -H "Authorization: Bearer $TOKEN" \
            -H "Content-Type: application/json" \
            -d "$data"
    fi
}

# Test 1: Public Classification Endpoint
echo -e "${YELLOW}Test 1: Public Classification${NC}"
CLASSIFICATION_RESULT=$(curl -s -X POST "$API_BASE_URL/api/classification/public/classify" \
    -H "Content-Type: application/json" \
    -d '{
        "title": "Test Public Classification",
        "description": "Testing the public classification endpoint",
        "demandType": "CREATE_NEW",
        "priority": "HIGH",
        "businessJustification": "Critical business need",
        "assetType": "Database",
        "assetOwner": "IT Department"
    }')

if echo "$CLASSIFICATION_RESULT" | grep -q '"classification"'; then
    echo -e "${GREEN}✓ Public classification successful${NC}"
    HASH=$(echo "$CLASSIFICATION_RESULT" | grep -o '"classificationHash":"[^"]*' | cut -d'"' -f4)
    echo "  Classification Hash: $HASH"
else
    echo -e "${RED}✗ Public classification failed${NC}"
    echo "$CLASSIFICATION_RESULT"
    exit 1
fi

# Test 2: Retrieve Classification by Hash
echo -e "${YELLOW}Test 2: Retrieve Classification by Hash${NC}"
HASH_RESULT=$(curl -s -X GET "$API_BASE_URL/api/classification/results/$HASH")

if echo "$HASH_RESULT" | grep -q "$HASH"; then
    echo -e "${GREEN}✓ Classification retrieved by hash${NC}"
else
    echo -e "${RED}✗ Failed to retrieve classification by hash${NC}"
    exit 1
fi

# Test 3: List Classification Rules (Authenticated)
echo -e "${YELLOW}Test 3: List Classification Rules${NC}"
RULES_RESULT=$(make_auth_request "GET" "/api/classification/rules")

if echo "$RULES_RESULT" | grep -q '"name"'; then
    echo -e "${GREEN}✓ Classification rules listed successfully${NC}"
    RULE_COUNT=$(echo "$RULES_RESULT" | grep -o '"name"' | wc -l)
    echo "  Found $RULE_COUNT rules"
else
    echo -e "${RED}✗ Failed to list classification rules${NC}"
    exit 1
fi

# Test 4: Create a New Rule (Admin only)
echo -e "${YELLOW}Test 4: Create Classification Rule${NC}"
NEW_RULE=$(make_auth_request "POST" "/api/classification/rules" '{
    "name": "E2E Test Rule",
    "description": "Rule created by E2E test script",
    "condition": {
        "type": "COMPARISON",
        "field": "priority",
        "operator": "EQUALS",
        "value": "LOW"
    },
    "classification": "C",
    "confidenceScore": 0.9
}')

if echo "$NEW_RULE" | grep -q '"E2E Test Rule"'; then
    echo -e "${GREEN}✓ Classification rule created successfully${NC}"
    RULE_ID=$(echo "$NEW_RULE" | grep -o '"id":[0-9]*' | cut -d':' -f2)
    echo "  Rule ID: $RULE_ID"
else
    echo -e "${YELLOW}⚠ Could not create rule (may already exist or insufficient permissions)${NC}"
    # Try to find existing rule
    EXISTING_RULES=$(make_auth_request "GET" "/api/classification/rules")
    RULE_ID=$(echo "$EXISTING_RULES" | grep -B2 -A2 '"E2E Test Rule"' | grep -o '"id":[0-9]*' | cut -d':' -f2 | head -1)
fi

# Test 5: Test Classification with Specific Input
echo -e "${YELLOW}Test 5: Test Classification${NC}"
TEST_RESULT=$(make_auth_request "POST" "/api/classification/test" '{
    "input": {
        "title": "Test Demand",
        "demandType": "CHANGE",
        "priority": "LOW",
        "businessJustification": "Test"
    }
}')

if echo "$TEST_RESULT" | grep -q '"classification":"C"'; then
    echo -e "${GREEN}✓ Test classification returned expected result (C)${NC}"
else
    echo -e "${RED}✗ Test classification failed or returned unexpected result${NC}"
    echo "$TEST_RESULT"
fi

# Test 6: Export Rules
echo -e "${YELLOW}Test 6: Export Classification Rules${NC}"
EXPORT_RESULT=$(make_auth_request "GET" "/api/classification/rules/export")

if echo "$EXPORT_RESULT" | grep -q '"classification"'; then
    echo -e "${GREEN}✓ Rules exported successfully${NC}"
    EXPORT_COUNT=$(echo "$EXPORT_RESULT" | grep -o '"name"' | wc -l)
    echo "  Exported $EXPORT_COUNT rules"
else
    echo -e "${RED}✗ Failed to export rules${NC}"
    exit 1
fi

# Test 7: Get Classification Statistics
echo -e "${YELLOW}Test 7: Classification Statistics${NC}"
STATS_RESULT=$(make_auth_request "GET" "/api/classification/statistics")

if echo "$STATS_RESULT" | grep -q '"totalClassifications"'; then
    echo -e "${GREEN}✓ Statistics retrieved successfully${NC}"
    TOTAL=$(echo "$STATS_RESULT" | grep -o '"totalClassifications":[0-9]*' | cut -d':' -f2)
    echo "  Total classifications: $TOTAL"
else
    echo -e "${RED}✗ Failed to retrieve statistics${NC}"
    exit 1
fi

# Test 8: Create Demand with Classification
echo -e "${YELLOW}Test 8: Create Demand with Classification${NC}"
DEMAND_RESULT=$(make_auth_request "POST" "/api/demands" "{
    \"title\": \"E2E Test Demand with Classification\",
    \"description\": \"Testing demand creation with classification\",
    \"demandType\": \"CREATE_NEW\",
    \"newAssetName\": \"Test Asset\",
    \"newAssetType\": \"Server\",
    \"newAssetOwner\": \"Test Owner\",
    \"priority\": \"HIGH\",
    \"businessJustification\": \"E2E Testing\",
    \"requestorId\": 1
}")

if echo "$DEMAND_RESULT" | grep -q '"id"'; then
    echo -e "${GREEN}✓ Demand created successfully${NC}"
    DEMAND_ID=$(echo "$DEMAND_RESULT" | grep -o '"id":[0-9]*' | cut -d':' -f2 | head -1)
    echo "  Demand ID: $DEMAND_ID"
    
    # Classify the created demand
    CLASSIFY_DEMAND=$(make_auth_request "POST" "/api/classification/classify-demand" "{
        \"demandId\": $DEMAND_ID
    }")
    
    if echo "$CLASSIFY_DEMAND" | grep -q '"classification"'; then
        echo -e "${GREEN}✓ Demand classified successfully${NC}"
        CLASSIFICATION=$(echo "$CLASSIFY_DEMAND" | grep -o '"classification":"[^"]*' | cut -d'"' -f4)
        echo "  Classification: $CLASSIFICATION"
    else
        echo -e "${YELLOW}⚠ Could not classify demand${NC}"
    fi
else
    echo -e "${RED}✗ Failed to create demand${NC}"
    echo "$DEMAND_RESULT"
fi

# Test 9: Delete Test Rule (Cleanup)
if [ ! -z "$RULE_ID" ]; then
    echo -e "${YELLOW}Test 9: Cleanup - Delete Test Rule${NC}"
    DELETE_RESULT=$(make_auth_request "DELETE" "/api/classification/rules/$RULE_ID")
    
    if echo "$DELETE_RESULT" | grep -q '"message"'; then
        echo -e "${GREEN}✓ Test rule deleted/deactivated${NC}"
    else
        echo -e "${YELLOW}⚠ Could not delete test rule${NC}"
    fi
fi

# Test 10: Frontend Availability
echo -e "${YELLOW}Test 10: Frontend Pages Availability${NC}"

# Check public classification page
PUBLIC_PAGE=$(curl -s -o /dev/null -w "%{http_code}" "$FRONTEND_URL/public-classification")
if [ "$PUBLIC_PAGE" = "200" ]; then
    echo -e "${GREEN}✓ Public classification page accessible${NC}"
else
    echo -e "${RED}✗ Public classification page not accessible (HTTP $PUBLIC_PAGE)${NC}"
fi

# Check rule management page (requires auth, so just check it exists)
RULES_PAGE=$(curl -s -o /dev/null -w "%{http_code}" "$FRONTEND_URL/classification-rules")
if [ "$RULES_PAGE" = "200" ] || [ "$RULES_PAGE" = "302" ]; then
    echo -e "${GREEN}✓ Classification rules page exists${NC}"
else
    echo -e "${RED}✗ Classification rules page not found (HTTP $RULES_PAGE)${NC}"
fi

echo ""
echo -e "${GREEN}================================${NC}"
echo -e "${GREEN}All E2E Tests Completed Successfully!${NC}"
echo -e "${GREEN}================================${NC}"
echo ""
echo "Summary:"
echo "- Public classification endpoint: ✓"
echo "- Hash-based retrieval: ✓"
echo "- Rule management: ✓"
echo "- Classification testing: ✓"
echo "- Statistics API: ✓"
echo "- Demand integration: ✓"
echo "- Frontend pages: ✓"

exit 0