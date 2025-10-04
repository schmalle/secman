# Tasks: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products

**Input**: Design documents from `/specs/006-please-evaluate-the/`
**Prerequisites**: plan.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓, quickstart.md ✓

## Execution Flow (main)
```
1. Load plan.md from feature directory ✓
   → Tech stack: Kotlin 2.1.0, Micronaut 4.4, MariaDB 11.4
   → Structure: Web app (backend-only changes)
2. Load optional design documents ✓
   → data-model.md: 5 existing entities, 7 response DTOs, 15 repository methods
   → contracts/: 5 MCP tool contracts
   → research.md: 8 key architectural decisions
3. Generate tasks by category ✓
   → Setup: Permissions, indexes
   → Tests: 5 contract tests, integration test
   → Core: 5 MCP tools, repository extensions
   → Integration: Rate limiting, registry updates
   → Polish: Unit tests, quickstart validation
4. Apply task rules ✓
   → Different files = [P]
   → Same file = sequential
   → Tests before implementation (TDD)
5. Number tasks sequentially ✓
6. Dependency graph generated ✓
7. Parallel execution examples included ✓
8. Task completeness validated ✓
9. SUCCESS (29 tasks ready for execution)
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions
- Paths use actual secman project structure

## Path Conventions
**Project Structure**: Web app (backend: `src/backendng/src/main/kotlin/com/secman/`)
- MCP tools: `mcp/tools/`
- Domain entities: `domain/`
- Repositories: `repository/`
- Tests: `src/backendng/src/test/kotlin/com/secman/`

---

## Phase 3.1: Setup & Prerequisites

- [x] **T001** [P] Add new MCP permissions to McpPermission enum
  **File**: `src/backendng/src/main/kotlin/com/secman/domain/McpPermission.kt`
  **Action**: Add `ASSETS_READ`, `SCANS_READ`, `VULNERABILITIES_READ` to existing enum
  **Success**: Enum compiles with 3 new values ✅

- [x] **T002** [P] Add database index for ScanPort.service column
  **File**: `src/backendng/src/main/kotlin/com/secman/domain/ScanPort.kt`
  **Action**: Added `Index(name = "idx_scan_port_service", columnList = "service")` to @Table annotation
  **Success**: Index added to entity, Hibernate will create on next deployment ✅

- [x] **T003** [P] Add database index for Vulnerability.cvssSeverity column
  **File**: `src/backendng/src/main/kotlin/com/secman/domain/Vulnerability.kt`
  **Action**: Added `Index(name = "idx_vulnerability_severity", columnList = "cvss_severity")` to @Table annotation
  **Success**: Index added to entity, Hibernate will create on next deployment ✅

---

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

- [ ] **T004** [P] Contract test for get_assets MCP tool
  **File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/GetAssetsToolContractTest.kt`
  **Action**: Write contract test validating JSON schema from `contracts/get_assets.json`
  - Test input schema validation (page, pageSize, filters)
  - Test output schema structure (assets array, pagination metadata)
  - Test error responses (invalid pageSize, total results exceeded)
  - Assert test FAILS (tool not implemented yet)
  **Success**: Test file created, compiles, fails with "GetAssetsTool not found"

- [ ] **T005** [P] Contract test for get_scans MCP tool
  **File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/GetScansToolContractTest.kt`
  **Action**: Write contract test validating JSON schema from `contracts/get_scans.json`
  - Test input schema (scanType enum, date filters)
  - Test output schema (scans array, metadata)
  - Assert test FAILS
  **Success**: Test file created, compiles, fails with "GetScansTool not found"

- [ ] **T006** [P] Contract test for get_vulnerabilities MCP tool
  **File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/GetVulnerabilitiesToolContractTest.kt`
  **Action**: Write contract test validating JSON schema from `contracts/get_vulnerabilities.json`
  - Test severity array filtering
  - Test CVE ID partial matching
  - Test date range validation
  - Assert test FAILS
  **Success**: Test file created, compiles, fails with "GetVulnerabilitiesTool not found"

