#!/bin/bash

echo "Testing OAuth GitHub Login Fix"
echo "==============================="

# Check if backend is running
echo "1. Checking if backend is running on port 8080..."
if curl -s http://localhost:8080/health > /dev/null; then
    echo "✓ Backend is running on port 8080"
else
    echo "✗ Backend is not running on port 8080"
    echo "Please start the backend with: cd src/backendng && ./gradlew run"
    exit 1
fi

# Check if frontend is running
echo "2. Checking if frontend is running on port 4321..."
if curl -s http://localhost:4321 > /dev/null; then
    echo "✓ Frontend is running on port 4321"
else
    echo "✗ Frontend is not running on port 4321"
    echo "Please start the frontend with: cd src/frontend && npm run dev"
    exit 1
fi

# Test OAuth authorization endpoint
echo "3. Testing OAuth authorization endpoint..."
response=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/oauth/authorize/1")
if [ "$response" = "302" ] || [ "$response" = "400" ]; then
    echo "✓ OAuth authorization endpoint responds correctly (HTTP $response)"
else
    echo "✗ OAuth authorization endpoint returned unexpected status: $response"
fi

# Test that /login/success endpoint no longer exists on backend
echo "4. Testing that /login/success no longer returns Unauthorized on backend..."
response=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/login/success")
if [ "$response" = "404" ]; then
    echo "✓ Backend correctly returns 404 for /login/success (no longer tries to handle it)"
elif [ "$response" = "401" ]; then
    echo "✗ Backend still returns 401 Unauthorized for /login/success"
    echo "This suggests the fix may not be complete"
else
    echo "? Backend returns $response for /login/success"
fi

# Test that frontend login/success page exists
echo "5. Testing that frontend login/success page exists..."
response=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:4321/login/success")
if [ "$response" = "200" ]; then
    echo "✓ Frontend login/success page is accessible"
else
    echo "✗ Frontend login/success page returned: $response"
fi

echo ""
echo "OAuth Fix Test Summary:"
echo "======================="
echo "The OAuth controller has been updated to redirect to frontend URLs:"
echo "- Success: http://localhost:4321/login/success"
echo "- Errors: http://localhost:4321/login"
echo ""
echo "This should resolve the 'Unauthorized' error users were seeing on port 8080."
echo "To fully test, try the GitHub OAuth login flow in a browser."