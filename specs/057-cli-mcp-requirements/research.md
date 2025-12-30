# Research: CLI and MCP Requirements Management

**Feature**: 057-cli-mcp-requirements
**Date**: 2025-12-29
**Status**: Complete

## Overview

Research into existing patterns and dependencies for implementing requirements management in CLI and MCP interfaces.

---

## 1. Existing Backend Endpoints

### Decision: Reuse existing RequirementController endpoints

**Rationale**: The backend already exposes all required functionality through REST endpoints. Creating CLI and MCP wrappers avoids code duplication and ensures consistency.

**Existing Endpoints Identified**:

| Endpoint | Method | Purpose | Auth |
|----------|--------|---------|------|
| `/api/requirements/export/xlsx` | GET | Export to Excel | USER+ |
| `/api/requirements/export/docx` | GET | Export to Word | USER+ |
| `/api/requirements` | POST | Create requirement | ADMIN/REQ |
| `/api/requirements/all` | DELETE | Delete all | ADMIN |

**Alternatives Considered**:
- Creating new dedicated CLI endpoints - Rejected: Unnecessary duplication
- Direct database access from CLI - Rejected: Bypasses validation and security

---

## 2. CLI Implementation Pattern

### Decision: Use Picocli with Micronaut DI

**Rationale**: Existing CLI commands (AddVulnerabilityCommand, ManageWorkgroupsCommand) use this pattern successfully. Provides dependency injection, parameter validation, and help generation.

**Pattern Reference**: `AddVulnerabilityCommand.kt`

```kotlin
@Command(name = "command-name", mixinStandardHelpOptions = true)
class CommandName : Runnable {
    @Inject lateinit var service: ServiceClass
    @Option(names = ["--param"], required = true) lateinit var param: String
    override fun run() { /* implementation */ }
}
```

**Alternatives Considered**:
- Manual argument parsing in SecmanCli.kt - Rejected: Already being migrated to Picocli
- Separate CLI application - Rejected: Existing infrastructure works well

---

## 3. MCP Tool Implementation Pattern

### Decision: Follow existing McpTool interface pattern

**Rationale**: All existing MCP tools implement the same interface and registration pattern. Consistent architecture enables automatic discovery and permission management.

**Pattern Reference**: `GetRequirementsTool.kt`

```kotlin
@Singleton
class NewTool(@Inject private val service: Service) : McpTool {
    override val name = "tool_name"
    override val description = "Tool description"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(...)
    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult
}
```

**Registration**: Add to `McpToolRegistry` constructor injection and `listOf()` registration.

**Alternatives Considered**:
- New MCP endpoint structure - Rejected: Would break existing MCP clients
- Direct REST call forwarding - Rejected: Loses MCP context and permissions

---

## 4. File Export Handling

### Decision: Server-side generation with binary transfer

**Rationale**: Backend already generates Excel/Word files using Apache POI. CLI downloads binary content and saves locally. MCP returns base64-encoded content.

**CLI Flow**:
1. Authenticate with backend
2. GET `/api/requirements/export/{format}` with auth header
3. Save response body to file
4. Report success with file path and size

**MCP Flow**:
1. Call existing service method to generate byte array
2. Base64 encode for JSON transport
3. Return with filename and content-type metadata

**Alternatives Considered**:
- Generate files locally in CLI - Rejected: Would require POI dependency in CLI module
- Stream large files - Deferred: Current implementation handles 10,000+ requirements

---

## 5. Authentication Pattern

### Decision: Reuse existing CLI auth pattern

**Rationale**: AddVulnerabilityCommand already implements username/password authentication with environment variable fallback. Same pattern ensures consistent UX.

**Pattern**:
```kotlin
val username = cliUsername ?: System.getenv("SECMAN_USERNAME") ?: error
val password = cliPassword ?: System.getenv("SECMAN_PASSWORD") ?: error
val token = authenticate(username, password, backendUrl)
```

**MCP Authentication**: Already handled by McpExecutionContext with API key permissions.

---

## 6. Permission Mapping

### Decision: Map CLI roles to MCP permissions

| Operation | CLI Roles | MCP Permission |
|-----------|-----------|----------------|
| Export | ADMIN, REQ, SECCHAMPION | REQUIREMENTS_READ |
| Add | ADMIN, REQ, SECCHAMPION | REQUIREMENTS_WRITE |
| Delete All | ADMIN only | REQUIREMENTS_WRITE + ADMIN role check |

**Note**: Delete all requires explicit `--confirm` flag (CLI) or `confirm: true` parameter (MCP) as safety measure.

---

## 7. Error Handling

### Decision: Consistent error reporting pattern

**CLI Errors**:
- Authentication failure → exit code 1, stderr message
- Authorization failure → exit code 1, "Insufficient permissions" message
- Network error → exit code 1, connection error details
- Validation error → exit code 1, specific field errors

**MCP Errors**:
- Return `McpToolResult.error(code, message)` with specific error codes:
  - `UNAUTHORIZED` - Authentication/permission issue
  - `VALIDATION_ERROR` - Input validation failed
  - `EXECUTION_ERROR` - Operation failed

---

## Summary

All research items resolved. Implementation follows established patterns with no new dependencies required:

- CLI: Picocli commands calling existing backend endpoints
- MCP: Tools implementing McpTool interface
- No schema changes
- No new backend endpoints
- No new external dependencies
