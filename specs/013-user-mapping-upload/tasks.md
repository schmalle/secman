# Tasks: User Mapping Upload Implementation

**Input**: Design documents from `/specs/013-user-mapping-upload/`
**Prerequisites**: plan.md (✓), spec.md (✓), data-model.md (✓), quickstart.md (✓), PLAN_EXECUTION.md (✓)
**Feature Branch**: `013-user-mapping-upload`

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → ✓ Found: Web application (Kotlin/Micronaut backend + Astro/React frontend)
   → ✓ Extract: Micronaut 4.4, Hibernate JPA, Apache POI 5.3, Astro 5.14, React 19
2. Load optional design documents:
   → ✓ spec.md: 28 functional requirements across 5 categories
   → ✓ data-model.md: UserMapping entity with 4 indexes
   → ✓ PLAN_EXECUTION.md: Complete code examples for each task
3. Generate tasks by category:
   → Tests: Write failing tests FIRST (TDD)
   → Core: Entity, repository, service, controller
   → Frontend: Service, component, admin integration
   → E2E: Test data files + Playwright scenarios
   → Polish: Documentation, QA, deployment
4. Apply task rules:
   → Different files = marked [P] for parallel execution
   → Same file = sequential (no [P])
   → Tests BEFORE implementation (TDD enforced)
5. Number tasks sequentially (T001-T038)
6. Generate dependency graph
7. Validate task completeness:
   → ✓ UserMapping entity has tests + implementation
   → ✓ All services have tests + implementation
   → ✓ 10 E2E test scenarios defined
8. Return: SUCCESS (38 tasks ready for execution)
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- All file paths are absolute from repository root

## Path Conventions (from plan.md)
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend Tests**: `src/frontend/tests/e2e/`
- **Test Data**: `testdata/`

---

## Phase 1: Setup & Verification ⚪

### T001 [P] Verify Apache POI dependency
**File**: `src/backendng/build.gradle.kts` (read-only verification)
**Description**: Verify Apache POI (org.apache.poi) is in dependencies for Excel parsing
**Action**: Read build.gradle.kts and confirm `poi` and `poi-ooxml` dependencies exist
**Validation**: Dependencies present (already used by existing import features)
**Effort**: 5 min
**Status**: NOT STARTED

### T002 [P] Verify frontend dependencies
**File**: `src/frontend/package.json` (read-only verification)
**Description**: Verify Astro 5.14, React 19, Axios, Bootstrap 5.3 are present
**Action**: Read package.json and confirm all required dependencies
**Validation**: Dependencies present, npm install succeeds
**Effort**: 5 min
**Status**: NOT STARTED

### T003 [P] Verify database connection
**File**: `src/backendng/src/main/resources/application.yml` (read-only verification)
**Description**: Verify Hibernate auto-ddl is enabled for table creation
**Action**: Confirm `jpa.default.properties.hibernate.hbm2ddl.auto` is set to `update`
**Validation**: Configuration allows table auto-creation
**Effort**: 5 min
**Status**: NOT STARTED

---

## Phase 2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE PHASE 3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation begins**

### Backend Entity Tests

### T004 Write UserMapping entity test
**File**: `src/backendng/src/test/kotlin/com/secman/domain/UserMappingTest.kt` (new)
**Description**: Write failing tests for UserMapping entity validation and persistence
**Test Cases**:
- ✓ Create mapping with all fields → succeeds
- ✓ Email normalized to lowercase on persist
- ✓ Domain normalized to lowercase on persist
- ✓ Whitespace trimmed from all fields
- ✓ Unique constraint enforced on (email, awsAccountId, domain)
- ✓ Same email with different AWS account → allowed
- ✓ Same email with different domain → allowed
- ✓ Timestamps auto-populated (createdAt, updatedAt)
**Validation**: All tests FAIL (UserMapping entity does not exist yet)
**Effort**: 45 min
**Status**: NOT STARTED
**Reference**: PLAN_EXECUTION.md lines 60-145

### Backend Repository Tests

### T005 Write UserMappingRepository test
**File**: `src/backendng/src/test/kotlin/com/secman/repository/UserMappingRepositoryTest.kt` (new)
**Description**: Write failing tests for repository query methods
**Test Cases**:
- ✓ Save and retrieve mapping
- ✓ findByEmail returns correct mappings
- ✓ findByAwsAccountId returns correct mappings
- ✓ findByDomain returns correct mappings
- ✓ existsByEmailAndAwsAccountIdAndDomain detects duplicates
- ✓ findByEmailAndAwsAccountIdAndDomain finds specific mapping
- ✓ countByEmail returns correct count
- ✓ findDistinctAwsAccountIdByEmail returns distinct accounts
- ✓ findDistinctDomainByEmail returns distinct domains
**Validation**: All tests FAIL (UserMappingRepository does not exist yet)
**Effort**: 1 hour
**Status**: NOT STARTED
**Reference**: PLAN_EXECUTION.md lines 234-352

### Backend Service Tests

### T006 [P] Create test Excel files
**Directory**: `src/backendng/src/test/resources/user-mapping-test-files/` (new)
**Description**: Create Excel test data files for service testing
**Files to Create**:
- `user-mappings-valid.xlsx` - 5 valid rows
- `user-mappings-invalid-email.xlsx` - 1 invalid email, 1 valid
- `user-mappings-invalid-aws.xlsx` - 1 invalid AWS account (too short)
- `user-mappings-invalid-aws-nonnumeric.xlsx` - 1 non-numeric AWS account
- `user-mappings-invalid-domain.xlsx` - 1 invalid domain (contains space)
- `user-mappings-duplicates.xlsx` - 3 rows with 1 duplicate
- `user-mappings-missing-column.xlsx` - Missing "Domain" column
- `user-mappings-empty.xlsx` - Only headers, no data
**Method**: Use Python openpyxl or LibreOffice Calc
**Validation**: All files open correctly in Excel/LibreOffice
**Effort**: 45 min
**Status**: NOT STARTED
**Reference**: tasks.md original lines 519-553

### T007 Write UserMappingImportService test
**File**: `src/backendng/src/test/kotlin/com/secman/service/UserMappingImportServiceTest.kt` (new)
**Description**: Write failing tests for Excel import service
**Test Cases**:
- ✓ Valid file with 5 rows → imports all 5
- ✓ Invalid email format → skips row with error
- ✓ Invalid AWS account (non-numeric) → skips row
- ✓ Invalid AWS account (wrong length) → skips row
- ✓ Invalid domain format → skips row
- ✓ Empty row → skipped silently
- ✓ Missing required column → error, no import
- ✓ Duplicate mapping → skipped
- ✓ Mixed valid/invalid → valid imported, invalid skipped
- ✓ File with only headers → error message
**Dependencies**: T006 (test Excel files must exist)
**Validation**: All tests FAIL (UserMappingImportService does not exist yet)
**Effort**: 1.5 hours
**Status**: NOT STARTED
**Reference**: PLAN_EXECUTION.md service test section

### Backend Controller Tests

### T008 Write ImportController.uploadUserMappings test
**File**: `src/backendng/src/test/kotlin/com/secman/controller/ImportControllerTest.kt` (modify existing)
**Description**: Write failing tests for user mapping upload endpoint
**Test Cases**:
- ✓ Upload valid file as ADMIN → 200 OK with ImportResult
- ✓ Upload valid file as USER → 403 Forbidden
- ✓ Upload valid file unauthenticated → 401 Unauthorized
- ✓ Upload .csv file → 400 Bad Request (wrong format)
- ✓ Upload file >10MB → 400 Bad Request (too large)
- ✓ Upload file with missing columns → 400 Bad Request
- ✓ Upload file with invalid data → 200 with partial success
**Dependencies**: T006 (test Excel files)
**Validation**: All tests FAIL (uploadUserMappings endpoint does not exist yet)
**Effort**: 1 hour
**Status**: NOT STARTED
**Reference**: PLAN_EXECUTION.md lines 599-628

---

## Phase 3: Backend Implementation ⚠️ ONLY AFTER PHASE 2 COMPLETE

