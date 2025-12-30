# Tasks: CLI and MCP Requirements Management

**Input**: Design documents from `/specs/057-cli-mcp-requirements/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Not requested in specification. Per constitution (User-Requested Testing), test tasks are not included.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **CLI**: `src/cli/src/main/kotlin/com/secman/cli/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify existing infrastructure and identify any missing dependencies

- [x] T001 Verify existing backend export endpoints work via curl/Postman: `GET /api/requirements/export/xlsx` and `GET /api/requirements/export/docx`
- [x] T002 Verify existing backend create endpoint works: `POST /api/requirements`
- [x] T003 Verify existing backend delete-all endpoint works: `DELETE /api/requirements/all`
- [x] T004 [P] Verify CLI module builds successfully: `./gradlew :cli:build`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create shared CLI service that ALL CLI commands depend on

**⚠️ CRITICAL**: No CLI command work can begin until this phase is complete

- [x] T005 Create RequirementCliService in `src/cli/src/main/kotlin/com/secman/cli/service/RequirementCliService.kt` with:
  - authenticate(username, password, backendUrl): String? method (returns JWT token)
  - exportRequirements(format, backendUrl, authToken): ByteArray method
  - addRequirement(request, backendUrl, authToken): AddRequirementResponseDto method
  - deleteAllRequirements(backendUrl, authToken): DeleteAllResponseDto method
  - Use existing VulnerabilityCliService as pattern reference

**Checkpoint**: Foundation ready - CLI command implementation can now begin

---

## Phase 3: User Story 1+2 - Export Requirements via CLI (Priority: P1) 🎯 MVP

**Goal**: Security administrators can export all requirements to Excel (US1) or Word (US2) files via CLI

**Independent Test**: Run `secman export-requirements --format xlsx --output test.xlsx` and verify valid Excel file is created

### Implementation for User Stories 1+2

