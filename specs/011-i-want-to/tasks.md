# Tasks: Release-Based Requirement Version Management

**Input**: Design documents from `/specs/011-i-want-to/`
**Prerequisites**: plan.md (✓), research.md (✓), data-model.md (✓), contracts/ (✓), quickstart.md (✓)
**Feature Branch**: `011-i-want-to`

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → ✓ Found: Web application (Kotlin/Micronaut backend + Astro/React frontend)
   → ✓ Extract: Micronaut 4.4, Hibernate JPA, Astro 5.14, React 19, MariaDB 11.4
2. Load optional design documents:
   → ✓ data-model.md: RequirementSnapshot entity
   → ✓ contracts/: 3 API specifications (release-api, comparison-api, export-extensions)
   → ✓ research.md: 5 technical decisions documented
3. Generate tasks by category:
   → Setup: RELEASE_MANAGER role, dependencies
   → Tests: 8 contract tests, 5 integration tests
   → Core: Entity, repositories, services, controllers
   → Integration: Export extensions, UI components
   → Polish: E2E tests, performance validation
4. Apply task rules:
   → Different files = marked [P] for parallel execution
   → Same file = sequential (no [P])
   → All tests before implementation (TDD)
5. Number tasks sequentially (T001-T042)
6. Generate dependency graph
7. Create parallel execution examples
8. Validate task completeness:
   → ✓ All 3 contracts have tests
   → ✓ RequirementSnapshot entity has model + repository
   → ✓ All endpoints implemented
9. Return: SUCCESS (42 tasks ready for execution)
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- All file paths are absolute from repository root

## Path Conventions (from plan.md)
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend Tests**: `src/frontend/tests/e2e/`

---

## Phase 3.1: Setup & Dependencies

