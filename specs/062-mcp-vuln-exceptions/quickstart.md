# Quickstart Guide: MCP Tools for Overdue Vulnerabilities and Exception Handling

**Feature**: 062-mcp-vuln-exceptions
**Date**: 2026-01-11

## Overview

This guide provides the implementation approach for adding 7 MCP tools that expose overdue asset querying and vulnerability exception handling.

## Prerequisites

- Existing services: `OutdatedAssetService`, `VulnerabilityExceptionRequestService`
- Existing entities: `VulnerabilityExceptionRequest`, `VulnerabilityException`, `OutdatedAssetMaterializedView`
- Existing DTOs: `VulnerabilityExceptionRequestDto`, `OutdatedAssetDto`, `CreateExceptionRequestDto`, `ReviewExceptionRequestDto`

## Implementation Sequence

### Phase 1: GetOverdueAssetsTool (US1 - P1)

**File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetOverdueAssetsTool.kt`

```kotlin
@Singleton
class GetOverdueAssetsTool(
    @Inject private val outdatedAssetService: OutdatedAssetService
) : McpTool {
    override val name = "get_overdue_assets"
    override val description = "Get assets with overdue vulnerabilities (ADMIN/VULN only)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "page" to mapOf("type" to "number", "description" to "Page number (default: 0)"),
            "size" to mapOf("type" to "number", "description" to "Page size (default: 20, max: 100)"),
            "minSeverity" to mapOf("type" to "string", "enum" to listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")),
            "searchTerm" to mapOf("type" to "string", "description" to "Search by asset name")
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // 1. Check delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled")
        }

        // 2. Check role (ADMIN or VULN)
        val hasRole = context.isAdmin || context.delegatedUserRoles?.contains("VULN") == true
        if (!hasRole) {
            return McpToolResult.error("ROLE_REQUIRED", "ADMIN or VULN role required")
        }

        // 3. Create Authentication adapter from context
        val authentication = createAuthenticationFromContext(context)

        // 4. Parse parameters and call service
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val size = ((arguments["size"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
        val minSeverity = arguments["minSeverity"] as? String
        val searchTerm = arguments["searchTerm"] as? String

        val pageable = Pageable.from(page, size)
        val result = outdatedAssetService.getOutdatedAssets(authentication, searchTerm, minSeverity, pageable)

        // 5. Return success with mapped DTOs
        return McpToolResult.success(mapOf(
            "assets" to result.content.map { OutdatedAssetDto.from(it) },
            "totalElements" to result.totalSize,
            "totalPages" to result.totalPages,
            "page" to page,
            "size" to size
        ))
    }

    private fun createAuthenticationFromContext(context: McpExecutionContext): Authentication {
        return object : Authentication {
            override fun getName() = context.delegatedUserEmail ?: "mcp-user"
            override fun getRoles() = context.delegatedUserRoles ?: emptySet()
            override fun getAttributes() = mapOf<String, Any>(
                "userId" to (context.delegatedUserId ?: 0L),
                "workgroupIds" to (context.accessibleWorkgroupIds?.toList() ?: emptyList<Long>())
            )
        }
    }
}
```

### Phase 2: CreateExceptionRequestTool (US2 - P1)

**File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateExceptionRequestTool.kt`

```kotlin
@Singleton
class CreateExceptionRequestTool(
    @Inject private val exceptionRequestService: VulnerabilityExceptionRequestService
) : McpTool {
    override val name = "create_exception_request"
    override val description = "Create a vulnerability exception request"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "required" to listOf("vulnerabilityId", "reason", "expirationDate"),
        "properties" to mapOf(
            "vulnerabilityId" to mapOf("type" to "number"),
            "reason" to mapOf("type" to "string", "minLength" to 50, "maxLength" to 2048),
            "expirationDate" to mapOf("type" to "string", "format" to "date-time"),
            "scope" to mapOf("type" to "string", "enum" to listOf("SINGLE_VULNERABILITY", "CVE_PATTERN"))
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled")
        }

        try {
            val dto = CreateExceptionRequestDto(
                vulnerabilityId = (arguments["vulnerabilityId"] as Number).toLong(),
                scope = ExceptionScope.valueOf(arguments["scope"] as? String ?: "SINGLE_VULNERABILITY"),
                reason = arguments["reason"] as String,
                expirationDate = LocalDateTime.parse(arguments["expirationDate"] as String)
            )

            val result = exceptionRequestService.createRequest(dto, context.delegatedUserId!!, null)

            val message = if (result.autoApproved) {
                "Exception request auto-approved (ADMIN/SECCHAMPION role)"
            } else {
                "Exception request created successfully. Status: PENDING"
            }

            return McpToolResult.success(mapOf("request" to result, "message" to message))
        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Validation failed")
        }
    }
}
```

### Phase 3-7: Remaining Tools

Follow the same pattern for:

3. **GetMyExceptionRequestsTool** → `exceptionRequestService.getUserRequests(userId, status, pageable)`
4. **GetPendingExceptionRequestsTool** → `exceptionRequestService.getPendingRequests(pageable)` (requires ADMIN/SECCHAMPION)
5. **ApproveExceptionRequestTool** → `exceptionRequestService.approveRequest(id, reviewerId, dto, clientIp)` (requires ADMIN/SECCHAMPION)
6. **RejectExceptionRequestTool** → `exceptionRequestService.rejectRequest(id, reviewerId, dto, clientIp)` (requires ADMIN/SECCHAMPION)
7. **CancelExceptionRequestTool** → `exceptionRequestService.cancelRequest(id, userId, clientIp)` (ownership check)

### Phase 8: Register Tools in McpToolRegistry

**File**: `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`

Add constructor injections:
```kotlin
@Inject private val getOverdueAssetsTool: GetOverdueAssetsTool,
@Inject private val createExceptionRequestTool: CreateExceptionRequestTool,
@Inject private val getMyExceptionRequestsTool: GetMyExceptionRequestsTool,
@Inject private val getPendingExceptionRequestsTool: GetPendingExceptionRequestsTool,
@Inject private val approveExceptionRequestTool: ApproveExceptionRequestTool,
@Inject private val rejectExceptionRequestTool: RejectExceptionRequestTool,
@Inject private val cancelExceptionRequestTool: CancelExceptionRequestTool,
```

Register in tools list and add authorization mappings in `isToolAuthorized()`.

### Phase 9: Update Documentation

**File**: `docs/MCP.md`

Add new tools to:
1. Permission Types table
2. Available MCP Tools section under "Vulnerability Management"
3. Usage Examples section

## Verification

```bash
# Build
./gradlew build

# Test via curl
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: sk-your-key" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "tools/call",
    "params": {
      "name": "get_overdue_assets",
      "arguments": {}
    }
  }'
```

## Error Handling Checklist

- [ ] DELEGATION_REQUIRED for all tools
- [ ] Role checks for admin-only tools
- [ ] NOT_FOUND for missing entities
- [ ] VALIDATION_ERROR for invalid input
- [ ] CONFLICT for duplicate requests
- [ ] INVALID_STATE for wrong status transitions
- [ ] CONCURRENT_MODIFICATION for optimistic lock failures
- [ ] FORBIDDEN for ownership violations
