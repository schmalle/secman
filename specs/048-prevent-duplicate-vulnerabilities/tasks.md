# Tasks: Prevent Duplicate Vulnerabilities in CrowdStrike Import

**Input**: Design documents from `/specs/048-prevent-duplicate-vulnerabilities/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Tests are REQUIRED for this feature per Constitution Principle II (TDD) and Principle IV (User-Requested Testing). User explicitly requested duplicate prevention verification, which requires comprehensive test coverage.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

This is a web application with backend + frontend + CLI structure:
- Backend: `src/backendng/src/main/kotlin/com/secman/`
- Backend Tests: `src/backendng/src/test/kotlin/com/secman/`
- CLI: `src/cli/src/main/kotlin/com/secman/cli/`
- Documentation: `docs/`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Prepare test infrastructure and documentation structure

- [x] T001 [P] Create test infrastructure setup for CrowdStrikeVulnerabilityImportServiceTest in src/backendng/src/test/kotlin/com/secman/service/
- [x] T002 [P] Create documentation file structure at docs/CROWDSTRIKE_IMPORT.md
- [x] T003 [P] Review existing implementation at src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt to understand transactional replace pattern

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core test utilities and helper methods that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Create test data factory for CrowdStrikeVulnerabilityBatchDto in src/backendng/src/test/kotlin/com/secman/fixtures/CrowdStrikeTestDataFactory.kt
- [x] T005 [P] Create helper method to count vulnerabilities by asset in src/backendng/src/test/kotlin/com/secman/fixtures/VulnerabilityTestHelpers.kt
- [x] T006 [P] Setup H2 in-memory database configuration for integration tests in src/backendng/src/test/resources/application-test.yml
- [x] T007 Setup Micronaut Test annotations and transaction management in test base class at src/backendng/src/test/kotlin/com/secman/service/BaseIntegrationTest.kt

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Prevent Duplicate Vulnerability Entries (Priority: P1) 🎯 MVP

**Goal**: Verify that the transactional replace pattern prevents duplicate vulnerability records when the same import runs multiple times

**Independent Test**: Run import twice with same vulnerability data and verify database contains same count (not doubled)

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before documentation**

- [ ] T008 [P] [US1] Create test file CrowdStrikeVulnerabilityImportServiceTest.kt at src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportServiceTest.kt with @MicronautTest and @Rollback annotations
- [ ] T009 [P] [US1] Write test `importing same vulnerabilities twice should not create duplicates` verifying idempotent import behavior (SC-001)
- [ ] T010 [P] [US1] Write test `importing vulnerabilities for first time should create all records` verifying initial import behavior (SC-001)
- [ ] T011 [P] [US1] Write test `importing fewer vulnerabilities should remove remediated ones` verifying remediation handling (SC-002)
- [ ] T012 [P] [US1] Write test `two different assets with same CVE should maintain separate records` verifying per-asset duplicate prevention (FR-003)

### Documentation for User Story 1

- [ ] T013 [US1] Add KDoc comments to importVulnerabilitiesForServer method in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt explaining duplicate prevention strategy
- [ ] T014 [US1] Create "Duplicate Prevention Mechanism" section in docs/CROWDSTRIKE_IMPORT.md explaining transactional replace pattern
- [ ] T015 [US1] Create "Why Transactional Replace" section in docs/CROWDSTRIKE_IMPORT.md comparing alternatives (upsert, soft delete, differential)

**Checkpoint**: At this point, duplicate prevention behavior is fully verified and documented

---

## Phase 4: User Story 2 - Import Performance with Large Datasets (Priority: P2)

**Goal**: Verify that duplicate prevention mechanism scales to handle enterprise-sized datasets (1000 assets, 10000 vulnerabilities) within 5 minutes

**Independent Test**: Import large dataset and measure completion time

### Tests for User Story 2

- [ ] T016 [US2] Write performance test `large dataset import should complete within time limit` in src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportServicePerformanceTest.kt testing 1000 assets with 10 vulnerabilities each (SC-003)
- [ ] T017 [US2] Write test `repeated imports should not degrade performance` verifying consistent execution time across multiple runs (SC-003)
- [ ] T018 [US2] Write test `concurrent imports for different assets should not cause deadlocks` using parallel execution with 10 threads importing different assets (SC-005)

### Documentation for User Story 2

- [ ] T019 [US2] Add "Performance Characteristics" section to docs/CROWDSTRIKE_IMPORT.md documenting expected timings and bottlenecks
- [ ] T020 [US2] Add performance benchmarks and optimization notes to docs/CROWDSTRIKE_IMPORT.md

**Checkpoint**: Performance characteristics verified and documented

---

## Phase 5: User Story 3 - Idempotent Import Operations (Priority: P2)

**Goal**: Verify system produces identical results regardless of how many times import runs, including proper transaction rollback on failures

**Independent Test**: Run same import 5 times and verify database state identical after each run

### Tests for User Story 3

- [ ] T021 [P] [US3] Write test `importing same data multiple times produces identical database state` running import 5 times consecutively and comparing final state (SC-004)
- [ ] T022 [P] [US3] Write test `import statistics should be consistent across repeated imports` verifying ImportStatisticsDto values match (SC-004)
- [ ] T023 [US3] Write test `failed import should rollback and preserve original data` simulating constraint violation during insert and verifying rollback (SC-006)
- [ ] T024 [US3] Write test `partial import failure should not leave corrupt data` testing transaction atomicity with multiple servers where one fails (SC-006)

### Documentation for User Story 3

- [ ] T025 [US3] Add "Idempotency Guarantees" section to docs/CROWDSTRIKE_IMPORT.md explaining same input → same output behavior
- [ ] T026 [US3] Add "Transaction Rollback Behavior" section to docs/CROWDSTRIKE_IMPORT.md explaining atomicity and failure handling
- [ ] T027 [US3] Add "Edge Case Handling" section to docs/CROWDSTRIKE_IMPORT.md documenting concurrent imports, same hostname in batch, etc.

**Checkpoint**: Idempotency and transaction behavior fully verified and documented

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final improvements and validation

- [ ] T028 [P] Add README section referencing docs/CROWDSTRIKE_IMPORT.md for import behavior documentation in README.md
- [ ] T029 [P] Review and update code comments for clarity in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt
- [ ] T030 Run full test suite to verify all tests pass with `./gradlew test`
- [ ] T031 Generate test coverage report with `./gradlew test jacocoTestReport` and verify ≥80% coverage for CrowdStrikeVulnerabilityImportService
- [ ] T032 Run quickstart.md manual verification scenarios to validate end-to-end behavior
- [ ] T033 [P] Update CLAUDE.md Active Technologies section if needed (already done by update-agent-context.sh)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - User stories can proceed in parallel (if staffed)
  - Or sequentially in priority order (US1 → US2 → US3)
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - Independent of US1 (different test scenarios)
- **User Story 3 (P2)**: Can start after Foundational (Phase 2) - Independent of US1 and US2 (different test scenarios)

**Note**: All three user stories test the SAME existing implementation from different angles, so they are completely independent.

### Within Each User Story

- Tests MUST be written and FAIL before documentation (TDD Red-Green-Refactor)
- Tests marked [P] can run in parallel (different test methods)
- Documentation tasks can run after tests pass

### Parallel Opportunities

**Setup Phase (Phase 1)**:
- T001, T002, T003 can all run in parallel (different files)

**Foundational Phase (Phase 2)**:
- T005 and T006 can run in parallel after T004 completes

**User Story 1 (Phase 3)**:
- T008, T009, T010, T011, T012 can all run in parallel (different test methods in same file)
- T013 (code comments) is independent and can run in parallel with docs
- T014, T015 (docs) can run sequentially

**User Story 2 (Phase 4)**:
- T016, T017, T018 can run sequentially (performance tests need isolation)

**User Story 3 (Phase 5)**:
- T021, T022, T023, T024 can all run in parallel (different test methods)
- T025, T026, T027 can run sequentially (building up docs)

**Polish Phase (Phase 6)**:
- T028, T029, T033 can run in parallel (different files)
- T030, T031, T032 run sequentially

**Cross-Story Parallelism**:
Once Foundational phase completes, all three user stories can be worked on in parallel by different team members.

---

## Parallel Example: User Story 1

```bash
# Launch all test tasks for User Story 1 together:
Task: "Create test file CrowdStrikeVulnerabilityImportServiceTest.kt"
Task: "Write test importing same vulnerabilities twice should not create duplicates"
Task: "Write test importing vulnerabilities for first time should create all records"
Task: "Write test importing fewer vulnerabilities should remove remediated ones"
Task: "Write test two different assets with same CVE should maintain separate records"

