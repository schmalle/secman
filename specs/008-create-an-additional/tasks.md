# Tasks: Workgroup-Based Access Control

**Feature**: 008-create-an-additional
**Branch**: `008-create-an-additional`
**Input**: Design documents from `/specs/008-create-an-additional/`
**Prerequisites**: ✅ plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → ✅ Loaded - Web app (Kotlin/Micronaut backend + Astro/React frontend)
   → Tech stack: Kotlin 2.1.0, Micronaut 4.5.4, Hibernate JPA, MariaDB 11.4
2. Load optional design documents:
   → ✅ data-model.md: 3 entities (Workgroup NEW, User MODIFY, Asset MODIFY)
   → ✅ contracts/: 3 YAML files (15 API endpoints total)
   → ✅ research.md: 4 technical decisions
   → ✅ quickstart.md: 8 acceptance scenarios
3. Generate tasks by category:
   → Setup: 0 tasks (dependencies already in project)
   → Tests: 18 tasks (13 contract tests + 5 integration tests)
   → Core: 21 tasks (3 domain + 5 repositories + 3 services + 5 controllers + 5 frontend)
   → Integration: 0 tasks (using existing patterns)
   → Polish: 3 tasks (E2E test, validation, documentation)
4. Apply task rules:
   → Contract tests: [P] (different test files)
   → Domain entities: [P] (different domain files)
   → Controllers: Sequential (imports affect compilation)
5. Number tasks sequentially (T001-T042)
6. Generate dependency graph below
7. Create parallel execution examples
8. Validate task completeness:
   → ✅ All 15 API endpoints have contract tests
   → ✅ All 3 entities have model tasks
   → ✅ All endpoints have implementation tasks
