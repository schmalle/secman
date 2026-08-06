# Tasks: Enhanced MCP Tools for Security Data Access

**Feature**: 009-i-want-to
**Branch**: `009-i-want-to`
**Input**: Design documents from `/specs/009-i-want-to/`
**Prerequisites**: ✅ plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory ✅
   → Tech stack: Kotlin 2.1.0, Micronaut 4.4, Hibernate JPA, MariaDB 11.4
   → Structure: Web app (backend-only), extends existing MCP server
2. Load design documents ✅
   → data-model.md: McpApiKey entity + DTOs
   → contracts/: 4 MCP tool schemas
   → research.md: Authentication, rate limiting, access control decisions
   → quickstart.md: 3 integration test scenarios + error scenarios
3. Generate tasks by category ✅
4. Apply TDD rules: Tests before implementation ✅
5. Number tasks sequentially (T001-T042) ✅
6. Generate dependency graph ✅
7. Create parallel execution examples ✅
8. Validate task completeness ✅
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in task descriptions
- All paths relative to repository root: `/Users/flake/sources/misc/secman/`

## Path Conventions
This is a **web application (backend-only)** feature:
- Backend: `src/backendng/src/main/kotlin/com/secman/`
- Tests: `src/backendng/src/test/kotlin/com/secman/`
- Contracts: `specs/009-i-want-to/contracts/`

---

## Phase 3.1: Setup & Dependencies

- [x] **T001** Review existing MCP infrastructure (Feature 006) to understand McpTool interface, McpToolRegistry, and existing tools pattern
  - **Files to review**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/McpTool.kt`, `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`, `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetAssetsTool.kt`
  - **Purpose**: Understand existing patterns before extending
  - **Completed**: ✅ Reviewed McpTool interface, McpToolRegistry, McpPermission, and McpOperation enums

- [x] **T002** Add Redis dependency to `build.gradle.kts` for rate limiting
  - **File**: `src/backendng/build.gradle.kts`
  - **Dependencies**: `implementation("io.micronaut.redis:micronaut-redis-lettuce")`
  - **Purpose**: Token bucket rate limiting backend
  - **Completed**: ✅ Added Redis dependency

- [x] **T003** [P] Add Spring Security Crypto dependency for BCrypt (if not already present from Feature 008)
  - **File**: `src/backendng/build.gradle.kts`
  - **Dependencies**: `implementation("org.springframework.security:spring-security-crypto:6.4.4")`
  - **Purpose**: API key hashing
  - **Completed**: ✅ Already present from Feature 008

---

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE PHASE 3.3

**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation in Phase 3.3**

### Contract Tests (JSON Schema Validation)

- [ ] **T004** [P] Contract test for `get_all_assets_detail` MCP tool
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/contract/GetAllAssetsDetailToolContractTest.kt`
  - **Contract**: `specs/009-i-want-to/contracts/get_all_assets_detail.json`
  - **Validates**: Input schema (page, pageSize, filters), output schema (assets array, pagination metadata)
  - **Expected**: FAIL (tool not implemented yet)

- [ ] **T005** [P] Contract test for `get_asset_scan_results` MCP tool
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/contract/GetAssetScanResultsToolContractTest.kt`
  - **Contract**: `specs/009-i-want-to/contracts/get_asset_scan_results.json`
  - **Validates**: Input schema (filters: assetId, port, service, product), output schema (scanResults array)
  - **Expected**: FAIL (tool not implemented yet)

- [ ] **T006** [P] Contract test for `get_all_vulnerabilities_detail` MCP tool
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/contract/GetAllVulnerabilitiesDetailToolContractTest.kt`
  - **Contract**: `specs/009-i-want-to/contracts/get_all_vulnerabilities_detail.json`
  - **Validates**: Input schema (filters: severity, cveId, exceptionStatus), output schema (vulnerabilities array)
  - **Expected**: FAIL (tool not implemented yet)

