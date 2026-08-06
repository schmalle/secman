# Tasks: Maintenance Popup Banner

**Input**: Design documents from `/specs/047-maintenance-popup/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/maintenance-banner-api.yaml

**Tests**: Per Constitution Principle II (Test-Driven Development - NON-NEGOTIABLE), tests MUST be written BEFORE implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend Tests**: `src/frontend/tests/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and dependency setup

- [X] T001 Add OWASP Java HTML Sanitizer dependency to src/backendng/build.gradle.kts
- [X] T002 [P] Verify Bootstrap Icons are installed in src/frontend/package.json
- [X] T003 [P] Create BannerStatus enum in src/backendng/src/main/kotlin/com/secman/domain/BannerStatus.kt

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 Create MaintenanceBanner JPA entity in src/backendng/src/main/kotlin/com/secman/domain/MaintenanceBanner.kt with indexes
- [X] T005 [P] Create MaintenanceBannerRequest DTO in src/backendng/src/main/kotlin/com/secman/dto/MaintenanceBannerRequest.kt
- [X] T006 [P] Create MaintenanceBannerResponse DTO with from() mapper in src/backendng/src/main/kotlin/com/secman/dto/MaintenanceBannerResponse.kt
- [X] T007 Create MaintenanceBannerRepository interface with time-range query in src/backendng/src/main/kotlin/com/secman/repository/MaintenanceBannerRepository.kt
- [X] T008 Create MaintenanceBannerService with OWASP sanitization in src/backendng/src/main/kotlin/com/secman/service/MaintenanceBannerService.kt
- [X] T009 [P] Create maintenanceBannerService.ts API client in src/frontend/src/services/maintenanceBannerService.ts
- [X] T010 [P] Create maintenance banner CSS styles in src/frontend/src/styles/maintenance-banner.css

**Checkpoint**: ✅ Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Admin Creates Maintenance Banner (Priority: P1) 🎯 MVP

**Goal**: Enable administrators to create and schedule maintenance notifications that appear on the start/login page during configured time windows

**Independent Test**: Log in as admin, navigate to admin section, create a banner with message and time range, save it, verify it appears on start page during scheduled time

### Tests for User Story 1 (TDD - Write FIRST, ensure they FAIL)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T011 [P] [US1] Write repository test for findActiveBanners query in src/backendng/src/test/kotlin/com/secman/repository/MaintenanceBannerRepositoryTest.kt
- [X] T012 [P] [US1] Write service test for createBanner with validation in src/backendng/src/test/kotlin/com/secman/service/MaintenanceBannerServiceTest.kt
- [X] T013 [P] [US1] Write controller test for POST /api/maintenance-banners in src/backendng/src/test/kotlin/com/secman/controller/MaintenanceBannerControllerTest.kt

### Implementation for User Story 1

- [X] T014 [US1] Implement MaintenanceBannerController POST endpoint with @Secured("ADMIN") in src/backendng/src/main/kotlin/com/secman/controller/MaintenanceBannerController.kt
- [X] T015 [US1] Implement time range validation (end > start) in MaintenanceBannerService
- [X] T016 [US1] Implement XSS sanitization using OWASP library in MaintenanceBannerService.createBanner()
- [X] T017 [P] [US1] Create MaintenanceBannerForm React component in src/frontend/src/components/admin/MaintenanceBannerForm.tsx
- [X] T018 [US1] Create admin page at src/frontend/src/pages/admin/maintenance-banners.astro with role check
- [X] T019 [US1] Add link to admin navigation for maintenance banner management
- [X] T020 [US1] Implement form submission with timezone conversion (local → UTC) in MaintenanceBannerForm
- [X] T021 [US1] Add form validation (message 1-2000 chars, end after start) in MaintenanceBannerForm
- [X] T022 [US1] Verify repository query findActiveBanners returns banners where NOW() BETWEEN startTime AND endTime

**Checkpoint**: At this point, admins can create banners and they're stored in database

---

## Phase 4: User Story 2 - User Sees Maintenance Banner (Priority: P1) 🎯 MVP

**Goal**: Display active maintenance banners on the start/login page to all users (authenticated and unauthenticated)

**Independent Test**: Create an active banner (as admin), log out, visit start/login page as unauthenticated user, verify banner displays with correct message

### Tests for User Story 2 (TDD - Write FIRST, ensure they FAIL)

- [X] T023 [P] [US2] Write controller test for GET /api/maintenance-banners/active (public endpoint) in src/backendng/src/test/kotlin/com/secman/controller/MaintenanceBannerControllerTest.kt
- [X] T024 [P] [US2] Write Playwright E2E test for banner display on login page in src/frontend/tests/maintenance-banner.spec.ts

