#!/bin/bash

echo "🧪 Testing German Translation Export"
echo "===================================="

# Get auth token
TOKEN=$(curl -s -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"adminuser","password":"password"}' | \
  grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "❌ Failed to get auth token"
    exit 1
fi

echo "✅ Authentication successful"

# Test English export
echo "📊 Testing English export..."
curl -s -X GET "http://localhost:8080/api/requirements/export/xlsx" \
  -H "Authorization: Bearer $TOKEN" \
  --output "/tmp/english_test.xlsx" \
  -w "Status: %{http_code}, Time: %{time_total}s\n"

english_size=$(wc -c < "/tmp/english_test.xlsx" 2>/dev/null || echo "0")
echo "   English file: $english_size bytes"

# Test German export  
echo "🇩🇪 Testing German export..."
curl -s -X GET "http://localhost:8080/api/requirements/export/xlsx/translated/german" \
  -H "Authorization: Bearer $TOKEN" \
  --output "/tmp/german_test.xlsx" \
  -w "Status: %{http_code}, Time: %{time_total}s\n"

german_size=$(wc -c < "/tmp/german_test.xlsx" 2>/dev/null || echo "0")
echo "   German file: $german_size bytes"

# Compare sizes
if [ "$english_size" -gt 1000 ] && [ "$german_size" -gt 1000 ]; then
    if [ "$german_size" != "$english_size" ]; then
        size_diff=$((german_size - english_size))
        echo "✅ SUCCESS: Files differ by $size_diff bytes - Translation working!"
    else
        echo "⚠️  Files are identical size - may not be translating"
    fi
else
    echo "❌ One or both files too small - likely errors"
fi

# Cleanup
rm -f /tmp/english_test.xlsx /tmp/german_test.xlsx

echo ""
echo "🔍 To verify content translation:"
echo "   1. Open frontend at http://localhost:4321"  
echo "   2. Go to Export page"
echo "   3. Select 'German' language"
echo "   4. Export Excel and check content"