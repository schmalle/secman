#!/bin/bash

echo "GitHub OAuth Flow Testing Guide"
echo "==============================="
echo ""
echo "1. First, ensure you have set up your GitHub OAuth App:"
echo "   - Go to GitHub Settings > Developer settings > OAuth Apps"
echo "   - Create a new OAuth App or update existing one"
echo "   - Set Authorization callback URL to: http://localhost:8080/oauth/callback"
echo ""
echo "2. Set the required environment variables for the backend:"
echo "   export GITHUB_CLIENT_ID='your-github-client-id'"
echo "   export GITHUB_CLIENT_SECRET='your-github-client-secret'"
echo ""
echo "3. Restart your backend with the environment variables:"
echo "   cd src/backendng"
echo "   GITHUB_CLIENT_ID='your-id' GITHUB_CLIENT_SECRET='your-secret' ./gradlew run"
echo ""
echo "4. Test the OAuth flow:"
echo "   a. Open your browser to: http://localhost:4321"
echo "   b. Click on 'Login with GitHub'"
echo "   c. You should be redirected to GitHub for authorization"
echo "   d. After authorizing, you should be redirected back to your app"
echo ""
echo "5. Current OAuth endpoints:"
echo "   - Start OAuth: GET http://localhost:8080/oauth/authorize/github"
echo "   - Callback: GET http://localhost:8080/oauth/callback?code=...&state=..."
echo "   - Success redirect: http://localhost:4321/login/success"
echo "   - Error redirect: http://localhost:4321/login?error=..."
echo ""
echo "Testing OAuth initialization..."
echo ""

# Check if backend is running
if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/health | grep -q "200"; then
    echo "✓ Backend is running"
    
    # Test OAuth authorize endpoint
    response=$(curl -s -w "\n%{http_code}" http://localhost:8080/oauth/authorize/github)
    http_code=$(echo "$response" | tail -n 1)
    
    if [ "$http_code" == "302" ] || [ "$http_code" == "303" ]; then
        echo "✓ OAuth authorize endpoint is working (returns redirect)"
    else
        echo "✗ OAuth authorize endpoint returned: $http_code"
        echo "  This might indicate missing GitHub OAuth credentials"
    fi
    
    # Check enabled providers
    echo ""
    echo "Checking enabled identity providers..."
    curl -s http://localhost:8080/api/identity-providers/enabled | jq '.' 2>/dev/null || echo "Unable to parse response"
    
else
    echo "✗ Backend is not running on port 8080"
    echo "  Please start the backend first"
fi

echo ""
echo "Note: The OAuth flow has been fixed to:"
echo "1. Use consistent redirect URIs (/oauth/callback)"
echo "2. Properly validate state parameters"
echo "3. Store tokens in secure HttpOnly cookies"
echo "4. Remove sensitive data from logs"