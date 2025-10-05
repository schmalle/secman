# Research: Microsoft Identity Provider Optimization

**Feature**: 010-please-review-the
**Date**: 2025-10-05

## Research Questions & Findings

### 1. Microsoft Azure AD Tenant-Specific Endpoints

**Question**: How do Microsoft tenant-specific endpoints differ from the common (`/common`) endpoint?

**Decision**: Use tenant-specific endpoints with format `https://login.microsoftonline.com/{tenantId}/v2.0/`

**Rationale**:
- **Security**: Tenant-specific endpoints restrict authentication to users from a single Azure AD tenant, preventing unauthorized access from other organizations
- **Compliance**: Many organizations require tenant isolation for regulatory compliance
- **Performance**: Tenant-specific endpoints can be faster as they don't need to discover the user's tenant
- **Claim validation**: The `tid` (tenant ID) claim in the ID token can be validated against the configured tenant ID

**Alternatives Considered**:
- **Common endpoint** (`/common/v2.0/`): Allows users from any tenant, but requires additional validation logic and doesn't meet the single-tenant requirement
- **Organizations endpoint** (`/organizations/v2.0/`): Similar to common but excludes personal Microsoft accounts - still multi-tenant

**Implementation Notes**:
- Discovery URL pattern: `https://login.microsoftonline.com/{tenantId}/v2.0/.well-known/openid-configuration`
- Authorization URL: `https://login.microsoftonline.com/{tenantId}/v2.0/oauth2/authorize`
- Token URL: `https://login.microsoftonline.com/{tenantId}/v2.0/oauth2/token`
- UserInfo URL: Not standard for Microsoft, use ID token claims instead or Microsoft Graph API

### 2. Microsoft ID Token Claims Structure

**Question**: Which claims are available in Microsoft ID tokens for user provisioning?

**Decision**: Extract `email`, `name`, and `preferred_username` (or `upn`) from ID token claims

**Rationale**:
- **Standard claims**: Microsoft follows OIDC spec and provides standard claims (sub, email, name)
- **Email availability**: Email claim may be missing for some users (B2B guests, users without email), requiring validation
- **UPN as fallback**: `preferred_username` or `upn` can serve as username when different from email
- **Tenant ID**: `tid` claim contains the tenant ID for validation

**Standard Microsoft ID Token Claims**:
```json
{
  "aud": "client_id",
  "iss": "https://login.microsoftonline.com/{tenantId}/v2.0",
  "sub": "unique_user_id",
  "tid": "tenant_id",
  "email": "user@example.com",  // May be absent
  "name": "John Doe",
  "preferred_username": "user@example.com",
  "upn": "user@example.com",  // User Principal Name
  "oid": "object_id",  // User's object ID in Azure AD
  "roles": []  // Not used in this version (deferred)
}
```

**Alternatives Considered**:
- **Microsoft Graph API**: Richer user data, but requires additional API calls and permissions - deferred to future
- **UserInfo endpoint**: Microsoft supports it but recommends using ID token claims for performance

**Implementation Notes**:
- Parse ID token JWT (already available from token exchange)
- Validate `tid` claim matches configured `tenantId`
- Extract `email` first, fallback to `preferred_username` or `upn` if needed
- Reject if no email-like claim found (per clarification)

### 3. Microsoft Error Codes (AADSTS)

**Question**: How should we handle Microsoft-specific AADSTS error codes?

**Decision**: Map common AADSTS codes to user-friendly messages with actionable guidance

**Rationale**:
- **User experience**: Raw AADSTS codes are cryptic and unhelpful to end users
- **Support burden**: Clear error messages reduce support tickets
- **Security**: Some errors should not expose internal details (e.g., tenant mismatch)

**Common AADSTS Error Codes to Map**:
```kotlin
AADSTS50020 -> "User account not found in this tenant. Please contact your administrator."
AADSTS50034 -> "User account does not exist. Please contact your administrator."
AADSTS50053 -> "Account is locked. Please contact your administrator."
AADSTS50055 -> "Password expired. Please reset your password."
AADSTS50056 -> "Invalid or null password. Please enter your password."
AADSTS50057 -> "User disabled. Please contact your administrator."
AADSTS50058 -> "Silent sign-in failed. Please try again."
AADSTS50105 -> "User not assigned to application. Please contact your administrator."
AADSTS50126 -> "Invalid username or password."
AADSTS50128 -> "Invalid tenant. Please verify configuration."
AADSTS50173 -> "Fresh authentication required. Please sign in again."
AADSTS65001 -> "User has not consented to application. Please grant permissions."
AADSTS70000 -> "Invalid grant. Please try again."
AADSTS700016 -> "Application not found in tenant. Please verify configuration."
```

**Alternatives Considered**:
- **Show raw codes**: Simpler but poor UX
- **Generic message**: Simpler but doesn't guide users to resolution
- **Full mapping**: Too many codes (hundreds), diminishing returns

**Implementation Notes**:
- Create `MicrosoftErrorMapper` utility class
- Parse error response from token exchange
- Match AADSTS code prefix
- Return mapped message or generic fallback

### 4. OpenID Connect Discovery for Microsoft

**Question**: Should we implement automatic endpoint discovery using the OIDC discovery document?

**Decision**: Implement discovery support with manual endpoint fallback

