#!/bin/bash

# OAuth Flow Validation Script
# This script tests the basic OAuth endpoints to ensure they're properly configured

echo "=== OAuth Flow Validation ==="
echo "Testing OAuth endpoints for proper configuration..."

BASE_URL="http://localhost:8080"

# Test 1: Test OAuth authorization endpoint
echo -e "\n1. Testing OAuth authorization endpoint..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/oauth/authorize/1")
if [ "$RESPONSE" -eq 302 ] || [ "$RESPONSE" -eq 200 ]; then
    echo "✅ OAuth authorization endpoint responds correctly (HTTP $RESPONSE)"
else
    echo "❌ OAuth authorization endpoint failed (HTTP $RESPONSE)"
fi

# Test 2: Test OAuth callback endpoint exists
echo -e "\n2. Testing OAuth callback endpoint..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/oauth/callback")
if [ "$RESPONSE" -eq 400 ] || [ "$RESPONSE" -eq 302 ]; then
    echo "✅ OAuth callback endpoint exists and validates parameters (HTTP $RESPONSE)"
else
    echo "❌ OAuth callback endpoint issue (HTTP $RESPONSE)"
fi

# Test 3: Test OAuth callback POST endpoint
echo -e "\n3. Testing OAuth callback POST endpoint..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/oauth/callback" \
    -H "Content-Type: application/json" \
    -d '{"code":"test","state":"test"}')
if [ "$RESPONSE" -eq 400 ]; then
    echo "✅ OAuth callback POST endpoint validates parameters correctly (HTTP $RESPONSE)"
else
    echo "❌ OAuth callback POST endpoint issue (HTTP $RESPONSE)"
fi

# Test 4: Check that sensitive endpoints are not accessible
echo -e "\n4. Testing endpoint security..."
RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/oauth/callback/1")
if [ "$RESPONSE" -eq 404 ]; then
    echo "✅ Old provider-specific callback endpoint is removed (HTTP $RESPONSE)"
else
    echo "⚠️  Old provider-specific callback endpoint still exists (HTTP $RESPONSE)"
fi

echo -e "\n=== Validation Complete ==="
echo "Notes:"
echo "- Ensure your application is running on $BASE_URL"
echo "- Test with actual OAuth provider configuration for complete validation"
echo "- Check application logs for detailed error information"