- [ ] **T007** [P] Contract test for `get_asset_complete_profile` MCP tool
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/contract/GetAssetCompleteProfileToolContractTest.kt`
  - **Contract**: `specs/009-i-want-to/contracts/get_asset_complete_profile.json`
  - **Validates**: Input schema (assetId, includeVulnerabilities, includeScanResults), output schema (asset with nested data)
  - **Expected**: FAIL (tool not implemented yet)

### Entity Tests

- [ ] **T008** [P] Hibernate entity test for `McpApiKey` domain entity
  - **File**: `src/backendng/src/test/kotlin/com/secman/domain/McpApiKeyTest.kt`
  - **Tests**: Field validation (keyHash format, name constraints, permissions non-empty), relationship to User, active/expiration logic
  - **Expected**: FAIL (entity not created yet)

### Service Layer Unit Tests

- [ ] **T009** [P] Unit tests for `McpAuthService` (API key validation & permission checks)
  - **File**: `src/backendng/src/test/kotlin/com/secman/service/mcp/McpAuthServiceTest.kt`
  - **Tests**: Valid API key authentication, invalid key rejection, expired key rejection, permission scope validation, workgroup extraction
  - **Mocks**: McpApiKeyRepository, UserRepository
  - **Expected**: FAIL (service not implemented yet)

- [ ] **T010** [P] Unit tests for `McpRateLimitService` (token bucket algorithm)
  - **File**: `src/backendng/src/test/kotlin/com/secman/service/mcp/McpRateLimitServiceTest.kt`
  - **Tests**: Within limit allows request, exceeds per-minute limit denies, exceeds per-hour limit denies, token refill over time, different tiers (STANDARD, HIGH, UNLIMITED)
  - **Mocks**: Redis operations (use embedded Redis or mock RedisCommands)
  - **Expected**: FAIL (service not implemented yet)

- [ ] **T011** [P] Unit tests for `McpAssetService` (asset queries with access control)
  - **File**: `src/backendng/src/test/kotlin/com/secman/service/mcp/McpAssetServiceTest.kt`
  - **Tests**: Filter by name/type/ip/owner, pagination (max 1000 items/page), total limit enforcement (100K max), workgroup access control applied, complete asset data returned (workgroups, creators)
  - **Mocks**: AssetRepository, AssetFilterService, WorkgroupRepository, UserRepository
  - **Expected**: FAIL (service not implemented yet)

- [ ] **T012** [P] Unit tests for `McpScanService` (scan result queries)
  - **File**: `src/backendng/src/test/kotlin/com/secman/service/mcp/McpScanServiceTest.kt`
  - **Tests**: Filter by assetId/port/service/product/scanType, date range filtering, pagination, access control (inherits from asset), asset name denormalization
  - **Mocks**: ScanResultRepository, AssetRepository, AssetFilterService
  - **Expected**: FAIL (service not implemented yet)

- [ ] **T013** [P] Unit tests for `McpVulnService` (vulnerability queries with exception filtering)
  - **File**: `src/backendng/src/test/kotlin/com/secman/service/mcp/McpVulnServiceTest.kt`
  - **Tests**: Filter by severity/cveId/daysOpen/exceptionStatus, exception status filtering (all, excepted_only, not_excepted), VulnerabilityException matching, date range filtering, access control
  - **Mocks**: VulnerabilityRepository, VulnerabilityExceptionRepository, AssetRepository, AssetFilterService
  - **Expected**: FAIL (service not implemented yet)

### Integration Tests (from Quickstart Scenarios)

- [ ] **T014** [P] Integration test: Security audit report generation (Quickstart Scenario 1)
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/integration/SecurityAuditIntegrationTest.kt`
  - **Scenario**: Query all CRITICAL vulnerabilities, enrich with asset details, generate report
  - **Tools used**: get_all_vulnerabilities_detail, get_asset_complete_profile
  - **Validates**: End-to-end workflow, workgroup filtering, data accuracy
  - **Expected**: FAIL (tools not implemented yet)

- [ ] **T015** [P] Integration test: Port exposure analysis (Quickstart Scenario 2)
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/integration/PortExposureIntegrationTest.kt`
  - **Scenario**: Query all assets, check scan results for risky ports (21, 23, 3389, 5900), analyze exposure
  - **Tools used**: get_all_assets_detail, get_asset_scan_results
  - **Validates**: Filtering by port, cross-tool data consistency
  - **Expected**: FAIL (tools not implemented yet)

- [ ] **T016** [P] Integration test: Compliance dashboard (Quickstart Scenario 3)
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/integration/ComplianceDashboardIntegrationTest.kt`
  - **Scenario**: Get all SERVER assets, check vulnerability status, determine compliance
  - **Tools used**: get_all_assets_detail, get_asset_complete_profile
  - **Validates**: Statistics calculation, complete profile data
  - **Expected**: FAIL (tools not implemented yet)

