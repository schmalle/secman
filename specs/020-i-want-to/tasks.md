# Tasks: IP Address Mapping to Users

**Feature**: 020-i-want-to
**Input**: Design documents from `/specs/020-i-want-to/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Following TDD (Test-Driven Development) as per Constitution Principle II (NON-NEGOTIABLE). Tests are written FIRST and must FAIL before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4, US5)
- Include exact file paths in descriptions

## Path Conventions
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend Tests**: `src/frontend/tests/e2e/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [x] T001 [P] Add Apache Commons Net dependency to `src/backendng/build.gradle.kts` for CIDR parsing
- [x] T002 [P] Verify Apache Commons CSV 1.11.0 is available (already in dependencies from Feature 016)
- [x] T003 [P] Create test fixtures directory `src/backendng/src/test/kotlin/com/secman/fixtures/`

**Checkpoint**: Dependencies ready, project structure verified

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Utility Classes (Shared by All Stories)

- [ ] T004 [P] Create IpRangeType enum in `src/backendng/src/main/kotlin/com/secman/domain/IpRangeType.kt`
  - Values: SINGLE, CIDR, DASH_RANGE
  - Used by UserMapping entity extension

- [ ] T005 [P] Create IpAddressParser utility in `src/backendng/src/main/kotlin/com/secman/util/IpAddressParser.kt`
  - Methods: parse(), ipToLong(), longToIp(), parseSingleIp(), parseCidr(), parseDashRange()
  - Uses Apache Commons Net SubnetUtils for CIDR parsing
  - Custom logic for dash range parsing
  - Returns IpRangeInfo data class

- [ ] T006 Write unit tests for IpAddressParser in `src/backendng/src/test/kotlin/com/secman/util/IpAddressParserTest.kt`
  - Test single IP parsing (valid/invalid)
  - Test CIDR parsing (valid/invalid prefix, /0-/32)
  - Test dash range parsing (valid, start > end error)
  - Test IP to Long conversion (edge cases: 0.0.0.0, 255.255.255.255)
  - Test large range warning (>/16)
  - **Verify tests FAIL before implementing T005**

### Database Schema Extensions

- [ ] T007 Extend UserMapping entity in `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`
  - Add fields: ipAddress (String, nullable, 50 chars)
  - Add fields: ipRangeType (IpRangeType enum, nullable)
  - Add fields: ipRangeStart (Long, nullable)
  - Add fields: ipRangeEnd (Long, nullable)
  - Update @PrePersist to compute IP range fields via IpAddressParser
  - Update @PreUpdate similarly
  - Add validation: require awsAccountId OR ipAddress (not both null)
  - Update unique constraint to include ipAddress
  - Add indexes: idx_user_mapping_ip_address, idx_user_mapping_ip_range, idx_user_mapping_email_ip

- [ ] T008 Extend Asset entity in `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`
  - Add field: ipNumeric (Long, nullable, computed from ip field)
  - Add @PrePersist hook to compute ipNumeric via IpAddressParser.ipToLong()
  - Add @PreUpdate hook similarly
  - Add index: idx_assets_ip_numeric

- [ ] T009 Create database migration script `src/backendng/src/main/resources/db/migration/V020__add_ip_mapping_support.sql` (if not using auto-migration)
  - ALTER TABLE user_mapping ADD COLUMN ... (4 new columns)
  - ALTER TABLE assets ADD COLUMN ip_numeric ...
  - CREATE INDEX statements (4 indexes)
  - UPDATE assets SET ip_numeric = ... (backfill existing data)
  - Note: If using Hibernate auto-migration (ddl-auto=update), this is optional

### DTOs

- [ ] T010 [P] Extend UserMappingDto in `src/backendng/src/main/kotlin/com/secman/dto/UserMappingDto.kt`
  - Add fields: ipAddress, ipRangeType, ipCount (computed)
  - Update fromEntity() companion method to compute ipCount

- [ ] T011 [P] Create IpMappingUploadResult DTO in `src/backendng/src/main/kotlin/com/secman/dto/IpMappingUploadResult.kt`
  - Data classes: IpMappingUploadResult, IpMappingUploadError
  - Fields per contracts/ip-mapping-upload-csv.yaml

### Repository Extensions

- [ ] T012 Extend AssetRepository in `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`
  - Add query method: findByUserIpMappings(email: String): List<Asset>
  - Query: JOIN user_mapping ON (ipNumeric BETWEEN ipRangeStart AND ipRangeEnd)
  - Filter by email, ipRangeType (SINGLE, CIDR, DASH_RANGE)

### Test Fixtures

- [ ] T013 [P] Create IpMappingTestFixtures in `src/backendng/src/test/kotlin/com/secman/fixtures/IpMappingTestFixtures.kt`
  - Fixture data: single IP mapping, CIDR range mapping, dash range mapping
  - Asset fixtures with various IPs (in range, out of range)
  - Used by all user story tests

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Map Individual IP Addresses to User (Priority: P1) 🎯 MVP

**Goal**: Administrators can create single IP mappings (e.g., 192.168.1.100 → user@example.com) and users see assets with matching IPs in Account Vulns view

**Independent Test**: Create a single IP mapping via UI or API, verify asset with that exact IP appears in user's Account Vulns view

### Contract Tests for User Story 1 (TDD - Write FIRST)

