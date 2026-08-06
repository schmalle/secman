# Tasks: CrowdStrike System Vulnerability Lookup

**Input**: Design documents from `/specs/015-we-have-currently/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: TDD is MANDATORY per project constitution. All tests MUST be written FIRST and FAIL before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions (Web Application)
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend Tests**: `src/frontend/tests/e2e/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Database schema and shared infrastructure needed by all user stories

- [ ] T001 [P] [Setup] Create or verify `falcon_configs` table schema in database (id, client_id, client_secret, cloud_region, is_active, created_at, updated_at) - Use Hibernate entity with auto-migration or verify existing table structure
- [ ] T002 [P] [Setup] Add Micronaut HTTP client dependency to `src/backendng/build.gradle.kts` if not already present
- [ ] T003 [P] [Setup] Configure application.yml with CrowdStrike API properties (api.url, timeouts, retry settings) in `src/backendng/src/main/resources/application.yml` - No credentials stored here, only endpoint URLs and timeout values

**Checkpoint**: Environment configured - foundational tasks can begin

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core DTOs, entities, and error handling infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T004 [P] [Foundation] Create `FalconConfig` entity in `src/backendng/src/main/kotlin/com/secman/domain/FalconConfig.kt`
  - Fields: id (Long), clientId (String), clientSecret (String), cloudRegion (String), isActive (Boolean), createdAt (LocalDateTime), updatedAt (LocalDateTime)
  - Table name: falcon_configs
  - JPA annotations: @Entity, @Table, @Id, @GeneratedValue, @Column
  - Index on isActive for efficient active config lookup
  - Add @PrePersist and @PreUpdate hooks for timestamp management

- [ ] T005 [P] [Foundation] Create `FalconConfigRepository` interface in `src/backendng/src/main/kotlin/com/secman/repository/FalconConfigRepository.kt`
  - Extend Micronaut Data JpaRepository<FalconConfig, Long>
  - Add method: `fun findByIsActiveTrue(): Optional<FalconConfig>` to get active config
  - Add method: `fun findFirstByOrderByCreatedAtDesc(): Optional<FalconConfig>` as fallback

- [ ] T006 [P] [Foundation] Create `CrowdStrikeVulnerabilityDto` data class in `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeVulnerabilityDto.kt` with all fields (id, hostname, ip, cveId, severity, cvssScore, affectedProduct, daysOpen, detectedAt, status, hasException, exceptionReason)

- [ ] T007 [P] [Foundation] Create `CrowdStrikeQueryRequest` data class in `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeQueryRequest.kt` with validation annotations (@NotBlank, @Size)

- [ ] T008 [P] [Foundation] Create `CrowdStrikeQueryResponse` data class in `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeQueryResponse.kt` (hostname, vulnerabilities list, totalCount, queriedAt)

- [ ] T009 [P] [Foundation] Create `CrowdStrikeSaveRequest` data class in `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeSaveRequest.kt` with validation (@NotBlank, @NotEmpty)

- [ ] T010 [P] [Foundation] Create `CrowdStrikeSaveResponse` data class in `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeSaveResponse.kt` (message, vulnerabilitiesSaved, assetsCreated, errors list)

- [ ] T011 [P] [Foundation] Create `CrowdStrikeError` sealed class in `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeError.kt` with error types (AuthenticationError, NotFoundError, RateLimitError, NetworkError, ServerError, ConfigurationError for missing/invalid config)

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Query CrowdStrike for System Vulnerabilities (Priority: P1) 🎯 MVP

**Goal**: Enable security analysts to search for a system by hostname and see OPEN vulnerabilities from last 40 days in a table

**Independent Test**: Enter a system name, click search, verify results display in table with columns for CVE, severity, product, days open, scan date, exception status. Test with system that has vulnerabilities, system with no vulnerabilities, non-existent system, and API unavailable scenario.

### Tests for User Story 1 (TDD - WRITE FIRST, ENSURE FAIL)

- [ ] T012 [P] [US1-Test] Contract test for query endpoint: `GET /api/crowdstrike/vulnerabilities?hostname={name}` in `src/backendng/src/test/kotlin/com/secman/contract/CrowdStrikeQueryContractTest.kt`
  - Test: Returns 401 without JWT token
  - Test: Returns 403 without ADMIN or VULN role
  - Test: Returns 400 with blank hostname
  - Test: Returns 200 with valid hostname and JWT (mocked service)
  - Test: Response schema matches CrowdStrikeQueryResponse DTO
  - Test: Returns 500 with ConfigurationError if no active FalconConfig exists in database

