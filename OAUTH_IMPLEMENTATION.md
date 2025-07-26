# OAuth GitHub Login Implementation

## Overview

This implementation fixes the GitHub login issue by providing a complete OAuth 2.0 authorization code flow integration.

## Components Implemented

### 1. Domain Models

**OAuthState** (`src/main/kotlin/com/secman/domain/OAuthState.kt`)
- Manages OAuth state parameters to prevent CSRF attacks
- Automatic expiry after 10 minutes
- Links state to specific identity providers

### 2. Repositories

**OAuthStateRepository** (`src/main/kotlin/com/secman/repository/OAuthStateRepository.kt`)
- CRUD operations for OAuth states
- Automatic cleanup of expired states
- State validation methods

### 3. Services

**OAuthService** (`src/main/kotlin/com/secman/service/OAuthService.kt`)
- Core OAuth logic implementation
- Authorization URL generation with state management
- Token exchange with GitHub's OAuth endpoints
- User information retrieval from GitHub API
- User auto-provisioning when enabled
- Integration with existing JWT token system

### 4. Controllers

**OAuthController** (`src/main/kotlin/com/secman/controller/OAuthController.kt`)
- `GET /oauth/authorize/{providerId}` - Initiates OAuth flow
- `GET /oauth/callback/{providerId}` - Handles OAuth callbacks  
- `POST /oauth/callback/{providerId}` - API endpoint for AJAX callbacks

### 5. Frontend

**Login Success Page** (`src/frontend/src/pages/login/success.astro`)
- Handles OAuth callback redirects
- Stores JWT tokens in localStorage
- Redirects to main application

## OAuth Flow

1. User clicks "Sign in with GitHub" button
2. Frontend redirects to `/oauth/authorize/{providerId}`
3. Backend generates secure state parameter and saves it
4. Backend redirects user to GitHub's authorization URL
5. User authorizes the application on GitHub
6. GitHub redirects back to `/oauth/callback/{providerId}` with code and state
7. Backend validates state parameter
8. Backend exchanges authorization code for access token
9. Backend fetches user information from GitHub API
10. Backend finds or creates user account (if auto-provisioning enabled)
11. Backend generates JWT token
12. Backend redirects to success page with token
13. Frontend stores token and redirects to main application

## Configuration

### Application Configuration (application.yml)

```yaml
micronaut:
  security:
    oauth2:
      enabled: true
    intercept-url-map:
      - pattern: /oauth/**
        access:
          - isAnonymous()

oauth:
  http-client:
    url: https://api.github.com
```

### GitHub OAuth Application Setup

1. Go to GitHub → Settings → Developer settings → OAuth Apps
2. Create new OAuth App with:
   - Application name: "SecMan"
   - Homepage URL: "http://localhost:4321" (or your domain)
   - Authorization callback URL: "http://localhost:8080/oauth/callback/1"

### Identity Provider Configuration

Use the IdentityProviderManagement UI to create a GitHub provider:

```json
{
  "name": "GitHub",
  "type": "OIDC",
  "clientId": "your_github_client_id",
  "clientSecret": "your_github_client_secret",
  "authorizationUrl": "https://github.com/login/oauth/authorize",
  "tokenUrl": "https://github.com/login/oauth/access_token",
  "userInfoUrl": "https://api.github.com/user",
  "scopes": "user:email",
  "enabled": true,
  "autoProvision": true,
  "buttonText": "Sign in with GitHub",
  "buttonColor": "#333333"
}
```

## Testing

### Prerequisites

1. Start database: `docker compose -f docker-compose.dev.yml up database`
2. Configure GitHub OAuth application
3. Start backend: `./gradlew run`
4. Start frontend: `npm run dev` (in src/frontend)

### Test Steps

1. Navigate to `http://localhost:4321/login`
2. Configure GitHub identity provider via admin UI
3. Click "Sign in with GitHub" button
4. Verify redirect to GitHub authorization page
5. Authorize the application
6. Verify redirect back to application with successful login
7. Check that user is created in database (if auto-provisioning enabled)

### Manual API Testing

```bash
# Test authorization endpoint
curl -v "http://localhost:8080/oauth/authorize/1"

# Should redirect to GitHub with proper parameters:
# - client_id
# - redirect_uri  
# - scope
# - state
# - response_type=code

# Test callback endpoint (with actual code and state from GitHub)
curl -v "http://localhost:8080/oauth/callback/1?code=GITHUB_CODE&state=GENERATED_STATE"
```

## Security Features

1. **State Parameter Validation**: Prevents CSRF attacks
2. **State Expiry**: 10-minute automatic expiry of state parameters
3. **Secure Random State Generation**: Uses UUID for unpredictable states
4. **Token Validation**: Validates GitHub's OAuth responses
5. **User Agent Headers**: Proper GitHub API compliance
6. **Error Handling**: Comprehensive error handling and logging

## Error Handling

The implementation handles various error scenarios:

- Invalid or expired state parameters
- OAuth authorization errors from GitHub
- Token exchange failures
- User information retrieval failures
- Network connectivity issues
- JSON parsing errors

All errors are logged and user-friendly error messages are displayed.

## Database Tables

The implementation creates the following tables:

- `oauth_states` - For state parameter management
- `identity_providers` - Already existed, used for OAuth provider configuration
- `users` - Already existed, OAuth users are created here

## Integration Points

- Uses existing User domain model and repository
- Integrates with existing JWT token generation
- Works with existing IdentityProvider management UI
- Compatible with existing authentication system