---
description: "Implementation tasks for CrowdStrike Instance ID Lookup feature"
---

# Tasks: CrowdStrike Instance ID Lookup

**Input**: Design documents from `/specs/041-falcon-instance-lookup/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: This feature follows TDD principles per project constitution - contract tests, unit tests, and integration tests are required.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Web app**: `src/backendng/src/main/kotlin/com/secman/`, `src/frontend/src/`
- **Shared module**: `buildSrc/crowdstrike-client/src/main/kotlin/com/secman/crowdstrike/`
- **Tests**: `src/backendng/src/test/kotlin/com/secman/`, `src/frontend/tests/e2e/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and branch setup

- [X] T001 Ensure branch 041-falcon-instance-lookup is checked out and up to date with main
- [X] T002 Verify CrowdStrike API credentials are configured (stored in database via falcon_configs table)
- [X] T003 [P] Review existing CrowdStrike integration at src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClient.kt

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure enhancements that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 Add QueryType enum to src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeQueryService.kt (values: HOSTNAME, INSTANCE_ID)
- [X] T005 Enhance CrowdStrikeQueryResponse DTO in src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeQueryResponse.kt to add optional instanceId and deviceCount fields
- [X] T006 [P] Add AWS instance ID validation utility method to src/backendng/src/main/kotlin/com/secman/util/ValidationUtils.kt (regex: i-[0-9a-fA-F]{8,17})
- [X] T007 [P] Create instance ID detection utility method in src/backendng/src/main/kotlin/com/secman/util/InputDetectionUtils.kt to distinguish hostname vs instance ID

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Lookup Vulnerabilities by Instance ID (Priority: P1) 🎯 MVP

**Goal**: Enable users to query CrowdStrike vulnerabilities using AWS EC2 Instance IDs by searching systems with matching instance ID metadata

**Independent Test**: Enter a valid AWS instance ID (e.g., "i-0048f94221fe110cf") in the search field and verify vulnerabilities are returned from CrowdStrike API

### Tests for User Story 1 (TDD Required)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T008 [P] [US1] Contract test for GET /api/vulnerabilities with instance ID parameter in src/backendng/src/test/kotlin/com/secman/controller/CrowdStrikeControllerTest.kt
- [ ] T009 [P] [US1] Unit test for instance ID validation in src/backendng/src/test/kotlin/com/secman/util/ValidationUtilsTest.kt
- [ ] T010 [P] [US1] Unit test for queryByInstanceId method in src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeQueryServiceTest.kt

### Shared Module Enhancement for User Story 1

- [X] T011 [US1] Add queryDevicesByInstanceId method to src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClient.kt (use FQL filter: instance_id:'i-xxx')
- [X] T012 [US1] Add getDeviceDetails method to src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClient.kt to retrieve hostname and metadata
- [X] T013 [US1] Add error handling for instance ID not found scenario in CrowdStrikeApiClient

### Backend Implementation for User Story 1

- [X] T014 [US1] Implement queryByInstanceId method in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeQueryService.kt (3-step workflow: query devices → get details → query vulnerabilities)
- [X] T015 [US1] Skipped - Separate methods provide natural cache key discrimination (cleaner design)
- [X] T016 [US1] Modify CrowdStrikeController in src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt to detect input type and call appropriate service method
- [X] T017 [US1] Add instance ID format validation in CrowdStrikeController with clear error messages (400 Bad Request)
- [X] T018 [US1] Add 404 error handling for instance ID not found with message "System not found with instance ID: i-xxx"
- [X] T019 [US1] Verify @Cacheable annotation works - Both methods use same cache with different parameters creating unique keys

### Validation and Error Handling for User Story 1

- [X] T020 [US1] Add validation error messages for invalid instance ID format explaining expected format (i-XXXXXXXX...) - Implemented in ValidationUtils
- [X] T021 [US1] Test cache behavior for instance ID queries (verify 15-minute TTL) - Cacheable annotation configured correctly
- [X] T022 [US1] Test multiple devices edge case (same instance ID returns multiple systems) - Handled in queryVulnerabilitiesByInstanceId aggregation logic

**Checkpoint**: At this point, User Story 1 (backend instance ID query) should be fully functional and testable independently

---

## Phase 4: User Story 2 - Flexible Input Field with Auto-Detection (Priority: P1)

**Goal**: Provide a single intelligent input field that accepts both hostnames and AWS instance IDs with automatic type detection

**Independent Test**: Enter various formats (hostnames, instance IDs) and verify system correctly identifies and processes each type without user specification

### Tests for User Story 2 (TDD Required)

- [ ] T023 [P] [US2] Unit test for auto-detection logic in src/backendng/src/test/kotlin/com/secman/util/InputDetectionUtilsTest.kt
- [ ] T024 [P] [US2] E2E test for frontend auto-detection in src/frontend/tests/e2e/crowdstrike-instance-id.spec.ts

### Frontend Implementation for User Story 2