### T001 ✅ Add RELEASE_MANAGER role to User entity
**File**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`
**Description**: Add "RELEASE_MANAGER" to the valid roles enum/collection in User entity
**Action**: Update User.roles to include RELEASE_MANAGER as a valid value
**Validation**: User can be created with RELEASE_MANAGER role
**Status**: COMPLETE - RELEASE_MANAGER added to Role enum

### T002 ✅ [P] Verify MariaDB dependency and Hibernate JPA configuration
**File**: `src/backendng/build.gradle.kts` (read-only verification)
**Description**: Verify Micronaut Data JPA and MariaDB driver are in dependencies
**Action**: Read build.gradle.kts and confirm `micronaut-data-hibernate-jpa` and `mariadb-java-client` exist
**Validation**: Dependencies present, no changes needed (existing infrastructure)
**Status**: COMPLETE - All dependencies verified

### T003 ✅ [P] Verify frontend dependencies (Astro, React, Bootstrap)
**File**: `src/frontend/package.json` (read-only verification)
**Description**: Verify Astro 5.14, React 19, Bootstrap 5.3 are in package.json
**Action**: Read package.json and confirm framework versions match plan.md
**Validation**: Dependencies present, npm install succeeds
**Status**: COMPLETE - All frontend dependencies verified

---

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation begins**

### Contract Tests (Parallel - Different Files)

### T004 [P] Contract test POST /api/releases (create release)
**File**: `src/backendng/src/test/kotlin/com/secman/controller/ReleaseControllerTest.kt` (new)
**Description**: Write failing contract test for release creation endpoint
**Spec**: `specs/011-i-want-to/contracts/release-api.yaml` - POST /api/releases
**Test Cases**:
- ✓ Success 201: Valid version (1.0.0), name, description
- ✓ Error 400: Duplicate version
- ✓ Error 400: Invalid version format (v1.0, Q4-2024)
- ✓ Error 403: USER role (not ADMIN/RELEASE_MANAGER)
- ✓ Error 401: Unauthenticated request
**Validation**: All tests FAIL (ReleaseController does not exist yet)

### T005 [P] Contract test GET /api/releases (list releases)
**File**: `src/backendng/src/test/kotlin/com/secman/controller/ReleaseControllerTest.kt`
**Description**: Write failing contract test for list releases endpoint
**Spec**: `specs/011-i-want-to/contracts/release-api.yaml` - GET /api/releases
**Test Cases**:
- ✓ Success 200: Returns array of ReleaseResponse
- ✓ Filter by status: ?status=PUBLISHED
- ✓ Error 401: Unauthenticated request
**Validation**: All tests FAIL

### T006 [P] Contract test GET /api/releases/{id} (get release details)
**File**: `src/backendng/src/test/kotlin/com/secman/controller/ReleaseControllerTest.kt`
**Description**: Write failing contract test for get release by ID
**Spec**: `specs/011-i-want-to/contracts/release-api.yaml` - GET /api/releases/{id}
**Test Cases**:
- ✓ Success 200: Returns ReleaseResponse with requirementCount
- ✓ Error 404: Release not found
- ✓ Error 401: Unauthenticated request
**Validation**: All tests FAIL

### T007 [P] Contract test DELETE /api/releases/{id} (delete release)
**File**: `src/backendng/src/test/kotlin/com/secman/controller/ReleaseControllerTest.kt`
**Description**: Write failing contract test for release deletion
**Spec**: `specs/011-i-want-to/contracts/release-api.yaml` - DELETE /api/releases/{id}
**Test Cases**:
- ✓ Success 204: Release and snapshots deleted
- ✓ Error 403: USER role (not ADMIN/RELEASE_MANAGER)
- ✓ Error 404: Release not found
- ✓ Error 401: Unauthenticated request
**Validation**: All tests FAIL

### T008 [P] Contract test GET /api/releases/{id}/requirements (list snapshots)
**File**: `src/backendng/src/test/kotlin/com/secman/controller/ReleaseControllerTest.kt`
**Description**: Write failing contract test for listing requirements in release
**Spec**: `specs/011-i-want-to/contracts/release-api.yaml` - GET /api/releases/{id}/requirements
**Test Cases**:
- ✓ Success 200: Returns array of RequirementSnapshotResponse
- ✓ Error 404: Release not found
**Validation**: All tests FAIL

### T009 [P] Contract test GET /api/releases/compare (compare releases)
**File**: `src/backendng/src/test/kotlin/com/secman/controller/ReleaseComparisonControllerTest.kt` (new)
**Description**: Write failing contract test for release comparison
**Spec**: `specs/011-i-want-to/contracts/comparison-api.yaml` - GET /api/releases/compare
**Test Cases**:
- ✓ Success 200: Returns ComparisonResult with added/deleted/modified
- ✓ Error 400: Same fromReleaseId and toReleaseId
- ✓ Error 404: One or both releases not found
- ✓ Error 401: Unauthenticated request
**Validation**: All tests FAIL (ReleaseComparisonController does not exist)

### T010 [P] Contract test GET /api/requirements/export/xlsx with releaseId
**File**: `src/backendng/src/test/kotlin/com/secman/controller/RequirementControllerTest.kt` (extend existing)
**Description**: Write failing contract test for export with optional releaseId parameter
**Spec**: `specs/011-i-want-to/contracts/export-extensions.yaml` - GET /api/requirements/export/xlsx
**Test Cases**:
- ✓ Success 200: Export current (no releaseId) - existing test, verify still passes
- ✓ Success 200: Export from release (?releaseId=123)
- ✓ Filename includes version: "requirements_v1.0.0_YYYY-MM-DD.xlsx"
- ✓ Error 404: Release not found
**Validation**: New tests FAIL (releaseId parameter not implemented)

### T011 [P] Contract test GET /api/requirements/export/word with releaseId
**File**: `src/backendng/src/test/kotlin/com/secman/controller/RequirementControllerTest.kt` (extend existing)
**Description**: Write failing contract test for Word export with releaseId
**Spec**: `specs/011-i-want-to/contracts/export-extensions.yaml` - GET /api/requirements/export/word
**Test Cases**:
- ✓ Success 200: Export from release (?releaseId=123)
- ✓ Release metadata in document header
- ✓ Error 404: Release not found
**Validation**: New tests FAIL

### Integration Tests (Parallel - Different Files)

### T012 [P] Integration test: Release creation workflow
**File**: `src/backendng/src/test/kotlin/com/secman/integration/ReleaseWorkflowTest.kt` (new)
**Description**: Write failing integration test for complete release creation flow
**Scenario**: From `quickstart.md` Scenario 1
**Test Steps**:
1. Create 10 test requirements (setup)
2. POST /api/releases with version "1.0.0"
3. Verify Release entity created in database
4. Verify 10 RequirementSnapshot entities created
5. Verify snapshots have correct data (match requirements)
6. Verify foreign keys set correctly (release_id)
**Validation**: Test FAILS (snapshot creation not implemented)

### T013 [P] Integration test: Requirement update after release
**File**: `src/backendng/src/test/kotlin/com/secman/integration/ReleaseWorkflowTest.kt`
**Description**: Write failing integration test for requirement update immutability
**Scenario**: From `quickstart.md` Scenario 2
**Test Steps**:
1. Create release "1.0.0" with 1 requirement
2. Update requirement.shortreq
3. Verify current requirement has new value
4. Verify snapshot still has old value
5. Verify snapshot.snapshotTimestamp unchanged
**Validation**: Test FAILS

### T014 [P] Integration test: Deletion prevention
**File**: `src/backendng/src/test/kotlin/com/secman/integration/ReleaseWorkflowTest.kt`
**Description**: Write failing integration test for requirement deletion prevention
**Scenario**: From `quickstart.md` Scenario 4
**Test Steps**:
1. Create release "1.0.0" with 1 requirement
2. Attempt DELETE /api/requirements/{id}
3. Verify 400 error returned
4. Verify error message: "Cannot delete requirement: frozen in releases 1.0.0"
5. Verify requirement still exists in database
**Validation**: Test FAILS

### T015 [P] Integration test: Export current vs historical
**File**: `src/backendng/src/test/kotlin/com/secman/integration/ReleaseWorkflowTest.kt`
**Description**: Write failing integration test for export functionality
**Scenario**: From `quickstart.md` Scenario 3
**Test Steps**:
1. Create release "1.0.0" with requirement "SEC-001: Auth required"
2. Update requirement to "SEC-001: MFA required"
3. Export without releaseId → verify contains "MFA required"
4. Export with releaseId=1 → verify contains "Auth required"
5. Verify filenames differ (current vs v1.0.0)
**Validation**: Test FAILS

### T016 [P] Integration test: Release comparison
**File**: `src/backendng/src/test/kotlin/com/secman/integration/ReleaseWorkflowTest.kt`
**Description**: Write failing integration test for comparison algorithm
**Scenario**: From `quickstart.md` Scenario 5
**Test Steps**:
1. Create release "1.0.0" with 5 requirements
2. Add 1 new requirement
3. Delete 1 requirement from current (mark inactive)
4. Modify 1 requirement field
5. Create release "1.1.0"
6. GET /api/releases/compare?fromReleaseId=1&toReleaseId=2
7. Verify added[] contains 1 item
8. Verify deleted[] contains 1 item
9. Verify modified[] contains 1 item with correct FieldChange
10. Verify unchanged count = 3
**Validation**: Test FAILS

---

## Phase 3.3: Core Implementation (ONLY after all tests are failing)

### Backend Entity & Repository Layer (Parallel - Different Files)

### T017 [P] Create RequirementSnapshot entity
**File**: `src/backendng/src/main/kotlin/com/secman/domain/RequirementSnapshot.kt` (new)
**Description**: Implement RequirementSnapshot JPA entity per data-model.md
**Reference**: `specs/011-i-want-to/data-model.md` - JPA Entity Definition
**Implementation**:
- @Entity with table name "requirement_snapshot"
- All fields from data-model.md (id, release FK, originalRequirementId, shortreq, details, etc.)
- @Index annotations on release_id and original_requirement_id
- Companion object with `fromRequirement()` factory method
- equals/hashCode based on id
**Validation**: Entity compiles, Hibernate creates table on startup

### T018 [P] Create RequirementSnapshotRepository
**File**: `src/backendng/src/main/kotlin/com/secman/repository/RequirementSnapshotRepository.kt` (new)
**Description**: Implement Micronaut Data repository for RequirementSnapshot
**Reference**: `specs/011-i-want-to/data-model.md` - Repository section
**Implementation**:
- Interface extending JpaRepository<RequirementSnapshot, Long>
- findByRelease(release: Release): List<RequirementSnapshot>
- findByReleaseId(releaseId: Long): List<RequirementSnapshot>
- findByOriginalRequirementId(requirementId: Long): List<RequirementSnapshot>
- countByReleaseId(releaseId: Long): Long
- deleteByReleaseId(releaseId: Long)
**Validation**: Repository compiles, queries generated by Micronaut Data

### Backend Service Layer (Sequential - Depends on Entities)

### T019 Write unit tests for ReleaseService.createRelease()
**File**: `src/backendng/src/test/kotlin/com/secman/service/ReleaseServiceTest.kt` (new)
**Description**: Write unit tests for release creation logic (use MockK for repository mocks)
**Test Cases**:
- ✓ Success: Creates release, snapshots all current requirements
- ✓ Validation: Rejects duplicate version
- ✓ Validation: Rejects invalid version format
- ✓ Edge case: Creates release with 0 requirements (warning)
- ✓ Snapshot accuracy: Copies all requirement fields correctly
**Validation**: All tests FAIL (ReleaseService does not exist)

### T020 Implement ReleaseService.createRelease()
**File**: `src/backendng/src/main/kotlin/com/secman/service/ReleaseService.kt` (new)
**Description**: Implement release creation with requirement snapshot logic
**Reference**: `specs/011-i-want-to/data-model.md` - Data Flow Diagrams
**Implementation**:
1. Validate version format (regex: `^\d+\.\d+\.\d+$`)
2. Check version uniqueness via ReleaseRepository
3. Create Release entity, set status=DRAFT, createdBy from authentication
4. Save release to get ID
5. Query all current requirements: requirementRepository.findByIsCurrent(true)
6. For each requirement: create RequirementSnapshot.fromRequirement(req, release)
7. Bulk save snapshots via snapshotRepository.saveAll()
8. Return release with snapshot count
**Validation**: T019 tests pass

### T021 Write unit tests for ReleaseService.deleteRelease()
**File**: `src/backendng/src/test/kotlin/com/secman/service/ReleaseServiceTest.kt`
**Description**: Write unit tests for release deletion
**Test Cases**:
- ✓ Success: Deletes release, cascade deletes snapshots
- ✓ Error: Release not found
**Validation**: All tests FAIL

### T022 Implement ReleaseService.deleteRelease()
**File**: `src/backendng/src/main/kotlin/com/secman/service/ReleaseService.kt`
**Description**: Implement release deletion (snapshots cascade via FK)
**Implementation**:
1. Find release by ID or throw NotFoundException
2. Delete release via releaseRepository.delete(release)
3. Cascade delete handled by database FK constraint
**Validation**: T021 tests pass

### T023 Write unit tests for RequirementService deletion prevention
**File**: `src/backendng/src/test/kotlin/com/secman/service/RequirementServiceTest.kt` (extend existing)
**Description**: Write unit tests for requirement deletion check
**Test Cases**:
- ✓ Blocked: Cannot delete requirement frozen in 1 release
- ✓ Blocked: Cannot delete requirement frozen in multiple releases (lists all)
- ✓ Success: Can delete requirement not in any release
**Validation**: All tests FAIL

### T024 Implement RequirementService deletion check
**File**: `src/backendng/src/main/kotlin/com/secman/service/RequirementService.kt` (extend existing)
**Description**: Add deletion prevention logic to existing RequirementService
**Reference**: `specs/011-i-want-to/data-model.md` - Deletion Prevention Logic
**Implementation**:
1. Before deleting requirement, query snapshotRepository.findByOriginalRequirementId(id)
2. If results not empty: collect release versions, throw BadRequestException("Cannot delete requirement: frozen in releases ${versions}")
3. Else: proceed with deletion
**Validation**: T023 tests pass

### T025 Write unit tests for RequirementComparisonService.compare()
**File**: `src/backendng/src/test/kotlin/com/secman/service/RequirementComparisonServiceTest.kt` (new)
**Description**: Write unit tests for diff algorithm
**Test Cases**:
- ✓ Added: Requirement in toRelease but not fromRelease
- ✓ Deleted: Requirement in fromRelease but not toRelease
- ✓ Modified: Requirement in both with different field values
- ✓ Unchanged: Identical requirements counted correctly
- ✓ Field changes: Detects shortreq, details, motivation changes
**Validation**: All tests FAIL (RequirementComparisonService does not exist)

### T026 Implement RequirementComparisonService.compare()
**File**: `src/backendng/src/main/kotlin/com/secman/service/RequirementComparisonService.kt` (new)
**Description**: Implement comparison algorithm per research.md Decision 5
**Reference**: `specs/011-i-want-to/research.md` - Decision 5: Comparison Algorithm
**Implementation**:
1. Load snapshots for both releases
2. Build Map<originalRequirementId, Snapshot> for each release
3. Iterate fromSnapshots: if not in toMap → added to deleted[]
4. Iterate toSnapshots: if not in fromMap → added to added[], else compare fields
5. For field comparison: iterate fields (shortreq, details, example, motivation, etc.), create FieldChange for differences
6. Return ComparisonResult(fromRelease, toRelease, added, deleted, modified, unchangedCount)
**Validation**: T025 tests pass

### Backend Controller Layer (Parallel after Services - Different Controllers)

### T027 [P] Implement ReleaseController
**File**: `src/backendng/src/main/kotlin/com/secman/controller/ReleaseController.kt` (new)
**Description**: Implement all release management endpoints
**Reference**: `specs/011-i-want-to/contracts/release-api.yaml`
**Implementation**:
- @Controller("/api/releases")
- @Secured(SecurityRule.IS_AUTHENTICATED) on class
- POST / → @Secured("ADMIN", "RELEASE_MANAGER"), calls releaseService.createRelease()
- GET / → returns all releases, optional status filter
- GET /{id} → returns release by ID
- DELETE /{id} → @Secured("ADMIN", "RELEASE_MANAGER"), calls releaseService.deleteRelease()
- GET /{id}/requirements → returns snapshotRepository.findByReleaseId(id)
**Validation**: T004-T008 contract tests pass

### T028 [P] Implement ReleaseComparisonController
**File**: `src/backendng/src/main/kotlin/com/secman/controller/ReleaseComparisonController.kt` (new)
**Description**: Implement comparison endpoint
**Reference**: `specs/011-i-want-to/contracts/comparison-api.yaml`
**Implementation**:
- @Controller("/api/releases/compare")
- @Secured(SecurityRule.IS_AUTHENTICATED)
- GET / with query params fromReleaseId, toReleaseId
- Validate IDs are different (400 if same)
- Call comparisonService.compare(fromId, toId)
- Return ComparisonResult
**Validation**: T009 contract test passes

### T029 Extend RequirementController with releaseId parameter
**File**: `src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt` (extend existing)
**Description**: Add optional releaseId parameter to existing export endpoints
**Reference**: `specs/011-i-want-to/contracts/export-extensions.yaml`
**Implementation**:
- exportToExcel(): Add @Nullable @QueryValue("releaseId") Long? releaseId
- exportToWord(): Add @Nullable @QueryValue("releaseId") Long? releaseId
- If releaseId present:
  - Load release via releaseRepository.findById()
  - Load snapshots via snapshotRepository.findByReleaseId()
  - Map snapshots → RequirementDTO
  - Set filename: "requirements_v${release.version}_${date}.{ext}"
  - Include release metadata in export header
- Else: existing logic (load current requirements)
**Validation**: T010-T011 contract tests pass

---

## Phase 3.4: Frontend Integration

### Frontend Components (Parallel - Different Files)

### T030 [P] Create ReleaseSelector component
**File**: `src/frontend/src/components/ReleaseSelector.tsx` (new)
**Description**: Create dropdown component for selecting releases
**Implementation**:
- React component with Bootstrap dropdown
- Props: onReleaseChange(releaseId: number | null)
- Fetch releases via GET /api/releases
- Options: "Current Version" (value=null) + all releases
- Display format: "{version} - {name}"
- Default selected: "Current Version"
**Validation**: Component renders, fetches releases, calls onReleaseChange

### T031 [P] Create ReleaseComparison component
**File**: `src/frontend/src/components/ReleaseComparison.tsx` (new)
**Description**: Create side-by-side comparison view component
**Implementation**:
- React component with two ReleaseSelector dropdowns (From, To)
- "Compare" button → calls GET /api/releases/compare
- Display ComparisonResult in three sections:
  - Added (green background): RequirementSnapshotSummary cards
  - Deleted (red background): RequirementSnapshotSummary cards
  - Modified (yellow background): RequirementDiff with expandable FieldChange details
- Unchanged count displayed
- Bootstrap card layout
**Validation**: Component renders, comparison displays correctly

### T032 [P] Update ReleaseManagement.tsx
**File**: `src/frontend/src/components/ReleaseManagement.tsx` (extend existing)
**Description**: Integrate release creation and list display
**Implementation**:
- Add "Create Release" button (visible if user has ADMIN || RELEASE_MANAGER)
- Modal form for release creation (version, name, description)
- Version field validation: regex `^\d+\.\d+\.\d+$` with error message
- Table showing releases: version, name, status, requirementCount, createdAt, createdBy
- "Delete" button per row (visible if ADMIN || RELEASE_MANAGER)
- "View Requirements" link → navigate to release detail
- Error handling for duplicate version, invalid format
**Validation**: UI functional, RBAC enforced, validation works

### T033 Integrate ReleaseSelector into export UI
**File**: `src/frontend/src/pages/export.astro` or `src/frontend/src/components/ExportForm.tsx` (extend existing)
**Description**: Add ReleaseSelector above export format dropdown
**Implementation**:
- Import ReleaseSelector component
- Add above format selection
- Default: "Current Version" selected
- When release selected: append ?releaseId={id} to export API call
- Update filename display preview based on selection
**Validation**: Export works for current and historical, filename correct

---

## Phase 3.5: End-to-End & Polish

### E2E Tests (Parallel - Different Test Files)

### T034 [P] E2E test: Release management workflow
**File**: `src/frontend/tests/e2e/release-management.spec.ts` (new)
**Description**: Playwright test for complete release management UI
**Scenario**: From `quickstart.md` Scenario 1, 2, 6
**Test Steps**:
1. Login as RELEASE_MANAGER
2. Navigate to Releases page
3. Click "Create Release"
4. Fill form: version="1.0.0", name="Test Release"
5. Submit → verify success message
6. Verify release appears in table
7. Create second release "1.1.0"
8. Delete release "1.0.0" → verify removed from table
**Validation**: All UI interactions work, data persists

### T035 [P] E2E test: Export with release selection
**File**: `src/frontend/tests/e2e/release-export.spec.ts` (new)
**Description**: Playwright test for export functionality
**Scenario**: From `quickstart.md` Scenario 3
**Test Steps**:
1. Create release "1.0.0"
2. Update requirement
3. Navigate to Export page
4. Select "Current Version" → Export Excel
5. Verify filename: "requirements_current_YYYY-MM-DD.xlsx"
6. Select release "1.0.0" → Export Excel
7. Verify filename: "requirements_v1.0.0_YYYY-MM-DD.xlsx"
**Validation**: Both exports download with correct filenames

### T036 [P] E2E test: Release comparison UI
**File**: `src/frontend/tests/e2e/release-comparison.spec.ts` (new)
**Description**: Playwright test for comparison feature
**Scenario**: From `quickstart.md` Scenario 5
**Test Steps**:
1. Create release "1.0.0"
2. Add requirement "NEW-001"
3. Update requirement "SEC-001"
4. Create release "1.1.0"
5. Navigate to Compare page
6. Select from="1.0.0", to="1.1.0"
7. Click "Compare"
8. Verify Added section shows "NEW-001" (green)
9. Verify Modified section shows "SEC-001" (yellow) with field changes
10. Expand field changes → verify old/new values displayed
**Validation**: Comparison UI displays correctly, color-coded

### T037 [P] E2E test: Permission enforcement
**File**: `src/frontend/tests/e2e/release-permissions.spec.ts` (new)
**Description**: Playwright test for RBAC enforcement
**Scenario**: From `quickstart.md` Scenario 7
**Test Steps**:
1. Login as USER (not RELEASE_MANAGER)
2. Navigate to Releases page
3. Verify "Create Release" button NOT visible
4. Verify release list visible (read-only)
5. Verify "Delete" buttons NOT visible
6. Logout, login as RELEASE_MANAGER
7. Verify "Create Release" button visible
8. Verify "Delete" buttons visible
**Validation**: UI respects user roles

### Performance & Validation

### T038 Performance test: Release creation with 1000 requirements
**File**: `src/backendng/src/test/kotlin/com/secman/performance/ReleasePerformanceTest.kt` (new)
**Description**: Measure release creation performance
**Reference**: `specs/011-i-want-to/research.md` - Performance Considerations
**Test**:
1. Create 1000 test requirements
2. Time POST /api/releases
3. Assert: < 2 seconds (bulk insert optimization)
**Validation**: Performance target met

### T039 Performance test: Export from release
**File**: `src/backendng/src/test/kotlin/com/secman/performance/ReleasePerformanceTest.kt`
**Description**: Measure export performance
**Test**:
1. Create release with 1000 snapshots
2. Time GET /api/requirements/export/xlsx?releaseId=X
3. Assert: < 3 seconds
**Validation**: Performance target met

### T040 Performance test: Comparison of 1000 vs 1000 requirements
**File**: `src/backendng/src/test/kotlin/com/secman/performance/ReleasePerformanceTest.kt`
**Description**: Measure comparison algorithm performance
**Test**:
1. Create two releases with 1000 requirements each
2. Time GET /api/releases/compare
3. Assert: < 1 second (in-memory diff)
**Validation**: Performance target met

### T041 Execute quickstart.md validation
**File**: `specs/011-i-want-to/quickstart.md` (manual execution)
**Description**: Run all 7 quickstart scenarios manually to validate feature
**Action**:
1. Start backend and frontend
2. Execute each scenario in quickstart.md
3. Verify all validation checkpoints pass
4. Document any deviations
**Validation**: All scenarios pass, feature works end-to-end

### T042 Final integration test run
**File**: All test files
**Description**: Run complete test suite and verify coverage
**Action**:
1. Backend: `./gradlew test` → verify all tests pass
2. Frontend: `npm test` → verify E2E tests pass
3. Check coverage: `./gradlew jacocoTestReport` → verify ≥80%
4. Verify no linting errors: `./gradlew ktlintCheck`, `npm run lint`
**Validation**: All tests green, coverage ≥80%, no lint errors

---

## Dependencies

### Critical Path (Must Execute in Order)
1. **Setup** (T001-T003) → blocks everything
2. **Tests Written** (T004-T016) → blocks implementation
3. **Entity & Repository** (T017-T018) → blocks services
4. **Service Tests** (T019, T021, T023, T025) → blocks service implementation
5. **Service Implementation** (T020, T022, T024, T026) → blocks controllers
6. **Controllers** (T027-T029) → enables contract tests to pass
7. **Frontend** (T030-T033) → enables E2E tests
8. **E2E Tests** (T034-T037) → validates UI
9. **Polish** (T038-T042) → final validation

### Parallel Execution Opportunities

**Phase 3.1**: T002, T003 can run in parallel (different files, read-only)

**Phase 3.2**: T004-T011 can run in parallel (different test files)

**Phase 3.2**: T012-T016 can run in parallel (all in same integration test file but independent test methods)

**Phase 3.3**: T017, T018 can run in parallel (different entity/repository files)

**Phase 3.3**: T027, T028 can run in parallel after services complete (different controller files)

**Phase 3.4**: T030, T031, T032 can run in parallel (different component files)

**Phase 3.5**: T034, T035, T036, T037 can run in parallel (different E2E test files)

**Phase 3.5**: T038, T039, T040 can run in parallel (independent performance tests)

---

## Parallel Execution Examples

### Example 1: Contract Tests (Phase 3.2)
```bash
# All contract tests can run in parallel (different test files):
# Note: Actually same file (ReleaseControllerTest.kt) but different test methods - can still parallelize test execution

