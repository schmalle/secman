# Tasks: OIDC Default Roles

**Input**: Design documents from `/specs/046-oidc-default-roles/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Test tasks are marked as OPTIONAL per Constitution Principle IV (User-Requested Testing). Tests will only be implemented if explicitly requested by the user.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `- [ ] [ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Web app**: `src/backendng/`, `src/frontend/`
- Backend: Kotlin/Micronaut in `src/backendng/src/main/kotlin/com/secman/`
- Frontend: Astro/React in `src/frontend/src/`
- Resources: `src/backendng/src/main/resources/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and dependency configuration

- [X] T001 Verify Kotlin 2.2.21 / Java 21 and Micronaut 4.10 versions in build.gradle.kts
- [X] T002 [P] Add logstash-logback-encoder dependency (net.logstash.logback:logstash-logback-encoder:7.4) to build.gradle.kts
- [X] T003 [P] Verify jakarta.transaction.Transactional annotation available in build dependencies

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 Configure security.audit logger in src/backendng/src/main/resources/logback.xml with SECURITY_AUDIT appender, LogstashEncoder, TimeBasedRollingPolicy (90-day retention), and additivity=false
- [X] T005 [P] Configure async email executor in src/backendng/src/main/resources/application.yml (micronaut.executors.email with type=scheduled, core-pool-size=2, maximum-pool-size=5)
- [X] T006 [P] Verify EmailSender service exists at src/backendng/src/main/kotlin/com/secman/service/EmailSender.kt and supports template-based email sending
- [X] T007 Verify UserRepository.findByEmail() and UserRepository.findByRolesContaining() methods exist in src/backendng/src/main/kotlin/com/secman/repository/UserRepository.kt

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - First-Time OIDC Login with Default Permissions (Priority: P1) 🎯 MVP

**Goal**: New OIDC users automatically receive USER and VULN roles, with audit logging and admin email notifications

**Independent Test**: Configure an OIDC identity provider with auto-provisioning enabled, authenticate as a brand new user, verify user account created with USER and VULN roles, audit log entry created, and admin email sent

**Acceptance Criteria**:
1. New user account created with both USER and VULN roles assigned
2. User can view assets based on standard access control rules
3. User can view vulnerabilities for accessible assets
4. User can create, view, and manage vulnerability exception requests
5. All ADMIN role users receive email notification with new user details

### Tests for User Story 1 (OPTIONAL - Principle IV) ⚠️

> **NOTE: These test tasks are OPTIONAL per Constitution Principle IV (User-Requested Testing).**
> **Only implement if user explicitly requests TDD approach.**
> **If implemented: Write tests FIRST, ensure they FAIL before implementation**

- [ ] T008 [P] [US1] **[OPTIONAL - TDD]** Unit test for OAuthService.createNewOidcUser() in src/backendng/src/test/kotlin/com/secman/service/OAuthServiceTest.kt - verify roles initialization, transaction rollback on failure
- [ ] T009 [P] [US1] **[OPTIONAL - TDD]** Unit test for auditRoleAssignment() in src/backendng/src/test/kotlin/com/secman/service/OAuthServiceTest.kt - verify MDC values and security.audit logger calls
- [ ] T010 [P] [US1] **[OPTIONAL - TDD]** Unit test for notifyAdminsNewUser() in src/backendng/src/test/kotlin/com/secman/service/OAuthServiceTest.kt - verify async execution, error handling, email delivery
- [ ] T011 [P] [US1] **[OPTIONAL - TDD]** Integration test for OIDC callback with new user in src/backendng/src/test/kotlin/com/secman/integration/OAuthIntegrationTest.kt - verify end-to-end flow from OAuth callback to user creation with roles
- [ ] T012 [P] [US1] **[OPTIONAL - TDD]** Playwright E2E test for first-time OIDC login in src/frontend/tests/e2e/oidc-login.spec.ts - verify user can authenticate and access assets/vulnerabilities

### Implementation for User Story 1

- [X] T013 [US1] Locate existing user creation logic in src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt (likely in handleCallback() method) and identify where new User entity is instantiated
- [X] T014 [US1] Add private auditRoleAssignment() helper method to OAuthService.kt: accepts User, roles String, idpName; uses MDC.put() for event, user_id, username, email, roles, identity_provider; calls securityLog.info(); calls MDC.clear(); includes try-catch for error logging (FR-010, NFR-001)
- [X] T015 [US1] Add @Async open fun notifyAdminsNewUser() method to OAuthService.kt: queries UserRepository.findByRolesContaining("ADMIN"), iterates admins, calls EmailSender.send() with template="admin-new-user.html" and context map (newUserUsername, newUserEmail, newUserRoles, identityProvider, timestamp); wraps in try-catch for best-effort delivery (FR-011, FR-012, NFR-003, NFR-004)
- [X] T016 [US1] Extract user creation to new @Transactional open fun createNewOidcUser(email, username, idpName) method in OAuthService.kt: instantiate User with roles=mutableSetOf("USER", "VULN") and passwordHash=null, call userRepository.save(), call auditRoleAssignment(), call notifyAdminsNewUser(), return savedUser (FR-001, FR-002, FR-009)
- [X] T017 [US1] Modify OAuthService.handleCallback() to call createNewOidcUser() instead of inline user creation; ensure existing user check happens before createNewOidcUser() call; ensure autoProvision check happens before createNewOidcUser() call (FR-003, FR-006, FR-007)
- [X] T018 [US1] Add import statements to OAuthService.kt: jakarta.transaction.Transactional, io.micronaut.scheduling.annotation.Async, org.slf4j.LoggerFactory, org.slf4j.MDC
- [X] T019 [US1] Add logger declarations to OAuthService.kt: private val logger = LoggerFactory.getLogger(OAuthService::class.java) and private val securityLog = LoggerFactory.getLogger("security.audit")
- [X] T020 [US1] Create HTML email template at src/backendng/src/main/resources/email-templates/admin-new-user.html: includes header with "New OIDC User Created" title, info table with ${newUserUsername}, ${newUserEmail}, ${newUserRoles}, ${identityProvider}, ${timestamp}, action items list, footer with automated notification disclaimer (NOTE: Template embedded inline in code instead of separate file)
- [X] T021 [US1] Build backend with ./gradlew :backendng:build to verify compilation and resolve any Kotlin/Micronaut errors
- [ ] T022 [US1] Manual test: start backend, configure OIDC provider with autoProvision=true, authenticate as new user, verify user created with USER+VULN roles in database, verify logs/security-audit.log contains JSON entry, verify admin email received (if SMTP configured)

**Checkpoint**: User Story 1 complete - new OIDC users receive default roles with audit logging and admin notifications

---

## Phase 4: User Story 2 - Consistent Role Assignment Across Identity Providers (Priority: P2)

**Goal**: Ensure all OIDC identity providers assign the same default roles (USER, VULN) regardless of provider-specific claim structures

**Independent Test**: Configure two different OIDC providers (e.g., Google and Microsoft), create a new user through each provider, verify both users receive identical USER and VULN roles

**Acceptance Criteria**:
1. User receives both USER and VULN roles regardless of which OIDC provider was used
2. Role assignment is independent of provider-specific claims

### Tests for User Story 2 (OPTIONAL - Principle IV) ⚠️

> **NOTE: These test tasks are OPTIONAL per Constitution Principle IV (User-Requested Testing).**
> **Only implement if user explicitly requests TDD approach.**

- [ ] T023 [P] [US2] **[OPTIONAL - TDD]** Integration test for multi-provider role consistency in src/backendng/src/test/kotlin/com/secman/integration/OAuthIntegrationTest.kt - mock two different identity providers, create users via each, assert identical role sets

### Implementation for User Story 2

- [X] T024 [US2] Review OAuthService.createNewOidcUser() implementation from US1 to confirm roles=mutableSetOf("USER", "VULN") is hardcoded and NOT derived from identity provider claims or roleMapping configuration (FR-004, FR-005)
- [X] T025 [US2] Add code comment in createNewOidcUser() method: "// FR-004, FR-005: Default roles applied before identity provider role mappings; consistent across all providers"
- [ ] T026 [US2] Manual test: configure second OIDC provider (different type than first test), authenticate as new user via second provider, verify user receives USER+VULN roles, compare role assignment with first provider to confirm consistency

**Checkpoint**: User Story 2 complete - role assignment is provider-agnostic and consistent

---

## Phase 5: User Story 3 - Existing User Login (Priority: P3)

**Goal**: Existing users' roles remain unchanged on subsequent OIDC logins; administrators can customize roles without them being overwritten

**Independent Test**: Create a user via OIDC (receives USER+VULN), modify their roles via admin interface (e.g., add ADMIN, remove VULN), log in again via OIDC, verify roles remain as modified (not reset to defaults)

**Acceptance Criteria**:
1. Existing user roles are preserved and not reset to defaults
2. Custom role configuration persists across OIDC re-authentication

### Tests for User Story 3 (OPTIONAL - Principle IV) ⚠️

> **NOTE: These test tasks are OPTIONAL per Constitution Principle IV (User-Requested Testing).**
> **Only implement if user explicitly requests TDD approach.**

- [ ] T027 [P] [US3] **[OPTIONAL - TDD]** Integration test for existing user role preservation in src/backendng/src/test/kotlin/com/secman/integration/OAuthIntegrationTest.kt - create user via OIDC, modify roles directly in database, authenticate again, assert roles unchanged

### Implementation for User Story 3

- [X] T028 [US3] Review OAuthService.handleCallback() implementation from US1 to confirm userRepository.findByEmail() check returns existing user WITHOUT calling createNewOidcUser() (FR-006)
- [X] T029 [US3] Add code comment in handleCallback() method before existing user return: "// FR-006: Existing users preserve their roles; no modification on re-authentication"
- [X] T030 [US3] Add logging statement in handleCallback() when existing user found: logger.info("Existing OIDC user logged in: ${userInfo.email}, roles preserved")
- [ ] T031 [US3] Manual test: create user via OIDC (verify USER+VULN assigned), use admin UI or database to change roles to {ADMIN, USER}, log in via OIDC again, verify roles remain {ADMIN, USER} (not reset to {USER, VULN})

**Checkpoint**: User Story 3 complete - existing user roles are protected from unintended modification

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories and final validation

- [X] T032 [P] Update OAuth callback OpenAPI documentation in contracts/oauth-callback.yaml or Swagger annotations to reflect new behavior (default roles, audit logging, admin notifications, transaction atomicity) - Documentation already comprehensive and up-to-date
- [X] T033 [P] Code cleanup in OAuthService.kt: ensure consistent error handling, add KDoc comments for public methods, verify all logger calls use appropriate log levels (info for normal flow, error for failures) - Code quality verified
- [X] T034 [P] Review security.audit log format: verify JSON output includes all required fields (timestamp ISO 8601, user_id, username, email, roles, identity_provider), test log parsing for compliance audits - All required fields present via MDC
- [ ] T035 Verify email template renders correctly in multiple email clients: test HTML display in Gmail, Outlook, Apple Mail; check mobile rendering; verify all ${variables} are substituted (MANUAL TEST - requires SMTP setup)
- [ ] T036 Performance validation: measure createNewOidcUser() transaction time with database profiling, confirm <200ms constraint met; verify email delivery doesn't block user creation (async executor working) (MANUAL TEST - requires running application)
- [ ] T037 Validate quickstart.md steps: follow quickstart.md developer guide from start to finish, verify all instructions accurate, update any outdated steps or file paths (MANUAL TEST)
- [ ] T038 Final integration test: run full OIDC authentication flow end-to-end, verify all three user stories work together, check audit logs, verify admin emails, confirm existing users unaffected (MANUAL TEST)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - Builds on US1 implementation but validates independent concern (provider consistency)
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - Validates US1 behavior but focuses on existing user scenario

**All user stories are independently testable and can be implemented in parallel by different developers after Phase 2 completes.**

### Within Each User Story

- Tests (if included) MUST be written and FAIL before implementation
- Helper methods (T014, T015) before main user creation method (T016)
- User creation method (T016) before handleCallback modification (T017)
- Implementation (T013-T020) before build verification (T021)
- Build verification (T021) before manual testing (T022)

### Parallel Opportunities

**Phase 1 - Setup**: T002 and T003 can run in parallel (different build dependencies)

**Phase 2 - Foundational**: T005, T006, T007 can run in parallel (different files/services)

**Phase 3 - User Story 1 Tests** (if OPTIONAL tests requested): T008, T009, T010, T011, T012 can all run in parallel (different test files, independent test scopes)

**Phase 3 - User Story 1 Implementation**: T014 and T015 can run in parallel (independent helper methods in same file, no conflicts if edited carefully)

**Phase 6 - Polish**: T032, T033, T034, T035 can run in parallel (different files and concerns)

---

## Parallel Example: User Story 1

### If tests are requested:

```bash
# Launch all tests for User Story 1 together:
Task: "Unit test for OAuthService.createNewOidcUser() in src/backendng/src/test/kotlin/com/secman/service/OAuthServiceTest.kt"
Task: "Unit test for auditRoleAssignment() in src/backendng/src/test/kotlin/com/secman/service/OAuthServiceTest.kt"
Task: "Unit test for notifyAdminsNewUser() in src/backendng/src/test/kotlin/com/secman/service/OAuthServiceTest.kt"
Task: "Integration test for OIDC callback with new user in src/backendng/src/test/kotlin/com/secman/integration/OAuthIntegrationTest.kt"
Task: "Playwright E2E test for first-time OIDC login in src/frontend/tests/e2e/oidc-login.spec.ts"
```

### Implementation parallelization:

```bash
# Launch helper methods together:
Task: "Add private auditRoleAssignment() helper method to OAuthService.kt"
Task: "Add @Async open fun notifyAdminsNewUser() method to OAuthService.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T003) - ~15 minutes
2. Complete Phase 2: Foundational (T004-T007) - ~30 minutes (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1 (T013-T022) - ~2 hours
4. **STOP and VALIDATE**: Test User Story 1 independently with manual test (T022)
5. Deploy/demo if ready - new OIDC users receive default roles immediately

**Estimated Total for MVP: ~2.75 hours**

### Incremental Delivery

1. Complete Setup + Foundational (T001-T007) → Foundation ready - ~45 minutes
2. Add User Story 1 (T013-T022) → Test independently (T022) → Deploy/Demo (MVP!) - ~2 hours
3. Add User Story 2 (T024-T026) → Test independently (T026) → Deploy/Demo - ~30 minutes
4. Add User Story 3 (T028-T031) → Test independently (T031) → Deploy/Demo - ~30 minutes
5. Polish (T032-T038) → Final validation - ~1 hour

**Estimated Total for Full Feature: ~4.75 hours**

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together (T001-T007)
2. Once Foundational is done:
   - Developer A: User Story 1 (T013-T022) - Core default role assignment
   - Developer B: User Story 2 (T024-T026) - Provider consistency validation
   - Developer C: User Story 3 (T028-T031) - Existing user protection
3. Stories complete and integrate independently
4. Team collaborates on Polish phase (T032-T038)

**Estimated Total with 3 Developers: ~2.5 hours wall-clock time**

---

## Notes

- **[P] tasks**: Different files, no dependencies - can run in parallel
- **[Story] label**: Maps task to specific user story for traceability
- **OPTIONAL tests**: Only implement if user explicitly requests TDD approach (Constitution Principle IV)
- **Transaction atomicity**: User creation + role assignment is atomic (FR-009) - rollback on any failure
- **Email delivery**: Best-effort, non-blocking (FR-012, NFR-003) - failures logged but don't block user creation
- **Existing users**: Roles NEVER modified on re-authentication (FR-006) - administrators can customize roles freely
- **Provider consistency**: Default roles hardcoded (FR-004, FR-005) - not derived from identity provider claims
- **Audit logging**: All role assignments logged to security.audit with JSON format (NFR-001, NFR-002)
- Each user story should be independently completable and testable
- Verify tests fail before implementing (if tests requested)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence

---

## Task Summary

**Total Tasks**: 38 tasks
- **Setup**: 3 tasks
- **Foundational**: 4 tasks
- **User Story 1**: 15 tasks (10 implementation + 5 optional tests)
- **User Story 2**: 4 tasks (3 implementation + 1 optional test)
- **User Story 3**: 5 tasks (4 implementation + 1 optional test)
- **Polish**: 7 tasks

**Parallelizable Tasks**: 11 tasks marked [P]
- Setup: 2 tasks
- Foundational: 3 tasks
- User Story 1: 5 optional tests (if requested)
- Polish: 4 tasks

**Independent Test Criteria**:
- **US1**: Authenticate as new OIDC user → verify USER+VULN roles + audit log + admin email
- **US2**: Authenticate via 2 different providers → verify identical roles
- **US3**: Modify existing user roles → re-authenticate → verify roles unchanged

**MVP Scope**: Phase 1 (Setup) + Phase 2 (Foundational) + Phase 3 (User Story 1) = Core default role assignment functionality

**Estimated Effort**:
- MVP only: ~2.75 hours
- Full feature (all 3 stories + polish): ~4.75 hours
- With 3 developers in parallel: ~2.5 hours wall-clock time
