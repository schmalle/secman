# Tasks: Outdated Assets View

**Input**: Design documents from `/specs/034-outdated-assets/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: This project follows TDD (Test-Driven Development) as per CLAUDE.md constitutional principles. All test tasks are included and MUST be completed before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Web app**: `src/backendng/src/main/kotlin/com/secman/`, `src/frontend/src/`
- **Tests**: `src/backendng/src/test/kotlin/com/secman/`, `src/frontend/tests/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure verification

- [X] T001 Verify Micronaut dependencies for async execution in src/backendng/build.gradle.kts
- [X] T002 [P] Verify Reactor dependencies for SSE in src/backendng/build.gradle.kts
- [X] T003 [P] Verify frontend dependencies (Axios, EventSource polyfill) in src/frontend/package.json

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core entities and infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Domain Entities

- [X] T004 [P] Create OutdatedAssetMaterializedView entity in src/backendng/src/main/kotlin/com/secman/domain/OutdatedAssetMaterializedView.kt
- [X] T005 [P] Create MaterializedViewRefreshJob entity with RefreshJobStatus enum in src/backendng/src/main/kotlin/com/secman/domain/MaterializedViewRefreshJob.kt
- [X] T006 [P] Create RefreshProgressEvent domain event in src/backendng/src/main/kotlin/com/secman/domain/RefreshProgressEvent.kt

### Repositories

- [X] T007 [P] Create OutdatedAssetMaterializedViewRepository interface in src/backendng/src/main/kotlin/com/secman/repository/OutdatedAssetMaterializedViewRepository.kt
- [X] T008 [P] Create MaterializedViewRefreshJobRepository interface in src/backendng/src/main/kotlin/com/secman/repository/MaterializedViewRefreshJobRepository.kt

### Contract Tests for Entities

- [X] T009 [P] Contract test for OutdatedAssetMaterializedView entity schema in src/backendng/src/test/kotlin/com/secman/contract/OutdatedAssetMaterializedViewContractTest.kt
- [X] T010 [P] Contract test for MaterializedViewRefreshJob entity schema in src/backendng/src/test/kotlin/com/secman/contract/MaterializedViewRefreshJobContractTest.kt

### Core Services (Shared)

- [X] T011 Create MaterializedViewRefreshService with async refresh logic in src/backendng/src/main/kotlin/com/secman/service/MaterializedViewRefreshService.kt
- [X] T012 Unit test for MaterializedViewRefreshService in src/backendng/src/test/kotlin/com/secman/service/MaterializedViewRefreshServiceTest.kt

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - View Outdated Assets (Priority: P1) 🎯 MVP

**Goal**: Users can view a fast-loading list of assets with overdue vulnerabilities, filtered by workgroup for VULN users

**Independent Test**: Navigate to "Vuln Management > Outdated Assets", verify page loads in <2 seconds showing assets with vulnerabilities older than configured threshold

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T013 [P] [US1] Contract test for GET /api/outdated-assets endpoint in src/backendng/src/test/kotlin/com/secman/contract/OutdatedAssetControllerContractTest.kt
- [X] T014 [P] [US1] Integration test for workgroup-based access control in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetAccessControlTest.kt
- [X] T015 [P] [US1] Playwright E2E test for outdated assets page navigation in src/frontend/tests/e2e/outdated-assets-navigation.spec.ts

### Implementation for User Story 1

#### Backend - Service Layer

- [ ] T016 [US1] Create OutdatedAssetService with getOutdatedAssets method in src/backendng/src/main/kotlin/com/secman/service/OutdatedAssetService.kt
- [ ] T017 [US1] Unit test for OutdatedAssetService in src/backendng/src/test/kotlin/com/secman/service/OutdatedAssetServiceTest.kt
- [ ] T018 [US1] Implement workgroup filtering logic in OutdatedAssetService
- [ ] T019 [US1] Implement RBAC (ADMIN sees all, VULN sees workgroup-only) in OutdatedAssetService

