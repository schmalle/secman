# Research: MCP and CLI User Mapping Upload

**Feature**: 064-mcp-cli-user-mapping
**Date**: 2026-01-19

## Research Findings

### 1. Existing MCP Tool Patterns

**Decision**: Follow established `McpTool` interface pattern with `McpExecutionContext`

**Rationale**:
- All existing MCP tools implement `McpTool` interface
- Pattern includes: `name`, `description`, `operation`, `inputSchema`, `execute()` method
- User Delegation and role checks are done within `execute()` method
- Results returned via `McpToolResult.success()` or `McpToolResult.error()`

**Alternatives considered**:
- Creating a new interface - Rejected: violates consistency with existing tools
- Using REST endpoints directly - Rejected: MCP requires tool-based interface

**Reference**: `ListUsersTool.kt`, `AddUserTool.kt` (Feature 060)

### 2. User Mapping Service Reuse

**Decision**: Reuse `UserMappingCliService` for MCP tool implementation

**Rationale**:
- Service already implements all required functionality:
  - `listMappings()` with email/status filtering
  - Import validation (email, domain, AWS account ID format)
  - Duplicate detection
  - Pending mapping support (future user mappings)
- Avoids code duplication
- Ensures consistent validation rules between CLI and MCP

**Alternatives considered**:
- Creating new `UserMappingMcpService` - Rejected: duplicates existing logic
- Using `UserMappingImportService` directly - Rejected: only handles Excel import, not JSON arrays

### 3. MCP Permission Mapping

**Decision**: Use `McpPermission.USER_ACTIVITY` for both tools

**Rationale**:
- Follows pattern of `list_users`, `add_user`, `delete_user` tools
- User mappings are administrative data related to user management
- ADMIN role check happens inside tool `execute()` method

**Alternatives considered**:
- New `McpPermission.USER_MAPPINGS_READ/WRITE` - Rejected: over-engineering for two tools
- `McpPermission.ASSETS_READ` - Rejected: mappings are user data, not asset data

### 4. MCP Input Schema Design

**Decision**: Accept array of mapping objects with optional dry-run flag

**Rationale**:
- Matches existing import file format (JSON array structure)
- Each entry has: `email` (required), `awsAccountId` (optional), `domain` (optional)
- Dry-run flag enables validation without persistence
- Maximum 1000 entries per call (prevents timeout, matches performance goal)

**Schema**:
```json
{
  "type": "object",
  "properties": {
    "mappings": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "email": { "type": "string" },
          "awsAccountId": { "type": "string" },
          "domain": { "type": "string" }
        },
        "required": ["email"]
      },
      "maxItems": 1000
    },
    "dryRun": { "type": "boolean", "default": false }
  },
  "required": ["mappings"]
}
```

### 5. CLI List Format Option

**Decision**: Add `--format` option supporting `table` (default) and `json` output

**Rationale**:
- `table` format for human readability (existing behavior)
- `json` format for scripting and automation (piping to jq, etc.)
- Matches common CLI patterns (kubectl, aws cli, gh)

**Alternatives considered**:
- `--output` flag name - Rejected: `--format` is more descriptive
- Additional formats (csv, yaml) - Deferred: table and json cover primary use cases

### 6. Documentation Location

**Decision**:
- MCP tools: Update existing `docs/MCP_TOOLS.md`
- CLI commands: Create new `docs/CLI_USER_MAPPINGS.md`

**Rationale**:
- MCP tools documentation already exists and follows consistent format
- CLI commands warrant dedicated documentation with file format examples
- Keeps related information grouped together

## Resolved Items

| Item | Resolution |
|------|------------|
| MCP tool interface | Use `McpTool` with `McpExecutionContext` |
| Service layer | Reuse `UserMappingCliService` |
| Permission model | `USER_ACTIVITY` with ADMIN role check |
| Batch size limit | 1000 mappings per MCP call |
| CLI output formats | `table` (default) and `json` |
| Documentation | MCP_TOOLS.md + CLI_USER_MAPPINGS.md |