- [ ] T014 [P] [US1] Contract test: POST /api/user-mappings (single IP) in `src/backendng/src/test/kotlin/com/secman/contract/IpMappingCrudContractTest.kt`
  - Test: Create mapping with valid single IP (192.168.1.100)
  - Test: Reject invalid IP format (999.999.999.999)
  - Test: Reject duplicate mapping
  - Test: Require ADMIN role (403 for non-admin)
  - **Verify tests FAIL before implementing T020**

- [ ] T015 [P] [US1] Contract test: GET /api/account-vulns (IP filtering) in `src/backendng/src/test/kotlin/com/secman/contract/AccountVulnsContractTest.kt`
  - Test: User with single IP mapping sees matching assets
  - Test: User does NOT see assets with non-matching IPs
  - Test: Assets without IP (null) are excluded
  - **Verify tests FAIL before implementing T023**

### Unit Tests for User Story 1 (TDD - Write FIRST)

- [ ] T016 [P] [US1] Unit test: IP validation for single IPs in `src/backendng/src/test/kotlin/com/secman/service/IpValidationTest.kt`
  - Test: Valid single IP passes (192.168.1.100)
  - Test: Invalid octet rejected (256.1.1.1)
  - Test: Malformed IP rejected (192.168.1)
  - **Verify tests FAIL before implementing IP validation in T020**

- [ ] T017 [P] [US1] Unit test: Asset IP matching (exact match) in `src/backendng/src/test/kotlin/com/secman/service/IpRangeMatcherTest.kt`
  - Test: ipNumeric == ipRangeStart for SINGLE type
  - Test: Asset 192.168.1.100 matches mapping 192.168.1.100
  - Test: Asset 192.168.1.101 does NOT match mapping 192.168.1.100
  - **Verify tests FAIL before implementing T023**

### Backend Implementation for User Story 1

- [ ] T018 [US1] Implement IP validation in UserMappingService (or new IpValidationService)
  - Path: `src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt`
  - Method: validateIpAddress(ip: String): Boolean
  - Use IpAddressParser for validation
  - Throw exception with clear message for invalid IPs

- [ ] T019 [US1] Add create endpoint for single IP mapping in UserMappingController
  - Path: `src/backendng/src/main/kotlin/com/secman/controller/UserMappingController.kt`
  - Endpoint: POST /api/user-mappings
  - Request: CreateUserMappingRequest (with ipAddress field)
  - Response: UserMappingDto
  - Validation: Check ADMIN role, validate IP format
  - Call UserMappingService to persist

- [ ] T020 [US1] Implement createUserMapping in UserMappingService
  - Path: `src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt`
  - Method: createUserMapping(request: CreateUserMappingRequest): UserMapping
  - Validate IP address (single IP format)
  - Check for duplicates
  - Parse IP to compute ipRangeStart/ipRangeEnd (same value for single IP)
  - Save to repository
  - Return entity

- [ ] T021 [US1] Extend AccountVulnsService to include IP-based filtering
  - Path: `src/backendng/src/main/kotlin/com/secman/service/AccountVulnsService.kt`
  - Method: getAccountVulns(authentication): AccountVulnsSummaryDto
  - Query: Find user's IP mappings (currently only AWS accounts)
  - Query: Find assets matching IP mappings (use AssetRepository.findByUserIpMappings)
  - Combine: AWS account assets + IP-based assets (deduplicate)
  - Group and return

### Frontend Implementation for User Story 1

- [ ] T022 [P] [US1] Create IpMappingForm component in `src/frontend/src/components/IpMappingForm.tsx`
  - Form fields: email, ipAddress (text input), domain (optional)
  - Client-side validation: Regex for single IP format
  - Submit button → calls userMappingService.create()
  - Error handling with validation messages
  - Success toast notification

- [ ] T023 [P] [US1] Update AccountVulnsView component in `src/frontend/src/components/AccountVulnsView.tsx`
  - Already exists from Feature 018 (AWS account filtering)
  - No changes needed if backend correctly merges IP + AWS results
  - Verify: Assets appear based on IP mappings (not just AWS accounts)

- [ ] T024 [US1] Add IP mapping creation to user-mappings.astro page
  - Path: `src/frontend/src/pages/user-mappings.astro`
  - Add "Add IP Mapping" button
  - Render IpMappingForm component in modal or inline
  - Admin-only visibility (check hasRole('ADMIN'))

- [ ] T025 [US1] Extend userMappingService.ts with IP creation method
  - Path: `src/frontend/src/services/userMappingService.ts`
  - Method: createIpMapping(request): Promise<UserMappingDto>
  - POST to /api/user-mappings
  - Handle errors (400 validation, 403 forbidden, 409 duplicate)

### E2E Tests for User Story 1

- [ ] T026 [US1] E2E test: Create single IP mapping and verify access in `src/frontend/tests/e2e/ip-mapping-single.spec.ts`
  - Login as admin
  - Create IP mapping: testuser@example.com → 192.168.1.100
  - Create asset with IP 192.168.1.100
  - Login as testuser
  - Navigate to Account Vulns page
  - Verify asset appears in list
  - Verify asset with different IP (10.0.0.50) does NOT appear

**Checkpoint**: At this point, User Story 1 (single IP mapping) should be fully functional and testable independently

---

## Phase 4: User Story 2 - Map IP Address Ranges to User (Priority: P1)

**Goal**: Administrators can create CIDR ranges (e.g., 192.168.1.0/24) and dash ranges (e.g., 10.0.0.1-10.0.0.255). Users see all assets with IPs in those ranges.

