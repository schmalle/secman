# Tasks: Heatmap MCP and API Exposure

**Input**: Design documents from `/specs/086-heatmap-mcp-api/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Not requested. No test tasks included per Constitution IV (User-Requested Testing).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: No setup needed — project infrastructure already exists (MCP server, tool registry, heatmap service, repository).

*Phase skipped — all infrastructure is in place.*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: No foundational work needed — the MCP tool interface, registry, execution context, and heatmap service/repository are all operational.

*Phase skipped — all prerequisites are met.*

**Checkpoint**: Foundation ready — user story implementation can begin.

---

## Phase 3: User Story 1 - AI Assistant Queries Heatmap via MCP (Priority: P1)

**Goal**: An AI assistant can call the `get_vulnerability_heatmap` MCP tool to retrieve per-asset severity counts scoped by the delegated user's access control.

**Independent Test**: Call `tools/call` with `get_vulnerability_heatmap` via an MCP API key and verify the response contains entries, summary, and lastCalculatedAt.

### Implementation for User Story 1

- [x] T001 [US1] Create GetVulnerabilityHeatmapTool implementing McpTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetVulnerabilityHeatmapTool.kt
- [x] T002 [US1] Register GetVulnerabilityHeatmapTool in McpToolRegistry constructor and tools map, map to VULNERABILITY_READ permission in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt

**Checkpoint**: `get_vulnerability_heatmap` appears in `tools/list` and returns heatmap data via `tools/call` with access control.

---

## Phase 4: User Story 2 - External Web Page Consumes Heatmap Data (Priority: P2)

**Goal**: External web applications can retrieve heatmap data via a CORS-enabled REST endpoint authenticated with MCP API keys.

**Independent Test**: Send a GET request to `/api/external/vulnerability-heatmap` with `X-MCP-API-Key` and `X-MCP-User-Email` headers from a different origin and verify CORS headers and JSON response.

### Implementation for User Story 2

- [x] T003 [US2] Add CORS configuration for /api/external/** path pattern in src/backendng/src/main/resources/application.yml
- [x] T004 [US2] Create ExternalHeatmapController with API key authentication and access-controlled heatmap query in src/backendng/src/main/kotlin/com/secman/controller/ExternalHeatmapController.kt

**Checkpoint**: External web pages can fetch heatmap data with proper CORS and API key auth.

---

## Phase 5: User Story 3 - Admin Triggers Heatmap Refresh via MCP (Priority: P3)

**Goal**: An admin's AI assistant can trigger heatmap recalculation via MCP without waiting for a CrowdStrike import.

**Independent Test**: Call `tools/call` with `refresh_vulnerability_heatmap` using an ADMIN-delegated API key and verify entriesCreated > 0. Verify non-admin gets an error.

### Implementation for User Story 3

- [x] T005 [US3] Create RefreshVulnerabilityHeatmapTool implementing McpTool in src/backendng/src/main/kotlin/com/secman/mcp/tools/RefreshVulnerabilityHeatmapTool.kt
- [x] T006 [US3] Register RefreshVulnerabilityHeatmapTool in McpToolRegistry constructor and tools map, map to VULNERABILITY_WRITE permission in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt

**Checkpoint**: `refresh_vulnerability_heatmap` appears in `tools/list` and recalculates heatmap when called by ADMIN.

---

## Phase 6: User Story 4 - MCP Documentation Updated (Priority: P2)

**Goal**: Developers and AI integration engineers can discover and understand the heatmap tools from the MCP documentation.

**Independent Test**: Read docs/MCP.md and confirm both heatmap tools are listed with descriptions, permissions, and usage examples.

### Implementation for User Story 4

- [x] T007 [P] [US4] Add get_vulnerability_heatmap and refresh_vulnerability_heatmap to the tools table and add usage examples in docs/MCP.md
- [x] T008 [P] [US4] Add external heatmap endpoint and MCP tool references to CLAUDE.md

**Checkpoint**: Documentation is complete and accurate.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Verify everything works together and build succeeds.

- [x] T009 Run ./gradlew build to verify backend compilation succeeds
- [x] T010 Run quickstart.md validation — verify curl examples match implemented endpoints

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Skipped — infrastructure exists
- **Foundational (Phase 2)**: Skipped — prerequisites met
- **US1 (Phase 3)**: No dependencies — can start immediately
- **US2 (Phase 4)**: No dependencies on US1 — can start in parallel
- **US3 (Phase 5)**: No dependencies on US1/US2 — can start in parallel
- **US4 (Phase 6)**: Depends on US1, US2, US3 completion (documents implemented tools)
- **Polish (Phase 7)**: Depends on all phases complete

### User Story Dependencies

- **US1 (P1)**: Independent — MCP query tool only needs existing heatmap service
- **US2 (P2)**: Independent — external REST endpoint only needs existing heatmap service
- **US3 (P3)**: Independent — MCP refresh tool only needs existing heatmap service
- **US4 (P2)**: Depends on US1+US2+US3 — documents what was implemented

### Within Each User Story

- Tool/Controller creation before registry/config updates
- Implementation before documentation

### Parallel Opportunities

- T001 (US1), T003+T004 (US2), T005 (US3) can all run in parallel — different files, no shared dependencies
- T007 and T008 (US4) can run in parallel — different documentation files
- T002 and T006 both modify McpToolRegistry — must be sequential (or combined)

---

## Parallel Example: Maximum Parallelism

```text
# Wave 1: All tool/controller implementations (parallel — different files):
T001: GetVulnerabilityHeatmapTool.kt
T003: application.yml CORS config
T004: ExternalHeatmapController.kt
T005: RefreshVulnerabilityHeatmapTool.kt

# Wave 2: Registry updates (sequential — same file):
T002: Register query tool in McpToolRegistry.kt
T006: Register refresh tool in McpToolRegistry.kt

# Wave 3: Documentation (parallel — different files):
T007: docs/MCP.md
T008: CLAUDE.md

# Wave 4: Verification:
T009: ./gradlew build
T010: Quickstart validation
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete T001 + T002 (MCP query tool)
2. **STOP and VALIDATE**: Test via `tools/call` with MCP API key
3. AI assistants can now query heatmap data

### Incremental Delivery

1. US1 → MCP query available → Deploy
2. US2 → External REST available → Deploy
3. US3 → MCP refresh available → Deploy
4. US4 → Documentation complete → Deploy
5. Each increment adds value without breaking previous stories

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- T002 and T006 both modify McpToolRegistry.kt — execute sequentially or combine into one edit session
- No database migrations needed — reuses existing asset_heatmap_entry table
- Commit after each task or logical group