### T009 Implement UserMapping entity
**File**: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt` (new)
**Description**: Create JPA entity with validation, indexes, and lifecycle hooks
**Requirements**:
- Fields: id, email, awsAccountId, domain, createdAt, updatedAt
- Unique constraint on (email, awsAccountId, domain)
- 4 indexes: email, awsAccountId, domain, (email + awsAccountId)
- Validation: @Email, @Pattern for awsAccountId (12 digits), @Pattern for domain
- @PrePersist: normalize email/domain to lowercase, trim whitespace
- @PreUpdate: update updatedAt timestamp
- @Serdeable for JSON serialization
**Dependencies**: T004 (tests must exist and fail)
**Validation**: Run `./gradlew test --tests UserMappingTest` → all tests PASS
**Effort**: 30 min
**Status**: NOT STARTED
**Commit**: `feat(user-mapping): add UserMapping entity with validation and tests`
**Reference**: PLAN_EXECUTION.md lines 28-94

### T010 Implement UserMappingRepository
**File**: `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt` (new)
**Description**: Create Micronaut Data repository with query methods
**Requirements**:
- Extends JpaRepository<UserMapping, Long>
- Methods: findByEmail, findByAwsAccountId, findByDomain
- Methods: existsByEmailAndAwsAccountIdAndDomain
- Methods: findByEmailAndAwsAccountIdAndDomain
- Methods: countByEmail, countByAwsAccountId
- Methods: findDistinctAwsAccountIdByEmail, findDistinctDomainByEmail
- KDoc comments for each method
**Dependencies**: T005 (tests must exist and fail), T009 (entity must exist)
**Validation**: Run `./gradlew test --tests UserMappingRepositoryTest` → all tests PASS
**Effort**: 20 min
**Status**: NOT STARTED
**Commit**: `feat(user-mapping): add UserMappingRepository with query methods and tests`
**Reference**: PLAN_EXECUTION.md lines 152-232

### T011 Implement UserMappingImportService
**File**: `src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt` (new)
**Description**: Create service to parse Excel and import mappings with validation
**Requirements**:
- @Singleton, @Transactional
- Method: importFromExcel(InputStream): ImportResult
- Private methods: validateHeaders, getHeaderMapping, parseRowToUserMapping
- Validation methods: validateEmail, validateAwsAccountId, validateDomain
- Uses Apache POI (XSSFWorkbook) for Excel parsing
- Handles cell types: STRING, NUMERIC, FORMULA
- Uses DataFormatter to preserve AWS account ID formatting
- Skips invalid rows, continues with valid
- Checks duplicates before insert (existsByEmailAndAwsAccountIdAndDomain)
- Returns ImportResult with counts (imported, skipped, errors list)
- KDoc comments for public methods
**Dependencies**: T007 (tests must exist and fail), T010 (repository must exist)
**Validation**: Run `./gradlew test --tests UserMappingImportServiceTest` → all tests PASS
**Effort**: 2 hours
**Status**: NOT STARTED
**Commit**: `feat(user-mapping): add UserMappingImportService with Excel parsing and validation`
**Reference**: PLAN_EXECUTION.md service implementation section

### T012 Add uploadUserMappings endpoint to ImportController
**File**: `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt` (modify existing)
**Description**: Add ADMIN-only endpoint for user mapping upload
**Requirements**:
- Add userMappingImportService to constructor injection
- Method signature: `uploadUserMappings(@Part xlsxFile: CompletedFileUpload): HttpResponse<*>`
- Annotations: @Post("/upload-user-mappings"), @Consumes(MULTIPART_FORM_DATA), @Transactional, @Secured("ADMIN")
- Reuse validateVulnerabilityFile() for file validation
- Call userMappingImportService.importFromExcel()
- Return HttpResponse.ok(ImportResult) on success
- Return HttpResponse.badRequest(ErrorResponse) on validation error
- Return HttpResponse.status(INTERNAL_SERVER_ERROR) on exception
- Add debug/info/error logging
- KDoc comment with endpoint details
**Dependencies**: T008 (tests must exist and fail), T011 (service must exist)
**Validation**: Run `./gradlew test --tests ImportControllerTest` → all tests PASS
**Effort**: 45 min
**Status**: NOT STARTED
**Commit**: `feat(user-mapping): add upload endpoint to ImportController with ADMIN security`
**Reference**: PLAN_EXECUTION.md lines 556-597

---

## Phase 4: Frontend Implementation

### T013 [P] Create userMappingService
**File**: `src/frontend/src/services/userMappingService.ts` (new)
**Description**: Create frontend service for API calls
**Requirements**:
- Function: uploadUserMappings(file: File): Promise<ImportResult>
- Uses axios POST to /api/import/upload-user-mappings
- Creates FormData with xlsxFile field
- Sets Content-Type: multipart/form-data header
- Returns ImportResult type
- Throws error on HTTP error responses
- Function: getSampleFileUrl(): string
- TypeScript interfaces for ImportResult and ErrorResponse
- JSDoc comments for functions
**Dependencies**: None (can run parallel with T014-T016)
**Validation**: TypeScript compilation succeeds, no errors
**Effort**: 20 min
**Status**: NOT STARTED
**Commit**: `feat(user-mapping): add frontend service for user mapping API`
**Reference**: PLAN_EXECUTION.md lines 636-656

### T014 [P] Create UserMappingUpload component
**File**: `src/frontend/src/components/UserMappingUpload.tsx` (new)
**Description**: Create React component for file upload UI
**Requirements**:
- File input field (accept=".xlsx", id="userMappingFile")
- Upload button with loading state
- File requirements card (format, size, columns)
- Sample file download link
- Success alert (green, shows imported/skipped counts)
- Error alert (red, shows error message)
- Error list display (if ImportResult.errors present)
- useState hooks: file, uploading, result, error
- handleFileChange: update file state
- handleUpload: async function to call uploadUserMappings
- Clear file input after successful upload
- Bootstrap 5 styling (cards, alerts, buttons, forms)
- Icons: bi-upload, bi-download, bi-exclamation-triangle
- Responsive layout
- Accessible labels and ARIA attributes
- JSDoc component comment
**Dependencies**: T013 (service must exist)
**Validation**: Component renders without errors, TypeScript compiles
**Effort**: 1.5 hours
**Status**: NOT STARTED
**Commit**: `feat(user-mapping): add UserMappingUpload React component with file upload UI`
**Reference**: PLAN_EXECUTION.md lines 660-741

### T015 Add User Mappings card to AdminPage
**File**: `src/frontend/src/components/AdminPage.tsx` (modify existing)
**Description**: Add new card for User Mappings feature
**Requirements**:
- Add card after "MCP API Keys" card (around line 167)
- Card structure: col-md-4 > card > card-body
- Icon: bi-diagram-3-fill
- Title: "User Mappings"
- Description: "Upload and manage user-to-AWS-account-to-domain mappings for role-based access control."
- Button: "Manage Mappings" linking to /admin/user-mappings
- Follow existing card pattern
**Dependencies**: None
**Validation**: Admin page renders with new card, link works
**Effort**: 10 min
**Status**: NOT STARTED
**Commit**: `feat(user-mapping): add User Mappings card to Admin page`
**Reference**: PLAN_EXECUTION.md lines 745-775

### T016 [P] Create admin user mappings page route
**File**: `src/frontend/src/pages/admin/user-mappings.astro` (new)
**Description**: Create Astro page for user mapping management
**Requirements**:
- Import Layout from '../../layouts/Layout.astro'
- Import UserMappingUpload from '../../components/UserMappingUpload'
- Page title: "User Mappings - Admin"
- Breadcrumb navigation: Home > Admin > User Mappings
- Render UserMappingUpload component with client:load directive
- Container with container-fluid class
**Dependencies**: T014 (component must exist)
**Validation**: Page accessible at /admin/user-mappings, component renders
**Effort**: 15 min
**Status**: NOT STARTED
**Commit**: `feat(user-mapping): add admin user mappings page route`
**Reference**: PLAN_EXECUTION.md lines 779-800

---

## Phase 5: Test Data & E2E Tests

### T017 [P] Create sample Excel template
**File**: `src/frontend/public/sample-files/user-mapping-template.xlsx` (new)
**Description**: Create downloadable sample Excel template for users
**Requirements**:
- Sheet 1 "Mappings": Headers (Email Address, AWS Account ID, Domain) + 3 sample rows
- Sheet 2 "Instructions": Detailed usage instructions, format rules, examples
- Sample rows: user1@example.com|123456789012|example.com, user2@example.com|987654321098|example.com, consultant@agency.com|555555555555|clientA.com
- File size <50KB
- Opens correctly in Excel/LibreOffice
**Method**: Use Python openpyxl or LibreOffice Calc
**Validation**: File downloads from frontend, opens correctly
**Effort**: 30 min
**Status**: NOT STARTED
**Commit**: `feat(user-mapping): add sample Excel template for download`
**Reference**: PLAN_EXECUTION.md lines 804-858

### T018 [P] Create E2E test data files
**Directory**: `testdata/` (existing)
**Description**: Create Excel test files for E2E testing
**Files to Create**:
- `user-mappings-valid.xlsx` - 5 valid rows
- `user-mappings-invalid-email.xlsx` - 1 invalid email, 1 valid
- `user-mappings-invalid-aws.xlsx` - 1 invalid AWS account (too short)
- `user-mappings-invalid-aws-nonnumeric.xlsx` - 1 non-numeric AWS account
- `user-mappings-invalid-domain.xlsx` - 1 invalid domain (space)
- `user-mappings-duplicates.xlsx` - 3 rows, 1 duplicate
- `user-mappings-missing-column.xlsx` - Missing "Domain" column
- `user-mappings-empty.xlsx` - Only headers
- `user-mappings-large.csv` - CSV file (wrong format test)
**Method**: Use Python script or LibreOffice Calc
**Validation**: All files <1MB, format correct
**Effort**: 45 min
**Status**: NOT STARTED
**Commit**: `test(user-mapping): add test Excel files for E2E testing`
**Reference**: tasks.md original lines 519-553

### T019 Write E2E test: Upload valid file
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (new)
**Description**: Playwright test for successful upload
**Test Scenario**:
- Login as admin
- Navigate to /admin/user-mappings
- Upload testdata/user-mappings-valid.xlsx
- Click "Upload" button
- Verify success alert: "Imported 5 mappings"
**Dependencies**: T017 (template), T018 (test files), T014 (component), T016 (page)
**Validation**: Test passes when backend + frontend fully implemented
**Effort**: 15 min
**Status**: NOT STARTED
**Reference**: PLAN_EXECUTION.md lines 862-876

### T020 Write E2E test: Invalid email handling
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (continue)
**Description**: Test partial success with invalid email
**Test Scenario**:
- Login as admin
- Upload testdata/user-mappings-invalid-email.xlsx
- Verify alert shows: "Imported 1, skipped 1"
- Verify error list contains: "Invalid email format"
**Dependencies**: T019 (same file, sequential)
**Validation**: Test passes
**Effort**: 10 min
**Status**: NOT STARTED

### T021 Write E2E test: Invalid AWS account ID
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (continue)
**Description**: Test AWS account ID validation
**Test Scenario**:
- Upload testdata/user-mappings-invalid-aws.xlsx
- Verify error: "AWS Account ID must be 12 digits"
**Dependencies**: T020 (same file, sequential)
**Validation**: Test passes
**Effort**: 10 min
**Status**: NOT STARTED

### T022 Write E2E test: Missing column
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (continue)
**Description**: Test missing required column handling
**Test Scenario**:
- Upload testdata/user-mappings-missing-column.xlsx
- Verify error alert: "Missing required column: Domain"
**Dependencies**: T021 (same file, sequential)
**Validation**: Test passes
**Effort**: 10 min
**Status**: NOT STARTED

### T023 Write E2E test: Duplicate handling
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (continue)
**Description**: Test duplicate mapping detection
**Test Scenario**:
- Upload testdata/user-mappings-duplicates.xlsx twice
- First upload: "Imported 3"
- Second upload: "Skipped 3 (duplicates)"
**Dependencies**: T022 (same file, sequential)
**Validation**: Test passes
**Effort**: 15 min
**Status**: NOT STARTED

### T024 Write E2E test: Empty file
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (continue)
**Description**: Test empty file handling
**Test Scenario**:
- Upload testdata/user-mappings-empty.xlsx
- Verify error: "No data rows found in file"
**Dependencies**: T023 (same file, sequential)
**Validation**: Test passes
**Effort**: 10 min
**Status**: NOT STARTED

### T025 Write E2E test: Wrong file type
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (continue)
**Description**: Test file type validation
**Test Scenario**:
- Upload testdata/user-mappings-large.csv
- Verify error: "Only .xlsx files are supported"
**Dependencies**: T024 (same file, sequential)
**Validation**: Test passes
**Effort**: 10 min
**Status**: NOT STARTED

### T026 Write E2E test: Non-admin access denied
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (continue)
**Description**: Test access control
**Test Scenario**:
- Login as regular USER (not admin)
- Navigate to /admin/user-mappings
- Verify "Access Denied" message
**Dependencies**: T025 (same file, sequential)
**Validation**: Test passes
**Effort**: 10 min
**Status**: NOT STARTED

### T027 Write E2E test: Download sample file
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (continue)
**Description**: Test sample file download
**Test Scenario**:
- Navigate to /admin/user-mappings
- Click "Download Sample" link
- Verify file downloads successfully
**Dependencies**: T026 (same file, sequential)
**Validation**: Test passes
**Effort**: 10 min
**Status**: NOT STARTED

### T028 Write E2E test: File too large
**File**: `src/frontend/tests/e2e/user-mapping-upload.spec.ts` (continue)
**Description**: Test file size limit
**Test Scenario**:
- Create or use 11MB test file
- Upload file
- Verify error: "File size exceeds maximum limit"
**Dependencies**: T027 (same file, sequential)
**Validation**: Test passes
**Effort**: 15 min
**Status**: NOT STARTED

---

## Phase 6: Documentation & Polish

### T029 Update CLAUDE.md - Key Entities
**File**: `CLAUDE.md` (modify existing)
**Description**: Add UserMapping to Key Entities section
**Requirements**:
- Add after Release/RequirementSnapshot section (around line 180)
- Content: Fields, validation, relationships, indexes, access control
- Format: Follow existing entity documentation pattern
**Dependencies**: T009-T012 (implementation complete)
**Validation**: Documentation accurate, complete
**Effort**: 10 min
**Status**: NOT STARTED
**Commit**: Part of larger doc update
**Reference**: PLAN_EXECUTION.md lines 896-916

### T030 Update CLAUDE.md - API Endpoints
**File**: `CLAUDE.md` (modify existing)
**Description**: Add upload endpoint to API Endpoints section
**Requirements**:
- Add to Import section (around line 450)
- Content: POST /api/import/upload-user-mappings (ADMIN only)
- Include request/response format
**Dependencies**: T029 (same file, sequential)
**Validation**: Documentation accurate
**Effort**: 5 min
**Status**: NOT STARTED

### T031 Update CLAUDE.md - Recent Changes
**File**: `CLAUDE.md` (modify existing)
**Description**: Add Feature 013 to Recent Changes section
**Requirements**:
- Add at top of Recent Changes (line 10)
- Format: "013-user-mapping-upload: User Mapping with AWS Account & Domain Upload (2025-10-07) - Excel upload for email-AWS-domain mappings, ADMIN-only access, validation and duplicate handling"
**Dependencies**: T030 (same file, sequential)
**Validation**: Documentation accurate
**Effort**: 5 min
**Status**: NOT STARTED
**Commit**: `docs(user-mapping): update CLAUDE.md with new entity and endpoints`

### T032 [P] Add inline code documentation
**Files**: All new backend files
**Description**: Add comprehensive KDoc comments
**Requirements**:
- Class-level KDoc for UserMapping, UserMappingRepository, UserMappingImportService
- Method-level KDoc for all public methods
- Parameter and return value documentation
- Example usage where helpful
- Comments explain WHY, not WHAT
**Dependencies**: T009-T012 (code must exist)
**Validation**: All public APIs documented
**Effort**: 30 min
**Status**: NOT STARTED
**Commit**: `docs(user-mapping): add comprehensive inline documentation`

### T033 [P] Add frontend JSDoc comments
**Files**: userMappingService.ts, UserMappingUpload.tsx
**Description**: Add JSDoc comments to frontend code
**Requirements**:
- Function-level JSDoc for service methods
- Component-level JSDoc for UserMappingUpload
- Parameter and return type documentation
**Dependencies**: T013-T014 (code must exist)
**Validation**: JSDoc formatted correctly
**Effort**: 20 min
**Status**: NOT STARTED
**Commit**: Part of T032 or separate commit

### T034 Code review and cleanup
**Files**: All new files
**Description**: Review all code for quality and consistency
**Checklist**:
- [ ] No console.log statements in production code
- [ ] No unused imports
- [ ] All TODOs resolved or documented
- [ ] Consistent naming conventions
- [ ] Error messages are user-friendly
- [ ] No hardcoded values (use constants)
- [ ] All nullable types handled correctly
- [ ] All async operations have error handling
- [ ] Code follows project style guide
**Dependencies**: T009-T016 (all implementation), T032-T033 (documentation)
**Validation**: All checklist items pass
**Effort**: 30 min
**Status**: NOT STARTED

### T035 Run linters and fix issues
**Files**: All new files
**Description**: Run Kotlin and TypeScript linters, fix all issues
**Commands**:
- Backend: `./gradlew ktlintCheck` then `./gradlew ktlintFormat`
- Frontend: `npm run lint` then `npm run lint:fix`
**Dependencies**: T034 (cleanup must be done first)
**Validation**: Linters pass with no errors
**Effort**: 15 min
**Status**: NOT STARTED
**Commit**: `refactor(user-mapping): code cleanup and linting fixes`

---

## Phase 7: QA & Testing

### T036 Manual testing checklist
**Description**: Execute manual testing scenarios
**Test Scenarios**:
1. Login as admin → Admin page → User Mappings card visible
2. Click "Manage Mappings" → Upload page loads
3. Download sample file → Opens in Excel correctly
4. Upload sample file → Success message with counts
5. Check database → Records exist with correct data
6. Upload same file again → Duplicates skipped
7. Upload file with 100 rows → Performance acceptable (<5s)
8. Upload file with invalid data → Partial success, errors shown
9. Login as regular user → Access denied on /admin/user-mappings
10. Test in Chrome, Firefox, Safari
11. Test responsive layout (mobile, tablet)
**Dependencies**: T009-T028 (all implementation and tests)
**Validation**: All scenarios pass
**Effort**: 45 min
**Status**: NOT STARTED

### T037 [P] Performance testing
**Description**: Verify performance meets targets
**Test Scenarios**:
- Upload 100-row file → measure time (target: <2s)
- Upload 1000-row file → measure time (target: <10s)
- Upload 10MB file → measure time (target: <20s)
- Database query by email → measure time (target: <10ms)
- Page load time → measure time (target: <1s)
**Dependencies**: T036 (manual testing complete)
**Validation**: All targets met
**Effort**: 30 min
**Status**: NOT STARTED
**Document**: Record results in performance.md

### T038 [P] Security testing
**Description**: Verify security controls are effective
**Test Scenarios**:
- Anonymous request → 401 Unauthorized
- USER role request → 403 Forbidden
- ADMIN role request → 200 OK
- File size limit → Rejection of 11MB file
- File type validation → Rejection of .csv file
- SQL injection attempt in email → No database impact
- XSS attempt in domain → No script execution
- Path traversal in filename → No file system access
**Dependencies**: T036 (manual testing complete)
**Validation**: All security controls effective
**Effort**: 30 min
**Status**: NOT STARTED
**Document**: Record results in security-test-report.md

---

## Phase 8: Deployment (Post-Implementation)

**Note**: Deployment tasks are documented in PLAN_EXECUTION.md Phase 8.
Execute after all development tasks (T001-T038) are complete.

---

## Summary Statistics

### Tasks by Phase
- Phase 1: Setup & Verification - 3 tasks (T001-T003)
- Phase 2: Tests First (TDD) - 5 tasks (T004-T008)
- Phase 3: Backend Implementation - 4 tasks (T009-T012)
- Phase 4: Frontend Implementation - 4 tasks (T013-T016)
- Phase 5: Test Data & E2E - 12 tasks (T017-T028)
- Phase 6: Documentation & Polish - 7 tasks (T029-T035)
- Phase 7: QA & Testing - 3 tasks (T036-T038)

**Total Tasks**: 38

### Effort Breakdown
- Setup & Verification: 15 min
- Backend Tests: 4.5 hours
- Backend Implementation: 3.5 hours
- Frontend Implementation: 2 hours
- E2E Tests: 2.5 hours
- Documentation: 1.5 hours
- QA: 1.5 hours

**Total Estimated Effort**: ~16 hours (2 days)

### Parallel Execution Opportunities
Tasks marked [P] can run in parallel (17 tasks):
- T001, T002, T003 (setup verification)
- T006 (test files - can run while writing tests)
- T013 (service), T014 (component), T016 (page) (different files)
- T017 (sample template), T018 (test data)
- T029-T033 (documentation - different files)
- T037, T038 (testing - different activities)

**Potential Time Savings**: ~30% if parallel execution used effectively

### Critical Path
T004 → T009 → T010 → T011 → T012 → T014 → T019-T028 → T036 → Deployment
(Must be executed sequentially due to dependencies)

---

## Dependency Graph

```
Setup (T001-T003) [P]
         ↓
