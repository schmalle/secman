# Tasks: User Mapping Management in User Edit Interface

**Feature**: 017-user-mapping-management  
**Branch**: `017-user-mapping-management`  
**Input**: Design documents from `/specs/017-user-mapping-management/`  
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/user-mappings-api.yml ✅

**Tests**: This feature follows TDD (Test-Driven Development) as specified in the Secman Constitution. All test tasks are REQUIRED and must be completed BEFORE implementation tasks.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **E2E Tests**: `tests/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: No new infrastructure needed - feature extends existing components

**Status**: ✅ Complete - All existing infrastructure is available:
- UserMapping entity exists (Feature 013)
- UserMappingRepository exists with all needed methods
- UserController exists and can be extended
- User edit dialog exists in frontend
- Authentication and RBAC infrastructure exists

**Checkpoint**: Foundation ready - user story implementation can begin

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared components needed by multiple user stories

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Backend Foundation

- [X] T001 [P] Create DTOs in `src/backendng/src/main/kotlin/com/secman/dto/UserMappingDto.kt`
  - Create `UserMappingResponse` with fields: id, email, awsAccountId, domain, createdAt, updatedAt
  - Create `CreateUserMappingRequest` with fields: awsAccountId, domain (both nullable)
  - Create `UpdateUserMappingRequest` with fields: awsAccountId, domain (both nullable)
  - Add extension function `fun UserMapping.toResponse(): UserMappingResponse`
  - All DTOs must be annotated with `@Serdeable` for Micronaut serialization

- [X] T002 Write unit tests for UserMappingService in `src/backendng/src/test/kotlin/com/secman/service/UserMappingServiceTest.kt`
  - Test getUserMappings returns mappings for valid user
  - Test getUserMappings throws exception for non-existent user
  - Test createMapping validates at least one field provided
  - Test createMapping detects duplicate mappings
  - Test createMapping succeeds with valid data
  - Test updateMapping validates at least one field provided
  - Test updateMapping detects duplicate mappings (excluding current)
  - Test updateMapping verifies mapping belongs to user
  - Test updateMapping succeeds with valid data
  - Test deleteMapping verifies mapping belongs to user
  - Test deleteMapping succeeds
  - Use MockK for mocking UserRepository and UserMappingRepository
  - **Expected**: All tests FAIL (service not yet implemented)

- [X] T003 Implement UserMappingService in `src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt` (depends on T001, T002)
  - Annotate class with `@Singleton`
  - Inject UserRepository and UserMappingRepository
  - Implement `getUserMappings(userId: Long): List<UserMappingResponse>`
  - Implement `createMapping(userId: Long, request: CreateUserMappingRequest): UserMappingResponse` with `@Transactional`
  - Implement `updateMapping(userId: Long, mappingId: Long, request: UpdateUserMappingRequest): UserMappingResponse` with `@Transactional`
  - Implement `deleteMapping(userId: Long, mappingId: Long): Boolean` with `@Transactional`
  - All methods must validate business rules per data-model.md
  - All methods must throw appropriate exceptions (NoSuchElementException, IllegalArgumentException, IllegalStateException)
  - **Validation**: Run `./gradlew test --tests UserMappingServiceTest` - all tests PASS ✅

### Frontend Foundation

- [X] T004 [P] Create API client utilities in `src/frontend/src/api/userMappings.ts`
  - Define TypeScript interfaces: UserMapping, CreateMappingRequest, UpdateMappingRequest
  - Implement `getUserMappings(userId: number): Promise<UserMapping[]>`
  - Implement `createMapping(userId: number, data: CreateMappingRequest): Promise<UserMapping>`
  - Implement `updateMapping(userId: number, mappingId: number, data: UpdateMappingRequest): Promise<UserMapping>`
  - Implement `deleteMapping(userId: number, mappingId: number): Promise<void>`
  - All functions must use authenticatedFetch or csrfPost/csrfDelete helpers
  - All functions must handle errors and throw with clear messages

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - View User's Existing Mappings (Priority: P1) 🎯 MVP

**Goal**: Enable administrators to see all domain and AWS account mappings for a user in the edit dialog

**Independent Test**: Open user edit dialog for any user and verify all mappings from user_mapping table are displayed correctly

### Backend Tests for User Story 1 (TDD)

- [X] T005 Write controller integration test for GET endpoint in `src/backendng/src/test/kotlin/com/secman/controller/UserControllerMappingTest.kt`
  - Test GET /api/users/{userId}/mappings returns 200 with mappings array
  - Test GET returns empty array for user with no mappings
  - Test GET returns 404 for non-existent user
  - Test GET returns 401 for unauthenticated request
  - Test GET returns 403 for non-admin user
  - Use `@MicronautTest` and mock UserMappingService
  - **Expected**: Tests FAIL (endpoint not yet implemented)

### Backend Implementation for User Story 1

- [X] T006 Extend UserController with GET mappings endpoint in `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt` (depends on T003, T005)
  - Add UserMappingService to constructor injection
  - Add `@Get("/{userId}/mappings")` endpoint
  - Annotate with `@Secured("ADMIN")`
  - Return `List<UserMappingResponse>`
  - **Validation**: Run `./gradlew test --tests UserControllerMappingTest` - GET tests PASS ✅

### Frontend Tests for User Story 1 (TDD)

- [X] T007 [P] Write E2E test for viewing mappings in `tests/user-mapping-management.spec.ts`
  - Test: displays "Access Mappings" section in user edit dialog
  - Test: shows table with columns "AWS Account ID", "Domain", "Created"
  - Test: displays existing mappings correctly (AWS-only, domain-only, both)
  - Test: displays empty state message when user has no mappings
  - Use Playwright with admin login
  - **Expected**: Tests FAIL (UI not yet implemented)

### Frontend Implementation for User Story 1

- [X] T008 Add mapping display section to UserEditPage in `src/frontend/src/components/UserEditPage.tsx` (depends on T004, T007)
  - Add state: `mappings: UserMapping[]`, `error: string | null`
  - Add `useEffect` to call `getUserMappings(userId)` on component mount
  - Add `loadMappings()` async function
  - Add JSX section titled "Access Mappings" at bottom of edit form
  - Display error alert if error exists
  - Render table with columns: AWS Account ID, Domain, Created, Actions (empty for US1)
  - Map over mappings array and render table rows
  - Display "No mappings configured for this user" when mappings array is empty
  - Show "-" for null awsAccountId or domain values
  - Format createdAt using `new Date(mapping.createdAt).toLocaleDateString()`
  - **Validation**: Run `npx playwright test tests/user-mapping-management.spec.ts` - viewing tests PASS ✅

**Checkpoint**: User Story 1 complete - administrators can now VIEW all user mappings

---

## Phase 4: User Story 2 - Add New Mapping (Priority: P2)

**Goal**: Enable administrators to create new domain or AWS account mappings for a user

**Independent Test**: Click "Add Mapping" in user edit dialog, enter valid data, verify new mapping appears and is persisted

### Backend Tests for User Story 2 (TDD)

- [ ] T009 Write controller integration test for POST endpoint in `src/backendng/src/test/kotlin/com/secman/controller/UserControllerMappingTest.kt`
  - Test POST /api/users/{userId}/mappings returns 201 with created mapping (AWS-only)
  - Test POST returns 201 for domain-only mapping
  - Test POST returns 201 for both AWS and domain
  - Test POST returns 400 when both fields are null/empty
  - Test POST returns 400 for invalid AWS Account ID format
  - Test POST returns 400 for invalid domain format
  - Test POST returns 409 for duplicate mapping
  - Test POST returns 404 for non-existent user
  - Test POST returns 401 for unauthenticated request
  - Test POST returns 403 for non-admin user
  - **Expected**: Tests FAIL (endpoint not yet implemented)

### Backend Implementation for User Story 2

- [ ] T010 Extend UserController with POST mappings endpoint in `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt` (depends on T003, T009)
  - Add `@Post("/{userId}/mappings")` endpoint
  - Annotate with `@Status(HttpStatus.CREATED)`
  - Annotate with `@Secured("ADMIN")`
  - Accept `@Body request: CreateUserMappingRequest`
  - Call `userMappingService.createMapping(userId, request)`
  - Return `UserMappingResponse`
  - **Validation**: Run `./gradlew test --tests UserControllerMappingTest` - POST tests PASS ✅

### Frontend Tests for User Story 2 (TDD)

- [ ] T011 [P] Write E2E test for adding mappings in `tests/user-mapping-management.spec.ts`
  - Test: "Add Mapping" button is visible
  - Test: clicking "Add Mapping" shows form with AWS Account ID and Domain fields
  - Test: successfully creates AWS-only mapping
  - Test: successfully creates domain-only mapping
  - Test: successfully creates mapping with both fields
  - Test: shows error when both fields are empty
  - Test: shows error for invalid AWS Account ID format (not 12 digits)
  - Test: shows error for duplicate mapping
  - Test: new mapping appears in table after creation
  - Test: form resets and closes after successful creation
  - **Expected**: Tests FAIL (UI not yet implemented)

### Frontend Implementation for User Story 2

- [ ] T012 Add mapping creation UI to UserEditPage in `src/frontend/src/components/UserEditPage.tsx` (depends on T004, T008, T011)
  - Add state: `isAddingMapping: boolean`, `newMapping: { awsAccountId: string, domain: string }`
  - Add "Add Mapping" button below mappings table (show when `!isAddingMapping`)
  - Add form card with AWS Account ID and Domain input fields (show when `isAddingMapping`)
  - Add input validation: AWS Account ID pattern="\d{12}", Domain text input
  - Add "Save" and "Cancel" buttons in form
  - Implement `handleAddMapping()` async function:
    - Validate at least one field provided
    - Call `createMapping(userId, newMapping)`
    - On success: reset form, set `isAddingMapping = false`, reload mappings
    - On error: set error message
  - Implement cancel handler: reset form, set `isAddingMapping = false`, clear error
  - **Validation**: Run `npx playwright test tests/user-mapping-management.spec.ts` - add tests PASS ✅

**Checkpoint**: User Story 2 complete - administrators can now ADD new mappings

---

## Phase 5: User Story 3 - Delete Existing Mapping (Priority: P2)

**Goal**: Enable administrators to remove mappings to revoke user access

**Independent Test**: Click "Delete" on a mapping, confirm, verify mapping is removed from display and database

### Backend Tests for User Story 3 (TDD)

- [ ] T013 Write controller integration test for DELETE endpoint in `src/backendng/src/test/kotlin/com/secman/controller/UserControllerMappingTest.kt`
  - Test DELETE /api/users/{userId}/mappings/{mappingId} returns 204 on success
  - Test DELETE returns 404 for non-existent mapping
  - Test DELETE returns 404 for mapping that doesn't belong to user
  - Test DELETE returns 404 for non-existent user
  - Test DELETE returns 401 for unauthenticated request
  - Test DELETE returns 403 for non-admin user
  - **Expected**: Tests FAIL (endpoint not yet implemented)

### Backend Implementation for User Story 3

- [ ] T014 Extend UserController with DELETE mappings endpoint in `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt` (depends on T003, T013)
  - Add `@Delete("/{userId}/mappings/{mappingId}")` endpoint
  - Annotate with `@Status(HttpStatus.NO_CONTENT)`
  - Annotate with `@Secured("ADMIN")`
  - Accept `@PathVariable userId: Long` and `@PathVariable mappingId: Long`
  - Call `userMappingService.deleteMapping(userId, mappingId)`
  - No return value (204 No Content)
  - **Validation**: Run `./gradlew test --tests UserControllerMappingTest` - DELETE tests PASS ✅

### Frontend Tests for User Story 3 (TDD)

- [ ] T015 [P] Write E2E test for deleting mappings in `tests/user-mapping-management.spec.ts`
  - Test: "Delete" button is visible for each mapping
  - Test: clicking "Delete" shows confirmation dialog
  - Test: clicking "Cancel" in confirmation keeps mapping
  - Test: clicking "OK" in confirmation removes mapping from table
  - Test: deleted mapping no longer appears after page reload
  - Test: empty state message shows when last mapping is deleted
  - **Expected**: Tests FAIL (UI not yet implemented)

### Frontend Implementation for User Story 3

- [ ] T016 Add mapping deletion UI to UserEditPage in `src/frontend/src/components/UserEditPage.tsx` (depends on T004, T008, T015)
  - Add "Delete" button in Actions column for each mapping row
  - Add button class: `btn btn-sm btn-danger`
  - Implement `handleDeleteMapping(mappingId: number)` async function:
    - Show confirmation dialog: `confirm('Are you sure you want to delete this mapping?')`
    - If confirmed: call `deleteMapping(userId, mappingId)`
    - On success: reload mappings via `loadMappings()`
    - On error: set error message
  - **Validation**: Run `npx playwright test tests/user-mapping-management.spec.ts` - delete tests PASS ✅

**Checkpoint**: User Story 3 complete - administrators can now DELETE mappings

---

## Phase 6: User Story 4 - Edit Existing Mapping (Priority: P3)

**Goal**: Enable administrators to modify mappings without delete+recreate

**Independent Test**: Click "Edit" on a mapping, change fields, verify mapping is updated in display and database

### Backend Tests for User Story 4 (TDD)

- [X] T017 Write controller integration test for PUT endpoint in `src/backendng/src/test/kotlin/com/secman/controller/UserControllerMappingTest.kt`
  - Test PUT /api/users/{userId}/mappings/{mappingId} returns 200 with updated mapping
  - Test PUT updates AWS Account ID only
  - Test PUT updates domain only
  - Test PUT updates both fields
  - Test PUT returns 400 when both fields are null/empty
  - Test PUT returns 400 for invalid AWS Account ID format
  - Test PUT returns 400 for invalid domain format
  - Test PUT returns 409 for duplicate mapping (different from current)
  - Test PUT returns 404 for non-existent mapping
  - Test PUT returns 404 for mapping that doesn't belong to user
  - Test PUT returns 404 for non-existent user
  - Test PUT returns 401 for unauthenticated request
  - Test PUT returns 403 for non-admin user
  - **Expected**: Tests FAIL (endpoint not yet implemented)

### Backend Implementation for User Story 4

- [X] T018 Extend UserController with PUT mappings endpoint in `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt` (depends on T003, T017)
  - Add `@Put("/{userId}/mappings/{mappingId}")` endpoint
  - Annotate with `@Secured("ADMIN")`
  - Accept `@PathVariable userId: Long`, `@PathVariable mappingId: Long`, `@Body request: UpdateUserMappingRequest`
  - Call `userMappingService.updateMapping(userId, mappingId, request)`
  - Return `UserMappingResponse`
  - **Validation**: Run `./gradlew test --tests UserControllerMappingTest` - PUT tests PASS ✅

### Frontend Tests for User Story 4 (TDD)

- [X] T019 [P] Write E2E test for editing mappings in `tests/user-mapping-management.spec.ts`
  - Test: "Edit" button is visible for each mapping
  - Test: clicking "Edit" shows inline form with populated values
  - Test: successfully updates AWS Account ID
  - Test: successfully updates domain
  - Test: successfully updates both fields
  - Test: shows error when both fields are cleared
  - Test: shows error for invalid AWS Account ID format
  - Test: shows error for duplicate mapping
  - Test: clicking "Cancel" discards changes
  - Test: updated values appear in table after save
  - **Expected**: Tests FAIL (UI not yet implemented)

### Frontend Implementation for User Story 4

- [X] T020 Add mapping edit UI to UserEditPage in `src/frontend/src/components/UserEditPage.tsx` (depends on T004, T008, T019)
  - Add state: `editingMappingId: number | null`, `editMapping: { awsAccountId: string, domain: string }`
  - Add "Edit" button in Actions column for each mapping row
  - Replace table row with inline edit form when `editingMappingId === mapping.id`:
    - Show input fields for AWS Account ID and Domain pre-filled with current values
    - Add "Save" and "Cancel" buttons
  - Implement `handleEditClick(mapping: UserMapping)` function:
    - Set `editingMappingId = mapping.id`
    - Set `editMapping = { awsAccountId: mapping.awsAccountId || '', domain: mapping.domain || '' }`
  - Implement `handleEditSave(mappingId: number)` async function:
    - Validate at least one field provided
    - Call `updateMapping(userId, mappingId, editMapping)`
    - On success: set `editingMappingId = null`, reload mappings
    - On error: set error message
  - Implement `handleEditCancel()` function:
    - Set `editingMappingId = null`
    - Clear error
  - **Validation**: Run `npx playwright test tests/user-mapping-management.spec.ts` - edit tests PASS ✅

**Checkpoint**: User Story 4 complete - administrators can now EDIT mappings inline

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that enhance all user stories

- [X] T021 [P] Add loading state to UserEditPage in `src/frontend/src/components/UserEditPage.tsx`
  - Add state: `isLoading: boolean`
  - Show loading spinner while mappings are being fetched
  - Show loading spinner during create/update/delete operations
  - Disable buttons during operations to prevent duplicate submissions

- [X] T022 [P] Enhance error handling in UserEditPage in `src/frontend/src/components/UserEditPage.tsx`
  - Display specific error messages from backend (e.g., "This mapping already exists")
  - Add dismissible error alerts with "X" close button
  - Add success toast/message after successful operations
  - Clear errors automatically after 5 seconds

- [X] T023 [P] Add ARIA labels and keyboard navigation in `src/frontend/src/components/UserEditPage.tsx`
  - Add aria-label to all buttons
  - Add aria-describedby to input fields
  - Ensure Tab navigation works correctly through form
  - Add Enter key handler for form submission
  - Add Escape key handler to cancel operations

- [ ] T024 Run full backend test suite and verify coverage
  - Execute: `./gradlew test`
  - Verify all tests pass
  - Check coverage report: `./gradlew jacocoTestReport`
  - Verify ≥80% coverage for new code (UserMappingService, UserController mappings endpoints)

- [ ] T025 Run full E2E test suite
  - Execute: `npx playwright test tests/user-mapping-management.spec.ts`
  - Verify all tests pass
  - Test with different browsers (Chromium, Firefox, WebKit)
  - Verify tests work with user having 0, 1, and 10+ mappings

- [ ] T026 [P] Manual testing checklist (follow quickstart.md validation)
  - Test with admin user and non-admin user (RBAC)
  - Test with very long domain names
  - Test with special characters in domain (should reject)
  - Test duplicate detection thoroughly
  - Test concurrent editing (two tabs, same user)
  - Test with slow network (Chrome DevTools throttling)
  - Verify pagination or scrolling with 50+ mappings

- [ ] T027 [P] Update documentation
  - Add mapping management section to `docs/DEVELOPMENT.md`
  - Update API documentation with new endpoints
  - Add screenshots of mapping UI to `docs/` or `pictures/`

- [X] T028 Security review
  - Verify all endpoints have `@Secured("ADMIN")` annotation
  - Verify frontend checks admin role before rendering mapping UI
  - Verify no mapping data leaks in error messages
  - Verify CSRF tokens are included in all mutation requests
  - Verify input validation prevents SQL injection (already covered by JPA)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: ✅ Complete - No new infrastructure needed
- **Foundational (Phase 2)**: Can start immediately - BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational (Phase 2) completion
  - US1 (View) can start after Foundational
  - US2 (Add) can start after Foundational (independent of US1 but builds on same UI)
  - US3 (Delete) can start after US1 (needs view UI to add delete buttons)
  - US4 (Edit) can start after US1 (needs view UI to add edit functionality)
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1 - View)**: Depends on Foundational - No dependencies on other stories
- **User Story 2 (P2 - Add)**: Depends on Foundational + US1 UI (extends same component)
- **User Story 3 (P2 - Delete)**: Depends on Foundational + US1 UI (adds delete button to table)
- **User Story 4 (P3 - Edit)**: Depends on Foundational + US1 UI (adds edit functionality to table)

### Within Each User Story (TDD Order)

1. **Tests FIRST** (write and verify they FAIL)
2. **Implementation** (make tests PASS)
3. **Validation** (run tests and verify PASS ✅)

### Task-Level Dependencies

- **T001**: No dependencies - create DTOs
- **T002**: Depends on T001 - test service using DTOs
- **T003**: Depends on T001, T002 - implement service to pass tests
- **T004**: No dependencies - create API client (parallel with backend)
- **T005**: Depends on T003 - test controller using service
- **T006**: Depends on T003, T005 - implement controller to pass tests
- **T007**: Depends on T004 - test UI using API client (parallel with T005)
- **T008**: Depends on T004, T007 - implement UI to pass tests
- **T009**: Depends on T003 - test POST endpoint
- **T010**: Depends on T003, T009 - implement POST endpoint
- **T011**: Depends on T004 - test add UI (parallel with T009)
- **T012**: Depends on T004, T008, T011 - implement add UI (extends US1 component)
- **T013**: Depends on T003 - test DELETE endpoint
- **T014**: Depends on T003, T013 - implement DELETE endpoint
- **T015**: Depends on T004 - test delete UI (parallel with T013)
- **T016**: Depends on T004, T008, T015 - implement delete UI (extends US1 component)
- **T017**: Depends on T003 - test PUT endpoint
- **T018**: Depends on T003, T017 - implement PUT endpoint
- **T019**: Depends on T004 - test edit UI (parallel with T017)
- **T020**: Depends on T004, T008, T019 - implement edit UI (extends US1 component)
- **Polish tasks (T021-T028)**: Depend on all US tasks (T001-T020)

### Parallel Opportunities

**Within Foundational Phase (after T001 complete)**:
- T002 (backend tests) and T004 (frontend API client) can run in parallel

**Between Backend and Frontend for Each Story**:
- Backend tests + frontend tests can run in parallel (different files)
- Example US1: T005 (controller test) || T007 (E2E test)
- Example US2: T009 (POST test) || T011 (add UI test)
- Example US3: T013 (DELETE test) || T015 (delete UI test)
- Example US4: T017 (PUT test) || T019 (edit UI test)

**Within Polish Phase**:
- T021, T022, T023, T027 can all run in parallel (different concerns)

---

## Parallel Example: Foundational Phase

After T001 (DTOs) is complete, launch in parallel:

```bash
# Backend track
Task: "T002 - Write unit tests for UserMappingService"
Task: "T003 - Implement UserMappingService" (after T002)

