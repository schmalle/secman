# Tasks: VULN Role & Vulnerability Management UI

**Feature**: 004-i-want-to
**Input**: Design documents from `/Users/flake/sources/misc/secman/specs/004-i-want-to/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → Extract: Kotlin/Micronaut backend, React/Astro frontend, MariaDB
2. Load design documents:
   → data-model.md: VulnerabilityException entity, User.Role enum
   → contracts/: 5 API endpoint specs
   → research.md: RBAC patterns, query approach, UI patterns
3. Generate tasks by category:
   → Setup: Entity and enum modifications
   → Tests: 5 contract tests + 2 unit tests + 1 integration test
   → Core: Repository, services, controller, DTOs
   → Frontend: Components, pages, sidebar
   → Polish: E2E tests, user management, docs
4. Apply task rules:
   → Different files = mark [P] for parallel
   → Tests before implementation (TDD)
   → Dependencies prevent parallelization
5. Number tasks sequentially (T001-T027)
6. Validate: All contracts have tests, all entities have models
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Phase 3.1: Setup & Data Model

### T001: Update User.Role enum to add VULN
**File**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`
**Action**: Add `VULN` to the `Role` enum (line 45)
```kotlin
enum class Role {
    USER, ADMIN, VULN  // Add VULN
}
```
**Test**: Verify Hibernate updates enum values on startup (check logs)

---

