---
description: "Task list for Asset Bulk Operations feature implementation"
---

# Tasks: Asset Bulk Operations

**Input**: Design documents from `/specs/029-asset-bulk-operations/`
**Prerequisites**: plan.md (constitution check), spec.md (user stories), research.md (technical decisions), data-model.md (entities/DTOs), contracts/ (API specs), quickstart.md (implementation guide)

**Tests**: This feature follows TDD approach per Constitution Principle II. All tests MUST be written FIRST and FAIL before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions
- Backend: `src/backendng/src/main/kotlin/com/secman/`
- Backend Tests: `src/backendng/src/test/kotlin/com/secman/`
- Frontend: `src/frontend/src/`
- Frontend Tests: `src/frontend/tests/e2e/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and verification of dependencies

- [X] T001 Verify Apache POI 5.3 dependency in src/backendng/build.gradle.kts (should exist from Feature 013/016) ✅ Found: poi-ooxml:5.4.1
- [X] T002 [P] Verify existing ImportResult DTO in src/backendng/src/main/kotlin/com/secman/dto/ImportResult.kt ✅ Found in UserMappingImportService.kt (will create proper DTO in Phase 2)
- [X] T003 [P] Verify workgroup filtering methods in src/backendng/src/main/kotlin/com/secman/service/AssetService.kt ✅ Found AssetFilterService.getAccessibleAssets()

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core DTOs and utilities that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 [P] Create BulkDeleteResult DTO in src/backendng/src/main/kotlin/com/secman/dto/BulkDeleteResult.kt ✅ Complete with companion factory method
- [X] T005 [P] Create AssetExportDto DTO in src/backendng/src/main/kotlin/com/secman/dto/AssetExportDto.kt ✅ Complete with fromAsset() factory
- [X] T006 [P] Create AssetImportDto DTO in src/backendng/src/main/kotlin/com/secman/dto/AssetImportDto.kt ✅ Complete with toAsset() conversion method
- [X] BONUS: Created ImportResult DTO in src/backendng/src/main/kotlin/com/secman/dto/ImportResult.kt ✅ Extracted from inline definitions

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Bulk Delete Assets (Priority: P1) 🎯 MVP

**Goal**: Enable ADMIN users to delete all assets with single button click and confirmation modal

**Independent Test**: Login as ADMIN user, navigate to Asset Management page, click "Delete All Assets", confirm, verify all assets deleted and count shows 0

### Tests for User Story 1 (TDD - Write FIRST, Ensure They FAIL)

- [SKIPPED] T007 [P] [US1] Create AssetBulkDeleteContractTest.kt - Tests skipped per user request
- [SKIPPED] T008 [P] [US1] Create AssetServiceTest.kt bulk delete tests - Tests skipped per user request

### Backend Implementation for User Story 1

- [X] T009 [US1] Implement deleteAllAssets() method in AssetBulkDeleteService.kt ✅ Complete with @Transactional, AtomicBoolean semaphore, manual cascade delete, entityManager.clear()
- [X] T010 [US1] Add DELETE /api/assets/bulk endpoint in AssetController.kt ✅ Complete with @Secured("ADMIN"), ConcurrentOperationException handling (409), BulkDeleteResult response

### Frontend Implementation for User Story 1

- [X] T011 [P] [US1] Create BulkDeleteConfirmModal.tsx ✅ Complete with checkbox acknowledgment, loading spinner, ESC key handling, warning text
- [X] T012 [US1] Update AssetManagement.tsx ✅ Complete with bulk delete button (isAdmin + assetCount > 0), modal state, handleBulkDelete(), success message display
- [X] T013 [US1] Add bulkDeleteAssets() to assetService.ts ✅ Complete with DELETE /api/assets/bulk call, error handling for 403/409/500

**Checkpoint**: ✅ User Story 1 complete - ADMIN users can delete all assets with confirmation modal

---

## Phase 4: User Story 2 - Export Assets to File (Priority: P1) 🎯 MVP

**Goal**: Enable users to export their accessible assets to Excel file for backup, reporting, or migration

**Independent Test**: Login as any user, navigate to I/O > Export, select "Assets", click export, verify .xlsx file downloads with all asset fields and correct workgroup filtering

### Tests for User Story 2 (TDD - Write FIRST, Ensure They FAIL)

- [ ] T014 [P] [US2] Create AssetExportContractTest.kt in src/backendng/src/test/kotlin/com/secman/contract/ with 3 test scenarios: 200 success (binary Excel response), 400 no data (empty asset list), 401 unauthorized
- [ ] T015 [P] [US2] Create AssetExportServiceTest.kt in src/backendng/src/test/kotlin/com/secman/service/ with scenarios: workgroup filtering (ADMIN vs non-ADMIN), Excel format validation (header row, data rows, column widths), empty asset list error

### Backend Implementation for User Story 2

- [ ] T016 [P] [US2] Create AssetExportService.kt in src/backendng/src/main/kotlin/com/secman/service/ with exportAssets(authentication) method applying workgroup filtering (ADMIN sees all, non-ADMIN sees workgroup+owned assets), convert Asset → AssetExportDto
- [ ] T017 [US2] Add writeToExcel(dtos) method to AssetExportService.kt using SXSSFWorkbook(100) for streaming, create CellStyle objects ONCE before loop (not per cell), write header row + data rows, set fixed column widths (no auto-sizing), dispose() workbook in finally block
- [ ] T018 [US2] Add GET /api/assets/export endpoint in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt (or ExportController.kt if exists) with @Secured(SecurityRule.IS_AUTHENTICATED), return binary Excel with Content-Disposition header, handle 400 error for empty asset list

### Frontend Implementation for User Story 2

- [ ] T019 [P] [US2] Add exportAssets() method to assetService.ts in src/frontend/src/services/ calling GET /api/assets/export, return Blob, handle 400/401/500 errors
- [ ] T020 [US2] Update Export.tsx component in src/frontend/src/components/ to add 'assets' to ExportType union, add export handler calling assetService.exportAssets() and triggering file download with generated filename (assets_export_YYYY-MM-DD.xlsx), pre-select based on URL param (?type=assets)

**Checkpoint**: User Story 2 complete - users can export assets to Excel with workgroup filtering

---

## Phase 5: User Story 3 - Import Assets from File (Priority: P2)

**Goal**: Enable users to import assets from Excel file with validation and duplicate handling

**Independent Test**: Login as any user, navigate to I/O > Import, select "Assets", upload valid .xlsx file, verify assets created in database, import summary shows imported/skipped counts, duplicates handled correctly

### Tests for User Story 3 (TDD - Write FIRST, Ensure They FAIL)

- [ ] T021 [P] [US3] Create AssetImportContractTest.kt in src/backendng/src/test/kotlin/com/secman/contract/ with 3 test scenarios: 200 success (valid file with ImportResult), 400 validation errors (invalid file format, missing headers, file too large), 401 unauthorized
- [ ] T022 [P] [US3] Create AssetImportServiceTest.kt in src/backendng/src/test/kotlin/com/secman/service/ with scenarios: duplicate handling (skip without updating), field validation (name/type/owner required, IP format), workgroup assignment (from column or user default), batch save performance

### Backend Implementation for User Story 3

- [ ] T023 [P] [US3] Create AssetImportService.kt in src/backendng/src/main/kotlin/com/secman/service/ with importFromExcel(stream, authentication) method annotated @Transactional, open XSSFWorkbook, validate headers (case-insensitive: Name, Type, Owner, IP Address, Description, Groups, Cloud Account ID, Cloud Instance ID, OS Version, AD Domain, Workgroups, Created At, Updated At, Last Seen)
- [ ] T024 [US3] Implement row parsing loop in AssetImportService.kt with: parse to AssetImportDto, validate required fields (name/type/owner non-blank), check duplicate by name (skip with error message), resolve workgroup names to entities (case-insensitive), add valid assets to batch list, collect error messages (limit to first 20)
- [ ] T025 [US3] Add batch save logic in AssetImportService.kt with repository.saveAll(validAssets), set manualCreator to importing user, return ImportResult with imported/skipped counts and error list
- [ ] T026 [US3] Add POST /api/import/upload-assets-xlsx endpoint in src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt with @Secured(SecurityRule.IS_AUTHENTICATED), validate file (10MB max, .xlsx extension, content-type), call AssetImportService.importFromExcel(), return ImportResult or 400 error

### Frontend Implementation for User Story 3

- [ ] T027 [P] [US3] Add importAssets(file) method to assetService.ts in src/frontend/src/services/ calling POST /api/import/upload-assets-xlsx with multipart/form-data, return ImportResult, handle 400/401/500 errors
- [ ] T028 [US3] Update Import.tsx component in src/frontend/src/components/ to add 'assets' to ImportType union, add upload handler calling assetService.importAssets(), display ImportResult with imported/skipped counts and error messages (with row numbers), pre-select based on URL param (?type=assets)

**Checkpoint**: User Story 3 complete - users can import assets from Excel with validation and error reporting

---

## Phase 6: User Story 4 - Sidebar Navigation for Asset I/O (Priority: P2)

**Goal**: Add "Assets" links under I/O > Import and I/O > Export in sidebar for easy access

**Independent Test**: Login as any user, view sidebar, expand I/O menu, verify Import and Export sub-items both show "Assets" option, click each link and verify correct page opens with pre-selected type

### Implementation for User Story 4 (No Backend Tests - UI Only)

- [ ] T029 [US4] Update Sidebar.tsx in src/frontend/src/components/ to add "Assets" link under I/O > Import section (href="/import?type=assets"), add "Assets" link under I/O > Export section (href="/export?type=assets"), follow existing pattern from Requirements/Nmap/Masscan/Vulnerabilities links

**Checkpoint**: User Story 4 complete - sidebar navigation provides direct access to asset import/export

---

## Phase 7: User Story 5 - Complete Workflow: Export, Delete, Import (Priority: P3)

**Goal**: Validate end-to-end workflow of export → delete → import with 100% data integrity

**Independent Test**: Login as ADMIN, export 500 assets to file, delete all assets via bulk delete, import previously exported file, verify all 500 assets restored with identical data

### End-to-End Tests for User Story 5

- [ ] T030 [US5] Create asset-bulk-operations.spec.ts in src/frontend/tests/e2e/ with test scenario: (1) login as ADMIN, (2) navigate to Asset Management, (3) export assets and verify download, (4) bulk delete all assets and verify confirmation modal + empty list, (5) navigate to Import, (6) upload previously exported file, (7) verify assets restored with correct count and data integrity
- [ ] T031 [US5] Add additional E2E test scenario in asset-bulk-operations.spec.ts: (1) login as non-ADMIN user, (2) verify bulk delete button not visible, (3) export assets (workgroup-filtered), (4) import assets, (5) verify imported assets assigned to user's workgroups

**Checkpoint**: User Story 5 complete - full workflow validated with E2E tests

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T032 [P] Run backend build verification with ./gradlew build in src/backendng/
- [ ] T033 [P] Run frontend build verification with npm run build in src/frontend/
- [ ] T034 [P] Run backend contract tests with ./gradlew test in src/backendng/
- [ ] T035 [P] Run frontend E2E tests with npx playwright test in src/frontend/
- [ ] T036 [P] Verify linting passes with npm run lint in src/frontend/
- [ ] T037 [P] Validate API contracts match OpenAPI specs in specs/029-asset-bulk-operations/contracts/
- [ ] T038 Code review checklist validation per quickstart.md (no console.log, error messages user-friendly, SXSSFWorkbook disposed, EntityManager cleared)
- [ ] T039 Performance testing: bulk delete 10K assets in <30 seconds, export 10K assets in <15 seconds, import 5K assets in <60 seconds
- [ ] T040 Update CLAUDE.md with new endpoints (DELETE /api/assets/bulk, GET /api/assets/export, POST /api/import/upload-assets-xlsx) and feature summary

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-7)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1 - Bulk Delete)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P1 - Export)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 3 (P2 - Import)**: Can start after Foundational (Phase 2) - No dependencies on other stories (imports work independently of export format)
- **User Story 4 (P2 - Sidebar)**: Can start after Foundational (Phase 2) - No dependencies on other stories (just navigation links)
- **User Story 5 (P3 - Workflow)**: Depends on User Stories 1, 2, and 3 completion (validates integration)

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD)
- DTOs before services (Foundational phase)
- Services before controllers/endpoints
- Backend endpoints before frontend service calls
- Frontend services before UI components
- Story complete before moving to next priority

### Parallel Opportunities

- **Phase 1**: All tasks marked [P] can run in parallel (T002, T003)
- **Phase 2**: All DTO creation tasks marked [P] can run in parallel (T004, T005, T006)
- **Phase 3 (US1)**: Contract tests and service tests can run in parallel (T007, T008), then frontend components can run in parallel (T011 independent of backend)
- **Phase 4 (US2)**: Contract tests and service tests can run in parallel (T014, T015), backend service methods can run in parallel (T016, T017), frontend tasks can run in parallel (T019, T020)
- **Phase 5 (US3)**: Contract tests and service tests can run in parallel (T021, T022), backend service implementation can be split (T023, T024, T025 sequential), frontend tasks can run in parallel (T027, T028)
- **Phase 8**: All verification tasks marked [P] can run in parallel (T032-T037)
- **User Stories**: Once Foundational phase completes, User Stories 1, 2, 3, and 4 can ALL be worked on in parallel by different team members

---

## Parallel Example: User Story 1 (Bulk Delete)

```bash
# Launch all tests for User Story 1 together (TDD - write first):
Task T007: "Create AssetBulkDeleteContractTest.kt in src/backendng/src/test/kotlin/com/secman/contract/"
Task T008: "Create AssetServiceTest.kt bulk delete tests in src/backendng/src/test/kotlin/com/secman/service/"

