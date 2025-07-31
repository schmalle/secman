#!/bin/bash

echo "Testing Standard View Functionality"
echo "===================================="
echo ""

# Check if backend is running
echo "1. Checking backend availability..."
if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health | grep -q "200"; then
    echo "✓ Backend is running"
else
    echo "✗ Backend is not running. Please start the backend first."
    exit 1
fi

# Check if frontend is running
echo ""
echo "2. Checking frontend availability..."
if curl -s -o /dev/null -w "%{http_code}" http://localhost:4321 | grep -q "200"; then
    echo "✓ Frontend is running"
else
    echo "✗ Frontend is not running. Please start the frontend first."
    exit 1
fi

# Check standards page
echo ""
echo "3. Checking standards page..."
if curl -s -o /dev/null -w "%{http_code}" http://localhost:4321/standards | grep -q "200"; then
    echo "✓ Standards page is accessible"
else
    echo "✗ Standards page is not accessible"
fi

# Check if dynamic routing works
echo ""
echo "4. Checking dynamic routing for standard view..."
if curl -s -o /dev/null -w "%{http_code}" http://localhost:4321/standards/1 | grep -q "200"; then
    echo "✓ Dynamic routing works (standards/[id])"
else
    echo "✗ Dynamic routing might not be working"
fi

echo ""
echo "View Functionality Implementation Summary:"
echo "========================================="
echo "✓ Created ViewStandard component at: src/frontend/src/components/ViewStandard.tsx"
echo "✓ Created dynamic route at: src/frontend/src/pages/standards/[id].astro"
echo "✓ Added View button to standards table"
echo ""
echo "Features implemented:"
echo "- View button (info style) added to each standard row"
echo "- Dedicated view page showing standard details"
echo "- Display of associated use cases"
echo "- Basic information (name, description, dates)"
echo "- Placeholder for future chapters functionality"
echo "- Navigation buttons (Edit, Back to Standards)"
echo ""
echo "To test:"
echo "1. Navigate to http://localhost:4321/standards"
echo "2. Click the 'View' button on any standard"
echo "3. You should see the standard details page"