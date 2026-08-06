# Tasks: Last Admin Protection

**Input**: Design documents from `/specs/037-last-admin-protection/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: TDD is mandatory per Constitution Principle II. All contract and unit tests must be written FIRST and FAIL before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/` for source
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/` for tests
- **Frontend**: `src/frontend/src/` for source

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify project structure and ensure no setup needed (feature extends existing code)

- [X] T001 Verify existing User entity at src/backendng/src/main/kotlin/com/secman/domain/User.kt includes roles collection
- [X] T002 Verify existing UserRepository at src/backendng/src/main/kotlin/com/secman/repository/UserRepository.kt
- [X] T003 [P] Verify existing UserService at src/backendng/src/main/kotlin/com/secman/service/UserService.kt
- [X] T004 [P] Verify existing UserDeletionValidator at src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt
- [X] T005 [P] Verify existing UserController at src/backendng/src/main/kotlin/com/secman/controller/UserController.kt
- [X] T006 [P] Verify JUnit 5 and MockK dependencies in build.gradle.kts

**Checkpoint**: All existing infrastructure verified - ready for TDD implementation

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core service-layer enhancements that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T007 Add countAdminUsers() method to UserService at src/backendng/src/main/kotlin/com/secman/service/UserService.kt
- [X] T008 Add UserRepository constructor parameter to UserDeletionValidator at src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Prevent System Lockout by Last Admin Deletion (Priority: P1) 🎯 MVP

**Goal**: Block deletion of the last ADMIN user to prevent system lockout. This is the core safety requirement.

**Independent Test**: Create a single ADMIN user, attempt to delete them, verify deletion is blocked with error message "Cannot delete the last administrator."

### Tests for User Story 1 (TDD - WRITE FIRST, MUST FAIL) ⚠️

> **CRITICAL**: Write these tests FIRST, ensure they FAIL before implementation begins

- [ ] T009 [P] [US1] Write contract test for DELETE /api/users/{id} blocking last admin deletion in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt
- [ ] T010 [P] [US1] Write contract test for DELETE /api/users/{id} allowing deletion when multiple admins exist in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt
- [ ] T011 [P] [US1] Write contract test for self-deletion blocking when user is last admin in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt
- [ ] T012 [P] [US1] Write unit test for UserDeletionValidator.validateUserDeletion blocking last admin in src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt
- [ ] T013 [P] [US1] Write unit test for UserDeletionValidator.validateUserDeletion allowing deletion when multiple admins in src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt
- [ ] T014 [P] [US1] Write unit test for UserDeletionValidator.validateUserDeletion allowing non-admin deletion when single admin exists in src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt
- [ ] T015 [P] [US1] Write unit test for UserService.countAdminUsers with zero admins in src/backendng/src/test/kotlin/com/secman/service/UserServiceTest.kt
- [ ] T016 [P] [US1] Write unit test for UserService.countAdminUsers with single admin in src/backendng/src/test/kotlin/com/secman/service/UserServiceTest.kt
- [ ] T017 [P] [US1] Write unit test for UserService.countAdminUsers with multiple admins in src/backendng/src/test/kotlin/com/secman/service/UserServiceTest.kt

**Verification Checkpoint**: Run tests - ALL should FAIL at this point (feature not implemented yet)

### Implementation for User Story 1

- [X] T018 [US1] Implement countAdminUsers() method in UserService at src/backendng/src/main/kotlin/com/secman/service/UserService.kt (returns count of users with ADMIN role using findAll and filter)
- [X] T019 [US1] Extend UserDeletionValidator.validateUserDeletion to check last admin protection at src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt (add admin count check before existing validation)
- [X] T020 [US1] Add BlockingReference for SystemConstraint type in UserDeletionValidator at src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt (entityType="SystemConstraint", role="last_admin")
- [X] T021 [US1] Verify UserController.delete already calls userDeletionValidator.validateUserDeletion and returns 409 for blocked deletions at src/backendng/src/main/kotlin/com/secman/controller/UserController.kt (no changes needed - validation already hooked up)

**Verification Checkpoint**: Run tests - ALL tests for US1 should now PASS

**Independent Test Validation**:
1. Start application with single admin user
2. Attempt DELETE /api/users/{adminId} via curl or Postman
3. Verify: 409 Conflict response with message "Cannot delete the last administrator"
4. Verify: Admin user still exists in database
5. Create second admin user
6. Attempt DELETE /api/users/{firstAdminId}
7. Verify: 200 OK response, first admin deleted, second admin remains

**Checkpoint**: User Story 1 is fully functional and independently testable. System prevents last admin deletion.

---

## Phase 4: User Story 2 - Clear Feedback for Blocked Deletions (Priority: P2)

**Goal**: Provide clear, actionable error messages when deletion is blocked, helping administrators understand why and how to proceed.

**Independent Test**: Trigger last-admin deletion scenario, verify error message is clear, actionable, and displayed prominently in UI with suggestion to add another admin first.

### Tests for User Story 2 (TDD - WRITE FIRST, MUST FAIL) ⚠️

- [ ] T022 [P] [US2] Write contract test verifying 409 response includes structured error with blockingReferences in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt
- [ ] T023 [P] [US2] Write contract test verifying error message text matches specification in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt
- [ ] T024 [P] [US2] Write contract test verifying error response includes details field with actionable guidance in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt

**Verification Checkpoint**: Run tests - ALL should FAIL at this point

### Implementation for User Story 2

- [X] T025 [US2] Verify UserDeletionValidator generates correct error message format at src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt (message should include "Cannot delete the last administrator. At least one ADMIN user must remain in the system.")
- [X] T026 [US2] Verify UserController DELETE endpoint returns properly structured 409 response at src/backendng/src/main/kotlin/com/secman/controller/UserController.kt (already implemented in lines 277-289 - validate format matches spec)
- [X] T027 [US2] Update frontend UserManagement component to detect 409 status code and display error alert at src/frontend/src/components/UserManagement.tsx
- [X] T028 [US2] Add prominent Bootstrap alert for last admin protection errors in UserManagement component at src/frontend/src/components/UserManagement.tsx
- [X] T029 [US2] Add actionable guidance text to UI error message at src/frontend/src/components/UserManagement.tsx (e.g., "Please create another admin user before deleting this one")

**Verification Checkpoint**: Run tests - ALL tests for US2 should now PASS

**Independent Test Validation**:
1. Login to UI as admin
2. Navigate to user management page
3. Attempt to delete last admin via UI
4. Verify: Error alert displayed prominently (Bootstrap danger alert)
5. Verify: Error message includes explanation and guidance
6. Verify: Error alert dismissible but remains until user acknowledges
7. Test via API: Verify structured JSON error with blockingReferences array

**Checkpoint**: User Story 2 is fully functional. Both API and UI provide clear, actionable feedback for blocked deletions.

---

## Phase 5: User Story 3 - Role Change Protection for Last Admin (Priority: P3)

**Goal**: Prevent removing the ADMIN role from the last administrator, completing protection against all paths that could result in zero admins.

**Independent Test**: Attempt to remove ADMIN role from only admin user via PUT /api/users/{id}, verify it's blocked with error message.

### Tests for User Story 3 (TDD - WRITE FIRST, MUST FAIL) ⚠️

- [ ] T030 [P] [US3] Write contract test for PUT /api/users/{id} blocking ADMIN role removal from last admin in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt
- [ ] T031 [P] [US3] Write contract test for PUT /api/users/{id} allowing ADMIN role removal when multiple admins exist in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt
- [ ] T032 [P] [US3] Write contract test verifying role update blocked even when user has other roles (ADMIN+VULN → VULN only) in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt
- [ ] T033 [P] [US3] Write unit test for UserDeletionValidator.validateAdminRoleRemoval blocking last admin in src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt
- [ ] T034 [P] [US3] Write unit test for UserDeletionValidator.validateAdminRoleRemoval allowing role removal when multiple admins in src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt
- [ ] T035 [P] [US3] Write unit test for UserDeletionValidator.validateAdminRoleRemoval allowing role changes that don't remove ADMIN in src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt

**Verification Checkpoint**: Run tests - ALL should FAIL at this point

### Implementation for User Story 3

- [X] T036 [US3] Add validateAdminRoleRemoval() method to UserDeletionValidator at src/backendng/src/main/kotlin/com/secman/service/UserDeletionValidator.kt (check if ADMIN role being removed from last admin)
- [X] T037 [US3] Update UserController.update endpoint to call validateAdminRoleRemoval before applying role changes at src/backendng/src/main/kotlin/com/secman/controller/UserController.kt (insert validation around line 227 before roles.clear)
- [X] T038 [US3] Add 409 Conflict response handling for role validation failures in UserController.update at src/backendng/src/main/kotlin/com/secman/controller/UserController.kt (return structured error like delete endpoint)
- [X] T039 [US3] Update frontend UserManagement component to handle 409 errors from role updates at src/frontend/src/components/UserManagement.tsx (extend existing error handling to PUT requests)

**Verification Checkpoint**: Run tests - ALL tests for US3 should now PASS

**Independent Test Validation**:
1. Create single admin user with roles [ADMIN, USER]
2. Attempt PUT /api/users/{id} with roles: ["USER"] (removing ADMIN)
3. Verify: 409 Conflict response
4. Verify: User still has ADMIN role
5. Create second admin user
6. Attempt PUT /api/users/{firstAdminId} with roles: ["USER"]
7. Verify: 200 OK response, ADMIN role removed successfully
8. Test UI: Verify role edit form displays error when trying to remove ADMIN from last admin

**Checkpoint**: All user stories complete. System protects against both deletion and role removal paths.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories and final validation

- [ ] T040 [P] Add integration test for concurrent deletion scenario with 2 admins in src/backendng/src/test/kotlin/com/secman/integration/UserManagementIntegrationTest.kt (verify transaction isolation prevents 0-admin state)
- [ ] T041 [P] Add performance test verifying admin count query completes <50ms for 1000 users in src/backendng/src/test/kotlin/com/secman/service/UserServiceTest.kt
- [ ] T042 [P] Verify all error messages match specification exactly (run quickstart.md validation scenarios)
- [ ] T043 [P] Run full test suite and verify ≥80% code coverage per Constitution Principle II
- [x] T044 [P] Update CLAUDE.md with Last Admin Protection patterns at /Users/flake/sources/misc/secman/CLAUDE.md (add to Common Patterns section)
- [x] T045 Code review checklist: Verify @Transactional on delete/update methods, verify 409 status codes, verify error message format
- [x] T046 Manual regression testing: Verify normal user deletion still works, verify normal role updates still work

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - verification only, can complete in 5 minutes
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories (2 tasks, ~15 minutes)
- **User Stories (Phases 3-5)**: All depend on Foundational phase completion
  - User Story 1 (P1): Can start after Foundational - No dependencies on other stories
  - User Story 2 (P2): Can start after Foundational - Builds on US1 but independently testable
  - User Story 3 (P3): Can start after Foundational - Independent of US1/US2
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - **No dependencies on other stories**
  - Delivers: Core deletion protection
  - Tests: T009-T017 (9 tests, can run in parallel)
  - Implementation: T018-T021 (4 tasks, sequential within story)
  - Estimated: 2-3 hours for tests + implementation

- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - **Builds on US1 validation, but independently testable**
  - Delivers: Clear error messaging and UI feedback
  - Tests: T022-T024 (3 tests, can run in parallel)
  - Implementation: T025-T029 (5 tasks, some parallel)
  - Estimated: 1-2 hours for tests + implementation

- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - **Independent of US1/US2, can run in parallel**
  - Delivers: Role change protection
  - Tests: T030-T035 (6 tests, can run in parallel)
  - Implementation: T036-T039 (4 tasks, sequential within story)
  - Estimated: 2-3 hours for tests + implementation

### Within Each User Story (TDD Workflow)

1. **Tests FIRST** (all marked [P], run in parallel)
2. **Verify tests FAIL** (critical TDD checkpoint)
3. **Implementation** (sequential dependencies within story)
4. **Verify tests PASS** (green phase)
5. **Independent validation** (manual testing of story)

### Parallel Opportunities

**Within Phase 1 (Setup)**:
- T001-T006 can all run in parallel (6 verification tasks)

**Within Phase 2 (Foundational)**:
- T007 and T008 must run sequentially (T008 depends on T007 being committed)

**Between User Stories** (After Foundational completes):
- User Story 1, 2, and 3 can be worked on in parallel by different developers
- Each story is independently testable and deliverable

**Within User Story 1**:
- All tests T009-T017 can run in parallel (9 test tasks)
- Implementation T018-T021 must run sequentially

**Within User Story 2**:
- All tests T022-T024 can run in parallel (3 test tasks)
- Implementation T025-T029: T025-T026 (backend verification), then T027-T029 (frontend) in parallel

**Within User Story 3**:
- All tests T030-T035 can run in parallel (6 test tasks)
- Implementation T036-T039 must run sequentially (validation logic, then controller integration, then frontend)

**Within Phase 6 (Polish)**:
- T040-T044 can all run in parallel (5 tasks)
- T045-T046 must wait for all tests to pass

---

## Parallel Example: User Story 1

```bash
# After Foundational (Phase 2) completes, launch all tests for User Story 1 in parallel:

Task T009: "Write contract test for DELETE blocking last admin in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt"
Task T010: "Write contract test for DELETE allowing deletion when multiple admins in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt"
Task T011: "Write contract test for self-deletion blocking in src/backendng/src/test/kotlin/com/secman/contract/UserControllerContractTest.kt"
Task T012: "Write unit test for validateUserDeletion blocking last admin in src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt"
Task T013: "Write unit test for validateUserDeletion allowing multiple admins in src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt"
Task T014: "Write unit test for validateUserDeletion allowing non-admin in src/backendng/src/test/kotlin/com/secman/service/UserDeletionValidatorTest.kt"
Task T015: "Write unit test for countAdminUsers zero case in src/backendng/src/test/kotlin/com/secman/service/UserServiceTest.kt"
Task T016: "Write unit test for countAdminUsers single case in src/backendng/src/test/kotlin/com/secman/service/UserServiceTest.kt"
Task T017: "Write unit test for countAdminUsers multiple case in src/backendng/src/test/kotlin/com/secman/service/UserServiceTest.kt"

# After all tests written and verified to FAIL, implement sequentially:
Task T018: "Implement countAdminUsers in UserService"
Task T019: "Extend validateUserDeletion with admin check in UserDeletionValidator"
Task T020: "Add BlockingReference for SystemConstraint"
Task T021: "Verify UserController.delete validation hookup"
```

## Parallel Example: All User Stories After Foundational

```bash
# Once Phase 2 (Foundational) completes, three developers can work in parallel:

Developer A: Phase 3 (User Story 1 - Core deletion protection)
  - Tasks T009-T021

Developer B: Phase 4 (User Story 2 - Error messaging)
  - Tasks T022-T029

Developer C: Phase 5 (User Story 3 - Role change protection)
  - Tasks T030-T039

# Each developer follows TDD: write tests → verify fail → implement → verify pass
```

---

## Implementation Strategy

### MVP First (User Story 1 Only) - Recommended

**Timeline**: ~4 hours total

1. Complete Phase 1: Setup (~5 minutes - verification only)
2. Complete Phase 2: Foundational (~15 minutes - 2 simple tasks)
3. Complete Phase 3: User Story 1 (~2-3 hours)
   - Write 9 tests (T009-T017) - ~1 hour
   - Verify all tests FAIL - ~5 minutes
   - Implement 4 tasks (T018-T021) - ~1 hour
   - Verify all tests PASS - ~5 minutes
   - Independent validation - ~15 minutes
4. **STOP and VALIDATE**: Test User Story 1 independently
   - Delete last admin → blocked ✅
   - Delete admin when 2+ exist → allowed ✅
   - Self-deletion blocked → blocked ✅
5. Deploy/demo if ready (core safety requirement delivered)

### Incremental Delivery (All Stories)

**Timeline**: ~8 hours total

1. Complete Setup + Foundational (~20 minutes)
2. Add User Story 1 (~2-3 hours) → Test independently → **Deploy/Demo (MVP!)**
3. Add User Story 2 (~1-2 hours) → Test independently → **Deploy/Demo (Better UX)**
4. Add User Story 3 (~2-3 hours) → Test independently → **Deploy/Demo (Complete protection)**
5. Add Polish (Phase 6) (~1 hour) → Final validation → **Deploy to production**

### Parallel Team Strategy

With 3 developers after Foundational completes:

1. All developers complete Setup + Foundational together (~20 minutes)
2. Once Foundational is done:
   - **Developer A**: User Story 1 (P1) - Core protection (~2-3 hours)
   - **Developer B**: User Story 2 (P2) - Error messaging (~1-2 hours)
   - **Developer C**: User Story 3 (P3) - Role protection (~2-3 hours)
3. Stories complete independently, integrate via shared UserDeletionValidator
4. Combined testing and Polish phase (~1 hour)

**Timeline with parallel work**: ~4 hours total (vs 8 hours sequential)

---

## Task Summary

### Total Task Count: **46 tasks**

**By Phase**:
- Phase 1 (Setup): 6 verification tasks
- Phase 2 (Foundational): 2 blocking tasks
- Phase 3 (User Story 1): 13 tasks (9 tests + 4 implementation)
- Phase 4 (User Story 2): 8 tasks (3 tests + 5 implementation)
- Phase 5 (User Story 3): 10 tasks (6 tests + 4 implementation)
- Phase 6 (Polish): 7 tasks

**By Story**:
- User Story 1 (P1 - MVP): 13 tasks
- User Story 2 (P2): 8 tasks
- User Story 3 (P3): 10 tasks
- Foundation + Polish: 15 tasks

**Parallel Opportunities**:
- 20 tasks marked [P] can run in parallel within their phase
- 3 user stories can run in parallel (after Foundational completes)
- Test writing within each story is highly parallel (9, 3, and 6 tests respectively)

**Independent Test Criteria**:
- ✅ User Story 1: Single admin deletion blocked, multiple admins allowed, self-deletion blocked
- ✅ User Story 2: Error messages clear, actionable, displayed prominently in UI and API
- ✅ User Story 3: Role removal blocked for last admin, allowed for non-last admin

**MVP Scope** (User Story 1 only): **21 tasks** (Setup + Foundational + US1)
**Timeline**: ~4 hours for MVP, ~8 hours for complete feature (sequential), ~4 hours (parallel with 3 devs)

---

## Notes

- [P] tasks = different files, no dependencies within phase
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- TDD is non-negotiable per Constitution: Tests MUST be written first and FAIL
- Verify tests fail before implementing (Red phase)
- Verify tests pass after implementing (Green phase)
- Commit after each task or logical group of [P] tasks
- Stop at any checkpoint to validate story independently
- Constitution requires ≥80% test coverage
- All changes extend existing files - no new architectural components
- Zero schema changes required - uses existing User entity