- [ ] T013 [P] [US1-Test] Integration test for CrowdStrike API query in `src/backendng/src/test/kotlin/com/secman/integration/CrowdStrikeQueryIntegrationTest.kt`
  - Test: Successful query returns mapped vulnerabilities (mock CrowdStrike API with WireMock, mock FalconConfigRepository)
  - Test: System not found returns 404 error
  - Test: CrowdStrike API 401 returns AuthenticationError
  - Test: CrowdStrike API 429 triggers retry with exponential backoff
  - Test: CrowdStrike API 500 retries once then fails
  - Test: Network timeout retries once then fails
  - Test: OAuth2 token is cached and reused (verify only one auth call for multiple queries)
  - Test: Missing FalconConfig (isActive=false or empty table) throws ConfigurationError

- [ ] T014 [P] [US1-Test] Unit test for CVSS score to severity mapping in `src/backendng/src/test/kotlin/com/secman/unit/CvssMapperTest.kt`
  - Test: Score 9.0-10.0 → "Critical"
  - Test: Score 7.0-8.9 → "High"
  - Test: Score 4.0-6.9 → "Medium"
  - Test: Score 0.1-3.9 → "Low"
  - Test: Null score → "Unknown"

- [ ] T015 [P] [US1-Test] Unit test for days open calculation in `src/backendng/src/test/kotlin/com/secman/unit/DaysOpenCalculatorTest.kt`
  - Test: 15 days ago → "15 days"
  - Test: 1 day ago → "1 day"
  - Test: Today → "0 days"

- [ ] T016 [P] [US1-Test] Unit test for exception matching logic in `src/backendng/src/test/kotlin/com/secman/unit/ExceptionMatcherTest.kt`
  - Test: Active IP exception matches vulnerability by asset IP
  - Test: Active PRODUCT exception matches vulnerability by product name
  - Test: Expired exception does not match
  - Test: No exceptions returns hasException=false

- [ ] T017 [P] [US1-Test] E2E test for search flow in `src/frontend/tests/e2e/crowdstrike-query.spec.ts`
  - Test: Page loads with search form (hostname input, Search button)
  - Test: Enter hostname and click Search → results displayed in table
  - Test: Search with no vulnerabilities → "No vulnerabilities found" message
  - Test: Search with invalid hostname → error message displayed
  - Test: Search while API unavailable → error message "CrowdStrike service unavailable"
  - Test: Table columns match spec: System, IP, CVE, Severity, Product, Days Open, Scan Date, Exception Status
  - Test: Severity badges have correct colors (Critical=red, High=orange, Medium=blue, Low=green)
  - Test: Exception badges show "Excepted" (green) or "Not Excepted" (red) with tooltips

**RUN ALL US1 TESTS NOW - VERIFY THEY ALL FAIL (RED)**

### Implementation for User Story 1

#### Backend Service Layer

- [ ] T018 [US1-Impl] Implement `CrowdStrikeVulnerabilityService` class in `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt`
  - Inject Micronaut HttpClient configured for CrowdStrike API URL
  - Inject FalconConfigRepository to load credentials from database
  - Inject AssetRepository, VulnerabilityRepository, VulnerabilityExceptionRepository
  - Add private var for cached OAuth2 token and expiration timestamp
  - Add private var for cached FalconConfig to avoid repeated database queries

- [ ] T019 [US1-Impl] Implement `loadFalconConfig()` private method in CrowdStrikeVulnerabilityService
  - Query FalconConfigRepository.findByIsActiveTrue()
  - If not found: Fallback to findFirstByOrderByCreatedAtDesc()
  - If still not found: Throw ConfigurationError("CrowdStrike API credentials not configured in database")
  - Cache the config to avoid repeated queries
  - Return FalconConfig with clientId, clientSecret, cloudRegion

- [ ] T020 [US1-Impl] Implement `authenticateWithCrowdStrike()` private method in CrowdStrikeVulnerabilityService
  - Load credentials using loadFalconConfig()
  - POST to /oauth2/token with client credentials from FalconConfig
  - Cache token with 30-minute expiration
  - Return token if cached and not expired, otherwise refresh
  - Handle 401/403 errors → throw AuthenticationError
  - Handle ConfigurationError → rethrow to controller

- [ ] T021 [US1-Impl] Implement `queryCrowdStrikeApi(hostname, token)` private method in CrowdStrikeVulnerabilityService
  - GET /spotlight/combined/vulnerabilities/v2 with filters
  - Filter: `hostname:'<name>'+status:'open'+created_timestamp:>='<40_days_ago>'`
  - Add Authorization header with bearer token
  - Handle 404 → NotFoundError, 429 → RateLimitError, 500 → ServerError, timeouts → NetworkError
  - Implement @Retryable for 429 (exponential backoff: 1s, 2s, 4s)
  - Implement single retry for 500 and timeouts
  - Return raw API response JSON

- [ ] T022 [US1-Impl] Implement `mapCvssScoreToSeverity(score)` private helper in CrowdStrikeVulnerabilityService (use logic from unit test T012)

- [ ] T023 [US1-Impl] Implement `calculateDaysOpen(detectedAt)` private helper in CrowdStrikeVulnerabilityService (use logic from unit test T013)