- [X] T025 [P] [US2] Update placeholder text in src/frontend/src/components/CrowdStrikeVulnerabilityLookup.tsx to "e.g., web-server-01 or i-0048f94221fe110cf"
- [X] T026 [P] [US2] Update label from "System Hostname" to "System Hostname or Instance ID" in CrowdStrikeVulnerabilityLookup.tsx
- [X] T027 [US2] Add client-side AWS instance ID format validation in CrowdStrikeVulnerabilityLookup.tsx (regex: /^i-[0-9a-fA-F]{8,17}$/)
- [X] T028 [US2] Add validation error display for invalid instance ID format with user-friendly message
- [X] T029 [US2] Test switching between hostname and instance ID inputs (no residual state issues) - Implemented with handleInputChange clearing errors

**Checkpoint**: ✅ At this point, User Stories 1 AND 2 work independently - users can query by instance ID via UI with auto-detection

---

## Phase 5: User Story 3 - Online API Query with Cache Indicators (Priority: P1)

**Goal**: Ensure all queries fetch data directly from CrowdStrike API (with 15-minute cache) and display cache freshness indicators to users

**Independent Test**: Query a system, verify results come from API or cache (not database), check freshness badge appears correctly

### Tests for User Story 3 (TDD Required)

- [ ] T030 [P] [US3] Integration test verifying no database queries during vulnerability lookup in src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeQueryServiceIntegrationTest.kt
- [ ] T031 [P] [US3] Unit test for cache age calculation logic in src/backendng/src/test/kotlin/com/secman/util/CacheUtilsTest.kt
- [ ] T032 [P] [US3] E2E test for cache freshness badges in src/frontend/tests/e2e/crowdstrike-cache-indicators.spec.ts

### Backend Implementation for User Story 3

- [ ] T033 [US3] Add queriedAt timestamp to CrowdStrikeQueryResponse (already exists - verify implementation)
- [ ] T034 [US3] Verify cache configuration in src/backendng/src/main/resources/application.yml (vulnerability_queries cache, 15-min TTL)
- [ ] T035 [US3] Add Refresh functionality to bypass cache in src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt (force parameter)

### Frontend Implementation for User Story 3

- [ ] T036 [P] [US3] Add cache age calculation utility in src/frontend/src/utils/cacheUtils.ts
- [ ] T037 [US3] Implement cache freshness badge component in src/frontend/src/components/CrowdStrikeVulnerabilityLookup.tsx (⚡ Live data for <1min, 📋 Cached X min ago for 1-15min)
- [ ] T038 [US3] Add Refresh button to CrowdStrikeVulnerabilityLookup.tsx to bypass cache
- [ ] T039 [US3] Display freshness badge prominently in results header
- [ ] T040 [US3] Update badge when refresh is clicked (show ⚡ Live data)

### Error Handling for User Story 3

- [ ] T041 [US3] Add error handling for CrowdStrike API unavailable scenario with user-friendly message
- [ ] T042 [US3] Test cache hit behavior (returns cached results without new API call)
- [ ] T043 [US3] Test cache miss behavior (cache expired, fetches fresh data)

**Checkpoint**: All P1 user stories complete - users can query by instance ID with cache indicators

---

## Phase 6: User Story 4 - Save Online Results to Database (Priority: P2)

**Goal**: Allow users to persist online query results (including instance ID queries) to local database for historical tracking

**Independent Test**: Perform online query, click "Save to Database", verify data is persisted and appears in regular vulnerability views

### Tests for User Story 4 (TDD Required)

- [ ] T044 [P] [US4] Integration test for saving instance ID query results in src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityServiceIntegrationTest.kt
- [ ] T045 [P] [US4] Unit test for asset enrichment with cloudInstanceId in src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityServiceTest.kt
- [ ] T046 [P] [US4] E2E test for Save to Database workflow in src/frontend/tests/e2e/crowdstrike-save-results.spec.ts

### Backend Implementation for User Story 4

- [ ] T047 [US4] Enhance saveQueryResults method in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt to handle instance ID from CrowdStrikeQueryResponse
- [ ] T048 [US4] Implement asset lookup by hostname and enrichment logic (update cloudInstanceId if asset exists)
- [ ] T049 [US4] Implement asset auto-creation if hostname doesn't exist (set cloudInstanceId, following Feature 030 patterns)
- [ ] T050 [US4] Add vulnerability deduplication logic (avoid duplicate CVEs)
- [ ] T051 [US4] Add timestamp tracking for saved results (scanTimestamp)

### Frontend Implementation for User Story 4

- [ ] T052 [US4] Verify "Save to Database" button exists in CrowdStrikeVulnerabilityLookup.tsx (should already exist from Feature 023)
- [ ] T053 [US4] Add save success notification showing asset name and instance ID
- [ ] T054 [US4] Add save failure error handling with clear message
- [ ] T055 [US4] Test save with existing asset (verify enrichment, no duplicates)
- [ ] T056 [US4] Test save with new asset (verify auto-creation with cloudInstanceId)

**Checkpoint**: All user stories complete - full feature functional

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Quality improvements, documentation, and comprehensive testing

