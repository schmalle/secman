# Tasks: Nmap Scan Import and Management

**Input**: Design documents from `/Users/flake/sources/misc/secman/specs/002-implement-a-parsing/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → ✅ DONE: Tech stack: Kotlin/Micronaut backend, TypeScript/React frontend
2. Load optional design documents:
   → ✅ data-model.md: 4 entities (Scan, ScanResult, ScanPort, Asset)
   → ✅ contracts/: 3 API contracts (upload-nmap, list-scans, asset-ports)
   → ✅ research.md: 6 technical decisions resolved
   → ✅ quickstart.md: 6 test scenarios defined
3. Generate tasks by category:
   → Setup: Test data, dependencies
   → Tests: 3 contract tests, 6 integration tests
   → Core: 4 entities, 3 repositories, 2 services, 1 controller
   → Frontend: 2 new components, 3 enhanced components
   → Integration: Upload flow, scan display, port history
   → Polish: Unit tests, E2E tests, performance validation
4. Apply task rules:
   → Different files = mark [P] for parallel
   → Same file = sequential (no [P])
   → Tests before implementation (TDD)
5. Number tasks sequentially (T001-T043)
6. Generate dependency graph (below)
7. Create parallel execution examples (below)
8. Validate task completeness: ✅ All contracts, entities, endpoints covered
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Path Conventions
**Web app structure** (from plan.md):
- Backend: `src/backendng/src/main/kotlin/com/secman/`
- Frontend: `src/frontend/src/components/`
- Backend tests: `src/backendng/src/test/kotlin/com/secman/`
- Frontend tests: `src/frontend/tests/`

---

## Phase 3.1: Setup

- [x] T001 Copy testdata/nmap.xml to backend test resources at `src/backendng/src/test/resources/nmap-test.xml` for unit testing
- [x] T002 Verify Micronaut XML parsing dependencies available (javax.xml.parsers standard library - no new deps needed)
- [x] T003 [P] Configure ESLint rules for new TypeScript components in `src/frontend/`

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### Contract Tests (Backend)
- [x] T004 [P] Contract test POST /api/scan/upload-nmap in `src/backendng/src/test/kotlin/com/secman/contract/ScanControllerUploadTest.kt` - test multipart file upload, XML validation, scan summary response
- [x] T005 [P] Contract test GET /api/scans in `src/backendng/src/test/kotlin/com/secman/contract/ScanControllerListTest.kt` - test pagination, ADMIN auth, scan type filter
- [x] T006 [P] Contract test GET /api/scans/{id} in `src/backendng/src/test/kotlin/com/secman/contract/ScanControllerDetailTest.kt` - test scan detail response with host list, 404 handling
- [x] T007 [P] Contract test GET /api/assets/{id}/ports in `src/backendng/src/test/kotlin/com/secman/contract/AssetControllerPortsTest.kt` - test port history response, authenticated access

### Integration Tests (Backend)
- [x] T008 [P] Integration test nmap upload workflow in `src/backendng/src/test/kotlin/com/secman/integration/NmapImportIntegrationTest.kt` - upload file → verify Scan/ScanResult/ScanPort/Asset created in DB
- [x] T009 [P] Integration test duplicate IP handling in `src/backendng/src/test/kotlin/com/secman/integration/DuplicateHostTest.kt` - verify skip behavior per research.md Decision 2
- [x] T010 [P] Integration test asset naming fallback in `src/backendng/src/test/kotlin/com/secman/integration/AssetNamingTest.kt` - verify IP-as-name when hostname missing per research.md Decision 1
- [x] T011 [P] Integration test multiple scans same host in `src/backendng/src/test/kotlin/com/secman/integration/ScanHistoryTest.kt` - verify ScanResult accumulation, asset reuse
- [x] T012 [P] Integration test RBAC for scans page in `src/backendng/src/test/kotlin/com/secman/integration/ScanRBACTest.kt` - verify admin-only access, normal user 403
- [x] T013 [P] Integration test port history retrieval in `src/backendng/src/test/kotlin/com/secman/integration/PortHistoryTest.kt` - verify chronological ordering, authenticated access

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### Domain Models
- [x] T014 [P] Create Scan entity in `src/backendng/src/main/kotlin/com/secman/domain/Scan.kt` with fields: id, scanType, filename, scanDate, uploadedBy, hostCount, duration, createdAt, results (OneToMany)
- [x] T015 [P] Create ScanResult entity in `src/backendng/src/main/kotlin/com/secman/domain/ScanResult.kt` with fields: id, scan (ManyToOne), asset (ManyToOne), ipAddress, hostname, discoveredAt, ports (OneToMany)
- [x] T016 [P] Create ScanPort entity in `src/backendng/src/main/kotlin/com/secman/domain/ScanPort.kt` with fields: id, scanResult (ManyToOne), portNumber, protocol, state, service, version
- [x] T017 Enhance Asset entity in `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt` - add bidirectional relationship to ScanResult (scanResults: List<ScanResult> - NO schema changes to Asset table)

### Repositories
- [x] T018 [P] Create ScanRepository in `src/backendng/src/main/kotlin/com/secman/repository/ScanRepository.kt` - extend JpaRepository with custom queries: findByUploadedBy, findByScanType
- [x] T019 [P] Create ScanResultRepository in `src/backendng/src/main/kotlin/com/secman/repository/ScanResultRepository.kt` - extend JpaRepository with findByAssetIdOrderByDiscoveredAtDesc
- [x] T020 [P] Create ScanPortRepository in `src/backendng/src/main/kotlin/com/secman/repository/ScanPortRepository.kt` - extend JpaRepository

### Services
- [x] T021 Create NmapParserService in `src/backendng/src/main/kotlin/com/secman/service/NmapParserService.kt` - parse nmap XML using javax.xml.parsers.DocumentBuilder, extract hosts/IPs/ports/metadata, handle malformed XML
- [x] T022 Create ScanImportService in `src/backendng/src/main/kotlin/com/secman/service/ScanImportService.kt` - coordinate import: call NmapParserService, lookup/create Assets by IP, create Scan/ScanResult/ScanPort records, return summary, implement duplicate IP skip logic, **add audit logging for scan uploads, parse errors, and asset creation (NFR-003)**

### Controller & DTOs
- [x] T023 Create ScanController in `src/backendng/src/main/kotlin/com/secman/controller/ScanController.kt` with endpoints: POST /api/scan/upload-nmap (@Secured("ADMIN")), GET /api/scans (@Secured("ADMIN") with pagination), GET /api/scans/{id} (@Secured("ADMIN"))
- [x] T024 Add GET /api/assets/{id}/ports endpoint to existing AssetController in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt` (@Secured("IS_AUTHENTICATED") - all users)
- [x] T025 [P] Create ScanSummaryDTO in `src/backendng/src/main/kotlin/com/secman/dto/ScanSummaryDTO.kt` for upload response
- [x] T026 [P] Create PortHistoryDTO in `src/backendng/src/main/kotlin/com/secman/dto/PortHistoryDTO.kt` for asset ports response

