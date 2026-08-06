# Data Model: MCP User Delegation

**Feature**: 050-mcp-user-delegation
**Date**: 2025-11-28

## Entity Changes

### McpApiKey (Extended)

**Table**: `mcp_api_keys`

| Field | Type | Nullable | Default | Description |
|-------|------|----------|---------|-------------|
| `delegation_enabled` | BOOLEAN | NO | FALSE | Whether this key can delegate to users |
| `allowed_delegation_domains` | VARCHAR(500) | YES | NULL | Comma-separated list of allowed email domains (e.g., "@company.com,@subsidiary.com") |

**Constraints**:
- If `delegation_enabled = TRUE`, then `allowed_delegation_domains` MUST NOT be NULL or empty
- Domain validation: each domain must start with "@" and contain at least one "."

**Kotlin Entity Extension**:
```kotlin
@Column(name = "delegation_enabled", nullable = false)
val delegationEnabled: Boolean = false

@Column(name = "allowed_delegation_domains", length = 500)
@Size(max = 500)
val allowedDelegationDomains: String? = null
```

**New Methods**:
```kotlin
fun isDelegationAllowedForEmail(email: String): Boolean
fun getDelegationDomainsList(): List<String>
```

### McpAuditLog (Extended)

**Table**: `mcp_audit_logs`

| Field | Type | Nullable | Default | Description |
|-------|------|----------|---------|-------------|
| `delegated_user_email` | VARCHAR(255) | YES | NULL | Email of the user on whose behalf the request was made |
| `delegated_user_id` | BIGINT | YES | NULL | ID of the delegated user (for joins) |

**Index**: `idx_mcp_audit_delegated_user` on `(delegated_user_email, timestamp)`

**Kotlin Entity Extension**:
```kotlin
@Column(name = "delegated_user_email", length = 255)
@Size(max = 255)
val delegatedUserEmail: String? = null

@Column(name = "delegated_user_id")
val delegatedUserId: Long? = null
```

## New Entities

### McpDelegationFailureTracker (In-Memory)

**Purpose**: Track delegation failures for threshold alerting

**Structure**:
```kotlin
data class DelegationFailureRecord(
    val apiKeyId: Long,
    val timestamp: LocalDateTime,
    val email: String,
    val reason: String
)

// In McpDelegationService
private val failureTracker: ConcurrentHashMap<Long, MutableList<DelegationFailureRecord>>
```

**Behavior**:
- Entries expire after `window-minutes` (configurable, default 5)
- When count exceeds `threshold` (configurable, default 10), trigger alert
- Alert is logged and can be picked up by monitoring

## State Transitions

### Delegation Request Flow

```
┌─────────────────┐
│ MCP Request     │
│ (X-MCP-API-Key) │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Validate API Key│
└────────┬────────┘
         │ Valid
         ▼
┌─────────────────────────────┐
│ Check delegation_enabled?   │
└────────┬───────────┬────────┘
         │ Yes       │ No
         ▼           ▼
┌─────────────────┐  ┌────────────────────┐
│ X-MCP-User-Email│  │ Use API Key perms  │
│ header present? │  │ (backward compat)  │
└────────┬───────────┼────────────────────┘
         │ Yes       │ No
         ▼           │
┌─────────────────┐  │
│ Validate domain │  │
│ restriction     │  │
└────────┬────────┘  │
         │ Pass      │ Fail → Track failure, reject
         ▼           │
┌─────────────────┐  │
│ Lookup user by  │  │
│ email           │  │
└────────┬────────┘  │
         │ Found     │ Not found → Track failure, reject
         ▼           │
┌─────────────────┐  │
│ Check user      │  │
│ active status   │  │
└────────┬────────┘  │
         │ Active    │ Inactive → Track failure, reject
         ▼           │
┌─────────────────┐  │
│ Compute         │  │
│ intersection    │◀─┘
│ permissions     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Execute tool    │
│ with effective  │
│ permissions     │
└─────────────────┘
```

## Relationships

```
┌──────────────┐       ┌──────────────┐
│  McpApiKey   │───────│    User      │
│              │ 1:N   │  (owner)     │
│ userId (FK)  │       │              │
│              │       │              │
│ delegationEnabled    │              │
│ allowedDomains       │              │
└──────────────┘       └──────────────┘
        │                      │
        │                      │
        │ (delegation lookup)  │
        │                      │
        ▼                      │
┌──────────────┐               │
│ McpAuditLog  │◀──────────────┘
│              │  (delegatedUserId)
│ apiKeyId(FK) │
│ userId(FK)   │
│ delegatedUserEmail
│ delegatedUserId
└──────────────┘
```

## Validation Rules

### McpApiKey Validation

1. **Delegation toggle**:
   - When `delegationEnabled` changes from `false` to `true`:
     - `allowedDelegationDomains` MUST be non-null and non-empty
   - When `delegationEnabled` is `false`:
     - `allowedDelegationDomains` MAY be null (ignored)

2. **Domain format**:
   - Each domain must match pattern: `@[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+`
   - Examples: `@company.com`, `@sub.company.co.uk`
   - Invalid: `company.com` (missing @), `@company` (no TLD)

3. **Domain list**:
   - Comma-separated, trimmed
   - Minimum 1 domain when delegation enabled
   - Maximum 10 domains (practical limit)

### Delegation Request Validation

1. **Email format**: Standard RFC 5322 email validation
2. **Domain match**: Case-insensitive suffix match against allowed domains
3. **User existence**: User must exist in `users` table with matching email
4. **User status**: User must be active (not disabled)

## Migration Notes

- New columns added with defaults ensure backward compatibility
- Existing API keys have `delegation_enabled = false` by default
- No data migration required; only schema extension
- Flyway migration script: `V050__mcp_user_delegation.sql`