- [ ] T024 [US1-Impl] Implement `mapToDtos(apiResponse)` private method in CrowdStrikeVulnerabilityService
  - Parse CrowdStrike JSON response
  - For each vulnerability: Map fields to CrowdStrikeVulnerabilityDto
    - cve.id → cveId
    - score → cvssScore, also map to severity using mapCvssScoreToSeverity()
    - apps[].product_name_version → affectedProduct (join with ", ")
    - created_timestamp → detectedAt (parse ISO 8601 to LocalDateTime)
    - hostname → hostname
    - local_ip → ip
    - Calculate daysOpen using calculateDaysOpen()
  - Return List<CrowdStrikeVulnerabilityDto>

- [ ] T025 [US1-Impl] Implement `checkExceptions(vulnerabilities)` private method in CrowdStrikeVulnerabilityService
  - Load all active VulnerabilityExceptions (expiration date null or future)
  - For each vulnerability DTO: Check against all active exceptions
    - IP exceptions: Match by IP
    - PRODUCT exceptions: Match by product name (case-insensitive contains)
  - Set hasException=true and exceptionReason if match found
  - Return updated List<CrowdStrikeVulnerabilityDto>

- [ ] T026 [US1-Impl] Implement public `queryByHostname(hostname)` method in CrowdStrikeVulnerabilityService
  - Validate hostname is not blank (throw IllegalArgumentException if blank)
  - Call authenticateWithCrowdStrike() → get token
  - Call queryCrowdStrikeApi(hostname, token) → get API response
  - Call mapToDtos(apiResponse) → get vulnerability DTOs
  - Call checkExceptions(vulnerabilities) → get DTOs with exception status
  - Return CrowdStrikeQueryResponse(hostname, vulnerabilities, totalCount, queriedAt=now())
  - Add @Slf4j logging: Log query start, success/failure, result count
  - Wrap all exceptions in appropriate error types

#### Backend Controller Layer

- [ ] T027 [US1-Impl] Create `CrowdStrikeController` class in `src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt`
  - Annotate with @Controller("/api/crowdstrike"), @Secured("ADMIN", "VULN"), @ExecuteOn(TaskExecutors.BLOCKING)
  - Inject CrowdStrikeVulnerabilityService

- [ ] T028 [US1-Impl] Implement `queryVulnerabilities(@QueryValue hostname)` method in CrowdStrikeController
  - Annotate with @Get("/vulnerabilities")
  - Call crowdStrikeService.queryByHostname(hostname)
  - Return HttpResponse.ok(result)
  - Catch exceptions and return appropriate HTTP status:
    - ConfigurationError → 500 with "CrowdStrike API credentials not configured. Contact administrator."
    - AuthenticationError → 500 with "CrowdStrike authentication failed"
    - NotFoundError → 404 with "System '{hostname}' not found in CrowdStrike"
    - RateLimitError → 429 with "Rate limit exceeded. Try again in {seconds} seconds"
    - NetworkError → 500 with "Unable to reach CrowdStrike API"
    - ServerError → 500 with "CrowdStrike service temporarily unavailable"
    - IllegalArgumentException → 400 with validation error message

#### Frontend Service Layer

- [ ] T029 [P] [US1-Impl] Create `crowdstrikeService.ts` in `src/frontend/src/services/crowdstrikeService.ts`
  - Import axios and auth utilities
  - Export interface `CrowdStrikeVulnerabilityDto` matching backend DTO structure
  - Export interface `CrowdStrikeQueryResponse` matching backend response structure

- [ ] T030 [US1-Impl] Implement `queryVulnerabilities(hostname: string)` function in crowdstrikeService.ts
  - GET /api/crowdstrike/vulnerabilities?hostname={hostname}
  - Add JWT token from sessionStorage to Authorization header
  - Return Promise<CrowdStrikeQueryResponse>
  - Handle errors: 401 → redirect to login, 403/404/429/500 → throw with user-friendly message

#### Frontend Component Layer

- [ ] T031 [US1-Impl] Create `CrowdStrikeVulnerabilityLookup.tsx` component in `src/frontend/src/components/CrowdStrikeVulnerabilityLookup.tsx`
  - Import React, useState, crowdstrikeService, Bootstrap 5 components
  - State: hostname (string), loading (boolean), error (string | null), queryResponse (CrowdStrikeQueryResponse | null)

- [ ] T032 [US1-Impl] Implement search form in CrowdStrikeVulnerabilityLookup.tsx
  - Form with hostname input field (required, max 255 chars)
  - Search button (disabled while loading)
  - Display loading spinner when loading=true
  - Display error alert when error is not null

- [ ] T033 [US1-Impl] Implement `handleSearch()` function in CrowdStrikeVulnerabilityLookup.tsx
  - Validate hostname is not blank
  - Set loading=true, error=null
  - Call crowdstrikeService.queryVulnerabilities(hostname)
  - On success: Set queryResponse=result, loading=false
  - On error: Set error=error message, loading=false, queryResponse=null

