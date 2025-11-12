# OAuth Localhost Callback URL Issue - Fix Guide

## Problem Description

When attempting to log in with Microsoft OAuth, users are redirected to Microsoft but encounter error:
```
AADSTS50011: The redirect URI 'https://localhost:8080/oauth/callback' specified in
the request does not match the redirect URIs configured for the application
```

Even though the backend is configured with `BACKEND_BASE_URL=https://secman.covestro.net`, the system is sending `localhost:8080` as the callback URL.

## Root Cause

The `callback_url` field in the `identity_provider` database table contains a hardcoded value of `https://localhost:8080/oauth/callback`. This was likely set when creating the OAuth provider through the admin UI during development.

According to the system design:
- If `callback_url` IS NULL → Use `${BACKEND_BASE_URL}/oauth/callback` (dynamic, environment-aware)
- If `callback_url` IS SET → Use the exact value from database (static, ignores environment)

## Why No Backend Logs Were Visible

The OAuth flow logging was at DEBUG level, which doesn't show by default. The code has now been updated to use INFO level logging for better visibility:
- `OAuthService.buildAuthorizationUrl` now logs the provider callback URL and constructed redirect URI at INFO level
- You'll now see: `"Provider callback URL: https://localhost:8080/oauth/callback"` and `"Constructed RedirectUri: https://localhost:8080/oauth/callback"`

## Solution

### Option 1: Update Database (Recommended)

Run the provided SQL script to remove hardcoded localhost references:

```bash
mysql -u secman -p -h localhost -D secman < fix-oauth-callback-urls.sql
```

This will:
1. Set `callback_url = NULL` for all providers with localhost URLs
2. Make the system dynamically use `${BACKEND_BASE_URL}/oauth/callback`
3. Automatically work in dev (localhost) and production (secman.covestro.net)

### Option 2: Update via Admin UI

1. Navigate to **Admin → Identity Providers**
2. Edit the Microsoft OAuth provider
3. Clear the "Callback URL" field (leave it blank)
4. Save the changes

### Option 3: Set Explicit Production URL

If you want to keep it explicit, set callback URL to:
```
https://secman.covestro.net/oauth/callback
```

## After Fix - What to Update in Azure

Once you fix the database, you need to update your Azure App Registration:

1. Go to Azure Portal → App Registrations → Your Application
2. Navigate to **Authentication** → **Platform configurations** → **Web**
3. Add redirect URI: `https://secman.covestro.net/oauth/callback`
4. Remove or keep `http://localhost:8080/oauth/callback` (only if you test OAuth in local dev)
5. Save changes

## Verification Steps

After applying the fix:

1. Restart the backend (to reload configuration):
   ```bash
   ./gradlew backendng:run
   ```

2. Check the logs when clicking "Login with Microsoft":
   ```
   INFO OAuthService.buildAuthorizationUrl: Starting for providerId=2, baseUrl=https://secman.covestro.net
   INFO OAuthService.buildAuthorizationUrl: Provider callback URL: null
   INFO OAuthService.buildAuthorizationUrl: Constructed RedirectUri: https://secman.covestro.net/oauth/callback
   INFO OAuthController - Redirecting to OAuth provider 2 with URL: ...
   ```

3. Verify Microsoft receives the correct redirect URI (should now be `https://secman.covestro.net/oauth/callback`)

## All Localhost References in Codebase

See the previous analysis for a complete list of hardcoded localhost references in:
- Configuration files (application.yml) - these are OK, they're fallback defaults
- Frontend API services - these use environment variables first
- CLI commands - these accept `--backend-url` parameter
- MCP server - needs to be updated if used in production
- Test/development scripts - these are OK

The **only problematic** localhost reference was in your database.

## Code Changes Made

Enhanced logging in `src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt`:
- Line 59: Added INFO log showing providerId and baseUrl
- Lines 84-85: Changed DEBUG to INFO for callback URL logging

This will help diagnose similar issues in the future.