**Independent Test**: Create a CIDR range mapping (192.168.1.0/24), verify assets with IPs 192.168.1.1, 192.168.1.50, 192.168.1.255 all appear in user's Account Vulns view

### Contract Tests for User Story 2 (TDD - Write FIRST)

- [ ] T027 [P] [US2] Contract test: POST /api/user-mappings (CIDR range) in `src/backendng/src/test/kotlin/com/secman/contract/IpMappingCrudContractTest.kt`
  - Test: Create mapping with valid CIDR (10.0.0.0/24)
  - Test: Reject invalid CIDR prefix (/33)
  - Test: Normalize CIDR with host bits (192.168.1.50/24 → 192.168.1.0/24)
  - **Verify tests FAIL before implementing T034**

- [ ] T028 [P] [US2] Contract test: POST /api/user-mappings (dash range) in same file
  - Test: Create mapping with valid dash range (172.16.0.1-172.16.0.100)
  - Test: Reject invalid dash range (start > end)
  - **Verify tests FAIL before implementing T034**

### Unit Tests for User Story 2 (TDD - Write FIRST)

- [ ] T029 [P] [US2] Unit test: CIDR range matching in `src/backendng/src/test/kotlin/com/secman/service/IpRangeMatcherTest.kt`
  - Test: Asset 192.168.1.50 matches range 192.168.1.0/24
  - Test: Asset 192.168.2.50 does NOT match range 192.168.1.0/24
  - Test: Boundary conditions (192.168.1.0, 192.168.1.255 in range)
  - **Verify tests FAIL before implementing T035**

- [ ] T030 [P] [US2] Unit test: Dash range matching in same file
  - Test: Asset 10.0.0.50 matches range 10.0.0.1-10.0.0.100
  - Test: Asset 10.0.0.101 does NOT match range
  - Test: Boundary conditions (10.0.0.1, 10.0.0.100 in range)
  - **Verify tests FAIL before implementing T035**

- [ ] T031 [P] [US2] Unit test: Large range warning in `src/backendng/src/test/kotlin/com/secman/service/UserMappingServiceTest.kt`
  - Test: Range >/16 logs warning
  - Test: Range 0.0.0.0/0 is rejected
  - **Verify tests FAIL before implementing T033**

### Backend Implementation for User Story 2

- [ ] T032 [US2] Extend IP validation in UserMappingService for CIDR and dash ranges
  - Path: `src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt`
  - Update validateIpAddress() to detect format (CIDR vs dash vs single)
  - Validate CIDR prefix (0-32)
  - Validate dash range (start <= end)
  - Reject 0.0.0.0/0
  - Log warning for ranges >/16

- [ ] T033 [US2] Update createUserMapping to handle CIDR and dash ranges
  - Path: `src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt`
  - Call IpAddressParser.parse() to get IpRangeInfo
  - Set ipRangeType (CIDR or DASH_RANGE)
  - Set ipRangeStart/ipRangeEnd from IpRangeInfo
  - Validation and persistence logic

- [ ] T034 [US2] Update UserMappingController to accept CIDR/dash ranges
  - Path: `src/backendng/src/main/kotlin/com/secman/controller/UserMappingController.kt`
  - No changes needed (same endpoint POST /api/user-mappings)
  - Request validation updated to accept CIDR and dash formats

- [ ] T035 [US2] Update AccountVulnsService IP filtering query for ranges
  - Path: `src/backendng/src/main/kotlin/com/secman/service/AccountVulnsService.kt`
  - Ensure findByUserIpMappings query handles BETWEEN for ranges
  - Test: Asset ipNumeric BETWEEN ipRangeStart AND ipRangeEnd
  - Already implemented in T012 repository method, verify it works

### Frontend Implementation for User Story 2

- [ ] T036 [P] [US2] Update IpMappingForm to accept CIDR and dash ranges
  - Path: `src/frontend/src/components/IpMappingForm.tsx`
  - Update validation regex to accept /CIDR and -dash formats
  - Add placeholder text: "192.168.1.0/24 or 10.0.0.1-10.0.0.255"
  - Add example text below input field

- [ ] T037 [P] [US2] Add client-side CIDR validation
  - Path: `src/frontend/src/components/IpMappingForm.tsx`
  - Validate prefix length (0-32)
  - Show error: "CIDR prefix must be 0-32"

- [ ] T038 [P] [US2] Add client-side dash range validation
  - Path: `src/frontend/src/components/IpMappingForm.tsx`
  - Validate start IP <= end IP (convert to numeric for comparison)
  - Show error: "Start IP must be <= end IP"

- [ ] T039 [P] [US2] Add IpRangeDisplay component in `src/frontend/src/components/IpRangeDisplay.tsx`
  - Display IP range with type badge (Single, CIDR, Dash Range)
  - Show IP count (e.g., "256 IPs" for /24)
  - Color-coded badges (blue for CIDR, green for dash, gray for single)

- [ ] T040 [US2] Update UserMappingTable to display IP ranges
  - Path: `src/frontend/src/components/UserMappingTable.tsx`
  - Add IP/Range column (use IpRangeDisplay component)
  - Add Type column (Single IP, CIDR, Dash Range)
  - Add IPs column (count from ipCount field)

### E2E Tests for User Story 2

