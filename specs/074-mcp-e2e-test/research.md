# Research: MCP E2E Test - User-Asset-Workgroup Workflow

**Feature Branch**: `074-mcp-e2e-test`
**Date**: 2026-02-04

## Research Questions Resolved

### 1. MCP Tool Implementation Pattern

**Decision**: Follow the existing McpTool interface pattern with @Singleton annotation and McpToolRegistry registration.

**Rationale**: 41 existing tools follow this pattern consistently. The registry uses lazy initialization with authorization mapping.

**Alternatives Considered**:
- Spring-style annotations: Rejected - project uses Micronaut DI
- Dynamic tool loading: Rejected - introduces complexity without benefit

**Key Implementation Pattern**:
```kotlin
@Singleton
class NewTool @Inject constructor(
    private val service: SomeService
) : McpTool {
    override val name = "tool_name"
    override val description = "Description (ADMIN role required)"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(...)

    override suspend fun execute(
        arguments: Map<String, Any>,
        context: McpExecutionContext
    ): McpToolResult {
        // Check delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "...")
        }
        // Check admin role
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "...")
        }
        // Input validation
        // Business logic
        // Return result
    }
}
```

### 2. Workgroup Service Availability

**Decision**: Reuse existing WorkgroupService methods directly from new MCP tools.

**Rationale**: WorkgroupService already provides all needed functionality:
- `createWorkgroup(name, description?, criticality?)`
- `deleteWorkgroup(id)` with cascade
- `assignUsersToWorkgroup(workgroupId, userIds)`
- `assignAssetsToWorkgroup(workgroupId, assetIds)`
- `listAllWorkgroups()`

**Alternatives Considered**:
- Create new methods: Rejected - existing methods are comprehensive
- Bypass service layer: Rejected - violates architecture principles

### 3. Asset Deletion Method

**Decision**: Use existing `AssetCascadeDeleteService.deleteAsset()` for single asset deletion.

**Rationale**: This method already handles:
- Pessimistic locking (prevents concurrent deletion)
- Cascade deletion (exception requests → exceptions → vulnerabilities → asset)
- Audit logging

**Alternatives Considered**:
- Direct repository delete: Rejected - skips cascade and audit
- New deletion method: Rejected - existing method is comprehensive

### 4. MCP Key Generation UI Structure

**Decision**: Add new permissions to `availablePermissions` array in `McpApiKeyManagement.tsx`.

**Rationale**: The UI uses a hardcoded array for permission checkboxes. New permissions must be added to this array for UI visibility.

**Key Findings**:
- Location: `/src/frontend/src/components/McpApiKeyManagement.tsx`
- Current permissions: REQUIREMENTS_READ/WRITE, ASSESSMENTS_READ/WRITE, ASSETS_READ, VULNERABILITIES_READ, SCANS_READ, TAGS_READ, SYSTEM_INFO, USER_ACTIVITY
- Permission checkboxes rendered in 2-column grid

**Note**: Tool list is NOT hardcoded in UI - tools are queried dynamically via `/api/mcp/capabilities`. Only permissions need UI update.

### 5. E2E Test Script Pattern

**Decision**: Follow existing E2E test pattern from `docs/E2E_EXCEPTION_WORKFLOW_TEST.md` using Bash with curl and jq.

**Rationale**:
- Aligns with clarification answer (Bash + curl + jq)
- Existing pattern proven in similar E2E tests
- Minimal dependencies (curl, jq, op CLI)

**Script Structure**:
```bash
#!/bin/bash
set -euo pipefail

# Prerequisites check
# 1Password credential resolution
# Authentication
# Test workflow execution
# Assertions
# Cleanup (with trap for failure handling)
```

### 6. McpPermission Enum Status

**Decision**: Add new permission `WORKGROUPS_WRITE` to McpPermission enum.

**Rationale**: Existing permissions don't cover workgroup management. Creating a dedicated permission follows the granular permission model.

**Location**: `/src/backendng/src/main/kotlin/com/secman/domain/McpPermission.kt`

## File Locations Summary

### Backend (New MCP Tools)
| File | Purpose |
|------|---------|
| `src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateWorkgroupTool.kt` | Create workgroup tool |
| `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteWorkgroupTool.kt` | Delete workgroup tool |
| `src/backendng/src/main/kotlin/com/secman/mcp/tools/AssignAssetsToWorkgroupTool.kt` | Assign assets tool |
| `src/backendng/src/main/kotlin/com/secman/mcp/tools/AssignUsersToWorkgroupTool.kt` | Assign users tool |
| `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteAssetTool.kt` | Delete single asset tool |
| `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt` | Tool registration (modify) |
| `src/backendng/src/main/kotlin/com/secman/domain/McpPermission.kt` | Permission enum (modify) |

### Existing Services (Reuse)
| File | Purpose |
|------|---------|
| `src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt` | Workgroup business logic |
| `src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt` | Asset deletion with cascade |

### Frontend (UI Update)
| File | Purpose |
|------|---------|
| `src/frontend/src/components/McpApiKeyManagement.tsx` | Add WORKGROUPS_WRITE permission |

### Test Script
| File | Purpose |
|------|---------|
| `tests/mcp-e2e-workgroup-test.sh` | E2E test script |

## Dependencies

### Backend Dependencies (Already Available)
- Micronaut 4.10 - DI framework
- Hibernate JPA - ORM
- Kotlin coroutines - Async execution

### Test Script Dependencies
- `curl` - HTTP client
- `jq` - JSON parser
- `op` - 1Password CLI v2.x

## Authorization Model

### Permission Hierarchy
```
WORKGROUPS_WRITE (new) - Grants access to:
  - create_workgroup
  - delete_workgroup
  - assign_assets_to_workgroup
  - assign_users_to_workgroup

ASSETS_READ (existing) + context check - Grants access to:
  - delete_asset (with isAdmin check)
```

### Access Control Flow
1. API key must have required permission
2. User delegation must be active for ADMIN operations
3. Delegated user must have ADMIN role
4. Context authorization check in each tool
