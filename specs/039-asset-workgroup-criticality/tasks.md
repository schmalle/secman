# Tasks: Asset and Workgroup Criticality Classification

**Input**: Design documents from `/specs/039-asset-workgroup-criticality/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Following TDD principle (Principle II), tests MUST be written before implementation. Test tasks are included per constitutional requirement.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Web app** (per plan.md): `src/backendng/`, `src/frontend/`
- Backend: `src/backendng/src/main/kotlin/com/secman/`
- Frontend: `src/frontend/src/`
- Tests: `src/backendng/src/test/kotlin/com/secman/`, `src/frontend/tests/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify environment and prepare for implementation

- [X] T001 Verify MariaDB connection and Hibernate configuration in `src/backendng/src/main/resources/application.yml`
- [X] T002 Verify frontend development environment (npm, Astro 5.14, React 19) in `src/frontend/`
- [X] T003 [P] Create backup of current database schema for rollback capability

**Checkpoint**: Environment verified - implementation can begin

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core criticality infrastructure that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Foundational Tests (TDD - Write These FIRST)

- [ ] T004 [P] Write unit tests for Criticality enum helper methods in `src/backendng/src/test/kotlin/com/secman/domain/CriticalityTest.kt`

### Foundational Implementation

- [ ] T005 Create Criticality enum with helper methods (displayName, bootstrapColor, icon, isHigherThan) in `src/backendng/src/main/kotlin/com/secman/domain/Criticality.kt`
- [ ] T006 Run unit tests for Criticality enum - verify all tests pass before proceeding

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Set Workgroup Baseline Criticality (Priority: P1) 🎯 MVP

**Goal**: Enable security administrators to assign criticality levels to workgroups with inheritance to assets

**Independent Test**: Create/edit a workgroup, set criticality level, verify assets inherit the criticality. Fully functional without P2-P5 features.

### Tests for User Story 1 (TDD - Write These FIRST) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T007 [P] [US1] Write unit test for Workgroup default criticality (MEDIUM) in `src/backendng/src/test/kotlin/com/secman/domain/WorkgroupTest.kt`
- [ ] T008 [P] [US1] Write unit test for Asset effectiveCriticality when null (inherits from workgroup) in `src/backendng/src/test/kotlin/com/secman/domain/AssetTest.kt`
- [ ] T009 [P] [US1] Write unit test for Asset effectiveCriticality with multiple workgroups (highest wins) in `src/backendng/src/test/kotlin/com/secman/domain/AssetTest.kt`
- [ ] T010 [P] [US1] Write unit test for Asset effectiveCriticality defaults to MEDIUM when no workgroups in `src/backendng/src/test/kotlin/com/secman/domain/AssetTest.kt`
- [ ] T011 [P] [US1] Write service test for WorkgroupService.updateWorkgroup with criticality change in `src/backendng/src/test/kotlin/com/secman/service/WorkgroupServiceTest.kt`
- [ ] T012 [P] [US1] Write contract test for POST /api/workgroups with criticality in request in `src/backendng/src/test/kotlin/com/secman/controller/WorkgroupControllerTest.kt`
- [ ] T013 [P] [US1] Write contract test for PUT /api/workgroups/{id} updating criticality in `src/backendng/src/test/kotlin/com/secman/controller/WorkgroupControllerTest.kt`
- [ ] T014 [P] [US1] Write contract test for GET /api/workgroups returning criticality in response in `src/backendng/src/test/kotlin/com/secman/controller/WorkgroupControllerTest.kt`

### Backend Implementation for User Story 1

- [ ] T015 [P] [US1] Add criticality field to Workgroup entity in `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`
- [ ] T016 [P] [US1] Add criticality and effectiveCriticality fields to Asset entity in `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`
- [ ] T017 [US1] Run domain unit tests - verify Workgroup and Asset tests pass
- [ ] T018 [P] [US1] Update CreateWorkgroupRequest DTO to include criticality field in `src/backendng/src/main/kotlin/com/secman/controller/dto/WorkgroupDtos.kt`
- [ ] T019 [P] [US1] Update UpdateWorkgroupRequest DTO to include optional criticality field in `src/backendng/src/main/kotlin/com/secman/controller/dto/WorkgroupDtos.kt`
- [ ] T020 [P] [US1] Update WorkgroupListResponse to include criticality in response in `src/backendng/src/main/kotlin/com/secman/controller/dto/WorkgroupDtos.kt`
- [ ] T021 [US1] Update WorkgroupService.create to handle criticality parameter in `src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt`
- [ ] T022 [US1] Update WorkgroupService.update to handle criticality changes in `src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt`
- [ ] T023 [US1] Run service tests - verify WorkgroupService tests pass
- [ ] T024 [US1] Update WorkgroupController POST endpoint to accept criticality in request body in `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`
- [ ] T025 [US1] Update WorkgroupController PUT endpoint to accept criticality in request body in `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`
- [ ] T026 [US1] Update WorkgroupController GET endpoints to include criticality in responses in `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`
- [ ] T027 [US1] Run contract tests - verify WorkgroupController tests pass
- [ ] T028 [US1] Start backend application and verify Hibernate creates workgroup.criticality and asset.criticality columns via auto-migration