- [ ] T041 [US2] E2E test: CIDR range mapping in `src/frontend/tests/e2e/ip-mapping-cidr.spec.ts`
  - Login as admin
  - Create CIDR mapping: user@example.com → 10.0.0.0/24
  - Create 3 assets: 10.0.0.1, 10.0.0.128, 10.0.0.255 (all in range)
  - Create 1 asset: 10.0.1.1 (out of range)
  - Login as user
  - Verify 3 assets appear, 1 does not

- [ ] T042 [US2] E2E test: Dash range mapping in `src/frontend/tests/e2e/ip-mapping-dash.spec.ts`
  - Login as admin
  - Create dash range: user@example.com → 172.16.0.1-172.16.0.100
  - Create assets within and outside range
  - Verify correct filtering

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently. Users can create single IPs and ranges.

---

## Phase 5: User Story 3 - Upload IP Mappings via CSV/Excel (Priority: P2)

**Goal**: Administrators can bulk upload IP mappings (mix of single, CIDR, dash) via CSV or Excel file. Invalid rows are skipped with error reporting.

**Independent Test**: Upload a CSV file with 100 IP mappings (mix of formats), verify all valid mappings are imported and error summary is displayed for invalid rows

### Contract Tests for User Story 3 (TDD - Write FIRST)

- [ ] T043 [P] [US3] Contract test: POST /api/import/upload-ip-mappings-csv in `src/backendng/src/test/kotlin/com/secman/contract/IpMappingUploadContractTest.kt`
  - Test: Upload valid CSV with mixed IP formats
  - Test: Reject file >10MB
  - Test: Reject file with wrong extension (.txt)
  - Test: Handle empty file gracefully
  - Test: Skip invalid rows, import valid ones
  - Test: Return ImportResult with counts and errors
  - **Verify tests FAIL before implementing T050**

- [ ] T044 [P] [US3] Contract test: GET /api/import/ip-mapping-template-csv in same file
  - Test: Download returns CSV with example data
  - Test: Content-Type is text/csv
  - Test: Content-Disposition header present
  - **Verify tests FAIL before implementing T054**

### Unit Tests for User Story 3 (TDD - Write FIRST)

- [ ] T045 [P] [US3] Unit test: CSV IP mapping parser in `src/backendng/src/test/kotlin/com/secman/service/CSVIpMappingParserTest.kt`
  - Test: Parse CSV with single IP rows
  - Test: Parse CSV with CIDR rows
  - Test: Parse CSV with dash range rows
  - Test: Parse CSV with mixed formats (all three in one file)
  - Test: Detect delimiter (comma, semicolon, tab)
  - Test: Detect encoding (UTF-8 BOM, ISO-8859-1)
  - Test: Skip row with invalid IP, report error
  - Test: Skip row with invalid email, report error
  - Test: Skip duplicate rows (within file)
  - Test: Skip rows duplicating existing database entries
  - Test: Handle CSV with no data rows (header only)
  - **Verify tests FAIL before implementing T048**

### Backend Implementation for User Story 3

- [ ] T046 [P] [US3] Create CSVIpMappingParser utility in `src/backendng/src/main/kotlin/com/secman/service/CSVIpMappingParser.kt`
  - Reuse Feature 016 CSV parsing infrastructure (encoding detection, delimiter detection)
  - Method: parse(csvFile: InputStream): List<ParsedIpMapping>
  - Validate each row: email format, IP format (single/CIDR/dash), domain format
  - Use IpAddressParser for IP validation
  - Return list of valid mappings + list of errors (row number, reason)

- [ ] T047 [P] [US3] Create ExcelIpMappingParser utility in `src/backendng/src/main/kotlin/com/secman/service/ExcelIpMappingParser.kt`
  - Similar to CSVIpMappingParser but uses Apache POI for .xlsx
  - Method: parse(xlsxFile: InputStream): List<ParsedIpMapping>
  - Handle Excel-specific edge cases (scientific notation, empty cells)

- [ ] T048 [US3] Implement CSV upload endpoint in ImportController
  - Path: `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt`
  - Endpoint: POST /api/import/upload-ip-mappings-csv
  - Request: MultipartFileUpload (csvFile)
  - Validate: File size <=10MB, extension .csv, not empty
  - Call CSVIpMappingParser.parse()
  - For valid mappings: Call UserMappingService.createBatch()
  - Return: IpMappingUploadResult (imported count, skipped count, errors)

- [ ] T049 [US3] Implement Excel upload endpoint in ImportController
  - Endpoint: POST /api/import/upload-ip-mappings-xlsx
  - Similar to T048 but for .xlsx files
  - Use ExcelIpMappingParser

- [ ] T050 [US3] Implement batch creation in UserMappingService
  - Method: createBatch(mappings: List<ParsedIpMapping>): BatchResult
  - Check for duplicates against existing database entries
  - Use repository.saveAll() for efficiency
  - Return: List of created entities + list of skipped (duplicates)

- [ ] T051 [US3] Implement CSV template download endpoint
  - Endpoint: GET /api/import/ip-mapping-template-csv
  - Generate CSV with headers: email, ip_address, domain
  - Add 3 example rows: single IP, CIDR, dash range
  - Set Content-Type and Content-Disposition headers
  - Return file for download

- [ ] T052 [US3] Implement Excel template download endpoint
  - Endpoint: GET /api/import/ip-mapping-template-xlsx
  - Generate Excel file with same structure as CSV template
  - Use Apache POI to create .xlsx

### Frontend Implementation for User Story 3