Tests First - Entity (T004)
         ↓
Backend Entity (T009)
         ↓
Tests First - Repository (T005)
         ↓
Backend Repository (T010)
         ↓
Test Files (T006) [P] + Tests First - Service (T007)
         ↓
Backend Service (T011)
         ↓
Tests First - Controller (T008)
         ↓
Backend Controller (T012)
         ↓
Frontend Service (T013) [P] + Frontend Component (T014) [P]
         ↓
Admin Page Card (T015)
         ↓
Admin Page Route (T016)
         ↓
Sample Template (T017) [P] + E2E Test Data (T018) [P]
         ↓
E2E Tests (T019-T028) [sequential within file]
         ↓
Documentation (T029-T033) [some P]
         ↓
Code Review (T034-T035)
         ↓
QA & Testing (T036-T038) [T037 & T038 can be P]
         ↓
Deployment (See PLAN_EXECUTION.md Phase 8)
```

---

## Parallel Execution Examples

### Example 1: Setup Phase
```bash
# Terminal 1
./gradlew dependencies | grep poi  # T001

# Terminal 2
cd src/frontend && npm list astro  # T002

# Terminal 3
grep -A5 "hibernate.hbm2ddl" src/backendng/src/main/resources/application.yml  # T003
```

### Example 2: Test File Creation
```bash
# While writing tests (T004-T005), another developer can create test files
# Terminal 1: Write UserMappingTest.kt (T004)
# Terminal 2: Create test Excel files (T006)
```

### Example 3: Frontend Implementation
```bash
# Three different files, can be done in parallel
# Terminal 1: Create userMappingService.ts (T013)
# Terminal 2: Create UserMappingUpload.tsx (T014)
# Terminal 3: Create user-mappings.astro (T016)
```

---

## Progress Tracking Table

| Task | Status | Assignee | Start | Complete | Notes |
|------|--------|----------|-------|----------|-------|
| T001-T003 | ⚪ | - | - | - | Setup verification |
| T004 | ⚪ | - | - | - | Entity tests |
| T005 | ⚪ | - | - | - | Repository tests |
| T006 | ⚪ | - | - | - | Test Excel files |
| T007 | ⚪ | - | - | - | Service tests |
| T008 | ⚪ | - | - | - | Controller tests |
| T009 | ⚪ | - | - | - | Entity impl |
| T010 | ⚪ | - | - | - | Repository impl |
| T011 | ⚪ | - | - | - | Service impl |
| T012 | ⚪ | - | - | - | Controller impl |
| T013 | ⚪ | - | - | - | Frontend service |
| T014 | ⚪ | - | - | - | Upload component |
| T015 | ⚪ | - | - | - | Admin card |
| T016 | ⚪ | - | - | - | Admin route |
| T017 | ⚪ | - | - | - | Sample template |
| T018 | ⚪ | - | - | - | E2E test data |
| T019-T028 | ⚪ | - | - | - | E2E tests (10) |
| T029-T031 | ⚪ | - | - | - | CLAUDE.md updates |
| T032-T033 | ⚪ | - | - | - | Inline docs |
| T034-T035 | ⚪ | - | - | - | Code review |
| T036-T038 | ⚪ | - | - | - | QA testing |

**Legend**: ⚪ Not Started | 🟡 In Progress | 🟢 Done | 🔴 Blocked

---

## Risk Register

| Risk | Probability | Impact | Mitigation | Task |
|------|-------------|--------|------------|------|
| Excel parsing edge cases | Medium | Medium | Comprehensive test files, use DataFormatter | T006, T007 |
| Performance with large files | Low | Medium | File size limit, performance testing | T037 |
| Unique constraint conflicts | Low | Low | Duplicate check before insert | T011 |
| Browser compatibility | Low | Low | Test in multiple browsers | T036 |
| Security vulnerabilities | Low | High | Security testing, @Secured enforcement | T038 |

---

## Test Coverage Goals

| Component | Unit Tests | Integration Tests | E2E Tests | Target Coverage |
|-----------|------------|-------------------|-----------|-----------------|
| UserMapping entity | 8 scenarios | - | - | 100% |
| UserMappingRepository | 9 scenarios | - | - | 100% |
| UserMappingImportService | 10 scenarios | - | - | >90% |
| ImportController | - | 7 scenarios | - | >80% |
| Frontend Components | - | - | 10 scenarios | Functional |
| **Overall** | **27** | **7** | **10** | **>80%** |

---

## Notes for Implementation

1. **TDD is Non-Negotiable**: Phase 2 MUST be complete before Phase 3
2. **Parallel Opportunities**: Look for [P] tasks to speed up execution
3. **Test Data**: T006 and T018 are critical - all other tests depend on them
4. **Commit Messages**: Use conventional commits format
5. **Code Review**: T034-T035 are quality gates - don't skip
6. **Reference Docs**: PLAN_EXECUTION.md has complete code examples

---

## Approval

- [ ] Tech Lead: _______________
- [ ] Backend Developer: _______________
- [ ] Frontend Developer: _______________
- [ ] QA Lead: _______________
- [ ] Date: _______________

---

**Task Breakdown Complete**: Ready for Execution ✅  
**Total Tasks**: 38  
**Estimated Effort**: 16 hours  
**Timeline**: 2-3 days

**Start with T001-T003, then strictly follow TDD (Phase 2 before Phase 3)**

### Task 1.1: Create UserMapping Entity ⚪
**Assignee**: Backend Developer
**Effort**: 30 min
**Priority**: P0 (Critical)
**Dependencies**: None

**Description**:
Create JPA entity for UserMapping with fields: id, email, awsAccountId, domain, createdAt, updatedAt

**Acceptance Criteria**:
- [ ] Entity class created with all fields
- [ ] JPA annotations correct (@Entity, @Table, @Column)
- [ ] Unique constraint on (email, awsAccountId, domain)
- [ ] Indexes defined: email, awsAccountId, domain, composite email+awsAccountId
- [ ] Validation annotations (@Email, @NotBlank, @Pattern for awsAccountId)
- [ ] @PrePersist method normalizes email/domain to lowercase
- [ ] Serdeable annotation for JSON serialization

**Files**:
- `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`

**Tests**:
- `src/backendng/src/test/kotlin/com/secman/domain/UserMappingTest.kt`

**Test Cases**:
- Entity can be instantiated with all fields
- Email is normalized to lowercase on persist
- Domain is normalized to lowercase on persist
- AWS account ID is trimmed on persist
- Timestamps are auto-populated on create

---

### Task 1.2: Create UserMappingRepository ⚪
**Assignee**: Backend Developer
**Effort**: 20 min
**Priority**: P0 (Critical)
**Dependencies**: Task 1.1

**Description**:
Create Micronaut Data repository interface with query methods for UserMapping entity

**Acceptance Criteria**:
- [ ] Repository interface extends JpaRepository<UserMapping, Long>
- [ ] Method: findByEmail(email: String): List<UserMapping>
- [ ] Method: findByAwsAccountId(accountId: String): List<UserMapping>
- [ ] Method: findByDomain(domain: String): List<UserMapping>
- [ ] Method: existsByEmailAndAwsAccountIdAndDomain(...): Boolean
- [ ] Method: findByEmailAndAwsAccountIdAndDomain(...): Optional<UserMapping>
- [ ] @Repository annotation present

**Files**:
- `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt`

**Tests**:
- `src/backendng/src/test/kotlin/com/secman/repository/UserMappingRepositoryTest.kt`

**Test Cases**:
- Save and retrieve UserMapping
- findByEmail returns correct mappings
- findByAwsAccountId returns correct mappings
- findByDomain returns correct mappings
- existsByEmailAndAwsAccountIdAndDomain detects duplicates
- Unique constraint prevents duplicate inserts

---

### Task 1.3: Write Entity Unit Tests ⚪
**Assignee**: Backend Developer
**Effort**: 30 min
**Priority**: P0 (Critical)
**Dependencies**: Task 1.1, 1.2

**Description**:
Comprehensive unit tests for UserMapping entity and repository

**Acceptance Criteria**:
- [ ] All test cases from Task 1.1 and 1.2 implemented
- [ ] Tests use @MicronautTest for persistence context
- [ ] Tests create in-memory H2 database
- [ ] Tests clean up after each test (@AfterEach)
- [ ] Code coverage >80% for entity and repository

**Files**:
- `src/backendng/src/test/kotlin/com/secman/domain/UserMappingTest.kt`
- `src/backendng/src/test/kotlin/com/secman/repository/UserMappingRepositoryTest.kt`

**Test Execution**:
```bash
./gradlew test --tests UserMappingTest
./gradlew test --tests UserMappingRepositoryTest
```

---

## Phase 2: Backend Service Layer ⚪

### Task 2.1: Create UserMappingImportService ⚪
**Assignee**: Backend Developer
**Effort**: 2 hours
**Priority**: P0 (Critical)
**Dependencies**: Task 1.2

**Description**:
Service to parse Excel files and import UserMapping records with validation

**Acceptance Criteria**:
- [ ] @Singleton service class created
- [ ] Method: importFromExcel(InputStream): ImportResult
- [ ] Method: validateHeaders(Sheet): String? - checks for required columns
- [ ] Method: getHeaderMapping(Sheet): Map<String, Int> - maps headers to indices
- [ ] Method: parseRowToUserMapping(Row, Map): UserMapping? - parses and validates row
- [ ] Method: validateEmail(String): Boolean - checks email format
- [ ] Method: validateAwsAccountId(String): Boolean - checks 12-digit numeric
- [ ] Method: validateDomain(String): Boolean - checks domain format
- [ ] Uses Apache POI (XSSFWorkbook) for Excel parsing
- [ ] Handles cell types: STRING, NUMERIC, FORMULA
- [ ] Skips invalid rows, continues processing valid rows
- [ ] Returns ImportResult with counts (imported, skipped, errors list)
- [ ] Detects duplicates before insert (existsByEmailAndAwsAccountIdAndDomain)
- [ ] @Transactional annotation for atomicity

**Files**:
- `src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt`

**Data Classes**:
```kotlin
@Serdeable
data class ImportResult(
    val message: String,
    val imported: Int,
    val skipped: Int,
    val errors: List<String> = emptyList()
)
```

---

### Task 2.2: Write Service Unit Tests ⚪
**Assignee**: Backend Developer
**Effort**: 1.5 hours
**Priority**: P0 (Critical)
**Dependencies**: Task 2.1

**Description**:
Comprehensive unit tests for UserMappingImportService

**Acceptance Criteria**:
- [ ] Test: Valid file with 5 rows imports successfully
- [ ] Test: Invalid email format skips row with error message
- [ ] Test: Invalid AWS account ID (non-numeric) skips row
- [ ] Test: Invalid AWS account ID (wrong length) skips row
- [ ] Test: Invalid domain format skips row
- [ ] Test: Empty row is skipped silently
- [ ] Test: Missing required column returns error
- [ ] Test: Duplicate mapping is skipped
- [ ] Test: Mixed valid/invalid rows - valid rows imported, invalid skipped
- [ ] Test: File with only headers (no data rows) returns error
- [ ] Mock UserMappingRepository for unit testing
- [ ] Code coverage >80%

**Files**:
- `src/backendng/src/test/kotlin/com/secman/service/UserMappingImportServiceTest.kt`

**Test Data**:
- Create test Excel files in `src/backendng/src/test/resources/`
  - `user-mappings-valid.xlsx`
  - `user-mappings-invalid-email.xlsx`
  - `user-mappings-invalid-aws.xlsx`
  - `user-mappings-missing-column.xlsx`
  - `user-mappings-empty.xlsx`

**Test Execution**:
```bash
./gradlew test --tests UserMappingImportServiceTest
```

---

## Phase 3: Backend API Controller ⚪

### Task 3.1: Add Upload Endpoint to ImportController ⚪
**Assignee**: Backend Developer
**Effort**: 45 min
**Priority**: P0 (Critical)
**Dependencies**: Task 2.1

**Description**:
Add new endpoint to ImportController for user mapping upload

**Acceptance Criteria**:
- [ ] Method: uploadUserMappings(@Part xlsxFile: CompletedFileUpload): HttpResponse<*>
- [ ] Annotation: @Post("/upload-user-mappings")
- [ ] Annotation: @Consumes(MediaType.MULTIPART_FORM_DATA)
- [ ] Annotation: @Secured("ADMIN") - restricts to admin users only
- [ ] Annotation: @Transactional for database operations
- [ ] Inject UserMappingImportService dependency
- [ ] Reuse validateVulnerabilityFile() for file validation (size, type, not empty)
- [ ] Call userMappingImportService.importFromExcel(inputStream)
- [ ] Return HttpResponse.ok(ImportResult) on success
- [ ] Return HttpResponse.badRequest(ErrorResponse) on validation error
- [ ] Return HttpResponse.status(INTERNAL_SERVER_ERROR) on exception
- [ ] Log debug message on upload start
- [ ] Log info message on successful import
- [ ] Log error message on exception

**Files**:
- `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt` (modify existing)

**Endpoint**:
```
POST /api/import/upload-user-mappings
Content-Type: multipart/form-data
Authorization: Bearer <jwt-token>
Body: xlsxFile (file)
Response: ImportResult JSON or ErrorResponse JSON
```

---

### Task 3.2: Write Controller Integration Tests ⚪
**Assignee**: Backend Developer
**Effort**: 1 hour
**Priority**: P0 (Critical)
**Dependencies**: Task 3.1

**Description**:
Integration tests for upload endpoint

**Acceptance Criteria**:
- [ ] Test: Upload valid file as ADMIN returns 200 with ImportResult
- [ ] Test: Upload valid file as USER returns 403 Forbidden
- [ ] Test: Upload valid file as unauthenticated returns 401 Unauthorized
- [ ] Test: Upload .csv file returns 400 Bad Request (wrong format)
- [ ] Test: Upload file >10MB returns 400 Bad Request (too large)
- [ ] Test: Upload file with missing columns returns 400 Bad Request
- [ ] Test: Upload file with invalid data returns 200 with partial success
- [ ] Use @MicronautTest with HTTP client
- [ ] Use MultipartBody for file upload
- [ ] Mock authentication context

**Files**:
- `src/backendng/src/test/kotlin/com/secman/controller/ImportControllerTest.kt` (modify existing)

**Test Execution**:
```bash
./gradlew test --tests ImportControllerTest
```

---

## Phase 4: Frontend Service & Components ⚪

### Task 4.1: Create UserMapping Service ⚪
**Assignee**: Frontend Developer
**Effort**: 20 min
**Priority**: P0 (Critical)
**Dependencies**: Task 3.1

**Description**:
Frontend service for user mapping API calls

**Acceptance Criteria**:
- [ ] Function: uploadUserMappings(file: File): Promise<ImportResult>
- [ ] Uses axios POST to /api/import/upload-user-mappings
- [ ] Creates FormData with xlsxFile field
- [ ] Sets Content-Type: multipart/form-data header
- [ ] Returns ImportResult type
- [ ] Throws error on HTTP error responses
- [ ] Function: getSampleFileUrl(): string - returns sample file download URL
- [ ] TypeScript interfaces for ImportResult and ErrorResponse

**Files**:
- `src/frontend/src/services/userMappingService.ts`

**Interfaces**:
```typescript
interface ImportResult {
  message: string;
  imported: number;
  skipped: number;
  errors?: string[];
}
```

---

### Task 4.2: Create UserMappingUpload Component ⚪
**Assignee**: Frontend Developer
**Effort**: 1.5 hours
**Priority**: P0 (Critical)
**Dependencies**: Task 4.1

**Description**:
React component for user mapping file upload

**Acceptance Criteria**:
- [ ] File input field (accept=".xlsx")
- [ ] Upload button with loading state (disabled during upload)
- [ ] File requirements card (format, size, columns, sample download link)
- [ ] Result display on success (green alert with imported/skipped counts)
- [ ] Error display on failure (red alert with error message)
- [ ] Error list display if ImportResult.errors is present
- [ ] useState hooks for file, uploading, result, error states
- [ ] handleFileChange function to update file state
- [ ] handleUpload async function to call uploadUserMappings service
- [ ] Clear file input after successful upload
- [ ] Bootstrap 5 styling (cards, alerts, buttons, forms)
- [ ] Icons from Bootstrap Icons (bi-upload, bi-download, bi-exclamation-triangle)
- [ ] Responsive layout (container, rows, cols)
- [ ] Accessible labels and ARIA attributes

**Files**:
- `src/frontend/src/components/UserMappingUpload.tsx`

**Component Structure**:
```tsx
<div className="container mt-4">
  <h2>User Mapping Upload</h2>
  <div className="card">{/* File requirements */}</div>
  <div className="mb-3">{/* File input */}</div>
  <button>{/* Upload button */}</button>
  {error && <div className="alert alert-danger">{/* Error */}</div>}
  {result && <div className="alert alert-success">{/* Success */}</div>}