### Frontend Implementation for User Story 1

- [ ] T029 [P] [US1] Create CriticalityBadge component with color+icon+text accessibility features in `src/frontend/src/components/CriticalityBadge.tsx`
- [ ] T030 [US1] Update WorkgroupManagement component - add criticality dropdown to create modal in `src/frontend/src/components/WorkgroupManagement.tsx`
- [ ] T031 [US1] Update WorkgroupManagement component - add criticality dropdown to edit modal in `src/frontend/src/components/WorkgroupManagement.tsx`
- [ ] T032 [US1] Update WorkgroupManagement component - add CriticalityBadge to table display in `src/frontend/src/components/WorkgroupManagement.tsx`
- [ ] T033 [US1] Update WorkgroupManagement component - add progress indicator for criticality update propagation in `src/frontend/src/components/WorkgroupManagement.tsx`
- [ ] T034 [US1] Update WorkgroupManagement component - add criticality filter dropdown in `src/frontend/src/components/WorkgroupManagement.tsx`
- [ ] T035 [US1] Update WorkgroupManagement component - add criticality sort capability in `src/frontend/src/components/WorkgroupManagement.tsx`
- [ ] T036 [P] [US1] Update API types to include criticality fields in `src/frontend/src/services/api.ts`

**Checkpoint**: User Story 1 complete - workgroups have criticality, assets inherit, UI displays badges with filters/sorting

---

## Phase 4: User Story 2 - Override Asset Criticality (Priority: P2)

**Goal**: Enable asset owners to override workgroup criticality for specific assets requiring individual classification

**Independent Test**: Edit an asset to set explicit criticality override, verify override displays independently of workgroup criticality. Clear override to revert to inheritance.

### Tests for User Story 2 (TDD - Write These FIRST) ⚠️

- [ ] T037 [P] [US2] Write unit test for Asset effectiveCriticality with explicit override in `src/backendng/src/test/kotlin/com/secman/domain/AssetTest.kt`
- [ ] T038 [P] [US2] Write unit test verifying override is independent of workgroup changes in `src/backendng/src/test/kotlin/com/secman/domain/AssetTest.kt`
- [ ] T039 [P] [US2] Write service test for AssetService.update with criticality override in `src/backendng/src/test/kotlin/com/secman/service/AssetServiceTest.kt`
- [ ] T040 [P] [US2] Write service test for AssetService.update clearing criticality (revert to inheritance) in `src/backendng/src/test/kotlin/com/secman/service/AssetServiceTest.kt`
- [ ] T041 [P] [US2] Write contract test for POST /api/assets with explicit criticality in `src/backendng/src/test/kotlin/com/secman/controller/AssetControllerTest.kt`
- [ ] T042 [P] [US2] Write contract test for PUT /api/assets/{id} updating criticality override in `src/backendng/src/test/kotlin/com/secman/controller/AssetControllerTest.kt`
- [ ] T043 [P] [US2] Write contract test for GET /api/assets returning both criticality and effectiveCriticality in `src/backendng/src/test/kotlin/com/secman/controller/AssetControllerTest.kt`

### Backend Implementation for User Story 2

