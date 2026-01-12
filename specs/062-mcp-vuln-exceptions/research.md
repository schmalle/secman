# Research: MCP Tools for Overdue Vulnerabilities and Exception Handling

**Feature**: 062-mcp-vuln-exceptions
**Date**: 2026-01-11

## Research Tasks

### 1. MCP Tool Implementation Pattern

**Decision**: Follow established `McpTool` interface pattern

**Rationale**: Existing MCP tools (`ListUsersTool`, `GetAssetMostVulnerabilitiesTool`, etc.) demonstrate a consistent pattern that should be followed.

**Pattern Analysis**:
```kotlin
@Singleton
class MyTool(
    @Inject private val myService: MyService
) : McpTool {
    override val name = "tool_name"
    override val description = "Human-readable description"
    override val operation = McpOperation.READ  // or WRITE
    override val inputSchema = mapOf(...)

    override suspend fun execute(
        arguments: Map<String, Any>,
        context: McpExecutionContext
    ): McpToolResult {
        // 1. Check delegation if required
        // 2. Check roles if required
        // 3. Call service layer
        // 4. Return McpToolResult.success() or McpToolResult.error()
    }
}
```

**Alternatives Considered**: None - pattern is well-established in codebase.

---

### 2. Access Control Approach for MCP Tools

**Decision**: Use `McpExecutionContext` properties for all authorization checks

**Rationale**: `McpExecutionContext` provides:
- `hasDelegation()` - Check if User Delegation is active
- `isAdmin` - Quick check for ADMIN role
- `delegatedUserRoles` - Set of role names for detailed checks
- `delegatedUserId` - User ID for ownership checks
- `delegatedUserEmail` - Email for audit logging

**Role Check Patterns**:
```kotlin
// ADMIN-only tool
if (!context.isAdmin) {
    return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required")
}

// ADMIN or SECCHAMPION
val hasApprovalRole = context.delegatedUserRoles?.any {
    it == "ADMIN" || it == "SECCHAMPION"
} == true
if (!hasApprovalRole) {
    return McpToolResult.error("APPROVAL_ROLE_REQUIRED", "ADMIN or SECCHAMPION role required")
}

// ADMIN or VULN
val hasVulnRole = context.isAdmin || context.delegatedUserRoles?.contains("VULN") == true
```

**Alternatives Considered**:
- Creating Micronaut `Authentication` objects to pass to services - rejected due to complexity and service methods already having userId parameters.

---

### 3. Service Layer Integration

**Decision**: Delegate to existing services, adapting parameters from MCP context

**Services to use**:

| Tool | Service | Method(s) |
|------|---------|-----------|
| `get_overdue_assets` | `OutdatedAssetService` | `getOutdatedAssets()`, Need to adapt context to Authentication |
| `create_exception_request` | `VulnerabilityExceptionRequestService` | `createRequest(dto, userId, clientIp)` |
| `get_my_exception_requests` | `VulnerabilityExceptionRequestService` | `getUserRequests(userId, status, pageable)` |
| `get_pending_exception_requests` | `VulnerabilityExceptionRequestService` | `getPendingRequests(pageable)` |
| `approve_exception_request` | `VulnerabilityExceptionRequestService` | `approveRequest(id, reviewerId, dto, clientIp)` |
| `reject_exception_request` | `VulnerabilityExceptionRequestService` | `rejectRequest(id, reviewerId, dto, clientIp)` |
| `cancel_exception_request` | `VulnerabilityExceptionRequestService` | `cancelRequest(id, userId, clientIp)` |

**Rationale**: Services contain all business logic, validation, and audit logging. MCP tools should be thin wrappers.

**Challenge**: `OutdatedAssetService.getOutdatedAssets()` expects `Authentication` object, but MCP provides `McpExecutionContext`.

**Solution**: Create an adapter method or use repository directly with workgroup filtering from context. The service uses `authentication.roles` and `authentication.attributes["workgroupIds"]` - we can query the repository directly with the same filtering logic.

---

### 4. OutdatedAssetService Authentication Adaptation

**Decision**: Create a simple `Authentication` implementation within the MCP tool for `OutdatedAssetService` calls

