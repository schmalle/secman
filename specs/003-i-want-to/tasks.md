# Tasks: Vulnerability Management System

**Input**: Design documents from `/Users/flake/sources/misc/secman/specs/003-i-want-to/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory ✅
   → Extracted: Kotlin/Java, Micronaut, Astro/React, MariaDB
2. Load optional design documents ✅
   → data-model.md: 2 entities (Vulnerability NEW, Asset EXTENDED)
   → contracts/: 2 files (upload-vulnerability-xlsx.yaml, get-asset-vulnerabilities.yaml)
   → research.md: Technical decisions extracted
   → quickstart.md: 7 test scenarios identified
3. Generate tasks by category ✅
   → Setup: Dependencies verification
   → Tests: 2 contract tests, 5 integration tests
   → Core: 2 entities, 2 services, 2 repositories, 2 DTOs, 2 controllers
   → Frontend: 2 components, 1 service, 1 page extension
   → Polish: Coverage check, manual QA
4. Apply task rules ✅
   → Different files = [P] for parallel
   → Same file = sequential
   → Tests before implementation (TDD)
5. Number tasks sequentially (T001-T035) ✅
6. Generate dependency graph ✅
7. Create parallel execution examples ✅
8. Validate task completeness ✅
   → All contracts have tests ✅
   → All entities have models ✅
   → All endpoints implemented ✅
9. Return: SUCCESS (tasks ready for execution) ✅
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Path Conventions
- **Backend**: `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `/Users/flake/sources/misc/secman/src/frontend/src/`
- **Frontend Tests**: `/Users/flake/sources/misc/secman/src/frontend/tests/`

---

## Phase 3.1: Setup & Dependencies
- [x] T001 Verify Apache POI 5.3.0 in build.gradle.kts (already present, no changes needed)
- [x] T002 Verify Micronaut Security JWT in build.gradle.kts (already present)
- [x] T003 Verify frontend dependencies: Astro 5.14, React 19, Bootstrap 5.3 (already present)

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### Contract Tests (Parallel)
- [x] T004 [P] Contract test POST /api/import/upload-vulnerability-xlsx in `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/contract/VulnerabilityImportContractTest.kt`
  - Test valid file + scan date → 200 OK with import counts
  - Test missing scan date → 400 Bad Request
  - Test invalid file format → 400 Bad Request
  - Test unauthenticated → 401 Unauthorized
  - Test response schema: imported, skipped, assetsCreated

- [x] T005 [P] Contract test GET /api/assets/{id}/vulnerabilities in `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/contract/AssetVulnerabilitiesContractTest.kt`
  - Test GET with valid asset ID → 200 OK with vulnerability array
  - Test GET with invalid asset ID → 404 Not Found
  - Test response matches Vulnerability schema

### Integration Tests (Parallel)
- [ ] T006 [P] Integration test: Import with known assets in `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityImportKnownAssetsTest.kt`
  - Create existing asset, upload file, verify vulnerabilities linked
  - Verify scan date recorded correctly

- [ ] T007 [P] Integration test: Import with unknown assets in `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityImportNewAssetsTest.kt`
  - Upload file with new hostname, verify asset auto-created
  - Verify defaults: owner="Security Team", type="Server", description="Auto-created from vulnerability scan"

- [ ] T008 [P] Integration test: Import with empty fields in `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityImportEmptyFieldsTest.kt`
  - Upload file with nulls/empty cells, verify imported with nulls preserved

- [ ] T009 [P] Integration test: Asset merge on conflict in `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/integration/AssetMergeIntegrationTest.kt`
  - Create asset with groups/IP, import conflicting data
  - Verify: groups appended, IP updated, owner/type/description preserved

- [ ] T010 [P] Integration test: Duplicate vulnerability handling in `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/integration/VulnerabilityDuplicateTest.kt`
  - Import same vulnerability twice (different scan dates)
  - Verify both kept as separate records (historical tracking)

### Unit Tests (Services - Parallel)
- [ ] T011 [P] Unit test VulnerabilityImportService parsing logic in `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/unit/VulnerabilityImportServiceTest.kt`
  - Test Excel row parsing (valid, invalid, empty cells)
  - Test header validation
  - Test file format validation
  - Mock Apache POI XSSFWorkbook

