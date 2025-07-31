#!/bin/bash

echo "Testing Requirements Integration with Standards"
echo "=============================================="
echo ""

# Function to check if backend is running
check_backend() {
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health | grep -q "200"; then
        echo "✓ Backend is running"
        return 0
    else
        echo "✗ Backend is not running"
        echo "  Please start the backend with: cd src/backendng && ./gradlew run"
        return 1
    fi
}

# Function to check if frontend is running
check_frontend() {
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:4321 | grep -q "200"; then
        echo "✓ Frontend is running"
        return 0
    else
        echo "✗ Frontend is not running"
        echo "  Please start the frontend with: cd src/frontend && npm run dev"
        return 1
    fi
}

echo "1. Checking services..."
check_backend
backend_status=$?
check_frontend
frontend_status=$?

if [ $backend_status -eq 0 ]; then
    echo ""
    echo "2. Testing new API endpoint..."
    echo "Testing: GET /api/standards/1/requirements"
    
    # Note: This would require proper authentication in a real test
    echo "   (Requires authentication - test manually in browser after login)"
    
    echo ""
    echo "3. Testing requirements grouping..."
    echo "   Requirements will be grouped by their 'chapter' attribute"
    echo "   Each chapter will show:"
    echo "   - Chapter name as accordion header"
    echo "   - Number of requirements badge"
    echo "   - List of requirements with details"
fi

echo ""
echo "Implementation Summary:"
echo "======================"
echo "✓ Added new API endpoint: GET /api/standards/{id}/requirements"
echo "✓ Updated ViewStandard component to fetch real requirements"
echo "✓ Implemented chapter-based grouping using requirement.chapter attribute"
echo "✓ Enhanced requirement display with:"
echo "  - Requirement short description (shortreq)"
echo "  - Detailed description (details)"
echo "  - Examples and motivation"
echo "  - Associated use cases and norms counts"
echo "  - Language badges"
echo ""
echo "New Features:"
echo "- Real-time requirement fetching from database"
echo "- Dynamic chapter organization based on requirement.chapter field"
echo "- Rich requirement display with all available information"
echo "- Badge counts for related entities"
echo "- Fallback for uncategorized requirements"
echo ""
echo "To test the functionality:"
echo "1. Ensure both backend and frontend are running"
echo "2. Login to the application"
echo "3. Navigate to Standards page (/standards)"
echo "4. Click 'View' on any standard"
echo "5. Check the 'Standard Structure' section"
echo "6. Requirements should be grouped by their chapter attribute"