#### Backend - Controller Layer

- [ ] T020 [US1] Create OutdatedAssetController with GET /api/outdated-assets endpoint in src/backendng/src/main/kotlin/com/secman/controller/OutdatedAssetController.kt
- [ ] T021 [US1] Add @Secured annotation for ADMIN/VULN roles in OutdatedAssetController
- [ ] T022 [US1] Implement pagination, sorting, filtering in OutdatedAssetController
- [ ] T023 [US1] Create OutdatedAssetDto response class in src/backendng/src/main/kotlin/com/secman/dto/OutdatedAssetDto.kt

#### Frontend - API Client

- [ ] T024 [P] [US1] Create outdatedAssetsApi.ts with axios client in src/frontend/src/services/outdatedAssetsApi.ts
- [ ] T025 [P] [US1] Implement getOutdatedAssets function with pagination params in outdatedAssetsApi.ts

#### Frontend - Components

- [ ] T026 [US1] Create outdated-assets.astro page in src/frontend/src/pages/outdated-assets.astro
- [ ] T027 [US1] Create OutdatedAssetsList.tsx component in src/frontend/src/components/OutdatedAssetsList.tsx
- [ ] T028 [US1] Implement table rendering with pagination in OutdatedAssetsList.tsx
- [ ] T029 [US1] Add navigation menu item "Outdated Assets" under "Vuln Management" in BaseLayout.astro

#### Integration

- [ ] T030 [US1] Integration test for complete user journey (login → navigate → view list) in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetIntegrationTest.kt
- [ ] T031 [US1] Performance test verifying <2s page load with 10,000 assets in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetPerformanceTest.kt

**Checkpoint**: At this point, User Story 1 should be fully functional - users can view outdated assets with proper access control

---

## Phase 4: User Story 2 - View Asset Details and Vulnerabilities (Priority: P1)

**Goal**: Users can see detailed vulnerability information for each outdated asset, including severity breakdown and oldest vulnerability age

**Independent Test**: Click on an asset in the Outdated Assets list, verify detailed vulnerability list is displayed with CVE IDs, severity, and days open

### Tests for User Story 2

- [ ] T032 [P] [US2] Contract test for GET /api/outdated-assets/{assetId}/vulnerabilities endpoint in src/backendng/src/test/kotlin/com/secman/contract/OutdatedAssetControllerContractTest.kt
- [ ] T033 [P] [US2] Playwright E2E test for asset detail view in src/frontend/tests/e2e/outdated-assets-detail.spec.ts

### Implementation for User Story 2

#### Backend - Service Layer

- [ ] T034 [US2] Add getAssetVulnerabilities method to OutdatedAssetService in src/backendng/src/main/kotlin/com/secman/service/OutdatedAssetService.kt
- [ ] T035 [US2] Implement access control for asset vulnerabilities (workgroup check) in OutdatedAssetService
- [ ] T036 [US2] Unit test for getAssetVulnerabilities in src/backendng/src/test/kotlin/com/secman/service/OutdatedAssetServiceTest.kt

#### Backend - Controller Layer

- [ ] T037 [US2] Add GET /api/outdated-assets/{assetId}/vulnerabilities endpoint to OutdatedAssetController in src/backendng/src/main/kotlin/com/secman/controller/OutdatedAssetController.kt
- [ ] T038 [US2] Create AssetVulnerabilitiesDto response class in src/backendng/src/main/kotlin/com/secman/dto/AssetVulnerabilitiesDto.kt
- [ ] T039 [US2] Implement pagination for vulnerability list (handle 100+ vulnerabilities) in OutdatedAssetController

#### Frontend - API Client

- [ ] T040 [P] [US2] Add getAssetVulnerabilities function to outdatedAssetsApi.ts in src/frontend/src/services/outdatedAssetsApi.ts

#### Frontend - Components