- [ ] **T017** [P] Integration test: Error scenarios (unauthorized access, rate limits, pagination)
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/integration/ErrorHandlingIntegrationTest.kt`
  - **Scenarios**:
    - INSUFFICIENT_PERMISSIONS (access asset in different workgroup)
    - RATE_LIMIT_EXCEEDED (exceed 5000 req/min)
    - INVALID_PAGINATION (pageSize > 1000)
    - TOTAL_RESULTS_EXCEEDED (query returning >100K results)
    - ASSET_NOT_FOUND (invalid asset ID)
  - **Expected**: FAIL (error handling not implemented yet)

- [ ] **T018** [P] Integration test: API key lifecycle (creation, usage, expiration, revocation)
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/integration/ApiKeyLifecycleIntegrationTest.kt`
  - **Scenarios**: Create key → use successfully → expire → fail authentication → revoke → fail authentication
  - **Validates**: Key hashing, expiration logic, active flag enforcement
  - **Expected**: FAIL (McpApiKey not implemented yet)

---

## Phase 3.3: Core Implementation (ONLY after all tests in 3.2 are failing)

**GATE**: Verify all tests T004-T018 are written and failing before proceeding.

### Domain Entities & Repositories

- [ ] **T019** [P] Create `McpApiKey` domain entity
  - **File**: `src/backendng/src/main/kotlin/com/secman/domain/McpApiKey.kt`
  - **Fields**: id, keyHash, userId (FK), name, permissions (ElementCollection), rateLimitTier, active, expiresAt, lastUsedAt, createdAt, updatedAt
  - **Relationships**: ManyToOne → User
  - **Annotations**: @Entity, @Table(indexes for keyHash, userId, active)
  - **Purpose**: Makes T008 pass

- [ ] **T020** [P] Create `McpApiKeyRepository` interface
  - **File**: `src/backendng/src/main/kotlin/com/secman/repository/McpApiKeyRepository.kt`
  - **Extends**: JpaRepository<McpApiKey, Long>
  - **Methods**: findByKeyHash(keyHash: String), findByUserIdAndActive(userId: Long, active: Boolean), existsByUserIdAndName(userId: Long, name: String)
  - **Purpose**: Database access for API keys

- [ ] **T021** Create `McpOperation` enum (if not exists from Feature 006)
  - **File**: `src/backendng/src/main/kotlin/com/secman/domain/McpOperation.kt`
  - **Values**: READ, WRITE, DELETE
  - **Purpose**: MCP tool operation types

### Service Layer - Authentication & Rate Limiting

- [ ] **T022** Implement `McpAuthService` (API key validation & permission checks)
  - **File**: `src/backendng/src/main/kotlin/com/secman/service/mcp/McpAuthService.kt`
  - **Methods**:
    - `validateApiKey(keyString: String): McpAuthContext` - Validate key, return auth context
    - `checkPermission(context: McpAuthContext, permission: String): Boolean` - Check if permission granted
    - `extractWorkgroups(user: User): Set<Long>` - Get user workgroup IDs
  - **Dependencies**: McpApiKeyRepository, UserRepository, PasswordEncoder (BCrypt)
  - **Purpose**: Makes T009 pass
  - **Error codes**: INVALID_API_KEY, INSUFFICIENT_PERMISSIONS

- [ ] **T023** Implement `McpRateLimitService` (token bucket rate limiting with Redis)
  - **File**: `src/backendng/src/main/kotlin/com/secman/service/mcp/McpRateLimitServiceImpl.kt`
  - **Interface**: `src/backendng/src/main/kotlin/com/secman/service/mcp/McpRateLimitService.kt`
  - **Methods**:
    - `checkRateLimit(apiKeyId: Long, tier: String): RateLimitResult` - Check if request allowed
    - `recordRequest(apiKeyId: Long)` - Record successful request
  - **Dependencies**: RedisCommands (Micronaut Redis)
  - **Algorithm**: Token bucket (5000/min, 100K/hour)
  - **Purpose**: Makes T010 pass
  - **Error codes**: RATE_LIMIT_EXCEEDED

### Service Layer - Data Queries