- [ ] T044 [US2] Run domain unit tests for asset override scenarios - verify tests pass
- [ ] T045 [P] [US2] Update CreateAssetRequest DTO to include optional criticality field in `src/backendng/src/main/kotlin/com/secman/controller/dto/AssetDtos.kt`
- [ ] T046 [P] [US2] Update UpdateAssetRequest DTO to include optional criticality field in `src/backendng/src/main/kotlin/com/secman/controller/dto/AssetDtos.kt`
- [ ] T047 [P] [US2] Update AssetListResponse to include both criticality and effectiveCriticality in `src/backendng/src/main/kotlin/com/secman/controller/dto/AssetDtos.kt`
- [ ] T048 [US2] Update AssetService.create to handle optional criticality parameter in `src/backendng/src/main/kotlin/com/secman/service/AssetService.kt`
- [ ] T049 [US2] Update AssetService.update to handle criticality override changes (including clearing to null) in `src/backendng/src/main/kotlin/com/secman/service/AssetService.kt`
- [ ] T050 [US2] Run service tests - verify AssetService tests pass
- [ ] T051 [US2] Update AssetController POST endpoint to accept optional criticality in request body in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- [ ] T052 [US2] Update AssetController PUT endpoint to accept optional criticality in request body in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- [ ] T053 [US2] Update AssetController GET endpoints to include both criticality and effectiveCriticality in responses in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- [ ] T054 [US2] Add RBAC validation - only ADMIN/VULN can modify asset criticality in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- [ ] T055 [US2] Run contract tests - verify AssetController tests pass

### Frontend Implementation for User Story 2

- [ ] T056 [US2] Update AssetManagement component - add criticality dropdown to create modal with "Inherit from workgroup" option in `src/frontend/src/components/AssetManagement.tsx`
- [ ] T057 [US2] Update AssetManagement component - add criticality dropdown to edit modal with clear capability in `src/frontend/src/components/AssetManagement.tsx`
- [ ] T058 [US2] Update AssetManagement component - add CriticalityBadge with inheritance indicator to table display in `src/frontend/src/components/AssetManagement.tsx`
- [ ] T059 [US2] Update AssetManagement component - show source workgroup name for inherited criticality in asset detail view in `src/frontend/src/components/AssetManagement.tsx`
- [ ] T060 [US2] Update AssetManagement component - visually distinguish inherited vs override badges (opacity/styling) in `src/frontend/src/components/AssetManagement.tsx`

**Checkpoint**: User Story 2 complete - assets can override workgroup criticality, UI clearly shows inheritance vs override

---

## Phase 5: User Story 3 - Filter and Sort by Criticality (Priority: P3)

**Goal**: Enable security team to filter and sort assets/workgroups by criticality for efficient prioritization workflows

**Independent Test**: Apply criticality filters and sorts, verify correct results with both inherited and explicit criticality values

### Tests for User Story 3 (TDD - Write These FIRST) ⚠️

- [ ] T061 [P] [US3] Write contract test for GET /api/workgroups?criticality=CRITICAL in `src/backendng/src/test/kotlin/com/secman/controller/WorkgroupControllerTest.kt`
- [ ] T062 [P] [US3] Write contract test for GET /api/assets?effectiveCriticality=CRITICAL returning both explicit and inherited in `src/backendng/src/test/kotlin/com/secman/controller/AssetControllerTest.kt`
- [ ] T063 [P] [US3] Write contract test for GET /api/assets sorted by criticality (CRITICAL first) in `src/backendng/src/test/kotlin/com/secman/controller/AssetControllerTest.kt`

### Backend Implementation for User Story 3

- [ ] T064 [US3] Add criticality query parameter support to WorkgroupController GET /api/workgroups in `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`
- [ ] T065 [US3] Add effectiveCriticality filter support to AssetController GET /api/assets in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- [ ] T066 [US3] Add criticality sort support to WorkgroupController GET /api/workgroups in `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`
- [ ] T067 [US3] Add criticality sort support to AssetController GET /api/assets in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- [ ] T068 [US3] Implement service-layer filtering logic for effectiveCriticality (compute on-demand) in `src/backendng/src/main/kotlin/com/secman/service/AssetService.kt`
- [ ] T069 [US3] Run contract tests - verify filter and sort tests pass
- [ ] T070 [US3] Create database indexes for workgroup.criticality and asset.criticality if not auto-created by Hibernate

### Frontend Implementation for User Story 3

- [ ] T071 [US3] Frontend filter/sort already implemented in US1 (T034, T035) - verify workgroup filter works with backend
- [ ] T072 [US3] Update AssetManagement component - add effectiveCriticality filter dropdown in `src/frontend/src/components/AssetManagement.tsx`
- [ ] T073 [US3] Update AssetManagement component - add criticality sort capability in `src/frontend/src/components/AssetManagement.tsx`
- [ ] T074 [US3] Verify filter shows both explicit and inherited assets for selected criticality level
- [ ] T075 [US3] Update export functionality to include criticality column in exported Excel in `src/backendng/src/main/kotlin/com/secman/service/ExportService.kt`