- [ ] T012 [P] Unit test AssetMergeService merge logic in `/Users/flake/sources/misc/secman/src/backendng/src/test/kotlin/com/secman/unit/AssetMergeServiceTest.kt`
  - Test group append (comma-separated deduplication)
  - Test IP update logic
  - Test field preservation (owner, type, description)
  - Test auto-creation defaults

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### Data Models (Parallel)
- [x] T013 [P] Create Vulnerability entity in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/domain/Vulnerability.kt`
  - Fields: id, asset (FK), vulnerabilityId, cvssSeverity, vulnerableProductVersions, daysOpen, scanTimestamp, createdAt
  - Annotations: @Entity, @Table with indexes
  - @ManyToOne relationship to Asset
  - @PrePersist for createdAt

- [x] T014 [P] Extend Asset entity in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`
  - Add fields: groups, cloudAccountId, cloudInstanceId, adDomain, osVersion
  - Add @OneToMany relationship to Vulnerability
  - Add @JsonIgnore on vulnerabilities list

### Repositories (Parallel)
- [x] T015 [P] Create VulnerabilityRepository in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt`
  - Extend JpaRepository<Vulnerability, Long>
  - Method: findByAssetId(assetId: Long, pageable: Pageable)
  - Method: findByAssetIdAndScanTimestampBetween(assetId, startDate, endDate, sort)

- [x] T016 [P] Extend AssetRepository in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`
  - Add method: findByName(name: String): Optional<Asset>
  - This repository already exists, just add the method

### DTOs (Parallel)
- [x] T017 [P] Create VulnerabilityImportResponse DTO in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/VulnerabilityImportResponse.kt`
  - @Serdeable data class
  - Fields: message, imported, skipped, assetsCreated, skippedDetails (list of row/reason)

- [x] T018 [P] Create SkippedRowDetail DTO in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/SkippedRowDetail.kt`
  - @Serdeable data class
  - Fields: row (Int), reason (String)

### Services (Sequential - Dependencies)
- [x] T019 Create AssetMergeService in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/AssetMergeService.kt`
  - @Singleton, inject AssetRepository
  - Method: findOrCreateAsset(hostname, excelData) → Asset
  - Logic: findByName, if exists merge (append groups, update IP), else create with defaults
  - Method: mergeGroups(existing, new) → String (comma-separated, deduplicated)

- [x] T020 Create VulnerabilityImportService in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/VulnerabilityImportService.kt`
  - @Singleton, inject VulnerabilityRepository, AssetMergeService
  - Method: importFromExcel(file: InputStream, scanDate: LocalDateTime) → ImportStats
  - Parse Excel with Apache POI XSSFWorkbook
  - Validate headers, parse rows (skip invalid, track in skippedDetails)
  - For each row: findOrCreateAsset, create Vulnerability, save
  - Return: VulnerabilityImportResponse

### Controllers (Sequential - Same file modifications)
- [ ] T021 Extend ImportController in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt`
  - Add @Post("/upload-vulnerability-xlsx") endpoint
  - @Consumes(MediaType.MULTIPART_FORM_DATA)
  - Parameters: @Part xlsxFile: CompletedFileUpload, @Part scanDate: String
  - Validate file (size, format, extension)
  - Parse scanDate to LocalDateTime
  - Call VulnerabilityImportService.importFromExcel
  - Return VulnerabilityImportResponse or error
  - @Secured(SecurityRule.IS_AUTHENTICATED)

- [ ] T022 Create or extend AssetController in `/Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
  - Add @Get("/{assetId}/vulnerabilities") endpoint
  - @Secured(SecurityRule.IS_AUTHENTICATED)
  - Parameters: assetId: Long, pageable: Pageable
  - Return: HttpResponse<Page<Vulnerability>>
  - 404 if asset not found

## Phase 3.4: Frontend Implementation

### Components (Parallel)
- [ ] T023 [P] Create VulnerabilityImportForm component in `/Users/flake/sources/misc/secman/src/frontend/src/components/VulnerabilityImportForm.tsx`
  - File input for .xlsx upload
  - HTML5 datetime-local input for scan date (pre-filled with current datetime)
  - Submit button "Import Vulnerabilities"
  - Loading indicator during upload
  - Success/error message display
  - Call vulnerabilityService.uploadVulnerabilityFile(file, scanDate)

- [ ] T024 [P] Create vulnerabilityService in `/Users/flake/sources/misc/secman/src/frontend/src/services/vulnerabilityService.ts`
  - Function: uploadVulnerabilityFile(file: File, scanDate: string) → Promise<VulnerabilityImportResponse>
  - POST to /api/import/upload-vulnerability-xlsx with multipart/form-data
  - Include JWT token from sessionStorage
  - Return parsed response

### Page Extensions (Sequential)
- [ ] T025 Extend Import.tsx in `/Users/flake/sources/misc/secman/src/frontend/src/components/Import.tsx`
  - Add "Vulnerabilities" tab (alongside existing "Requirements" tab)
  - Tab content: <VulnerabilityImportForm client:load />
  - Bootstrap nav-tabs styling