- [ ] T041 [US2] Create OutdatedAssetDetail.tsx component in src/frontend/src/components/OutdatedAssetDetail.tsx
- [ ] T042 [US2] Implement vulnerability table with severity badges in OutdatedAssetDetail.tsx
- [ ] T043 [US2] Add click handler to OutdatedAssetsList.tsx to show detail view
- [ ] T044 [US2] Implement modal or detail page for vulnerability list in OutdatedAssetDetail.tsx

#### Integration

- [ ] T045 [US2] Integration test for asset detail view with access control in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetDetailIntegrationTest.kt

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently - users can view list and drill into asset details

---

## Phase 5: User Story 3 - Manual Refresh of Outdated Assets (Priority: P2)

**Goal**: Users can manually trigger materialized view refresh and see progress updates in real-time

**Independent Test**: Click "Refresh" button, verify progress indicator shows percentage (e.g., "Refreshing... 35%"), verify data updates after completion

### Tests for User Story 3

- [ ] T046 [P] [US3] Contract test for POST /api/outdated-assets/refresh endpoint in src/backendng/src/test/kotlin/com/secman/contract/OutdatedAssetControllerContractTest.kt
- [ ] T047 [P] [US3] Contract test for GET /api/outdated-assets/refresh-progress SSE endpoint in src/backendng/src/test/kotlin/com/secman/contract/OutdatedAssetRefreshProgressHandlerContractTest.kt
- [ ] T048 [P] [US3] Contract test for GET /api/outdated-assets/refresh-status/{jobId} endpoint in src/backendng/src/test/kotlin/com/secman/contract/OutdatedAssetControllerContractTest.kt
- [ ] T049 [P] [US3] Unit test for concurrent refresh prevention in src/backendng/src/test/kotlin/com/secman/service/MaterializedViewRefreshServiceTest.kt
- [ ] T050 [P] [US3] Playwright E2E test for manual refresh with progress in src/frontend/tests/e2e/outdated-assets-refresh.spec.ts

### Implementation for User Story 3

#### Backend - Refresh Trigger

- [ ] T051 [US3] Add triggerManualRefresh endpoint to OutdatedAssetController (POST /api/outdated-assets/refresh) in src/backendng/src/main/kotlin/com/secman/controller/OutdatedAssetController.kt
- [ ] T052 [US3] Implement concurrent refresh check (return 409 Conflict if running) in OutdatedAssetController
- [ ] T053 [US3] Create RefreshJobResponseDto and RefreshJobConflictDto in src/backendng/src/main/kotlin/com/secman/dto/RefreshJobDto.kt

#### Backend - SSE Progress Handler

- [ ] T054 [US3] Create OutdatedAssetRefreshProgressHandler with SSE endpoint in src/backendng/src/main/kotlin/com/secman/controller/OutdatedAssetRefreshProgressHandler.kt
- [ ] T055 [US3] Implement Sinks.Many multicast pattern for progress broadcasting in OutdatedAssetRefreshProgressHandler
- [ ] T056 [US3] Implement ApplicationEventListener for RefreshProgressEvent in OutdatedAssetRefreshProgressHandler
- [ ] T057 [US3] Create RefreshProgressData DTO with @Serdeable annotation in src/backendng/src/main/kotlin/com/secman/controller/OutdatedAssetRefreshProgressHandler.kt

#### Backend - Polling Endpoint

- [ ] T058 [US3] Add GET /api/outdated-assets/refresh-status/{jobId} endpoint to OutdatedAssetController in src/backendng/src/main/kotlin/com/secman/controller/OutdatedAssetController.kt
- [ ] T059 [US3] Create RefreshJobStatusDto response class in src/backendng/src/main/kotlin/com/secman/dto/RefreshJobDto.kt

#### Backend - Progress Tracking

- [ ] T060 [US3] Implement progress event publishing in MaterializedViewRefreshService (emit events every 1000 assets) in src/backendng/src/main/kotlin/com/secman/service/MaterializedViewRefreshService.kt
- [ ] T061 [US3] Add batch processing with progress updates to refresh logic in MaterializedViewRefreshService