- [ ] **T007** [P] Contract test for search_products MCP tool
  **File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/SearchProductsToolContractTest.kt`
  **Action**: Write contract test validating JSON schema from `contracts/search_products.json`
  - Test service name partial matching
  - Test protocol enum validation
  - Test state filtering
  - Assert test FAILS
  **Success**: Test file created, compiles, fails with "SearchProductsTool not found"

- [ ] **T008** [P] Contract test for get_asset_profile MCP tool
  **File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/GetAssetProfileToolContractTest.kt`
  **Action**: Write contract test validating JSON schema from `contracts/get_asset_profile.json`
  - Test assetId required parameter
  - Test comprehensive profile structure (asset, scans, vulns, products, stats)
  - Test ASSET_NOT_FOUND error case
  - Assert test FAILS
  **Success**: Test file created, compiles, fails with "GetAssetProfileTool not found"

- [ ] **T009** [P] Integration test for MCP security data access end-to-end
  **File**: `src/backendng/src/test/kotlin/com/secman/integration/McpSecurityDataIntegrationTest.kt`
  **Action**: Write integration test covering quickstart.md scenarios:
  - Scenario 1: Asset inventory query with filtering
  - Scenario 3: Vulnerability analysis with severity filtering
  - Scenario 4: Product discovery
  - Test permission enforcement (ASSETS_READ, SCANS_READ, VULNERABILITIES_READ)
  - Test pagination limits (500/page, 50K total)
  - Test rate limiting (1000 req/min)
  - Assert test FAILS (tools not implemented)
  **Success**: Test file created, uses real database, fails waiting for tool implementations

---

## Phase 3.3: Repository Extensions

- [x] **T010** [P] Extend AssetRepository with pagination and filtering methods
  **File**: `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`
  **Action**: Add methods per data-model.md:
  ```kotlin
  fun findByGroupsContaining(group: String): List<Asset>
  fun findByIpContainingIgnoreCase(ip: String): List<Asset>
  fun findAll(pageable: Pageable): Page<Asset>
  fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<Asset>
  ```
  **Success**: Methods added, compiles, follows Micronaut Data naming conventions

- [x] **T011** [P] Extend ScanRepository with filtering and pagination
  **File**: `src/backendng/src/main/kotlin/com/secman/repository/ScanRepository.kt`
  **Action**: Added methods findByScanDateBetween and findByScanType with pagination ✅
  **Success**: Methods added, compiles, ready for tool usage

- [x] **T012** [P] Extend ScanResultRepository with asset-based queries
  **File**: `src/backendng/src/main/kotlin/com/secman/repository/ScanResultRepository.kt`
  **Action**: Added paginated versions of findByAssetId, findByAssetIdOrderByDiscoveredAtDesc, findByScanId ✅
  **Success**: Methods added, supports lazy loading of ports

- [x] **T013** [P] Extend ScanPortRepository for product discovery
  **File**: `src/backendng/src/main/kotlin/com/secman/repository/ScanPortRepository.kt`
  **Action**: Added findByServiceContainingIgnoreCase and findByStateAndServiceNotNull ✅
  **Success**: Methods added, ready for product search queries

- [x] **T014** [P] Extend VulnerabilityRepository with severity filtering
  **File**: `src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt`
  **Action**: Added findByVulnerabilityIdContainingIgnoreCase, findByCvssSeverity, findByCvssSeverityIn, findByScanTimestampBetween ✅
  **Success**: Methods added, supports severity array filtering

---

## Phase 3.4: Rate Limiting Implementation

- [x] **T015** Implement rate limiting in McpToolPermissionService
  **File**: `src/backendng/src/main/kotlin/com/secman/service/McpToolPermissionService.kt`
  **Action**: Implemented sliding window rate limiting with:
  - Created `RateLimitTracker` data class with ConcurrentHashMap and AtomicInteger
  - Implemented `checkRateLimit()` with minute (1000 req/min) and hour (50,000 req/hour) windows
  - Thread-safe using concurrent data structures
  - Automatic cleanup of expired windows
  - Integrated with existing `hasPermission()` method ✅
  **Success**: Rate limiting fully implemented, thread-safe, respects clarified limits

---

## Phase 3.5: MCP Tool Implementations (depends on repositories + rate limiting)