- [ ] T026 Extend asset.astro in `/Users/flake/sources/misc/secman/src/frontend/src/pages/asset.astro`
  - Add "Vulnerabilities" section below existing asset details
  - Fetch vulnerabilities: GET /api/assets/{id}/vulnerabilities
  - Display table: columns (CVE ID, Severity, Product Versions, Days Open, Scan Date)
  - Display new asset fields: Groups (as badges), OS Version, AD Domain, Cloud Account/Instance ID
  - Handle empty state (no vulnerabilities)

### E2E Tests
- [ ] T027 [P] E2E test: Vulnerability import flow in `/Users/flake/sources/misc/secman/src/frontend/tests/e2e/vulnerability-import.spec.ts`
  - Navigate to /import page
  - Click "Vulnerabilities" tab
  - Upload test-vulnerabilities.xlsx
  - Set scan date
  - Click Import button
  - Verify success message with counts
  - Navigate to asset page
  - Verify vulnerabilities displayed

## Phase 3.5: Integration & Polish

### Database Migration Verification
- [ ] T028 Verify Hibernate auto-creates vulnerability table on first run
  - Start backend, check logs for table creation
  - Verify indexes: idx_vulnerability_asset, idx_vulnerability_asset_scan
  - Verify foreign key constraint: asset_id → asset.id

- [ ] T029 Verify Asset table extended with new columns
  - Check logs for ALTER TABLE statements
  - Verify columns: groups, cloud_account_id, cloud_instance_id, ad_domain, os_version

### Test Data & Manual QA
- [ ] T030 [P] Create test Excel file in `/Users/flake/sources/misc/secman/testdata/test-vulnerabilities.xlsx`
  - Headers: Hostname, Local IP, Host groups, Cloud service account ID, Cloud service instance ID, OS version, Active Directory domain, Vulnerability ID, CVSS severity, Vulnerable product versions, Days open
  - Row 1: MSHome, existing asset
  - Row 2-3: WebServer01, duplicate hostname (different CVEs)
  - Row 4: NewAsset, new asset with minimal data
  - Row 5: InvalidRow, missing hostname (should be skipped)

- [ ] T031 Run quickstart.md Scenario 1: Import vulnerability file
  - Execute all steps in quickstart.md Scenario 1
  - Verify: 3 imported, 1 skipped, 1 asset created
  - Check API response matches expected

- [ ] T032 Run quickstart.md Scenario 2-3: View vulnerabilities, verify auto-creation
  - Navigate to asset detail pages
  - Verify vulnerabilities displayed correctly
  - Verify new asset created with defaults

- [ ] T033 Run quickstart.md Scenario 4-5: Asset merge, duplicate handling
  - Test conflict resolution logic
  - Verify groups appended, IP updated
  - Verify duplicate vulnerabilities kept

### Code Quality & Coverage
- [ ] T034 Run test suite, verify ≥80% coverage
  - Backend: ./gradlew test jacocoTestReport
  - Check coverage report: build/reports/jacoco/test/html/index.html
  - Frontend: npm test
  - Verify all tests pass

- [ ] T035 Final validation & cleanup
  - Run linters: ./gradlew ktlintCheck, npm run lint
  - Fix any linting issues
  - Update CLAUDE.md if needed (already done)
  - Remove debug logs
  - Commit final changes

---

## Dependencies

### Blocking Dependencies
- **Tests (T004-T012) MUST complete before implementation (T013-T022)**
- **T013 (Vulnerability entity) blocks T015 (VulnerabilityRepository)**
- **T014 (Asset extension) blocks T016 (AssetRepository.findByName)**
- **T016 (AssetRepository) blocks T019 (AssetMergeService)**
- **T015, T019 (Repositories, Services) block T020 (VulnerabilityImportService)**
- **T017, T020 (DTOs, Service) block T021 (ImportController endpoint)**
- **T015 (VulnerabilityRepository) blocks T022 (AssetController endpoint)**
- **T024 (vulnerabilityService) blocks T023 (VulnerabilityImportForm)**
- **T021, T023, T024 (Backend API, Form, Service) block T025 (Import.tsx extension)**
- **T022 (AssetController endpoint) blocks T026 (asset.astro extension)**
- **T025, T026 (Frontend pages) block T027 (E2E test)**
- **T021, T022 (API endpoints) block T028-T029 (DB verification)**
- **All implementation (T013-T026) blocks T030-T035 (QA & polish)**

### No Dependencies (Can Start Anytime)
- T001-T003 (Setup verification - already complete)

---

## Parallel Execution Examples