## Phase 3.4: Frontend Implementation

### New Components
- [ ] T027 [P] Create ScanManagement component in `src/frontend/src/components/ScanManagement.tsx` - scan list table with pagination, scan detail modal, ADMIN role check, links to asset pages
- [ ] T028 [P] Create PortHistory component in `src/frontend/src/components/PortHistory.tsx` - modal/panel showing port timeline, grouped by scan date, port state badges (open/filtered/closed)

### Enhanced Components
- [ ] T029 Enhance Import component in `src/frontend/src/components/Import.tsx` - add nmap file type to accept attribute, add upload handler for /api/scan/upload-nmap, display scan summary response
- [ ] T030 Enhance Sidebar component in `src/frontend/src/components/Sidebar.tsx` - add "Scans" menu item with admin-only conditional rendering (check isAdmin state)
- [ ] T031 Enhance AssetManagement component in `src/frontend/src/components/AssetManagement.tsx` - add "Show open ports" button to asset rows, check for scan data existence via API, open PortHistory modal on click

### Routing & Pages
- [ ] T032 Create /scans route in `src/frontend/src/pages/scans.astro` - render ScanManagement component, add auth guard for ADMIN role

## Phase 3.5: Integration & Polish

### End-to-End Tests
- [ ] T033 [P] E2E test nmap upload flow in `src/frontend/tests/e2e/nmap-import.spec.ts` using Playwright - login as admin, navigate to Import, upload testdata/nmap.xml, verify success message
- [ ] T034 [P] E2E test scans page access in `src/frontend/tests/e2e/scans-page.spec.ts` - verify admin can access, normal user gets 403, scan list displays correctly
- [ ] T035 [P] E2E test port history display in `src/frontend/tests/e2e/port-history.spec.ts` - navigate to asset page, click "Show open ports", verify modal shows port data

### Unit Tests (Backend)
- [ ] T036 [P] Unit test NmapParserService in `src/backendng/src/test/kotlin/com/secman/service/NmapParserServiceTest.kt` - test valid XML parsing, malformed XML handling, missing hostname handling, port extraction
- [ ] T037 [P] Unit test ScanImportService in `src/backendng/src/test/kotlin/com/secman/service/ScanImportServiceTest.kt` - test asset lookup/create logic, duplicate skip, scan summary generation

### Performance & Validation
- [ ] T038 Performance test large file upload in `src/backendng/src/test/kotlin/com/secman/performance/LargeFileImportTest.kt` - test 1000-host nmap file completes <30s per plan.md performance goals
- [ ] T039 Validate database constraints in `src/backendng/src/test/kotlin/com/secman/domain/EntityConstraintTest.kt` - test FK cascades, check constraints (port range 1-65535, scanType enum, protocol enum)
- [ ] T040 Run manual validation using quickstart.md - execute all 6 test scenarios, verify database state, check RBAC enforcement
- [ ] T041 [P] Update API documentation - regenerate OpenAPI spec if using Swagger annotations, verify contract YAMLs match implementation
- [ ] T042 Code review checklist - verify 80%+ test coverage, linting passes, no hardcoded values, security logging present for file uploads
- [ ] T043 [P] Test cascade delete behavior in `src/backendng/src/test/kotlin/com/secman/integration/CascadeDeleteTest.kt` - verify when Asset deleted, associated ScanResult and ScanPort records are automatically removed via FK constraints (addresses edge case: orphaned scan data)