# After tests fail, implement backend (sequential due to service → controller dependency):
Task T009: "Implement deleteAllAssets() in AssetService.kt"
Task T010: "Add DELETE /api/assets/bulk endpoint in AssetController.kt"

# Frontend can start in parallel with backend or after endpoint ready:
Task T011: "Create BulkDeleteConfirmModal.tsx component" (can do in parallel)
Task T012: "Update AssetManagement.tsx with bulk delete button"
Task T013: "Add bulkDeleteAssets() to assetService.ts"
```

---

## Parallel Example: Multiple User Stories

```bash
# Once Foundational phase (Phase 2) completes, launch all P1 stories in parallel:

# Developer A works on User Story 1 (Bulk Delete):
Tasks T007-T013

# Developer B works on User Story 2 (Export) in parallel:
Tasks T014-T020

# Developer C works on User Story 3 (Import) in parallel:
Tasks T021-T028

# Developer D works on User Story 4 (Sidebar) in parallel:
Task T029

# All stories complete independently, then Developer E validates integration:
# User Story 5 (Workflow E2E Tests):
Tasks T030-T031
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T006) - CRITICAL
3. Complete Phase 3: User Story 1 - Bulk Delete (T007-T013)
4. Complete Phase 4: User Story 2 - Export (T014-T020)
5. **STOP and VALIDATE**: Test bulk delete and export independently
6. Deploy/demo MVP (ADMIN can delete all assets, users can export before deletion)

