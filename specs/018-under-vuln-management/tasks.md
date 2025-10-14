# Tasks: Account Vulns - AWS Account-Based Vulnerability Overview

**Input**: Design documents from `/specs/018-under-vuln-management/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/account-vulns-api.yaml
**Branch**: `018-under-vuln-management`

**Tests**: TDD is NON-NEGOTIABLE per constitution. All tests MUST be written FIRST and FAIL before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4, US5)
- Include exact file paths in descriptions

## Path Conventions
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`, `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`, `src/frontend/tests/e2e/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization - verify existing structure is ready

- [ ] T001 Verify backend project structure exists at `src/backendng/`
- [ ] T002 Verify frontend project structure exists at `src/frontend/`
- [ ] T003 [P] Verify existing entities (UserMapping, Asset, Vulnerability, User) are accessible
- [ ] T004 [P] Verify existing repositories (UserMappingRepository, AssetRepository) are available

**Checkpoint**: Project structure validated, no setup required (uses existing infrastructure)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core DTOs and service interfaces that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Backend Foundation

- [ ] T005 [P] [Foundation] Create `AssetVulnCountDto.kt` in `src/backendng/src/main/kotlin/com/secman/dto/` with fields: id (Long), name (String), type (String), vulnerabilityCount (Int)
- [ ] T006 [P] [Foundation] Create `AccountGroupDto.kt` in `src/backendng/src/main/kotlin/com/secman/dto/` with fields: awsAccountId (String), assets (List<AssetVulnCountDto>), totalAssets (Int), totalVulnerabilities (Int)
- [ ] T007 [Foundation] Create `AccountVulnsSummaryDto.kt` in `src/backendng/src/main/kotlin/com/secman/dto/` with fields: accountGroups (List<AccountGroupDto>), totalAssets (Int), totalVulnerabilities (Int) - depends on T005, T006
- [ ] T008 [Foundation] Create `AccountVulnsService.kt` interface in `src/backendng/src/main/kotlin/com/secman/service/` with method signature: `fun getAccountVulnsSummary(userEmail: String): AccountVulnsSummaryDto`

### Frontend Foundation

- [ ] T009 [P] [Foundation] Create TypeScript interfaces in `src/frontend/src/services/accountVulnsService.ts`: AccountVulnsSummary, AccountGroup, AssetVulnCount matching backend DTOs
- [ ] T010 [P] [Foundation] Create `accountVulnsService.ts` API client wrapper with method: `getAccountVulns(): Promise<AccountVulnsSummary>` using existing axios instance from `src/frontend/src/services/api.ts`

**Checkpoint**: Foundation ready - all DTOs and service interfaces defined. User story implementation can now begin.

---

## Phase 3: User Story 1 - View Vulnerabilities for Single AWS Account (Priority: P1) 🎯 MVP

**Goal**: Enable non-admin users with ONE AWS account mapping to view all their assets with vulnerability counts

**Independent Test**: Create test user with one AWS account mapping (123456789012), add 5 assets with that cloudAccountId, import 10 vulnerabilities across assets, login, navigate to Account Vulns, verify single table displays all 5 assets with correct counts

### Tests for User Story 1 (Write FIRST, ensure FAIL)

- [ ] T011 [P] [US1] Create `AccountVulnsContractTest.kt` in `src/backendng/src/test/kotlin/com/secman/contract/` with test: `GET /api/account-vulns returns 200 for non-admin user with single AWS account mapping` - verify response has 1 account group with correct assets
- [ ] T012 [P] [US1] Add test to `AccountVulnsContractTest.kt`: `GET /api/account-vulns sorts assets by vulnerability count descending` - verify first asset has highest count
- [ ] T013 [P] [US1] Add test to `AccountVulnsContractTest.kt`: `GET /api/account-vulns includes assets with 0 vulnerabilities` - verify asset with 0 count appears in response
- [ ] T014 [P] [US1] Create `AccountVulnsServiceTest.kt` in `src/backendng/src/test/kotlin/com/secman/service/` with unit test: mock UserMappingRepository to return single AWS account, mock AssetRepository, verify service returns correct AccountVulnsSummaryDto structure
- [ ] T015 [P] [US1] Create `account-vulns.spec.ts` in `src/frontend/tests/e2e/` with test: `displays single account with assets and vulnerability counts` - use Playwright to login, navigate, verify table content

**Run all User Story 1 tests** - Expected: ALL FAIL (endpoints/services don't exist yet)

### Implementation for User Story 1

#### Backend Implementation

- [ ] T016 [US1] Implement `AccountVulnsService.kt` in `src/backendng/src/main/kotlin/com/secman/service/`:
  - Inject UserMappingRepository, AssetRepository
  - Query user_mapping by email for AWS account IDs (filter awsAccountId IS NOT NULL)
  - If empty list → throw NotFoundException
  - Query assets by cloudAccountId IN (accountIds)
  - Count vulnerabilities per asset using JPQL LEFT JOIN GROUP BY
  - Group assets by cloudAccountId
  - Sort assets within groups by vulnerability count DESC
  - Sort account groups by awsAccountId ASC
  - Return AccountVulnsSummaryDto

- [ ] T017 [US1] Create `AccountVulnsController.kt` in `src/backendng/src/main/kotlin/com/secman/controller/`:
  - Annotation: @Controller("/api/account-vulns"), @Secured(SecurityRule.IS_AUTHENTICATED)
  - Inject AccountVulnsService
  - GET endpoint: extract user email from Authentication principal
  - Call service.getAccountVulnsSummary(email)
  - Return ResponseEntity with 200 OK
  - Catch NotFoundException → return 404 with error message

#### Frontend Implementation

- [ ] T018 [US1] Create `AssetVulnTable.tsx` in `src/frontend/src/components/`:
  - Props: assets (AssetVulnCount[])
  - Render Bootstrap Table with columns: Asset Name, Type, Vulnerability Count
  - Make asset name clickable (link to `/assets/{id}`)

- [ ] T019 [US1] Create `AccountVulnsView.tsx` in `src/frontend/src/components/`:
  - Use React hooks: useState for data/loading/error
  - useEffect to call accountVulnsService.getAccountVulns()
  - Handle loading state (show spinner)
  - Handle error states (404, 403, 401)
  - For single account: render account ID header + summary + <AssetVulnTable>
  - Bootstrap styling

- [ ] T020 [US1] Create `account-vulns.astro` in `src/frontend/src/pages/`:
  - Import MainLayout
  - Import AccountVulnsView component
  - Render: `<MainLayout title="Account Vulns"><AccountVulnsView client:load /></MainLayout>`

**Run User Story 1 tests** - Expected: ALL PASS

**Checkpoint**: User Story 1 complete. Non-admin users with 1 AWS account can view their vulnerability overview. Test independently before proceeding.

---

## Phase 4: User Story 3 - Error Handling for Missing Account Mappings (Priority: P1)

**Goal**: Non-admin users with NO AWS account mappings see a clear error message

**Independent Test**: Create test user with zero user_mapping records, login, navigate to Account Vulns, verify error message displays: "No AWS accounts are mapped to your user account. Please contact your administrator."

**Note**: US3 before US2 because it's also P1 (critical error handling for MVP)

### Tests for User Story 3 (Write FIRST, ensure FAIL)

- [ ] T021 [P] [US3] Add test to `AccountVulnsContractTest.kt`: `GET /api/account-vulns returns 404 for user with no AWS account mappings` - verify 404 status, error message content
- [ ] T022 [P] [US3] Add test to `AccountVulnsContractTest.kt`: `GET /api/account-vulns returns 404 for user with only null awsAccountId mappings` - create user_mapping with null awsAccountId, verify 404
- [ ] T023 [P] [US3] Add test to `account-vulns.spec.ts`: `displays error message when user has no AWS account mappings` - use Playwright to test error state

**Run User Story 3 tests** - Expected: ALL FAIL (error handling not implemented yet)

### Implementation for User Story 3

#### Backend Implementation (Error Handling)

- [ ] T024 [US3] Update `AccountVulnsService.kt`:
  - After querying user_mapping, if awsAccountIds list is empty → throw `NotFoundException("No AWS accounts are mapped to your user account. Please contact your administrator.")`

- [ ] T025 [US3] Update `AccountVulnsController.kt`:
  - Add @Error handler for NotFoundException
  - Return 404 with JSON: `{ "message": exception.message, "status": 404 }`

#### Frontend Implementation (Error Display)

- [ ] T026 [US3] Update `AccountVulnsView.tsx`:
  - Add error state handling for 404 responses
  - Render Bootstrap Alert (danger) with error message from API
  - Add guidance text below: "AWS account mappings are required to view vulnerability data for your infrastructure"

**Run User Story 3 tests** - Expected: ALL PASS

**Checkpoint**: User Story 3 complete. Users without mappings see clear error message. Test independently.

---

## Phase 5: User Story 4 - Admin Role Redirect (Priority: P1)

**Goal**: Admin users see redirect message directing them to System Vulns view

**Independent Test**: Login as admin user, navigate to Account Vulns, verify redirect message displays: "Please use System Vulns view" with clickable link to `/system-vulns`

### Tests for User Story 4 (Write FIRST, ensure FAIL)

- [ ] T027 [P] [US4] Add test to `AccountVulnsContractTest.kt`: `GET /api/account-vulns returns 403 for ADMIN user` - create admin user, verify 403 status, message content, redirectUrl field
- [ ] T028 [P] [US4] Add test to `AccountVulnsContractTest.kt`: `GET /api/account-vulns returns 403 for user with both ADMIN and USER roles` - verify ADMIN takes precedence
- [ ] T029 [P] [US4] Add test to `account-vulns.spec.ts`: `admin user sees redirect message with link to System Vulns` - use Playwright to verify message and link functionality
- [ ] T030 [P] [US4] Add test to `account-vulns.spec.ts`: `Account Vulns menu item has disabled styling for admin users` - verify CSS class and tooltip

**Run User Story 4 tests** - Expected: ALL FAIL (admin check not implemented yet)

### Implementation for User Story 4

#### Backend Implementation (Admin Check)

- [ ] T031 [US4] Update `AccountVulnsController.kt`:
  - BEFORE calling service: check if authentication.roles.contains("ADMIN")
  - If admin → return 403 with JSON: `{ "message": "Please use System Vulns view", "redirectUrl": "/system-vulns", "status": 403 }`
  - Otherwise proceed to service call

#### Frontend Implementation (Admin Redirect + Menu Styling)

- [ ] T032 [US4] Update `AccountVulnsView.tsx`:
  - Add error state handling for 403 responses
  - Render Bootstrap Alert (info) with message: "Please use System Vulns view"
  - Add Button or Link to navigate to `/system-vulns` using redirectUrl from API response

- [ ] T033 [US4] Update `MainLayout.astro` in `src/frontend/src/layouts/`:
  - Check if current user has ADMIN role (read from session/auth context)
  - Add Account Vulns menu item under "Vuln Management" section
  - If admin: apply `.disabled` class + `.text-muted` class
  - If admin: add `title` attribute with tooltip text: "Admins should use System Vulns view"
  - Menu item always visible and clickable (navigates to account-vulns page where redirect message displays)

**Run User Story 4 tests** - Expected: ALL PASS

**Checkpoint**: User Story 4 complete. Admin users cannot access Account Vulns, see clear redirect message, menu has visual indicator. Test independently.

---

## Phase 6: User Story 2 - View Vulnerabilities for Multiple AWS Accounts with Grouping (Priority: P2)

**Goal**: Non-admin users with MULTIPLE AWS accounts see assets grouped by account with clear visual separation

**Independent Test**: Create test user with 3 AWS account mappings (123456789012, 555555555555, 987654321098), add assets to each account, login, navigate to Account Vulns, verify 3 distinct account groups displayed, sorted by account ID, each with summary header

### Tests for User Story 2 (Write FIRST, ensure FAIL)

- [ ] T034 [P] [US2] Add test to `AccountVulnsContractTest.kt`: `GET /api/account-vulns returns multiple account groups sorted by account ID ascending` - create user with 3 AWS accounts, verify 3 groups in response, verify order (123... before 555... before 987...)
- [ ] T035 [P] [US2] Add test to `AccountVulnsContractTest.kt`: `GET /api/account-vulns includes group summary with totalAssets and totalVulnerabilities per account` - verify summary fields match asset/vuln counts
- [ ] T036 [P] [US2] Add test to `AccountVulnsServiceTest.kt`: unit test for multiple account grouping logic - mock 3 AWS accounts, verify AccountGroupDto list is correctly structured and sorted
- [ ] T037 [P] [US2] Add test to `account-vulns.spec.ts`: `displays multiple account groups with Bootstrap Accordion` - use Playwright to verify 3 accordion items, expandable, correct labels

**Run User Story 2 tests** - Expected: ALL FAIL (multi-account grouping UI not implemented yet)

### Implementation for User Story 2

#### Backend Implementation (Multi-Account Grouping)

- [ ] T038 [US2] Update `AccountVulnsService.kt`:
  - Already implements multi-account grouping in T016 (no changes needed if T016 implemented correctly)
  - Verify: Account groups are created for EACH distinct AWS account ID
  - Verify: Account groups are sorted by awsAccountId (numerical ascending)
  - Verify: Each AccountGroupDto has totalAssets and totalVulnerabilities calculated

#### Frontend Implementation (Accordion Grouping)

- [ ] T039 [US2] Create `AccountVulnGroup.tsx` in `src/frontend/src/components/`:
  - Props: accountGroup (AccountGroup), index (number for accordion ID)
  - Render Bootstrap Accordion.Item with:
    - Header: "AWS Account: {awsAccountId} - {totalAssets} assets, {totalVulnerabilities} vulnerabilities"
    - Body: <AssetVulnTable assets={accountGroup.assets} />
  - Use React.memo to prevent unnecessary re-renders

- [ ] T040 [US2] Update `AccountVulnsView.tsx`:
  - Detect if accountGroups.length > 1 (multiple accounts)
  - If multiple accounts: render Bootstrap Accordion with multiple <AccountVulnGroup> components (one per group)
  - If single account (length === 1): render single account display (no accordion needed - existing US1 code)
  - Accordion default: All items expanded (or first 3 expanded if >3 accounts)

**Run User Story 2 tests** - Expected: ALL PASS

**Checkpoint**: User Story 2 complete. Users with multiple AWS accounts see clear grouping with accordion UI. Test independently - verify US1 still works (single account), US2 works (multiple accounts).

---

## Phase 7: User Story 5 - Asset Navigation and Detail View (Priority: P3)

**Goal**: Users can click asset names to view detailed vulnerability information

**Independent Test**: From Account Vulns page, click any asset name, verify navigation to `/assets/{id}` showing full vulnerability details, click Back, verify return to Account Vulns (not System Vulns)

### Tests for User Story 5 (Write FIRST, ensure FAIL)

- [ ] T041 [P] [US5] Add test to `account-vulns.spec.ts`: `clicking asset name navigates to asset detail page` - use Playwright to click asset link, verify URL changes to `/assets/\\d+`
- [ ] T042 [P] [US5] Add test to `account-vulns.spec.ts`: `asset detail page shows all vulnerabilities` - verify vulnerability table displays CVE IDs, severity, etc.
- [ ] T043 [P] [US5] Add test to `account-vulns.spec.ts`: `Back navigation returns to Account Vulns view` - from asset detail, click Back/breadcrumb, verify URL is `/account-vulns` not `/system-vulns`

**Run User Story 5 tests** - Expected: FAIL (navigation context not preserved)

### Implementation for User Story 5

#### Frontend Implementation (Navigation Context)

- [ ] T044 [US5] Update `AssetVulnTable.tsx`:
  - Asset name already clickable (implemented in T018)
  - Verify link uses React Router or Astro Link: `<a href={`/assets/${asset.id}?from=account-vulns`}>` (add query param for context)

- [ ] T045 [US5] Update existing asset detail page (if needed):
  - Check for `?from=account-vulns` query parameter
  - If present: render breadcrumb/Back button that links to `/account-vulns`
  - If not present: default breadcrumb behavior (link to `/system-vulns` or `/assets`)

**Run User Story 5 tests** - Expected: ALL PASS

**Checkpoint**: User Story 5 complete. Asset navigation preserves context. Test full flow: Account Vulns → Asset Detail → Back → Account Vulns.

---

## Phase 8: Per-Account Pagination (Priority: P2 - Enhancement to US2)

**Goal**: Accounts with >20 assets show "Load More" button to expand pagination

**Independent Test**: Create test user with 1 AWS account, add 25 assets to that account, login, navigate to Account Vulns, verify first 20 assets displayed, "Load More" button present, click button, verify 25 assets now visible

### Tests for Pagination (Write FIRST, ensure FAIL)

- [ ] T046 [P] [Pagination] Add test to `account-vulns.spec.ts`: `displays Load More button when account has more than 20 assets` - create 25 assets in one account, verify button exists
- [ ] T047 [P] [Pagination] Add test to `account-vulns.spec.ts`: `clicking Load More displays next 20 assets` - verify asset count increases from 20 to 25, button disappears
- [ ] T048 [P] [Pagination] Add test to `account-vulns.spec.ts`: `each account group has independent pagination` - create 25 assets in account A, 15 in account B, verify Load More only on account A

**Run Pagination tests** - Expected: ALL FAIL (pagination not implemented yet)

### Implementation for Pagination

- [ ] T049 [Pagination] Update `AccountVulnGroup.tsx`:
  - Add React state: `const [currentPage, setCurrentPage] = useState(0)`
  - Constant: `const pageSize = 20`
  - Calculate visible assets: `const visibleAssets = accountGroup.assets.slice(0, (currentPage + 1) * pageSize)`
  - Render visibleAssets in <AssetVulnTable>
  - Show "Load More" button if `accountGroup.assets.length > (currentPage + 1) * pageSize`
  - Button onClick: `setCurrentPage(currentPage + 1)`

**Run Pagination tests** - Expected: ALL PASS

**Checkpoint**: Pagination complete. Large accounts (>20 assets) use Load More button. Test with 25, 50, 100 assets.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories or overall quality

- [ ] T050 [P] [Polish] Add loading skeleton to `AccountVulnsView.tsx` for better UX during API call (Bootstrap Placeholder components)
- [ ] T051 [P] [Polish] Add empty state message in `AccountVulnGroup.tsx`: "No assets found for AWS account {id}" when accountGroup.assets.length === 0 (satisfies edge case requirement)
- [ ] T052 [P] [Polish] Add integration test in `src/backendng/src/test/kotlin/com/secman/integration/AccountVulnsIntegrationTest.kt`: full flow test with real DB (H2 in-memory), create user + mappings + assets + vulns, call endpoint, verify full response structure
- [ ] T053 [P] [Polish] Verify authentication tests in `AccountVulnsContractTest.kt`: `GET /api/account-vulns returns 401 for unauthenticated request` (no JWT token)
- [ ] T054 [P] [Polish] Add unit tests for DTO serialization/deserialization (verify Jackson/Micronaut JSON handling) in `src/backendng/src/test/kotlin/com/secman/dto/AccountVulnsDtoTest.kt`
- [ ] T055 [Polish] Update `CLAUDE.md`: Add feature 018 summary to "Recent Changes" section with new endpoints, components, and key design decisions
- [ ] T056 [Polish] Run full test suite: `./gradlew test && npm run test:e2e` - verify all tests pass, coverage ≥80%
- [ ] T057 [Polish] Run quickstart.md manual testing scenarios: single account, multiple accounts, no mapping, admin redirect, pagination
- [ ] T058 [Polish] Performance validation: Test with 500 assets across 50 AWS accounts, verify page load <3s, responsive UI

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - verification only
- **Foundational (Phase 2)**: Depends on Setup - BLOCKS all user stories
- **User Stories (Phase 3-7)**: All depend on Foundational completion
  - US1 (P1): Can start after Foundational - no dependencies on other stories
  - US3 (P1): Can start after Foundational - no dependencies on other stories
  - US4 (P1): Can start after Foundational - no dependencies on other stories
  - US2 (P2): Can start after Foundational - no dependencies on US1 but enhances it
  - US5 (P3): Can start after US1 completes (depends on AssetVulnTable existing)
- **Pagination (Phase 8)**: Depends on US2 completion (enhances AccountVulnGroup)
- **Polish (Phase 9)**: Depends on all desired user stories being complete

### User Story Independence

All P1 stories (US1, US3, US4) are INDEPENDENT and can be developed in parallel by different team members after Foundational phase completes.

US2 (P2) is independent but enhances US1 (single account view becomes part of multi-account logic).

US5 (P3) depends on US1 (asset table must exist) but doesn't break US1 - just adds navigation.

### Within Each User Story

1. Tests MUST be written FIRST and FAIL before implementation (TDD NON-NEGOTIABLE)
2. Backend: DTOs → Service → Controller
3. Frontend: API Service → Components (leaf components first) → Page
4. Tests run → Expected: ALL PASS
5. Checkpoint validation before moving to next story

### Parallel Opportunities (After Foundational Phase Completes)

**Parallel Backend Work**:
- US1 contract tests + US3 contract tests + US4 contract tests (T011-T015, T021-T023, T027-T030) - all different test files
- US1 service implementation + US1 controller creation (T016, T017) - different files

**Parallel Frontend Work**:
- US1 components (T018 AssetVulnTable, T019 AccountVulnsView, T020 page) - all different files
- US2 AccountVulnGroup component (T039) can start while US1 is in progress (different file)

**Parallel User Stories** (if team has 3+ developers):
- Developer A: US1 (T011-T020) - MVP core
- Developer B: US3 (T021-T026) - Error handling
- Developer C: US4 (T027-T033) - Admin redirect

After P1 stories complete, US2 and US5 can proceed in parallel or sequentially based on priority.

---

## Parallel Example: Foundational Phase (Phase 2)

```bash
# Launch all DTO creation tasks together (different files):
Task: T005 "Create AssetVulnCountDto.kt"
Task: T006 "Create AccountGroupDto.kt"
Task: T009 "Create TypeScript interfaces in accountVulnsService.ts"
Task: T010 "Create accountVulnsService.ts API client wrapper"