**Checkpoint**: User Story 3 complete - users can efficiently filter and sort by criticality across workgroups and assets

---

## Phase 6: User Story 4 - Criticality-Based Notifications (Priority: P4)

**Goal**: Integrate with Feature 035 notification system to prioritize alerts based on asset criticality

**Independent Test**: Create vulnerability on CRITICAL asset, verify immediate notification sent. Create vulnerability on MEDIUM asset, verify standard notification schedule used.

**Dependencies**: Requires Feature 035 (NotificationService) to be available

### Tests for User Story 4 (TDD - Write These FIRST) ⚠️

- [ ] T076 [P] [US4] Write integration test for immediate notification on CRITICAL asset vulnerability in `src/backendng/src/test/kotlin/com/secman/integration/CriticalityNotificationIntegrationTest.kt`
- [ ] T077 [P] [US4] Write integration test for standard schedule notification on MEDIUM asset vulnerability in `src/backendng/src/test/kotlin/com/secman/integration/CriticalityNotificationIntegrationTest.kt`
- [ ] T078 [P] [US4] Write unit test for notification routing logic based on asset criticality in `src/backendng/src/test/kotlin/com/secman/service/NotificationServiceTest.kt`

### Backend Implementation for User Story 4

- [ ] T079 [US4] Update NotificationService to check asset.effectiveCriticality before routing in `src/backendng/src/main/kotlin/com/secman/service/NotificationService.kt`
- [ ] T080 [US4] Implement immediate notification queue for CRITICAL asset vulnerabilities in `src/backendng/src/main/kotlin/com/secman/service/NotificationService.kt`
- [ ] T081 [US4] Update sendNewVulnerabilityNotification method to use criticality-based routing in `src/backendng/src/main/kotlin/com/secman/service/NotificationService.kt`
- [ ] T082 [US4] Run integration tests - verify criticality-based notification routing works
- [ ] T083 [P] [US4] Update notification templates to include asset criticality in email body (optional enhancement) in `src/backendng/src/main/resources/email-templates/`

**Checkpoint**: User Story 4 complete - notifications prioritize CRITICAL assets with immediate delivery

---

## Phase 7: User Story 5 - Criticality in Dashboards and Reports (Priority: P5)

**Goal**: Enhance dashboards and reports with criticality-based statistics and filtering

**Independent Test**: View outdated assets dashboard, filter by criticality, verify correct statistics. Export report, verify criticality column included.

**Dependencies**: Requires Feature 034 (OutdatedAssetsDashboard) and Feature 036 (Vulnerability Statistics) to be available

### Tests for User Story 5 (TDD - Write These FIRST) ⚠️

- [ ] T084 [P] [US5] Write E2E test for outdated assets dashboard criticality filter in `src/frontend/tests/e2e/criticality-dashboard.spec.ts`
- [ ] T085 [P] [US5] Write contract test for GET /api/outdated-assets?criticality=CRITICAL in `src/backendng/src/test/kotlin/com/secman/controller/OutdatedAssetsControllerTest.kt`

### Backend Implementation for User Story 5

- [ ] T086 [US5] Add criticality filter support to OutdatedAssetsController in `src/backendng/src/main/kotlin/com/secman/controller/OutdatedAssetsController.kt`
- [ ] T087 [US5] Update OutdatedAssetsMaterializedView to include effectiveCriticality column in `src/backendng/src/main/kotlin/com/secman/domain/OutdatedAssetMaterializedView.kt`
- [ ] T088 [US5] Update MaterializedViewRefreshJob to calculate effectiveCriticality during refresh in `src/backendng/src/main/kotlin/com/secman/service/MaterializedViewRefreshService.kt`
- [ ] T089 [P] [US5] Add criticality statistics endpoint (count by criticality level) to AssetController in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- [ ] T090 [US5] Run contract tests for dashboard endpoints - verify criticality filtering works

### Frontend Implementation for User Story 5

- [ ] T091 [US5] Update OutdatedAssetsDashboard component - add criticality filter dropdown in `src/frontend/src/components/OutdatedAssetsDashboard.tsx`
- [ ] T092 [US5] Update OutdatedAssetsDashboard component - display CriticalityBadge in asset list in `src/frontend/src/components/OutdatedAssetsDashboard.tsx`
- [ ] T093 [US5] Update OutdatedAssetsDashboard component - add criticality statistics summary (X CRITICAL, Y HIGH, Z MEDIUM, W LOW) in `src/frontend/src/components/OutdatedAssetsDashboard.tsx`
- [ ] T094 [P] [US5] Add criticality column to exported reports (if not already done in T075) in `src/backendng/src/main/kotlin/com/secman/service/ExportService.kt`
- [ ] T095 [US5] Run E2E tests - verify dashboard criticality features work end-to-end