**Rationale**: US1 + US2 provide immediate value for data backup + cleanup workflow. Import can be added later.

### Incremental Delivery

1. **Foundation** (Phase 1-2) → DTOs ready
2. **MVP Release** (Phase 3-4) → Bulk delete + Export working → Deploy/Demo
3. **Import Release** (Phase 5) → Import working → Deploy/Demo (complete data lifecycle)
4. **Navigation Release** (Phase 6) → Sidebar links → Deploy/Demo (improved UX)
5. **Validation Release** (Phase 7) → E2E workflow tests → Deploy/Demo (full confidence)
6. **Polish** (Phase 8) → Code review + performance validation → Final release

**Rationale**: Each increment adds value without breaking previous functionality. Users can start using bulk delete + export immediately while import is developed.

### Parallel Team Strategy

With 4 developers:

1. **All developers** complete Setup + Foundational together (Phase 1-2)
2. Once Foundational is done:
   - **Developer A**: User Story 1 (Bulk Delete) - T007-T013
   - **Developer B**: User Story 2 (Export) - T014-T020
   - **Developer C**: User Story 3 (Import) - T021-T028
   - **Developer D**: User Story 4 (Sidebar) - T029
3. **All developers** merge and test independently
4. **One developer** runs User Story 5 (E2E Workflow) - T030-T031
5. **All developers** participate in Polish phase - T032-T040