- [ ] T053 [P] [US3] Create IpMappingUpload component in `src/frontend/src/components/IpMappingUpload.tsx`
  - File input with .csv and .xlsx accept filters
  - "Upload CSV" and "Upload Excel" buttons (or tabs)
  - Client-side validation: File size <=10MB, correct extension
  - Upload progress indicator (spinner)
  - Display upload result: Imported count, skipped count, error details
  - Error table with columns: Row, Reason, IP Address, Email

- [ ] T054 [P] [US3] Add download template buttons
  - Path: `src/frontend/src/components/IpMappingUpload.tsx`
  - "Download CSV Template" button → calls userMappingService.downloadCSVTemplate()
  - "Download Excel Template" button → calls userMappingService.downloadExcelTemplate()
  - Handle blob download with proper filename

- [ ] T055 [US3] Extend userMappingService.ts with upload methods
  - Path: `src/frontend/src/services/userMappingService.ts`
  - Method: uploadCSV(file): Promise<IpMappingUploadResult>
  - Method: uploadExcel(file): Promise<IpMappingUploadResult>
  - Method: downloadCSVTemplate(): Promise<Blob>
  - Method: downloadExcelTemplate(): Promise<Blob>
  - Handle multipart/form-data for file uploads

- [ ] T056 [US3] Add upload UI to user-mappings.astro page
  - Path: `src/frontend/src/pages/user-mappings.astro`
  - Render IpMappingUpload component
  - Position: Below manual "Add IP Mapping" form, above table
  - Admin-only visibility

### E2E Tests for User Story 3

- [ ] T057 [US3] E2E test: CSV upload with mixed formats in `src/frontend/tests/e2e/ip-mapping-upload-csv.spec.ts`
  - Login as admin
  - Create test CSV with 10 rows (mix of single, CIDR, dash)
  - Upload CSV
  - Verify success message shows "10 imported"
  - Verify mappings appear in table
  - Download template, verify format

- [ ] T058 [US3] E2E test: CSV upload with errors in same file
  - Create CSV with 5 valid rows, 3 invalid rows (bad IP, bad email, duplicate)
  - Upload CSV
  - Verify "5 imported, 3 skipped"
  - Verify error details table shows row numbers and reasons

**Checkpoint**: All P1 and P2 user stories complete. Users can create IP mappings manually (US1, US2) and bulk upload (US3).

---

## Phase 6: User Story 4 - View and Manage IP Mappings (Priority: P2)

**Goal**: Administrators can view all IP mappings in a searchable, filterable, paginated table. They can edit and delete existing mappings.

**Independent Test**: Create 100 IP mappings, search for specific email, filter by domain, edit an IP address, delete a mapping. Verify all operations work correctly.

### Contract Tests for User Story 4 (TDD - Write FIRST)

- [ ] T059 [P] [US4] Contract test: GET /api/user-mappings (list with pagination) in `src/backendng/src/test/kotlin/com/secman/contract/IpMappingCrudContractTest.kt`
  - Test: List returns paginated results (default 20 per page)
  - Test: Search by email filters correctly
  - Test: Filter by domain works
  - Test: Filter by mappingType (AWS, IP, ALL) works
  - **Verify tests FAIL before implementing T065**

- [ ] T060 [P] [US4] Contract test: PUT /api/user-mappings/{id} in same file
  - Test: Update IP address successfully
  - Test: Update email successfully
  - Test: Reject invalid IP format
  - Test: Require ADMIN role
  - **Verify tests FAIL before implementing T067**

- [ ] T061 [P] [US4] Contract test: DELETE /api/user-mappings/{id} in same file
  - Test: Delete mapping successfully
  - Test: Return 404 if mapping not found
  - Test: Require ADMIN role
  - **Verify tests FAIL before implementing T068**

### Backend Implementation for User Story 4

- [ ] T062 [US4] Implement list endpoint with pagination in UserMappingController
  - Endpoint: GET /api/user-mappings
  - Query params: page, size, email (search), domain (filter), mappingType (filter)
  - Return: Page<UserMappingDto> with totalElements, totalPages, content
  - Default page size: 20

- [ ] T063 [US4] Implement search and filter in UserMappingRepository
  - Path: `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt`
  - Method: findByFilters(email, domain, mappingType, pageable): Page<UserMapping>
  - Dynamic query: Apply filters conditionally
  - mappingType filter: AWS (awsAccountId not null), IP (ipAddress not null), ALL (no filter)

- [ ] T064 [US4] Implement update endpoint in UserMappingController
  - Endpoint: PUT /api/user-mappings/{id}
  - Request: UpdateUserMappingRequest (email, awsAccountId, ipAddress, domain)
  - Validate: Check ADMIN role, validate IP if provided
  - Call UserMappingService.update()
  - Return: Updated UserMappingDto

- [ ] T065 [US4] Implement update in UserMappingService
  - Method: update(id, request): UserMapping
  - Find existing mapping by ID (throw 404 if not found)
  - Update fields from request
  - Re-compute ipRangeStart/ipRangeEnd if ipAddress changed
  - Validate: Still require awsAccountId OR ipAddress
  - Save and return

- [ ] T066 [US4] Implement delete endpoint in UserMappingController
  - Endpoint: DELETE /api/user-mappings/{id}
  - Validate: Check ADMIN role
  - Call UserMappingService.delete()
  - Return: 204 No Content

