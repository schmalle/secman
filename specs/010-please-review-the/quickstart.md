# Quickstart: Microsoft Identity Provider Testing

**Feature**: 010-please-review-the
**Date**: 2025-10-05

## Purpose

This quickstart guide validates the Microsoft identity provider optimization feature through manual testing and automated integration tests. It ensures that single-tenant authentication, tenant validation, email requirement, and configuration testing work correctly.

## Prerequisites

### Azure AD Configuration

1. **Azure AD Tenant** (for testing):
   - Tenant ID (UUID format): `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
   - Tenant domain: `yourtenant.onmicrosoft.com`

2. **App Registration** in Azure Portal:
   - Go to Azure Portal → Azure Active Directory → App registrations → New registration
   - Name: `SecMan Test App`
   - Supported account types: **Accounts in this organizational directory only (Single tenant)**
   - Redirect URI: `http://localhost:8080/oauth/callback`
   - After registration, note:
     - Application (client) ID
     - Directory (tenant) ID
   - Create client secret:
     - Certificates & secrets → New client secret
     - Note the secret value (shown only once)

3. **API Permissions**:
   - Microsoft Graph → Delegated permissions
   - Add: `openid`, `email`, `profile`
   - Grant admin consent (if required)

4. **Test Users** in Azure AD:
   - User with email: `testuser@yourtenant.onmicrosoft.com`
   - User from different tenant (for negative testing)

### Application Setup

1. **Environment Variables** (`.env` file):
```bash
# Add to existing .env file
MICROSOFT_CLIENT_ID=your-app-client-id
MICROSOFT_CLIENT_SECRET=your-app-client-secret
MICROSOFT_TENANT_ID=your-tenant-id
```

2. **Start Services**:
```bash
docker-compose up -d mariadb
cd src/backendng && ./gradlew run  # Terminal 1
cd src/frontend && npm run dev     # Terminal 2
```

3. **Database Ready**:
   - MariaDB running on port 3306
   - Tables auto-created by Hibernate
   - `tenant_id` column added to `identity_providers`

## Test Scenarios

### Scenario 1: Configure Microsoft Provider

**Objective**: Create a Microsoft identity provider with tenant ID

**Steps**:
1. Navigate to: `http://localhost:4321/admin/identity-providers`
2. Click "Add Identity Provider"
3. Click "Microsoft" template button
4. Fill in configuration:
   - **Provider Name**: `Microsoft Test`
   - **Type**: OpenID Connect (OIDC)
   - **Client ID**: `${MICROSOFT_CLIENT_ID}`
   - **Client Secret**: `${MICROSOFT_CLIENT_SECRET}`
   - **Tenant ID**: `${MICROSOFT_TENANT_ID}` ⭐ NEW FIELD
   - **Discovery URL**: `https://login.microsoftonline.com/${MICROSOFT_TENANT_ID}/v2.0/.well-known/openid-configuration`
   - **Scopes**: `openid email profile`
   - **Enabled**: ✅ Checked
   - **Auto-provision**: ✅ Checked
   - **Button Text**: `Sign in with Microsoft`
   - **Button Color**: `#0078d4`
5. Click "Create Provider"

**Expected Results**:
- ✅ Provider saved successfully
- ✅ Tenant ID field visible and required for Microsoft
- ✅ Discovery URL includes tenant ID placeholder
- ✅ Provider appears in list with "Enabled" badge

**Validation**:
```bash
# Query database
docker exec -it secman-mariadb-1 mysql -u secman -pCHANGEME secman \
  -e "SELECT id, name, tenant_id, enabled FROM identity_providers WHERE name='Microsoft Test';"
```

Expected output:
```
+----+----------------+--------------------------------------+---------+
| id | name           | tenant_id                            | enabled |
+----+----------------+--------------------------------------+---------+
|  2 | Microsoft Test | xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx |       1 |
+----+----------------+--------------------------------------+---------+
```

### Scenario 2: Test Provider Configuration

**Objective**: Validate provider configuration using test endpoint