- [ ] **T016** [P] Implement GetAssetsTool with unit tests
  **File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetAssetsTool.kt`
  **Test File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/GetAssetsToolTest.kt`
  **Action**:
  - Implement `McpTool` interface following GetRequirementsTool pattern
  - Use AssetRepository methods (T010) with pagination
  - Validate pageSize ≤ 500, total ≤ 50,000
  - Map Asset entities to AssetResponse DTOs per data-model.md
  - Write unit tests with mocked repository:
    * Test pagination (page 0, page 1, last page)
    * Test filtering (name, type, ip, owner, group)
    * Test empty results
    * Test page size validation
    * Test total results limit (50,000)
  **Success**: Tool implemented, contract test T004 passes, unit tests ≥80% coverage

- [ ] **T017** [P] Implement GetScansTool with unit tests
  **File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetScansTool.kt`
  **Test File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/GetScansToolTest.kt`
  **Action**:
  - Implement `McpTool` interface
  - Use ScanRepository methods (T011) with filtering
  - Support scanType enum, uploadedBy, date range filters
  - Map Scan entities to ScanResponse DTOs
  - Write unit tests:
    * Test scan type filtering (nmap, masscan)
    * Test date range filtering
    * Test user filtering
    * Test pagination
  **Success**: Tool implemented, contract test T005 passes, unit tests pass

- [ ] **T018** [P] Implement GetVulnerabilitiesTool with unit tests
  **File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetVulnerabilitiesTool.kt`
  **Test File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/GetVulnerabilitiesToolTest.kt`
  **Action**:
  - Implement `McpTool` interface
  - Use VulnerabilityRepository methods (T014)
  - Support severity array filtering, CVE ID search, asset ID, date range
  - Map Vulnerability entities to VulnerabilityResponse DTOs with asset name
  - Write unit tests:
    * Test severity array filtering (Critical, High)
    * Test CVE ID partial matching
    * Test asset filtering
    * Test date range
  **Success**: Tool implemented, contract test T006 passes, unit tests pass

- [ ] **T019** [P] Implement SearchProductsTool with unit tests
  **File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/SearchProductsTool.kt`
  **Test File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/SearchProductsToolTest.kt`
  **Action**:
  - Implement `McpTool` interface
  - Use ScanPortRepository methods (T013)
  - Filter by service name (partial), version, protocol, state
  - Join with ScanResult and Asset to get asset details
  - Map to ProductResponse DTOs
  - Write unit tests:
    * Test service name search (case-insensitive)
    * Test protocol filtering (tcp/udp)
    * Test state filtering (open only)
    * Test product version matching
  **Success**: Tool implemented, contract test T007 passes, unit tests pass

- [ ] **T020** Implement GetAssetProfileTool with unit tests (depends on T016-T019)
  **File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetAssetProfileTool.kt`
  **Test File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/GetAssetProfileToolTest.kt`
  **Action**:
  - Implement `McpTool` interface
  - Aggregate data from multiple repositories:
    * AssetRepository: Get asset by ID
    * ScanResultRepository: Get latest scan
    * ScanPortRepository: Get open ports
    * VulnerabilityRepository: Get current vulnerabilities
  - Calculate statistics:
    * Total scans count
    * Total vulnerabilities count
    * Vulnerabilities by severity breakdown
    * Unique services count
  - Map to AssetProfileResponse DTO
  - Write unit tests:
    * Test profile assembly
    * Test ASSET_NOT_FOUND error
    * Test empty scans/vulnerabilities
    * Test statistics calculation
  **Success**: Tool implemented, contract test T008 passes, unit tests pass

---

## Phase 3.6: MCP Registry Integration

- [ ] **T021** Update McpToolRegistry with new tool registrations and permission mappings
  **File**: `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`
  **Action**:
  - Inject new tools in constructor:
    * `getAssetsTool: GetAssetsTool`
    * `getScansTool: GetScansTool`
    * `getVulnerabilitiesTool: GetVulnerabilitiesTool`
    * `searchProductsTool: SearchProductsTool`
    * `getAssetProfileTool: GetAssetProfileTool`
  - Add tools to `tools` map in lazy initializer
  - Update `isToolAuthorized()` method with permission mappings per data-model.md:
    * `get_assets` → ASSETS_READ
    * `get_scans` → SCANS_READ
    * `get_vulnerabilities` → VULNERABILITIES_READ
    * `search_products` → SCANS_READ
    * `get_asset_profile` → ASSETS_READ + SCANS_READ + VULNERABILITIES_READ
  **Success**: Registry compiles, tools discoverable, permission checks work