### T002: Create VulnerabilityException entity
**File**: `src/backendng/src/main/kotlin/com/secman/domain/VulnerabilityException.kt`
**Action**: Create new JPA entity with:
- Fields: id, exceptionType (enum), targetValue, expirationDate, reason, createdBy, createdAt, updatedAt
- Nested enum: `ExceptionType { IP, PRODUCT }`
- Methods: `isActive()`, `matches(vulnerability, asset)`
- Indexes: `idx_vuln_exception_type`, `idx_vuln_exception_expiration`
- Validation: `@NotBlank`, `@NotNull`, `@Size` annotations

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/data-model.md` lines 27-110
**Test**: Hibernate auto-creates `vulnerability_exception` table on startup

---

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### T003 [P]: Contract test GET /api/vulnerabilities/current
**File**: `src/backendng/src/test/kotlin/com/secman/controller/VulnerabilityManagementControllerTest.kt`
**Action**: Create test class with:
- `testGetCurrentVulnerabilitiesRequiresAuth()` - Verify 401 without JWT
- `testGetCurrentVulnerabilitiesRequiresVulnRole()` - Verify 403 for normal user
- `testGetCurrentVulnerabilitiesReturnsLatestScansOnly()` - Verify only latest scan per asset returned
- `testGetCurrentVulnerabilitiesFiltersBySeverity()` - Verify severity filter works
- `testGetCurrentVulnerabilitiesFiltersbyExceptionStatus()` - Verify exception filter works

**Contract**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/contracts/get-current-vulnerabilities.yaml`
**Expected**: All tests FAIL (controller doesn't exist yet)

---

### T004 [P]: Contract test GET /api/vulnerability-exceptions
**File**: `src/backendng/src/test/kotlin/com/secman/controller/VulnerabilityManagementControllerTest.kt`
**Action**: Add test methods:
- `testGetVulnerabilityExceptionsRequiresAuth()` - Verify 401 without JWT
- `testGetVulnerabilityExceptionsRequiresVulnRole()` - Verify 403 for normal user
- `testGetVulnerabilityExceptionsReturnsAll()` - Verify returns all exceptions
- `testGetVulnerabilityExceptionsFiltersActiveOnly()` - Verify activeOnly filter

**Contract**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/contracts/get-vulnerability-exceptions.yaml`
**Expected**: All tests FAIL

---

### T005 [P]: Contract test POST /api/vulnerability-exceptions
**File**: `src/backendng/src/test/kotlin/com/secman/controller/VulnerabilityManagementControllerTest.kt`
**Action**: Add test methods:
- `testPostExceptionRequiresVulnRole()` - Verify 403 for normal user
- `testPostExceptionValidation()` - Verify 400 for invalid data (missing reason, invalid type)
- `testPostExceptionCreatesSuccessfully()` - Verify 201 with valid data
- `testPostExceptionSetsCreatedBy()` - Verify createdBy field set from authentication

**Contract**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/contracts/post-vulnerability-exception.yaml`
**Expected**: All tests FAIL

---

### T006 [P]: Contract test PUT /api/vulnerability-exceptions/{id}
**File**: `src/backendng/src/test/kotlin/com/secman/controller/VulnerabilityManagementControllerTest.kt`
**Action**: Add test methods:
- `testPutExceptionRequiresVulnRole()` - Verify 403 for normal user
- `testPutExceptionValidation()` - Verify 400 for invalid data
- `testPutExceptionNotFound()` - Verify 404 for non-existent ID
- `testPutExceptionUpdatesSuccessfully()` - Verify 200 with updated data

**Contract**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/contracts/put-vulnerability-exception.yaml`
**Expected**: All tests FAIL

---

### T007 [P]: Contract test DELETE /api/vulnerability-exceptions/{id}
**File**: `src/backendng/src/test/kotlin/com/secman/controller/VulnerabilityManagementControllerTest.kt`
**Action**: Add test methods:
- `testDeleteExceptionRequiresVulnRole()` - Verify 403 for normal user
- `testDeleteExceptionNotFound()` - Verify 404 for non-existent ID
- `testDeleteExceptionSucceeds()` - Verify 204 on successful delete

**Contract**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/contracts/delete-vulnerability-exception.yaml`
**Expected**: All tests FAIL

---

### T008 [P]: Unit test VulnerabilityService
**File**: `src/backendng/src/test/kotlin/com/secman/service/VulnerabilityServiceTest.kt`
**Action**: Create test class with MockK:
- `testGetCurrentVulnerabilitiesReturnsLatestPerAsset()` - Mock repository, verify groupBy logic
- `testGetCurrentVulnerabilitiesWithSeverityFilter()` - Verify filtering logic
- `testGetCurrentVulnerabilitiesAppliesExceptions()` - Verify hasException field set correctly

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/research.md` lines 47-88
**Expected**: All tests FAIL (service doesn't exist)

---

### T009 [P]: Unit test VulnerabilityExceptionService
**File**: `src/backendng/src/test/kotlin/com/secman/service/VulnerabilityExceptionServiceTest.kt`
**Action**: Create test class with MockK:
- `testGetActiveExceptionsFiltersExpired()` - Verify only active exceptions returned
- `testIsVulnerabilityExceptedMatchesIP()` - Verify IP matching logic
- `testIsVulnerabilityExceptedMatchesProduct()` - Verify product contains matching
- `testIsVulnerabilityExceptedIgnoresExpired()` - Verify expired exceptions don't match

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/research.md` lines 91-131
**Expected**: All tests FAIL (service doesn't exist)

---

### T010 [P]: Integration test VULN role authorization
**File**: `src/backendng/src/test/kotlin/com/secman/integration/VulnRoleAuthorizationTest.kt`
**Action**: Create integration test with:
- `testNormalUserCannotAccessVulnEndpoints()` - Verify 403 for all 5 endpoints with USER role
- `testAdminCanAccessVulnEndpoints()` - Verify 200 for all endpoints with ADMIN role
- `testVulnRoleCanAccessVulnEndpoints()` - Verify 200 for all endpoints with VULN role

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/plan.md` lines 190-200
**Expected**: All tests FAIL (endpoints don't exist)

---

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### T011: Create VulnerabilityExceptionRepository
**File**: `src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityExceptionRepository.kt`
**Action**: Create JPA repository extending JpaRepository:
- Derived query: `findByExpirationDateIsNullOrExpirationDateGreaterThan(date: LocalDateTime)`
- Derived query: `findByExceptionType(type: ExceptionType)`
- Derived query: `findByCreatedBy(username: String)`

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/data-model.md` lines 147-170
**Dependencies**: T002 (VulnerabilityException entity must exist)

---

### T012: Create VulnerabilityException DTOs
**File**: `src/backendng/src/main/kotlin/com/secman/dto/VulnerabilityExceptionDto.kt`
**Action**: Create data classes:
- `VulnerabilityExceptionDto` - Response DTO with all fields + isActive + affectedVulnerabilityCount
- `CreateVulnerabilityExceptionRequest` - Request DTO for POST
- `UpdateVulnerabilityExceptionRequest` - Request DTO for PUT
- `VulnerabilityWithExceptionDto` - Extend existing VulnerabilityDto with hasException + exceptionReason fields

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/data-model.md` lines 172-217
**Dependencies**: T002 (entity must exist for enum references)

---

### T013: Implement VulnerabilityService
**File**: `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityService.kt`
**Action**: Create @Singleton service with:
- Inject: `vulnerabilityRepository`, `assetRepository`, `vulnerabilityExceptionService`
- Method: `getCurrentVulnerabilities(severity: String?, system: String?, exceptionStatus: String?): List<VulnerabilityWithExceptionDto>`
  - Query all vulnerabilities ordered by scanTimestamp DESC
  - Group by assetId, get max scanTimestamp per asset
  - Filter to only vulnerabilities with latest scan timestamp
  - Apply severity, system, exceptionStatus filters
  - For each vulnerability, check if excepted via `vulnerabilityExceptionService.isVulnerabilityExcepted()`
  - Map to VulnerabilityWithExceptionDto with hasException + exceptionReason

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/research.md` lines 47-88
**Dependencies**: T011 (repository), T012 (DTOs), T008 (unit tests MUST be failing)
**Expected**: T008 unit tests now PASS

---

### T014: Implement VulnerabilityExceptionService
**File**: `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityExceptionService.kt`
**Action**: Create @Singleton service with:
- Inject: `vulnerabilityExceptionRepository`
- Method: `getActiveExceptions(): List<VulnerabilityException>` - Filter by expirationDate
- Method: `isVulnerabilityExcepted(vuln: Vulnerability, asset: Asset): Pair<Boolean, String?>` - Returns (isExcepted, reason)
  - Get active exceptions
  - Check IP match: `asset.ip == exception.targetValue`
  - Check product match: `vuln.vulnerableProductVersions?.contains(exception.targetValue)`
  - Return first matching exception's reason
- Method: `getAllExceptions(activeOnly: Boolean, type: ExceptionType?): List<VulnerabilityExceptionDto>` - List with filters
- Method: `createException(request: CreateVulnerabilityExceptionRequest, username: String): VulnerabilityExceptionDto`
- Method: `updateException(id: Long, request: UpdateVulnerabilityExceptionRequest): VulnerabilityExceptionDto`
- Method: `deleteException(id: Long)`

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/research.md` lines 91-131
**Dependencies**: T011 (repository), T012 (DTOs), T009 (unit tests MUST be failing)
**Expected**: T009 unit tests now PASS

---

### T015: Implement VulnerabilityManagementController
**File**: `src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityManagementController.kt`
**Action**: Create @Controller with @Secured(SecurityRule.IS_AUTHENTICATED):
- Inject: `vulnerabilityService`, `vulnerabilityExceptionService`
- `@Get("/api/vulnerabilities/current")` `@Secured("ADMIN", "VULN")` - Calls vulnerabilityService.getCurrentVulnerabilities()
- `@Get("/api/vulnerability-exceptions")` `@Secured("ADMIN", "VULN")` - Calls vulnerabilityExceptionService.getAllExceptions()
- `@Post("/api/vulnerability-exceptions")` `@Secured("ADMIN", "VULN")` - Calls vulnerabilityExceptionService.createException(), get username from authentication
- `@Put("/api/vulnerability-exceptions/{id}")` `@Secured("ADMIN", "VULN")` - Calls vulnerabilityExceptionService.updateException()
- `@Delete("/api/vulnerability-exceptions/{id}")` `@Secured("ADMIN", "VULN")` - Calls vulnerabilityExceptionService.deleteException(), return 204

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/contracts/` (all 5 YAML files)
**Dependencies**: T013, T014 (services), T003-T007 (contract tests MUST be failing)
**Expected**: T003-T007 contract tests now PASS, T010 integration test now PASS

---

## Phase 3.4: Frontend Implementation

### T016 [P]: Create vulnerabilityManagementService
**File**: `src/frontend/src/services/vulnerabilityManagementService.ts`
**Action**: Create Axios service with:
- `getCurrentVulnerabilities(severity?, system?, exceptionStatus?): Promise<VulnerabilityWithExceptionDto[]>`
- `getVulnerabilityExceptions(activeOnly?, type?): Promise<VulnerabilityExceptionDto[]>`
- `createVulnerabilityException(request: CreateRequest): Promise<VulnerabilityExceptionDto>`
- `updateVulnerabilityException(id: number, request: UpdateRequest): Promise<VulnerabilityExceptionDto>`
- `deleteVulnerabilityException(id: number): Promise<void>`
- All methods add JWT from sessionStorage to Authorization header

**Reference**: Existing pattern in `src/frontend/src/services/vulnerabilityService.ts`
**Dependencies**: None (independent file)

---

### T017 [P]: Update auth.ts utility
**File**: `src/frontend/src/utils/auth.ts`
**Action**: Add helper function:
```typescript
export function hasVulnAccess(): boolean {
    return hasRole('ADMIN') || hasRole('VULN');
}
```

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/research.md` lines 48-58
**Dependencies**: None (independent file)

---

### T018 [P]: Create CurrentVulnerabilitiesTable component
**File**: `src/frontend/src/components/CurrentVulnerabilitiesTable.tsx`
**Action**: Create React component with:
- State: vulnerabilities, severityFilter, systemFilter, exceptionFilter, sortField, sortOrder
- useEffect: Fetch vulnerabilities on mount via vulnerabilityManagementService
- Render: Bootstrap table with columns (System, IP, CVE, Severity, Product, Days Open, Scan Date, Exception)
- Filter dropdowns: Severity (Critical/High/Medium/Low), System (unique list), Exception Status (All/Excepted/Not Excepted)
- Sortable column headers
- Exception badge/indicator with tooltip showing reason

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/research.md` lines 164-203
**Pattern**: `src/frontend/src/components/AssetManagement.tsx` (table pattern)
**Dependencies**: T016 (service)

---

### T019 [P]: Create VulnerabilityExceptionForm component
**File**: `src/frontend/src/components/VulnerabilityExceptionForm.tsx`
**Action**: Create React component with:
- Props: exception (optional for edit), onSave, onCancel
- State: exceptionType, targetValue, expirationDate, reason, validation errors
- Form fields: Type (IP/PRODUCT radio), Target Value (input), Expiration Date (datetime-local input or null checkbox), Reason (textarea)
- Validation: Required fields, IP format for IP type, future date for expiration
- Submit: Call vulnerabilityManagementService.create or update
- Bootstrap form styling

**Reference**: Existing pattern in `src/frontend/src/components/VulnerabilityImportForm.tsx`
**Dependencies**: T016 (service)

---

### T020 [P]: Create VulnerabilityExceptionsTable component
**File**: `src/frontend/src/components/VulnerabilityExceptionsTable.tsx`
**Action**: Create React component with:
- State: exceptions, showForm, editingException
- useEffect: Fetch exceptions on mount
- Render: Bootstrap table with columns (Type, Target, Expiration, Reason, Created By, Status, Actions)
- Status badge: Green "Active" or Red "Expired" based on isActive
- Actions: Edit button (opens form modal), Delete button (confirm dialog)
- "Create Exception" button (opens form modal)
- Modal with VulnerabilityExceptionForm for create/edit

**Reference**: Pattern from `src/frontend/src/components/AssetManagement.tsx`
**Dependencies**: T016 (service), T019 (form component)

---

### T021: Modify Sidebar for Vuln Management menu
**File**: `src/frontend/src/components/Sidebar.tsx`
**Action**: Add state and JSX:
- Import `hasVulnAccess` from auth.ts
- Add state: `const [vulnMenuOpen, setVulnMenuOpen] = useState(false)`
- Add menu item after existing admin menu (around line 160):
```tsx
{hasVulnAccess() && (
    <>
        <li>
            <button onClick={() => setVulnMenuOpen(!vulnMenuOpen)} className="btn btn-link...">
                <i className="bi bi-shield-exclamation me-2"></i>
                Vuln Management
                <i className={`bi bi-chevron-${vulnMenuOpen ? 'down' : 'right'} ms-auto`}></i>
            </button>
        </li>
        {vulnMenuOpen && (
            <ul className="list-unstyled ps-4">
                <li><a href="/vulnerabilities/current">Vulns</a></li>
                <li><a href="/vulnerabilities/exceptions">Exceptions</a></li>
            </ul>
        )}
    </>
)}
```

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/research.md` lines 133-163
**Dependencies**: T017 (hasVulnAccess function)

---

### T022: Create Vulns page
**File**: `src/frontend/src/pages/vulnerabilities/current.astro`
**Action**: Create Astro page:
- Layout: BaseLayout with title "Current Vulnerabilities"
- Import and render `<CurrentVulnerabilitiesTable client:load />`
- Role check: Redirect to /login if not hasVulnAccess()

**Pattern**: `src/frontend/src/pages/assets.astro`
**Dependencies**: T018 (table component), T017 (hasVulnAccess)

---

### T023: Create Exceptions page
**File**: `src/frontend/src/pages/vulnerabilities/exceptions.astro`
**Action**: Create Astro page:
- Layout: BaseLayout with title "Vulnerability Exceptions"
- Import and render `<VulnerabilityExceptionsTable client:load />`
- Role check: Redirect to /login if not hasVulnAccess()

**Pattern**: `src/frontend/src/pages/assets.astro`
**Dependencies**: T020 (table component), T017 (hasVulnAccess)

---

## Phase 3.5: Integration & Polish

### T024 [P]: E2E test vuln role access
**File**: `src/frontend/tests/e2e/vuln-role-access.spec.ts`
**Action**: Create Playwright test:
- Test 1: Normal user cannot see "Vuln Management" in sidebar
- Test 2: Normal user gets 403 on direct URL access to /vulnerabilities/current
- Test 3: VULN user sees "Vuln Management" menu
- Test 4: VULN user can access both Vulns and Exceptions pages
- Test 5: ADMIN user has identical access to VULN user

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/quickstart.md` Scenarios 2, 3, 10
**Dependencies**: T021-T023 (pages exist)

---

### T025 [P]: E2E test vulnerability exceptions CRUD
**File**: `src/frontend/tests/e2e/vulnerability-exceptions.spec.ts`
**Action**: Create Playwright test:
- Test 1: Create IP-based exception, verify appears in table
- Test 2: Create product-based exception with expiration, verify active status
- Test 3: Edit exception, verify all fields updated
- Test 4: Delete exception, verify removed from table
- Test 5: Verify exception indicator appears on current vulnerabilities page

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/quickstart.md` Scenarios 5, 6, 7, 8
**Dependencies**: T021-T023 (pages exist)

---

### T026: Update user management UI for VULN role
**File**: `src/frontend/src/components/UserManagement.tsx`
**Action**: Modify user form to add VULN role checkbox:
- Find role checkboxes section (around line 200)
- Add checkbox:
```tsx
<div className="form-check">
    <input type="checkbox" id="role-vuln" checked={roles.includes('VULN')}
           onChange={() => toggleRole('VULN')} />
    <label htmlFor="role-vuln">VULN (Vulnerability Management)</label>
</div>
```
- Ensure toggleRole function handles VULN role

**Reference**: `/Users/flake/sources/misc/secman/specs/004-i-want-to/quickstart.md` Scenario 1
**Dependencies**: T001 (User.Role enum has VULN)

---

### T027: Update CLAUDE.md documentation
**File**: `/Users/flake/sources/misc/secman/CLAUDE.md`
**Action**: Already updated by `.specify/scripts/bash/update-agent-context.sh claude` in Phase 1
- Verify Recent Changes section includes Feature 004
- Verify VulnerabilityException entity in Key Entities
- Verify new API endpoints in API Endpoints section

**Reference**: Auto-updated in Phase 1, verify completeness
**Dependencies**: None (documentation task)

---

## Dependencies Graph

```
Setup:
  T001 (User.Role) → T012 (DTOs), T026 (UI)
  T002 (Entity) → T011 (Repository)

Tests (must fail first):
  T003-T007 [P] → T015 (Controller)
  T008 [P] → T013 (VulnerabilityService)
  T009 [P] → T014 (VulnerabilityExceptionService)
  T010 [P] → T015 (Controller)

Backend Core:
  T011 (Repository) → T013, T014 (Services)
  T012 (DTOs) → T013, T014, T015
  T013, T014 (Services) → T015 (Controller)

Frontend:
  T016 [P] (Service) → T018, T019, T020 (Components)
  T017 [P] (Auth) → T021, T022, T023 (Pages/Sidebar)
  T018 [P] (CurrentVulnTable) → T022 (Page)
  T019 [P] (Form) → T020 (ExceptionsTable)
  T020 (ExceptionsTable) → T023 (Page)
  T021 (Sidebar) → T024 (E2E)
  T022, T023 (Pages) → T024, T025 (E2E)

Polish:
  T024, T025 [P] → (no dependencies)
  T026 → (no dependencies)
  T027 → (no dependencies)
```

## Parallel Execution Examples

### Example 1: Run all contract tests in parallel (after T001-T002)
```bash
# Execute T003-T007 together:
claude code "Write contract test for GET /api/vulnerabilities/current in VulnerabilityManagementControllerTest.kt" &
claude code "Write contract test for GET /api/vulnerability-exceptions in VulnerabilityManagementControllerTest.kt" &
claude code "Write contract test for POST /api/vulnerability-exceptions in VulnerabilityManagementControllerTest.kt" &
claude code "Write contract test for PUT /api/vulnerability-exceptions/{id} in VulnerabilityManagementControllerTest.kt" &
claude code "Write contract test for DELETE /api/vulnerability-exceptions/{id} in VulnerabilityManagementControllerTest.kt" &
wait
```

### Example 2: Run unit tests in parallel (after T011-T012)
```bash
# Execute T008-T009 together:
claude code "Write unit tests for VulnerabilityService in VulnerabilityServiceTest.kt" &
claude code "Write unit tests for VulnerabilityExceptionService in VulnerabilityExceptionServiceTest.kt" &
wait
```

### Example 3: Run frontend components in parallel (after T016-T017)
```bash
# Execute T018-T020 together:
claude code "Create CurrentVulnerabilitiesTable component" &
claude code "Create VulnerabilityExceptionForm component" &
claude code "Create VulnerabilityExceptionsTable component" &
wait
```

### Example 4: Run E2E tests in parallel (after T021-T023)
```bash
# Execute T024-T025 together:
npx playwright test tests/e2e/vuln-role-access.spec.ts &
npx playwright test tests/e2e/vulnerability-exceptions.spec.ts &
wait
```

## Task Execution Checklist

**Setup Phase**:
- [x] T001: User.Role enum updated
- [x] T002: VulnerabilityException entity created

**Tests Phase** (All must FAIL before proceeding):
- [x] T003 [P]: GET current vulnerabilities contract test (FAILING)
- [x] T004 [P]: GET exceptions contract test (FAILING)
- [x] T005 [P]: POST exception contract test (FAILING)
- [x] T006 [P]: PUT exception contract test (FAILING)
- [x] T007 [P]: DELETE exception contract test (FAILING)
- [x] T008 [P]: VulnerabilityService unit test (FAILING)
- [x] T009 [P]: VulnerabilityExceptionService unit test (FAILING)
- [x] T010 [P]: VULN role authorization integration test (FAILING)

**Core Implementation** (Make tests PASS):
- [x] T011: VulnerabilityExceptionRepository created
- [x] T012: DTOs created
- [x] T013: VulnerabilityService implemented (T008 now PASSES)
- [x] T014: VulnerabilityExceptionService implemented (T009 now PASSES)
- [x] T015: VulnerabilityManagementController implemented (T003-T007, T010 now PASS)

**Frontend**:
- [x] T016 [P]: vulnerabilityManagementService created
- [x] T017 [P]: auth.ts updated with hasVulnAccess()
- [x] T018 [P]: CurrentVulnerabilitiesTable component
- [x] T019 [P]: VulnerabilityExceptionForm component
- [x] T020: VulnerabilityExceptionsTable component
- [x] T021: Sidebar modified with Vuln Management menu
- [x] T022: /vulnerabilities/current page created
- [x] T023: /vulnerabilities/exceptions page created

**Polish**:
- [x] T024 [P]: E2E test vuln role access
- [x] T025 [P]: E2E test exception CRUD
- [x] T026: User management UI updated
- [x] T027: CLAUDE.md verified

## Validation Checklist
*GATE: All must pass before feature is complete*

- [x] All contracts have corresponding tests (T003-T007 cover all 5 contracts)
- [x] All entities have model tasks (T002 covers VulnerabilityException)
- [x] All tests come before implementation (Phase 3.2 before 3.3)
- [x] Parallel tasks truly independent (different files, no shared state)
- [x] Each task specifies exact file path
- [x] No task modifies same file as another [P] task
- [x] TDD workflow enforced (tests MUST fail before implementation)

## Notes
- **[P] tasks** = different files, no dependencies, can run in parallel
- **Verify tests fail** before implementing (Red-Green-Refactor)
- **Commit after each task** for rollback safety
- **Run quickstart.md** after T027 for manual validation
- **Avoid**: vague tasks, same file conflicts, skipping tests

---

**Total Tasks**: 27
**Parallel Tasks**: 14 (marked with [P])
**Sequential Tasks**: 13
**Estimated Completion**: 23-25 developer hours (with parallelization: 15-18 hours)