**Checkpoint**: User Story 5 complete - dashboards and reports include criticality-based insights

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Final integration, performance optimization, and validation

### Performance & Validation

- [ ] T096 [P] Verify database indexes exist for workgroup.criticality and asset.criticality via `SHOW INDEX` queries
- [ ] T097 [P] Run performance benchmark - verify asset list filtering completes in <2s for 10K assets
- [ ] T098 [P] Run performance benchmark - verify dashboard loads in <3s for 1K assets
- [ ] T099 [P] Run performance benchmark - verify workgroup criticality propagation completes in <5s for 1K assets

### Documentation & Deployment

- [ ] T100 [P] Update CLAUDE.md with new criticality functionality in `CLAUDE.md`
- [ ] T101 [P] Create migration verification script to check all workgroups have criticality=MEDIUM after deployment
- [ ] T102 [P] Document rollback procedure (DROP COLUMN commands) in quickstart.md

### Full Test Suite Execution

- [ ] T103 Run full backend test suite (`./gradlew test`) - verify all tests pass
- [ ] T104 Run frontend E2E test suite (`npm run test:e2e`) - verify all Playwright tests pass
- [ ] T105 Verify test coverage meets ≥80% threshold per constitutional requirement

**Final Checkpoint**: Feature complete and ready for production deployment

---

## Implementation Strategy

### MVP Scope (User Story 1 Only)

For minimal viable product, implement **only Phase 1-3** (User Story 1):
- Workgroups have criticality
- Assets inherit from workgroups
- UI displays badges with basic filters/sorting
- **Estimated effort**: 4-6 hours

This delivers immediate value: bulk asset classification via workgroups.

### Incremental Delivery

- **Sprint 1** (MVP): User Story 1 (T001-T036) - 4-6 hours
- **Sprint 2**: User Story 2 (T037-T060) - 2-3 hours
- **Sprint 3**: User Story 3 (T061-T075) - 2-3 hours
- **Sprint 4**: User Story 4 (T076-T083) - 1-2 hours
- **Sprint 5**: User Story 5 (T084-T095) - 2-3 hours
- **Sprint 6**: Polish (T096-T105) - 1-2 hours

**Total estimated effort**: 12-19 hours following TDD workflow

### Parallel Execution Opportunities

Within each user story phase, tasks marked [P] can run in parallel:

**User Story 1 Example**:
- Tests T007-T014 can all run in parallel (different test files)
- Backend tasks T015-T016 can run in parallel (different entity files)
- Backend DTO tasks T018-T020 can run in parallel (same file, different sections)
- Frontend component tasks T029 and T036 can run in parallel (different files)

**Key Constraint**: Tasks affecting the same file MUST run sequentially unless they modify independent sections.

### User Story Dependencies

```
Phase 1 (Setup)
    ↓
Phase 2 (Foundational - Criticality enum)
    ↓
Phase 3 (US1 - Workgroup Criticality) ← MVP
    ↓
Phase 4 (US2 - Asset Override) ← Independent, depends on US1
    ↓
Phase 5 (US3 - Filter/Sort) ← Independent, depends on US1+US2
    ↓
Phase 6 (US4 - Notifications) ← Depends on US1+US2, Feature 035
    ↓
Phase 7 (US5 - Dashboards) ← Depends on US1+US2, Features 034+036
    ↓
Phase 8 (Polish)
```

**Note**: User Stories 2-5 can theoretically be implemented in parallel AFTER US1 is complete, but sequential implementation is recommended to reduce complexity and enable incremental testing.

---

## Task Summary

- **Total Tasks**: 105
- **User Story 1 (MVP)**: 32 tasks (T004-T036)
- **User Story 2**: 24 tasks (T037-T060)
- **User Story 3**: 15 tasks (T061-T075)
- **User Story 4**: 8 tasks (T076-T083)
- **User Story 5**: 12 tasks (T084-T095)
- **Polish**: 10 tasks (T096-T105)
- **Parallel opportunities**: 42 tasks marked [P]

**Constitutional Compliance**: All tasks follow TDD principle with tests written before implementation (Principle II). RBAC enforcement tasks included (Principle V). Database migration follows schema evolution principle (Principle VI).
