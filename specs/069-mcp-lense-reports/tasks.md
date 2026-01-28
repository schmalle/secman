# Tasks: MCP Lense Reports

**Input**: Design documents from `/specs/069-mcp-lense-reports/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/mcp-tools.md

**Tests**: Not explicitly requested - tests omitted per project guidelines ("Never write testcases" in CLAUDE.md).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Documentation**: `docs/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create shared DTOs and directory structure

- [x] T001 [P] Create reports DTO package directory at src/backendng/src/main/kotlin/com/secman/dto/reports/
- [x] T002 [P] Create RiskAssessmentSummaryDto.kt with all nested DTOs (AssessmentSummaryDto, RiskSummaryDto, AssetCoverageDto, RecentAssessmentDto, HighPriorityRiskDto) in src/backendng/src/main/kotlin/com/secman/dto/reports/RiskAssessmentSummaryDto.kt
- [x] T003 [P] Create RiskMitigationStatusDto.kt with nested DTOs (MitigationSummaryDto, RiskMitigationDetailDto) in src/backendng/src/main/kotlin/com/secman/dto/reports/RiskMitigationStatusDto.kt
- [x] T004 [P] Create TopServerByVulnerabilitiesDto.kt in src/backendng/src/main/kotlin/com/secman/dto/reports/TopServerByVulnerabilitiesDto.kt

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Repository queries and service layer that ALL user stories depend on

**⚠️ CRITICAL**: No MCP tool implementation can begin until this phase is complete

- [x] T005 Add count queries to RiskRepository: countByStatus(), countByRiskLevel(), countOverdueRisks(today), countUnassignedRisks() in src/backendng/src/main/kotlin/com/secman/repository/RiskRepository.kt
- [x] T006 Add queries to RiskAssessmentRepository: countByStatus(), findRecentAssessments(limit), countAssetsWithAssessments() in src/backendng/src/main/kotlin/com/secman/repository/RiskAssessmentRepository.kt
- [x] T007 Create ReportService with getRiskAssessmentSummary(accessibleAssetIds) and getRiskMitigationStatus(accessibleAssetIds, statusFilter) in src/backendng/src/main/kotlin/com/secman/service/ReportService.kt
- [x] T008 Add getTopServersByVulnerabilities(authentication, limit, domain) method to VulnerabilityStatisticsService in src/backendng/src/main/kotlin/com/secman/service/VulnerabilityStatisticsService.kt

**Checkpoint**: Foundation ready - MCP tool implementation can now begin in parallel

---

## Phase 3: User Story 1 - Risk Assessment Summary (Priority: P1) 🎯 MVP

**Goal**: Provide `get_risk_assessment_summary` MCP tool returning assessment metrics, risk breakdowns, asset coverage, recent assessments, and high-priority risks

**Independent Test**: Call `get_risk_assessment_summary` via MCP and verify it returns all expected sections with proper access control filtering

### Implementation for User Story 1

- [ ] T009 [US1] Create GetRiskAssessmentSummaryTool.kt implementing McpTool interface with inputSchema (no params), inject ReportService, use context.getFilterableAssetIds() for access control in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetRiskAssessmentSummaryTool.kt
- [ ] T010 [US1] Register GetRiskAssessmentSummaryTool in McpToolRegistry constructor injection and add to tools map in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt
- [ ] T011 [US1] Add permission mapping for get_risk_assessment_summary -> ASSESSMENTS_READ in McpToolRegistry.isToolAuthorized() in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt

**Checkpoint**: User Story 1 complete - `get_risk_assessment_summary` tool is functional and testable independently

---

## Phase 4: User Story 2 - Risk Mitigation Status (Priority: P1)

**Goal**: Provide `get_risk_mitigation_status` MCP tool returning open risks summary and detailed risk list with overdue/unassigned identification

**Independent Test**: Call `get_risk_mitigation_status` via MCP with optional status filter and verify it returns summary counts and risk details with isOverdue flag

### Implementation for User Story 2

- [ ] T012 [US2] Create GetRiskMitigationStatusTool.kt implementing McpTool interface with inputSchema (optional status filter), inject ReportService, validate status enum, use context.getFilterableAssetIds() in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetRiskMitigationStatusTool.kt
- [ ] T013 [US2] Register GetRiskMitigationStatusTool in McpToolRegistry constructor injection and add to tools map in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt
- [ ] T014 [US2] Add permission mapping for get_risk_mitigation_status -> ASSESSMENTS_READ in McpToolRegistry.isToolAuthorized() in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt

**Checkpoint**: User Stories 1 AND 2 complete - both risk report tools are functional

---

## Phase 5: User Story 3 - Vulnerability Statistics (Priority: P2)

**Goal**: Provide `get_vulnerability_statistics` MCP tool aggregating severity distribution, most common vulns, top products, top assets, by asset type, and top 50 servers

**Independent Test**: Call `get_vulnerability_statistics` via MCP with optional domain filter and verify all 6 statistics sections are returned including top 50 servers

### Implementation for User Story 3

