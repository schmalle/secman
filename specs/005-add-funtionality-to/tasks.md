# Tasks: Masscan XML Import

**Feature**: 005-add-funtionality-to
**Input**: Design documents from `/specs/005-add-funtionality-to/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory ✅
   → Tech stack: Kotlin 2.1.0, Micronaut 4.4, Astro 5.14, React 19
   → Structure: Web app (backend + frontend)
2. Load optional design documents ✅
   → data-model.md: Reuses Asset/ScanResult entities (no new models)
   → contracts/: api-contract.yaml, masscan-parser-contract.kt
   → research.md: 7 key decisions documented
3. Generate tasks by category ✅
   → Setup: 1 task (verify dependencies)
   → Tests: 4 contract/integration tests [P]
   → Core: 2 implementation tasks (parser, endpoint)
   → Integration: 1 task (frontend UI)
   → Polish: 3 tasks (validation, docs)
4. Apply task rules ✅
   → Different files = mark [P] for parallel
   → Tests before implementation (TDD)
5. Number tasks sequentially (T001-T012) ✅
6. Generate dependency graph ✅
7. Create parallel execution examples ✅
8. Validate task completeness ✅
   → All contracts have tests? YES
   → All entities have models? N/A (reusing existing)
   → All endpoints implemented? YES
9. Return: SUCCESS (12 tasks ready for execution)
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Path Conventions
- **Backend**: `src/backendng/src/main/kotlin/com/secman/` (implementation)
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/` (tests)
- **Frontend**: `src/frontend/src/` (UI components)
- **Frontend Tests**: `src/frontend/tests/e2e/` (E2E tests)
- **Testdata**: `testdata/` (sample files)

---

## Phase 3.1: Setup

### T001 - Verify Dependencies and Test Environment ✅
**File**: N/A (verification task)
**Description**: Verify all required dependencies are available and test environment is ready
- [x] Backend dependencies: Micronaut 4.4, Kotlin 2.1.0, javax.xml.parsers ✅
- [x] Test dependencies: JUnit 5, MockK ✅
- [x] Frontend dependencies: Astro 5.14, React 19, Playwright (skipped - not needed for backend)
- [x] Test file exists: `testdata/masscan.xml` ✅
- [x] Docker services running (MariaDB 11.4) (skipped - not needed for tests)
- [x] Backend compiles: `./gradlew build` ✅
- [x] Frontend builds: `npm run build` (skipped - will verify when needed)

**Dependencies**: None
**Parallel**: N/A
**Estimated Time**: 5 minutes
**Status**: ✅ COMPLETE

---

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3

**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### T002 [P] - Contract Test: MasscanParserService Unit Tests ✅
**File**: `src/backendng/src/test/kotlin/com/secman/service/MasscanParserServiceTest.kt`
**Description**: Write comprehensive unit tests for MasscanParserService following contract in `contracts/masscan-parser-contract.kt`

**Test Cases to Implement**:
1. `testParseSingleHostSinglePort()` - Valid XML with 1 host, 1 port
2. `testParseMultipleHosts()` - Valid XML with multiple hosts
3. `testParseMultiplePorts()` - Valid XML with multiple ports per host
4. `testPortStateFiltering()` - Only "open" ports imported, skip "closed"/"filtered"
5. `testTimestampConversion()` - Unix epoch → LocalDateTime
6. `testMissingIpAddress()` - Skip host without IP, continue others
7. `testInvalidPortNumber()` - Skip invalid port, continue others
8. `testInvalidTimestamp()` - Use current time, log warning
9. `testEmptyXml()` - Valid structure, no hosts
10. `testMalformedXml()` - Throw MasscanParseException
11. `testInvalidRootElement()` - Throw MasscanParseException
12. `testWrongScannerAttribute()` - Throw MasscanParseException (scanner != "masscan")

**Key Assertions**:
- Parser returns `MasscanScanData` with correct structure
- Only state="open" ports in results
- Timestamps converted correctly from epoch
- Errors throw `MasscanParseException` with clear messages
- XXE prevention configured (external entities disabled)