# After tests pass, launch documentation tasks:
Task: "Add KDoc comments to importVulnerabilitiesForServer method"
# Then:
Task: "Create Duplicate Prevention Mechanism section in docs"
Task: "Create Why Transactional Replace section in docs"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Run tests, verify they all pass
5. Review documentation, confirm duplicate prevention is explained
6. **MVP COMPLETE**: Core duplicate prevention verified and documented

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → **MVP delivered** (duplicate prevention verified)
3. Add User Story 2 → Test independently → Performance characteristics verified
4. Add User Story 3 → Test independently → Idempotency guarantees verified
5. Complete Polish → Final validation
6. Each story adds verification coverage without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (duplicate prevention tests + docs)
   - Developer B: User Story 2 (performance tests + docs)
   - Developer C: User Story 3 (idempotency tests + docs)
3. Stories complete independently, all testing same implementation
4. Combine documentation sections

---

## Test Execution

### Run All Tests

```bash
cd src/backendng
./gradlew test --tests "CrowdStrikeVulnerabilityImportServiceTest"
./gradlew test --tests "CrowdStrikeVulnerabilityImportServicePerformanceTest"
```

### Run with Coverage

```bash
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

### Expected Test Results

- **User Story 1 Tests**: 4 tests, all passing
  - ✅ Idempotent import (no duplicates)
  - ✅ Initial import creates records
  - ✅ Remediation removes old vulnerabilities
  - ✅ Per-asset duplicate prevention

- **User Story 2 Tests**: 3 tests, all passing
  - ✅ Large dataset < 5 minutes
  - ✅ No performance degradation
  - ✅ No deadlocks on concurrent imports

- **User Story 3 Tests**: 4 tests, all passing
  - ✅ Multiple runs produce identical state
  - ✅ Import statistics consistent
  - ✅ Transaction rollback works
  - ✅ Partial failure doesn't corrupt data

**Total**: 11 integration tests verifying duplicate prevention from all angles

---

## Success Metrics

| Metric | Target | Verification |
|--------|--------|--------------|
| Test Coverage | ≥80% for CrowdStrikeVulnerabilityImportService | JaCoCo report (T031) |
| All Tests Pass | 100% (11/11 tests) | Test execution (T030) |
| Documentation Complete | All sections in docs/CROWDSTRIKE_IMPORT.md | Manual review |
| Code Comments Added | KDoc on importVulnerabilitiesForServer | Code review (T013) |
| Manual Validation | All quickstart.md scenarios work | Manual testing (T032) |
| Performance | 1000 assets in < 5 min | Performance test (T016) |

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story tests the SAME implementation from different perspectives
- All stories are independent and can be completed in parallel
- Tests are REQUIRED per Constitution (not optional for this feature)
- Verify tests fail before documenting (Red-Green-Refactor)
- No implementation changes needed - tests verify existing code
- Documentation explains the "why" and "how" of transactional replace
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