- [ ] T015 [US3] Create GetVulnerabilityStatisticsTool.kt implementing McpTool interface with inputSchema (optional domain filter), inject VulnerabilityStatisticsService, call all 6 statistics methods and aggregate into single response in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetVulnerabilityStatisticsTool.kt
- [ ] T016 [US3] Register GetVulnerabilityStatisticsTool in McpToolRegistry constructor injection and add to tools map in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt
- [ ] T017 [US3] Add permission mapping for get_vulnerability_statistics -> VULNERABILITIES_READ in McpToolRegistry.isToolAuthorized() in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt

**Checkpoint**: User Stories 1, 2, AND 3 complete - risk and vulnerability report tools are functional

---

## Phase 6: User Story 4 - Exception Statistics (Priority: P3)

**Goal**: Provide `get_exception_statistics` MCP tool returning exception request counts, approval rates, and top requesters/CVEs with optional date range filtering

**Independent Test**: Call `get_exception_statistics` via MCP with optional date range and verify it returns all statistics matching existing ExceptionStatisticsDto structure

### Implementation for User Story 4

- [ ] T018 [US4] Create GetExceptionStatisticsTool.kt implementing McpTool interface with inputSchema (optional start_date, end_date), inject ExceptionRequestStatisticsService, validate date range, convert to service parameters in src/backendng/src/main/kotlin/com/secman/mcp/tools/GetExceptionStatisticsTool.kt
- [ ] T019 [US4] Register GetExceptionStatisticsTool in McpToolRegistry constructor injection and add to tools map in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt
- [ ] T020 [US4] Add permission mapping for get_exception_statistics -> VULNERABILITIES_READ in McpToolRegistry.isToolAuthorized() in src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt

**Checkpoint**: All 4 user stories complete - all report MCP tools are functional

---

## Phase 7: Polish & Documentation

**Purpose**: Documentation updates and final verification

- [ ] T021 [P] Update docs/MCP.md with get_risk_assessment_summary tool documentation including description, input schema, output schema, and usage example in docs/MCP.md
- [ ] T022 [P] Update docs/MCP.md with get_risk_mitigation_status tool documentation including description, input schema with status filter, output schema, and usage example in docs/MCP.md
- [ ] T023 [P] Update docs/MCP.md with get_vulnerability_statistics tool documentation including description, input schema with domain filter, output schema showing all 6 sections including top 50 servers, and usage example in docs/MCP.md
- [ ] T024 [P] Update docs/MCP.md with get_exception_statistics tool documentation including description, input schema with date range, output schema, and usage example in docs/MCP.md
- [ ] T025 Run ./gradlew build to verify all code compiles without errors
- [ ] T026 Verify all 4 tools appear in MCP tools/list response via manual test
- [ ] T027 Run quickstart.md verification checklist to confirm all deliverables complete

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phases 3-6)**: All depend on Foundational phase completion
  - User stories can proceed in parallel (if staffed) or sequentially in priority order
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational - No dependencies on other stories
- **User Story 2 (P1)**: Can start after Foundational - No dependencies on other stories (can run parallel with US1)
- **User Story 3 (P2)**: Can start after Foundational - No dependencies on other stories
- **User Story 4 (P3)**: Can start after Foundational - No dependencies on other stories

### Within Each User Story

1. Create tool class first
2. Register in McpToolRegistry second
3. Add permission mapping third (can be combined with registration in same file edit)

### Parallel Opportunities

- All Setup tasks (T001-T004) can run in parallel
- User Stories 1-4 (Phases 3-6) can all run in parallel after Foundational completes
- All documentation tasks (T021-T024) can run in parallel

---

## Parallel Example: After Foundational Phase

```bash
# Launch all 4 user stories in parallel (if team capacity allows):
# Developer A: User Story 1 (T009-T011)
# Developer B: User Story 2 (T012-T014)
# Developer C: User Story 3 (T015-T017)
# Developer D: User Story 4 (T018-T020)

# Or sequentially for single developer:
# Complete US1 -> Complete US2 -> Complete US3 -> Complete US4
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T004)
2. Complete Phase 2: Foundational (T005-T008)
3. Complete Phase 3: User Story 1 (T009-T011)
4. **STOP and VALIDATE**: Test `get_risk_assessment_summary` independently
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → First MCP report tool available (MVP!)
3. Add User Story 2 → Test independently → Both risk reports available
4. Add User Story 3 → Test independently → Vulnerability statistics available
5. Add User Story 4 → Test independently → All 4 reports available
6. Complete Polish → Full documentation and verification

### Single Developer Strategy

With one developer working sequentially:

1. Phase 1: ~30 min (4 parallel DTOs)
2. Phase 2: ~2 hrs (repository queries + 2 services)
3. Phase 3-6: ~1 hr each (tool + registration + permissions)
4. Phase 7: ~1 hr (documentation + verification)

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable via MCP
- Follow existing MCP tool patterns in codebase (see GetAssetsTool, GetVulnerabilitiesTool)
- Use McpToolResult.success() and McpToolResult.error() for responses
- Use context.getFilterableAssetIds() for access control filtering
- Commit after each phase or logical task group
- Stop at any checkpoint to validate story independently