#### Frontend - API Client

- [ ] T062 [P] [US3] Add triggerRefresh function to outdatedAssetsApi.ts in src/frontend/src/services/outdatedAssetsApi.ts
- [ ] T063 [P] [US3] Add getRefreshStatus polling function to outdatedAssetsApi.ts in src/frontend/src/services/outdatedAssetsApi.ts

#### Frontend - Components

- [ ] T064 [US3] Add "Refresh" button to outdated-assets.astro page in src/frontend/src/pages/outdated-assets.astro
- [ ] T065 [US3] Create RefreshProgressIndicator.tsx component in src/frontend/src/components/RefreshProgressIndicator.tsx
- [ ] T066 [US3] Implement EventSource SSE client in RefreshProgressIndicator.tsx
- [ ] T067 [US3] Add progress bar showing percentage (e.g., "Refreshing... 35%") in RefreshProgressIndicator.tsx
- [ ] T068 [US3] Implement button disable during refresh (prevent duplicate requests) in outdated-assets.astro
- [ ] T069 [US3] Add auto-reload of outdated assets list on refresh completion in outdated-assets.astro

#### Integration

- [ ] T070 [US3] Integration test for complete refresh workflow (trigger → progress → completion) in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetRefreshIntegrationTest.kt
- [ ] T071 [US3] Test SSE connection lifecycle (connect → progress → disconnect) in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetRefreshSSETest.kt
- [ ] T072 [US3] Performance test verifying refresh completes <30s for 10,000 assets in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetRefreshPerformanceTest.kt

**Checkpoint**: All three user stories (1, 2, 3) should now be independently functional - users can view, detail, and refresh

---

## Phase 6: User Story 4 - Filter and Search Outdated Assets (Priority: P3)

**Goal**: Users can filter by severity, search by asset name, and sort by various columns for efficient navigation in large datasets

**Independent Test**: Use filter controls to show only Critical vulnerabilities, search for a specific asset name, sort by oldest vulnerability age - verify results update <1 second

### Tests for User Story 4

- [ ] T073 [P] [US4] Unit test for severity filtering logic in src/backendng/src/test/kotlin/com/secman/service/OutdatedAssetServiceTest.kt
- [ ] T074 [P] [US4] Unit test for search filtering logic in src/backendng/src/test/kotlin/com/secman/service/OutdatedAssetServiceTest.kt
- [ ] T075 [P] [US4] Playwright E2E test for filter and search in src/frontend/tests/e2e/outdated-assets-filter.spec.ts

### Implementation for User Story 4

#### Backend - Service Layer

- [ ] T076 [US4] Add minSeverity filter parameter to OutdatedAssetService.getOutdatedAssets in src/backendng/src/main/kotlin/com/secman/service/OutdatedAssetService.kt
- [ ] T077 [US4] Add searchTerm filter parameter to OutdatedAssetService.getOutdatedAssets in src/backendng/src/main/kotlin/com/secman/service/OutdatedAssetService.kt
- [ ] T078 [US4] Implement severity filtering logic (CRITICAL, HIGH, MEDIUM, LOW) in OutdatedAssetService

#### Backend - Repository Queries

- [ ] T079 [US4] Add custom query with severity and search filters to OutdatedAssetMaterializedViewRepository in src/backendng/src/main/kotlin/com/secman/repository/OutdatedAssetMaterializedViewRepository.kt
- [ ] T080 [US4] Optimize query with indexes for filter performance in OutdatedAssetMaterializedViewRepository

#### Frontend - Components

- [ ] T081 [P] [US4] Add severity filter dropdown to OutdatedAssetsList.tsx in src/frontend/src/components/OutdatedAssetsList.tsx
- [ ] T082 [P] [US4] Add search input field to OutdatedAssetsList.tsx in src/frontend/src/components/OutdatedAssetsList.tsx
- [ ] T083 [US4] Add column sort handlers to OutdatedAssetsList.tsx (asset name, overdue count, oldest vuln age)
- [ ] T084 [US4] Implement real-time filtering (debounced search input) in OutdatedAssetsList.tsx
- [ ] T085 [US4] Add "Clear Filters" button to OutdatedAssetsList.tsx