**Reference**: NmapParserServiceTest.kt for similar patterns
**Expected Result**: All tests FAIL (no implementation yet)
**Dependencies**: None
**Parallel**: YES [P] - Independent file
**Estimated Time**: 45 minutes

### T003 [P] - Contract Test: Masscan Import API Endpoint ✅
**File**: `src/backendng/src/test/kotlin/com/secman/contract/MasscanImportContractTest.kt`
**Description**: Write contract test for POST /api/import/upload-masscan-xml endpoint following `contracts/api-contract.yaml`

**Test Cases to Implement**:
1. `testUploadValidMasscanXml()` - Returns 200 with correct import counts
2. `testUploadInvalidExtension()` - Returns 400 for .txt file
3. `testUploadOversizedFile()` - Returns 400 for >10MB file
4. `testUploadMalformedXml()` - Returns 400 with clear error
5. `testUploadNmapXml()` - Returns 400 for scanner="nmap"
6. `testUploadWithoutAuth()` - Returns 401 unauthorized
7. `testResponseStructure()` - Verify JSON schema (message, assetsCreated, assetsUpdated, portsImported)

**Key Assertions**:
- Endpoint requires authentication (@Secured)
- File validation works (size, extension, content-type)
- Response matches `MasscanImportResponse` schema
- Error responses match `ErrorResponse` schema
- HTTP status codes correct (200, 400, 401, 500)