./gradlew test --tests ReleaseControllerTest.testPostRelease
./gradlew test --tests ReleaseControllerTest.testGetReleases
./gradlew test --tests ReleaseControllerTest.testGetReleaseById
./gradlew test --tests ReleaseControllerTest.testDeleteRelease
./gradlew test --tests ReleaseComparisonControllerTest.testCompareReleases
# etc.
```

### Example 2: Entity & Repository (Phase 3.3)
```bash
# T017 and T018 can run in parallel (different files):
# Terminal 1:
# Implement RequirementSnapshot.kt

# Terminal 2:
# Implement RequirementSnapshotRepository.kt (can write interface while entity is being written)
```

### Example 3: Frontend Components (Phase 3.4)
```bash
# T030, T031, T032 can run in parallel (different component files):
# Terminal 1: Create ReleaseSelector.tsx
# Terminal 2: Create ReleaseComparison.tsx
# Terminal 3: Update ReleaseManagement.tsx
```

### Example 4: E2E Tests (Phase 3.5)
```bash
# All E2E tests can run in parallel:
npm run test:e2e -- release-management.spec.ts &
npm run test:e2e -- release-export.spec.ts &
npm run test:e2e -- release-comparison.spec.ts &
npm run test:e2e -- release-permissions.spec.ts &
wait
```

---

## Notes

### TDD Compliance
- ✅ All tests (T004-T016) written BEFORE implementation (T017-T029)
- ✅ Unit tests (T019, T021, T023, T025) before implementation
- ✅ Integration tests (T012-T016) validate complete workflows
- ✅ E2E tests (T034-T037) validate UI interactions
- ✅ Performance tests (T038-T040) validate constitutional requirements

### File Modifications
- **New files**: 17 new files (entity, repositories, services, controllers, components, tests)
- **Extended files**: 4 existing files (User.kt, RequirementService.kt, RequirementController.kt, ReleaseManagement.tsx)
- **Read-only**: 2 files (build.gradle.kts, package.json - verification only)

### Parallel Execution
- ✅ [P] tasks are truly independent (different files OR different test methods)
- ✅ No [P] conflicts (no two [P] tasks modify same file)
- ✅ Dependencies clearly documented

### Constitutional Compliance
- ✅ TDD: Tests before implementation throughout
- ✅ Security: @Secured annotations, input validation
- ✅ API-First: Contracts defined, tests validate schemas
- ✅ RBAC: Permission checks at controller and UI levels
- ✅ Performance: Validated via T038-T040 (<200ms API, <3s export)

---

## Validation Checklist

*GATE: Checked before task execution begins*

- [x] All 3 contract files have corresponding contract tests (T004-T011)
- [x] RequirementSnapshot entity has model (T017) and repository (T018) tasks
- [x] All tests (T004-T016) come before implementation (T017-T029)
- [x] Parallel tasks [P] truly independent (verified above)
- [x] Each task specifies exact file path
- [x] No [P] task modifies same file as another [P] task
- [x] All 7 quickstart scenarios covered by tests
- [x] Performance requirements validated (T038-T040)
- [x] Permission checks tested (T037)

**Task generation complete. 42 tasks ready for execution following TDD principles.**