# Frontend track (parallel with backend)
Task: "T004 - Create API client utilities in src/frontend/src/api/userMappings.ts"
```

---

## Parallel Example: User Story 1 (View)

After Foundational phase complete:

```bash
# Backend track
Task: "T005 - Write controller test for GET endpoint"
Task: "T006 - Implement GET endpoint" (after T005)

# Frontend track (parallel with backend)
Task: "T007 - Write E2E test for viewing mappings"
Task: "T008 - Implement mapping display UI" (after T007)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only - View Mappings)

1. Complete **Phase 2: Foundational** (T001-T004) → Foundation ready
2. Complete **Phase 3: User Story 1** (T005-T008) → VIEW functionality complete
3. **STOP and VALIDATE**: Test viewing mappings independently
4. Deploy/demo if ready → Admins can now SEE user mappings

### Incremental Delivery (Recommended)

1. **Foundation**: T001-T004 → Service + API client ready
2. **US1 (View)**: T005-T008 → Admins can view mappings ✅ Deploy
3. **US2 (Add)**: T009-T012 → Admins can add mappings ✅ Deploy
4. **US3 (Delete)**: T013-T016 → Admins can delete mappings ✅ Deploy
5. **US4 (Edit)**: T017-T020 → Admins can edit mappings ✅ Deploy
6. **Polish**: T021-T028 → Enhanced UX and validation ✅ Final release