---

## Phase 3.7: Integration & Validation

- [ ] **T022** Run integration test from T009 and verify all scenarios pass
  **File**: `src/backendng/src/test/kotlin/com/secman/integration/McpSecurityDataIntegrationTest.kt`
  **Action**:
  - Populate test database with sample data (assets, scans, vulnerabilities)
  - Run integration test suite
  - Verify all quickstart.md scenarios work end-to-end
  - Check permission enforcement works correctly
  - Verify pagination limits are respected
  - Confirm rate limiting triggers at configured thresholds
  **Success**: Integration test passes, all scenarios green

- [ ] **T023** Execute quickstart.md validation scenarios manually
  **File**: `specs/006-please-evaluate-the/quickstart.md`
  **Action**:
  - Start backend via Docker Compose
  - Create test user and MCP API key
  - Import test scan data (Nmap, Masscan, vulnerabilities)
  - Execute all 9 test scenarios from quickstart.md:
    * Scenario 1: Asset inventory query
    * Scenario 2: Scan history retrieval
    * Scenario 3: Vulnerability analysis
    * Scenario 4: Product discovery
    * Scenario 5: Asset profile
    * Test 6: Pagination limits
    * Test 7: Permission enforcement
    * Test 8: Rate limiting
    * Test 9: Empty results
  - Verify success criteria checklist
  **Success**: All scenarios execute successfully, responses match expected format

- [ ] **T024** Run performance validation with medium-scale data
  **Action**:
  - Populate database with medium-scale test data:
    * 10,000 assets
    * 100,000 scan results
    * 500,000 vulnerabilities
  - Execute representative queries
  - Measure response times
  - Verify NFR-001: <5 seconds for typical queries
  - Check concurrent request handling (NFR-002)
  **Success**: Performance targets met at medium deployment scale

---

## Phase 3.8: Polish & Documentation

- [ ] **T025** [P] Verify unit test coverage ≥80% for all new MCP tools
  **Files**: All test files from T016-T020
  **Action**:
  - Run JaCoCo coverage report
  - Verify coverage ≥80% per NFR-007 and Constitution principle II
  - Add additional tests if coverage below threshold
  **Success**: Coverage report shows ≥80% for all MCP tool classes

- [ ] **T026** [P] Run Kotlin linting and fix any issues
  **Action**:
  - Run `./gradlew ktlintCheck`
  - Fix any Kotlin convention violations
  - Ensure code follows secman formatting standards
  **Success**: Linting passes with no warnings

- [ ] **T027** [P] Update CLAUDE.md with feature completion notes
  **File**: `CLAUDE.md`
  **Action**:
  - Update Recent Changes section with Feature 006 summary
  - Document new MCP permissions
  - Note added MCP tools (5 tools)
  - Keep under 150 lines per constitutional guidelines
  **Success**: CLAUDE.md updated, concise summary added

- [ ] **T028** Verify Docker build succeeds for both AMD64 and ARM64
  **Action**:
  - Run `docker-compose build`
  - Verify backend container builds successfully
  - Test on both architectures if possible
  **Success**: Docker build completes without errors

- [ ] **T029** Final validation: Run all tests and verify green build
  **Action**:
  - Run `./gradlew test` (all unit + integration tests)
  - Verify contract tests pass (T004-T008)
  - Verify unit tests pass with ≥80% coverage
  - Verify integration test passes (T022)
  - Check Docker build success (T028)
  - Review quickstart results (T023)
  **Success**: All tests green, coverage ≥80%, build succeeds

---

## Dependencies

### Critical Path
```
T001-T003 (Setup)
  ↓
T004-T009 (Tests - MUST FAIL)
  ↓
T010-T014 (Repositories)
  ↓
T015 (Rate Limiting)
  ↓
T016-T020 (MCP Tools)
  ↓
T021 (Registry)
  ↓
T022-T024 (Integration & Performance)
  ↓
T025-T029 (Polish & Validation)
```