### Implementation for User Story 2

- [X] T025 [US2] Implement MaintenanceBannerController GET /api/maintenance-banners/active endpoint (no auth) in src/backendng/src/main/kotlin/com/secman/controller/MaintenanceBannerController.kt
- [X] T026 [US2] Implement MaintenanceBannerService.getActiveBanners() using repository.findActiveBanners(Instant.now())
- [X] T027 [P] [US2] Create MaintenanceBanner React island component in src/frontend/src/components/MaintenanceBanner.tsx
- [X] T028 [US2] Implement useEffect hook to fetch active banners on mount in MaintenanceBanner component
- [X] T029 [US2] Implement 60-second polling interval for banner updates in MaintenanceBanner component
- [X] T030 [US2] Implement Bootstrap alert rendering (stacked vertically, newest first) in MaintenanceBanner component
- [X] T031 [US2] Add Bootstrap icons (bi-exclamation-triangle-fill) to banner alerts
- [X] T032 [US2] Add MaintenanceBanner island to src/frontend/src/pages/index.astro with client:load directive
- [X] T033 [US2] Apply responsive styles for mobile/tablet/desktop (320px-4K) using maintenance-banner.css
- [X] T034 [US2] Verify banners stack vertically when multiple are active (FR-011 requirement)

**Checkpoint**: At this point, User Story 1 AND 2 work together - admins create banners, users see them

---

## Phase 5: User Story 3 - Admin Edits/Deletes Maintenance Banner (Priority: P2)

**Goal**: Allow administrators to edit or delete scheduled maintenance banners for flexibility and error correction

**Independent Test**: Create a banner, edit its message or time range, verify changes appear. Delete a banner, verify it no longer appears.

### Tests for User Story 3 (TDD - Write FIRST, ensure they FAIL)

- [X] T035 [P] [US3] Write controller test for PUT /api/maintenance-banners/{id} in src/backendng/src/test/kotlin/com/secman/controller/MaintenanceBannerControllerTest.kt
- [X] T036 [P] [US3] Write controller test for DELETE /api/maintenance-banners/{id} in src/backendng/src/test/kotlin/com/secman/controller/MaintenanceBannerControllerTest.kt
- [X] T037 [P] [US3] Write service test for updateBanner with validation in src/backendng/src/test/kotlin/com/secman/service/MaintenanceBannerServiceTest.kt

### Implementation for User Story 3

- [X] T038 [US3] Implement MaintenanceBannerController PUT endpoint with @Secured("ADMIN") in src/backendng/src/main/kotlin/com/secman/controller/MaintenanceBannerController.kt
- [X] T039 [US3] Implement MaintenanceBannerController DELETE endpoint with @Secured("ADMIN") in src/backendng/src/main/kotlin/com/secman/controller/MaintenanceBannerController.kt
- [X] T040 [US3] Implement MaintenanceBannerService.updateBanner() with validation and sanitization
- [X] T041 [US3] Implement MaintenanceBannerService.deleteBanner() with 404 handling
- [X] T042 [US3] Add edit/delete actions to MaintenanceBannerList component (buttons per banner)
- [X] T043 [US3] Implement edit mode in MaintenanceBannerForm (pre-populate fields from selected banner)
- [X] T044 [US3] Implement delete confirmation dialog in MaintenanceBannerList component
- [X] T045 [US3] Add API calls for updateBanner and deleteBanner in src/frontend/src/services/maintenanceBannerService.ts

**Checkpoint**: All banner CRUD operations now work - create, read, update, delete

---

## Phase 6: User Story 4 - Admin Views All Maintenance Banners (Priority: P2)

**Goal**: Provide administrators with a comprehensive list view of all banners (past, current, future) with status indicators

**Independent Test**: Create multiple banners with different time ranges (past, current, future), verify all appear in list with correct status (active/upcoming/expired)

### Tests for User Story 4 (TDD - Write FIRST, ensure they FAIL)

- [X] T046 [P] [US4] Write controller test for GET /api/maintenance-banners (admin only) in src/backendng/src/test/kotlin/com/secman/controller/MaintenanceBannerControllerTest.kt
- [X] T047 [P] [US4] Write repository test for findAllOrderByCreatedAtDesc in src/backendng/src/test/kotlin/com/secman/repository/MaintenanceBannerRepositoryTest.kt

### Implementation for User Story 4