</div>
```

---

### Task 4.3: Add User Mappings Card to Admin Page ⚪
**Assignee**: Frontend Developer
**Effort**: 10 min
**Priority**: P1 (High)
**Dependencies**: None

**Description**:
Add new card to Admin page for User Mappings feature

**Acceptance Criteria**:
- [ ] New card added after "MCP API Keys" card
- [ ] Card title: "User Mappings" with diagram icon (bi-diagram-3-fill)
- [ ] Card text: Brief description of feature
- [ ] Button: "Manage Mappings" linking to /admin/user-mappings
- [ ] Follows existing card pattern (col-md-4, card, card-body, etc.)

**Files**:
- `src/frontend/src/components/AdminPage.tsx` (modify existing)

**Code Snippet**:
```tsx
<div className="col-md-4 mb-3">
  <div className="card">
    <div className="card-body">
      <h5 className="card-title">
        <i className="bi bi-diagram-3-fill me-2"></i>User Mappings
      </h5>
      <p className="card-text">Upload and manage user-to-AWS-account-to-domain mappings.</p>
      <a href="/admin/user-mappings" className="btn btn-primary">Manage Mappings</a>
    </div>
  </div>
</div>
```

---

### Task 4.4: Create Admin User Mappings Page ⚪
**Assignee**: Frontend Developer
**Effort**: 15 min
**Priority**: P1 (High)
**Dependencies**: Task 4.2

**Description**:
Create Astro page for user mapping management

**Acceptance Criteria**:
- [ ] Page created at /admin/user-mappings route
- [ ] Uses Layout component
- [ ] Breadcrumb navigation (Home > Admin > User Mappings)
- [ ] Renders UserMappingUpload component with client:load directive
- [ ] Page title: "User Mappings - Admin"

**Files**:
- `src/frontend/src/pages/admin/user-mappings.astro`

**Page Structure**:
```astro
---
import Layout from '../../layouts/Layout.astro';
import UserMappingUpload from '../../components/UserMappingUpload';
---
<Layout title="User Mappings - Admin">
  <div class="container-fluid">
    <nav aria-label="breadcrumb">...</nav>
    <UserMappingUpload client:load />
  </div>