Each deployment adds independent value without breaking previous functionality.

### Parallel Team Strategy

With 2 developers:

1. **Both complete Foundation together** (T001-T004)
2. **Split by backend/frontend**:
   - Developer A: Backend tests + implementations (T005, T006, T009, T010, T013, T014, T017, T018)
   - Developer B: Frontend tests + implementations (T007, T008, T011, T012, T015, T016, T019, T020)
3. **Both work on Polish** (T021-T028)

With 4 developers:

1. **All complete Foundation together** (T001-T004)
2. **Split by user story**:
   - Dev A: US1 (T005-T008)
   - Dev B: US2 (T009-T012)
   - Dev C: US3 (T013-T016)
   - Dev D: US4 (T017-T020)
3. **All work on Polish** (T021-T028)

---

## Notes

- **[P] tasks**: Different files, no dependencies, can be parallelized
- **[Story] label**: Maps task to specific user story for traceability
- **TDD is MANDATORY**: All test tasks must be completed BEFORE implementation tasks
- **Verify tests FAIL**: Before implementing, ensure tests fail as expected
- **Verify tests PASS**: After implementing, ensure all tests pass
- **Each user story is independently testable**: Can stop after any story phase and have working feature
- **Commit strategy**: Commit after each task or logical group (e.g., after T003 when service tests pass)
- **Checkpoint validation**: Stop at each checkpoint to validate story independently before moving to next
- **Coverage target**: Maintain ≥80% code coverage for all new code
- **Backend validation**: `./gradlew test` and `./gradlew jacocoTestReport`
- **Frontend validation**: `npx playwright test tests/user-mapping-management.spec.ts`
- **Manual testing**: Follow quickstart.md validation section for comprehensive testing