---

## Dependencies

### Critical Path (Sequential)
1. T001-T003 (Setup) → T004-T013 (Tests) → T014-T026 (Backend Core) → T027-T032 (Frontend) → T033-T043 (Validation)
2. T014-T017 (Entities) must complete before T018-T020 (Repositories)
3. T018-T020 (Repositories) must complete before T021-T022 (Services)
4. T021-T022 (Services) must complete before T023-T024 (Controllers)
5. T023-T024 (Controllers) must complete before T027-T032 (Frontend)

### Parallel Groups
**Group 1: Contract Tests** (T004-T007) - Independent test files
**Group 2: Integration Tests** (T008-T013) - Independent test files
**Group 3: Entities** (T014-T017) - Different entity files (but must wait for each other to finish before repositories)
**Group 4: Repositories** (T018-T020) - Independent repository files
**Group 5: DTOs** (T025-T026) - Independent DTO files
**Group 6: New Components** (T027-T028) - Independent React components
**Group 7: E2E Tests** (T033-T035) - Independent Playwright specs
**Group 8: Unit Tests** (T036-T037, T043) - Independent test files

---

## Parallel Execution Examples

### Example 1: Contract Tests (After T003)
```bash
# All contract tests can run in parallel - different test files
Task: "Write contract test for POST /api/scan/upload-nmap in ScanControllerUploadTest.kt"
Task: "Write contract test for GET /api/scans in ScanControllerListTest.kt"
Task: "Write contract test for GET /api/scans/{id} in ScanControllerDetailTest.kt"
Task: "Write contract test for GET /api/assets/{id}/ports in AssetControllerPortsTest.kt"
```

### Example 2: Integration Tests (After T007)
```bash
# All integration tests can run in parallel
Task: "Write integration test for nmap upload workflow in NmapImportIntegrationTest.kt"
Task: "Write integration test for duplicate IP handling in DuplicateHostTest.kt"
Task: "Write integration test for asset naming fallback in AssetNamingTest.kt"
Task: "Write integration test for multiple scans same host in ScanHistoryTest.kt"
Task: "Write integration test for RBAC enforcement in ScanRBACTest.kt"
Task: "Write integration test for port history retrieval in PortHistoryTest.kt"
```

### Example 3: Domain Entities (After T013)
```bash
# Entities can be created in parallel - independent files
Task: "Create Scan entity in domain/Scan.kt"
Task: "Create ScanResult entity in domain/ScanResult.kt"
Task: "Create ScanPort entity in domain/ScanPort.kt"
# T017 runs after T014-T016 complete (modifies existing Asset.kt)
```

### Example 4: Frontend Components (After T026)
```bash
# New components independent - can run in parallel
Task: "Create ScanManagement component in components/ScanManagement.tsx"
Task: "Create PortHistory component in components/PortHistory.tsx"
```

---

## Notes

### TDD Workflow
1. **Write ALL tests first** (T004-T013) - Verify they FAIL
2. **Implement backend core** (T014-T026) - Verify tests PASS
3. **Implement frontend** (T027-T032) - Verify UI works
4. **Validate everything** (T033-T042) - Verify E2E, performance, coverage

### File Modification Conflicts
- **T017**: Modifies existing Asset.kt - NOT parallel with T014-T016
- **T024**: Modifies existing AssetController.kt - NOT parallel with other controller work
- **T029-T031**: Each modifies different existing files - Can be sequential or careful parallel

### Research Decisions Applied
- **T010**: Implements research.md Decision 1 (IP-as-name fallback)
- **T009**: Implements research.md Decision 2 (duplicate skip)
- **T014**: Implements research.md Decision 3 (default type "Network Host")
- **T011**: Implements research.md Decision 4 (point-in-time snapshots)
- **T014**: Implements research.md Decision 5 (scanType discriminator)
- **T023**: Implements research.md Decision 6 (60s timeout via Micronaut config)

### Performance Targets (from plan.md)
- Upload 1000-host file: <30s (T038 validates)
- API response: <200ms p95 (T038 validates)
- Test coverage: ≥80% (T042 validates)

---

## Validation Checklist
*GATE: Checked before marking feature complete*

- [x] All contracts have corresponding tests (T004-T007 cover 3 contract files)
- [x] All entities have model tasks (T014-T017 cover 4 entities)
- [x] All tests come before implementation (T004-T013 before T014+)
- [x] Parallel tasks truly independent (different files, verified above)
- [x] Each task specifies exact file path (all tasks include full paths)
- [x] No task modifies same file as another [P] task (conflicts identified in Notes)

**Task count**: 43 tasks total
**Estimated parallel savings**: ~40% with 8 parallel groups
**Critical path length**: ~15 sequential steps (setup → tests → entities → repos → services → controller → frontend → validation)