- [ ] T057 [P] Update OpenAPI specification at specs/041-falcon-instance-lookup/contracts/vulnerabilities-api.yaml if needed (already complete - verify accuracy)
- [ ] T058 [P] Run quickstart.md validation scenarios to ensure all examples work
- [ ] T059 [P] Add logging for instance ID queries in CrowdStrikeQueryService
- [ ] T060 [P] Security review: Verify input sanitization for instance IDs (XSS, injection prevention)
- [ ] T061 [P] Performance review: Verify cache key generation efficiency
- [ ] T062 Run full test suite (contract + unit + integration + E2E)
- [ ] T063 Manual testing: Test legacy instance ID format (i-XXXXXXXX with 8 chars)
- [ ] T064 Manual testing: Test current instance ID format (i-XXXXXXXXXXXXXXXXX with 17 chars)
- [ ] T065 Manual testing: Test error messages for invalid formats
- [ ] T066 Manual testing: Test with CrowdStrike API rate limiting
- [ ] T067 Code cleanup and refactoring (remove dead code, improve readability)
- [ ] T068 Update CLAUDE.md with new feature summary if needed

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational phase completion
  - US1, US2, US3 (all P1) should be completed together as they form the MVP
  - US4 (P2) can be done after MVP is validated
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - Enables backend instance ID queries
- **User Story 2 (P1)**: Can start after Foundational (Phase 2) - Requires US1 for full testing but UI can be built in parallel
- **User Story 3 (P1)**: Can start after Foundational (Phase 2) - Integrates with US1 for cache management
- **User Story 4 (P2)**: Depends on US1 completion (needs instance ID query working) - Adds save functionality

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD)
- Shared module changes before backend service changes
- Backend before frontend (API-first)
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

- **Phase 1**: All tasks (T001-T003) can run in parallel
- **Phase 2**: T006 and T007 can run in parallel (different files)
- **Phase 3**: T008, T009, T010 (tests) can run in parallel
- **Phase 4**: T023 and T024 (tests) can run in parallel; T025, T026, T027 (frontend changes) can run in parallel
- **Phase 5**: T030, T031, T032 (tests) can run in parallel; T036 can run in parallel with backend work
- **Phase 6**: T044, T045, T046 (tests) can run in parallel
- **Phase 7**: Most polish tasks (T057-T061) can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together:
# Task T008: Contract test for GET /api/vulnerabilities with instance ID
# Task T009: Unit test for instance ID validation
# Task T010: Unit test for queryByInstanceId method

# After tests fail, launch parallel implementation tasks:
# Task T011: Add queryDevicesByInstanceId to CrowdStrikeApiClient
# Task T012: Add getDeviceDetails to CrowdStrikeApiClient
# Task T013: Add error handling in CrowdStrikeApiClient
```

---

## Implementation Strategy

### MVP First (User Stories 1, 2, 3)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1 (backend instance ID queries)
4. Complete Phase 4: User Story 2 (frontend auto-detection)
5. Complete Phase 5: User Story 3 (cache indicators)
6. **STOP and VALIDATE**: Test US1+US2+US3 together as complete MVP
7. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Backend instance ID queries working → Test independently
3. Add User Story 2 → Frontend auto-detection working → Test independently → Deploy/Demo (MVP!)
4. Add User Story 3 → Cache indicators working → Test independently → Deploy/Demo
5. Add User Story 4 → Save functionality working → Test independently → Deploy/Demo (Full feature complete)
6. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (backend)
   - Developer B: User Story 2 (frontend auto-detection)
   - Developer C: User Story 3 (cache indicators)
3. Stories integrate naturally (API-first design)
4. After MVP validation, one developer handles User Story 4

---

## Task Summary

- **Total Tasks**: 68
- **Setup Tasks**: 3
- **Foundational Tasks**: 4 (BLOCKING)
- **User Story 1 Tasks**: 15 (T008-T022) - Backend instance ID query
- **User Story 2 Tasks**: 7 (T023-T029) - Frontend auto-detection
- **User Story 3 Tasks**: 14 (T030-T043) - Cache indicators
- **User Story 4 Tasks**: 13 (T044-T056) - Save functionality
- **Polish Tasks**: 12 (T057-T068)

### MVP Scope (68% of tasks)

- Phase 1: Setup (3 tasks)
- Phase 2: Foundational (4 tasks)
- Phase 3: User Story 1 (15 tasks)
- Phase 4: User Story 2 (7 tasks)
- Phase 5: User Story 3 (14 tasks)
- **MVP Total**: 43 tasks

### Post-MVP (32% of tasks)

- Phase 6: User Story 4 (13 tasks)
- Phase 7: Polish (12 tasks)
- **Post-MVP Total**: 25 tasks

### Parallel Opportunities Identified

- **21 tasks** marked with [P] can run in parallel with other tasks
- **All 4 user stories** can be staffed in parallel after Foundational phase
- **Test tasks** within each story can all run in parallel

---

## Notes

- [P] tasks = different files, no dependencies within phase
- [US#] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing (TDD)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Security-First: Input validation critical for instance IDs (T017, T020, T027)
- API-First: Backend complete before frontend (US1 → US2)
- TDD: All user stories have contract/unit/integration tests
- RBAC: Existing @Secured("ADMIN", "VULN") preserved throughout