---

## Total Task Summary

- **Phase 1 (Setup)**: 0 tasks (existing infrastructure)
- **Phase 2 (Foundational)**: 4 tasks (T001-T004)
- **Phase 3 (US1 - View)**: 4 tasks (T005-T008)
- **Phase 4 (US2 - Add)**: 4 tasks (T009-T012)
- **Phase 5 (US3 - Delete)**: 4 tasks (T013-T016)
- **Phase 6 (US4 - Edit)**: 4 tasks (T017-T020)
- **Phase 7 (Polish)**: 8 tasks (T021-T028)

**Total**: 28 tasks

### Tasks Per User Story

- **US1 (P1 - View)**: 4 implementation tasks (T005-T008)
- **US2 (P2 - Add)**: 4 implementation tasks (T009-T012)
- **US3 (P2 - Delete)**: 4 implementation tasks (T013-T016)
- **US4 (P3 - Edit)**: 4 implementation tasks (T017-T020)

### Parallel Opportunities

- **Foundational**: 2 parallel tracks (backend T002-T003 || frontend T004)
- **Each User Story**: 2 parallel tracks (backend tests/impl || frontend tests/impl)
- **Polish**: 4 parallel tasks (T021, T022, T023, T027)

### Suggested MVP Scope

**MVP = Foundational + User Story 1 (View Mappings)**
- Tasks: T001-T008
- Delivers: Administrators can view all mappings for a user
- Time estimate: 2-3 hours (per quickstart.md)
- Value: Immediate visibility into user access without requiring full CRUD

**MVP + Add/Delete = Foundational + US1 + US2 + US3**
- Tasks: T001-T016
- Delivers: Full read + add + delete capabilities
- Time estimate: 4-5 hours
- Value: Complete management workflow (edit is convenience, not critical)
