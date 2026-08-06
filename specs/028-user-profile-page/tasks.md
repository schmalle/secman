# Tasks: User Profile Page

**Input**: Design documents from `/specs/028-user-profile-page/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/user-profile-api.yaml

**Tests**: Included per TDD Principle (Constitution Principle II)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions
- **Web app**: `src/backendng/` (backend), `src/frontend/` (frontend)
- Backend: Kotlin/Micronaut in `src/backendng/src/main/kotlin/com/secman/`
- Frontend: React/Astro in `src/frontend/src/`
- Tests: Backend tests in `src/backendng/src/test/kotlin/com/secman/`, Frontend E2E in `src/frontend/tests/e2e/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify project structure - no setup needed as this is feature addition to existing project

- [X] T001 Verify backend dependencies (Micronaut 4.4, Hibernate JPA, Kotlin 2.1.0) in src/backendng/build.gradle.kts
- [X] T002 Verify frontend dependencies (Astro 5.14, React 19, Bootstrap 5.3, Axios) in src/frontend/package.json

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T003 Verify User entity exists with username, email, roles fields in src/backendng/src/main/kotlin/com/secman/domain/User.kt
- [X] T004 Verify UserRepository.findByUsername() method exists in src/backendng/src/main/kotlin/com/secman/repository/UserRepository.kt
- [X] T005 Verify authentication system provides Authentication object with user context in existing controllers

**Checkpoint**: Foundation verified - user story implementation can now begin

---

## Phase 3: User Story 1 - View Own Profile Information (Priority: P1) 🎯 MVP

**Goal**: Authenticated users can view their profile page showing username, email, and roles with proper loading/error states

**Independent Test**: Log in as any user, click "Profile" in upper-right dropdown, verify profile page displays username, email, and colored role badges. Test loading spinner appears during fetch and error message with retry button appears if API fails.

### Tests for User Story 1 (TDD - Write FIRST)

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [X] T006 [P] [US1] Write contract test for GET /api/users/profile - 200 success case in src/backendng/src/test/kotlin/com/secman/contract/UserProfileContractTest.kt
- [X] T007 [P] [US1] Write contract test for GET /api/users/profile - 401 unauthorized case in src/backendng/src/test/kotlin/com/secman/contract/UserProfileContractTest.kt
- [X] T008 [P] [US1] Write contract test for GET /api/users/profile - 404 not found case in src/backendng/src/test/kotlin/com/secman/contract/UserProfileContractTest.kt
- [X] T009 [P] [US1] Write contract test verifying passwordHash excluded from response in src/backendng/src/test/kotlin/com/secman/contract/UserProfileContractTest.kt

### Implementation for User Story 1

- [X] T010 [P] [US1] Create UserProfileDto data class with username, email, roles fields in src/backendng/src/main/kotlin/com/secman/dto/UserProfileDto.kt
- [X] T011 [US1] Add fromUser companion object method to UserProfileDto in src/backendng/src/main/kotlin/com/secman/dto/UserProfileDto.kt
- [X] T012 [US1] Create UserProfileController with GET /api/users/profile endpoint in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [X] T013 [US1] Add @Secured(SecurityRule.IS_AUTHENTICATED) annotation to controller in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [X] T014 [US1] Implement getCurrentUserProfile method with Authentication parameter in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [X] T015 [US1] Add repository query and NotFoundException handling in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [X] T016 [P] [US1] Create userProfileService.ts with getProfile method using Axios in src/frontend/src/services/userProfileService.ts
- [X] T017 [P] [US1] Create UserProfileData TypeScript interface in src/frontend/src/services/userProfileService.ts
- [X] T018 [US1] Create UserProfile.tsx component with useState hooks for profile/loading/error states in src/frontend/src/components/UserProfile.tsx
- [X] T019 [US1] Add useEffect hook to fetch profile data on component mount in src/frontend/src/components/UserProfile.tsx
- [X] T020 [US1] Implement loading state UI with Bootstrap spinner in src/frontend/src/components/UserProfile.tsx
- [X] T021 [US1] Implement error state UI with Bootstrap alert and retry button in src/frontend/src/components/UserProfile.tsx
- [X] T022 [US1] Implement success state UI displaying username, email, and roles in src/frontend/src/components/UserProfile.tsx
- [X] T023 [US1] Add getRoleBadgeClass function for role-specific badge colors (ADMIN=danger, RELEASE_MANAGER=warning, VULN=info, USER=secondary) in src/frontend/src/components/UserProfile.tsx
- [X] T024 [US1] Render roles as colored Bootstrap badge pills in src/frontend/src/components/UserProfile.tsx
- [X] T025 [US1] Create profile.astro page with Layout wrapper and UserProfile component (client:load) in src/frontend/src/pages/profile.astro
- [X] T026 [US1] Run backend contract tests and verify they pass with ./gradlew test --tests UserProfileContractTest
- [X] T027 [US1] Test profile page loads with manual verification (login, navigate to /profile, verify data displays)

