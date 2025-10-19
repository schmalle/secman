#!/bin/bash

echo "=== Diagnosing Requirements Issue ==="
echo ""

echo "1. Checking database connection..."
mysql -u secman -pCHANGEME -h localhost secman -e "SELECT 1;" 2>&1 | grep -q "ERROR"
if [ $? -eq 0 ]; then
    echo "   ❌ Database connection failed"
else
    echo "   ✅ Database connection OK"
fi

echo ""
echo "2. Checking requirements table..."
REQ_COUNT=$(mysql -u secman -pCHANGEME -h localhost secman -e "SELECT COUNT(*) FROM requirement;" 2>/dev/null | tail -n 1)
echo "   Found $REQ_COUNT requirements in database"

echo ""
echo "3. Checking adminuser roles..."
mysql -u secman -pCHANGEME -h localhost secman -e "
SELECT u.username, GROUP_CONCAT(ur.role_name) as roles
FROM user u
LEFT JOIN user_roles ur ON u.id = ur.user_id
WHERE u.username = 'adminuser'
GROUP BY u.username;
" 2>/dev/null

echo ""
echo "4. Sample requirements (first 5)..."
mysql -u secman -pCHANGEME -h localhost secman -e "
SELECT id, shortreq, chapter
FROM requirement
LIMIT 5;
" 2>/dev/null

echo ""
echo "5. Checking if backend is running..."
curl -s http://localhost:8080/health > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "   ✅ Backend is running"
else
    echo "   ❌ Backend is not running"
fi

echo ""
echo "=== Diagnosis Complete ==="