**Rationale**:
- **Reduced configuration**: Admins only need to provide tenant ID, not all endpoint URLs
- **Correctness**: Discovery ensures using current Microsoft endpoints (they may change)
- **Backward compatibility**: Existing providers with manual URLs continue working
- **Reliability**: Fallback to manual endpoints if discovery fails

**Discovery Document Structure** (Microsoft):
```json
{
  "issuer": "https://login.microsoftonline.com/{tenantId}/v2.0",
  "authorization_endpoint": "https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/authorize",
  "token_endpoint": "https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token",
  "userinfo_endpoint": "https://graph.microsoft.com/oidc/userinfo",
  "jwks_uri": "https://login.microsoftonline.com/{tenantId}/discovery/v2.0/keys",
  "response_types_supported": ["code", "id_token", "token"],
  "scopes_supported": ["openid", "profile", "email", "offline_access"],
  ...
}
```

**Alternatives Considered**:
- **Manual only**: Simpler but more error-prone configuration
- **Discovery only**: Doesn't support custom/test endpoints
- **Cache forever**: Simpler but endpoints might become stale

**Implementation Notes**:
- Fetch discovery document when `discoveryUrl` is present
- Cache in-memory for 24 hours (not persisted to database)
- Use manual URLs as fallback if discovery fails or not configured
- For Microsoft template, pre-fill discovery URL: `https://login.microsoftonline.com/{tenantId}/v2.0/.well-known/openid-configuration`

### 5. Hibernate JPA Column Addition for tenantId

**Question**: How should we add the `tenantId` field to maintain backward compatibility?

**Decision**: Add nullable `tenantId` column, validate at application layer for Microsoft providers

**Rationale**:
- **Backward compatibility**: Existing GitHub providers don't need tenant ID (null allowed)
- **Hibernate auto-migration**: `hbm2ddl.auto=update` automatically adds the column
- **Validation**: Application logic enforces tenant ID requirement for Microsoft providers only
- **Data integrity**: Column-level nullable, application-level validation

**Schema Change** (Hibernate will apply automatically):
```sql
ALTER TABLE identity_providers
ADD COLUMN tenant_id VARCHAR(255) NULL;
```

**Alternatives Considered**:
- **Required field**: Breaks existing providers (GitHub)
- **Separate table**: Over-engineered for single field
- **JSON column**: Less queryable, no referential integrity

**Implementation Notes**:
- Add `tenantId: String?` field to `IdentityProvider` entity
- Mark with `@Column(name = "tenant_id", nullable = true)`
- Validate in controller: require tenant ID when provider name = "Microsoft"
- Update frontend template to include tenant ID input field

### 6. Provider Configuration Test Endpoint Design

**Question**: What should the `/api/identity-providers/{id}/test` endpoint validate?

**Decision**: Perform basic field validation and URL format checking (per clarification)

**Rationale**:
- **Scope decision**: Clarification specified "basic validation only"
- **No credentials test**: Avoids exposing client secret in logs or requiring actual OAuth flow
- **Fast feedback**: Synchronous validation, no external API calls
- **Security**: No risk of credential leakage

**Validations to Perform**:
1. **Required fields present**: client_id, client_secret, tenant_id (for Microsoft)
2. **URL format valid**: authorization_url, token_url match `https://` pattern
3. **Scopes format**: Non-empty string containing "openid"
4. **Provider enabled**: Check enabled flag (warning if disabled)
5. **Tenant ID format**: UUID or GUID format for Microsoft

**Response Format**:
```json
{
  "valid": true|false,
  "checks": [
    {"name": "Client ID", "status": "pass", "message": "Present"},
    {"name": "Tenant ID", "status": "pass", "message": "Valid UUID format"},
    {"name": "Authorization URL", "status": "pass", "message": "Valid HTTPS URL"},
    {"name": "Token URL", "status": "pass", "message": "Valid HTTPS URL"},
    {"name": "Scopes", "status": "pass", "message": "Includes 'openid'"}
  ]
}
```

**Alternatives Considered**:
- **Full credential test**: Too complex, security risk, slow
- **Endpoint reachability**: Network dependent, slow, not in scope per clarification
- **Discovery test**: Requires external calls, not in scope

**Implementation Notes**:
- New endpoint: `POST /api/identity-providers/{id}/test`
- Return 200 with validation results
- No modification of provider state
- Requires `IS_AUTHENTICATED` security rule

## Summary of Decisions

| Decision Point | Chosen Approach | Key Reason |
|---------------|-----------------|------------|
| Endpoint type | Tenant-specific (`/{tenantId}/v2.0/`) | Single-tenant security requirement |
| Claims extraction | ID token claims (email, name, preferred_username) | Standard OIDC, no extra API calls |
| Error handling | Map AADSTS codes to friendly messages | Improved UX, reduced support burden |
| Discovery | Implement with manual fallback | Best of both worlds |
| Schema migration | Nullable tenantId field | Backward compatibility |
| Test endpoint | Basic field/URL validation only | Per clarification, fast and safe |

## Dependencies & Constraints

**No new dependencies required**:
- All functionality implementable with existing Micronaut, Kotlin, and JPA stack
- Frontend changes use existing React/TypeScript/Axios

**Constraints respected**:
- ✅ No breaking changes to existing OAuth flow
- ✅ Backward compatible with GitHub provider
- ✅ Hibernate auto-migration only (no manual SQL)
- ✅ Single-tenant scope (multi-tenant deferred)
- ✅ No role mapping (deferred to future)

## Next Phase

Proceed to Phase 1: Design contracts and data model based on these research findings.