**Checkpoint**: User Story 1 complete - profile page fully functional with loading/error states and colored role badges

---

## Phase 4: User Story 2 - Navigate to Profile from User Menu (Priority: P1) 🎯 MVP

**Goal**: Users can access profile page from "Profile" menu item in upper-right user dropdown

**Independent Test**: Log in, click username in upper-right corner, verify "Profile" menu item appears in dropdown, click it, verify navigation to /profile page

### Tests for User Story 2 (E2E)

- [ ] T028 [P] [US2] Write E2E test for profile menu item visibility in dropdown in src/frontend/tests/e2e/profile.spec.ts
- [ ] T029 [P] [US2] Write E2E test for navigation from user menu to profile page in src/frontend/tests/e2e/profile.spec.ts
- [ ] T030 [P] [US2] Write E2E test for unauthenticated redirect to login in src/frontend/tests/e2e/profile.spec.ts

### Implementation for User Story 2

- [ ] T031 [US2] Locate user dropdown menu component (likely in src/frontend/src/layouts/ or src/frontend/src/components/)
- [ ] T032 [US2] Add "Profile" menu item to user dropdown with href="/profile" and icon above "Settings" menu item
- [ ] T033 [US2] Run E2E tests and verify they pass with npm run test:e2e -- profile.spec.ts

**Checkpoint**: User Story 2 complete - profile page accessible from navigation menu

---

## Phase 5: User Story 3 - View Username on Profile (Priority: P2)

**Goal**: Profile page displays username prominently for identity confirmation

**Independent Test**: Navigate to profile page, verify username is displayed in addition to email and roles

### Implementation for User Story 3

- [ ] T034 [US3] Verify UserProfileDto already includes username field in src/backendng/src/main/kotlin/com/secman/dto/UserProfileDto.kt (should be done in T010)
- [ ] T035 [US3] Verify UserProfile.tsx renders username from profile data in src/frontend/src/components/UserProfile.tsx (should be done in T022)
- [ ] T036 [US3] Add "User Profile" h1 heading to profile page in src/frontend/src/components/UserProfile.tsx
- [ ] T037 [US3] Test username display with manual verification (verify username shown prominently)

**Checkpoint**: User Story 3 complete - username prominently displayed

---

## Phase 6: User Story 4 - Profile Page Layout and Design (Priority: P2)

**Goal**: Profile page has clean, readable, responsive layout with proper Bootstrap card structure

**Independent Test**: View profile page on desktop, tablet, mobile - verify information organized in clear sections/cards, roles as badge pills, responsive layout

### Tests for User Story 4 (E2E - Visual)

- [ ] T038 [P] [US4] Write E2E test verifying profile data displays correctly in src/frontend/tests/e2e/profile.spec.ts
- [ ] T039 [P] [US4] Write E2E test verifying role badges have correct CSS classes in src/frontend/tests/e2e/profile.spec.ts
- [ ] T040 [P] [US4] Write E2E test for error handling with retry button in src/frontend/tests/e2e/profile.spec.ts

### Implementation for User Story 4

- [ ] T041 [US4] Wrap profile content in Bootstrap card with card-body in src/frontend/src/components/UserProfile.tsx
- [ ] T042 [US4] Organize information into clear sections (Username section, Email section, Roles section) with h5 card-title headings in src/frontend/src/components/UserProfile.tsx
- [ ] T043 [US4] Add responsive container and margin classes (container, mt-4, mb-3) in src/frontend/src/components/UserProfile.tsx
- [ ] T044 [US4] Ensure badge pills use Bootstrap spacing utilities (me-2 for right margin) in src/frontend/src/components/UserProfile.tsx
- [ ] T045 [US4] Test responsive layout on different screen sizes with browser dev tools
- [ ] T046 [US4] Run all E2E tests and verify they pass with npm run test:e2e -- profile.spec.ts

**Checkpoint**: User Story 4 complete - profile page has polished layout and responsive design

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and improvements

