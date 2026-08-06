# Tasks: Memory and Heap Space Optimization

**Input**: Design documents from `/specs/073-memory-optimization/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Not included (tests only when explicitly requested per constitution)

**Organization**: Tasks grouped by user story for independent implementation and testing

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Config**: `src/backendng/src/main/resources/application.yml`
- **Frontend**: `src/frontend/src/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration and metrics infrastructure needed by all optimizations

- [x] T001 Create MemoryOptimizationConfig class in src/backendng/src/main/kotlin/com/secman/config/MemoryOptimizationConfig.kt
- [x] T002 [P] Add memory configuration section to src/backendng/src/main/resources/application.yml with lazy-loading-enabled, batch-size, streaming-exports-enabled flags
- [x] T003 [P] Create MemoryEndpoint class for JVM metrics in src/backendng/src/main/kotlin/com/secman/controller/MemoryController.kt
- [x] T004 [P] Add endpoints.memory configuration to src/backendng/src/main/resources/application.yml
- [ ] T005 Verify memory endpoint responds correctly: `curl http://localhost:8080/memory` (MANUAL - requires running server)

**Checkpoint**: Feature flags and observability infrastructure ready

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Repository query additions that multiple user stories depend on

**⚠️ CRITICAL**: User story implementations depend on these queries being available

- [x] T006 Add findByIdWithWorkgroups() EntityGraph query to src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt
- [x] T007 [P] Add findByIdWithWorkgroups() EntityGraph query to src/backendng/src/main/kotlin/com/secman/repository/UserRepository.kt
- [x] T008 [P] Add findDuplicateIds(batchSize: Int) native query to src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt

**Checkpoint**: Foundation ready - user story implementation can begin

---

## Phase 3: User Story 1 - Stable Vulnerability Queries Under Load (Priority: P1) 🎯 MVP

**Goal**: Vulnerability queries remain stable with exception status filters on 300K+ datasets without memory spikes >50MB

**Independent Test**: Query vulnerabilities with exception status filter on 100K+ records, monitor heap via /memory endpoint

### Implementation for User Story 1

- [x] T009 [US1] Add findWithExceptionStatusFilter() native query with SQL-level exception filtering to src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt
- [x] T010 [US1] Modify getCurrentVulnerabilities() in src/backendng/src/main/kotlin/com/secman/service/VulnerabilityService.kt to use SQL-level exception filtering instead of UNPAGED + in-memory filter
- [x] T011 [US1] Add feature flag check to VulnerabilityService to enable fallback to original behavior if needed
- [x] T012 [US1] Modify cleanupDuplicates() in src/backendng/src/main/kotlin/com/secman/service/VulnerabilityService.kt to use findDuplicateIds() with batched deletion instead of findAll()
- [x] T013 [US1] Add batch size configuration injection from MemoryOptimizationConfig to VulnerabilityService

**Checkpoint**: Vulnerability queries with exception filters use SQL-level filtering, duplicate cleanup is batched

---

## Phase 4: User Story 2 - Memory-Efficient Vulnerability Export (Priority: P2)

**Goal**: Exports of 300K+ records complete with <100MB memory overhead via streaming

**Independent Test**: Trigger export of 100K+ vulnerabilities, monitor /memory endpoint during export

### Implementation for User Story 2

- [x] T014 [US2] Refactor exportVulnerabilities() in src/backendng/src/main/kotlin/com/secman/service/VulnerabilityExportService.kt to use streaming pattern
- [x] T015 [US2] Remove intermediate allVulnerabilities mutableList accumulation in VulnerabilityExportService
- [x] T016 [US2] Modify export loop to write directly to SXSSFWorkbook during fetch (write-on-fetch pattern)
- [x] T017 [US2] Add feature flag check for streamingExportsEnabled to enable fallback to original behavior
- [x] T018 [US2] Update VulnerabilityExportDto.fromVulnerabilityWithException() if needed after DTO changes (coordinate with US4)

**Checkpoint**: Vulnerability exports stream directly to Excel without accumulating all records

---

## Phase 5: User Story 3 - Optimized Entity Loading (Priority: P2)

**Goal**: Asset/User workgroups use LAZY loading, list operations don't trigger N+1 queries

**Independent Test**: Load asset list of 50 items, verify Hibernate SQL shows 2-3 queries not 51

### Implementation for User Story 3

- [x] T019 [US3] Modify Asset.kt workgroups relationship from EAGER to LAZY in src/backendng/src/main/kotlin/com/secman/domain/Asset.kt
- [x] T020 [US3] Identify all callers of getEffectiveCriticality() and ensure workgroups are loaded via EntityGraph before call
- [x] T021 [US3] Update AssetService detail/update methods to use findByIdWithWorkgroups() for operations needing workgroups
- [x] T022 [P] [US3] Modify User.kt workgroups relationship from EAGER to LAZY in src/backendng/src/main/kotlin/com/secman/domain/User.kt
- [x] T023 [US3] Update UserService authentication/authorization methods to use findByIdWithWorkgroups() where needed
- [x] T024 [US3] Add conditional EAGER/LAZY behavior based on lazyLoadingEnabled feature flag in services
- [ ] T025 [US3] Verify asset list endpoint does not trigger workgroup loading (enable Hibernate SQL logging to verify)

**Checkpoint**: Entity loading is LAZY by default with explicit JOIN FETCH where needed, feature flag enables rollback

---

## Phase 6: User Story 4 - Streamlined Data Transfer Objects (Priority: P3)

**Goal**: API response sizes reduce 40%+ by removing nested Asset objects from DTOs

**Independent Test**: Compare API response sizes before/after using `curl -w '%{size_download}'`

