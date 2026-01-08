# Quickstart: MCP List Users Tool

**Feature**: 060-mcp-list-users

## Overview

Add a new MCP tool `list_users` that returns all secman users. Requires User Delegation with ADMIN role.

## Files to Create

### 1. ListUsersTool.kt

**Path**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/ListUsersTool.kt`

```kotlin
package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class ListUsersTool(
    @Inject private val userRepository: UserRepository
) : McpTool {

    override val name = "list_users"
    override val description = "List all users in the system (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // FR-002, FR-004: Require User Delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // FR-003, FR-005: Require ADMIN role
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to list users"
            )
        }

        try {
            val users = userRepository.findAll()

            // FR-006, FR-007, FR-008: Return user data (exclude passwordHash)
            val result = mapOf(
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
                // FR-009: Include total count in metadata
                "totalCount" to users.size
            )

            return McpToolResult.success(result)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve users: ${e.message}")
        }
    }
}
```

## Files to Modify

### 2. McpToolRegistry.kt

**Path**: `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`

Add injection and registration:

```kotlin
// Add to constructor parameters:
@Inject private val listUsersTool: ListUsersTool

// Add to tools list in lazy block:
listUsersTool

// Add to isToolAuthorized function:
"list_users" -> {
    permissions.contains(McpPermission.USER_ACTIVITY)
}
```

## Build & Verify

```bash
# Build backend
./gradlew build

# Verify tool appears in MCP listing (manual test with MCP client)
```

## Usage Example

```json
// MCP Request (with User Delegation header for admin user)
{
  "tool": "list_users",
  "arguments": {}
}

// Response
{
  "users": [
    {
      "id": 1,
      "username": "admin",
      "email": "admin@example.com",
      "roles": ["ADMIN", "USER"],
      "authSource": "LOCAL",
      "mfaEnabled": false,
      "createdAt": "2024-01-01T00:00:00Z",
      "lastLogin": "2026-01-04T10:30:00Z"
    }
  ],
  "totalCount": 1
}
```

## Error Scenarios

| Scenario | Error Code | Message |
|----------|------------|---------|
| No User Delegation | DELEGATION_REQUIRED | User Delegation must be enabled to use this tool |
| Non-admin user | ADMIN_REQUIRED | ADMIN role required to list users |
| Database error | EXECUTION_ERROR | Failed to retrieve users: {details} |