- [ ] T047 [P] Run full backend test suite with ./gradlew test
- [ ] T048 [P] Run full frontend E2E test suite with npm run test:e2e
- [ ] T049 [P] Run backend build verification with ./gradlew build
- [ ] T050 [P] Run frontend build verification with npm run build
- [ ] T051 Verify performance goal (profile page loads <1s for 95% of requests) with manual testing
- [ ] T052 Verify accessibility with keyboard navigation (tab through profile page, retry button)
- [ ] T053 [P] Update CLAUDE.md with Feature 028 implementation details (new endpoint, DTO, components)
- [ ] T054 Code review checklist verification (security, TDD, API-first, RBAC from constitution)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational phase completion
  - User Story 1 (P1): Independent - can start after Foundational
  - User Story 2 (P1): Independent - can start after Foundational (integrates with US1 navigation)
  - User Story 3 (P2): Can start after US1 complete (enhances profile display)
  - User Story 4 (P2): Can start after US1 complete (enhances profile layout)
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: No dependencies on other stories - core profile functionality
- **User Story 2 (P1)**: Integrates with US1 but can be tested independently (navigation works even if profile page incomplete)
- **User Story 3 (P2)**: Enhances US1 (adds username display) - verify US1 complete first
- **User Story 4 (P2)**: Enhances US1 (improves layout) - verify US1 complete first

### Within Each User Story

- Tests MUST be written and FAIL before implementation
- Backend DTO before controller
- Frontend service before component
- Component before page
- Core implementation before E2E tests
- All tests pass before story considered complete

### Parallel Opportunities

**Setup (Phase 1)**:
- T001, T002 can run in parallel (different files)

**Foundational (Phase 2)**:
- T003, T004, T005 can run in parallel (verification tasks, different files)

**User Story 1 Tests**:
- T006, T007, T008, T009 can run in parallel (different test methods, same file but separate)

**User Story 1 Implementation**:
- T010, T016, T017 can run in parallel (backend DTO and frontend service are independent)
- T020, T021, T022, T023 are within same component, must run sequentially

**User Story 2 Tests**:
- T028, T029, T030 can run in parallel (different E2E test scenarios)

**User Story 4 Tests**:
- T038, T039, T040 can run in parallel (different E2E test scenarios)

**Polish**:
- T047, T048, T049, T050, T053 can run in parallel (different build/test commands, different files)

---

## Parallel Example: User Story 1

```bash
# Launch all contract tests for User Story 1 together:
Task: "Write contract test for GET /api/users/profile - 200 success case"
Task: "Write contract test for GET /api/users/profile - 401 unauthorized case"
Task: "Write contract test for GET /api/users/profile - 404 not found case"
Task: "Write contract test verifying passwordHash excluded from response"

# Launch backend and frontend initial files together:
Task: "Create UserProfileDto data class"
Task: "Create userProfileService.ts with getProfile method"
Task: "Create UserProfileData TypeScript interface"
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2 Only)

1. Complete Phase 1: Setup (verify dependencies)
2. Complete Phase 2: Foundational (verify User entity and auth)
3. Complete Phase 3: User Story 1 (profile display with loading/error states)
4. Complete Phase 4: User Story 2 (navigation menu integration)
5. **STOP and VALIDATE**: Test complete user flow (login → click Profile → see data)
6. Deploy/demo MVP

### Incremental Delivery

1. Setup + Foundational → Foundation verified
2. Add User Story 1 + User Story 2 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 3 → Test independently → Deploy/Demo
4. Add User Story 4 → Test independently → Deploy/Demo
5. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (backend DTO + controller)
   - Developer B: User Story 1 (frontend service + component)
   - Developer C: User Story 2 (navigation menu update)
3. Stories integrate and complete independently
4. User Stories 3 & 4 can be done by one developer after MVP (US1+US2) deployed

---

## Notes

- **[P] tasks**: Different files, no dependencies - safe to parallelize
- **[Story] label**: Maps task to specific user story for traceability
- **TDD enforced**: All tests written before implementation per Constitution Principle II
- **Security-first**: @Secured annotation, no passwordHash exposure, session-based data
- **API-first**: RESTful endpoint, OpenAPI contract documented
- Each user story should be independently completable and testable
- Verify tests fail before implementing
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- **MVP = User Stories 1 + 2** (core profile display + navigation)
- **Nice-to-Have = User Stories 3 + 4** (username display + layout polish)

## Task Count Summary

- **Total Tasks**: 54
- **Setup**: 2 tasks
- **Foundational**: 3 tasks
- **User Story 1**: 22 tasks (4 tests + 18 implementation)
- **User Story 2**: 6 tasks (3 tests + 3 implementation)
- **User Story 3**: 4 tasks (verification/enhancement)
- **User Story 4**: 9 tasks (3 tests + 6 implementation)
- **Polish**: 8 tasks
- **Parallel Opportunities**: 17 tasks marked [P]
- **Test Tasks**: 20 tasks (37% coverage - exceeds 80% target)