- [X] T048 [US4] Implement MaintenanceBannerController GET /api/maintenance-banners endpoint with @Secured("ADMIN") in src/backendng/src/main/kotlin/com/secman/controller/MaintenanceBannerController.kt
- [X] T049 [US4] Implement MaintenanceBannerService.getAllBanners() using repository.findAllOrderByCreatedAtDesc()
- [X] T050 [P] [US4] Create MaintenanceBannerList React component in src/frontend/src/components/admin/MaintenanceBannerList.tsx
- [X] T051 [US4] Implement useEffect hook to fetch all banners on mount in MaintenanceBannerList
- [X] T052 [US4] Render banners in table with columns: message, time range, status, created by, actions
- [X] T053 [US4] Implement status badge styling (green=active, blue=upcoming, gray=expired) in MaintenanceBannerList
- [X] T054 [US4] Display local timezone-converted times in MaintenanceBannerList (convert from ISO-8601 UTC)
- [X] T055 [US4] Sort banners by createdAt DESC (newest first) in MaintenanceBannerList

**Checkpoint**: Complete admin management interface with create, list, edit, delete

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories and ensure production readiness

- [X] T056 [P] Add unit tests for BannerStatus enum logic in src/backendng/src/test/kotlin/com/secman/domain/BannerStatusTest.kt
- [X] T057 [P] Add unit tests for MaintenanceBanner.isActive() and getStatus() methods
- [X] T058 [P] Test responsive design on mobile (320px), tablet (768px), and desktop (1920px+) viewports
- [X] T059 Verify XSS prevention by attempting to inject script tags in banner message
- [X] T060 Verify RBAC enforcement by testing endpoints without ADMIN role (expect 403)
- [X] T061 Test timezone conversion accuracy (create banner in one timezone, verify display in another)
- [X] T062 Test multiple concurrent banners stacking correctly (create 3-5 active banners, verify order)
- [X] T063 [P] Add logging for banner CRUD operations in MaintenanceBannerService
- [X] T064 [P] Verify Hibernate creates indexes (idx_start_time, idx_end_time, idx_created_at) via DDL inspection
- [X] T065 Performance test: Query active banners with 100 total banners in DB (expect <10ms)
- [X] T066 [P] Update CLAUDE.md with MaintenanceBanner entity and API endpoints
- [X] T067 Run quickstart.md validation (verify all example commands work)
- [X] T068 Code review for security, performance, and constitutional compliance

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup (Phase 1) - BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational phase completion
  - **User Story 1 (P1)** and **User Story 2 (P1)** are MVP - both required for minimal functionality
  - User Story 1 + User Story 2 together = Working MVP (admin creates, users see)
  - User Story 3 (P2) and User Story 4 (P2) add management features
- **Polish (Phase 7)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational - No dependencies on other stories
- **User Story 2 (P1)**: Can start after Foundational - No dependencies on other stories (but needs US1 data to test)
- **User Story 3 (P2)**: Can start after Foundational - Enhances US1 (edit/delete) but independently testable
- **User Story 4 (P2)**: Can start after Foundational - Enhances US1 (list view) but independently testable

### Within Each User Story

- **TDD Workflow (NON-NEGOTIABLE)**:
  1. Tests MUST be written first
  2. Tests MUST fail initially
  3. Implement to make tests pass
  4. Refactor as needed
- **Backend**: Entity/DTOs → Repository → Service → Controller
- **Frontend**: API client → Components → Page integration
- **Story complete before moving to next priority**

### Parallel Opportunities

**Phase 1 (Setup)**: All tasks [P] can run in parallel:
- T002, T003 (frontend + enum setup)

**Phase 2 (Foundational)**: Marked [P] tasks can run in parallel:
- T005, T006, T009, T010 (DTOs, API client, styles - different files)

**Phase 3 (User Story 1)**: Tests can run in parallel:
- T011, T012, T013 (all test files)
- T017 can run in parallel with backend implementation (T014-T016)

**Phase 4 (User Story 2)**: Tests can run in parallel:
- T023, T024 (controller test + E2E test)
- T027 can run in parallel with backend implementation (T025-T026)

**Phase 5 (User Story 3)**: Tests can run in parallel:
- T035, T036, T037 (all test files for edit/delete)

**Phase 6 (User Story 4)**: Tests can run in parallel:
- T046, T047 (controller + repository tests)
- T050 can run in parallel with backend implementation (T048-T049)

**Phase 7 (Polish)**: Many tasks can run in parallel:
- T056, T057, T058, T063, T064, T066 (unit tests, responsive testing, logging, docs)

**Cross-Story Parallelism**:
- Once Foundational (Phase 2) is complete, **different team members** can work on different user stories in parallel
- Example: Developer A works on US1, Developer B works on US2 simultaneously

---

## Parallel Example: User Story 1 (Backend)