#### Integration

- [ ] T086 [US4] Performance test verifying filter/search response <1s with 10,000 assets in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetFilterPerformanceTest.kt

**Checkpoint**: All user stories (1-4) functional - users have full filtering and search capabilities

---

## Phase 7: User Story 5 - Workgroup-Based Access Control (Priority: P2)

**Goal**: VULN users see only assets from their assigned workgroups, ADMIN users see all assets - ensuring security compliance

**Independent Test**: Log in as VULN user assigned to Workgroup A, verify only Workgroup A outdated assets are shown; log in as ADMIN, verify all assets shown

### Tests for User Story 5

- [ ] T087 [P] [US5] Unit test for workgroup filtering logic in src/backendng/src/test/kotlin/com/secman/service/OutdatedAssetServiceTest.kt
- [ ] T088 [P] [US5] Integration test for VULN user access control in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetAccessControlTest.kt
- [ ] T089 [P] [US5] Integration test for ADMIN user access control in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetAccessControlTest.kt
- [ ] T090 [P] [US5] Playwright E2E test for workgroup filtering in src/frontend/tests/e2e/outdated-assets-workgroups.spec.ts

### Implementation for User Story 5

#### Backend - Service Layer

- [ ] T091 [US5] Implement getUserWorkgroups helper in OutdatedAssetService in src/backendng/src/main/kotlin/com/secman/service/OutdatedAssetService.kt
- [ ] T092 [US5] Add workgroup filtering to getOutdatedAssets query in OutdatedAssetService
- [ ] T093 [US5] Add workgroup filtering to getAssetVulnerabilities query (403 Forbidden if no access) in OutdatedAssetService

#### Backend - Repository Queries

- [ ] T094 [US5] Implement workgroup filtering query using FIND_IN_SET or LIKE in OutdatedAssetMaterializedViewRepository in src/backendng/src/main/kotlin/com/secman/repository/OutdatedAssetMaterializedViewRepository.kt

#### Integration

- [ ] T095 [US5] Integration test verifying VULN user can only access their workgroup assets in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetWorkgroupIntegrationTest.kt
- [ ] T096 [US5] Integration test verifying 403 Forbidden for non-workgroup asset access in src/backendng/src/test/kotlin/com/secman/integration/OutdatedAssetWorkgroupIntegrationTest.kt

**Checkpoint**: All user stories (1-5) complete - full feature functionality with security compliance

---

## Phase 8: CLI Import Integration (Critical for Auto-Refresh)

**Goal**: Trigger materialized view refresh automatically when CLI imports vulnerability data

**Independent Test**: Run `falcon-vulns query --save`, verify materialized view refresh job is triggered in background, verify outdated assets list updates

### Tests for CLI Integration

- [ ] T097 [P] Unit test for refresh trigger in CrowdStrikeVulnerabilityImportService in src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportServiceTest.kt
- [ ] T098 [P] Integration test for CLI import → refresh workflow in src/backendng/src/test/kotlin/com/secman/integration/CrowdStrikeImportRefreshIntegrationTest.kt

### Implementation for CLI Integration

- [ ] T099 Modify CrowdStrikeVulnerabilityImportService.importServerVulnerabilities to trigger async refresh in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt
- [ ] T100 Add materializedViewRefreshService injection to CrowdStrikeVulnerabilityImportService in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt
- [ ] T101 Call triggerAsyncRefresh("CLI Import") after line 84 (before return statement) in CrowdStrikeVulnerabilityImportService

