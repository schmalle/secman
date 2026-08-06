# Data Model: Microsoft Identity Provider Optimization

**Feature**: 010-please-review-the
**Date**: 2025-10-05

## Overview

This feature enhances the existing `IdentityProvider` entity to support Microsoft-specific tenant configuration while maintaining backward compatibility with other providers (GitHub). No new entities are required.

## Entity Changes

### IdentityProvider (MODIFIED)

**Purpose**: Represents a configured OAuth 2.0/OIDC identity provider with Microsoft-specific tenant support

**File**: `src/backendng/src/main/kotlin/com/secman/domain/IdentityProvider.kt`

#### Added Fields

| Field Name | Type | Nullable | Default | Validation | Purpose |
|------------|------|----------|---------|------------|---------|
| `tenantId` | `String` | Yes (NULL) | NULL | UUID/GUID format for Microsoft | Azure AD tenant ID for single-tenant authentication |

#### Complete Schema (after changes)

```kotlin
@Entity
@Table(name = "identity_providers")
@Serdeable
data class IdentityProvider(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: ProviderType = ProviderType.OIDC,

    @Column(name = "client_id", nullable = false)
    var clientId: String = "",

    @Column(name = "client_secret")
    var clientSecret: String? = null,

    // NEW FIELD
    @Column(name = "tenant_id")
    var tenantId: String? = null,

    @Column(name = "discovery_url")
    var discoveryUrl: String? = null,

    @Column(name = "authorization_url")
    var authorizationUrl: String? = null,

    @Column(name = "token_url")
    var tokenUrl: String? = null,

    @Column(name = "user_info_url")
    var userInfoUrl: String? = null,

    var issuer: String? = null,

    @Column(name = "jwks_uri")
    var jwksUri: String? = null,

    var scopes: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = false,

    @Column(name = "auto_provision", nullable = false)
    var autoProvision: Boolean = false,

    @Column(name = "button_text", nullable = false)
    var buttonText: String = "",

    @Column(name = "button_color", nullable = false)
    var buttonColor: String = "#007bff",

    @Column(name = "role_mapping", columnDefinition = "TEXT")
    var roleMapping: String? = null,

    @Column(name = "claim_mappings", columnDefinition = "TEXT")
    var claimMappings: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
```

#### Field Validation Rules

**tenantId**:
- **Format**: UUID or GUID (e.g., `8ade847c-7c5a-4f17-86f5-f83c1d8f3f1b`)
- **Required when**: Provider name contains "Microsoft" (case-insensitive)
- **Validation regex**: `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`
- **Error message**: "Tenant ID is required for Microsoft providers and must be a valid UUID"

**Backward Compatibility**:
- GitHub providers: `tenantId` remains NULL
- Validation only enforced for Microsoft providers
- Database allows NULL for backward compatibility

#### Indexes

No new indexes required. Existing indexes sufficient:
- Primary key on `id`
- Unique index on `name`

Queries by tenant ID not expected to be common (small number of providers).

### OAuthState (NO CHANGES)

**Purpose**: Temporary state tokens for CSRF protection during OAuth flow

**File**: `src/backendng/src/main/kotlin/com/secman/domain/OAuthState.kt`

No changes required. Existing implementation handles all providers.

### User (NO CHANGES)

**Purpose**: User accounts (both local and OAuth-provisioned)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Clarification Impact**: Auto-provisioned Microsoft users always receive USER role (hardcoded in service layer, not entity).

No entity changes required.

## Service Layer Data Flow

### OAuth Callback Flow (Modified)

```
1. User completes Microsoft auth → callback with code + state
2. OAuthService.handleCallback():
   a. Validate state token (existing)
   b. Exchange code for tokens
   c. Parse ID token JWT → extract claims
   d. NEW: Validate tid claim matches provider.tenantId
   e. NEW: Validate email claim exists (reject if missing)
   f. Extract: email, name, preferred_username
   g. findOrCreateUser(email)
      - If exists: return existing
      - If new + autoProvision: create with USER role
   h. Generate JWT token
   i. Return success
```

### Tenant Validation Logic

```kotlin
// In OAuthService.handleCallback()
val idToken = parseIdToken(tokenResponse.idToken)
val tidClaim = idToken.claims["tid"] as? String

if (provider.tenantId != null && tidClaim != provider.tenantId) {
    return CallbackResult.Error("Tenant mismatch: User from wrong organization")
}
```

### Email Validation Logic

```kotlin
// In OAuthService.getUserInfo() or parseIdToken()
val email = userInfo["email"] as? String
    ?: userInfo["preferred_username"] as? String
    ?: userInfo["upn"] as? String

if (email == null) {
    return CallbackResult.Error("Email address required for account creation")
}
```

## DTO Changes

### IdentityProviderCreateRequest (MODIFIED)

**File**: `src/backendng/src/main/kotlin/com/secman/controller/IdentityProviderController.kt`

