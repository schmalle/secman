# Tasks: Role-Based Access Control - RISK, REQ, and SECCHAMPION Roles

**Input**: Design documents from `/specs/025-role-based-access-control/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅

**Tests**: Following TDD (Test-Driven Development) - NON-NEGOTIABLE constitutional requirement. All tests are written FIRST before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `- [ ] [ID] [P?] [Story?] Description`
- **Checkbox**: ALWAYS `- [ ]` (markdown checkbox)
- **[ID]**: Sequential task number (T001, T002, ...)
- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: User story label (US1, US2, US3, US4, US5) - only for user story phases
- Include exact file paths in descriptions

## Path Conventions (Web Application)
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend Tests**: `src/frontend/tests/e2e/`
- **Documentation**: `README.md` at repository root

---

## Phase 1: Setup (Shared Infrastructure) ✅ COMPLETE

**Purpose**: Database migration and access denial logging infrastructure (required for all user stories)

- [x] T001 Create database migration script V2__rename_champion_to_secchampion.sql in src/backendng/src/main/resources/db/migration/
- [x] T002 Create rollback migration script in db/rollback/V2__rollback_secchampion_to_champion.sql
- [x] T003 [P] Create AccessDenialLogger service in src/backendng/src/main/kotlin/com/secman/service/AccessDenialLogger.kt
- [x] T004 [P] Configure Logback for ACCESS_DENIAL_AUDIT logger in src/backendng/src/main/resources/logback.xml

**Checkpoint**: ✅ Migration and logging infrastructure ready

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core User entity updates and logging service that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Tests for Foundation (TDD - Write FIRST)

- [ ] T005 [P] Write unit test for RISK role in enum in src/backendng/src/test/kotlin/com/secman/domain/UserTest.kt
- [ ] T006 [P] Write unit test for SECCHAMPION role (not CHAMPION) in src/backendng/src/test/kotlin/com/secman/domain/UserTest.kt
- [ ] T007 [P] Write unit test for User.hasRiskAccess() method in src/backendng/src/test/kotlin/com/secman/domain/UserTest.kt
- [ ] T008 [P] Write unit test for User.hasReqAccess() method in src/backendng/src/test/kotlin/com/secman/domain/UserTest.kt
- [ ] T009 [P] Write unit test for User.hasVulnAccess() method in src/backendng/src/test/kotlin/com/secman/domain/UserTest.kt
- [ ] T010 [P] Write integration test for SECCHAMPION role persistence in src/backendng/src/test/kotlin/com/secman/integration/RoleEnumPersistenceTest.kt
- [ ] T011 [P] Write integration test for RISK role persistence in src/backendng/src/test/kotlin/com/secman/integration/RoleEnumPersistenceTest.kt
- [ ] T012 [P] Write unit test for AccessDenialLogger.logAccessDenial() in src/backendng/src/test/kotlin/com/secman/service/AccessDenialLoggerTest.kt
- [ ] T013 [P] Write unit test for AccessDenialLogger MDC context cleanup in src/backendng/src/test/kotlin/com/secman/service/AccessDenialLoggerTest.kt

### Implementation for Foundation

- [ ] T014 Update User.Role enum (add RISK, rename CHAMPION to SECCHAMPION) in src/backendng/src/main/kotlin/com/secman/domain/User.kt
- [ ] T015 Add User.hasRiskAccess() method in src/backendng/src/main/kotlin/com/secman/domain/User.kt
- [ ] T016 Add User.hasReqAccess() method in src/backendng/src/main/kotlin/com/secman/domain/User.kt
- [ ] T017 Add User.hasVulnAccess() method in src/backendng/src/main/kotlin/com/secman/domain/User.kt
- [ ] T018 Run database migration V2__rename_champion_to_secchampion.sql on dev database
- [ ] T019 Verify migration success (0 CHAMPION roles, N SECCHAMPION roles) via SQL query
- [ ] T020 Run all foundation tests (T005-T013) - all must pass before proceeding

**Checkpoint**: Foundation complete ✅ - User stories can now be implemented in parallel

---

## Phase 3: User Story 1 - Risk Manager Accessing Risk Management (Priority: P1) 🎯 MVP

**Goal**: Enable users with RISK role to access Risk Management section and all sub-items, while denying access to Requirements and Admin

**Independent Test**: Assign user RISK role, login, verify access to Risk Management, verify denial to Requirements/Admin with generic error message and access denial logging

### Tests for User Story 1 (TDD - Write FIRST) ⚠️

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T021 [P] [US1] Contract test: RISK role can GET /api/risk-assessments in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T022 [P] [US1] Contract test: RISK role can POST /api/risk-assessments in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T023 [P] [US1] Contract test: RISK role can PUT /api/risk-assessments/{id} in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T024 [P] [US1] Contract test: RISK role can DELETE /api/risk-assessments/{id} in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T025 [P] [US1] Contract test: RISK role CANNOT GET /api/requirements (403 Forbidden) in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T026 [P] [US1] Contract test: RISK role CANNOT GET /api/admin/* (403 Forbidden) in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T027 [P] [US1] Contract test: Access denial is logged when RISK accesses /api/requirements in src/backendng/src/test/kotlin/com/secman/contract/AccessDenialLoggingContractTest.kt
- [ ] T028 [P] [US1] E2E test: RISK user sees Risk Management in navigation in src/frontend/tests/e2e/risk-role-access.spec.ts
- [ ] T029 [P] [US1] E2E test: RISK user does NOT see Requirements in navigation in src/frontend/tests/e2e/risk-role-access.spec.ts
- [ ] T030 [P] [US1] E2E test: RISK user gets generic 403 error when accessing Requirements in src/frontend/tests/e2e/risk-role-access.spec.ts

### Implementation for User Story 1

- [ ] T031 [P] [US1] Update @Secured annotation on RiskAssessmentController to include RISK role in src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt
- [ ] T032 [P] [US1] Add access denial logging to RiskAssessmentController error handler in src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt
- [ ] T033 [P] [US1] Create hasRiskAccess() permission helper in frontend src/frontend/src/utils/permissions.ts
- [ ] T034 [US1] Update Sidebar.tsx to show Risk Management for RISK role in src/frontend/src/components/Sidebar.tsx
- [ ] T035 [US1] Update Sidebar.tsx to hide Requirements for RISK role in src/frontend/src/components/Sidebar.tsx
- [ ] T036 [US1] Update Sidebar.tsx to hide Admin for RISK role in src/frontend/src/components/Sidebar.tsx
- [ ] T037 [P] [US1] Create PermissionDenied component with generic error message in src/frontend/src/components/PermissionDenied.tsx
- [ ] T038 [US1] Run all US1 tests (T021-T030) - all must pass

**Checkpoint**: User Story 1 complete ✅ - RISK role functional, access control enforced, audit logging working

---

## Phase 4: User Story 2 - Requirements Manager Accessing Requirements (Priority: P1) 🎯 MVP

**Goal**: Enable users with REQ role to access Requirements section and all sub-folders, while denying access to Risk Management and Admin

**Independent Test**: Assign user REQ role, login, verify access to Requirements, verify denial to Risk Management/Admin with generic error message and access denial logging

### Tests for User Story 2 (TDD - Write FIRST) ⚠️

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T039 [P] [US2] Contract test: REQ role can GET /api/requirements in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T040 [P] [US2] Contract test: REQ role can POST /api/requirements in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T041 [P] [US2] Contract test: REQ role can GET /api/norms in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T042 [P] [US2] Contract test: REQ role can GET /api/usecases in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T043 [P] [US2] Contract test: REQ role CANNOT GET /api/risk-assessments (403 Forbidden) in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T044 [P] [US2] Contract test: REQ role CANNOT GET /api/admin/* (403 Forbidden) in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T045 [P] [US2] Contract test: Access denial is logged when REQ accesses /api/risk-assessments in src/backendng/src/test/kotlin/com/secman/contract/AccessDenialLoggingContractTest.kt
- [ ] T046 [P] [US2] E2E test: REQ user sees Requirements in navigation in src/frontend/tests/e2e/req-role-access.spec.ts
- [ ] T047 [P] [US2] E2E test: REQ user does NOT see Risk Management in navigation in src/frontend/tests/e2e/req-role-access.spec.ts
- [ ] T048 [P] [US2] E2E test: REQ user gets generic 403 error when accessing Risk Management in src/frontend/tests/e2e/req-role-access.spec.ts

### Implementation for User Story 2

- [ ] T049 [P] [US2] Update @Secured annotation on RequirementController to include REQ role in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt
- [ ] T050 [P] [US2] Update @Secured annotation on NormController to include REQ role in src/backendng/src/main/kotlin/com/secman/controller/NormController.kt
- [ ] T051 [P] [US2] Update @Secured annotation on UseCaseController to include REQ role in src/backendng/src/main/kotlin/com/secman/controller/UseCaseController.kt
- [ ] T052 [P] [US2] Add access denial logging to RequirementController error handler in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt
- [ ] T053 [P] [US2] Create hasReqAccess() permission helper in frontend src/frontend/src/utils/permissions.ts
- [ ] T054 [US2] Update Sidebar.tsx to show Requirements for REQ role in src/frontend/src/components/Sidebar.tsx
- [ ] T055 [US2] Update Sidebar.tsx to hide Risk Management for REQ role in src/frontend/src/components/Sidebar.tsx
- [ ] T056 [US2] Update Sidebar.tsx to hide Admin for REQ role in src/frontend/src/components/Sidebar.tsx
- [ ] T057 [US2] Run all US2 tests (T039-T048) - all must pass

**Checkpoint**: User Story 2 complete ✅ - REQ role functional, access control enforced, audit logging working

---

## Phase 5: User Story 3 - Security Champion Accessing Multiple Protected Areas (Priority: P2)

**Goal**: Enable users with SECCHAMPION role to access Risk Management, Requirements, and Vulnerabilities, while explicitly denying Admin access

**Independent Test**: Assign user SECCHAMPION role, login, verify access to Risk/Req/Vuln, verify denial to Admin with generic error message and access denial logging

### Tests for User Story 3 (TDD - Write FIRST) ⚠️

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T058 [P] [US3] Contract test: SECCHAMPION role can GET /api/risk-assessments in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T059 [P] [US3] Contract test: SECCHAMPION role can GET /api/requirements in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T060 [P] [US3] Contract test: SECCHAMPION role can GET /api/vulnerabilities in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T061 [P] [US3] Contract test: SECCHAMPION role can POST /api/vulnerability-exceptions in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T062 [P] [US3] Contract test: SECCHAMPION role CANNOT GET /api/admin/* (403 Forbidden) in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T063 [P] [US3] Contract test: SECCHAMPION role CANNOT GET /api/users (403 Forbidden) in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T064 [P] [US3] Contract test: SECCHAMPION role CANNOT GET /api/workgroups (403 Forbidden) in src/backendng/src/test/kotlin/com/secman/contract/RoleAuthorizationContractTest.kt
- [ ] T065 [P] [US3] Contract test: Access denial is logged when SECCHAMPION accesses /api/admin in src/backendng/src/test/kotlin/com/secman/contract/AccessDenialLoggingContractTest.kt
- [ ] T066 [P] [US3] E2E test: SECCHAMPION user sees Risk, Requirements, Vulnerabilities in navigation in src/frontend/tests/e2e/secchampion-role-access.spec.ts
- [ ] T067 [P] [US3] E2E test: SECCHAMPION user does NOT see Admin in navigation in src/frontend/tests/e2e/secchampion-role-access.spec.ts
- [ ] T068 [P] [US3] E2E test: SECCHAMPION user gets generic 403 error when accessing Admin in src/frontend/tests/e2e/secchampion-role-access.spec.ts

### Implementation for User Story 3

- [ ] T069 [P] [US3] Update @Secured annotation on RiskAssessmentController to include SECCHAMPION role in src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt
- [ ] T070 [P] [US3] Update @Secured annotation on RequirementController to include SECCHAMPION role in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt
- [ ] T071 [P] [US3] Update @Secured annotation on VulnerabilityController to include SECCHAMPION role in src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityController.kt
- [ ] T072 [P] [US3] Update @Secured annotation on VulnerabilityExceptionController to include SECCHAMPION role in src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityExceptionController.kt
- [ ] T073 [P] [US3] Add access denial logging to UserController for SECCHAMPION denials in src/backendng/src/main/kotlin/com/secman/controller/UserController.kt
- [ ] T074 [P] [US3] Add access denial logging to WorkgroupController for SECCHAMPION denials in src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt
- [ ] T075 [P] [US3] Create hasSecChampionAccess() permission helper in frontend src/frontend/src/utils/permissions.ts
- [ ] T076 [US3] Update Sidebar.tsx to show Risk, Requirements, Vulnerabilities for SECCHAMPION role in src/frontend/src/components/Sidebar.tsx
- [ ] T077 [US3] Update Sidebar.tsx to hide Admin for SECCHAMPION role in src/frontend/src/components/Sidebar.tsx
- [ ] T078 [US3] Run all US3 tests (T058-T068) - all must pass

**Checkpoint**: User Story 3 complete ✅ - SECCHAMPION role functional with broad access but no admin privileges

---

## Phase 6: User Story 4 - Admin Assigning Roles to Users (Priority: P2)

**Goal**: Enable ADMIN users to assign RISK, REQ, SECCHAMPION roles to users through existing user management interface

**Independent Test**: Login as ADMIN, navigate to user management, verify new roles appear in dropdown, assign/remove roles, verify immediate effect on next API request

### Tests for User Story 4 (TDD - Write FIRST) ⚠️

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T079 [P] [US4] Contract test: ADMIN can assign RISK role to user via PUT /api/users/{id}/roles in src/backendng/src/test/kotlin/com/secman/contract/UserRoleManagementContractTest.kt
- [ ] T080 [P] [US4] Contract test: ADMIN can assign SECCHAMPION role to user in src/backendng/src/test/kotlin/com/secman/contract/UserRoleManagementContractTest.kt
- [ ] T081 [P] [US4] Contract test: ADMIN can remove RISK role from user in src/backendng/src/test/kotlin/com/secman/contract/UserRoleManagementContractTest.kt
- [ ] T082 [P] [US4] Contract test: Non-ADMIN user CANNOT assign roles (403 Forbidden) in src/backendng/src/test/kotlin/com/secman/contract/UserRoleManagementContractTest.kt
- [ ] T083 [P] [US4] Integration test: Role change takes effect on next API request (no caching) in src/backendng/src/test/kotlin/com/secman/integration/RoleChangeEffectTest.kt
- [ ] T084 [P] [US4] Integration test: Multiple roles grant combined permissions in src/backendng/src/test/kotlin/com/secman/integration/MultiRolePermissionTest.kt
- [ ] T085 [P] [US4] Contract test: Invalid role assignment is rejected with validation error in src/backendng/src/test/kotlin/com/secman/contract/UserRoleManagementContractTest.kt
- [ ] T086 [P] [US4] E2E test: ADMIN sees RISK, REQ, SECCHAMPION in role dropdown in src/frontend/tests/e2e/admin-role-assignment.spec.ts
- [ ] T087 [P] [US4] E2E test: Assigned role becomes effective within 30 seconds in src/frontend/tests/e2e/admin-role-assignment.spec.ts

### Implementation for User Story 4

- [ ] T088 [P] [US4] Add role validation to UserController.updateUserRoles() in src/backendng/src/main/kotlin/com/secman/controller/UserController.kt
- [ ] T089 [P] [US4] Verify RISK, REQ, SECCHAMPION appear in user management UI role selector (no code changes needed - enum auto-populates)
- [ ] T090 [US4] Run all US4 tests (T079-T087) - all must pass

**Checkpoint**: User Story 4 complete ✅ - Role assignment working, validation enforced, immediate effect verified

---

## Phase 7: User Story 5 - Navigation Menu Visibility Based on Roles (Priority: P3)

**Goal**: Users only see navigation menu items they have permission to access (UX enhancement)

**Independent Test**: Login with each role combination, verify navigation shows/hides appropriate items

### Tests for User Story 5 (TDD - Write FIRST) ⚠️

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T091 [P] [US5] Unit test: Sidebar renders Risk Management for RISK role in src/frontend/tests/unit/Sidebar.test.tsx
- [ ] T092 [P] [US5] Unit test: Sidebar hides Requirements for RISK role in src/frontend/tests/unit/Sidebar.test.tsx
- [ ] T093 [P] [US5] Unit test: Sidebar renders Requirements for REQ role in src/frontend/tests/unit/Sidebar.test.tsx
- [ ] T094 [P] [US5] Unit test: Sidebar hides Risk Management for REQ role in src/frontend/tests/unit/Sidebar.test.tsx
- [ ] T095 [P] [US5] Unit test: Sidebar renders Risk, Req, Vuln for SECCHAMPION in src/frontend/tests/unit/Sidebar.test.tsx
- [ ] T096 [P] [US5] Unit test: Sidebar hides Admin for SECCHAMPION in src/frontend/tests/unit/Sidebar.test.tsx
- [ ] T097 [P] [US5] Unit test: Sidebar renders all items for ADMIN in src/frontend/tests/unit/Sidebar.test.tsx
- [ ] T098 [P] [US5] E2E test: Multi-role user (RISK + REQ) sees both sections in src/frontend/tests/e2e/multi-role-navigation.spec.ts

### Implementation for User Story 5

- [ ] T099 [US5] Final verification: No additional implementation needed (already completed in US1-US3 Sidebar.tsx updates)
- [ ] T100 [US5] Run all US5 tests (T091-T098) - all must pass

**Checkpoint**: User Story 5 complete ✅ - Navigation correctly shows/hides items based on roles

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, final validation, and production readiness

- [ ] T101 [P] Update README.md with role permission matrix at repository root
- [ ] T102 [P] Update README.md with role descriptions and access control documentation
- [ ] T103 [P] Update README.md with migration instructions for CHAMPION → SECCHAMPION rename
- [ ] T104 [P] Add error handling documentation for 403 Forbidden responses
- [ ] T105 Verify backward compatibility: All existing roles (USER, ADMIN, VULN, RELEASE_MANAGER) unchanged
- [ ] T106 Run full backend test suite: ./gradlew test
- [ ] T107 Run full frontend E2E test suite: npm test
- [ ] T108 Manual test: Access denial logging with log aggregation query (Splunk/ELK)
- [ ] T109 Performance test: Verify role check overhead <50ms per request
- [ ] T110 Security review: Verify no role requirements leaked in error messages
- [ ] T111 Final migration test on staging database
- [ ] T112 Code review checklist verification

**Checkpoint**: Feature complete ✅ - Ready for production deployment

---

## Dependencies & Execution Order

### Critical Path (Must Complete in Sequence)

1. **Phase 1 (Setup)**: T001-T004 → Database migration and logging infrastructure
2. **Phase 2 (Foundation)**: T005-T020 → User entity updates (BLOCKS all user stories)
3. **User Stories (Can run in parallel)**: After Phase 2 complete:
   - Phase 3 (US1): T021-T038
   - Phase 4 (US2): T039-T057
   - Phase 5 (US3): T058-T078
   - Phase 6 (US4): T079-T090
   - Phase 7 (US5): T091-T100
4. **Phase 8 (Polish)**: T101-T112 → After all user stories complete

### Parallel Execution Opportunities

**After Phase 2 Foundation Complete, these can run in parallel:**

- **Team A**: User Story 1 (RISK role) - Tasks T021-T038
- **Team B**: User Story 2 (REQ role) - Tasks T039-T057
- **Team C**: User Story 3 (SECCHAMPION role) - Tasks T058-T078
- **Team D**: User Story 4 (Role assignment) - Tasks T079-T090
- **Team E**: User Story 5 (Navigation) - Tasks T091-T100

**Within each user story, these tasks can run in parallel:**
- Test writing tasks (all [P] marked tests)
- Implementation tasks for different files ([P] marked implementations)

**Example: User Story 1 Parallel Execution**
- Tests T021-T030 (10 tasks) can all be written in parallel by different developers
- Implementations T031, T032, T033, T037 can run in parallel (different files)
- T034-T036 must run sequentially (same file - Sidebar.tsx)

---

## Independent Test Criteria by User Story

### User Story 1 (RISK Role)
**Test**: Create test user with ONLY RISK role → Login → Access /api/risk-assessments (200 OK) → Access /api/requirements (403 Forbidden with generic message) → Verify access denial logged

### User Story 2 (REQ Role)
**Test**: Create test user with ONLY REQ role → Login → Access /api/requirements (200 OK) → Access /api/risk-assessments (403 Forbidden with generic message) → Verify access denial logged

### User Story 3 (SECCHAMPION Role)
**Test**: Create test user with ONLY SECCHAMPION role → Login → Access /api/risk-assessments, /api/requirements, /api/vulnerabilities (all 200 OK) → Access /api/admin (403 Forbidden) → Verify access denial logged

### User Story 4 (Role Assignment)
**Test**: Login as ADMIN → Assign RISK role to test user → Verify user can access /api/risk-assessments on next request (within 30 seconds) → Remove RISK role → Verify user loses access on next request

### User Story 5 (Navigation)
**Test**: Login with each role → Verify navigation items match permission matrix → Login with multi-role user (RISK + REQ) → Verify both sections visible

---

## Implementation Strategy

### MVP Scope (Minimum Viable Product)
- **Phase 1**: Setup (T001-T004)
- **Phase 2**: Foundation (T005-T020)
- **Phase 3**: User Story 1 - RISK role (T021-T038)
- **Phase 4**: User Story 2 - REQ role (T039-T057)

**MVP Deliverable**: RISK and REQ roles fully functional with access control, audit logging, and frontend navigation

### Full Feature Scope
- **MVP** + Phase 5 (SECCHAMPION), Phase 6 (Role Assignment UI), Phase 7 (Navigation Polish), Phase 8 (Documentation)

### Estimated Timeline
- **Phase 1**: 30 minutes (4 tasks)
- **Phase 2**: 90 minutes (16 tasks - TDD foundation)
- **Phase 3 (US1)**: 120 minutes (18 tasks - backend + frontend + E2E)
- **Phase 4 (US2)**: 120 minutes (19 tasks - similar to US1)
- **Phase 5 (US3)**: 120 minutes (21 tasks - broader permissions)
- **Phase 6 (US4)**: 60 minutes (12 tasks - role assignment)
- **Phase 7 (US5)**: 45 minutes (10 tasks - navigation polish)
- **Phase 8**: 90 minutes (12 tasks - documentation + validation)

**Total**: ~11 hours (MVP: ~6 hours for US1 + US2)

---

## Format Validation ✅

**All tasks follow required format**:
- ✅ Checkbox prefix `- [ ]`
- ✅ Sequential Task IDs (T001-T112)
- ✅ [P] marker for parallelizable tasks
- ✅ [Story] label for user story tasks (US1-US5)
- ✅ File paths in descriptions
- ✅ Clear action verbs

**Total Tasks**: 112
- Setup: 4 tasks
- Foundation: 16 tasks (TDD tests + implementation)
- User Story 1: 18 tasks (10 tests + 8 implementations)
- User Story 2: 19 tasks (10 tests + 9 implementations)
- User Story 3: 21 tasks (11 tests + 10 implementations)
- User Story 4: 12 tasks (9 tests + 3 implementations)
- User Story 5: 10 tasks (8 tests + 2 implementations)
- Polish: 12 tasks

**TDD Compliance**: 58 tests written FIRST before 54 implementation tasks ✅

---

**Next**: Begin Phase 1 (Setup) → Follow Red-Green-Refactor cycle throughout