**Rationale**: Maximizes parallelism after foundation is ready. Each developer owns a complete user story.

---

## Notes

- **[P] tasks**: Different files, no dependencies, can run in parallel
- **[Story] label**: Maps task to specific user story for traceability (US1-US5)
- **TDD Required**: ALL tests must be written FIRST and FAIL before implementation (Constitution Principle II)
- **File Paths**: All tasks include exact file paths for clarity
- **Independent Stories**: Each user story (except US5) should be independently completable and testable
- **Commit Strategy**: Commit after each task or logical group (e.g., after T009+T010 for backend bulk delete)
- **Checkpoint Validation**: Stop at each checkpoint to validate story independently before proceeding
- **Performance Targets**: US1: <30s (10K assets), US2: <15s (10K assets), US3: <60s (5K assets)
- **Avoid**: Vague tasks, same file conflicts, cross-story dependencies that break independence

---

## Task Summary

- **Total Tasks**: 40
- **User Story 1 (P1 - Bulk Delete)**: 7 tasks (T007-T013)
- **User Story 2 (P1 - Export)**: 7 tasks (T014-T020)
- **User Story 3 (P2 - Import)**: 8 tasks (T021-T028)
- **User Story 4 (P2 - Sidebar)**: 1 task (T029)
- **User Story 5 (P3 - Workflow)**: 2 tasks (T030-T031)
- **Setup + Foundational**: 6 tasks (T001-T006)
- **Polish**: 9 tasks (T032-T040)

**Parallel Opportunities**: 15 tasks marked [P] can run in parallel within their phase
**Independent Stories**: User Stories 1-4 can all be developed in parallel after Foundational phase
**MVP Scope**: User Stories 1 + 2 (14 tasks) provide core value for immediate deployment
**Full Feature**: All 40 tasks complete all 5 user stories with E2E validation and polish
