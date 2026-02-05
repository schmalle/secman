# Tasks: MCP E2E Test - User-Asset-Workgroup Workflow

**Input**: Design documents from `/specs/074-mcp-e2e-test/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Permission Infrastructure)

**Purpose**: Add the WORKGROUPS_WRITE permission that all new MCP tools will require

- [x] T001 [US2] Add `WORKGROUPS_WRITE` enum value to `src/backendng/src/main/kotlin/com/secman/domain/McpPermission.kt`

**Checkpoint**: Permission enum ready - MCP tool implementation can begin

---

## Phase 2: User Story 2 - MCP Tool Implementation (Priority: P1) 🎯 MVP

**Goal**: Implement 5 new MCP tools for workgroup management and asset deletion

**Independent Test**: Each tool can be tested via curl calls to `/api/mcp/tools/call`

### Implementation for User Story 2

- [x] T002 [P] [US2] Create `CreateWorkgroupTool.kt` in `src/backendng/src/main/kotlin/com/secman/mcp/tools/` implementing McpTool interface with WorkgroupService.createWorkgroup()
- [x] T003 [P] [US2] Create `DeleteWorkgroupTool.kt` in `src/backendng/src/main/kotlin/com/secman/mcp/tools/` implementing McpTool interface with WorkgroupService.deleteWorkgroup()
- [x] T004 [P] [US2] Create `AssignAssetsToWorkgroupTool.kt` in `src/backendng/src/main/kotlin/com/secman/mcp/tools/` implementing McpTool interface with WorkgroupService.assignAssetsToWorkgroup()
- [x] T005 [P] [US2] Create `AssignUsersToWorkgroupTool.kt` in `src/backendng/src/main/kotlin/com/secman/mcp/tools/` implementing McpTool interface with WorkgroupService.assignUsersToWorkgroup()
- [x] T006 [P] [US2] Create `DeleteAssetTool.kt` in `src/backendng/src/main/kotlin/com/secman/mcp/tools/` implementing McpTool interface with AssetCascadeDeleteService.deleteAsset()
- [x] T007 [US2] Register all 5 new tools in `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt` - inject tools and add to tools map
- [x] T008 [US2] Add authorization mappings for new tools in McpToolRegistry.isToolAuthorized() mapping tool names to WORKGROUPS_WRITE permission
- [x] T009 [US2] Verify backend compiles with `./gradlew build` from `src/backendng/`

**Checkpoint**: All 5 MCP tools are implemented and registered - can be tested via MCP protocol

---

## Phase 3: User Story 4 - MCP Key Generation UI (Priority: P1)

**Goal**: Update frontend to display WORKGROUPS_WRITE permission in MCP key generation

**Independent Test**: Navigate to MCP API Keys page and verify new permission checkbox appears

**Note**: Can run in parallel with Phase 2 (US2) after T001 completes

### Implementation for User Story 4

- [x] T010 [US4] Add `'WORKGROUPS_WRITE'` to `availablePermissions` array in `src/frontend/src/components/McpApiKeyManagement.tsx`
- [x] T011 [US4] Verify frontend builds with `npm run build` from `src/frontend/`

**Checkpoint**: UI displays new permission - users can grant workgroup management access to API keys

---

## Phase 4: User Story 1 + User Story 3 - E2E Test Script (Priority: P1/P2)

**Goal**: Create Bash test script with 1Password credential management that validates the complete workflow

**Independent Test**: Run `./tests/mcp-e2e-workgroup-test.sh` against local secman instance

**Dependencies**: Requires Phase 2 (US2) to be complete - test script calls the new MCP tools

### Implementation for User Stories 1 & 3

- [x] T012 [US1/US3] Create `tests/` directory at repository root if not exists
- [x] T013 [US1/US3] Create `tests/mcp-e2e-workgroup-test.sh` with shebang and `set -euo pipefail`
- [x] T014 [US3] Implement `check_prerequisites()` function - verify curl, jq, op CLI are available
- [x] T015 [US3] Implement `resolve_credentials()` function - use `op read` to resolve 1Password URIs from SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY environment variables
- [x] T016 [US1] Implement `authenticate()` function - POST to /api/auth/login and extract JWT token
- [x] T017 [US1] Implement `mcp_call()` helper function - curl wrapper for MCP tool calls with auth headers
- [x] T018 [US1] Implement `create_test_user()` function - call add_user MCP tool for TEST user with VULN role
- [x] T019 [US1] Implement `create_test_asset_with_vulnerability()` function - call add_vulnerability MCP tool (auto-creates asset)
- [x] T020 [US1] Implement `create_test_workgroup()` function - call create_workgroup MCP tool
- [x] T021 [US1] Implement `assign_asset_to_workgroup()` function - call assign_assets_to_workgroup MCP tool
- [x] T022 [US1] Implement `assign_user_to_workgroup()` function - call assign_users_to_workgroup MCP tool
- [x] T023 [US1] Implement `verify_access_as_test_user()` function - switch X-MCP-User-Email header to test@example.com and call get_assets, assert count equals 1
- [x] T024 [US1] Implement `cleanup()` function - call delete_workgroup, delete_asset, delete_user MCP tools in order
- [x] T025 [US1] Add `trap cleanup EXIT` for cleanup on failure
- [x] T026 [US1] Implement `main()` function orchestrating full workflow with progress output
- [x] T027 [US1/US3] Ensure no credential values are echoed to output (FR-017)
- [x] T028 [US1] Make script executable with `chmod +x tests/mcp-e2e-workgroup-test.sh`

**Checkpoint**: E2E test script complete - validates entire user-asset-workgroup workflow

---

## Phase 5: Polish & Validation

**Purpose**: Final verification and security review

- [x] T029 Verify full backend build passes: `./gradlew build` from `src/backendng/`
- [x] T030 [P] Verify frontend build passes: `npm run build` from `src/frontend/`
- [ ] T031 Run E2E test script against local instance and verify all assertions pass
- [x] T032 Security review: Verify authorization checks in all 5 new MCP tools
- [x] T033 Security review: Verify test script does not log credentials
- [x] T034 Update `docs/MCP.md` to document new workgroup management tools

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (T001)           ─┬─► Phase 2 (T002-T009) ─► Phase 4 (T012-T028) ─► Phase 5
                         │
                         └─► Phase 3 (T010-T011) ─────────────────────────►
```