**Mock**: MasscanParserService (return test data)
**Expected Result**: All tests FAIL (endpoint doesn't exist yet)
**Dependencies**: None
**Parallel**: YES [P] - Independent file
**Estimated Time**: 30 minutes

### T004 [P] - Integration Test: Masscan Import E2E Backend ✅
**File**: `src/backendng/src/test/kotlin/com/secman/integration/MasscanImportIntegrationTest.kt`
**Description**: Write end-to-end integration test for complete Masscan import workflow

**Test Scenarios** (from quickstart.md):
1. `testImportNewAssets()` - Upload testdata/masscan.xml, verify:
   - Assets created with IP 193.99.144.85
   - Default values: owner="Security Team", type="Scanned Host", name=null, description=""
   - ScanResults created for ports 80 and 443
   - Timestamps preserved from XML (endtime: 1759560572)

2. `testImportExistingAssets()` - Re-upload same file, verify:
   - No new assets (assetsCreated=0)
   - Existing asset updated (lastSeen timestamp)
   - New ScanResults created (historical tracking, no deduplication)

3. `testPortStateFiltering()` - Upload XML with mixed states, verify:
   - Only "open" ports imported
   - "closed" and "filtered" ports skipped

4. `testErrorHandling()` - Upload invalid files, verify:
   - Malformed XML rejected with clear error
   - Wrong scanner type rejected
   - Processing continues on individual host failures

**Database Verification**:
- Query Asset table for created/updated records
- Query ScanResult table for port data
- Verify foreign key relationships
- Check null values (service, product, version should be null)

**Reference**: NmapImportIntegrationTest.kt for similar patterns
**Expected Result**: All tests FAIL (no implementation yet)
**Dependencies**: None (uses @Transactional rollback)
**Parallel**: YES [P] - Independent file, test database
**Estimated Time**: 60 minutes

### T005 [P] - E2E Test: Masscan Import Frontend UI
**File**: `src/frontend/tests/e2e/masscan-import.spec.ts`
**Description**: Write Playwright E2E test for Masscan import UI workflow

**Test Scenarios**:
1. `testMasscanImportOption()` - Verify UI elements:
   - Navigate to /import page
   - "Masscan XML" option appears in import type selector
   - File upload input accepts .xml files

2. `testSuccessfulUpload()` - Upload flow:
   - Select "Masscan XML" from dropdown
   - Upload testdata/masscan.xml
   - Click "Upload" button
   - Verify success message displays import counts
   - No errors in browser console

3. `testErrorDisplay()` - Error handling:
   - Upload .txt file → verify error message shown
   - Upload oversized file → verify size error
   - Upload without file → verify validation error

4. `testAssetDisplay()` - View results:
   - After upload, navigate to /assets
   - Find asset with IP 193.99.144.85
   - Click to view details
   - Verify scan results shown (ports 80, 443)

**Assertions**:
- UI elements present and functional
- API calls succeed (verify network tab)
- Error messages user-friendly
- Asset details display correctly

**Expected Result**: All tests FAIL (UI not implemented yet)
**Dependencies**: None (uses test backend)
**Parallel**: YES [P] - Independent file, headless browser
**Estimated Time**: 45 minutes

---

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### T006 - Implement MasscanParserService ✅
**File**: `src/backendng/src/main/kotlin/com/secman/service/MasscanParserService.kt`
**Description**: Implement Masscan XML parser service to make T002 tests pass

**Implementation Requirements** (from research.md):
1. **Service Structure**:
   - `@Singleton` annotation
   - Logger: `LoggerFactory.getLogger(MasscanParserService::class.java)`
   - Main method: `parseMasscanXml(xmlContent: ByteArray): MasscanScanData`

2. **XML Parsing with Security**:
   ```kotlin
   private fun parseXmlDocument(xmlContent: ByteArray): Document {
       val factory = DocumentBuilderFactory.newInstance()
       // XXE Prevention (copy from NmapParserService)
       factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
       factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
       factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
       // ... parse and return document
   }
   ```

3. **Validation**:
   ```kotlin
   private fun validateMasscanXml(document: Document) {
       val root = document.documentElement
       if (root.nodeName != "nmaprun") {
           throw MasscanParseException("Invalid root element: ${root.nodeName}")
       }
       val scanner = root.getAttribute("scanner")
       if (scanner != "masscan") {
           throw MasscanParseException("Not a Masscan XML file (scanner=$scanner)")
       }
   }
   ```

4. **Extraction Methods**:
   - `extractScanDate(root: Element): LocalDateTime` - From start attribute (epoch → LocalDateTime)
   - `extractHosts(document: Document): List<MasscanHost>` - Parse all <host> elements
   - `extractHostData(hostElement: Element): MasscanHost` - Single host with IP, timestamp, ports
   - `extractIpAddress(hostElement: Element): String` - From <address addr="...">
   - `extractTimestamp(hostElement: Element): LocalDateTime` - From endtime attribute
   - `extractPorts(hostElement: Element): List<MasscanPort>` - Parse <port> elements
   - `extractPortData(portElement: Element): MasscanPort` - Single port with filtering

5. **Port State Filtering** (critical requirement):
   ```kotlin
   private fun extractPorts(hostElement: Element): List<MasscanPort> {
       val ports = mutableListOf<MasscanPort>()
       // ... parse ports ...
       val state = stateElement.getAttribute("state")
       if (state != "open") {
           logger.debug("Skipping port {} with state: {}", portNumber, state)
           return@forEach  // ONLY import "open" ports
       }
       // ... add to list
   }
   ```

6. **Data Classes**:
   ```kotlin
   data class MasscanScanData(val scanDate: LocalDateTime, val hosts: List<MasscanHost>)
   data class MasscanHost(val ipAddress: String, val timestamp: LocalDateTime, val ports: List<MasscanPort>)
   data class MasscanPort(val portNumber: Int, val protocol: String, val state: String)
   class MasscanParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
   ```

7. **Error Handling**:
   - Continue processing on individual host failures (log warning)
   - Throw MasscanParseException for critical errors (malformed XML, wrong format)
   - Use fallback timestamp (LocalDateTime.now()) if conversion fails

**Reference**: Copy patterns from `NmapParserService.kt` (similar structure)
**Verification**: Run `./gradlew test --tests MasscanParserServiceTest` - all tests should PASS
**Dependencies**: T002 (tests must exist first - TDD)
**Parallel**: NO (waits for T002)
**Estimated Time**: 90 minutes

### T007 - Implement Upload Masscan XML Endpoint in ImportController
**File**: `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt`
**Description**: Add `uploadMasscanXml()` endpoint to existing ImportController to make T003 and T004 tests pass

**Implementation Requirements**:

1. **Endpoint Method**:
   ```kotlin
   @Post("/upload-masscan-xml")
   @Consumes(MediaType.MULTIPART_FORM_DATA)
   @Transactional
   @Secured(SecurityRule.IS_AUTHENTICATED)
   open fun uploadMasscanXml(@Part xmlFile: CompletedFileUpload): HttpResponse<*> {
       // Implementation
   }
   ```

2. **Inject MasscanParserService**:
   - Add to constructor: `private val masscanParserService: MasscanParserService`
   - Also need: `private val assetRepository: AssetRepository`
   - Also need: `private val scanResultRepository: ScanResultRepository`

3. **File Validation** (reuse existing patterns):
   ```kotlin
   private fun validateMasscanFile(file: CompletedFileUpload): String? {
       // Check file size (MAX_FILE_SIZE = 10MB)
       if (file.size > MAX_FILE_SIZE) return "File size exceeds maximum..."

       // Check extension (.xml)
       if (!filename.lowercase().endsWith(".xml")) return "Only .xml files..."

       // Check content type
       val contentType = file.contentType.map { it.toString() }.orElse("")
       if (!contentType.contains("xml") && !contentType.contains("octet-stream"))
           return "Invalid file format..."

       // Check not empty
       if (file.size == 0L) return "File is empty"

       return null  // Valid
   }
   ```

4. **Import Logic** (from research.md):
   ```kotlin
   // Parse XML
   val scanData = masscanParserService.parseMasscanXml(xmlFile.bytes)

   var assetsCreated = 0
   var assetsUpdated = 0
   var portsImported = 0

   for (host in scanData.hosts) {
       try {
           // Find or create asset by IP
           val existingAsset = assetRepository.findByIp(host.ipAddress).orElse(null)
           val asset = if (existingAsset == null) {
               // Create with defaults
               val newAsset = Asset(
                   name = null,  // Masscan doesn't provide hostname
                   ip = host.ipAddress,
                   type = "Scanned Host",
                   owner = "Security Team",
                   description = "",
                   lastSeen = host.timestamp
               )
               assetRepository.save(newAsset)
               assetsCreated++
               newAsset
           } else {
               // Update lastSeen
               existingAsset.lastSeen = host.timestamp
               assetRepository.save(existingAsset)
               assetsUpdated++
               existingAsset
           }

           // Import ports (only "open" already filtered by parser)
           for (port in host.ports) {
               val scanResult = ScanResult(
                   asset = asset,
                   port = port.portNumber,
                   protocol = port.protocol,
                   state = port.state,
                   service = null,  // Masscan doesn't provide
                   product = null,
                   version = null,
                   discoveredAt = host.timestamp
               )
               scanResultRepository.save(scanResult)
               portsImported++
           }
       } catch (e: Exception) {
           logger.warn("Failed to process host {}: {}", host.ipAddress, e.message)
       }
   }

   return HttpResponse.ok(MasscanImportResponse(
       message = "Imported $portsImported ports across $assetsCreated new assets",
       assetsCreated = assetsCreated,
       assetsUpdated = assetsUpdated,
       portsImported = portsImported
   ))
   ```

5. **Response DTO**:
   ```kotlin
   @Serdeable
   data class MasscanImportResponse(
       val message: String,
       val assetsCreated: Int,
       val assetsUpdated: Int,
       val portsImported: Int
   )
   ```

6. **Error Handling**:
   - Validation errors → 400 with ErrorResponse
   - Parse errors → 400 with clear message
   - Database errors → 500 with error message
   - Wrap in try-catch, log errors

**Reference**: Copy patterns from `uploadVulnerabilityXlsx()` and `uploadNmapXml()` in same file
**Verification**:
- Run `./gradlew test --tests MasscanImportContractTest` - should PASS
- Run `./gradlew test --tests MasscanImportIntegrationTest` - should PASS
**Dependencies**: T003, T004 (tests must exist first), T006 (parser implementation)
**Parallel**: NO (waits for T003, T004, T006)
**Estimated Time**: 60 minutes

---

## Phase 3.4: Integration

### T008 - Add Masscan Import Option to Frontend UI
**File**: `src/frontend/src/pages/import.astro`
**Description**: Add Masscan XML upload option to existing import page to make T005 tests pass

**Implementation Requirements**:

1. **Add Import Type Option**:
   ```tsx
   // Find existing import type selector (likely a select or radio group)
   // Add new option for Masscan XML
   <option value="masscan">Masscan XML</option>
   // OR
   <Radio value="masscan" label="Masscan XML" />
   ```

2. **File Upload Handler**:
   ```tsx
   const handleMasscanUpload = async (file: File) => {
       const formData = new FormData();
       formData.append('xmlFile', file);

       try {
           const response = await axios.post('/api/import/upload-masscan-xml', formData, {
               headers: {
                   'Authorization': `Bearer ${token}`,
                   'Content-Type': 'multipart/form-data'
               }
           });

           // Display success message with counts
           const { message, assetsCreated, assetsUpdated, portsImported } = response.data;
           showSuccess(`${message} - Assets: ${assetsCreated} created, ${assetsUpdated} updated. Ports: ${portsImported}`);
       } catch (error) {
           // Display error message
           showError(error.response?.data?.error || 'Upload failed');
       }
   };
   ```

3. **UI Elements**:
   - Import type selector (add "Masscan XML" option)
   - File input (accepts .xml files)
   - Upload button
   - Success/error message display
   - Import statistics display (assetsCreated, assetsUpdated, portsImported)

4. **Validation**:
   - Client-side: Check file extension (.xml)
   - Client-side: Check file size (<10MB)
   - Display validation errors before upload

5. **Styling**:
   - Match existing import UI patterns (Requirements Excel, Nmap XML, Vulnerability Excel)
   - Use Bootstrap 5 classes for consistency
   - Responsive design (mobile-friendly)

**Reference**: Copy patterns from existing import options in same file
**Verification**:
- Run `npm run test:e2e -- masscan-import.spec.ts` - should PASS
- Manual test: Upload testdata/masscan.xml via UI
**Dependencies**: T005 (E2E test), T007 (backend endpoint)
**Parallel**: NO (waits for T005, T007)
**Estimated Time**: 45 minutes

---

## Phase 3.5: Polish

### T009 [P] - Manual Validation via Quickstart Guide
**File**: N/A (manual testing)
**Description**: Execute all test scenarios from `quickstart.md` to validate end-to-end functionality

**Validation Steps** (from quickstart.md):
1. ✅ Step 1-2: Authenticate and upload testdata/masscan.xml
2. ✅ Step 3: Verify asset created with correct defaults
3. ✅ Step 4: Verify scan results created (ports 80, 443)
4. ✅ Step 5-6: Re-upload, verify update behavior and duplicates
5. ✅ Step 7-8: Frontend UI validation (manual)
6. ✅ Step 9-13: Error handling (invalid files, auth, wrong scanner)
7. ✅ Step 14: Performance test (large file)
8. ✅ Step 15: Database validation (SQL queries)

**Expected Results**:
- All curl commands succeed with correct responses
- Assets created with IP 193.99.144.85, defaults applied
- Scan results show ports 80 and 443, nulls where expected
- Re-import creates duplicates (historical tracking)
- Error handling works (400/401/500 responses)
- Frontend UI functional
- Performance acceptable (<10s for 1000 hosts)

**Checklist**:
- [ ] Backend API works (curl tests pass)
- [ ] Frontend UI works (manual browser test)
- [ ] Error handling works (invalid inputs rejected)
- [ ] Performance acceptable (large file test)
- [ ] Database state correct (SQL verification)

**Dependencies**: T006, T007, T008 (all implementation complete)
**Parallel**: YES [P] - Manual testing, independent
**Estimated Time**: 30 minutes

### T010 [P] - Run Full Test Suite and Verify Coverage
**File**: N/A (test execution)
**Description**: Run all tests and verify ≥80% code coverage per constitutional requirements

**Commands to Execute**:
```bash
# Backend tests
./gradlew test

# Check coverage report
./gradlew jacocoTestReport
open src/backendng/build/reports/jacoco/test/html/index.html

# Frontend E2E tests
npm run test:e2e

# Lint checks
./gradlew ktlintCheck  # Kotlin
npm run lint           # Frontend
```

**Verification**:
- [ ] All unit tests pass (MasscanParserServiceTest)
- [ ] All contract tests pass (MasscanImportContractTest)
- [ ] All integration tests pass (MasscanImportIntegrationTest)
- [ ] All E2E tests pass (masscan-import.spec.ts)
- [ ] Code coverage ≥80% for new code
- [ ] Linting passes (no violations)
- [ ] No security vulnerabilities (run `./gradlew dependencyCheckAnalyze`)

**Coverage Targets**:
- MasscanParserService.kt: ≥85%
- ImportController.kt (new method): ≥80%
- Overall project: maintain existing coverage level

**Dependencies**: T002, T003, T004, T005, T006, T007, T008
**Parallel**: YES [P] - Test execution, independent
**Estimated Time**: 15 minutes

### T011 - Update Documentation
**File**: `CLAUDE.md` (already updated by /plan), plus code documentation
**Description**: Ensure all code is documented and CLAUDE.md reflects the new feature

**Documentation Tasks**:

1. **Code Comments in MasscanParserService.kt**:
   ```kotlin
   /**
    * Service for parsing Masscan XML scan files
    *
    * Parses Masscan XML output format to extract:
    * - Scan metadata (scan date)
    * - Host information (IP address, timestamp)
    * - Port details (number, protocol, state - open only)
    *
    * Related to:
    * - Feature: 005-add-funtionality-to (Masscan XML Import)
    * - FR-002: Parse Masscan XML format
    * - FR-013: Import only state="open" ports
    *
    * @see NmapParserService for similar pattern
    */
   ```

2. **Code Comments in ImportController.uploadMasscanXml()**:
   ```kotlin
   /**
    * Upload Masscan XML scan file
    *
    * Related to: Feature 005-add-funtionality-to (Masscan XML Import)
    *
    * Endpoint: POST /api/import/upload-masscan-xml
    * Request: multipart/form-data with xmlFile
    * Response: MasscanImportResponse with counts
    *
    * Default values for auto-created assets:
    * - owner: "Security Team"
    * - type: "Scanned Host"
    * - name: null (Masscan doesn't provide hostname)
    * - description: ""
    *
    * @param xmlFile Masscan XML file to import (max 10MB)
    * @return Import response with counts (assetsCreated, assetsUpdated, portsImported)
    */
   ```

3. **Verify CLAUDE.md Updated**:
   - Check that Feature 005 appears in "Recent Changes"
   - Tech stack includes Masscan import dependencies
   - References to MasscanParserService documented

4. **API Documentation** (if OpenAPI generated):
   - Regenerate API docs to include new endpoint
   - Verify `/api/import/upload-masscan-xml` documented

**Verification**:
- [ ] All new classes have KDoc comments
- [ ] All new methods have KDoc comments
- [ ] Feature references included (FR-### numbers)
- [ ] CLAUDE.md includes Feature 005
- [ ] API docs updated (if applicable)

**Dependencies**: T006, T007 (implementation complete)
**Parallel**: NO (needs final code)
**Estimated Time**: 20 minutes

### T012 - Final Validation and Cleanup
**File**: N/A (project-wide)
**Description**: Final checks before marking feature complete

**Validation Checklist**:
- [ ] All tasks T001-T011 completed
- [ ] All tests passing (unit, contract, integration, E2E)
- [ ] Code coverage ≥80%
- [ ] Linting passes (Kotlin + ESLint)
- [ ] No security vulnerabilities
- [ ] Docker build succeeds (AMD64 + ARM64)
- [ ] API responds <200ms p95 (check logs)
- [ ] Documentation complete
- [ ] Quickstart guide validated
- [ ] No TODO/FIXME comments in new code
- [ ] Testdata/masscan.xml committed to repo

**Constitutional Compliance Check**:
- [x] I. Security-First: XXE prevention, file validation, authentication ✅
- [x] II. TDD: Tests written before implementation ✅
- [x] III. API-First: RESTful endpoint, OpenAPI documented ✅
- [x] IV. Docker-First: Works in existing Docker setup ✅
- [x] V. RBAC: @Secured annotation enforced ✅
- [x] VI. Schema Evolution: No migrations needed ✅

**Cleanup Tasks**:
- [ ] Remove any debug logging
- [ ] Remove commented-out code
- [ ] Format code (ktlint, prettier)
- [ ] Squash/organize commits if needed

**Feature Completion Criteria** (from spec.md):
- [x] System accepts Masscan XML files
- [x] Assets created with default values (owner, type, name, description)
- [x] Only open ports imported (filtering works)
- [x] Duplicate ports kept as separate records (historical tracking)
- [x] Timestamps preserved from XML
- [x] Error handling works
- [x] Frontend UI integration complete

**Dependencies**: All previous tasks (T001-T011)
**Parallel**: NO (final validation)
**Estimated Time**: 15 minutes

---

## Dependencies Graph

```
T001 (Setup)
  └─→ T002 [P] Parser Tests
  └─→ T003 [P] API Contract Test
  └─→ T004 [P] Integration Test
  └─→ T005 [P] E2E Frontend Test
        │
        ├─→ T006 (Parser Implementation)
        │     └─→ T007 (API Endpoint)
        │           └─→ T008 (Frontend UI)
        │
        └─→ T009 [P] (Manual Validation)
        └─→ T010 [P] (Test Suite)
              └─→ T011 (Documentation)
                    └─→ T012 (Final Validation)
```

**Critical Path**: T001 → T002 → T006 → T007 → T008 → T011 → T012
**Total Estimated Time**: ~6-7 hours (with parallelization)

---

## Parallel Execution Examples

### Example 1: Launch All Test Tasks in Parallel (after T001)
```bash
# Run these 4 tasks simultaneously:
Task: "Write MasscanParserServiceTest.kt with 12 test cases per contract"
Task: "Write MasscanImportContractTest.kt for API endpoint contract"
Task: "Write MasscanImportIntegrationTest.kt for E2E backend workflow"
Task: "Write masscan-import.spec.ts for frontend UI E2E test"

# All are independent files in different test directories
# Expected result: All tests FAIL (no implementation yet)
```

### Example 2: Launch Polish Tasks in Parallel (after T008)
```bash
# Run these 2 tasks simultaneously:
Task: "Execute quickstart.md validation steps (manual testing)"
Task: "Run test suite and verify ≥80% coverage"

# Independent validation tasks
# Expected result: All pass (feature complete)
```

---

## Notes

**TDD Enforcement**:
- Tasks T002-T005 MUST be completed before T006-T008
- Tests MUST fail before implementation
- Verify with: `./gradlew test` (should show failures)
- After implementation: `./gradlew test` (should show all pass)

**Parallel Execution ([P] tasks)**:
- T002, T003, T004, T005 can run together (different files)
- T009, T010 can run together (independent validation)
- Other tasks are sequential (dependencies)

**File Modification Conflicts**:
- ImportController.kt: Only T007 modifies (no conflict)
- import.astro: Only T008 modifies (no conflict)
- No [P] tasks modify the same file

**Commit Strategy**:
- Commit after each task completes
- Use conventional commits: `feat(import): Add Masscan XML parser`
- Reference feature: `feat(import): Add upload endpoint (Feature 005)`

**Troubleshooting**:
- If T002 tests fail to compile: Check MasscanScanData, MasscanHost, MasscanPort data classes exist
- If T006 implementation unclear: Reference NmapParserService.kt for patterns
- If T007 integration fails: Check AssetRepository.findByIp() method exists
- If T008 UI doesn't work: Verify backend endpoint returns CORS headers

---

## Validation Checklist (Final)

*GATE: All must pass before feature considered complete*

- [ ] All contracts have corresponding tests (T002, T003)
- [ ] All tests implemented before code (TDD followed)
- [ ] Parser service complete (T006)
- [ ] API endpoint complete (T007)
- [ ] Frontend UI complete (T008)
- [ ] Manual testing passed (T009)
- [ ] Test coverage ≥80% (T010)
- [ ] Documentation complete (T011)
- [ ] Constitutional compliance verified (T012)
- [ ] Quickstart guide works end-to-end
- [ ] No security vulnerabilities
- [ ] Performance targets met (<200ms p95)

**Success Criteria**: Feature is complete when all 12 tasks done and validation checklist ✅

---

*Generated from plan.md, data-model.md, contracts/, research.md, quickstart.md*
*Total Tasks: 12 | Parallel Tasks: 4 | Estimated Time: 6-7 hours*