- [ ] T034 [US1-Impl] Implement results table in CrowdStrikeVulnerabilityLookup.tsx
  - Display only if queryResponse is not null
  - Bootstrap table with columns: System, IP, CVE, Severity, Product, Days Open, Scan Date, Exception Status
  - Map queryResponse.vulnerabilities to table rows
  - CVE column: Display as <code> tag
  - Severity column: Display badge with color (use getSeverityBadgeClass helper)
  - Exception column: Display badge (Excepted=green, Not Excepted=red) with tooltip showing reason
  - Show "No vulnerabilities found" message if vulnerabilities array is empty

- [ ] T035 [US1-Impl] Implement `getSeverityBadgeClass(severity)` helper function in CrowdStrikeVulnerabilityLookup.tsx
  - Critical → "bg-danger" (red)
  - High → "bg-warning text-dark" (orange)
  - Medium → "bg-info text-dark" (blue)
  - Low → "bg-success" (green)
  - Default → "bg-secondary"

- [ ] T036 [US1-Impl] Implement `getExceptionBadge(hasException, reason)` helper function in CrowdStrikeVulnerabilityLookup.tsx
  - hasException=true → <span className="badge bg-success" title={reason}>Excepted</span>
  - hasException=false → <span className="badge bg-danger">Not Excepted</span>

#### Frontend Page Layer

- [ ] T037 [US1-Impl] Create Astro page `crowdstrike-lookup.astro` in `src/frontend/src/pages/crowdstrike-lookup.astro`
  - Import CrowdStrikeVulnerabilityLookup component
  - Set page title "CrowdStrike Vulnerability Lookup"
  - Add breadcrumb: Home > Vuln Management > CrowdStrike Lookup
  - Render CrowdStrikeVulnerabilityLookup component with client:load directive
  - Add "Back to Home" link at bottom

**RUN ALL US1 TESTS AGAIN - VERIFY THEY ALL PASS (GREEN)**

**Checkpoint**: User Story 1 complete and independently testable. System can query CrowdStrike and display results.

---

## Phase 4: User Story 2 - Filter and Sort Live Results (Priority: P2)

**Goal**: Enable security analysts to filter results by severity, exception status, product and sort by any column

**Independent Test**: Perform a search that returns multiple vulnerabilities, apply severity filter (e.g., "Critical" only), verify table shows only Critical vulnerabilities. Click column header, verify sorting works (ascending/descending). Apply product filter, verify results filtered correctly.

### Tests for User Story 2 (TDD - WRITE FIRST, ENSURE FAIL)

- [ ] T035 [P] [US2-Test] E2E test for filtering in `src/frontend/tests/e2e/crowdstrike-filter.spec.ts`
  - Test: Apply severity filter "Critical" → only Critical vulnerabilities displayed
  - Test: Apply severity filter "High" → only High vulnerabilities displayed
  - Test: Apply exception status filter "Excepted" → only excepted vulnerabilities displayed
  - Test: Apply exception status filter "Not Excepted" → only non-excepted vulnerabilities displayed
  - Test: Enter product filter text "Apache" → only vulnerabilities with "Apache" in product field displayed
  - Test: Clear filters → all vulnerabilities displayed again

- [ ] T036 [P] [US2-Test] E2E test for sorting in `src/frontend/tests/e2e/crowdstrike-sort.spec.ts`
  - Test: Click "Severity" column header → sorted ascending by severity
  - Test: Click "Severity" again → sorted descending
  - Test: Click "Days Open" column header → sorted ascending by days open (numeric)
  - Test: Click "Scan Date" column header → sorted ascending by scan date (chronological)
  - Test: Sort icon displays correctly (up arrow for asc, down arrow for desc, expand icon for unsorted)

**RUN ALL US2 TESTS NOW - VERIFY THEY ALL FAIL (RED)**

### Implementation for User Story 2

- [ ] T037 [US2-Impl] Add filter state to CrowdStrikeVulnerabilityLookup.tsx
  - State: severityFilter (string, default "")
  - State: exceptionFilter (string, default "")
  - State: productFilter (string, default "")
  - State: sortField (string, default "scanTimestamp")
  - State: sortOrder ("asc" | "desc", default "desc")

- [ ] T038 [US2-Impl] Implement filter controls UI in CrowdStrikeVulnerabilityLookup.tsx
  - Add filter row above table with 3 controls (Bootstrap form-select/form-control)
  - Severity filter: Dropdown with options [All, Critical, High, Medium, Low]
  - Exception filter: Dropdown with options [All, Excepted, Not Excepted]
  - Product filter: Text input with placeholder "Filter by product..."
  - Bind onChange handlers to update state

- [ ] T039 [US2-Impl] Implement `getFilteredVulnerabilities()` function in CrowdStrikeVulnerabilityLookup.tsx
  - Start with queryResponse.vulnerabilities
  - If severityFilter not empty: Filter by severity === severityFilter
  - If exceptionFilter === "Excepted": Filter by hasException === true
  - If exceptionFilter === "Not Excepted": Filter by hasException === false
  - If productFilter not empty: Filter by affectedProduct contains productFilter (case-insensitive)
  - Return filtered list