- [ ] **T024** Implement `McpAssetService` (asset queries with access control)
  - **File**: `src/backendng/src/main/kotlin/com/secman/service/mcp/McpAssetService.kt`
  - **Methods**:
    - `getAllAssetsDetail(context: McpAuthContext, filters: AssetFilters, pagination: McpPaginationParams): McpPaginatedResponse<AssetResponse>`
    - `getAssetCompleteProfile(context: McpAuthContext, assetId: Long, includeVulns: Boolean, includeScanResults: Boolean): AssetCompleteResponse`
  - **Dependencies**: AssetRepository, WorkgroupRepository, UserRepository, AssetFilterService
  - **Purpose**: Makes T011 pass
  - **Access control**: Apply workgroup filters via AssetFilterService

- [ ] **T025** Implement `McpScanService` (scan result queries)
  - **File**: `src/backendng/src/main/kotlin/com/secman/service/mcp/McpScanService.kt`
  - **Methods**:
    - `getAssetScanResults(context: McpAuthContext, filters: ScanResultFilters, pagination: McpPaginationParams): McpPaginatedResponse<ScanResultResponse>`
  - **Dependencies**: ScanResultRepository, AssetRepository, AssetFilterService
  - **Purpose**: Makes T012 pass
  - **Access control**: Filter scan results by accessible assets

- [ ] **T026** Implement `McpVulnService` (vulnerability queries with exception filtering)
  - **File**: `src/backendng/src/main/kotlin/com/secman/service/mcp/McpVulnService.kt`
  - **Methods**:
    - `getAllVulnerabilitiesDetail(context: McpAuthContext, filters: VulnFilters, pagination: McpPaginationParams): McpPaginatedResponse<VulnerabilityResponse>`
    - `checkVulnerabilityException(vuln: Vulnerability, asset: Asset): VulnerabilityException?`
  - **Dependencies**: VulnerabilityRepository, VulnerabilityExceptionRepository, AssetRepository, AssetFilterService
  - **Purpose**: Makes T013 pass
  - **Exception filtering**: Apply exceptionStatus filter (all, excepted_only, not_excepted)

### MCP Tools Implementation

- [ ] **T027** [P] Implement `GetAllAssetsDetailTool` (extends McpTool)
  - **File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetAllAssetsDetailTool.kt`
  - **Properties**: name = "get_all_assets_detail", description, operation = READ, inputSchema (from contract)
  - **Method**: `suspend fun execute(arguments: Map<String, Any>): McpToolResult`
  - **Dependencies**: McpAssetService, McpAuthService, McpRateLimitService
  - **Purpose**: Makes T004 pass
  - **Flow**: Validate API key → check rate limit → check permission → query assets → return response

- [ ] **T028** [P] Implement `GetAssetScanResultsTool` (extends McpTool)
  - **File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetAssetScanResultsTool.kt`
  - **Properties**: name = "get_asset_scan_results", operation = READ, inputSchema (from contract)
  - **Method**: `suspend fun execute(arguments: Map<String, Any>): McpToolResult`
  - **Dependencies**: McpScanService, McpAuthService, McpRateLimitService
  - **Purpose**: Makes T005 pass
  - **Permission**: SCANS_READ

- [ ] **T029** [P] Implement `GetAllVulnerabilitiesDetailTool` (extends McpTool)
  - **File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetAllVulnerabilitiesDetailTool.kt`
  - **Properties**: name = "get_all_vulnerabilities_detail", operation = READ, inputSchema (from contract)
  - **Method**: `suspend fun execute(arguments: Map<String, Any>): McpToolResult`
  - **Dependencies**: McpVulnService, McpAuthService, McpRateLimitService
  - **Purpose**: Makes T006 pass
  - **Permission**: VULNERABILITIES_READ

- [ ] **T030** [P] Implement `GetAssetCompleteProfileTool` (extends McpTool)
  - **File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetAssetCompleteProfileTool.kt`
  - **Properties**: name = "get_asset_complete_profile", operation = READ, inputSchema (from contract)
  - **Method**: `suspend fun execute(arguments: Map<String, Any>): McpToolResult`
  - **Dependencies**: McpAssetService, McpAuthService, McpRateLimitService
  - **Purpose**: Makes T007 pass
  - **Permission**: ASSETS_READ
  - **Nested queries**: Optionally include vulnerabilities and scan results

---

## Phase 3.4: Integration & Tool Registration

- [ ] **T031** Register new MCP tools in `McpToolRegistry`
  - **File**: `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`
  - **Action**: Add @Inject constructors for 4 new tools, register in tool list
  - **Purpose**: Make tools discoverable via MCP protocol
  - **Depends on**: T027-T030 (tools implemented)