**Steps**:
1. In provider list, click "Test" button for Microsoft provider
2. Wait for validation results

**Expected Results** (All Pass):
```json
{
  "valid": true,
  "checks": [
    {"name": "Client ID", "status": "pass", "message": "Present"},
    {"name": "Client Secret", "status": "pass", "message": "Present"},
    {"name": "Tenant ID", "status": "pass", "message": "Valid UUID format"},
    {"name": "Authorization URL", "status": "pass", "message": "Valid HTTPS URL"},
    {"name": "Token URL", "status": "pass", "message": "Valid HTTPS URL"},
    {"name": "Scopes", "status": "pass", "message": "Includes 'openid'"}
  ]
}
```

**Alternative Test** (API direct):
```bash
# Get JWT token first (login as admin)
TOKEN="your-jwt-token"

# Test provider
curl -X POST "http://localhost:8080/api/identity-providers/2/test" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" | jq
```

### Scenario 3: Successful Microsoft Authentication

**Objective**: Complete OAuth flow with valid tenant user

**Steps**:
1. Navigate to: `http://localhost:4321/login`
2. Click "Sign in with Microsoft" button
3. Browser redirects to Microsoft login (tenant-specific URL):
   - URL should contain: `login.microsoftonline.com/${TENANT_ID}/oauth2/v2.0/authorize`
4. Enter credentials: `testuser@yourtenant.onmicrosoft.com`
5. Grant consent (if prompted)
6. Browser redirects back to application

**Expected Results**:
- ✅ Redirected to Microsoft with correct tenant ID in URL
- ✅ After login, redirected to `http://localhost:4321/login/success?token=...`
- ✅ User automatically provisioned in database
- ✅ User assigned USER role
- ✅ Session established

**Validation**:
```bash
# Check user created
docker exec -it secman-mariadb-1 mysql -u secman -pCHANGEME secman \
  -e "SELECT id, username, email FROM users WHERE email='testuser@yourtenant.onmicrosoft.com';"
```

Expected output:
```
+----+----------+---------------------------------------+
| id | username | email                                 |
+----+----------+---------------------------------------+
|  5 | testuser | testuser@yourtenant.onmicrosoft.com   |
+----+----------+---------------------------------------+
```

```bash
# Check user has USER role
docker exec -it secman-mariadb-1 mysql -u secman -pCHANGEME secman \
  -e "SELECT * FROM user_roles WHERE user_id=5;"
```

Expected output:
```
+---------+------+
| user_id | name |
+---------+------+
|       5 | USER |
+---------+------+
```

### Scenario 4: Tenant Mismatch Rejection

**Objective**: Verify authentication fails for users from wrong tenant

**Steps**:
1. Navigate to: `http://localhost:4321/login`
2. Click "Sign in with Microsoft"
3. Login with user from **different Azure AD tenant**

**Expected Results**:
- ✅ After OAuth callback, redirected to login page with error
- ✅ Error message: "Tenant mismatch: User from wrong organization"
- ✅ User NOT created in database
- ✅ Error logged in backend logs

**Validation**:
```bash
# Check backend logs
docker-compose logs backend | grep -i "tenant mismatch"
```

Expected log entry:
```
ERROR com.secman.service.OAuthService - OAuth callback error: Tenant mismatch: tid claim does not match configured tenant ID
```

### Scenario 5: Email Missing Rejection

**Objective**: Verify authentication fails when email claim is missing

**Testing Note**: This is difficult to test with real Azure AD accounts (most have email). Can be tested with:
- Mock ID token in integration tests
- Or special Azure AD B2B guest account without email

**Expected Results** (in integration test):
- ✅ When ID token has no `email`, `preferred_username`, or `upn` claim
- ✅ Authentication rejected with error: "Email address required for account creation"
- ✅ User NOT created
- ✅ Error logged

### Scenario 6: Invalid Tenant ID Format

**Objective**: Verify validation rejects malformed tenant IDs

**Steps**:
1. Try to create Microsoft provider with invalid tenant ID:
   - `not-a-uuid`
   - `123456`
   - Empty string