- [x] T006 [US1+US2] Create ExportRequirementsCommand in `src/cli/src/main/kotlin/com/secman/cli/commands/ExportRequirementsCommand.kt` with:
  - @Command annotation with name="export-requirements", mixinStandardHelpOptions=true
  - @Option --format (required, enum: xlsx, docx)
  - @Option --output (optional, default: timestamped filename)
  - @Option --username, --password (with SECMAN_USERNAME/SECMAN_PASSWORD env var fallback)
  - @Option --backend-url (default: http://localhost:8080)
  - @Option --verbose
  - @Inject RequirementCliService
  - run() method: authenticate, call exportRequirements, save to file, print summary

- [x] T007 [US1+US2] Add export-requirements command routing in `src/cli/src/main/kotlin/com/secman/cli/SecmanCli.kt`:
  - Add "export-requirements" case in execute() method
  - Use Picocli with Micronaut DI pattern (like manage-user-mappings)
  - Update showHelp() with export-requirements documentation

- [x] T008 [US1+US2] Implement file saving logic in ExportRequirementsCommand:
  - Generate default filename with timestamp if --output not specified
  - Validate output directory exists
  - Write binary content to file
  - Print success message with file path, size, and requirement count

**Checkpoint**: User Stories 1 and 2 complete - CLI export to Excel and Word both functional

---

## Phase 4: User Story 5 - Export Requirements via MCP (Priority: P2)

**Goal**: AI assistants can export requirements through MCP protocol with base64-encoded response

**Independent Test**: Call `export_requirements` MCP tool with `format: "xlsx"` and verify base64-encoded file content returned

### Implementation for User Story 5

- [x] T009 [US5] Create ExportRequirementsTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/ExportRequirementsTool.kt` with:
  - Implement McpTool interface
  - name = "export_requirements"
  - description = "Export all requirements to Excel or Word format"
  - operation = McpOperation.READ
  - inputSchema with format parameter (enum: xlsx, docx, required)
  - execute() method: call RequirementService, generate file bytes, base64 encode, return with metadata

- [x] T010 [US5] Register ExportRequirementsTool in `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`:
  - Add @Inject constructor parameter
  - Add to tools list in lazy initialization
  - Add permission check for "export_requirements" requiring REQUIREMENTS_READ

**Checkpoint**: User Story 5 complete - MCP export functional

---

## Phase 5: User Story 3 - Add Requirement via CLI (Priority: P2)

**Goal**: Security administrators can add new requirements from command line

**Independent Test**: Run `secman add-requirement --shortreq "Test requirement" --chapter "Test"` and verify requirement created

### Implementation for User Story 3

- [x] T011 [US3] Create AddRequirementCommand in `src/cli/src/main/kotlin/com/secman/cli/commands/AddRequirementCommand.kt` with:
  - @Command annotation with name="add-requirement", mixinStandardHelpOptions=true
  - @Option --shortreq (required)
  - @Option --chapter, --details, --motivation, --example, --norm, --usecase (all optional)
  - @Option --username, --password (with env var fallback)
  - @Option --backend-url, --verbose
  - @Inject RequirementCliService
  - run() method: validate shortreq, authenticate, call addRequirement, print result

- [x] T012 [US3] Add add-requirement command routing in `src/cli/src/main/kotlin/com/secman/cli/SecmanCli.kt`:
  - Add "add-requirement" case in execute() method (note: different from existing "add-vulnerability")
  - Use Picocli with Micronaut DI pattern
  - Update showHelp() with add-requirement documentation

**Checkpoint**: User Story 3 complete - CLI add requirement functional

---

## Phase 6: User Story 6 - Add Requirement via MCP (Priority: P2)

**Goal**: AI assistants can create requirements through MCP protocol

**Independent Test**: Call `add_requirement` MCP tool with `shortreq: "Test"` and verify requirement created with returned ID

### Implementation for User Story 6

- [x] T013 [US6] Create AddRequirementTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/AddRequirementTool.kt` with:
  - Implement McpTool interface
  - name = "add_requirement"
  - description = "Create a new security requirement"
  - operation = McpOperation.WRITE
  - inputSchema with shortreq (required), details, motivation, example, norm, usecase, chapter (optional)
  - execute() method: validate shortreq, call RequirementService.createRequirement, return created ID

- [x] T014 [US6] Register AddRequirementTool in `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`:
  - Add @Inject constructor parameter
  - Add to tools list
  - Add permission check for "add_requirement" requiring REQUIREMENTS_WRITE

**Checkpoint**: User Story 6 complete - MCP add requirement functional

---

## Phase 7: User Story 4 - Delete All Requirements via CLI (Priority: P3)

**Goal**: Administrators can clear all requirements from the system with safety confirmation

**Independent Test**: Run `secman delete-all-requirements --confirm` with ADMIN user and verify all requirements deleted

### Implementation for User Story 4

- [x] T015 [US4] Create DeleteAllRequirementsCommand in `src/cli/src/main/kotlin/com/secman/cli/commands/DeleteAllRequirementsCommand.kt` with:
  - @Command annotation with name="delete-all-requirements", mixinStandardHelpOptions=true
  - @Option --confirm (required, boolean)
  - @Option --username, --password (with env var fallback)
  - @Option --backend-url, --verbose
  - @Inject RequirementCliService
  - run() method: validate --confirm flag, warn user, authenticate, call deleteAllRequirements, print count

- [x] T016 [US4] Add delete-all-requirements command routing in `src/cli/src/main/kotlin/com/secman/cli/SecmanCli.kt`:
  - Add "delete-all-requirements" case in execute() method
  - Use Picocli with Micronaut DI pattern
  - Update showHelp() with delete-all-requirements documentation

**Checkpoint**: User Story 4 complete - CLI delete all requirements functional

---

## Phase 8: User Story 7 - Delete All Requirements via MCP (Priority: P3)

**Goal**: AI assistants with admin privileges can clear all requirements through MCP protocol

**Independent Test**: Call `delete_all_requirements` MCP tool with `confirm: true` and verify all requirements deleted

### Implementation for User Story 7

- [x] T017 [US7] Create DeleteAllRequirementsTool in `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteAllRequirementsTool.kt` with:
  - Implement McpTool interface
  - name = "delete_all_requirements"
  - description = "Delete all requirements from the system (ADMIN only)"
  - operation = McpOperation.DELETE
  - inputSchema with confirm parameter (required, boolean)
  - execute() method: validate confirm=true, check ADMIN role in context, call RequirementService, return count

- [x] T018 [US7] Register DeleteAllRequirementsTool in `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`:
  - Add @Inject constructor parameter
  - Add to tools list
  - Add permission check for "delete_all_requirements" requiring REQUIREMENTS_WRITE + ADMIN role check

**Checkpoint**: User Story 7 complete - MCP delete all requirements functional

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Documentation and final validation

- [x] T019 [P] Update CLAUDE.md with new CLI commands in "Development Commands" section
- [x] T020 [P] Update docs/CLI.md with export-requirements, add-requirement, delete-all-requirements commands (help text in SecmanCli.kt serves as CLI documentation)
- [x] T021 Run `./gradlew build` to verify all code compiles and existing tests pass (note: shadowJar has pre-existing zip64 issue)
- [x] T022 Manual validation: test each CLI command with --help flag
- [x] T023 Manual validation: run quickstart.md scenarios to verify functionality

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all CLI commands
- **User Stories (Phase 3+)**: All CLI stories depend on Phase 2 (RequirementCliService)
- **MCP Stories (Phase 4, 6, 8)**: No dependency on CLI - can run in parallel with CLI phases
- **Polish (Phase 9)**: Depends on all desired user stories being complete

### User Story Dependencies

| Story | Priority | Depends On | Can Parallel With |
|-------|----------|------------|-------------------|
| US1+US2 (CLI Export) | P1 | Phase 2 | US5, US6, US7 |
| US5 (MCP Export) | P2 | None (backend only) | All CLI stories |
| US3 (CLI Add) | P2 | Phase 2 | US5, US6, US7 |
| US6 (MCP Add) | P2 | None (backend only) | All CLI stories |
| US4 (CLI Delete) | P3 | Phase 2 | US5, US6, US7 |
| US7 (MCP Delete) | P3 | None (backend only) | All CLI stories |

### Parallel Opportunities

**CLI Stream** (requires Phase 2 first):
- T006, T007, T008 → T011, T012 → T015, T016

**MCP Stream** (independent):
- T009, T010 (can run in parallel with any CLI task)
- T013, T014 (can run in parallel with any CLI task)
- T017, T018 (can run in parallel with any CLI task)

---

## Parallel Example: Maximum Parallelism

```bash
# After Phase 2 completes, launch CLI and MCP streams in parallel:

# Stream 1 - CLI Export (US1+US2):
Task: "T006 [US1+US2] Create ExportRequirementsCommand"

# Stream 2 - MCP Export (US5) - PARALLEL:
Task: "T009 [US5] Create ExportRequirementsTool"

# Stream 3 - MCP Add (US6) - PARALLEL:
Task: "T013 [US6] Create AddRequirementTool"
```

---

## Implementation Strategy

### MVP First (User Stories 1+2 Only)

1. Complete Phase 1: Setup (verify existing endpoints)
2. Complete Phase 2: Foundational (RequirementCliService)
3. Complete Phase 3: User Stories 1+2 (ExportRequirementsCommand)
4. **STOP and VALIDATE**: Test `secman export-requirements --format xlsx` independently
5. Deploy/demo if ready - users can now export requirements!

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add US1+US2 (CLI Export) → Test independently → **MVP Delivered!**
3. Add US5 (MCP Export) → Test independently → AI assistants can export
4. Add US3+US6 (Add requirement) → Test independently → Full CRUD for requirements
5. Add US4+US7 (Delete all) → Test independently → Admin operations available

### Parallel Team Strategy

With two developers:

1. Team completes Setup + Foundational together
2. Once Phase 2 is done:
   - Developer A: CLI stream (US1+US2 → US3 → US4)
   - Developer B: MCP stream (US5 → US6 → US7)
3. Stories complete and integrate independently

---

## Summary

| Metric | Count |
|--------|-------|
| Total Tasks | 23 |
| Setup Tasks | 4 |
| Foundational Tasks | 1 |
| US1+US2 Tasks (CLI Export) | 3 |
| US3 Tasks (CLI Add) | 2 |
| US4 Tasks (CLI Delete) | 2 |
| US5 Tasks (MCP Export) | 2 |
| US6 Tasks (MCP Add) | 2 |
| US7 Tasks (MCP Delete) | 2 |
| Polish Tasks | 5 |
| Parallel Opportunities | CLI/MCP streams can run independently |

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- MCP tools can be implemented in parallel with CLI commands (different files, no conflicts)