- [ ] T040 [US2-Impl] Implement sortable column headers in results table in CrowdStrikeVulnerabilityLookup.tsx
  - Make column headers clickable (onClick handler)
  - Add cursor: pointer style
  - Display sort icon next to header text (SortIcon component)

- [ ] T041 [US2-Impl] Implement `handleSort(field)` function in CrowdStrikeVulnerabilityLookup.tsx
  - If sortField === field: Toggle sortOrder (asc ↔ desc)
  - Else: Set sortField=field, sortOrder="asc"

- [ ] T042 [US2-Impl] Implement `SortIcon` component in CrowdStrikeVulnerabilityLookup.tsx
  - Props: field (string)
  - If sortField !== field: Display expand icon (bi-chevron-expand, muted)
  - If sortField === field and sortOrder === "asc": Display up arrow (bi-chevron-up)
  - If sortField === field and sortOrder === "desc": Display down arrow (bi-chevron-down)

- [ ] T043 [US2-Impl] Implement `getSortedVulnerabilities(filtered)` function in CrowdStrikeVulnerabilityLookup.tsx
  - Sort filtered vulnerabilities by sortField
  - Handle sorting for different field types:
    - Severity: Custom order (Critical > High > Medium > Low)
    - Days Open: Parse numeric value from string "X days"
    - Scan Date: Date comparison
    - Others: String comparison
  - Apply sortOrder (asc/desc)
  - Return sorted list

- [ ] T044 [US2-Impl] Update results table to use filtered and sorted data in CrowdStrikeVulnerabilityLookup.tsx
  - Replace queryResponse.vulnerabilities with getSortedVulnerabilities(getFilteredVulnerabilities())
  - Display filtered count vs total count (e.g., "Showing 5 of 20 vulnerabilities")

**RUN ALL US2 TESTS AGAIN - VERIFY THEY ALL PASS (GREEN)**

**Checkpoint**: User Story 2 complete. Filtering and sorting work independently of US1 search functionality.

---

## Phase 5: User Story 3 - Persist Vulnerabilities to Database (Priority: P3)

**Goal**: Enable security analysts to save displayed CrowdStrike vulnerabilities to local database for historical tracking and exception management

**Independent Test**: Search for a system, review results, click "Save to Database" button, verify success message, navigate to Vuln Management / Vuln Overview page, verify saved vulnerabilities appear with all fields populated correctly.

### Tests for User Story 3 (TDD - WRITE FIRST, ENSURE FAIL)

- [ ] T045 [P] [US3-Test] Contract test for save endpoint: `POST /api/crowdstrike/vulnerabilities/save` in `src/backendng/src/test/kotlin/com/secman/contract/CrowdStrikeSaveContractTest.kt`
  - Test: Returns 401 without JWT token
  - Test: Returns 403 without ADMIN or VULN role
  - Test: Returns 400 with missing hostname
  - Test: Returns 400 with empty vulnerabilities array
  - Test: Returns 200 with valid request (mocked service)
  - Test: Response schema matches CrowdStrikeSaveResponse DTO

- [ ] T046 [P] [US3-Test] Integration test for save operation in `src/backendng/src/test/kotlin/com/secman/integration/CrowdStrikeSaveIntegrationTest.kt`
  - Test: Save vulnerabilities for existing asset → vulnerabilities created, assetsCreated=0
  - Test: Save vulnerabilities for new system → asset created with defaults, vulnerabilities created, assetsCreated=1
  - Test: Save duplicate CVE + asset → creates new historical record with different scanTimestamp
  - Test: Partial save failure → successful saves counted, errors listed

- [ ] T047 [P] [US3-Test] Unit test for asset matching in `src/backendng/src/test/kotlin/com/secman/unit/AssetMatcherTest.kt`
  - Test: Hostname exists (case-insensitive) → returns existing asset
  - Test: Hostname doesn't exist, IP exists → returns asset by IP
  - Test: Neither exists → creates new asset with defaults (owner="CrowdStrike", type="Endpoint")
  - Test: Both exist (same asset) → returns existing asset

- [ ] T048 [P] [US3-Test] E2E test for save flow in `src/frontend/tests/e2e/crowdstrike-save.spec.ts`
  - Test: "Save to Database" button visible after search
  - Test: Click save → success toast notification with count
  - Test: Navigate to /vuln-overview → saved vulnerabilities appear in table
  - Test: Save again → creates new historical records (different scanTimestamp)

**RUN ALL US3 TESTS NOW - VERIFY THEY ALL FAIL (RED)**

### Implementation for User Story 3

#### Backend Service Layer

