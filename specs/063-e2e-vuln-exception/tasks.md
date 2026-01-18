# Tasks: E2E Vulnerability Exception Workflow Test Suite

**Input**: Design documents from `/specs/063-e2e-vuln-exception/`
**Prerequisites**: plan.md, spec.md, research.md, contracts/, quickstart.md

**Tests**: User explicitly requested this test suite - the test script IS the deliverable.

**Organization**: Tasks organized by component (MCP tools) then by user story sections in the test script.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Based on plan.md structure:
- **Backend MCP Tools**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/`
- **MCP Registry**: `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`
- **Test Script**: `bin/test-e2e-exception-workflow.sh`

---

## Phase 1: Setup

**Purpose**: Verify project structure and dependencies are in place

- [x] T001 Verify existing MCP tools are operational by checking src/backendng/src/main/kotlin/com/secman/mcp/tools/ directory structure

**Checkpoint**: Project structure verified, ready to create new components

---

## Phase 2: Foundational (MCP Tools)

**Purpose**: Create the 2 new MCP tools that enable the E2E test script

**⚠️ CRITICAL**: Test script cannot function without these tools

- [x] T002 [P] Create DeleteAllAssetsTool.kt in src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteAllAssetsTool.kt following DeleteAllRequirementsTool pattern
- [x] T003 [P] Create AddVulnerabilityTool.kt in src/backendng/src/main/kotlin/com/secman/mcp/tools/AddVulnerabilityTool.kt following AddUserTool pattern
- [x] T004 Register new tools in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt with appropriate role permissions (ADMIN for delete_all_assets, ADMIN/VULN for add_vulnerability)
- [x] T005 Verify backend compiles with ./gradlew :backendng:compileKotlin

**Checkpoint**: MCP tools ready - test script implementation can begin

---

## Phase 3: User Story 1 - Admin Prepares Clean Environment (Priority: P1) 🎯 MVP

**Goal**: Delete all existing assets via MCP to prepare clean test environment

**Independent Test**: Invoke delete_all_assets MCP tool, verify asset count returns zero

### Implementation for User Story 1

- [x] T006 [US1] Create test script scaffold with configuration section (BASE_URL, API_KEY, test constants) in bin/test-e2e-exception-workflow.sh
- [x] T007 [US1] Implement helper functions (fail, success, mcp_call, log) in bin/test-e2e-exception-workflow.sh
- [x] T008 [US1] Implement Step 0: Cleanup pre-existing test user (delete apple@schmall.io if exists) in bin/test-e2e-exception-workflow.sh
- [x] T009 [US1] Implement Step 1: Call delete_all_assets MCP tool with confirmation=true in bin/test-e2e-exception-workflow.sh
- [x] T010 [US1] Add verification: Query assets and assert count is zero in bin/test-e2e-exception-workflow.sh

**Checkpoint**: US1 section complete - can delete all assets and verify clean state

---

## Phase 4: User Story 2 - Admin Creates Test User and Assets (Priority: P1)

**Goal**: Create test user apple@schmall.io and asset with 10-day vulnerability

**Independent Test**: Create user, create asset with vulnerability, verify user can see asset via delegation

### Implementation for User Story 2

- [x] T011 [US2] Implement Step 2: Call add_user MCP tool to create apple@schmall.io with USER role in bin/test-e2e-exception-workflow.sh
- [x] T012 [US2] Implement Step 3a: Call add_vulnerability MCP tool to create asset + 10-day HIGH vulnerability in bin/test-e2e-exception-workflow.sh
- [x] T013 [US2] Capture asset hostname and vulnerability ID from response for later use in bin/test-e2e-exception-workflow.sh

**Checkpoint**: US2 section complete - test user and initial asset/vulnerability exist

---

## Phase 5: User Story 3 - User Views Non-Overdue Vulnerability (Priority: P2)

**Goal**: Verify apple@schmall.io sees vulnerability but has NO overdue items

**Independent Test**: Query as apple@schmall.io via delegation, verify overdue count is zero

### Implementation for User Story 3

- [x] T014 [US3] Implement Step 4a: Set X-MCP-User-Email header to apple@schmall.io for delegation in bin/test-e2e-exception-workflow.sh
- [x] T015 [US3] Implement Step 4b: Call get_assets or get_vulnerabilities as apple@schmall.io, verify asset visible in bin/test-e2e-exception-workflow.sh
- [x] T016 [US3] Implement Step 4c: Call get_overdue_assets as apple@schmall.io, verify empty result (10-day vuln is NOT overdue) in bin/test-e2e-exception-workflow.sh

**Checkpoint**: US3 section complete - non-overdue case validated

---

## Phase 6: User Story 4 - Admin Adds Critical Overdue Vulnerability (Priority: P2)

**Goal**: Add 40-day CRITICAL vulnerability to trigger overdue state

**Independent Test**: Add vulnerability, query overdue assets, verify new vuln appears

### Implementation for User Story 4

- [x] T017 [US4] Implement Step 5: Call add_vulnerability MCP tool with daysOpen=40, criticality=CRITICAL on same asset in bin/test-e2e-exception-workflow.sh
- [x] T018 [US4] Capture the overdue vulnerability ID for exception request in bin/test-e2e-exception-workflow.sh

**Checkpoint**: US4 section complete - overdue vulnerability exists

---

## Phase 7: User Story 5 - User Requests Exception (Priority: P1)

**Goal**: apple@schmall.io queries overdue vulnerabilities and creates exception request

**Independent Test**: Query overdue as user, create exception request, verify PENDING status

### Implementation for User Story 5

- [x] T019 [US5] Implement Step 6a: Call get_overdue_assets as apple@schmall.io via delegation, verify 40-day vuln appears in bin/test-e2e-exception-workflow.sh
- [x] T020 [US5] Implement Step 6b: Extract vulnerability ID from overdue response in bin/test-e2e-exception-workflow.sh
- [x] T021 [US5] Implement Step 7a: Call create_exception_request as apple@schmall.io with vulnerability ID and reason in bin/test-e2e-exception-workflow.sh
- [x] T022 [US5] Implement Step 7b: Call get_my_exception_requests as apple@schmall.io, verify PENDING request exists in bin/test-e2e-exception-workflow.sh

**Checkpoint**: US5 section complete - exception request created and visible to user

---

## Phase 8: User Story 6 - Admin Approves Exception (Priority: P1)

**Goal**: Admin reviews and approves the pending exception request

**Independent Test**: Query pending requests as admin, approve, verify APPROVED status

### Implementation for User Story 6

- [x] T023 [US6] Implement Step 8a: Remove X-MCP-User-Email header (act as admin) in bin/test-e2e-exception-workflow.sh
- [x] T024 [US6] Implement Step 8b: Call get_pending_exception_requests as admin, find apple@schmall.io's request in bin/test-e2e-exception-workflow.sh
- [x] T025 [US6] Implement Step 8c: Call approve_exception_request with request ID in bin/test-e2e-exception-workflow.sh
- [x] T026 [US6] Implement Step 9: Call get_my_exception_requests as apple@schmall.io, verify status is APPROVED in bin/test-e2e-exception-workflow.sh

**Checkpoint**: US6 section complete - exception workflow completed successfully

---

## Phase 9: User Story 7 - Test Cleanup (Priority: P3)

**Goal**: Delete all test data to leave system in clean state

**Independent Test**: Delete user and assets, verify nothing remains

### Implementation for User Story 7

- [x] T027 [US7] Implement Step 10a: Call delete_user MCP tool to remove apple@schmall.io in bin/test-e2e-exception-workflow.sh
- [x] T028 [US7] Implement Step 10b: Call delete_all_assets MCP tool to remove test assets in bin/test-e2e-exception-workflow.sh
- [x] T029 [US7] Implement final success message with timing information in bin/test-e2e-exception-workflow.sh

**Checkpoint**: US7 section complete - cleanup successful, test fully repeatable

---

## Phase 10: Polish & Documentation

**Purpose**: Final validation and documentation updates

- [x] T030 Make script executable with chmod +x bin/test-e2e-exception-workflow.sh
- [x] T031 Add --help flag support showing usage instructions in bin/test-e2e-exception-workflow.sh
- [x] T032 Add --verbose flag for detailed output in bin/test-e2e-exception-workflow.sh
- [ ] T033 Test full script execution against running backend
- [x] T034 Update docs/MCP.md with new tool documentation (add_vulnerability, delete_all_assets)
- [x] T035 Run ./gradlew build to verify all components compile and pass existing tests

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 - BLOCKS all test script work
- **User Stories (Phase 3-9)**: All depend on Phase 2 (MCP tools must exist)
  - Must be implemented sequentially in the script (later steps depend on earlier state)
- **Polish (Phase 10)**: Depends on all user stories complete

### User Story Dependencies

This feature is unique: all user stories are SEQUENTIAL steps in a single test script.

- **US1**: Can start after Phase 2 - No dependencies
- **US2**: Depends on US1 (needs clean environment)
- **US3**: Depends on US2 (needs user and asset)
- **US4**: Depends on US2 (needs existing asset)
- **US5**: Depends on US4 (needs overdue vulnerability)
- **US6**: Depends on US5 (needs pending exception request)
- **US7**: Depends on US6 (needs completed workflow to clean up)

### Parallel Opportunities

- **Phase 2**: T002 and T003 can run in parallel (different files)
- **Test Script**: NO parallel within phases 3-9 (all modifications to same file bin/test-e2e-exception-workflow.sh)
- **Phase 10**: T030, T031, T032 can run in parallel with T034

---

## Parallel Example: Phase 2 (Foundational)

```bash
# Launch MCP tool creation in parallel:
Task: "Create DeleteAllAssetsTool.kt in src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteAllAssetsTool.kt"
Task: "Create AddVulnerabilityTool.kt in src/backendng/src/main/kotlin/com/secman/mcp/tools/AddVulnerabilityTool.kt"
```

---

## Implementation Strategy

### MVP First (US1 + US2 Only)

1. Complete Phase 1: Setup (verify)
2. Complete Phase 2: Foundational (create MCP tools)
3. Complete Phase 3: US1 (delete all assets)
4. Complete Phase 4: US2 (create user + asset)
5. **STOP and VALIDATE**: Run partial script to verify MCP tools work
6. Continue with US3-US7

### Full Delivery

1. Complete all phases sequentially
2. Run full test script: `./bin/test-e2e-exception-workflow.sh`
3. Verify completes in under 60 seconds (SC-001)
4. Verify script is idempotent (run twice, both succeed)

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- All test script tasks modify the SAME FILE - no parallelism within script
- MCP tools can be developed in parallel
- Test against running backend with valid admin MCP API key
- Fail-fast behavior: script exits on first error with clear message