### Example 1: Contract Tests (After setup)
```bash
# Launch T004-T005 together (different test files):
Task agent: "Write contract test POST /api/import/upload-vulnerability-xlsx in VulnerabilityImportContractTest.kt. Test: valid file+scanDate→200, missing scanDate→400, invalid format→400, unauthorized→401. All tests MUST FAIL (no implementation yet)."

Task agent: "Write contract test GET /api/assets/{id}/vulnerabilities in AssetVulnerabilitiesContractTest.kt. Test: valid ID→200 with array, invalid ID→404. All tests MUST FAIL (no implementation yet)."
```

### Example 2: Integration Tests (After contract tests)
```bash
# Launch T006-T010 together (different test files):
Task agent: "Integration test in VulnerabilityImportKnownAssetsTest.kt: Create asset, upload file, verify vulnerabilities linked with correct scan date."

Task agent: "Integration test in VulnerabilityImportNewAssetsTest.kt: Upload file with unknown hostname, verify asset auto-created with defaults."

Task agent: "Integration test in VulnerabilityImportEmptyFieldsTest.kt: Upload file with nulls, verify imported with nulls preserved."

Task agent: "Integration test in AssetMergeIntegrationTest.kt: Create asset, import conflicting data, verify merge (groups appended, IP updated, owner/type preserved)."

Task agent: "Integration test in VulnerabilityDuplicateTest.kt: Import same vulnerability twice (different scan dates), verify both kept."
```

### Example 3: Data Models (After tests failing)
```bash
# Launch T013-T014 together (different entity files):
Task agent: "Create Vulnerability entity in domain/Vulnerability.kt per data-model.md. Include @Entity, @Table with indexes, @ManyToOne Asset, @PrePersist for createdAt."

Task agent: "Extend Asset entity in domain/Asset.kt. Add fields: groups, cloudAccountId, cloudInstanceId, adDomain, osVersion. Add @OneToMany Vulnerability with @JsonIgnore."
```

### Example 4: Repositories (After entities)
```bash
# Launch T015-T016 together (different repository files):
Task agent: "Create VulnerabilityRepository in repository/VulnerabilityRepository.kt. Extend JpaRepository. Methods: findByAssetId(assetId, pageable), findByAssetIdAndScanTimestampBetween(assetId, start, end, sort)."

Task agent: "Extend AssetRepository in repository/AssetRepository.kt. Add method: findByName(name: String): Optional<Asset>. File already exists, just add method."
```

### Example 5: Frontend Components (After backend complete)
```bash
# Launch T023-T024 together (different files):
Task agent: "Create VulnerabilityImportForm.tsx. File input (.xlsx), datetime-local input (pre-filled), submit button. Call vulnerabilityService.uploadVulnerabilityFile(file, scanDate). Show success/error message."

Task agent: "Create vulnerabilityService.ts. Function: uploadVulnerabilityFile(file, scanDate) → Promise<VulnerabilityImportResponse>. POST multipart/form-data to /api/import/upload-vulnerability-xlsx with JWT."
```

---

## Notes
- **[P] tasks** = different files, no dependencies, can run in parallel
- **TDD CRITICAL**: Verify all tests fail (T004-T012) before writing ANY implementation code
- **After each task**: Run tests, verify expected behavior, commit changes
- **Avoid**: Modifying same file in parallel tasks, skipping test failures, vague task descriptions
- **Constitution compliance**: All endpoints @Secured, tests ≥80% coverage, Docker-compatible, RBAC enforced

---

## Validation Checklist
*GATE: Checked before marking tasks complete*

- [x] All contracts have corresponding tests (T004-T005 cover upload-vulnerability-xlsx.yaml, get-asset-vulnerabilities.yaml)
- [x] All entities have model tasks (T013 Vulnerability, T014 Asset extension)
- [x] All tests come before implementation (T004-T012 before T013-T026)
- [x] Parallel tasks truly independent (checked file paths, no conflicts)
- [x] Each task specifies exact file path (all tasks have absolute paths)
- [x] No task modifies same file as another [P] task (verified: Import.tsx T025 not parallel, asset.astro T026 not parallel due to sequential dependency)

---

## Task Execution Status
Track progress by marking tasks complete:
- **Phase 3.1 Setup**: 3/3 complete (verified, no changes needed)
- **Phase 3.2 Tests**: 0/9 complete (T004-T012)
- **Phase 3.3 Core**: 0/10 complete (T013-T022)
- **Phase 3.4 Frontend**: 0/5 complete (T023-T027)
- **Phase 3.5 Polish**: 0/8 complete (T028-T035)

**Total**: 3/35 tasks complete (8.6%)

**Next Action**: Start Phase 3.2 - Write contract tests T004-T005 in parallel (MUST FAIL before proceeding)
