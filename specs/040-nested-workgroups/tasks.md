# Tasks: Nested Workgroups

**Input**: Design documents from `/specs/040-nested-workgroups/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Per TDD constitutional requirement, tests MUST be written before implementation. Tasks below follow TDD workflow: contract tests → unit tests → implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Tests (Backend)**: `src/backendng/src/test/kotlin/com/secman/`
- **Tests (Frontend)**: `src/frontend/tests/`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [ ] T001 Verify Micronaut 4.10, Kotlin 2.2.21, MariaDB 11.4 are correctly configured per plan.md
- [ ] T002 [P] Verify frontend dependencies (Astro 5.14, React 19, Bootstrap 5.3) are correctly configured
- [ ] T003 [P] Verify test frameworks (JUnit 5, MockK, Playwright) are available

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T004 Add parent_id (BIGINT NULL) and version (BIGINT NOT NULL DEFAULT 0) columns to workgroup table schema in src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt
- [ ] T005 Add @ManyToOne parent field and @OneToMany children field with JPA annotations to Workgroup entity in src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt
- [ ] T006 [P] Add @Version annotation to version field in Workgroup entity for optimistic locking in src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt
- [ ] T007 [P] Add calculateDepth() helper method to Workgroup entity in src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt
- [ ] T008 [P] Add getAncestors() helper method to Workgroup entity in src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt
- [ ] T009 [P] Add isDescendantOf() helper method to Workgroup entity in src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt
- [ ] T010 Add findByParent() method to WorkgroupRepository in src/backendng/src/main/kotlin/com/secman/repository/WorkgroupRepository.kt
- [ ] T011 [P] Add findRootLevelWorkgroups() method with @Query to WorkgroupRepository in src/backendng/src/main/kotlin/com/secman/repository/WorkgroupRepository.kt
- [ ] T012 [P] Add findAllDescendants() method with recursive CTE query to WorkgroupRepository in src/backendng/src/main/kotlin/com/secman/repository/WorkgroupRepository.kt
- [ ] T013 [P] Add findAllAncestors() method with recursive CTE query to WorkgroupRepository in src/backendng/src/main/kotlin/com/secman/repository/WorkgroupRepository.kt
- [ ] T014 [P] Add countDescendants() method with recursive CTE query to WorkgroupRepository in src/backendng/src/main/kotlin/com/secman/repository/WorkgroupRepository.kt
- [ ] T015 Create WorkgroupValidationService with @Singleton annotation in src/backendng/src/main/kotlin/com/secman/service/WorkgroupValidationService.kt
- [ ] T016 [P] Create ValidationException class in src/backendng/src/main/kotlin/com/secman/service/WorkgroupValidationService.kt
- [ ] T017 Run backend build to verify schema migration and entity changes compile successfully
- [ ] T018 Start backend application and verify database migration creates parent_id column, version column, foreign key, index, and unique constraint

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Create Child Workgroup (Priority: P1) 🎯 MVP

**Goal**: Enable administrators to create workgroups within parent workgroups, establishing hierarchical organization with validation (depth limit, sibling uniqueness, circular reference prevention).

**Independent Test**: Create a parent workgroup, then create a child workgroup under it, and verify the parent-child relationship is established. Test depth limit (5 levels) and sibling name uniqueness.

### Tests for User Story 1 (TDD - Write Tests FIRST) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T019 [P] [US1] Write contract test for POST /workgroups/{id}/children success (201) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T020 [P] [US1] Write contract test for POST /workgroups/{id}/children depth exceeded (400) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T021 [P] [US1] Write contract test for POST /workgroups/{id}/children sibling name conflict (400) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T022 [P] [US1] Write contract test for POST /workgroups/{id}/children unauthorized (401) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T023 [P] [US1] Write contract test for POST /workgroups/{id}/children forbidden non-ADMIN (403) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T024 [P] [US1] Write contract test for POST /workgroups/{id}/children parent not found (404) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T025 [P] [US1] Write unit test for validateDepthLimit() with depth 1-4 allowed, 5+ rejected in src/backendng/src/test/kotlin/com/secman/service/WorkgroupValidationServiceTest.kt
- [ ] T026 [P] [US1] Write unit test for validateSiblingUniqueness() case-insensitive checks in src/backendng/src/test/kotlin/com/secman/service/WorkgroupValidationServiceTest.kt
- [ ] T027 [P] [US1] Write unit test for Workgroup.calculateDepth() at various levels in src/backendng/src/test/kotlin/com/secman/domain/WorkgroupTest.kt

**Verify all tests FAIL** before proceeding to implementation

### Implementation for User Story 1

- [ ] T028 [P] [US1] Implement validateDepthLimit() method in WorkgroupValidationService in src/backendng/src/main/kotlin/com/secman/service/WorkgroupValidationService.kt
- [ ] T029 [P] [US1] Implement validateSiblingUniqueness() method in WorkgroupValidationService in src/backendng/src/main/kotlin/com/secman/service/WorkgroupValidationService.kt
- [ ] T030 [US1] Create CreateChildWorkgroupRequest DTO with validation annotations in src/backendng/src/main/kotlin/com/secman/dto/CreateChildWorkgroupRequest.kt
- [ ] T031 [US1] Implement createChild() method in WorkgroupService with validation calls in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T032 [US1] Add @Post("/{id}/children") endpoint with @Secured("ADMIN") in WorkgroupController in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T033 [US1] Add error handling for ValidationException returning 400 Bad Request in WorkgroupController in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T034 [US1] Add audit logging (operation, timestamp, user) for child creation in WorkgroupService.createChild() in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T035 [US1] Update WorkgroupResponse DTO to include parentId, depth, childCount, hasChildren, ancestors fields in src/backendng/src/main/kotlin/com/secman/dto/WorkgroupResponse.kt
- [ ] T036 [US1] Run backend tests to verify all User Story 1 tests pass
- [ ] T037 [P] [US1] Create CreateChildWorkgroupModal React component with form validation in src/frontend/src/components/CreateChildWorkgroupModal.tsx
- [ ] T038 [P] [US1] Add createChild() API method to workgroupApi.ts in src/frontend/src/services/workgroupApi.ts
- [ ] T039 [US1] Integrate CreateChildWorkgroupModal with workgroups page (Add Child button) in src/frontend/src/pages/workgroups.astro
- [ ] T040 [US1] Add error handling and display for depth limit and sibling conflict errors in CreateChildWorkgroupModal in src/frontend/src/components/CreateChildWorkgroupModal.tsx
- [ ] T041 [US1] Write Playwright E2E test for creating child workgroup via UI in src/frontend/tests/workgroups-hierarchy.spec.ts
- [ ] T042 [US1] Run frontend tests to verify User Story 1 E2E test passes

**Checkpoint**: User Story 1 complete and independently testable - administrators can create child workgroups with validation

---

## Phase 4: User Story 2 - View Workgroup Hierarchy (Priority: P2)

**Goal**: Enable administrators to visualize hierarchical organizational structure with tree view, expand/collapse, and breadcrumb navigation.

**Independent Test**: Create a multi-level hierarchy (parent > child > grandchild) and verify the UI displays all levels with clear parent-child relationships, expand/collapse works, and breadcrumbs show full path.

### Tests for User Story 2 (TDD - Write Tests FIRST) ⚠️

- [ ] T043 [P] [US2] Write contract test for GET /workgroups/{id}/children success (200) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T044 [P] [US2] Write contract test for GET /workgroups/{id}/ancestors success (200) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T045 [P] [US2] Write contract test for GET /workgroups/root success (200) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T046 [P] [US2] Write integration test for recursive ancestor query returns correct path in src/backendng/src/test/kotlin/com/secman/integration/WorkgroupHierarchyIntegrationTest.kt
- [ ] T047 [P] [US2] Write integration test for recursive descendant query returns full subtree in src/backendng/src/test/kotlin/com/secman/integration/WorkgroupHierarchyIntegrationTest.kt
- [ ] T048 [P] [US2] Write unit test for Workgroup.getAncestors() returns root-to-parent path in src/backendng/src/test/kotlin/com/secman/domain/WorkgroupTest.kt

**Verify all tests FAIL** before proceeding to implementation

### Implementation for User Story 2

- [ ] T049 [P] [US2] Implement getChildren() method in WorkgroupService in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T050 [P] [US2] Implement getAncestors() method in WorkgroupService in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T051 [P] [US2] Implement getRootWorkgroups() method in WorkgroupService in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T052 [US2] Add @Get("/{id}/children") endpoint with @Secured(SecurityRule.IS_AUTHENTICATED) in WorkgroupController in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T053 [P] [US2] Add @Get("/{id}/ancestors") endpoint with @Secured(SecurityRule.IS_AUTHENTICATED) in WorkgroupController in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T054 [P] [US2] Add @Get("/root") endpoint with @Secured(SecurityRule.IS_AUTHENTICATED) in WorkgroupController in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T055 [US2] Create BreadcrumbItem DTO in src/backendng/src/main/kotlin/com/secman/dto/BreadcrumbItem.kt
- [ ] T056 [US2] Run backend tests to verify all User Story 2 tests pass
- [ ] T057 [P] [US2] Create WorkgroupTree React component with lazy-loading and expand/collapse state in src/frontend/src/components/WorkgroupTree.tsx
- [ ] T058 [P] [US2] Create WorkgroupBreadcrumb React component with ancestor navigation in src/frontend/src/components/WorkgroupBreadcrumb.tsx
- [ ] T059 [P] [US2] Add getChildren(), getAncestors(), getRootWorkgroups() API methods to workgroupApi.ts in src/frontend/src/services/workgroupApi.ts
- [ ] T060 [US2] Update workgroups.astro page to render WorkgroupTree for root workgroups in src/frontend/src/pages/workgroups.astro
- [ ] T061 [US2] Add WorkgroupBreadcrumb to workgroup detail pages in src/frontend/src/pages/workgroups.astro
- [ ] T062 [US2] Add CSS styling for tree indentation and expand/collapse buttons in src/frontend/src/styles/workgroups.css
- [ ] T063 [US2] Write Playwright E2E test for tree expand/collapse functionality in src/frontend/tests/workgroups-hierarchy.spec.ts
- [ ] T064 [US2] Write Playwright E2E test for breadcrumb navigation in src/frontend/tests/workgroups-hierarchy.spec.ts
- [ ] T065 [US2] Run frontend tests to verify User Story 2 E2E tests pass

**Checkpoint**: User Story 2 complete and independently testable - hierarchy visualization with tree and breadcrumbs works

---

## Phase 5: User Story 3 - Delete Workgroup with Children (Priority: P3)

**Goal**: Enable administrators to delete workgroups while preserving data through child promotion (children move to grandparent level or become root-level).

**Independent Test**: Create a parent with children, delete the parent, and verify children are promoted to grandparent level (or root if parent was root). Verify all relationships (users, assets) remain intact.

### Tests for User Story 3 (TDD - Write Tests FIRST) ⚠️

- [ ] T066 [P] [US3] Write contract test for DELETE /workgroups/{id} success with child promotion (204) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T067 [P] [US3] Write contract test for DELETE /workgroups/{id} unauthorized (401) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T068 [P] [US3] Write contract test for DELETE /workgroups/{id} forbidden non-ADMIN (403) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T069 [P] [US3] Write integration test for child promotion on parent delete in src/backendng/src/test/kotlin/com/secman/integration/WorkgroupHierarchyIntegrationTest.kt
- [ ] T070 [P] [US3] Write integration test for root-level parent delete promotes children to root in src/backendng/src/test/kotlin/com/secman/integration/WorkgroupHierarchyIntegrationTest.kt

**Verify all tests FAIL** before proceeding to implementation

### Implementation for User Story 3

- [ ] T071 [US3] Implement deleteWithPromotion() method in WorkgroupService with child promotion logic in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T072 [US3] Add @Delete("/{id}") endpoint with @Secured("ADMIN") and @Status(HttpStatus.NO_CONTENT) in WorkgroupController in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T073 [US3] Add audit logging (operation, timestamp, user, children promoted count) for deletion in WorkgroupService.deleteWithPromotion() in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T074 [US3] Run backend tests to verify all User Story 3 tests pass
- [ ] T075 [P] [US3] Add deleteWorkgroup() API method to workgroupApi.ts in src/frontend/src/services/workgroupApi.ts
- [ ] T076 [US3] Add delete button with confirmation dialog to workgroup detail view in src/frontend/src/pages/workgroups.astro
- [ ] T077 [US3] Update confirmation dialog to show child promotion warning when workgroup has children in src/frontend/src/components/DeleteWorkgroupModal.tsx
- [ ] T078 [US3] Write Playwright E2E test for delete with child promotion via UI in src/frontend/tests/workgroups-hierarchy.spec.ts
- [ ] T079 [US3] Run frontend tests to verify User Story 3 E2E test passes

**Checkpoint**: User Story 3 complete and independently testable - deletion with child promotion works

---

## Phase 6: User Story 4 - Move Workgroup in Hierarchy (Priority: P4)

**Goal**: Enable administrators to reorganize hierarchy by moving workgroups from one parent to another with validation (circular reference prevention, depth limits, name uniqueness).

**Independent Test**: Create a hierarchy, move a workgroup to a different parent, and verify all relationships (users, assets, sub-children) remain intact. Test circular reference prevention.

### Tests for User Story 4 (TDD - Write Tests FIRST) ⚠️

- [ ] T080 [P] [US4] Write contract test for PUT /workgroups/{id}/parent success (200) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T081 [P] [US4] Write contract test for PUT /workgroups/{id}/parent circular reference (400) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T082 [P] [US4] Write contract test for PUT /workgroups/{id}/parent depth exceeded (400) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T083 [P] [US4] Write contract test for PUT /workgroups/{id}/parent optimistic lock conflict (409) in src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt
- [ ] T084 [P] [US4] Write unit test for validateNoCircularReference() prevents A→B→A cycles in src/backendng/src/test/kotlin/com/secman/service/WorkgroupValidationServiceTest.kt
- [ ] T085 [P] [US4] Write unit test for validateMove() checks depth, circular refs, uniqueness in src/backendng/src/test/kotlin/com/secman/service/WorkgroupValidationServiceTest.kt
- [ ] T086 [P] [US4] Write unit test for Workgroup.isDescendantOf() returns correct result in src/backendng/src/test/kotlin/com/secman/domain/WorkgroupTest.kt
- [ ] T087 [P] [US4] Write integration test for optimistic locking conflict handling on concurrent moves in src/backendng/src/test/kotlin/com/secman/integration/WorkgroupHierarchyIntegrationTest.kt

**Verify all tests FAIL** before proceeding to implementation

### Implementation for User Story 4

- [ ] T088 [P] [US4] Implement validateNoCircularReference() method in WorkgroupValidationService in src/backendng/src/main/kotlin/com/secman/service/WorkgroupValidationService.kt
- [ ] T089 [P] [US4] Implement validateMove() method in WorkgroupValidationService in src/backendng/src/main/kotlin/com/secman/service/WorkgroupValidationService.kt
- [ ] T090 [P] [US4] Implement calculateSubtreeDepth() private helper method in WorkgroupValidationService in src/backendng/src/main/kotlin/com/secman/service/WorkgroupValidationService.kt
- [ ] T091 [US4] Create MoveWorkgroupRequest DTO in src/backendng/src/main/kotlin/com/secman/dto/MoveWorkgroupRequest.kt
- [ ] T092 [US4] Implement move() method in WorkgroupService with validation and optimistic locking in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T093 [US4] Add @Put("/{id}/parent") endpoint with @Secured("ADMIN") in WorkgroupController in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T094 [US4] Add error handling for OptimisticLockException returning 409 Conflict in WorkgroupController in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T095 [US4] Add audit logging (operation, timestamp, user, old parent, new parent) for move in WorkgroupService.move() in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T096 [US4] Run backend tests to verify all User Story 4 tests pass
- [ ] T097 [P] [US4] Add moveWorkgroup() API method to workgroupApi.ts in src/frontend/src/services/workgroupApi.ts
- [ ] T098 [US4] Create MoveWorkgroupModal React component with parent selection dropdown (excluding self and descendants) in src/frontend/src/components/MoveWorkgroupModal.tsx
- [ ] T099 [US4] Add "Change Parent" button to workgroup detail view in src/frontend/src/pages/workgroups.astro
- [ ] T100 [US4] Add error handling for circular reference, depth limit, and optimistic lock conflicts in MoveWorkgroupModal in src/frontend/src/components/MoveWorkgroupModal.tsx
- [ ] T101 [US4] Write Playwright E2E test for moving workgroup via UI in src/frontend/tests/workgroups-hierarchy.spec.ts
- [ ] T102 [US4] Write Playwright E2E test for optimistic lock conflict retry flow in src/frontend/tests/workgroups-hierarchy.spec.ts
- [ ] T103 [US4] Run frontend tests to verify User Story 4 E2E tests pass

**Checkpoint**: User Story 4 complete and independently testable - moving workgroups with validation works

---

## Phase 7: User Story 5 - Inherit Asset Access from Parent (Priority: P5)

**Goal**: Enable automatic downward permission inheritance where users assigned to parent workgroups get access to assets in child workgroups.

**Independent Test**: Assign user to parent workgroup, assign asset to child workgroup, verify user can access the asset. Verify inheritance is downward-only (child users don't inherit parent assets).

### Tests for User Story 5 (TDD - Write Tests FIRST) ⚠️

- [ ] T104 [P] [US5] Write integration test for parent user accessing child asset in src/backendng/src/test/kotlin/com/secman/integration/WorkgroupPermissionIntegrationTest.kt
- [ ] T105 [P] [US5] Write integration test for child user NOT accessing parent asset (downward-only) in src/backendng/src/test/kotlin/com/secman/integration/WorkgroupPermissionIntegrationTest.kt
- [ ] T106 [P] [US5] Write integration test for user removal from parent revokes child asset access in src/backendng/src/test/kotlin/com/secman/integration/WorkgroupPermissionIntegrationTest.kt
- [ ] T107 [P] [US5] Write unit test for getAccessibleWorkgroups() includes descendants for parent assignment in src/backendng/src/test/kotlin/com/secman/service/WorkgroupServiceTest.kt

**Verify all tests FAIL** before proceeding to implementation

### Implementation for User Story 5

- [ ] T108 [US5] Implement getAccessibleWorkgroups() method using recursive descendant query in WorkgroupService in src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt
- [ ] T109 [US5] Update asset query filtering to include workgroups + all descendant workgroups for user's assigned workgroups in AssetService in src/backendng/src/main/kotlin/com/secman/service/AssetService.kt
- [ ] T110 [US5] Update GET /api/assets endpoint to apply descendant-based filtering in AssetController in src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt
- [ ] T111 [US5] Run backend tests to verify all User Story 5 tests pass
- [ ] T112 [P] [US5] Add UI indicator showing inherited access (vs direct access) on asset list in src/frontend/src/components/AssetList.tsx
- [ ] T113 [US5] Write Playwright E2E test for permission inheritance scenario in src/frontend/tests/workgroups-hierarchy.spec.ts
- [ ] T114 [US5] Run frontend tests to verify User Story 5 E2E test passes

**Checkpoint**: User Story 5 complete and independently testable - permission inheritance works

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T115 [P] Add GET /workgroups/{id}/descendants endpoint for admin dashboard in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T116 [P] Add performance logging for recursive CTE queries in WorkgroupRepository in src/backendng/src/main/kotlin/com/secman/repository/WorkgroupRepository.kt
- [ ] T117 [P] Add database index verification query to confirm idx_workgroup_parent exists in migration script
- [ ] T118 [P] Update API documentation with hierarchy endpoints in OpenAPI spec
- [ ] T119 [P] Add README section documenting hierarchy feature usage
- [ ] T120 [P] Run full backend test suite (contract + unit + integration) and verify ≥80% coverage
- [ ] T121 [P] Run full frontend test suite (E2E Playwright) and verify all tests pass
- [ ] T122 Review quickstart.md and verify all implementation steps match actual code
- [ ] T123 Run manual security review: verify @Secured annotations on all hierarchy endpoints
- [ ] T124 Run manual performance test: create 500 workgroups, verify page load <3s and hierarchy operations <500ms
- [ ] T125 Run manual test: create 5-level hierarchy and verify all CRUD operations work correctly

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-7)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3 → P4 → P5)
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - No dependencies on other stories (independently testable)
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - No dependencies on other stories (independently testable)
- **User Story 4 (P4)**: Can start after Foundational (Phase 2) - No dependencies on other stories (independently testable)
- **User Story 5 (P5)**: Can start after Foundational (Phase 2) - No dependencies on other stories (independently testable)

### Within Each User Story

- **Tests MUST be written FIRST** and MUST FAIL before implementation (TDD constitutional requirement)
- Models/entities before services
- Services before controllers/endpoints
- Backend endpoints before frontend components
- Frontend components before E2E tests
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

- **Setup tasks (T001-T003)**: All marked [P] can run in parallel
- **Foundational tasks (T004-T016)**: Many marked [P] can run in parallel within Phase 2
- **Once Foundational phase completes**: All 5 user stories can start in parallel (if team capacity allows)
- **Within each user story**:
  - All contract tests for that story can run in parallel
  - All unit tests for that story can run in parallel
  - Backend and frontend work can proceed in parallel after endpoints are defined
- **Polish tasks (T115-T125)**: All marked [P] can run in parallel

---

## Parallel Example: User Story 1

```bash
# Write all tests together (tests FAIL initially):
Task T019: "Write contract test for POST /workgroups/{id}/children success"
Task T020: "Write contract test for POST /workgroups/{id}/children depth exceeded"
Task T021: "Write contract test for POST /workgroups/{id}/children sibling conflict"
Task T022-T024: Additional contract tests
Task T025-T027: Unit tests