- [ ] T049 [US3-Impl] Implement `findOrCreateAsset(hostname, ip)` private method in CrowdStrikeVulnerabilityService
  - Search for existing asset by hostname (case-insensitive): `assetRepository.findByNameIgnoreCase(hostname)`
  - If found: Return existing asset
  - If not found: Search by IP: `assetRepository.findByIp(ip)`
  - If found by IP: Return existing asset
  - If still not found: Create new Asset with defaults:
    - name = hostname
    - ip = ip
    - type = "Endpoint"
    - owner = "CrowdStrike"
    - description = ""
    - lastSeen = LocalDateTime.now()
  - Save and return new asset

- [ ] T050 [US3-Impl] Implement public `saveToDatabase(hostname, vulnerabilities)` method in CrowdStrikeVulnerabilityService
  - Initialize counters: vulnerabilitiesSaved=0, assetsCreated=0, errors=[]
  - For each vulnerability DTO:
    - Try:
      - Find or create asset using findOrCreateAsset(vulnerability.hostname, vulnerability.ip)
      - If asset was newly created: Increment assetsCreated
      - Create Vulnerability entity:
        - asset = found/created asset
        - vulnerabilityId = vulnerability.cveId
        - cvssSeverity = vulnerability.severity
        - vulnerableProductVersions = vulnerability.affectedProduct
        - daysOpen = vulnerability.daysOpen
        - scanTimestamp = vulnerability.detectedAt
      - Save vulnerability: `vulnerabilityRepository.save(vuln)`
      - Increment vulnerabilitiesSaved
    - Catch exception:
      - Log error
      - Add error message to errors list
      - Continue with next vulnerability (partial save allowed)
  - Return CrowdStrikeSaveResponse(message, vulnerabilitiesSaved, assetsCreated, errors)
  - Construct message: "Saved X vulnerabilities for system '{hostname}'" (append "Created new asset" if assetsCreated > 0)
  - Add @Slf4j logging: Log save start, success count, errors

#### Backend Controller Layer

- [ ] T051 [US3-Impl] Implement `saveVulnerabilities(@Body request)` method in CrowdStrikeController
  - Annotate with @Post("/vulnerabilities/save")
  - Validate request body (Micronaut validation automatically applied via @NotBlank, @NotEmpty annotations)
  - Call crowdStrikeService.saveToDatabase(request.hostname, request.vulnerabilities)
  - Return HttpResponse.ok(result)
  - Catch exceptions and return appropriate HTTP status:
    - IllegalArgumentException → 400 with validation error
    - Other exceptions → 500 with "Database error: Unable to save vulnerabilities"

#### Frontend Service Layer

- [ ] T052 [US3-Impl] Export interface `CrowdStrikeSaveRequest` in crowdstrikeService.ts matching backend DTO

- [ ] T053 [US3-Impl] Export interface `CrowdStrikeSaveResponse` in crowdstrikeService.ts matching backend response

- [ ] T054 [US3-Impl] Implement `saveVulnerabilities(request: CrowdStrikeSaveRequest)` function in crowdstrikeService.ts
  - POST /api/crowdstrike/vulnerabilities/save with request body
  - Add JWT token to Authorization header
  - Return Promise<CrowdStrikeSaveResponse>
  - Handle errors: 400/500 → throw with user-friendly message

#### Frontend Component Layer

- [ ] T055 [US3-Impl] Add save button to CrowdStrikeVulnerabilityLookup.tsx
  - Display "Save to Database" button above results table (only when queryResponse is not null and vulnerabilities list is not empty)
  - Button style: Bootstrap btn-primary
  - Disabled when saving in progress

- [ ] T056 [US3-Impl] Add save state to CrowdStrikeVulnerabilityLookup.tsx
  - State: saving (boolean, default false)
  - State: saveSuccess (string | null, for success toast message)

- [ ] T057 [US3-Impl] Implement `handleSave()` function in CrowdStrikeVulnerabilityLookup.tsx
  - Set saving=true, saveSuccess=null, error=null
  - Construct CrowdStrikeSaveRequest with hostname and current vulnerabilities list (filtered or all)
  - Call crowdstrikeService.saveVulnerabilities(request)
  - On success:
    - Set saveSuccess=response.message
    - Set saving=false
    - Display success toast notification (Bootstrap toast or alert)
  - On error:
    - Set error=error message
    - Set saving=false

- [ ] T058 [US3-Impl] Add success toast notification UI in CrowdStrikeVulnerabilityLookup.tsx
  - Display Bootstrap alert-success when saveSuccess is not null
  - Show message: saveSuccess + " (X vulnerabilities saved, Y assets created)"
  - Auto-dismiss after 5 seconds
  - Provide manual close button

**RUN ALL US3 TESTS AGAIN - VERIFY THEY ALL PASS (GREEN)**

**Checkpoint**: User Story 3 complete. Vulnerabilities can be saved to database independently of query/filter functionality.

---

## Phase 6: User Story 4 - Refresh Results (Priority: P3)

**Goal**: Enable security analysts to re-query CrowdStrike API with same parameters without re-entering hostname