**Checkpoint**: CLI imports now automatically trigger refresh - no manual intervention needed

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T102 [P] Add "Last updated: X minutes ago" timestamp display to outdated-assets.astro page in src/frontend/src/pages/outdated-assets.astro
- [ ] T103 [P] Implement friendly message when no outdated assets exist in OutdatedAssetsList.tsx ("No assets currently have overdue vulnerabilities. Great job!")
- [ ] T104 [P] Add error handling for materialized view refresh failures in MaterializedViewRefreshService
- [ ] T105 [P] Add structured logging for refresh operations in MaterializedViewRefreshService
- [ ] T106 [P] Add metrics tracking (duration, success rate, queue depth) in MaterializedViewRefreshService
- [ ] T107 [P] Add SSE connection error handling and reconnection logic in RefreshProgressIndicator.tsx
- [ ] T108 [P] Implement timeout handling (2-minute timeout for refresh) in MaterializedViewRefreshService
- [ ] T109 [P] Add database indexes verification script for performance in src/backendng/src/main/resources/db/migration/
- [ ] T110 Code cleanup and refactoring across all new files
- [ ] T111 [P] Update CLAUDE.md with Outdated Assets feature documentation
- [ ] T112 Run quickstart.md validation (verify all scenarios work)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phases 3-7)**: All depend on Foundational phase completion
  - User Story 1 (P1) → User Story 2 (P1): Sequential (US2 needs US1 list component)
  - User Story 3 (P2) → Can start after Foundation (independent refresh feature)
  - User Story 4 (P3) → Depends on User Story 1 (adds filtering to list)
  - User Story 5 (P2) → Can start after Foundation (access control is independent)
- **CLI Integration (Phase 8)**: Depends on User Story 3 completion (needs refresh service)
- **Polish (Phase 9)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P1)**: Depends on User Story 1 (needs list component to add detail view)
- **User Story 3 (P2)**: Can start after Foundational (Phase 2) - Independent feature
- **User Story 4 (P3)**: Depends on User Story 1 (adds filtering to existing list)
- **User Story 5 (P2)**: Can start after Foundational (Phase 2) - Independent access control

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD requirement)
- Contract tests before implementation
- Unit tests before implementation
- Models/entities before services
- Services before controllers
- Backend before frontend (API must exist for frontend to call)
- Integration tests after core implementation

### Parallel Opportunities

**Within Foundational Phase (Phase 2)**:
- All entity creation tasks (T004, T005, T006) can run in parallel
- All repository creation tasks (T007, T008) can run in parallel
- All contract tests (T009, T010) can run in parallel

**Within User Story 1 (Phase 3)**:
- All test tasks (T013, T014, T015) can run in parallel
- Frontend API client (T024, T025) parallel with backend service if contract is clear

**Within User Story 2 (Phase 4)**:
- Contract tests (T032, T033) can run in parallel
- Frontend API client (T040) parallel with backend controller work

**Within User Story 3 (Phase 5)**:
- All contract tests (T046, T047, T048, T049, T050) can run in parallel
- Frontend API client tasks (T062, T063) can run in parallel

**Within User Story 4 (Phase 6)**:
- All unit tests (T073, T074, T075) can run in parallel
- Frontend filter components (T081, T082) can run in parallel

**Within User Story 5 (Phase 7)**:
- All test tasks (T087, T088, T089, T090) can run in parallel

**Within CLI Integration (Phase 8)**:
- Test tasks (T097, T098) can run in parallel

**Within Polish (Phase 9)**:
- All tasks marked [P] can run in parallel

**Cross-Story Parallelization** (if team capacity allows):
- After Foundational phase completes:
  - Team A: User Story 1 + User Story 2 (sequential dependency)
  - Team B: User Story 3 (independent)
  - Team C: User Story 5 (independent)
  - Then: User Story 4 (after US1 completes)

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together (TDD - tests first):
Task T013: "Contract test for GET /api/outdated-assets endpoint"
Task T014: "Integration test for workgroup-based access control"
Task T015: "Playwright E2E test for outdated assets page navigation"

