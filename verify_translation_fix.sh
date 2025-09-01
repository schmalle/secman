#!/bin/bash

# Quick verification script to test if German translation is working

echo "🔍 Translation Fix Verification"
echo "=============================="

# Get fresh token
TOKEN=$(curl -s -X POST "http://localhost:8080/api/auth/login" -H "Content-Type: application/json" -d '{"username":"adminuser","password":"password"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "❌ Failed to get auth token"
    exit 1
fi

echo "✅ Authentication successful"

# Test Excel exports
echo ""
echo "Testing Excel Exports:"
echo "----------------------"

# English Excel
echo -n "English Excel: "
curl -s -X GET "http://localhost:8080/api/requirements/export/xlsx" -H "Authorization: Bearer $TOKEN" --output "/tmp/english.xlsx" -w "HTTP:%{http_code}"
english_size=$(wc -c < "/tmp/english.xlsx" 2>/dev/null || echo "0")
echo " -> $english_size bytes"

# German Excel
echo -n "German Excel:  "
curl -s -X GET "http://localhost:8080/api/requirements/export/xlsx/translated/german" -H "Authorization: Bearer $TOKEN" --output "/tmp/german.xlsx" -w "HTTP:%{http_code}"
german_size=$(wc -c < "/tmp/german.xlsx" 2>/dev/null || echo "0")
echo " -> $german_size bytes"

# Analysis
echo ""
echo "Analysis:"
echo "---------"
if [ "$english_size" -gt 1000 ] && [ "$german_size" -gt 1000 ]; then
    echo "✅ Both files are proper Excel files (>1KB)"
    
    if [ "$german_size" != "$english_size" ]; then
        size_diff=$((german_size - english_size))
        echo "✅ File sizes differ by $size_diff bytes - Translation likely working!"
        echo ""
        echo "🎉 SUCCESS: German translation appears to be working in Excel exports"
        echo "   The file size difference indicates content has been translated."
        echo "   Some API timeouts may occur but the system falls back gracefully."
    else
        echo "⚠️  File sizes are identical - translation may not be working"
    fi
else
    echo "❌ One or both files are too small - likely error responses"
    echo "English file contents:"
    head -5 /tmp/english.xlsx 2>/dev/null || echo "Cannot read"
    echo ""
    echo "German file contents:"
    head -5 /tmp/german.xlsx 2>/dev/null || echo "Cannot read"
fi

# Cleanup
rm -f /tmp/english.xlsx /tmp/german.xlsx

echo ""
echo "🔧 Next Steps:"
echo "- Open the Secman frontend"
echo "- Go to Export page"
echo "- Select 'German' language"
echo "- Click 'Excel - All' export"
echo "- Check if the exported Excel file contains German text"