**Expected Results**:
- ✅ Validation error: "Tenant ID must be a valid UUID format"
- ✅ Provider NOT created
- ✅ Error shown in UI

**API Test**:
```bash
curl -X POST "http://localhost:8080/api/identity-providers" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Microsoft Invalid",
    "type": "OIDC",
    "clientId": "test",
    "clientSecret": "test",
    "tenantId": "not-a-uuid",
    "buttonText": "Test"
  }' | jq
```

Expected response:
```json
{
  "error": "Tenant ID must be a valid UUID format",
  "timestamp": "2025-10-05T..."
}
```

### Scenario 7: GitHub Provider Still Works

**Objective**: Verify backward compatibility - GitHub providers work without tenant ID

**Steps**:
1. Ensure GitHub provider exists (from ProviderInitializationService)
2. Verify tenant_id is NULL for GitHub
3. Test GitHub OAuth flow (if credentials available)

**Validation**:
```bash
docker exec -it secman-mariadb-1 mysql -u secman -pCHANGEME secman \
  -e "SELECT id, name, tenant_id, enabled FROM identity_providers WHERE name='GitHub';"
```

Expected output:
```
+----+--------+-----------+---------+
| id | name   | tenant_id | enabled |
+----+--------+-----------+---------+
|  1 | GitHub | NULL      |       1 |
+----+--------+-----------+---------+
```

## Integration Test Execution

**Backend Tests**:
```bash
cd src/backendng
./gradlew test --tests OAuthServiceTest
./gradlew test --tests IdentityProviderControllerTest
```

**Expected Test Results**:
- ✅ `testTenantValidation_success` - tid claim matches tenant ID
- ✅ `testTenantValidation_mismatch` - tid claim mismatch rejected
- ✅ `testEmailValidation_present` - email claim extracted correctly
- ✅ `testEmailValidation_missing` - authentication rejected
- ✅ `testProviderTest_allPass` - valid configuration passes
- ✅ `testProviderTest_invalidTenantId` - malformed tenant ID fails
- ✅ `testUserRoleAssignment` - USER role assigned to new users

**Frontend E2E Tests** (if implemented):
```bash
cd src/frontend
npm run test:e2e -- microsoft-oauth.spec.ts
```

## Cleanup

**Remove Test Data**:
```bash
docker exec -it secman-mariadb-1 mysql -u secman -pCHANGEME secman << EOF
DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%yourtenant.onmicrosoft.com');
DELETE FROM users WHERE email LIKE '%yourtenant.onmicrosoft.com';
DELETE FROM identity_providers WHERE name = 'Microsoft Test';
EOF
```

## Success Criteria

Feature is ready for production when:

- [x] All 7 manual test scenarios pass
- [x] All backend integration tests pass (≥85% coverage)
- [x] Frontend E2E test passes (OAuth flow end-to-end)
- [x] No TypeScript/Kotlin linting errors
- [x] Docker build succeeds for both AMD64 and ARM64
- [x] Database migration applied successfully (tenant_id column present)
- [x] GitHub provider continues to work (backward compatibility)
- [x] Error messages are user-friendly (no raw AADSTS codes)

## Troubleshooting

**Issue**: "Tenant ID is required for Microsoft providers"
- **Solution**: Ensure tenant ID field is filled when creating Microsoft provider

**Issue**: "Tenant mismatch" error during login
- **Solution**: Verify tenant ID in provider configuration matches Azure AD tenant ID exactly

**Issue**: "Email address required for account creation"
- **Solution**: Ensure test user has email address in Azure AD profile, or fix claim mapping

**Issue**: Discovery URL fails to load
- **Solution**: Check network connectivity, verify tenant ID is correct, use manual endpoints as fallback

**Issue**: OAuth callback redirect fails
- **Solution**: Verify redirect URI in Azure AD app registration matches `http://localhost:8080/oauth/callback`

## Next Steps

After quickstart validation:
1. Run `/tasks` to generate implementation tasks
2. Execute tasks following TDD workflow
3. Re-run quickstart tests after each major change
4. Create PR when all tests pass
