# Tasks: MCP and CLI User Mapping Upload

**Input**: Design documents from `/specs/064-mcp-cli-user-mapping/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Not requested - following constitution principle IV (User-Requested Testing)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **CLI**: `src/cli/src/main/kotlin/com/secman/cli/`
- **Docs**: `docs/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify existing infrastructure is sufficient for this feature

- [ ] T001 Verify existing `UserMappingCliService` has required import/list functionality in `src/cli/src/main/kotlin/com/secman/cli/service/UserMappingCliService.kt`
- [ ] T002 Verify `UserMappingRepository` supports required queries in `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Add pagination support needed by MCP list tool

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T003 Add pagination query methods to `UserMappingRepository` in `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt`

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - MCP Bulk Import User Mappings (Priority: P1) 🎯 MVP

**Goal**: Enable programmatic import of user mappings via MCP interface with full validation and dry-run support

**Independent Test**: Call `import_user_mappings` MCP tool with test mapping entries and verify import results

### Implementation for User Story 1

- [ ] T004 [US1] Create `ImportUserMappingsTool.kt` implementing `McpTool` interface in `src/backendng/src/main/kotlin/com/secman/mcp/tools/ImportUserMappingsTool.kt`
- [ ] T005 [US1] Implement input schema per `contracts/mcp-tools.md` (mappings array, dryRun flag) in `ImportUserMappingsTool.kt`
- [ ] T006 [US1] Implement User Delegation and ADMIN role checks in `ImportUserMappingsTool.execute()`
- [ ] T007 [US1] Implement mapping validation (email, awsAccountId, domain formats) in `ImportUserMappingsTool.kt`
- [ ] T008 [US1] Implement dry-run mode that validates without persisting in `ImportUserMappingsTool.kt`
- [ ] T009 [US1] Implement import logic reusing `UserMappingCliService.createMapping()` pattern in `ImportUserMappingsTool.kt`
- [ ] T010 [US1] Return detailed results (totalProcessed, created, createdPending, skipped, errors) in `ImportUserMappingsTool.kt`
- [ ] T011 [US1] Register `ImportUserMappingsTool` in `McpToolRegistry` in `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`
- [ ] T012 [US1] Add `import_user_mappings` permission mapping in `McpToolRegistry.isToolAuthorized()`

**Checkpoint**: MCP bulk import tool functional - can import mappings programmatically

---

## Phase 4: User Story 2 - CLI Bulk Import from File (Priority: P1)

**Goal**: Ensure CLI import command is fully documented and matches web UI parity

**Independent Test**: Run `./bin/secman manage-user-mappings import --file test.csv` and verify output

### Implementation for User Story 2

- [ ] T013 [US2] Verify CLI `ImportCommand` already supports CSV and JSON import in `src/cli/src/main/kotlin/com/secman/cli/commands/ImportCommand.kt`
- [ ] T014 [US2] Verify dry-run mode works correctly in `ImportCommand.kt`
- [ ] T015 [US2] Add usage examples to CLI help text in `ImportCommand.kt` if missing

**Checkpoint**: CLI bulk import matches web UI functionality

---

## Phase 5: User Story 3 - MCP List User Mappings (Priority: P2)

**Goal**: Enable retrieval of user mappings via MCP with pagination and email filtering

**Independent Test**: Call `list_user_mappings` MCP tool and verify paginated list returned

### Implementation for User Story 3

- [ ] T016 [US3] Create `ListUserMappingsTool.kt` implementing `McpTool` interface in `src/backendng/src/main/kotlin/com/secman/mcp/tools/ListUserMappingsTool.kt`
- [ ] T017 [US3] Implement input schema (email filter, page, size) per `contracts/mcp-tools.md` in `ListUserMappingsTool.kt`
- [ ] T018 [US3] Implement User Delegation and ADMIN role checks in `ListUserMappingsTool.execute()`
- [ ] T019 [US3] Implement pagination using repository queries (from T003) in `ListUserMappingsTool.kt`
- [ ] T020 [US3] Implement email filtering (partial match) in `ListUserMappingsTool.kt`
- [ ] T021 [US3] Map `UserMapping` entity to `UserMappingDto` response format in `ListUserMappingsTool.kt`
- [ ] T022 [US3] Register `ListUserMappingsTool` in `McpToolRegistry` in `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`
- [ ] T023 [US3] Add `list_user_mappings` permission mapping in `McpToolRegistry.isToolAuthorized()`

**Checkpoint**: MCP list tool functional - can query mappings programmatically

---

## Phase 6: User Story 4 - CLI List User Mappings (Priority: P2)

**Goal**: Add JSON output format to CLI list command for scripting

**Independent Test**: Run `./bin/secman manage-user-mappings list --format json` and verify JSON output

### Implementation for User Story 4

- [ ] T024 [US4] Add `--format` option (table/json) to `ListCommand.kt` in `src/cli/src/main/kotlin/com/secman/cli/commands/ListCommand.kt`
- [ ] T025 [US4] Implement table output format (existing behavior) in `ListCommand.kt`
- [ ] T026 [US4] Implement JSON output format per `contracts/cli-commands.md` in `ListCommand.kt`
- [ ] T027 [US4] Update command help text with format examples in `ListCommand.kt`

**Checkpoint**: CLI list command supports both table and JSON output

---

## Phase 7: User Story 5 - Documentation Update (Priority: P3)

**Goal**: Comprehensive documentation for MCP and CLI user mapping features

**Independent Test**: Review documentation completeness against `contracts/` specifications

### Implementation for User Story 5

- [ ] T028 [P] [US5] Add `import_user_mappings` tool documentation to `docs/MCP_TOOLS.md`
- [ ] T029 [P] [US5] Add `list_user_mappings` tool documentation to `docs/MCP_TOOLS.md`
- [ ] T030 [P] [US5] Create CLI user mappings guide at `docs/CLI_USER_MAPPINGS.md`
- [ ] T031 [US5] Add CSV format specification and examples to `docs/CLI_USER_MAPPINGS.md`
- [ ] T032 [US5] Add JSON format specification and examples to `docs/CLI_USER_MAPPINGS.md`
- [ ] T033 [US5] Add common workflow examples (bulk provisioning, CI/CD) to documentation

**Checkpoint**: Documentation complete - users can self-serve with docs

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and cleanup

- [ ] T034 Run `./gradlew build` to verify no compilation errors
- [ ] T035 Verify MCP tools appear in tool listing via MCP client
- [ ] T036 Run quickstart.md validation scenarios manually
- [ ] T037 Update CLAUDE.md with new MCP tools and CLI commands if needed

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - verification only
- **Foundational (Phase 2)**: Depends on Setup - adds pagination support
- **User Stories (Phase 3-7)**: All depend on Foundational phase completion
  - US1 and US2 (P1) can run in parallel
  - US3 and US4 (P2) can run in parallel after P1 stories
  - US5 (P3) depends on US1-US4 for accurate documentation
- **Polish (Phase 8)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: MCP Import - No dependencies on other stories
- **User Story 2 (P1)**: CLI Import - No dependencies on other stories
- **User Story 3 (P2)**: MCP List - No dependencies on US1/US2
- **User Story 4 (P2)**: CLI List - No dependencies on other stories
- **User Story 5 (P3)**: Documentation - Should complete after US1-US4 for accuracy

### Within Each User Story

- Core implementation before integration
- Tool registration after tool implementation
- Story complete before moving to next priority

### Parallel Opportunities

**P1 Stories (can run in parallel)**:
- US1 (MCP Import) and US2 (CLI Import) work on different files
- T004-T012 (MCP tool) can run parallel to T013-T015 (CLI verification)

**P2 Stories (can run in parallel)**:
- US3 (MCP List) and US4 (CLI List) work on different files
- T016-T023 (MCP tool) can run parallel to T024-T027 (CLI enhancement)

**P3 Documentation (parallel docs)**:
- T028, T029, T030 all work on different files

---

## Parallel Example: P1 Stories

```bash
# Launch US1 and US2 together after Foundational phase:

# Developer A - US1 (MCP Import):
Task: "Create ImportUserMappingsTool.kt in src/backendng/src/main/kotlin/com/secman/mcp/tools/ImportUserMappingsTool.kt"

# Developer B - US2 (CLI Import):
Task: "Verify CLI ImportCommand supports CSV and JSON import in src/cli/src/main/kotlin/com/secman/cli/commands/ImportCommand.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (verification)
2. Complete Phase 2: Foundational (pagination support)
3. Complete Phase 3: User Story 1 (MCP Import)
4. **STOP and VALIDATE**: Test MCP import independently
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 (MCP Import) → Test independently → Demo
3. Add User Story 2 (CLI Import) → Test independently → Demo (CLI parity)
4. Add User Story 3 (MCP List) → Test independently → Demo (read capability)
5. Add User Story 4 (CLI List) → Test independently → Demo (JSON output)
6. Add User Story 5 (Documentation) → Review → Complete

### Single Developer Strategy

If working alone:
1. Complete all P1 stories first (US1 → US2)
2. Then complete all P2 stories (US3 → US4)
3. Finally complete P3 (US5 - Documentation)

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- MCP tools follow existing patterns in `src/backendng/src/main/kotlin/com/secman/mcp/tools/`
- Reuse `UserMappingCliService` logic - do not duplicate validation
