#!/bin/bash

# Test script to verify nmap upload fix
set -e

echo "=== Testing Nmap Upload Fix ==="

# Step 1: Login and get token
echo -e "\n1. Logging in as admin..."
LOGIN_RESPONSE=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"adminuser","password":"password"}')

echo "Login response: $LOGIN_RESPONSE"

TOKEN=$(echo $LOGIN_RESPONSE | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "ERROR: Failed to get auth token"
  exit 1
fi

echo "Got token: ${TOKEN:0:50}..."

# Step 2: Upload nmap scan (first time)
echo -e "\n2. Uploading nmap scan (first time)..."
UPLOAD1=$(curl -s -X POST http://localhost:8080/api/scan/upload-nmap \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/Users/flake/sources/misc/secman/src/frontend/tests/nmap-test.xml" \
  -w "\nHTTP_CODE:%{http_code}")

HTTP_CODE1=$(echo "$UPLOAD1" | grep -o "HTTP_CODE:[0-9]*" | cut -d: -f2)
RESPONSE1=$(echo "$UPLOAD1" | sed 's/HTTP_CODE:.*//')

echo "HTTP Status: $HTTP_CODE1"
echo "Response: $RESPONSE1"

if [ "$HTTP_CODE1" != "200" ]; then
  echo "ERROR: First upload failed with status $HTTP_CODE1"
  exit 1
fi

echo "✓ First upload successful"

# Step 3: Upload same scan again (test duplicate handling)
echo -e "\n3. Uploading same nmap scan again (test duplicate handling)..."
UPLOAD2=$(curl -s -X POST http://localhost:8080/api/scan/upload-nmap \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/Users/flake/sources/misc/secman/src/frontend/tests/nmap-test.xml" \
  -w "\nHTTP_CODE:%{http_code}")

HTTP_CODE2=$(echo "$UPLOAD2" | grep -o "HTTP_CODE:[0-9]*" | cut -d: -f2)
RESPONSE2=$(echo "$UPLOAD2" | sed 's/HTTP_CODE:.*//')

echo "HTTP Status: $HTTP_CODE2"
echo "Response: $RESPONSE2"

if [ "$HTTP_CODE2" != "200" ]; then
  echo "ERROR: Second upload failed with status $HTTP_CODE2"
  exit 1
fi

echo "✓ Second upload successful"

# Step 4: Verify no JSON serialization errors
echo -e "\n4. Verifying no JSON serialization errors..."
if echo "$RESPONSE1" | grep -q "error"; then
  echo "ERROR: First response contains error"
  exit 1
fi

if echo "$RESPONSE2" | grep -q "error"; then
  echo "ERROR: Second response contains error"
  exit 1
fi

echo "✓ No serialization errors"

echo -e "\n=== All Tests Passed! ==="
echo "The @JsonIgnore fix successfully prevents lazy loading errors"