- [ ] T067 [US4] Implement delete in UserMappingService
  - Method: delete(id): void
  - Find existing mapping by ID (throw 404 if not found)
  - Delete from repository
  - Optional: Log deletion for audit trail

### Frontend Implementation for User Story 4

- [ ] T068 [P] [US4] Update UserMappingTable with search and filter
  - Path: `src/frontend/src/components/UserMappingTable.tsx`
  - Add search input for email (debounced)
  - Add domain filter dropdown (load unique domains from API)
  - Add mapping type filter: All, AWS Only, IP Only
  - Add pagination controls (page number, size selector)
  - Update on filter change → call userMappingService.list(filters)

- [ ] T069 [P] [US4] Add edit functionality to UserMappingTable
  - Add "Edit" button on each row
  - Click → open IpMappingForm in edit mode (pre-filled with current values)
  - Submit → call userMappingService.update(id, data)
  - Success → refresh table, show toast

- [ ] T070 [P] [US4] Add delete functionality to UserMappingTable
  - Add "Delete" button on each row
  - Click → show confirmation modal: "Delete mapping for {email} → {ip/aws}?"
  - Confirm → call userMappingService.delete(id)
  - Success → refresh table, show toast

- [ ] T071 [US4] Extend userMappingService.ts with list/update/delete methods
  - Path: `src/frontend/src/services/userMappingService.ts`
  - Method: list(page, size, email, domain, mappingType): Promise<Page<UserMappingDto>>
  - Method: update(id, request): Promise<UserMappingDto>
  - Method: delete(id): Promise<void>

### E2E Tests for User Story 4

- [ ] T072 [US4] E2E test: Search and filter in `src/frontend/tests/e2e/ip-mapping-search.spec.ts`
  - Login as admin
  - Create 50 IP mappings with various emails and domains
  - Search for specific email → verify filtering
  - Filter by domain → verify results
  - Filter by mapping type (IP only) → verify AWS mappings hidden

- [ ] T073 [US4] E2E test: Edit mapping in same file
  - Create IP mapping with 192.168.1.100
  - Click edit, change to 192.168.1.200
  - Save, verify updated IP shows in table
  - Verify old IP no longer grants access, new IP does

- [ ] T074 [US4] E2E test: Delete mapping in same file
  - Create IP mapping
  - Click delete, confirm
  - Verify mapping removed from table
  - Verify user no longer has access to assets with that IP

**Checkpoint**: All CRUD operations complete. Administrators can fully manage IP mappings via UI.

---

## Phase 7: User Story 5 - Combine IP and AWS Account Mappings for Access Control (Priority: P3)

**Goal**: Users with both IP mappings AND AWS account mappings see assets from BOTH sources in a unified Account Vulns view. No duplicates.

**Independent Test**: Create user with AWS account mapping (123456789012) and IP mapping (192.168.1.0/24). Create assets matching each. Verify both appear in Account Vulns view, deduplicated.

### Contract Tests for User Story 5 (TDD - Write FIRST)

- [ ] T075 [P] [US5] Contract test: GET /api/account-vulns (combined filtering) in `src/backendng/src/test/kotlin/com/secman/contract/AccountVulnsContractTest.kt`
  - Test: User with AWS mapping sees AWS assets
  - Test: User with IP mapping sees IP assets
  - Test: User with BOTH sees assets from both sources
  - Test: Asset matching BOTH AWS and IP appears only once (deduplicated)
  - Test: User with only IP (no AWS) does not get "no mappings" error
  - **Verify tests FAIL before implementing T079**

### Unit Tests for User Story 5 (TDD - Write FIRST)

- [ ] T076 [P] [US5] Unit test: Asset deduplication in `src/backendng/src/test/kotlin/com/secman/service/AccountVulnsServiceTest.kt`
  - Test: Asset with cloudAccountId=123 AND ip=192.168.1.50 matching both mappings
  - Test: Returns single asset instance (not duplicate)
  - Test: Deduplication by asset ID
  - **Verify tests FAIL before implementing T079**

### Backend Implementation for User Story 5

- [ ] T077 [US5] Update AccountVulnsService to query both AWS and IP mappings
  - Path: `src/backendng/src/main/kotlin/com/secman/service/AccountVulnsService.kt`
  - Method: getAccountVulns(authentication): AccountVulnsSummaryDto
  - Query 1: Find user's AWS account mappings (existing from Feature 018)
  - Query 2: Find user's IP mappings (new)
  - Query 3: Find assets matching AWS accounts (existing)
  - Query 4: Find assets matching IP ranges (new, using AssetRepository.findByUserIpMappings)
  - Combine: Merge asset lists, deduplicate by asset ID
  - Group: By AWS account AND/OR IP range
  - Return: Unified summary

- [ ] T078 [US5] Handle users with only IP mappings (no AWS)
  - Update error handling in AccountVulnsService
  - Current: Returns 404 "no mappings" if no AWS account mappings
  - New: Check if user has IP mappings OR AWS mappings
  - If neither: Return 404 "no mappings"
  - If only IP: Return IP-based results
  - If only AWS: Return AWS-based results (existing behavior)
  - If both: Return combined results

### Frontend Implementation for User Story 5

- [ ] T079 [P] [US5] Update AccountVulnsView to handle combined grouping
  - Path: `src/frontend/src/components/AccountVulnsView.tsx`
  - Current: Groups assets by AWS account
  - New: Accept grouping by AWS account OR IP range OR both
  - Display: "AWS Account: 123456789012" and "IP Range: 192.168.1.0/24" as separate groups
  - Assets in both: Appear in both groups (user sees full context)

