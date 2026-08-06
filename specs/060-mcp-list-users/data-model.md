# Data Model: MCP List Users Tool

**Feature**: 060-mcp-list-users
**Date**: 2026-01-04

## Overview

No new entities or database changes required. This feature reads from the existing `User` entity and projects a subset of fields to the MCP response.

## Existing Entities Used

### User (Read-Only)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

| Field | Type | Exposed in Response | Notes |
|-------|------|---------------------|-------|
| id | Long | Yes | User identifier |
| username | String | Yes | Unique username |
| email | String | Yes | Unique email |
| passwordHash | String | **NO** | Security: Never exposed |
| roles | Set<Role> | Yes | USER, ADMIN, VULN, etc. |
| workgroups | Set<Workgroup> | No | Not needed per spec |
| mfaEnabled | Boolean | Yes | MFA status |
| authSource | AuthSource | Yes | LOCAL, OAUTH, HYBRID |
| createdAt | Instant | Yes | Account creation time |
| updatedAt | Instant | No | Not requested |
| lastLogin | Instant | Yes | May be null |

### McpExecutionContext (Read-Only)

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/mcp/McpExecutionContext.kt`

Used for authorization checks:
- `hasDelegation()`: Boolean - Must be true
- `isAdmin`: Boolean - Must be true
- `delegatedUserEmail`: String? - For logging/audit

## Response Structure

No new DTO required. Response is an inline map structure:

```kotlin
mapOf(
    "users" to users.map { user ->
        mapOf(
            "id" to user.id,
            "username" to user.username,
            "email" to user.email,
            "roles" to user.roles.map { it.name },
            "authSource" to user.authSource.name,
            "mfaEnabled" to user.mfaEnabled,
            "createdAt" to user.createdAt?.toString(),
            "lastLogin" to user.lastLogin?.toString()
        )
    },
    "totalCount" to users.size
)
```

## Error Response Codes

| Code | Condition | HTTP Equivalent |
|------|-----------|-----------------|
| DELEGATION_REQUIRED | `!context.hasDelegation()` | 401 Unauthorized |
| ADMIN_REQUIRED | `!context.isAdmin` | 403 Forbidden |
| EXECUTION_ERROR | Database/system failure | 500 Internal Error |

## Database Queries

Single query executed:

```sql
SELECT * FROM users
```

Via `UserRepository.findAll()` - returns all users with eager-loaded roles.

## No Schema Changes Required

- No new tables
- No new columns
- No migrations needed