# After tests fail, launch parallel implementation:
Task T028: "Implement validateDepthLimit() method"
Task T029: "Implement validateSiblingUniqueness() method"

# Backend and frontend can proceed in parallel:
Backend: Task T031-T036 (service + controller + logging)
Frontend: Task T037-T040 (modal + API integration)
```

---

## Parallel Example: Multiple User Stories (Team Strategy)

```bash
# After Foundational phase (T004-T018) completes:

# Developer A works on User Story 1 (MVP):
Task T019-T042 (Create Child Workgroup)

# Developer B works on User Story 2 (View Hierarchy):
Task T043-T065 (Tree visualization + breadcrumbs)

# Developer C works on User Story 3 (Delete with Promotion):
Task T066-T079 (Delete with child promotion)

# All three stories can complete independently and integrate without conflicts
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T018) - CRITICAL, blocks all stories
3. Complete Phase 3: User Story 1 (T019-T042)
4. **STOP and VALIDATE**: Test User Story 1 independently
5. Deploy/demo MVP - administrators can now create child workgroups

**MVP Deliverable**: Hierarchical workgroup creation with validation (depth limit, sibling uniqueness, circular reference prevention)

### Incremental Delivery

1. **Foundation** (Setup + Foundational) → Database ready, entity structure in place
2. **MVP** (+User Story 1) → Create child workgroups → Deploy/Demo ✅
3. **Enhanced** (+User Story 2) → View hierarchy with tree/breadcrumbs → Deploy/Demo ✅
4. **Robust** (+User Story 3) → Delete with child promotion → Deploy/Demo ✅
5. **Flexible** (+User Story 4) → Move workgroups → Deploy/Demo ✅
6. **Advanced** (+User Story 5) → Permission inheritance → Deploy/Demo ✅
7. **Production-Ready** (+Polish) → Performance validated, documentation complete → Production deployment