**Rationale**: `OutdatedAssetService.getOutdatedAssets()` requires:
- `authentication.roles` - Set of role names
- `authentication.attributes["workgroupIds"]` - List of accessible workgroup IDs

**Implementation Approach**:
```kotlin
// Create minimal Authentication from McpExecutionContext
val authentication = object : io.micronaut.security.authentication.Authentication {
    override fun getName(): String = context.delegatedUserEmail ?: "mcp-user"
    override fun getRoles(): Collection<String> = context.delegatedUserRoles ?: emptySet()
    override fun getAttributes(): Map<String, Any> = mapOf(
        "userId" to (context.delegatedUserId ?: 0L),
        "workgroupIds" to (context.accessibleWorkgroupIds?.toList() ?: emptyList<Long>())
    )
}
```

**Alternatives Considered**:
- Duplicating the service logic in the MCP tool - rejected to maintain single source of truth
- Modifying `OutdatedAssetService` to accept alternative parameters - rejected to avoid changes to working code

---

### 5. Error Handling Patterns

**Decision**: Use consistent error codes matching existing MCP tools

**Error Code Mapping**:
| Scenario | Code | Message Pattern |
|----------|------|-----------------|
| Missing User Delegation | `DELEGATION_REQUIRED` | "User Delegation must be enabled to use this tool" |
| Missing required role | `{ROLE}_REQUIRED` | "{ROLE} role required to [action]" |
| Entity not found | `NOT_FOUND` | "{Entity} with ID {id} not found" |
| Validation failure | `VALIDATION_ERROR` | "{Field}: {message}" |
| Duplicate/conflict | `CONFLICT` | "Already exists: {details}" |
| Invalid state transition | `INVALID_STATE` | "Cannot {action} request in {status} status" |
| Concurrent modification | `CONCURRENT_MODIFICATION` | "Request was already reviewed by {username}" |
| Execution error | `EXECUTION_ERROR` | "Failed to {action}: {message}" |

**Rationale**: Consistent error codes enable AI assistants to provide meaningful feedback to users.

---

### 6. MCP Permission Mapping

**Decision**: Use existing permissions where possible, adding new ones only if necessary

**Mapping Analysis**:

| Tool | Required Role | MCP Permission | Existing? |
|------|---------------|----------------|-----------|
| `get_overdue_assets` | ADMIN or VULN | `VULNERABILITIES_READ` | Yes |
| `create_exception_request` | Any authenticated | `VULNERABILITIES_READ` | Yes |
| `get_my_exception_requests` | Any authenticated | `VULNERABILITIES_READ` | Yes |
| `get_pending_exception_requests` | ADMIN or SECCHAMPION | `VULNERABILITIES_READ` | Yes |
| `approve_exception_request` | ADMIN or SECCHAMPION | `VULNERABILITIES_READ` | Yes (write check done in execute) |
| `reject_exception_request` | ADMIN or SECCHAMPION | `VULNERABILITIES_READ` | Yes (write check done in execute) |
| `cancel_exception_request` | Original requester | `VULNERABILITIES_READ` | Yes |

**Rationale**: All exception handling relates to vulnerabilities, so `VULNERABILITIES_READ` permission is appropriate. Role-based authorization is enforced within each tool's `execute()` method using `McpExecutionContext`, not at the permission level. This matches the pattern used by `list_users` tool (requires `USER_ACTIVITY` permission but checks `isAdmin` in execute).

---

### 7. Pagination Support

**Decision**: Support pagination via `page` and `size` parameters for list operations

**Pattern**:
```kotlin
val page = (arguments["page"] as? Number)?.toInt() ?: 0
val size = ((arguments["size"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
val pageable = Pageable.from(page, size)
```

**Applied to**:
- `get_overdue_assets`
- `get_my_exception_requests`
- `get_pending_exception_requests`

**Rationale**: Pagination prevents large result sets from overwhelming AI context windows.

---

## Summary

All research items resolved. Implementation can proceed with:
1. Thin MCP tool wrappers delegating to existing services
2. `McpExecutionContext` for all authorization checks
3. Consistent error handling with defined codes
4. Pagination support for list operations
5. Authentication adapter for `OutdatedAssetService` integration