# After tests written and failing, backend implementation:
Task T016: "Create OutdatedAssetService with getOutdatedAssets method"
Task T017: "Unit test for OutdatedAssetService"

# Parallel frontend API client work (if API contract is clear):
Task T024: "Create outdatedAssetsApi.ts with axios client"
Task T025: "Implement getOutdatedAssets function with pagination params"
```

---

## Parallel Example: Foundational Phase

```bash
# Launch all entity creation together:
Task T004: "Create OutdatedAssetMaterializedView entity"
Task T005: "Create MaterializedViewRefreshJob entity"
Task T006: "Create RefreshProgressEvent domain event"

# Launch all repository creation together:
Task T007: "Create OutdatedAssetMaterializedViewRepository interface"
Task T008: "Create MaterializedViewRefreshJobRepository interface"

# Launch all contract tests together:
Task T009: "Contract test for OutdatedAssetMaterializedView entity schema"
Task T010: "Contract test for MaterializedViewRefreshJob entity schema"
```

---

## Implementation Strategy

### MVP First (User Stories 1-2 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1 (View Outdated Assets)
4. Complete Phase 4: User Story 2 (View Asset Details)
5. **STOP and VALIDATE**: Test User Stories 1-2 independently
6. Deploy/demo MVP (core viewing functionality)

**MVP Scope**: Users can view outdated assets list and drill into asset details - this delivers immediate value without refresh or advanced filtering features.

### Incremental Delivery (All User Stories)

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 + User Story 2 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 3 (Manual Refresh) → Test independently → Deploy/Demo
4. Add User Story 5 (Workgroup Access) → Test independently → Deploy/Demo
5. Add User Story 4 (Filter/Search) → Test independently → Deploy/Demo
6. Add CLI Integration (Phase 8) → Test independently → Deploy/Demo
7. Polish (Phase 9) → Final deployment

Each increment adds value without breaking previous functionality.

### Parallel Team Strategy

With multiple developers:

1. **Team completes Setup + Foundational together** (Phases 1-2)
2. **Once Foundational is done**:
   - **Developer A**: User Stories 1 + 2 (sequential, tightly coupled)
   - **Developer B**: User Story 3 (Manual Refresh - independent)
   - **Developer C**: User Story 5 (Workgroup Access - independent)
3. **After US1 completes**:
   - **Developer D**: User Story 4 (Filter/Search - depends on US1)
4. **After US3 completes**:
   - **Any developer**: CLI Integration (Phase 8)
5. **Team completes Polish together** (Phase 9)

---

## Task Summary

- **Total Tasks**: 112
- **Phase 1 (Setup)**: 3 tasks
- **Phase 2 (Foundational)**: 9 tasks (BLOCKS all user stories)
- **Phase 3 (User Story 1 - P1)**: 19 tasks
- **Phase 4 (User Story 2 - P1)**: 14 tasks
- **Phase 5 (User Story 3 - P2)**: 27 tasks
- **Phase 6 (User Story 4 - P3)**: 14 tasks
- **Phase 7 (User Story 5 - P2)**: 10 tasks
- **Phase 8 (CLI Integration)**: 5 tasks
- **Phase 9 (Polish)**: 11 tasks

**Parallelizable Tasks**: 48 tasks marked [P] (42.9%)

**MVP Scope** (User Stories 1-2): 42 tasks (37.5% of total)

**Independent Test Criteria**:
- ✅ User Story 1: Navigate to page, verify list loads <2s
- ✅ User Story 2: Click asset, verify vulnerabilities shown
- ✅ User Story 3: Click refresh, verify progress indicator
- ✅ User Story 4: Use filters, verify results update <1s
- ✅ User Story 5: Login as VULN user, verify workgroup filtering

---

## Notes

- [P] tasks = different files, no dependencies within the same batch
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Follow TDD: Write tests first, verify they FAIL, then implement
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Performance targets: <2s page load, <30s refresh, <1s filter response
