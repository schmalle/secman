# Research: E2E Vulnerability Exception Workflow Test Suite

**Feature**: 063-e2e-vuln-exception
**Date**: 2026-01-14

## Research Summary

All technical unknowns have been resolved through codebase exploration.

---

## Finding 1: Existing MCP Tool Patterns

**Decision**: Follow existing MCP tool implementation patterns in `mcp/tools/`

**Rationale**: The codebase has 25+ existing MCP tools with consistent patterns:
- Singleton classes with `@Inject` dependencies
- Tool definition via `ToolDefinition` data class
- Parameter validation via JSON schema
- Role-based access via `McpAccessControlService`
- User delegation via `X-MCP-User-Email` header

**Alternatives Considered**:
- Custom tool framework: Rejected - would diverge from existing patterns
- REST-only approach: Rejected - MCP tools provide better Claude integration

**Reference Files**:
- `src/backendng/src/main/kotlin/com/secman/mcp/tools/AddUserTool.kt`
- `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteAllRequirementsTool.kt`

---

## Finding 2: Vulnerability Service Integration

**Decision**: Use existing `VulnerabilityService.addVulnerabilityFromCli()` for the new MCP tool

**Rationale**:
- Method already supports all required parameters (hostname, cve, criticality, daysOpen)
- Auto-creates asset if not exists
- Handles duplicate CVE updates
- Well-tested via existing CLI command

**Alternatives Considered**:
- Create new service method: Rejected - existing method is sufficient
- Direct repository access: Rejected - would bypass business logic

**Reference Files**:
- `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityService.kt`
- `src/backendng/src/main/kotlin/com/secman/dto/AddVulnerabilityRequestDto.kt`

---

## Finding 3: Asset Bulk Delete Integration

**Decision**: Use existing `AssetBulkDeleteService.deleteAllAssets()` for the new MCP tool

**Rationale**:
- Service already handles cascade deletion (vulnerabilities, scan results, ports)
- Implements concurrent operation protection (semaphore)
- Returns detailed deletion counts
- Used by existing REST endpoint `DELETE /api/assets/bulk`

**Alternatives Considered**:
- Iterate and delete individually: Rejected - inefficient, no atomicity
- New service method: Rejected - existing method is comprehensive

**Reference Files**:
- `src/backendng/src/main/kotlin/com/secman/service/AssetBulkDeleteService.kt`

---

## Finding 4: Overdue Threshold Configuration

**Decision**: Use existing 30-day threshold for CRITICAL severity

**Rationale**:
- Verified in `VulnerabilityConfigService.kt` - default is 30 days
- Configurable via database but 30 days is standard
- Test case (40 days) will clearly exceed threshold

**Alternatives Considered**:
- Make threshold configurable in test: Rejected - adds complexity, 30 days is reliable
- Use different severity: Rejected - CRITICAL with 30-day threshold is well-defined

**Reference Files**:
- `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityConfigService.kt`

---

## Finding 5: Exception Request Workflow

**Decision**: Use existing exception request MCP tools (no new implementation needed)

**Rationale**: Complete workflow already implemented:
- `create_exception_request` - with auto-approval for ADMIN/SECCHAMPION
- `get_my_exception_requests` - user's own requests
- `get_pending_exception_requests` - for approvers
- `approve_exception_request` - with optional comment
- `reject_exception_request` - with required comment
- `cancel_exception_request` - requester only

**Reference Files**:
- `src/backendng/src/main/kotlin/com/secman/mcp/tools/` (exception request tools)
- `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityExceptionService.kt`

---

## Finding 6: User Management via MCP

**Decision**: Use existing `add_user` and `delete_user` MCP tools

**Rationale**:
- `add_user` creates user with roles, password hashing, event publishing
- `delete_user` validates references, prevents self-deletion
- Both require ADMIN role via User Delegation

**Reference Files**:
- `src/backendng/src/main/kotlin/com/secman/mcp/tools/AddUserTool.kt`
- `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteUserTool.kt`

---

## Finding 7: Test Script Technology

**Decision**: Bash script with curl and jq for MCP calls

**Rationale**:
- Portable across Linux/macOS
- No additional dependencies (curl/jq standard)
- Easy to debug and modify
- Matches existing `bin/` scripts pattern

**Alternatives Considered**:
- Python script: Rejected - adds dependency, Bash is simpler for sequential calls
- Node.js script: Rejected - adds dependency, overkill for this use case
- Integration test: Rejected - user explicitly requested CLI script

---

## Unresolved Items

None. All technical unknowns resolved.