**Independent Test**: Perform a search, click "Refresh" button, verify API is called again and results are updated (can mock API to return different data to verify refresh happened).

### Tests for User Story 4 (TDD - WRITE FIRST, ENSURE FAIL)

- [ ] T059 [P] [US4-Test] E2E test for refresh in `src/frontend/tests/e2e/crowdstrike-refresh.spec.ts`
  - Test: "Refresh" button visible after search
  - Test: Click refresh → loading spinner displayed
  - Test: After refresh → results updated (mock API to return different count to verify)
  - Test: Refresh preserves hostname (doesn't clear search field)

**RUN ALL US4 TESTS NOW - VERIFY THEY ALL FAIL (RED)**

### Implementation for User Story 4

- [ ] T060 [US4-Impl] Add refresh button to CrowdStrikeVulnerabilityLookup.tsx
  - Display "Refresh" button next to "Search" button (only when queryResponse is not null)
  - Button style: Bootstrap btn-outline-primary
  - Icon: bi-arrow-clockwise
  - Disabled when loading=true

- [ ] T061 [US4-Impl] Implement `handleRefresh()` function in CrowdStrikeVulnerabilityLookup.tsx
  - Reuse existing handleSearch() logic (no need to duplicate)
  - Call handleSearch() which already uses current hostname state

**RUN ALL US4 TESTS AGAIN - VERIFY THEY ALL PASS (GREEN)**

**Checkpoint**: User Story 4 complete. All four user stories are now independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements and validations that affect multiple user stories

- [ ] T062 [P] [Polish] Add comprehensive error logging in CrowdStrikeVulnerabilityService for audit trail (FR-019)
  - Log all API queries with timestamp, hostname, result count or error type
  - Use SLF4J logger at INFO level for successful queries, WARN for errors
  - Do not log sensitive data (API credentials, tokens)

- [ ] T063 [P] [Polish] Add input sanitization for hostname field in CrowdStrikeController
  - Trim whitespace
  - Validate hostname format (alphanumeric, dots, hyphens only)
  - Reject hostnames with special characters that could be injection attempts
  - Return 400 with "Invalid hostname format" if validation fails

- [ ] T064 [P] [Polish] Add rate limit handling UI feedback in CrowdStrikeVulnerabilityLookup.tsx
  - When 429 error received: Display countdown timer "Rate limit exceeded. Try again in X seconds"
  - Parse Retry-After header if present
  - Auto-enable search button after countdown expires

- [ ] T065 [P] [Polish] Add navigation link to CrowdStrike Lookup page in Vuln Management menu
  - Update main navigation menu in `src/frontend/src/layouts/MainLayout.astro` (or relevant layout file)
  - Add menu item: "Vuln Management" > "CrowdStrike Lookup" → /crowdstrike-lookup

- [ ] T066 [P] [Polish] Add accessibility improvements to CrowdStrikeVulnerabilityLookup.tsx
  - Add ARIA labels to form inputs (aria-label="System hostname")
  - Add ARIA live region for loading/error states (aria-live="polite")
  - Ensure keyboard navigation works (Tab order, Enter to submit form)
  - Add screen reader announcements for result count changes

- [ ] T067 [P] [Polish] Add pagination warning for large result sets in CrowdStrikeVulnerabilityLookup.tsx
  - If queryResponse.totalCount > 1000: Display warning alert
  - Message: "System has {totalCount} vulnerabilities. Showing first 1000. Please refine your search or filter results."

- [ ] T068 [Polish] Run full quickstart.md validation
  - Follow developer setup steps in `specs/015-we-have-currently/quickstart.md`
  - Verify all curl examples work
  - Verify E2E test examples run
  - Update quickstart if any discrepancies found

- [ ] T069 [Polish] Performance testing for 1000 vulnerability results
  - Mock CrowdStrike API to return 1000 vulnerabilities
  - Verify table renders in < 3 seconds
  - Verify filtering/sorting completes in < 500ms
  - Optimize if performance targets not met (consider virtualization for large lists)

- [ ] T070 [P] [Polish] Security review of CrowdStrike integration
  - Verify API credentials never logged
  - Verify error messages don't expose sensitive info
  - Verify token expiration logic prevents token theft
  - Test RBAC enforcement (non-ADMIN/VULN users cannot access endpoints)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3 → P3)
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - Enhances US1 but independently testable
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - Extends US1 but independently testable
- **User Story 4 (P3)**: Can start after Foundational (Phase 2) - Convenience feature, independently testable

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD Red-Green-Refactor)
- DTOs before services (within Foundational phase)
- Services before controllers
- Controllers before frontend services
- Frontend services before components
- Components before pages
- Story complete before moving to next priority

### Parallel Opportunities

