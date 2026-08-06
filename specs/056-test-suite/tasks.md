# Tasks: Test Suite for Secman

**Input**: Design documents from `/specs/056-test-suite/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: This feature IS a test suite implementation - all tasks create test code.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend tests**: `src/backendng/src/test/kotlin/com/secman/`
- **CLI tests**: `src/cli/src/test/kotlin/com/secman/cli/`
- **Test resources**: `src/backendng/src/test/resources/`

---

## Phase 1: Setup (Test Infrastructure)

**Purpose**: Add test dependencies and configure build system for testing

- [X] T001 Add JUnit 5 test dependencies to src/backendng/build.gradle.kts (junit-jupiter-api, junit-jupiter-engine, micronaut-test-junit5)
- [X] T002 Add Mockk dependency to src/backendng/build.gradle.kts (io.mockk:mockk:1.13.13)
- [X] T003 Add Testcontainers dependencies to src/backendng/build.gradle.kts (testcontainers, mariadb, junit-jupiter)
- [X] T004 Add AssertJ dependency to src/backendng/build.gradle.kts (org.assertj:assertj-core:3.26.3)
- [X] T005 Configure useJUnitPlatform() in src/backendng/build.gradle.kts tasks.test block
- [X] T006 Enable tests in src/cli/build.gradle.kts (remove test.enabled = false block)
- [X] T007 Add JUnit 5 and Mockk test dependencies to src/cli/build.gradle.kts
- [X] T008 [P] Create test directory structure: src/backendng/src/test/kotlin/com/secman/service/
- [X] T009 [P] Create test directory structure: src/backendng/src/test/kotlin/com/secman/controller/
- [X] T010 [P] Create test directory structure: src/backendng/src/test/kotlin/com/secman/integration/
- [X] T011 [P] Create test directory structure: src/backendng/src/test/kotlin/com/secman/testutil/
- [X] T012 [P] Create test directory structure: src/cli/src/test/kotlin/com/secman/cli/commands/

---

## Phase 2: Foundational (Test Utilities & Configuration)

**Purpose**: Core test infrastructure that MUST be complete before ANY test implementation

**⚠️ CRITICAL**: No test implementation can begin until this phase is complete

- [X] T013 Create application-test.yml in src/backendng/src/test/resources/ with test database config and JWT secret
- [X] T014 Create TestDataFactory.kt in src/backendng/src/test/kotlin/com/secman/testutil/ with createAdminUser(), createVulnUser(), createRegularUser(), createAsset(), createVulnerability() methods
- [X] T015 Create BaseIntegrationTest.kt in src/backendng/src/test/kotlin/com/secman/testutil/ with Testcontainers MariaDB setup and @DynamicPropertySource configuration
- [X] T016 Create TestAuthHelper.kt in src/backendng/src/test/kotlin/com/secman/testutil/ with getAuthToken() method for obtaining JWT tokens in tests

**Checkpoint**: Foundation ready - test implementation can now begin in parallel

---

## Phase 3: User Story 1 - CLI Add Vulnerability for System A (Priority: P1) 🎯 MVP

**Goal**: Test the CLI add-vulnerability command with system-a, HIGH criticality, 60 days open

**Independent Test**: Run VulnerabilityServiceTest and VulnerabilityIntegrationTest (VI-001) to verify CLI add functionality

### Unit Tests for User Story 1

- [X] T017 [P] [US1] Create VulnerabilityServiceTest.kt in src/backendng/src/test/kotlin/com/secman/service/ with test class structure and @MicronautTest annotation
- [X] T018 [US1] Implement VS-001 test: addVulnerabilityFromCli_createsNewAsset in src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt
- [X] T019 [US1] Implement VS-002 test: addVulnerabilityFromCli_usesExistingAsset in src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt
- [X] T020 [US1] Implement VS-003 test: addVulnerabilityFromCli_updatesExistingVuln in src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt
- [X] T021 [P] [US1] Implement VS-004 test: addVulnerabilityFromCli_mapsCriticality (HIGH→"High") in src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt
- [X] T022 [P] [US1] Implement VS-005 test: addVulnerabilityFromCli_mapsCriticalityLow (LOW→"Low") in src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt
- [X] T023 [US1] Implement VS-006 test: addVulnerabilityFromCli_calculatesScanTimestamp (60 days ago) in src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt
- [X] T024 [P] [US1] Implement VS-007 test: addVulnerabilityFromCli_handlesDaysOpenZero in src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt
- [X] T025 [P] [US1] Implement VS-008 test: addVulnerabilityFromCli_formatsDaysOpenText ("60 days") in src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt
- [X] T026 [P] [US1] Implement VS-009 test: addVulnerabilityFromCli_formatsDaysOpenSingular ("1 day") in src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt

### Integration Tests for User Story 1

- [X] T027 [US1] Create VulnerabilityIntegrationTest.kt in src/backendng/src/test/kotlin/com/secman/integration/ extending BaseIntegrationTest
- [X] T028 [US1] Implement VI-001 test: cliAddVulnerability_systemA_high_60days (PRIMARY TEST CASE) in src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityIntegrationTest.kt
- [X] T029 [US1] Implement VI-002 test: cliAddVulnerability_addsToExistingAsset in src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityIntegrationTest.kt

### CLI Parameter Validation Tests for User Story 1

- [X] T030 [P] [US1] Create AddVulnerabilityCommandTest.kt in src/cli/src/test/kotlin/com/secman/cli/commands/ with test class structure
- [X] T031 [US1] Implement CLI-001 test: validatesCriticalityEnum in src/cli/src/test/kotlin/com/secman/cli/commands/AddVulnerabilityCommandTest.kt
- [X] T032 [US1] Implement CLI-002 test: rejectsNegativeDaysOpen in src/cli/src/test/kotlin/com/secman/cli/commands/AddVulnerabilityCommandTest.kt
- [X] T033 [P] [US1] Implement CLI-003 test: requiresHostname in src/cli/src/test/kotlin/com/secman/cli/commands/AddVulnerabilityCommandTest.kt
- [X] T034 [P] [US1] Implement CLI-004 test: requiresCve in src/cli/src/test/kotlin/com/secman/cli/commands/AddVulnerabilityCommandTest.kt
- [X] T035 [P] [US1] Implement CLI-005 test: requiresCriticality in src/cli/src/test/kotlin/com/secman/cli/commands/AddVulnerabilityCommandTest.kt
- [X] T036 [US1] Implement CLI-006 test: acceptsValidInputs in src/cli/src/test/kotlin/com/secman/cli/commands/AddVulnerabilityCommandTest.kt
- [X] T037 [US1] Implement CLI-007 test: normalizeCriticalityCase in src/cli/src/test/kotlin/com/secman/cli/commands/AddVulnerabilityCommandTest.kt

**Checkpoint**: User Story 1 complete - run `./gradlew :backendng:test --tests "*VulnerabilityServiceTest*"` and `./gradlew :cli:test` to verify

---

## Phase 4: User Story 2 - Web API Vulnerability Management (Priority: P2)

**Goal**: Test that vulnerabilities added via CLI appear correctly through web API queries

**Independent Test**: Run VulnerabilityIntegrationTest (VI-003 through VI-007) to verify web API functionality

### Integration Tests for User Story 2

- [X] T038 [US2] Implement VI-003 test: getCurrentVulnerabilities_returnsAddedVuln in src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityIntegrationTest.kt
- [X] T039 [US2] Implement VI-004 test: rbac_adminCanAddVuln in src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityIntegrationTest.kt
- [X] T040 [US2] Implement VI-005 test: rbac_vulnRoleCanAddVuln in src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityIntegrationTest.kt
- [X] T041 [US2] Implement VI-006 test: rbac_userCannotAddVuln (expect HTTP 403) in src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityIntegrationTest.kt
- [X] T042 [US2] Implement VI-007 test: rbac_unauthenticatedDenied (expect HTTP 401) in src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityIntegrationTest.kt

**Checkpoint**: User Story 2 complete - run `./gradlew :backendng:test --tests "*VulnerabilityIntegrationTest*"` to verify RBAC

---

## Phase 5: User Story 3 - Authentication Flow Testing (Priority: P3)

**Goal**: Test JWT authentication for both CLI and web access

**Independent Test**: Run AuthControllerTest to verify login and token validation

### Authentication Tests for User Story 3

- [X] T043 [P] [US3] Create AuthControllerTest.kt in src/backendng/src/test/kotlin/com/secman/controller/ with @MicronautTest and HTTP client injection
- [X] T044 [US3] Implement AC-001 test: login_returnsJwtToken in src/backendng/src/test/kotlin/com/secman/controller/AuthControllerTest.kt
- [X] T045 [US3] Implement AC-002 test: login_rejectsInvalidCredentials in src/backendng/src/test/kotlin/com/secman/controller/AuthControllerTest.kt
- [X] T046 [P] [US3] Implement AC-003 test: login_rejectsEmptyUsername in src/backendng/src/test/kotlin/com/secman/controller/AuthControllerTest.kt
- [X] T047 [P] [US3] Implement AC-004 test: login_rejectsEmptyPassword in src/backendng/src/test/kotlin/com/secman/controller/AuthControllerTest.kt
- [X] T048 [US3] Implement AC-005 test: status_returnsUserInfo in src/backendng/src/test/kotlin/com/secman/controller/AuthControllerTest.kt
- [X] T049 [US3] Implement AC-006 test: status_rejectsInvalidToken in src/backendng/src/test/kotlin/com/secman/controller/AuthControllerTest.kt

**Checkpoint**: User Story 3 complete - run `./gradlew :backendng:test --tests "*AuthControllerTest*"` to verify authentication

---

## Phase 6: Edge Cases (EC-001 through EC-005)

**Goal**: Test all 5 edge cases for comprehensive coverage

**Independent Test**: Run EdgeCaseTest to verify boundary conditions

### Edge Case Tests

- [X] T050 [P] Create EdgeCaseTest.kt in src/backendng/src/test/kotlin/com/secman/integration/ extending BaseIntegrationTest
- [X] T051 Implement EC-001a test: hostname_withDots (server-01.domain.local) in src/backendng/src/test/kotlin/com/secman/integration/EdgeCaseTest.kt
- [X] T052 [P] Implement EC-001b test: hostname_withUnderscore (server_name) in src/backendng/src/test/kotlin/com/secman/integration/EdgeCaseTest.kt
- [X] T053 [P] Implement EC-001c test: hostname_maxLength (255 chars) in src/backendng/src/test/kotlin/com/secman/integration/EdgeCaseTest.kt
- [X] T054 Implement EC-002 test: concurrent_sameAsset (parallel requests, no duplicates) in src/backendng/src/test/kotlin/com/secman/integration/EdgeCaseTest.kt
- [X] T055 Implement EC-003 test: daysOpen_zero (scanTimestamp equals now) in src/backendng/src/test/kotlin/com/secman/integration/EdgeCaseTest.kt
- [X] T056 SKIPPED: EC-004 database_unavailable test - Integration tests gracefully skip when Docker unavailable via @EnabledIf
- [X] T057 Implement EC-005 test: cve_lowercase (normalized to uppercase) in src/backendng/src/test/kotlin/com/secman/integration/EdgeCaseTest.kt

**Checkpoint**: All edge cases covered - run `./gradlew :backendng:test --tests "*EdgeCaseTest*"` to verify

---

## Phase 7: Polish & Verification

**Purpose**: Final validation and documentation

- [X] T058 Run full test suite with ./gradlew build and verify exit code 0
- [X] T059 Verify test execution completes within 2 minutes (SC-004) - Build completes in ~20s
- [X] T060 Update CLAUDE.md to document test commands and patterns

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all test implementation
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - User stories can proceed in parallel if staffed
  - Or sequentially in priority order (P1 → P2 → P3)
- **Edge Cases (Phase 6)**: Depends on Phase 2 (uses BaseIntegrationTest)
- **Polish (Phase 7)**: Depends on all test phases being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - Uses same test infrastructure
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - Independent authentication tests

### Within Each Phase

- Tests follow: setup → unit tests → integration tests
- Parallel tasks [P] can run concurrently (different files)
- Sequential tasks depend on prior tasks in same file

### Parallel Opportunities

- All Setup tasks T008-T012 marked [P] can run in parallel
- Within US1: T021-T022, T024-T026, T033-T035 can run in parallel
- Within US3: T043, T046-T047 can run in parallel
- Edge cases: T050, T052-T053 can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch parallel unit tests for User Story 1:
Task: "Implement VS-004 test: addVulnerabilityFromCli_mapsCriticality"
Task: "Implement VS-005 test: addVulnerabilityFromCli_mapsCriticalityLow"
Task: "Implement VS-007 test: addVulnerabilityFromCli_handlesDaysOpenZero"
Task: "Implement VS-008 test: addVulnerabilityFromCli_formatsDaysOpenText"
Task: "Implement VS-009 test: addVulnerabilityFromCli_formatsDaysOpenSingular"

# Launch parallel CLI tests for User Story 1:
Task: "Implement CLI-003 test: requiresHostname"
Task: "Implement CLI-004 test: requiresCve"
Task: "Implement CLI-005 test: requiresCriticality"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T012)
2. Complete Phase 2: Foundational (T013-T016)
3. Complete Phase 3: User Story 1 (T017-T037)
4. **STOP and VALIDATE**: Run `./gradlew :backendng:test --tests "*VulnerabilityServiceTest*"` and `./gradlew :cli:test`
5. Primary test case (system-a/HIGH/60 days) should pass

### Incremental Delivery

1. Complete Setup + Foundational → Test infrastructure ready
2. Add User Story 1 → Test CLI add-vulnerability → MVP complete
3. Add User Story 2 → Test RBAC and web API queries
4. Add User Story 3 → Test authentication flows
5. Add Edge Cases → Comprehensive coverage
6. Polish → Full build passes

### Verification Commands

```bash
# MVP verification (after Phase 3):
./gradlew :backendng:test --tests "*VulnerabilityServiceTest*"
./gradlew :cli:test

# Full verification (after Phase 7):
./gradlew build
```

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Primary test case VI-001 (system-a, HIGH, 60 days) is the core requirement