### Implementation for User Story 4

- [x] T026 [US4] Locate VulnerabilityWithExceptionDto definition in src/backendng/src/main/kotlin/com/secman/dto/VulnerabilityExceptionDto.kt
- [x] T027 [US4] Add @JsonIgnore to `asset: Asset` field in VulnerabilityWithExceptionDto (excluded from JSON, kept for internal use)
- [x] T028 [US4] Asset field retained for service-layer use; no construction changes needed
- [x] T029 [US4] Verify frontend CurrentVulnerabilitiesTable.tsx uses flat fields - VERIFIED (no .asset. references found)
- [x] T030 [US4] VulnerabilityExportDto already uses flat fields from DTO - no changes needed

**Checkpoint**: API responses contain only flat asset fields, no nested objects

---

## Phase 7: User Story 5 - Efficient Access Control Queries (Priority: P3)

**Goal**: Access control uses single unified query instead of 4-5 separate queries + in-memory deduplication

**Independent Test**: Profile getAccessibleAssets() for user with multiple workgroups and mappings, verify single query executes

### Implementation for User Story 5

- [x] T031 [US5] Add findAccessibleAssets() unified UNION query to src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt
- [x] T032 [US5] Modify getAccessibleAssets() in src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt to use unified query
- [x] T033 [US5] Add fallback to original multi-query behavior via feature flag check
- [x] T034 [US5] Validate unified query returns identical results to original implementation (add comparison logging in dev mode)
- [x] T035 [US5] Remove in-memory deduplication (distinctBy) after unified query returns DISTINCT results

**Checkpoint**: Access control uses single database round trip, results match original behavior

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Build verification, validation, documentation

- [x] T036 Run ./gradlew build to verify no compilation errors in src/backendng/
- [x] T037 [P] Verify all feature flags default to optimized (true) in application.yml
- [x] T038 [P] Add memory optimization configuration documentation to CLAUDE.md (Environment Variables section)
- [ ] T039 Manual validation: Test vulnerability list with exception status filter
- [ ] T040 Manual validation: Test vulnerability export for performance
- [ ] T041 Manual validation: Test asset list for N+1 query elimination
- [ ] T042 Manual validation: Verify API response size reduction
- [ ] T043 Manual validation: Test rollback by setting feature flags to false

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS user stories 3 and 5
- **User Story 1 (Phase 3)**: Depends on T008 (foundational query)
- **User Story 2 (Phase 4)**: Can start after Setup; depends on US4 for DTO changes if coordinating
- **User Story 3 (Phase 5)**: Depends on T006, T007 (foundational EntityGraph queries)
- **User Story 4 (Phase 6)**: Can start after Setup - minimal dependencies
- **User Story 5 (Phase 7)**: Depends on Foundational phase
- **Polish (Phase 8)**: Depends on all user stories complete

### User Story Dependencies

| Story | Depends On | Can Parallel With |
|-------|-----------|-------------------|
| US1 (P1) | Foundational | US4 (different files) |
| US2 (P2) | Setup, may need US4 DTO | US1, US3, US5 |
| US3 (P2) | Foundational EntityGraph queries | US1, US4 |
| US4 (P3) | Setup only | US1, US3, US5 |
| US5 (P3) | Foundational | US1, US3, US4 |

### Within Each User Story

- Repository changes before service changes
- Service logic before feature flag integration
- Core implementation before validation/verification

### Parallel Opportunities

Within Setup:
- T002, T003, T004 can run in parallel (different files)

Within Foundational:
- T006, T007, T008 can run in parallel (different repositories)

Within User Story 3:
- T019 and T022 can run in parallel (Asset.kt vs User.kt)

---

## Parallel Example: Foundational Phase

```bash
# Launch all foundational repository queries together:
Task: "Add findByIdWithWorkgroups() to AssetRepository"
Task: "Add findByIdWithWorkgroups() to UserRepository"
Task: "Add findDuplicateIds() to VulnerabilityRepository"
```

## Parallel Example: User Story 1 + 4

```bash
# US1 and US4 can proceed in parallel (different services/files):
Task: "T009 [US1] Add findWithExceptionStatusFilter() to VulnerabilityRepository"
Task: "T026 [US4] Locate VulnerabilityWithExceptionDto in VulnerabilityService"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T005)
2. Complete Phase 2: Foundational T008 only (needed for US1)
3. Complete Phase 3: User Story 1 (T009-T013)
4. **STOP and VALIDATE**: Test vulnerability queries with exception filters
5. Verify memory stays under 50MB baseline increase via /memory endpoint
6. Deploy if ready - highest impact optimization delivered

### Incremental Delivery

1. Setup + Foundational → Infrastructure ready
2. Add US1 → Test SQL filtering → Reduced memory for queries ✓
3. Add US4 → Test response sizes → Smaller payloads ✓
4. Add US2 → Test exports → Streaming exports ✓
5. Add US3 → Test entity loading → LAZY loading ✓
6. Add US5 → Test access control → Unified queries ✓
7. Polish → Validate all success criteria

### Risk-Based Order (Recommended)

Based on quickstart.md risk assessment:

1. **Low Risk First**: US4 (DTO), US2 (export streaming)
2. **Medium Risk**: US1 (SQL filtering), US5 (unified query)
3. **Medium Risk + Feature Flagged**: US3 (LAZY loading)

This allows early wins while deferring potentially disruptive changes.

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks in same phase
- [Story] label maps task to specific user story for traceability
- Feature flags (T002) enable rollback without code revert
- /memory endpoint (T003-T004) provides validation capability throughout
- Constitution compliance: No test tasks included (testing only when requested)
- All changes maintain backward API compatibility