- **Phase 1**: No dependencies - start immediately
- **Phase 2 (US2)**: Depends on T001 (permission enum)
- **Phase 3 (US4)**: Depends on T001 (permission enum) - can run parallel to Phase 2
- **Phase 4 (US1/US3)**: Depends on Phase 2 completion (needs MCP tools to exist)
- **Phase 5**: Depends on Phases 2, 3, and 4

### Parallel Opportunities

**After T001 completes**, launch in parallel:
- T002, T003, T004, T005, T006 (all MCP tool implementations - different files)
- T010 (frontend update - different codebase)

**Within Phase 4**, some test script functions can be developed in parallel but must be integrated sequentially.

---

## Implementation Strategy

### Recommended Order (Single Developer)

1. T001 → Permission enum
2. T002-T006 (parallel) → All 5 MCP tools
3. T007-T008 → Registry updates
4. T009 → Backend build verification
5. T010-T011 → Frontend update
6. T012-T028 → Test script
7. T029-T034 → Final validation

### Task Summary

| Phase | Tasks | Story | Description |
|-------|-------|-------|-------------|
| 1 | T001 | US2 | Permission infrastructure |
| 2 | T002-T009 | US2 | 5 MCP tools + registry |
| 3 | T010-T011 | US4 | Frontend UI update |
| 4 | T012-T028 | US1/US3 | E2E test script |
| 5 | T029-T034 | All | Validation & security |

**Total Tasks**: 34
**Completed**: 33
**Remaining**: 1 (T031: E2E test run - requires running backend)
