# Quickstart: MCP User Delegation

**Feature**: 050-mcp-user-delegation
**Date**: 2025-11-28

## Overview

This guide covers implementing MCP user delegation, which allows trusted external tools to make MCP requests on behalf of authenticated users.

## Prerequisites

- Existing MCP server implementation (McpController, McpAuthenticationService)
- Existing User entity with email and roles
- Existing McpApiKey entity and McpAuditLog entity

## Implementation Order

### Phase 1: Backend Entity Extensions

1. **Extend McpApiKey entity** (`McpApiKey.kt`)
   - Add `delegationEnabled: Boolean = false`
   - Add `allowedDelegationDomains: String? = null`
   - Add helper methods: `isDelegationAllowedForEmail()`, `getDelegationDomainsList()`

2. **Extend McpAuditLog entity** (`McpAuditLog.kt`)
   - Add `delegatedUserEmail: String? = null`
   - Add `delegatedUserId: Long? = null`
   - Add index for delegation queries

3. **Create Flyway migration** (`V050__mcp_user_delegation.sql`)
   ```sql
   ALTER TABLE mcp_api_keys
     ADD COLUMN delegation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
     ADD COLUMN allowed_delegation_domains VARCHAR(500) NULL;

   ALTER TABLE mcp_audit_logs
     ADD COLUMN delegated_user_email VARCHAR(255) NULL,
     ADD COLUMN delegated_user_id BIGINT NULL;

   CREATE INDEX idx_mcp_audit_delegated_user
     ON mcp_audit_logs(delegated_user_email, timestamp);
   ```

### Phase 2: Backend Service Layer

4. **Create McpDelegationService** (`McpDelegationService.kt`)
   - `validateDelegation(apiKey, email)`: Validate domain, lookup user, check status
   - `computeEffectivePermissions(userRoles, apiKeyPermissions)`: Intersection logic
   - `trackDelegationFailure(apiKeyId, email, reason)`: Threshold tracking
   - `checkAlertThreshold(apiKeyId)`: Trigger alert if threshold exceeded

5. **Update McpAuthenticationService** (`McpAuthenticationService.kt`)
   - Add `authenticateWithDelegation(apiKey, userEmail)` method
   - Call delegation service when header present

6. **Update McpAuditService** (`McpAuditService.kt`)
   - Extend logging methods to include `delegatedUserEmail`, `delegatedUserId`

### Phase 3: Backend Controller Layer

7. **Update McpController** (`McpController.kt`)
   - Extract `X-MCP-User-Email` header
   - Call delegation service when header present and key has delegation enabled
   - Use effective permissions for authorization

8. **Update McpApiKeyController** (`McpApiKeyController.kt`)
   - Add delegation fields to create/update DTOs
   - Add validation: delegation requires domains
   - Return delegation config in responses

### Phase 4: Frontend Updates

9. **Update API key form component** (`McpApiKeyForm.tsx`)
   - Add "Enable User Delegation" toggle
   - Add "Allowed Domains" input (required when toggle enabled)
   - Client-side validation

10. **Update API key list display** (`mcp-api-keys.astro`)
    - Show delegation status badge
    - Show allowed domains in detail view

### Phase 5: Documentation

11. **Update MCP_INTEGRATION.md**
    - Add "User Delegation" section
    - Document X-MCP-User-Email header
    - Document domain restrictions
    - Add example configurations

### Phase 6: Configuration

12. **Update application.yml**
    ```yaml
    mcp:
      delegation:
        alert:
          threshold: 10
          window-minutes: 5
    ```

## Key Code Patterns

### Domain Validation
```kotlin
fun isDelegationAllowedForEmail(email: String): Boolean {
    if (!delegationEnabled || allowedDelegationDomains.isNullOrBlank()) {
        return false
    }
    val emailDomain = "@" + email.substringAfter("@").lowercase()
    return getDelegationDomainsList().any {
        emailDomain.endsWith(it.lowercase())
    }
}
```

### Permission Intersection
```kotlin
fun computeEffectivePermissions(
    userRoles: Set<String>,
    apiKeyPermissions: Set<McpPermission>
): Set<McpPermission> {
    val userImpliedPermissions = userRoles.flatMap { role ->
        roleToPermissions[role] ?: emptySet()
    }.toSet()

    return apiKeyPermissions.intersect(userImpliedPermissions)
}
```

### Controller Header Handling
```kotlin
@Post("/tools/call")
suspend fun callTool(
    @Header("X-MCP-API-Key") apiKey: String?,
    @Header("X-MCP-User-Email") delegatedEmail: String?,
    @Body request: McpToolCallRequest
): HttpResponse<McpToolCallResponse> {
    // ... authentication ...

    val effectivePermissions = if (mcpApiKey.delegationEnabled && !delegatedEmail.isNullOrBlank()) {
        delegationService.validateAndGetPermissions(mcpApiKey, delegatedEmail)
    } else {
        mcpApiKey.getPermissionSet()
    }

    // ... continue with effectivePermissions ...
}
```

## Testing Checklist (When Requested)

- [ ] API key creation with delegation enabled requires domains
- [ ] Delegation request with valid user/domain succeeds
- [ ] Delegation request with invalid domain is rejected
- [ ] Delegation request with non-existent user is rejected
- [ ] Delegation request with inactive user is rejected
- [ ] Fallback to API key permissions when no email header
- [ ] Legacy (non-delegation) keys ignore email header
- [ ] Audit logs include delegated user email
- [ ] Alert triggered after threshold failures
- [ ] Permission intersection computed correctly

## Common Issues

1. **"Delegation enabled but no domains"**: Ensure `allowedDelegationDomains` is set when enabling delegation
2. **"Email domain not allowed"**: Check domain format (must start with @)
3. **"User not found"**: Verify user exists in Secman with exact email match (case-insensitive)
4. **Performance issues**: Ensure index exists on users.email column