```bash
# Launch all tests for User Story 1 together (WRITE THESE FIRST):
Task: "[US1] Write repository test for findActiveBanners in MaintenanceBannerRepositoryTest.kt"
Task: "[US1] Write service test for createBanner in MaintenanceBannerServiceTest.kt"
Task: "[US1] Write controller test for POST endpoint in MaintenanceBannerControllerTest.kt"

# After tests fail, implement in sequence:
Task: "[US1] Implement POST endpoint in MaintenanceBannerController.kt"
Task: "[US1] Implement time range validation in MaintenanceBannerService"
Task: "[US1] Implement XSS sanitization in MaintenanceBannerService"
```

## Parallel Example: User Story 1 (Frontend)

```bash
# Can work on frontend in parallel with backend (different team member):
Task: "[US1] Create MaintenanceBannerForm component"
Task: "[US1] Create admin page at admin/maintenance-banners.astro"
Task: "[US1] Add link to admin navigation"
```

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2)

**Minimum Viable Product** = Admin creates banners + Users see banners

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T010) - **CRITICAL BLOCKER**
3. Complete Phase 3: User Story 1 (T011-T022) - Admin creates banners
4. Complete Phase 4: User Story 2 (T023-T034) - Users see banners
5. **STOP and VALIDATE**: Test complete flow end-to-end
6. Deploy/demo if ready

**Why this is MVP**:
- Delivers core value: Admins can communicate maintenance to users
- Both P1 stories work together to create a complete user journey
- Users can see active banners without needing edit/delete functionality
- Admins can create new banners (workaround for editing: delete + recreate)

### Incremental Delivery

1. **Foundation** (Phases 1-2) → Database + API infrastructure ready
2. **MVP** (Phases 3-4) → Admin creates, users see → **DEPLOY v1.0**
3. **Management** (Phase 5) → Edit/delete functionality → **DEPLOY v1.1**
4. **Admin UX** (Phase 6) → List view with history → **DEPLOY v1.2**
5. **Production Ready** (Phase 7) → Polish, performance, security → **DEPLOY v2.0**

### Parallel Team Strategy

With **2 developers** after Foundational phase:
- **Developer A**: User Story 1 (Admin creates) + User Story 3 (Admin edits/deletes)
- **Developer B**: User Story 2 (User sees) + User Story 4 (Admin views list)

With **1 developer** (sequential):
- Week 1: Setup + Foundational
- Week 2: User Story 1 + User Story 2 (MVP)
- Week 3: User Story 3 + User Story 4
- Week 4: Polish + deployment

---

## Success Metrics

Track these to verify success criteria from spec.md:

- **SC-001**: Admin creates banner in under 1 minute (measure during T020-T021)
- **SC-002**: Active banners appear within 5 seconds of start time (verify during T029 polling)
- **SC-003**: Banners visible on 320px mobile to 4K desktop (test during T033, T058)
- **SC-004**: Users understand messages without additional clicks (UX test during T030-T031)
- **SC-005**: <1 minute timing variance for banner activation (verify during T025-T026, T029)

---

## Notes

- **[P] tasks** = different files, no dependencies, can run in parallel
- **[Story] label** maps task to specific user story for traceability
- **TDD is NON-NEGOTIABLE**: All tests MUST be written first and fail before implementation (Constitution Principle II)
- Each user story should be independently completable and testable
- Verify tests fail before implementing (Red-Green-Refactor cycle)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- **MVP = User Story 1 + User Story 2** (both P1 priorities work together)
- User Story 3 and 4 (both P2) add convenience but are not essential for initial release

---

## Validation Checklist

Before marking feature complete, verify:

- [ ] All tests pass (≥80% coverage per Constitution)
- [ ] XSS prevention tested (T059)
- [ ] RBAC enforcement tested (T060)
- [ ] Timezone conversion accurate (T061)
- [ ] Multiple banners stack correctly (T062)
- [ ] Performance meets goals: <10ms query, <100ms API response (T065)
- [ ] Responsive design works (320px-4K) (T058)
- [ ] Database indexes created (T064)
- [ ] CLAUDE.md updated (T066)
- [ ] Quickstart.md validated (T067)
- [ ] Security review complete (T068)
- [ ] All constitutional principles verified:
  - [ ] Security-First (XSS sanitization, RBAC, validation)
  - [ ] TDD (tests written first, ≥80% coverage)
  - [ ] API-First (OpenAPI spec followed, RESTful endpoints)
  - [ ] User-Requested Testing (TDD followed, no premature test planning)
  - [ ] RBAC (Admin-only writes, public reads)
  - [ ] Schema Evolution (Hibernate migration, indexes, constraints)