</Layout>
```

---

## Phase 5: Test Data & E2E Tests ⚪

### Task 5.1: Create Sample Excel Template ⚪
**Assignee**: QA / Developer
**Effort**: 30 min
**Priority**: P1 (High)
**Dependencies**: None

**Description**:
Create sample Excel template for user download

**Acceptance Criteria**:
- [ ] File created: `src/frontend/public/sample-files/user-mapping-template.xlsx`
- [ ] Sheet 1 "Mappings": 
  - Headers: Email Address, AWS Account ID, Domain
  - 3 sample data rows
- [ ] Sheet 2 "Instructions":
  - Format description
  - Validation rules
  - Examples of valid/invalid data
  - Usage instructions
- [ ] File size <50KB
- [ ] File opens correctly in Excel/LibreOffice

**Template Rows**:
```
user1@example.com, 123456789012, example.com
user2@example.com, 987654321098, example.com
consultant@agency.com, 555555555555, clientA.com
```

---

### Task 5.2: Create Test Data Files ⚪
**Assignee**: QA / Developer
**Effort**: 30 min
**Priority**: P0 (Critical)
**Dependencies**: None

**Description**:
Create Excel test files for E2E testing

**Acceptance Criteria**:
- [ ] File: `testdata/user-mappings-valid.xlsx` - 5 valid rows
- [ ] File: `testdata/user-mappings-invalid-email.xlsx` - 1 invalid email, 1 valid row
- [ ] File: `testdata/user-mappings-invalid-aws.xlsx` - 1 invalid AWS account (too short), 1 valid
- [ ] File: `testdata/user-mappings-invalid-aws-nonnumeric.xlsx` - 1 non-numeric AWS account
- [ ] File: `testdata/user-mappings-invalid-domain.xlsx` - 1 invalid domain (space), 1 valid
- [ ] File: `testdata/user-mappings-duplicates.xlsx` - 3 rows, 1 duplicate
- [ ] File: `testdata/user-mappings-missing-column.xlsx` - Missing "Domain" column
- [ ] File: `testdata/user-mappings-empty.xlsx` - Only headers, no data rows
- [ ] File: `testdata/user-mappings-large.csv` - CSV file (wrong format)
- [ ] All files <1MB

**Test Data Matrix**:
| File | Rows | Valid | Invalid | Duplicates | Expected Result |
|------|------|-------|---------|------------|-----------------|
| valid | 5 | 5 | 0 | 0 | Import 5, skip 0 |
| invalid-email | 2 | 1 | 1 | 0 | Import 1, skip 1 |
| invalid-aws | 2 | 1 | 1 | 0 | Import 1, skip 1 |
| duplicates | 3 | 3 | 0 | 1 | Import 2, skip 1 |
| missing-column | 2 | 0 | 0 | 0 | Error: missing column |
| empty | 0 | 0 | 0 | 0 | Error: no data |

---

### Task 5.3: Write E2E Tests ⚪
**Assignee**: QA / Developer
**Effort**: 2 hours
**Priority**: P0 (Critical)
**Dependencies**: Task 4.4, 5.2

**Description**:
Playwright E2E tests for user mapping upload

**Acceptance Criteria**:
- [ ] Test file: `src/frontend/tests/e2e/user-mapping-upload.spec.ts`
- [ ] Test setup: Login as admin before each test
- [ ] Test 1: Upload valid file → success message with "Imported 5"
- [ ] Test 2: Upload file with invalid email → partial success, error details shown
- [ ] Test 3: Upload file with invalid AWS account → partial success
- [ ] Test 4: Upload file with missing column → error message
- [ ] Test 5: Upload duplicate mappings → "Skipped: X" message
- [ ] Test 6: Upload empty file → error message
- [ ] Test 7: Upload CSV file → error "Only .xlsx files supported"
- [ ] Test 8: Non-admin access → "Access Denied" message
- [ ] Test 9: Download sample file → file downloads successfully
- [ ] Test 10: Upload oversized file (>10MB) → error "File too large"
- [ ] All tests use test data files from testdata/ directory
- [ ] Tests clean up database after each test
- [ ] Tests are stable (no flakiness)

**Files**:
- `src/frontend/tests/e2e/user-mapping-upload.spec.ts`

**Test Execution**:
```bash
npm run test:e2e -- user-mapping-upload
```

---

## Phase 6: Documentation & Polish ⚪

### Task 6.1: Update CLAUDE.md ⚪
**Assignee**: Tech Lead / Developer
**Effort**: 20 min
**Priority**: P1 (High)
**Dependencies**: All previous tasks

**Description**:
Update project documentation with new feature

**Acceptance Criteria**:
- [ ] Add UserMapping to "Key Entities" section
- [ ] Add upload endpoint to "API Endpoints" section
- [ ] Add Feature 013 to "Recent Changes" section
- [ ] Update entity count in "Architecture" section
- [ ] Add validation patterns to "Common Patterns" section

**Files**:
- `CLAUDE.md`

**Sections to Update**:
```markdown
### UserMapping (NEW - Feature 013)
- **Fields**: id, email, awsAccountId, domain, createdAt, updatedAt
- **Validation**: Email format, AWS account (12 digits), domain format
- **Relationships**: Independent (no FK to User)
- **Indexes**: Unique composite (email, awsAccountId, domain)
- **Access**: ADMIN role only