- [ ] **T032** Add Redis configuration to `application.yml`
  - **File**: `src/backendng/src/main/resources/application.yml`
  - **Config**: Redis host, port, connection pool settings from environment variables
  - **Purpose**: Enable rate limiting Redis backend
  - **Environment**: REDIS_HOST, REDIS_PORT (default: localhost:6379)

- [ ] **T033** Update Docker Compose to include Redis service
  - **File**: `docker-compose.yml`
  - **Service**: Add redis:7-alpine, expose port 6379, volume for persistence
  - **Purpose**: Development/production Redis deployment
  - **Networking**: Same network as backend

- [ ] **T034** Create database migration script for `mcp_api_key` table
  - **File**: `src/backendng/src/main/resources/db/migration/V009__create_mcp_api_key_table.sql` (if using Flyway)
  - **Tables**: mcp_api_key, mcp_api_key_permissions (join table)
  - **Indexes**: idx_apikey_hash, idx_apikey_user, idx_apikey_active
  - **Purpose**: Hibernate auto-migration alternative (if using explicit migrations)
  - **Note**: May not be needed if Hibernate auto-migration is enabled

---

## Phase 3.5: Polish & Validation

- [ ] **T035** [P] Add unit tests for input validation edge cases
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/InputValidationTest.kt`
  - **Tests**: Invalid page numbers (negative), pageSize out of bounds (0, 1001, 10000), invalid date ranges, malformed CVE IDs, SQL injection attempts
  - **Purpose**: Security hardening

- [ ] **T036** [P] Add unit tests for pagination edge cases
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/tools/PaginationTest.kt`
  - **Tests**: Empty result set, single page, multiple pages, total limit boundary (99999, 100000, 100001), page beyond total pages
  - **Purpose**: Correct pagination behavior

- [ ] **T037** [P] Add unit tests for workgroup access control edge cases
  - **File**: `src/backendng/src/test/kotlin/com/secman/service/mcp/AccessControlTest.kt`
  - **Tests**: User with no workgroups, user with multiple workgroups, asset in multiple workgroups, orphan asset (no workgroups), manual creator ownership, scan uploader ownership
  - **Purpose**: Security validation

- [ ] **T038** Performance test: MCP tool response times (<200ms p95)
  - **File**: `src/backendng/src/test/kotlin/com/secman/mcp/performance/McpToolPerformanceTest.kt`
  - **Tests**:
    - get_all_assets_detail with 100 assets (<150ms p95)
    - get_asset_scan_results with 100 ports (<100ms p95)
    - get_all_vulnerabilities_detail with 100 vulns (<120ms p95)
    - get_asset_complete_profile with 50 vulns + 30 ports (<180ms p95)
  - **Purpose**: Validate performance requirements
  - **Tool**: JMH or custom timing

- [ ] **T039** [P] Update `CLAUDE.md` with new MCP tool documentation
  - **File**: `CLAUDE.md`
  - **Sections**: Add API Endpoints (MCP Tools section), update Recent Changes
  - **Purpose**: Agent context for future development
  - **Already done**: Basic update via update-agent-context.sh, add detailed tool descriptions

- [ ] **T040** [P] Create API key management CLI commands (optional - admin utility)
  - **File**: `src/backendng/src/main/kotlin/com/secman/cli/McpApiKeyCommands.kt`
  - **Commands**: `create-api-key`, `list-api-keys`, `revoke-api-key`
  - **Purpose**: Manual API key management without UI
  - **Note**: Future feature, may defer to separate story

- [ ] **T041** Run all integration tests from quickstart.md scenarios
  - **Action**: Execute T014-T018 integration tests against test database
  - **Validates**: All quickstart scenarios work end-to-end
  - **Purpose**: User acceptance criteria validation
  - **Command**: `./gradlew test --tests "*Integration*"`

- [ ] **T042** Final code review and refactoring
  - **Action**: Review all new code for duplication, complexity, security issues
  - **Checklist**:
    - No hardcoded secrets or API keys
    - All error paths return proper McpToolResult.Error
    - All success paths include metadata (query time, result count)
    - BCrypt cost factor = 12 (not configurable)
    - Rate limit enforcement in all tools
    - Access control in all queries
  - **Purpose**: Code quality & security audit

---

## Dependencies Graph