# Then T007 (depends on T005, T006):
Task: T007 "Create AccountVulnsSummaryDto.kt"

# Then T008:
Task: T008 "Create AccountVulnsService.kt interface"
```

---

## Parallel Example: User Story 1 Tests (Phase 3)

```bash
# Launch all US1 contract tests together (independent test cases in same file executed separately):
Task: T011 "Contract test: GET returns 200 for single AWS account"
Task: T012 "Contract test: Assets sorted by vulnerability count"
Task: T013 "Contract test: Includes assets with 0 vulnerabilities"

# In parallel with backend tests, launch frontend E2E test:
Task: T015 "E2E test: displays single account with assets"

# In parallel, launch unit tests:
Task: T014 "Unit test: AccountVulnsServiceTest with mocks"
```

---

## Parallel Example: User Story 1 Implementation (Phase 3)

```bash
# After all US1 tests written and FAILING, launch implementation:

# Backend (sequential dependencies):
Task: T016 "Implement AccountVulnsService.kt" (service layer)
Task: T017 "Create AccountVulnsController.kt" (depends on T016 - service must exist)

# Frontend (parallel):
Task: T018 "Create AssetVulnTable.tsx" (leaf component)
Task: T019 "Create AccountVulnsView.tsx" (parent component, can start while T018 in progress)
Task: T020 "Create account-vulns.astro" (page, can start while T019 in progress)
```

---

## Parallel Example: P1 User Stories (Phase 3-5)

```bash
# After Foundational phase completes, with 3 developers:

# Developer A - US1 MVP:
Tasks: T011-T020 (tests + implementation for single account view)

# Developer B - US3 Error Handling:
Tasks: T021-T026 (tests + implementation for no mapping error)

# Developer C - US4 Admin Redirect:
Tasks: T027-T033 (tests + implementation for admin restriction)

# Stories complete independently, integrate when all done
```

---

## Implementation Strategy

### MVP First (P1 User Stories Only)

1. Complete Phase 1: Setup (T001-T004) - verification only, ~5 minutes
2. Complete Phase 2: Foundational (T005-T010) - DTOs + interfaces, ~30 minutes
3. **CHECKPOINT**: Foundation ready
4. Complete Phase 3: US1 (T011-T020) - Core single account view, ~2-3 hours
5. Complete Phase 4: US3 (T021-T026) - Error handling, ~30 minutes
6. Complete Phase 5: US4 (T027-T033) - Admin redirect, ~45 minutes
7. **STOP and VALIDATE**: Test all P1 stories independently
8. **MVP COMPLETE**: All P1 user stories working, ready for demo/deploy

**MVP Delivers**: Non-admin users can view vulnerabilities for their AWS account(s), admins redirected, clear error for users without mappings. Feature is production-ready at this point.

### Incremental Delivery (Add P2, P3 Features)

After MVP (P1) is validated:
9. Complete Phase 6: US2 (T034-T040) - Multi-account grouping UI, ~1-2 hours
10. **TEST**: Verify US1 still works (single account), US2 works (multiple accounts)
11. Complete Phase 8: Pagination (T046-T049) - Per-account Load More, ~45 minutes
12. **TEST**: Verify pagination works for accounts with >20 assets
13. Complete Phase 7: US5 (T041-T045) - Asset navigation context, ~30 minutes
14. **TEST**: Verify full navigation flow
15. Complete Phase 9: Polish (T050-T058) - Final QA and performance validation

**Total Estimated Time**:
- MVP (P1): 4-5 hours
- Full Feature (P1+P2+P3): 7-9 hours

### Parallel Team Strategy (3 Developers)

**Day 1 Morning** (Together):
- Phase 1 + 2: Setup + Foundational (T001-T010) - ~45 minutes

**Day 1 Afternoon** (Parallel):
- Dev A: US1 (T011-T020) - MVP core
- Dev B: US3 (T021-T026) - Error handling
- Dev C: US4 (T027-T033) - Admin redirect

**Day 1 End of Day**:
- Integrate + Test P1 stories together
- Demo MVP to stakeholders

**Day 2** (Sequential or Parallel):
- Dev A: US2 (T034-T040) - Multi-account grouping
- Dev B: Pagination (T046-T049) - Load More button
- Dev C: US5 (T041-T045) - Navigation context

**Day 2 Afternoon**:
- All devs: Polish (T050-T058) - Final QA

---

## Notes

- **[P] tasks** = Different files, no dependencies, can run in parallel
- **[Story] label** = Maps task to specific user story (US1, US2, US3, US4, US5) for traceability
- **TDD NON-NEGOTIABLE**: Write tests FIRST (Red), implement (Green), refactor (Refactor cycle)
- Each user story is independently completable and testable
- Stop at any checkpoint to validate story works on its own
- Verify tests FAIL before implementing (proves tests are valid)
- Commit after each task or logical group (e.g., all US1 tests, US1 implementation)
- Run full test suite frequently: `./gradlew test && npm run test:e2e`

---

## Test Coverage Target

**Goal**: ≥80% code coverage per constitution

**Backend**:
- Contract tests: 10 scenarios (T011-T013, T021-T022, T027-T028, T034-T035, T053)
- Unit tests: 3 scenarios (T014, T036, T054)
- Integration tests: 1 full flow (T052)
- **Total**: 14 backend tests

**Frontend**:
- E2E tests: 11 scenarios (T015, T023, T029-T030, T037, T041-T043, T046-T048)
- **Total**: 11 frontend tests

**Grand Total**: 25 tests covering all 5 user stories + edge cases + polish