### User Mapping Upload (NEW - Feature 013)
- `POST /api/import/upload-user-mappings` - Upload Excel (ADMIN only)
```

---

### Task 6.2: Create Feature README ⚪
**Assignee**: Tech Lead / Developer
**Effort**: 15 min
**Priority**: P2 (Medium)
**Dependencies**: All previous tasks

**Description**:
Create README summarizing the feature

**Acceptance Criteria**:
- [ ] File: `specs/013-user-mapping-upload/README.md`
- [ ] Contains: Feature overview, purpose, key entities, API endpoints
- [ ] Contains: Links to spec.md, data-model.md, plan.md, quickstart.md
- [ ] Contains: Quick start command examples
- [ ] Contains: Known limitations and future enhancements
- [ ] Markdown formatted, concise (<300 lines)

**Files**:
- `specs/013-user-mapping-upload/README.md`

---

### Task 6.3: Add Inline Code Documentation ⚪
**Assignee**: Developer
**Effort**: 30 min
**Priority**: P2 (Medium)
**Dependencies**: All implementation tasks

**Description**:
Add KDoc and JSDoc comments to all new code

**Acceptance Criteria**:
- [ ] UserMapping entity: Class-level KDoc describing purpose
- [ ] UserMappingRepository: Method-level KDoc for each query
- [ ] UserMappingImportService: Class-level KDoc, method-level KDoc for public methods
- [ ] ImportController.uploadUserMappings: KDoc with endpoint details, parameters, returns
- [ ] userMappingService.ts: JSDoc for uploadUserMappings function
- [ ] UserMappingUpload.tsx: Component-level JSDoc
- [ ] All comments use standard format (/** ... */)
- [ ] Comments explain WHY, not WHAT (code is self-explanatory)

**Example KDoc**:
```kotlin
/**
 * Import user mappings from Excel file.
 * 
 * Parses Excel file with columns: Email Address, AWS Account ID, Domain.
 * Validates each row and creates UserMapping records.
 * Skips invalid rows and duplicates.
 * 
 * @param inputStream Excel file input stream (.xlsx format)
 * @return ImportResult with counts of imported/skipped records and error details
 * @throws IllegalArgumentException if file format is invalid
 */