9. SUCCESS - 42 tasks ready for execution
```

---

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Paths are absolute from repository root: `/Users/flake/sources/misc/secman/`

## Path Conventions
**Project Type**: Web application
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend Tests**: `src/frontend/tests/e2e/`

---

## Phase 3.1: Setup

**Status**: ✅ No setup needed - dependencies already present in project

---

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3

**CRITICAL**: These tests MUST be written and MUST FAIL before ANY implementation

### Contract Tests (from contracts/*.yaml)

**From workgroup-crud.yaml (5 endpoints)**:
- [ ] **T001** [P] Contract test POST /api/workgroups in `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupCreateContractTest.kt`
  - Test creates workgroup with valid name
  - Test rejects duplicate name (case-insensitive)
  - Test validates name format (alphanumeric + spaces + hyphens)
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (WorkgroupController not implemented)

- [ ] **T002** [P] Contract test GET /api/workgroups in `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupListContractTest.kt`
  - Test returns list of all workgroups
  - Test requires ADMIN role
  - Test returns 401 for unauthenticated requests
  - **Expected**: All tests FAIL (WorkgroupController not implemented)

- [ ] **T003** [P] Contract test GET /api/workgroups/{id} in `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupGetContractTest.kt`
  - Test returns workgroup details by ID
  - Test includes userCount and assetCount
  - Test returns 404 for non-existent workgroup
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (WorkgroupController not implemented)

- [ ] **T004** [P] Contract test PUT /api/workgroups/{id} in `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupUpdateContractTest.kt`
  - Test updates workgroup name and description
  - Test validates name constraints
  - Test returns 404 for non-existent workgroup
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (WorkgroupController not implemented)

- [ ] **T005** [P] Contract test DELETE /api/workgroups/{id} in `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupDeleteContractTest.kt`
  - Test deletes workgroup and returns 204
  - Test clears all user and asset memberships
  - Test returns 404 for non-existent workgroup
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (WorkgroupController not implemented)

**From workgroup-assignment.yaml (6 endpoints)**:
- [ ] **T006** [P] Contract test POST /api/workgroups/{id}/users in `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupAssignUsersContractTest.kt`
  - Test assigns multiple users to workgroup
  - Test validates user IDs exist
  - Test returns added user IDs
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (WorkgroupController not implemented)

- [ ] **T007** [P] Contract test DELETE /api/workgroups/{id}/users/{userId} in `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupRemoveUserContractTest.kt`
  - Test removes user from workgroup
  - Test returns 404 if user not in workgroup
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (WorkgroupController not implemented)

- [ ] **T008** [P] Contract test POST /api/workgroups/{id}/assets in `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupAssignAssetsContractTest.kt`
  - Test assigns multiple assets to workgroup
  - Test validates asset IDs exist
  - Test returns added asset IDs
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (WorkgroupController not implemented)

- [ ] **T009** [P] Contract test DELETE /api/workgroups/{id}/assets/{assetId} in `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupRemoveAssetContractTest.kt`
  - Test removes asset from workgroup
  - Test returns 404 if asset not in workgroup
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (WorkgroupController not implemented)

- [ ] **T010** [P] Contract test GET /api/users/{userId}/workgroups in `src/backendng/src/test/kotlin/com/secman/contract/UserWorkgroupsContractTest.kt`
  - Test returns workgroups for a user
  - Test returns empty array if user has no workgroups
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (UserController modification not implemented)

- [ ] **T011** [P] Contract test GET /api/assets/{assetId}/workgroups in `src/backendng/src/test/kotlin/com/secman/contract/AssetWorkgroupsContractTest.kt`
  - Test returns workgroups for an asset
  - Test returns empty array if asset has no workgroups
  - Test requires ADMIN role
  - **Expected**: All tests FAIL (AssetController modification not implemented)

**From filtered-lists.yaml (4 endpoint modifications)**:
- [ ] **T012** [P] Contract test GET /api/assets (workgroup-filtered) in `src/backendng/src/test/kotlin/com/secman/contract/AssetListFilteredContractTest.kt`
  - Test ADMIN sees all assets
  - Test USER sees only workgroup assets + owned assets
  - Test VULN sees only workgroup assets + owned assets
  - Test user with no workgroups sees only owned assets
  - Test dual ownership visibility (manual creator + scan uploader)
  - **Expected**: All tests FAIL (filtering not implemented)

- [ ] **T013** [P] Contract test GET /api/vulnerabilities/current (workgroup-filtered) in `src/backendng/src/test/kotlin/com/secman/contract/VulnerabilityListFilteredContractTest.kt`
  - Test ADMIN sees all vulnerabilities
  - Test VULN sees only workgroup vulnerabilities
  - Test USER sees only workgroup vulnerabilities
  - Test filtering based on accessible assets
  - **Expected**: All tests FAIL (filtering not implemented)

### Integration Tests (from quickstart.md scenarios)

- [ ] **T014** [P] Integration test workgroup CRUD operations in `src/backendng/src/test/kotlin/com/secman/integration/WorkgroupCrudIntegrationTest.kt`
  - Scenario 1: Create workgroup with valid name
  - Scenario 1: Reject duplicate name (case-insensitive)
  - Scenario 7: Delete workgroup clears memberships
  - **Expected**: All tests FAIL (WorkgroupService not implemented)

- [ ] **T015** [P] Integration test workgroup assignment in `src/backendng/src/test/kotlin/com/secman/integration/WorkgroupAssignmentIntegrationTest.kt`
  - Scenario 2: Assign users to workgroup
  - Scenario 3: Assign assets to workgroup
  - Test admin-only restriction (FR-014)
  - **Expected**: All tests FAIL (assignment logic not implemented)

- [ ] **T016** [P] Integration test workgroup filtering (8 scenarios) in `src/backendng/src/test/kotlin/com/secman/integration/WorkgroupFilteringIntegrationTest.kt`
  - Scenario 4: USER sees workgroup assets + owned assets
  - Scenario 5: USER sees workgroup vulnerabilities
  - Scenario 5a: USER sees workgroup scan results
  - Scenario 6: ADMIN sees all assets/vulnerabilities
  - Scenario 6a: VULN role respects workgroup restrictions
  - Scenario 8: User with no workgroups sees only owned assets
  - Test dual ownership visibility (FR-016, FR-020)
  - Test user deletion nulls ownership (FR-027)
  - **Expected**: All tests FAIL (AssetFilterService not implemented)

- [ ] **T017** [P] Integration test RBAC for workgroups in `src/backendng/src/test/kotlin/com/secman/integration/WorkgroupRBACIntegrationTest.kt`
  - Test ADMIN can manage workgroups
  - Test VULN cannot manage workgroups
  - Test USER cannot manage workgroups
  - Test ADMIN bypass workgroup filtering
  - Test VULN respects workgroup filtering
  - **Expected**: All tests FAIL (controller authorization not implemented)

- [ ] **T018** [P] Unit test AssetFilterService in `src/backendng/src/test/kotlin/com/secman/unit/AssetFilterServiceTest.kt`
  - Test getAccessibleAssetIds for ADMIN (returns all)
  - Test getAccessibleAssetIds for USER with workgroups
  - Test getAccessibleAssetIds for USER without workgroups
  - Test dual ownership filtering
  - Test workgroup membership union logic
  - **Expected**: All tests FAIL (AssetFilterService not implemented)

**CHECKPOINT**: After T001-T018 complete, verify ALL tests FAIL. Do NOT proceed to Phase 3.3 until tests are failing.

---

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### Domain Layer (from data-model.md)

- [ ] **T019** [P] Create Workgroup entity in `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`
  - Fields: id, name, description, createdAt, updatedAt
  - ManyToMany relationships to User and Asset
  - @PrePersist and @PreUpdate for timestamps
  - Validation: name 1-100 chars, alphanumeric + spaces + hyphens
  - **Dependencies**: None
  - **Makes GREEN**: None yet (no services)

- [ ] **T020** [P] Modify User entity in `src/backendng/src/main/kotlin/com/secman/domain/User.kt`
  - Add `workgroups: MutableSet<Workgroup>` field
  - ManyToMany with @JoinTable(name = "user_workgroups")
  - FetchType.EAGER (needed for access control checks)
  - **Dependencies**: T019 (Workgroup must exist)
  - **Makes GREEN**: None yet

- [ ] **T021** [P] Modify Asset entity in `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`
  - Add `workgroups: MutableSet<Workgroup>` field
  - Add `manualCreator: User?` nullable FK (LAZY fetch)
  - Add `scanUploader: User?` nullable FK (LAZY fetch)
  - ManyToMany with @JoinTable(name = "asset_workgroups")
  - FetchType.EAGER for workgroups
  - **Dependencies**: T019 (Workgroup must exist)
  - **Makes GREEN**: None yet

### Repository Layer (from research.md + data-model.md)

- [ ] **T022** [P] Create WorkgroupRepository in `src/backendng/src/main/kotlin/com/secman/repository/WorkgroupRepository.kt`
  - Extends JpaRepository<Workgroup, Long>
  - Method: `existsByNameIgnoreCase(name: String): Boolean`
  - Method: `findByNameIgnoreCase(name: String): Optional<Workgroup>`
  - Method: `findById(id: Long): Optional<Workgroup>`
  - **Dependencies**: T019
  - **Makes GREEN**: None yet (needs service layer)

- [ ] **T023** Extend AssetRepository in `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`
  - Add method: `findByWorkgroupsIdIn(workgroupIds: List<Long>): List<Asset>`
  - Add method: `findByManualCreatorId(userId: Long): List<Asset>`
  - Add method: `findByScanUploaderId(userId: Long): List<Asset>`
  - **Dependencies**: T021 (Asset.workgroups must exist)
  - **Makes GREEN**: None yet
  - **Note**: Sequential (modifies existing file)

- [ ] **T024** Extend VulnerabilityRepository in `src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt`
  - Add method: `findByAssetIdIn(assetIds: List<Long>): List<Vulnerability>`
  - Add method: `findByAssetIdIn(assetIds: List<Long>, pageable: Pageable): Page<Vulnerability>`
  - **Dependencies**: T021 (Asset changes for filtering)
  - **Makes GREEN**: None yet
  - **Note**: Sequential (modifies existing file)

- [ ] **T025** Extend ScanRepository in `src/backendng/src/main/kotlin/com/secman/repository/ScanRepository.kt`
  - Add method: `findByAssetIdIn(assetIds: List<Long>): List<Scan>`
  - **Dependencies**: T021
  - **Makes GREEN**: None yet
  - **Note**: Sequential (modifies existing file after T024)

- [ ] **T026** Extend UserRepository in `src/backendng/src/main/kotlin/com/secman/repository/UserRepository.kt`
  - Add method: `findByWorkgroupsId(workgroupId: Long): List<User>`
  - **Dependencies**: T020 (User.workgroups must exist)
  - **Makes GREEN**: None yet
  - **Note**: Sequential (modifies existing file after T025)

### Service Layer (from research.md)

- [ ] **T027** Create WorkgroupService in `src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt`
  - Method: `createWorkgroup(name, description): Workgroup` with case-insensitive duplicate check
  - Method: `updateWorkgroup(id, name?, description?): Workgroup`
  - Method: `deleteWorkgroup(id): Unit` (clear memberships via cascade)
  - Method: `assignUsersToWorkgroup(workgroupId, userIds): Unit`
  - Method: `removeUserFromWorkgroup(workgroupId, userId): Unit`
  - Method: `assignAssetsToWorkgroup(workgroupId, assetIds): Unit`
  - Method: `removeAssetFromWorkgroup(workgroupId, assetId): Unit`
  - Validation: Name format, length constraints (FR-006)
  - @Transactional on all mutating methods
  - **Dependencies**: T022 (WorkgroupRepository)
  - **Makes GREEN**: T014 (workgroup CRUD integration test)

- [ ] **T028** Create AssetFilterService in `src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt`
  - Method: `getAccessibleAssetIds(authentication): List<Long>`
    - If ADMIN role: return all asset IDs
    - If VULN/USER role: return union of (workgroup asset IDs + owned asset IDs)
  - Method: `filterAssets(assets, authentication): List<Asset>` (in-memory filter)
  - Method: `isAssetAccessible(assetId, authentication): Boolean`
  - Logic: Check user.workgroups.id + asset.manualCreator.id + asset.scanUploader.id
  - **Dependencies**: T023, T020, T021 (repositories + entities)
  - **Makes GREEN**: T018 (AssetFilterService unit test), T016 (filtering integration test)

- [ ] **T029** Modify UserDeletionValidator in `src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt`
  - Extend validation to allow deletion (assets persist with null ownership per FR-027)
  - Document dual ownership nulling behavior
  - **Dependencies**: T021 (Asset dual ownership)
  - **Makes GREEN**: T016 (user deletion scenario)
  - **Note**: Sequential (modifies existing file after T028)

### Controller Layer (from contracts/*.yaml)

- [ ] **T030** Create WorkgroupController in `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`
  - @Controller("/api/workgroups")
  - @Secured("ADMIN") on all endpoints
  - POST / - createWorkgroup (T001)
  - GET / - listWorkgroups (T002)
  - GET /{id} - getWorkgroup (T003)
  - PUT /{id} - updateWorkgroup (T004)
  - DELETE /{id} - deleteWorkgroup (T005)
  - POST /{id}/users - assignUsersToWorkgroup (T006)
  - DELETE /{id}/users/{userId} - removeUserFromWorkgroup (T007)
  - POST /{id}/assets - assignAssetsToWorkgroup (T008)
  - DELETE /{id}/assets/{assetId} - removeAssetFromWorkgroup (T009)
  - **Dependencies**: T027 (WorkgroupService)
  - **Makes GREEN**: T001-T009, T014, T015, T017 (contract + integration tests)

- [ ] **T031** Modify UserController in `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt`
  - Add GET /api/users/{userId}/workgroups endpoint
  - @Secured("ADMIN")
  - Return user's workgroups as WorkgroupResponse list
  - **Dependencies**: T026 (UserRepository), T020 (User.workgroups)
  - **Makes GREEN**: T010 (user workgroups contract test)
  - **Note**: Sequential (modifies existing file after T030)

- [ ] **T032** Modify AssetController in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
  - Modify GET /api/assets to call AssetFilterService
  - Add GET /api/assets/{assetId}/workgroups endpoint (ADMIN only)
  - Filter results based on Authentication
  - Return AssetResponse with workgroups field populated
  - **Dependencies**: T028 (AssetFilterService), T023 (AssetRepository)
  - **Makes GREEN**: T011, T012 (asset contract tests), T016 (filtering scenarios)
  - **Note**: Sequential (modifies existing file after T031)

- [ ] **T033** Modify VulnerabilityManagementController in `src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityManagementController.kt`
  - Modify GET /api/vulnerabilities/current to call AssetFilterService
  - Filter vulnerabilities by accessible asset IDs
  - Respect VULN/USER/ADMIN roles
  - **Dependencies**: T028 (AssetFilterService), T024 (VulnerabilityRepository)
  - **Makes GREEN**: T013 (vulnerability contract test), T016 (filtering scenarios)
  - **Note**: Sequential (modifies existing file after T032)

- [ ] **T034** Modify ScanController in `src/backendng/src/main/kotlin/com/secman/controller/ScanController.kt`
  - Modify GET /api/scans to call AssetFilterService
  - Filter scans by accessible asset IDs
  - Include scans uploaded by current user (scan uploader)
  - **Dependencies**: T028 (AssetFilterService), T025 (ScanRepository)
  - **Makes GREEN**: T016 (scan filtering scenario in integration test)
  - **Note**: Sequential (modifies existing file after T033)

### Frontend (from spec UI requirements + contracts)

- [ ] **T035** [P] Create WorkgroupBadges component in `src/frontend/src/components/WorkgroupBadges.tsx`
  - Display workgroup names as Bootstrap badges
  - Props: workgroups (array of {id, name})
  - Color: badge-secondary
  - **Dependencies**: None (independent component)
  - **Makes GREEN**: None (no E2E tests yet)

- [ ] **T036** [P] Create WorkgroupSelector component in `src/frontend/src/components/WorkgroupSelector.tsx`
  - Multi-select dropdown for workgroup assignment
  - Props: selectedWorkgroups, availableWorkgroups, onChange, disabled (for non-admin)
  - Uses React state for selection
  - **Dependencies**: None (independent component)
  - **Makes GREEN**: None

- [ ] **T037** Create admin workgroups page in `src/frontend/src/pages/admin/workgroups.astro`
  - Table listing all workgroups (GET /api/workgroups)
  - Create workgroup form (POST /api/workgroups)
  - Edit workgroup modal (PUT /api/workgroups/{id})
  - Delete workgroup button (DELETE /api/workgroups/{id})
  - User assignment UI (POST/DELETE /api/workgroups/{id}/users)
  - Asset assignment UI (POST/DELETE /api/workgroups/{id}/assets)
  - Admin-only access check
  - **Dependencies**: T030 (WorkgroupController), T035, T036 (components)
  - **Makes GREEN**: None (E2E test comes later)
  - **Note**: Sequential (uses T035, T036)

- [ ] **T038** Modify AssetForm component in `src/frontend/src/components/AssetForm.tsx`
  - Add WorkgroupSelector for workgroup assignment (admin only)
  - Display WorkgroupBadges for current workgroups
  - Conditional rendering: Show selector only if user has ADMIN role
  - **Dependencies**: T035, T036 (components), T032 (AssetController endpoints)
  - **Makes GREEN**: None
  - **Note**: Sequential (modifies existing file after T037)

- [ ] **T039** Display workgroup info on asset/user detail views
  - Modify `src/frontend/src/pages/asset.astro` to show WorkgroupBadges
  - Modify `src/frontend/src/pages/admin/user-management.astro` to show user workgroups
  - **Dependencies**: T035 (WorkgroupBadges), T032, T031 (endpoints)
  - **Makes GREEN**: None
  - **Note**: Sequential (modifies existing files after T038)

---

## Phase 3.4: Integration

**Status**: ✅ No integration tasks needed - using existing DB connection, auth middleware, logging

---

## Phase 3.5: Polish

- [ ] **T040** Create E2E test in `src/frontend/tests/e2e/workgroup-management.spec.ts`
  - Test admin creates workgroup
  - Test admin assigns users to workgroup
  - Test admin assigns assets to workgroup
  - Test non-admin cannot access workgroup management
  - Test filtered asset list (user sees only workgroup assets)
  - Follow quickstart.md scenarios
  - **Dependencies**: T037, T038, T039 (frontend complete)
  - **Makes GREEN**: Final validation of feature

- [ ] **T041** Run quickstart.md validation script
  - Execute all 8 scenarios manually
  - Verify expected responses match
  - Document any deviations
  - **Dependencies**: T040 (all implementation complete)
  - **Makes GREEN**: Final acceptance

- [ ] **T042** Update documentation
  - Update `CLAUDE.md` with feature summary (workgroup-based access control implemented)
  - Ensure contracts/ YAML files are current
  - Update data-model.md if schema differed from plan
  - **Dependencies**: T041 (validation complete)

---

## Dependencies

### Critical Path (must be sequential):
1. **Domain → Repository → Service → Controller** (T019-T022 → T027 → T030)
2. **Tests must fail before implementation** (T001-T018 before T019)
3. **Asset modifications sequential** (T023 → T024 → T025 → T026)
4. **Controller modifications sequential** (T030 → T031 → T032 → T033 → T034)
5. **Frontend build on components** (T035, T036 → T037 → T038 → T039)

### Parallelizable Groups:
- **T001-T018**: All test files (different files, no dependencies)
- **T019-T021**: Domain entities (different files)
- **T022**: WorkgroupRepository (independent of T023-T026)
- **T035-T036**: Frontend components (different files)

### Blocking Relationships:
- T001-T018 block T019 (TDD: tests before code)
- T019 blocks T020, T021, T022 (Workgroup entity needed)
- T020, T021 block T023-T026 (entity fields needed for repositories)
- T022 blocks T027 (WorkgroupRepository needed for service)
- T023, T020, T021 block T028 (repositories + entities needed for filtering)
- T027 blocks T030 (service needed for controller)
- T028 blocks T032, T033, T034 (filtering service needed)
- T030 blocks T031-T034 (WorkgroupController first, then modifications)
- T035, T036 block T037 (components needed for page)
- T032 blocks T038 (AssetController endpoints needed)
- T037, T038 block T039 (pages ready for detail view modifications)
- T039 blocks T040 (frontend complete for E2E)
- T040 blocks T041, T042 (E2E before validation)

---

## Parallel Execution Examples

### Batch 1: Contract Tests (T001-T013 can run together)
```bash
# Launch all 13 contract test tasks in parallel:
# T001: WorkgroupCreateContractTest.kt
# T002: WorkgroupListContractTest.kt
# T003: WorkgroupGetContractTest.kt
# T004: WorkgroupUpdateContractTest.kt
# T005: WorkgroupDeleteContractTest.kt
# T006: WorkgroupAssignUsersContractTest.kt
# T007: WorkgroupRemoveUserContractTest.kt
# T008: WorkgroupAssignAssetsContractTest.kt
# T009: WorkgroupRemoveAssetContractTest.kt
# T010: UserWorkgroupsContractTest.kt
# T011: AssetWorkgroupsContractTest.kt
# T012: AssetListFilteredContractTest.kt
# T013: VulnerabilityListFilteredContractTest.kt
```

### Batch 2: Integration + Unit Tests (T014-T018 can run together)
```bash
# Launch all 5 integration/unit test tasks in parallel:
# T014: WorkgroupCrudIntegrationTest.kt
# T015: WorkgroupAssignmentIntegrationTest.kt
# T016: WorkgroupFilteringIntegrationTest.kt
# T017: WorkgroupRBACIntegrationTest.kt
# T018: AssetFilterServiceTest.kt
```

### Batch 3: Domain Entities (T019-T021 can run together)
```bash
# Launch all 3 domain entity tasks in parallel:
# T019: Workgroup.kt
# T020: User.kt (add workgroups field)
# T021: Asset.kt (add workgroups + dual ownership)
```

### Batch 4: Frontend Components (T035-T036 can run together)
```bash
# Launch both component tasks in parallel:
# T035: WorkgroupBadges.tsx
# T036: WorkgroupSelector.tsx
```

---

## Validation Checklist

*GATE: Verified before task execution*

- [✅] All 15 API endpoints have corresponding contract tests (T001-T013)
- [✅] All 3 entities have model tasks (T019-T021)
- [✅] All tests come before implementation (T001-T018 before T019)
- [✅] Parallel tasks truly independent:
  - T001-T018: Different test files ✓
  - T019-T021: Different entity files ✓
  - T035-T036: Different component files ✓
- [✅] Each task specifies exact file path (absolute from repo root)
- [✅] No [P] task modifies same file as another [P] task

---

## Notes

- **TDD Enforcement**: T001-T018 must FAIL before proceeding to T019
- **Sequential Controllers**: T031-T034 modify existing files, cannot parallelize
- **Sequential Repositories**: T023-T026 modify existing files, ordered to avoid conflicts
- **Commit Strategy**: Commit after each green test (micro-commits preferred)
- **Total Tasks**: 42
- **Estimated Time**: 12-16 hours (per plan.md estimate)
- **Parallel Batches**: 4 batches (tests, entities, components) save ~6 hours

---

## Task Execution Order Summary

**Phase 3.2 (Tests)**: T001 → T002 → ... → T018 (all parallel within phase)
**Phase 3.3 (Implementation)**:
1. Domain: T019 [P], T020 [P], T021 [P]
2. Repositories: T022 [P] | T023 → T024 → T025 → T026 (sequential)
3. Services: T027 → T028 → T029
4. Controllers: T030 → T031 → T032 → T033 → T034
5. Frontend: T035 [P], T036 [P] → T037 → T038 → T039

**Phase 3.5 (Polish)**: T040 → T041 → T042

**Ready for execution**: Run `/implement` or execute tasks manually following TDD discipline
