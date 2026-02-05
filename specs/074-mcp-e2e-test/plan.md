# Implementation Plan: MCP E2E Test - User-Asset-Workgroup Workflow

**Branch**: `074-mcp-e2e-test` | **Date**: 2026-02-04 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/074-mcp-e2e-test/spec.md`

## Summary

Implement 5 new MCP tools for workgroup management (`create_workgroup`, `delete_workgroup`, `assign_assets_to_workgroup`, `assign_users_to_workgroup`, `delete_asset`), update the MCP key generation UI to display the new WORKGROUPS_WRITE permission, and create an E2E Bash test script that validates the complete user-asset-workgroup access control workflow using 1Password for credential management.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25 (backend), Bash (test script), TypeScript/React 19 (frontend)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, curl, jq, 1Password CLI v2.x
**Storage**: MariaDB 11.4 (existing tables: users, assets, vulnerabilities, workgroups, user_workgroups, asset_workgroups)
**Testing**: E2E Bash script with assertions, manual verification for UI
**Target Platform**: Linux/macOS server (localhost for test script)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Test script completes in under 2 minutes
**Constraints**: No credential leakage in logs, proper authorization checks
**Scale/Scope**: Single E2E test scenario, 5 new MCP tools, 1 UI permission addition

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | Authorization checks in all tools, no credential logging, security review required |
| III. API-First | ✅ PASS | MCP tools follow existing JSON-RPC patterns, contracts defined |
| IV. User-Requested Testing | ✅ PASS | Test script is explicitly requested in spec requirements |
| V. RBAC | ✅ PASS | All tools require ADMIN role via delegation, @Secured on MCP endpoint |
| VI. Schema Evolution | ✅ PASS | No schema changes - only new enum value in McpPermission |

**Gate Result**: PASS - No violations detected.

## Project Structure

### Documentation (this feature)

```text
specs/074-mcp-e2e-test/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 output - MCP patterns research
├── data-model.md        # Phase 1 output - Entity documentation
├── quickstart.md        # Phase 1 output - Manual testing guide
├── contracts/           # Phase 1 output
│   └── mcp-tools.yaml   # MCP tool contracts
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── mcp/
│   ├── tools/
│   │   ├── CreateWorkgroupTool.kt      # NEW: Create workgroup MCP tool
│   │   ├── DeleteWorkgroupTool.kt      # NEW: Delete workgroup MCP tool
│   │   ├── AssignAssetsToWorkgroupTool.kt    # NEW: Assign assets MCP tool
│   │   ├── AssignUsersToWorkgroupTool.kt     # NEW: Assign users MCP tool
│   │   └── DeleteAssetTool.kt          # NEW: Delete single asset MCP tool
│   └── McpToolRegistry.kt              # MODIFY: Register new tools
├── domain/
│   └── McpPermission.kt                # MODIFY: Add WORKGROUPS_WRITE
└── service/
    ├── WorkgroupService.kt             # EXISTING: Reuse methods
    └── AssetCascadeDeleteService.kt    # EXISTING: Reuse for asset deletion

src/frontend/src/components/
└── McpApiKeyManagement.tsx             # MODIFY: Add WORKGROUPS_WRITE permission

tests/
└── mcp-e2e-workgroup-test.sh           # NEW: E2E test script
```

**Structure Decision**: Web application pattern - backend Kotlin services with React frontend. Test script in repository-root `tests/` folder as specified in FR-015.

## Implementation Components

### Component 1: New MCP Tools (Backend)

**Files to Create**:
1. `CreateWorkgroupTool.kt` - Implements McpTool interface, calls WorkgroupService.createWorkgroup()
2. `DeleteWorkgroupTool.kt` - Implements McpTool interface, calls WorkgroupService.deleteWorkgroup()
3. `AssignAssetsToWorkgroupTool.kt` - Implements McpTool interface, calls WorkgroupService.assignAssetsToWorkgroup()
4. `AssignUsersToWorkgroupTool.kt` - Implements McpTool interface, calls WorkgroupService.assignUsersToWorkgroup()
5. `DeleteAssetTool.kt` - Implements McpTool interface, calls AssetCascadeDeleteService.deleteAsset()

**Pattern to Follow**: See research.md for detailed McpTool implementation pattern including authorization checks.

### Component 2: Permission & Registry Updates (Backend)

**Files to Modify**:
1. `McpPermission.kt` - Add `WORKGROUPS_WRITE` enum value
2. `McpToolRegistry.kt` - Inject and register all 5 new tools, add authorization mapping

### Component 3: Frontend UI Update

**Files to Modify**:
1. `McpApiKeyManagement.tsx` - Add `'WORKGROUPS_WRITE'` to `availablePermissions` array

### Component 4: E2E Test Script

**File to Create**:
- `tests/mcp-e2e-workgroup-test.sh`

**Script Structure**:
```bash
#!/bin/bash
set -euo pipefail

# Trap for cleanup on failure
trap cleanup EXIT

# Functions
check_prerequisites()
resolve_credentials()
authenticate()
create_test_user()
create_test_asset_with_vulnerability()
create_test_workgroup()
assign_asset_to_workgroup()
assign_user_to_workgroup()
verify_access_as_test_user()
cleanup()

# Main execution
main()
```

## Dependencies

### Build Dependencies (Existing)
- Micronaut 4.10 DI framework
- Hibernate JPA ORM
- Kotlin coroutines

### Runtime Dependencies (Test Script)
- curl (HTTP client)
- jq (JSON parser)
- op (1Password CLI v2.x)

### Service Dependencies
- WorkgroupService (existing)
- AssetCascadeDeleteService (existing)
- UserService (existing, via AddUserTool/DeleteUserTool)

## Security Considerations

1. **Authorization**: All new tools require ADMIN role via user delegation
2. **Credential Protection**: Test script uses 1Password CLI, never echoes secrets
3. **Input Validation**: All tool inputs validated against schema before processing
4. **Audit Trail**: Operations logged via existing audit infrastructure
5. **Access Control**: Workgroup assignment respects RBAC model

## Complexity Tracking

> No complexity violations detected. All implementations follow existing patterns.

| Aspect | Approach | Justification |
|--------|----------|---------------|
| 5 new MCP tools | Standard McpTool pattern | Consistent with 41 existing tools |
| 1 enum addition | McpPermission extension | Follows existing permission model |
| 1 UI array entry | Simple array addition | Minimal change, no new components |
| Bash test script | curl + jq | Aligns with existing E2E patterns |

## Phase 1 Artifacts Generated

- [x] research.md - MCP patterns and implementation details
- [x] data-model.md - Entity documentation and relationships
- [x] contracts/mcp-tools.yaml - MCP tool contracts
- [x] quickstart.md - Manual testing guide

## Next Steps

Run `/speckit.tasks` to generate the implementation task list.