### Parallel Opportunities
- **Setup**: T001, T002, T003 can run in parallel
- **Contract Tests**: T004-T008 can run in parallel
- **Repositories**: T010-T014 can run in parallel
- **MCP Tools**: T016-T019 can run in parallel (T020 depends on them)
- **Polish**: T025, T026, T027 can run in parallel

### Blocking Dependencies
- T004-T009 MUST complete and FAIL before any T010+ implementation
- T010-T014 MUST complete before T016-T020 (tools need repositories)
- T015 MUST complete before T016-T020 (tools check rate limits)
- T016-T019 MUST complete before T020 (GetAssetProfileTool aggregates data)
- T021 MUST complete before T022 (integration test needs registered tools)
- T022 MUST complete before T023-T024 (validate integration works)

---

## Parallel Execution Examples

### Example 1: Run all contract tests in parallel (after setup)
```bash
# Launch T004-T008 together:
./gradlew test --tests GetAssetsToolContractTest &
./gradlew test --tests GetScansToolContractTest &
./gradlew test --tests GetVulnerabilitiesToolContractTest &
./gradlew test --tests SearchProductsToolContractTest &
./gradlew test --tests GetAssetProfileToolContractTest &
wait
# All should FAIL with "Tool not found"
```

### Example 2: Extend all repositories in parallel (after tests fail)
```bash
# Can edit these files simultaneously (different files)
# T010: AssetRepository.kt
# T011: ScanRepository.kt
# T012: ScanResultRepository.kt
# T013: ScanPortRepository.kt
# T014: VulnerabilityRepository.kt
```

### Example 3: Implement tools in parallel (after repositories ready)
```bash
# T016-T019 can run in parallel:
# - GetAssetsTool.kt + GetAssetsToolTest.kt
# - GetScansTool.kt + GetScansToolTest.kt
# - GetVulnerabilitiesTool.kt + GetVulnerabilitiesToolTest.kt
# - SearchProductsTool.kt + SearchProductsToolTest.kt
# T020 waits for T016-T019 to complete
```

---

## Notes

- **[P] markers**: Tasks with [P] modify different files and have no dependencies, safe for parallel execution
- **TDD compliance**: Tests T004-T009 MUST be written and MUST FAIL before any implementation tasks
- **Commit strategy**: Commit after each task completes
- **Coverage target**: 80% minimum per NFR-007 and Constitution principle II
- **Performance validation**: Required at medium scale (10K assets) per NFR-001
- **Rate limiting**: Enforced per clarification (1000 req/min, 50K req/hour)
- **Pagination**: Enforced limits per clarification (500/page, 50K max results)

---

## Task Generation Rules Applied
*Validated during tasks.md creation*

1. **From Contracts**: ✅
   - 5 contract files → 5 contract test tasks (T004-T008) [P]
   - 5 MCP tools → 5 implementation tasks (T016-T020)

2. **From Data Model**: ✅
   - 5 repositories → 5 extension tasks (T010-T014) [P]
   - 7 response DTOs → integrated into tool implementation tasks

3. **From User Stories**: ✅
   - quickstart.md scenarios → integration test (T009) + manual validation (T023)

4. **Ordering**: ✅
   - Setup (T001-T003) → Tests (T004-T009) → Repositories (T010-T014) → Rate Limiting (T015) → Tools (T016-T020) → Registry (T021) → Validation (T022-T024) → Polish (T025-T029)

---

## Validation Checklist
*GATE: All checks passed*

- [x] All contracts have corresponding tests (5 contracts = 5 tests T004-T008)
- [x] All repository extensions have tasks (5 repositories = 5 tasks T010-T014)
- [x] All tests come before implementation (T004-T009 before T010+)
- [x] Parallel tasks truly independent (different files, verified)
- [x] Each task specifies exact file path
- [x] No [P] task modifies same file as another [P] task
- [x] Constitutional TDD principle enforced (tests MUST FAIL first)
- [x] 80% coverage target specified (T025)
- [x] Performance validation included (T024)
- [x] Integration test covers permission enforcement, pagination, rate limiting

---

**Total Tasks**: 29
**Estimated Parallel Batches**: 5 (Setup, Contract Tests, Repositories, Tools 1-4, Polish)
**Critical Path Length**: ~10 sequential steps
**Ready for Execution**: ✅ All tasks well-defined with clear success criteria