@Transactional
open fun importFromExcel(inputStream: InputStream): ImportResult { ... }
```

---

### Task 6.4: Code Review & Cleanup ⚪
**Assignee**: Tech Lead
**Effort**: 1 hour
**Priority**: P1 (High)
**Dependencies**: All implementation tasks

**Description**:
Review all code for quality, consistency, and completeness

**Acceptance Criteria**:
- [ ] All code follows Kotlin/TypeScript style guide
- [ ] No console.log or debug statements left in production code
- [ ] All TODOs resolved or documented as future work
- [ ] All imports are used (no unused imports)
- [ ] Variable/function names are descriptive and consistent
- [ ] Error messages are user-friendly
- [ ] No hardcoded values (use constants)
- [ ] All nullable types handled correctly
- [ ] All async operations have error handling
- [ ] Code is DRY (no duplication)

**Review Checklist**:
- [ ] Entity annotations correct
- [ ] Repository methods efficient
- [ ] Service validation comprehensive
- [ ] Controller security enforced
- [ ] Frontend error handling complete
- [ ] Tests cover all edge cases
- [ ] Documentation accurate

---

## Phase 7: Testing & QA ⚪

### Task 7.1: Manual Testing ⚪
**Assignee**: QA
**Effort**: 1 hour
**Priority**: P0 (Critical)
**Dependencies**: All implementation tasks

**Description**:
Manual testing of complete feature flow

**Test Scenarios**:
- [ ] Login as admin → navigate to Admin page → click "User Mappings"
- [ ] Download sample file → verify file opens in Excel
- [ ] Upload sample file → verify success message
- [ ] Check database → verify records created
- [ ] Upload same file again → verify duplicates skipped
- [ ] Upload file with 100 rows → verify performance acceptable
- [ ] Upload file with invalid data → verify partial success, error details shown
- [ ] Upload 10MB file → verify upload completes (may take 20s)
- [ ] Upload 11MB file → verify error "File too large"
- [ ] Upload CSV file → verify error "Only .xlsx supported"
- [ ] Login as regular user → navigate to /admin/user-mappings → verify "Access Denied"
- [ ] Test in Chrome, Firefox, Safari
- [ ] Test responsive layout (mobile, tablet)

**Defect Tracking**:
- Log any bugs in issue tracker
- Prioritize blockers (P0) for immediate fix
- Document minor issues (P2-P3) for future sprints

---

### Task 7.2: Performance Testing ⚪
**Assignee**: QA / Developer
**Effort**: 30 min
**Priority**: P2 (Medium)
**Dependencies**: Task 7.1

**Description**:
Verify performance meets targets

**Performance Tests**:
- [ ] Upload 100-row file → measure time (target: <2s)
- [ ] Upload 1000-row file → measure time (target: <10s)
- [ ] Upload 10MB file → measure time (target: <20s)
- [ ] Database query by email → measure time (target: <10ms)
- [ ] Page load time → measure time (target: <1s)
- [ ] Test on slow network (throttled to 3G) → verify reasonable experience

**Performance Report**:
| Test | Target | Actual | Status |
|------|--------|--------|--------|
| 100 rows | <2s | __ | __ |
| 1000 rows | <10s | __ | __ |
| 10MB file | <20s | __ | __ |
| Query | <10ms | __ | __ |
| Page load | <1s | __ | __ |

---

### Task 7.3: Security Testing ⚪
**Assignee**: Security / QA
**Effort**: 30 min
**Priority**: P0 (Critical)
**Dependencies**: Task 7.1

**Description**:
Verify security controls are effective

**Security Tests**:
- [ ] Upload endpoint requires authentication → verify 401 for anonymous
- [ ] Upload endpoint requires ADMIN role → verify 403 for USER role
- [ ] Page requires admin permissions → verify redirect/error for non-admin
- [ ] File size limit enforced → verify rejection of large files
- [ ] File type validation enforced → verify rejection of non-xlsx files
- [ ] SQL injection attempt in email field → verify no database impact
- [ ] XSS attempt in domain field → verify no script execution
- [ ] Path traversal in filename → verify no file system access
- [ ] Duplicate request (CSRF) → verify idempotent behavior

**Security Report**:
- [ ] No vulnerabilities found
- [ ] All access controls working
- [ ] File upload restrictions enforced
- [ ] Input validation effective

---

## Phase 8: Deployment ⚪

### Task 8.1: Database Migration Verification ⚪
**Assignee**: DBA / DevOps
**Effort**: 15 min
**Priority**: P0 (Critical)
**Dependencies**: Task 1.1

**Description**:
Verify Hibernate auto-creates table correctly

**Acceptance Criteria**:
- [ ] Start backend application
- [ ] Check logs for "create table user_mapping" SQL
- [ ] Verify table created in database
- [ ] Verify columns match entity definition
- [ ] Verify indexes created correctly
- [ ] Verify unique constraint created
- [ ] Test rollback: drop table, restart app, verify recreation

**Commands**:
```bash
./gradlew run  # Start backend
# Check logs for SQL statements
# Connect to database
mysql -u root -p secman
SHOW CREATE TABLE user_mapping;
DESCRIBE user_mapping;
SHOW INDEX FROM user_mapping;
```

---

### Task 8.2: Deploy to Staging ⚪
**Assignee**: DevOps
**Effort**: 30 min
**Priority**: P0 (Critical)
**Dependencies**: All testing tasks

**Description**:
Deploy feature to staging environment

**Acceptance Criteria**:
- [ ] Merge feature branch to develop
- [ ] Build backend: `./gradlew build`
- [ ] Build frontend: `npm run build`
- [ ] Deploy to staging servers
- [ ] Verify table created in staging database
- [ ] Smoke test: Upload sample file
- [ ] Verify logs for errors
- [ ] Rollback plan documented

**Deployment Steps**:
1. Merge branch: `git merge 013-user-mapping-upload`
2. Run tests: `./gradlew test && npm test`
3. Build artifacts: `./gradlew build && npm run build`
4. Deploy backend: `docker-compose up -d backend`
5. Deploy frontend: `docker-compose up -d frontend`
6. Check logs: `docker-compose logs -f`
7. Test feature: Upload file via UI

---

### Task 8.3: Deploy to Production ⚪
**Assignee**: DevOps
**Effort**: 30 min
**Priority**: P0 (Critical)
**Dependencies**: Task 8.2

**Description**:
Deploy feature to production environment

**Acceptance Criteria**:
- [ ] Staging testing complete, no critical issues
- [ ] Merge develop to main
- [ ] Tag release: `git tag v1.13.0`
- [ ] Build production artifacts
- [ ] Deploy to production servers
- [ ] Verify table created in production database
- [ ] Smoke test: Upload sample file
- [ ] Monitor logs for 24 hours
- [ ] Notify admin users of new feature

**Deployment Checklist**:
- [ ] Database backup taken
- [ ] Rollback plan ready
- [ ] Deployment window scheduled (low traffic period)
- [ ] On-call support available
- [ ] Monitoring alerts configured
- [ ] User communication prepared

---

## Summary Statistics

### Tasks by Phase
- Phase 1: Backend Foundation - 3 tasks
- Phase 2: Backend Service - 2 tasks
- Phase 3: Backend API - 2 tasks
- Phase 4: Frontend - 4 tasks
- Phase 5: E2E Tests - 3 tasks
- Phase 6: Documentation - 4 tasks
- Phase 7: QA - 3 tasks
- Phase 8: Deployment - 3 tasks

**Total Tasks**: 24

### Estimated Effort
- Backend: 6.5 hours
- Frontend: 2.5 hours
- Testing: 3 hours
- Documentation: 1.5 hours
- Deployment: 1.5 hours

**Total Effort**: ~15 hours (2 days)

### Priority Breakdown
- P0 (Critical): 15 tasks
- P1 (High): 6 tasks
- P2 (Medium): 3 tasks

---

## Risk Register

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Excel parsing edge cases | Medium | Medium | Comprehensive test data, reuse existing patterns |
| Performance with large files | Low | Medium | File size limit, batch processing |
| Unique constraint conflicts | Low | Low | Duplicate check before insert |
| Browser compatibility issues | Low | Low | Test in multiple browsers |
| Security vulnerabilities | Low | High | Security testing, role enforcement |

---

## Dependencies

### External
- Apache POI library (already included)
- Bootstrap 5 (already included)
- Axios (already included)

### Internal
- Authentication system (existing)
- Admin role infrastructure (existing)
- ImportController (existing, extend)
- AdminPage component (existing, extend)

---

## Notes

- Follow TDD approach: Write tests first, then implementation
- Reuse existing patterns from VulnerabilityImport and RequirementsImport features
- Keep backend and frontend changes synchronized
- Document all decisions and rationale in code comments
- Regularly commit progress with descriptive messages

---

## Approval

- [ ] Product Owner: _______________
- [ ] Tech Lead: _______________
- [ ] Backend Developer: _______________
- [ ] Frontend Developer: _______________
- [ ] QA Lead: _______________
- [ ] DevOps: _______________
- [ ] Date: _______________