- [ ] T080 [P] [US5] Update summary stats to include IP mappings
  - Path: `src/frontend/src/components/AccountVulnsView.tsx`
  - Current: "X AWS Accounts, Y Assets, Z Vulnerabilities"
  - New: "X AWS Accounts, W IP Ranges, Y Assets, Z Vulnerabilities"
  - Tooltips: Explain that assets may match multiple sources

### E2E Tests for User Story 5

- [ ] T081 [US5] E2E test: Combined access control in `src/frontend/tests/e2e/ip-mapping-combined.spec.ts`
  - Login as admin
  - Create user "hybriduser@example.com"
  - Create AWS mapping: hybriduser → 123456789012
  - Create IP mapping: hybriduser → 192.168.1.0/24
  - Create asset A: cloudAccountId=123456789012, ip=10.0.0.50 (AWS only)
  - Create asset B: ip=192.168.1.100 (IP only)
  - Create asset C: cloudAccountId=123456789012, ip=192.168.1.200 (both)
  - Login as hybriduser
  - Navigate to Account Vulns
  - Verify: All 3 assets appear
  - Verify: Asset C appears only once (deduplicated)
  - Verify: Summary shows "1 AWS Account, 1 IP Range, 3 Assets"

**Checkpoint**: All user stories complete. IP and AWS account mappings work independently and together.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T082 [P] Add large range warning modal in IpMappingForm
  - Path: `src/frontend/src/components/IpMappingForm.tsx`
  - Detect: CIDR range >/16 or dash range >65536 IPs
  - Show modal: "This range contains X IPs. Are you sure?"
  - Options: "Yes, map entire range" or "No, let me refine"
  - Block: 0.0.0.0/0 with error message

- [ ] T083 [P] Add delete impact warning (optional enhancement)
  - Path: `src/frontend/src/components/UserMappingTable.tsx`
  - Before deleting IP mapping, query: How many assets currently match?
  - Show in confirmation modal: "This will affect access to X assets. Continue?"

- [ ] T084 [P] Performance optimization: Add query caching for IP range lookups
  - Path: `src/backendng/src/main/kotlin/com/secman/service/AccountVulnsService.kt`
  - Use @Cacheable annotation on getAccountVulns (if Micronaut caching configured)
  - Cache key: User email + timestamp bucket (e.g., 5-minute buckets)
  - Invalidate on UserMapping create/update/delete

- [ ] T085 [P] Add API documentation updates
  - Update OpenAPI spec files in `contracts/` if changes made during implementation
  - Regenerate Swagger UI documentation
  - Update quickstart.md with any new endpoints or changed behavior

- [ ] T086 [P] Update CLAUDE.md with Feature 020 details
  - Path: `/Users/flake/sources/misc/secman/CLAUDE.md`
  - Add Feature 020 section with:
    - Entity changes (UserMapping extension, Asset extension)
    - New endpoints (IP mapping upload, CRUD)
    - Query patterns (IP range matching)
    - Statistics (lines of code, test coverage)

- [ ] T087 Code cleanup and refactoring
  - Remove unused imports
  - Apply consistent formatting (kotlinter or spotless)
  - Ensure all error messages are user-friendly
  - Check for code duplication between CSV and Excel parsers (refactor to shared utility)

- [ ] T088 Run validation tests from quickstart.md
  - Follow quickstart.md step-by-step
  - Verify all cURL examples work
  - Verify all UI flows work
  - Document any discrepancies or updates needed

- [ ] T089 [P] Security audit
  - Review: All endpoints have @Secured annotations
  - Review: All IP validation prevents injection attacks
  - Review: File uploads have size limits and content-type validation
  - Review: No sensitive data in error messages or logs
  - Review: JWT token validation on all IP mapping operations

- [ ] T090 [P] Performance testing
  - Benchmark: IP range query with 10k mappings × 100k assets
  - Target: <2 seconds per spec.md SC-003
  - Benchmark: CSV upload of 1000 mappings
  - Target: <60 seconds per spec.md SC-002
  - Document results, optimize if needed

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational phase completion
- **User Story 2 (Phase 4)**: Depends on Foundational phase completion - can proceed in parallel with US1
- **User Story 3 (Phase 5)**: Depends on Foundational + US1 + US2 (reuses single IP and range validation logic)
- **User Story 4 (Phase 6)**: Depends on Foundational + US1 (reuses CRUD logic)
- **User Story 5 (Phase 7)**: Depends on Foundational + US1 (extends AccountVulnsService)
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1 - MVP)**: Independent - Can start after Foundational (Phase 2)
- **User Story 2 (P1)**: Independent - Can start after Foundational (Phase 2) - Shares Foundational utilities with US1
- **User Story 3 (P2)**: Depends on US1 + US2 (reuses validation logic for all IP formats)
- **User Story 4 (P2)**: Depends on US1 (extends CRUD operations)
- **User Story 5 (P3)**: Depends on US1 (extends AccountVulnsService filtering)

### Within Each User Story (TDD Ordering)

1. **Contract Tests** FIRST → Write and ensure they FAIL
2. **Unit Tests** NEXT → Write and ensure they FAIL
3. **Backend Implementation** → Make tests PASS (Green)
4. **Frontend Implementation** → Integrate with backend
5. **E2E Tests** → Verify end-to-end user journey
6. **Refactor** → Clean up while keeping tests GREEN