Each increment adds value without breaking previous stories.

### Parallel Team Strategy

With 3+ developers after Foundational phase completes:

1. **All team members**: Complete Setup + Foundational together (T001-T018)
2. **Once T018 checkpoint passes**:
   - **Developer A**: User Story 1 (T019-T042) - MVP
   - **Developer B**: User Story 2 (T043-T065) - Visualization
   - **Developer C**: User Story 3 (T066-T079) - Deletion
   - **Developer D** (optional): User Story 4 (T080-T103) - Move
3. Stories complete independently and integrate seamlessly
4. **Team**: Polish phase together (T115-T125)

---

## Task Summary

- **Total Tasks**: 125
- **Setup Phase**: 3 tasks
- **Foundational Phase**: 15 tasks (BLOCKS all stories)
- **User Story 1 (MVP)**: 24 tasks (19 tests + 5 implementation + backend + frontend)
- **User Story 2**: 23 tasks
- **User Story 3**: 14 tasks
- **User Story 4**: 24 tasks
- **User Story 5**: 11 tasks
- **Polish**: 11 tasks

**Parallel Opportunities**: 67 tasks marked [P] can run in parallel with other tasks in same phase

**Independent Stories**: All 5 user stories are independently testable after Foundational phase

**Test Coverage**: 57 test tasks (contract + unit + integration + E2E) ensuring TDD compliance and ≥80% coverage target

---

## Notes

- **[P] tasks**: Different files, no dependencies - can run in parallel
- **[Story] label**: Maps task to specific user story for traceability
- **TDD Mandatory**: All tests MUST be written first and MUST fail before implementation
- **Independent Stories**: Each user story can be completed and tested independently
- **Commit Strategy**: Commit after each task or logical group
- **Stop at Checkpoints**: Validate story independence before proceeding
- **Avoid**: Vague tasks, same file conflicts, cross-story dependencies that break independence
- **Security**: All hierarchy modification endpoints use @Secured("ADMIN"), view endpoints use @Secured(IS_AUTHENTICATED)
- **Performance**: Target <500ms for hierarchy operations, <3s page load for 100 workgroups, verify at T124
- **Database**: Hibernate auto-migration handles schema changes, verify at T018