```
Setup (T001-T003)
  ↓
Tests Written (T004-T018) ← MUST FAIL
  ↓
Entities (T019-T021) [P]
  ↓
  ├─→ Auth Service (T022) ────┐
  ├─→ Rate Limit Service (T023) ──┤
  ├─→ Asset Service (T024) ───┤
  ├─→ Scan Service (T025) ────┤
  ├─→ Vuln Service (T026) ────┤
  └──────────────────────────┘
                 ↓
    MCP Tools (T027-T030) [P]
                 ↓
    Integration (T031-T034)
                 ↓
    Polish (T035-T042) [P except T041-T042]
```

**Critical Path**: T001 → T002-T003 → T004-T018 → T019-T021 → T022-T026 → T027-T030 → T031 → T041-T042

---

## Parallel Execution Examples

### Phase 3.2 - Launch all contract tests in parallel:
```bash
# Terminal 1
./gradlew test --tests GetAllAssetsDetailToolContractTest

# Terminal 2
./gradlew test --tests GetAssetScanResultsToolContractTest

# Terminal 3
./gradlew test --tests GetAllVulnerabilitiesDetailToolContractTest

# Terminal 4
./gradlew test --tests GetAssetCompleteProfileToolContractTest
```

### Phase 3.2 - Launch all service unit tests in parallel:
```bash
# All can run simultaneously (different test files)
./gradlew test --tests McpAuthServiceTest &
./gradlew test --tests McpRateLimitServiceTest &
./gradlew test --tests McpAssetServiceTest &
./gradlew test --tests McpScanServiceTest &
./gradlew test --tests McpVulnServiceTest &
wait
```

### Phase 3.3 - Implement entities in parallel:
```kotlin
// T019, T020, T021 can be done simultaneously by different developers
// Each operates on different files with no conflicts
```

### Phase 3.3 - Implement MCP tools in parallel:
```kotlin
// T027, T028, T029, T030 can be done simultaneously
// Each implements a different tool file, no shared code
```

---

## Validation Checklist

**GATE: Verified before marking tasks complete**

- [x] All 4 contracts have corresponding contract tests (T004-T007)
- [x] McpApiKey entity has entity test (T008)
- [x] All 5 services have unit tests (T009-T013)
- [x] All 3 quickstart scenarios have integration tests (T014-T016)
- [x] Error scenarios have integration test (T017)
- [x] API key lifecycle has integration test (T018)
- [x] All tests come before implementation (Phase 3.2 before 3.3)
- [x] Parallel tasks [P] truly independent (different files, no dependencies)
- [x] Each task specifies exact file path
- [x] No [P] task modifies same file as another [P] task
- [x] TDD order enforced: Tests (T004-T018) block Implementation (T019-T030)

---

## Notes

- **[P] tasks** = Different files, no dependencies, can run in parallel
- **TDD critical**: Verify all tests T004-T018 fail before starting T019
- **Commit strategy**: Commit after each task or logical group (e.g., all contract tests)
- **Review checkpoints**: After T018 (all tests failing), after T030 (all tools implemented), after T042 (final review)
- **Avoid**: Implementing before testing, parallel tasks on same file, skipping error scenarios

---

## Task Generation Rules Applied

✅ **From Contracts** (4 files):
- get_all_assets_detail.json → T004 (contract test) + T027 (implementation)
- get_asset_scan_results.json → T005 (contract test) + T028 (implementation)
- get_all_vulnerabilities_detail.json → T006 (contract test) + T029 (implementation)
- get_asset_complete_profile.json → T007 (contract test) + T030 (implementation)

✅ **From Data Model**:
- McpApiKey entity → T008 (entity test) + T019 (entity creation) + T020 (repository)
- DTOs (AssetResponse, etc.) → Included in service implementations (T024-T026)

✅ **From Quickstart Scenarios**:
- Scenario 1 (Security audit) → T014 integration test
- Scenario 2 (Port exposure) → T015 integration test
- Scenario 3 (Compliance) → T016 integration test
- Error scenarios → T017 integration test
- API key lifecycle → T018 integration test

✅ **Ordering**:
- Setup (T001-T003) → Tests (T004-T018) → Entities (T019-T021) → Services (T022-T026) → Tools (T027-T030) → Integration (T031-T034) → Polish (T035-T042)

---

**Status**: ✅ Tasks ready for execution (42 tasks total)
**Estimated Effort**: 2-3 weeks with TDD approach
**Next Command**: Start with T001 (review existing MCP infrastructure)