### Parallel Opportunities

**Within Phases**:
- Phase 1 (Setup): All 3 tasks marked [P] can run in parallel
- Phase 2 (Foundational): T004, T005, T010, T011, T013 marked [P] can run in parallel (different files)
- Within each User Story: All tasks marked [P] can run in parallel

**Across User Stories** (if team capacity allows):
- After Phase 2 completes:
  - Developer A: User Story 1 (T014-T026)
  - Developer B: User Story 2 (T027-T042) - in parallel with US1
  - Developer C: Start foundational work for US3 (parser tests)
- After US1 + US2 complete:
  - User Story 3, 4, 5 can proceed (may have dependencies on US1/US2 code)

**Example Parallel Execution for User Story 1**:
```bash
# All contract tests for US1 together:
Task T014: Contract test POST /api/user-mappings (single IP)
Task T015: Contract test GET /api/account-vulns (IP filtering)

# All unit tests for US1 together:
Task T016: Unit test IP validation for single IPs
Task T017: Unit test Asset IP matching (exact match)

# All frontend components for US1 together:
Task T022: Create IpMappingForm component
Task T023: Update AccountVulnsView component
```

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2 Only)

**Recommended for initial release**:

1. ✅ Complete Phase 1: Setup (T001-T003)
2. ✅ Complete Phase 2: Foundational (T004-T013) - CRITICAL BLOCKING PHASE
3. ✅ Complete Phase 3: User Story 1 (T014-T026) - Single IP mapping
4. ✅ Complete Phase 4: User Story 2 (T027-T042) - CIDR and dash ranges
5. **STOP and VALIDATE**: Test US1 + US2 independently
6. Deploy/demo to get feedback

**Rationale**: US1 + US2 (both P1) deliver core value - administrators can create IP mappings (single and ranges), users see filtered assets. This is a complete, usable feature.

### Incremental Delivery (Full Feature)

1. Foundation: Setup + Foundational (Phase 1 + 2)
2. **Release 1 (MVP)**: US1 + US2 → Test → Deploy
3. **Release 2**: Add US3 (bulk upload) → Test → Deploy
4. **Release 3**: Add US4 (search/filter/edit/delete) → Test → Deploy
5. **Release 4**: Add US5 (combined AWS + IP) → Test → Deploy
6. **Release 5**: Polish phase (optimization, documentation)

**Each release adds value without breaking previous functionality.**

### Parallel Team Strategy

With 3 developers after Foundational phase completes:

1. **Week 1**: All devs complete Phase 2 (Foundational) together
2. **Week 2**:
   - Dev A: User Story 1 (single IP)
   - Dev B: User Story 2 (ranges)
   - Dev C: Prepare User Story 3 (CSV parser tests)
3. **Week 3**:
   - Dev A: User Story 4 (CRUD UI)
   - Dev B: User Story 3 (bulk upload)
   - Dev C: User Story 5 (combined filtering)
4. **Week 4**: All devs on Polish phase + integration testing

---

## Task Summary

**Total Tasks**: 90
**Setup Tasks**: 3 (T001-T003)
**Foundational Tasks**: 10 (T004-T013)
**User Story 1 Tasks**: 13 (T014-T026) - Single IP mapping
**User Story 2 Tasks**: 16 (T027-T042) - CIDR and dash ranges
**User Story 3 Tasks**: 16 (T043-T058) - CSV/Excel bulk upload
**User Story 4 Tasks**: 16 (T059-T074) - Search, filter, edit, delete
**User Story 5 Tasks**: 7 (T075-T081) - Combined AWS + IP filtering
**Polish Tasks**: 9 (T082-T090) - Cross-cutting concerns

**Parallelizable Tasks**: 42 tasks marked [P]
**Sequential Tasks**: 48 tasks (same file edits or dependencies)

**Estimated Effort** (rough):
- Foundational: 3-4 days (critical path)
- User Story 1: 2-3 days
- User Story 2: 2-3 days
- User Story 3: 3-4 days (CSV parsing complexity)
- User Story 4: 2-3 days
- User Story 5: 1-2 days
- Polish: 2-3 days
- **Total: ~15-22 days** (single developer, sequential)
- **With 3 devs in parallel: ~8-12 days** (after foundational)

---

## Notes

- **[P] tasks** = Different files, no dependencies, can run in parallel
- **[Story] labels** = Maps task to specific user story (US1, US2, US3, US4, US5)
- **TDD ordering**: Tests FIRST (fail), then implementation (pass), then refactor
- **Independent stories**: Each user story is independently completable and testable
- **Checkpoints**: Stop after each user story phase to validate independently
- **MVP scope**: User Stories 1 + 2 (both P1) provide core functionality
- **Commit strategy**: Commit after each task or logical group (e.g., all tests for a story)
- **Avoid**: Vague tasks, same file conflicts, cross-story dependencies that break independence

**Constitution Compliance**:
- ✅ TDD enforced (tests written first, marked as must-fail before implementation)
- ✅ Security-First (ADMIN role checks, input validation, file size limits)
- ✅ API-First (contracts guide implementation)
- ✅ RBAC (all IP mapping operations require ADMIN role)
- ✅ Schema Evolution (Hibernate auto-migration, backward compatible)

**Ready for execution**: Each task is specific enough for an LLM to complete without additional context.