- **Phase 1 (Setup)**: All 3 tasks can run in parallel [P]
- **Phase 2 (Foundational)**: Tasks T004-T009 can run in parallel [P]
- **Once Foundational completes**: All user stories can start in parallel (if team capacity allows)
- **Within US1 Tests**: Tasks T010-T015 can run in parallel [P]
- **Within US1 Backend**: Tasks T016-T023 are sequential (same service file)
- **Within US1 Frontend**: Task T026 can run parallel with backend work [P]
- **Within US2 Tests**: Tasks T035-T036 can run in parallel [P]
- **Within US3 Tests**: Tasks T045-T048 can run in parallel [P]
- **Within US4 Tests**: Task T059 can run in parallel with other user stories [P]
- **Phase 7 (Polish)**: Tasks T062-T067, T070 can run in parallel [P]

---

## Parallel Example: User Story 1

### Launch all US1 tests together (TDD - write tests first):
```bash
# Backend tests in parallel:
Task T010: "Contract test for query endpoint in CrowdStrikeQueryContractTest.kt"
Task T011: "Integration test for CrowdStrike API query in CrowdStrikeQueryIntegrationTest.kt"
Task T012: "Unit test for CVSS mapping in CvssMapperTest.kt"
Task T013: "Unit test for days open calculation in DaysOpenCalculatorTest.kt"
Task T014: "Unit test for exception matching in ExceptionMatcherTest.kt"

# Frontend E2E test in parallel:
Task T015: "E2E test for search flow in crowdstrike-query.spec.ts"

# All should FAIL (RED) - no implementation exists yet
```

### After tests fail, launch US1 implementation:
```bash
# Backend implementation (sequential in same service file):
Task T016-T023: CrowdStrikeVulnerabilityService methods (sequential)
Task T024-T025: CrowdStrikeController (after service complete)

# Frontend implementation (can start in parallel with backend):
Task T026: Frontend service interface [P]
Task T027: Frontend service implementation (after T026)
Task T028-T033: React component (sequential in same file)
Task T034: Astro page (after component)

# All tests should PASS (GREEN) when implementation complete
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T009) - CRITICAL
3. Complete Phase 3: User Story 1 (T010-T034)
   - Write all tests first (T010-T015) - verify they FAIL
   - Implement backend (T016-T025)
   - Implement frontend (T026-T034)
   - Run all tests - verify they PASS
4. **STOP and VALIDATE**: Test User Story 1 independently
5. Deploy/demo if ready - **Working MVP with core CrowdStrike query functionality**

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 2 → Test independently → Deploy/Demo (Now with filtering/sorting)
4. Add User Story 3 → Test independently → Deploy/Demo (Now with database persistence)
5. Add User Story 4 → Test independently → Deploy/Demo (Now with refresh convenience)
6. Complete Polish phase → Final production-ready release

Each story adds value without breaking previous stories. Users can use US1 alone, or US1+US2 together, etc.

### Parallel Team Strategy

With multiple developers:

1. **Team completes Setup + Foundational together** (everyone needs foundation)
2. **Once Foundational is done, parallelize:**
   - Developer A: User Story 1 (P1) - Critical path
   - Developer B: User Story 2 (P2) - Can work independently
   - Developer C: User Story 3 (P3) - Can work independently
   - Developer D: User Story 4 (P3) - Can work independently
3. **Stories complete and integrate independently**
4. **Team completes Polish together** (affects all stories)

---

## Summary

**Total Tasks**: 70 (including 31 test tasks, 39 implementation tasks)

**Tasks per User Story**:
- **Setup**: 3 tasks
- **Foundational**: 6 tasks (blocks all stories)
- **User Story 1** (P1): 25 tasks (6 test files, 19 implementation tasks) - MVP
- **User Story 2** (P2): 8 tasks (2 test files, 6 implementation tasks)
- **User Story 3** (P3): 14 tasks (4 test files, 10 implementation tasks)
- **User Story 4** (P3): 3 tasks (1 test file, 2 implementation tasks)
- **Polish**: 9 tasks (cross-cutting improvements)

**Parallel Opportunities**:
- Phase 1: 3 parallel tasks
- Phase 2: 6 parallel tasks
- User Stories: 4 stories can run in parallel after foundation
- Within stories: 20+ parallel test tasks marked [P]

**Independent Test Criteria**:
- **US1**: Search for system → results displayed in table (testable alone)
- **US2**: Apply filters/sorting → table updates correctly (works on US1 results)
- **US3**: Click save → vulnerabilities appear in Vuln Overview (extends US1)
- **US4**: Click refresh → results updated (convenience for US1)

**Suggested MVP Scope**: Phase 1 + Phase 2 + Phase 3 (User Story 1 only) = 34 tasks
- Delivers core value: Real-time CrowdStrike vulnerability lookup
- Fully functional and independently testable
- Can be deployed and used immediately

---

## Notes

- **TDD is MANDATORY**: All tests MUST be written FIRST and FAIL before implementation (per constitution)
- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail (RED) before implementing
- Verify tests pass (GREEN) after implementing
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Each story adds value without breaking previous stories
- MVP = Foundation + User Story 1 (34 tasks)