```kotlin
@Serdeable
data class IdentityProviderCreateRequest(
    val name: String,
    val type: String,
    val clientId: String,
    val clientSecret: String? = null,
    val tenantId: String? = null,  // NEW FIELD
    val discoveryUrl: String? = null,
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val userInfoUrl: String? = null,
    val issuer: String? = null,
    val jwksUri: String? = null,
    val scopes: String? = null,
    val enabled: Boolean = false,
    val autoProvision: Boolean = false,
    val buttonText: String,
    val buttonColor: String = "#007bff",
    val roleMapping: String? = null,
    val claimMappings: String? = null
)
```

### IdentityProviderUpdateRequest (MODIFIED)

```kotlin
@Serdeable
data class IdentityProviderUpdateRequest(
    val name: String?,
    val type: String?,
    val clientId: String?,
    val clientSecret: String?,
    val tenantId: String?,  // NEW FIELD
    val discoveryUrl: String?,
    val authorizationUrl: String?,
    val tokenUrl: String?,
    val userInfoUrl: String?,
    val issuer: String?,
    val jwksUri: String?,
    val scopes: String?,
    val enabled: Boolean?,
    val autoProvision: Boolean?,
    val buttonText: String?,
    val buttonColor: String?,
    val roleMapping: String?,
    val claimMappings: String?
)
```

### TestProviderResponse (NEW)

**Purpose**: Response from `/api/identity-providers/{id}/test` endpoint

```kotlin
@Serdeable
data class TestProviderResponse(
    val valid: Boolean,
    val checks: List<ValidationCheck>
)

@Serdeable
data class ValidationCheck(
    val name: String,
    val status: String,  // "pass", "fail", "warning"
    val message: String
)
```

## Frontend Data Model

### IdentityProvider Interface (TypeScript) - MODIFIED

**File**: `src/frontend/src/components/IdentityProviderManagement.tsx`

```typescript
interface IdentityProvider {
  id?: number;
  name: string;
  type: 'OIDC' | 'SAML';
  clientId: string;
  clientSecret?: string;
  tenantId?: string;  // NEW FIELD
  discoveryUrl?: string;
  authorizationUrl?: string;
  tokenUrl?: string;
  userInfoUrl?: string;
  issuer?: string;
  jwksUri?: string;
  scopes?: string;
  enabled: boolean;
  autoProvision: boolean;
  buttonText: string;
  buttonColor: string;
  roleMapping?: { [key: string]: string };
  claimMappings?: { [key: string]: string };
  createdAt?: string;
  updatedAt?: string;
}
```

### Microsoft Provider Template (Updated)

```typescript
const providerTemplates = {
  microsoft: {
    name: 'Microsoft',
    discoveryUrl: 'https://login.microsoftonline.com/{tenantId}/v2.0/.well-known/openid-configuration',
    authorizationUrl: 'https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/authorize',
    tokenUrl: 'https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token',
    scopes: 'openid email profile',
    buttonText: 'Sign in with Microsoft',
    buttonColor: '#0078d4',
    tenantId: ''  // NEW: Placeholder for user input
  }
}
```

## Database Migration

### Automatic Migration (Hibernate)

When the application starts with `hbm2ddl.auto=update`, Hibernate will detect the new field and execute:

```sql
ALTER TABLE identity_providers
ADD COLUMN tenant_id VARCHAR(255) NULL;
```

**No manual migration required** - this is handled automatically by Hibernate JPA.

### Rollback Strategy

If rollback is needed:
```sql
ALTER TABLE identity_providers
DROP COLUMN tenant_id;
```

**Note**: Rollback would break Microsoft providers created with this feature. Not recommended unless feature is completely removed.

## State Transitions

No new entity states. `IdentityProvider` remains in same states:
- **Draft**: `enabled = false`, not usable for auth
- **Active**: `enabled = true`, available on login page
- **Disabled**: `enabled = false`, hidden from login page

Tenant validation adds an implicit state:
- **Invalid tenant config**: `tenantId` NULL when required → validation error on save

## Data Constraints

### Application-Level Constraints

```kotlin
// In IdentityProviderController.createProvider()
if (request.name.contains("Microsoft", ignoreCase = true)) {
    if (request.tenantId.isNullOrBlank()) {
        return HttpResponse.badRequest(
            ErrorResponse("Tenant ID is required for Microsoft providers")
        )
    }

    val uuidRegex = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    if (!uuidRegex.matches(request.tenantId)) {
        return HttpResponse.badRequest(
            ErrorResponse("Tenant ID must be a valid UUID format")
        )
    }
}
```

### Database-Level Constraints

- **Unique name**: Already enforced by unique index
- **Non-null client_id**: Already enforced
- **tenantId nullable**: Enforced at column level

## Summary

**Modified Entities**: 1 (IdentityProvider)
**New Entities**: 0
**New DTOs**: 1 (TestProviderResponse)
**Modified DTOs**: 2 (Create/UpdateRequest)
**Database Changes**: 1 column addition (auto-migrated)
**Backward Compatibility**: ✅ Maintained (tenantId nullable)
