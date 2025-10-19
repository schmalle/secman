# Tasks: Admin User Notification System

**Input**: Design documents from `/specs/027-admin-user-notifications/`
**Prerequisites**: plan.md (tech stack), spec.md (user stories), data-model.md (entities), contracts/ (API specs)

**Tests**: Following TDD principle (Constitution Principle II), tests MUST be written first and FAIL before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend tests**: `src/frontend/tests/e2e/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and dependency configuration

**IMPORTANT**: SMTP configuration is database-driven (stored in SystemSetting entity), NOT in application.yml

- [ ] T001 Verify Micronaut Email dependency in src/backendng/build.gradle.kts (dependency already present with JavaMail support)
- [ ] T002 [P] Install MailHog (or similar) SMTP testing tool for local development (for testing email sending)
- [ ] T003 [P] Verify backend builds with new dependencies: ./gradlew build
- [ ] T004 Prepare SystemSettingsInitializer to seed default SMTP configuration to database (will be implemented in Foundational phase)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T005 Create SystemSetting entity in src/backendng/src/main/kotlin/com/secman/domain/SystemSetting.kt with support for SMTP configuration keys: smtp_host, smtp_port, smtp_auth, smtp_starttls, smtp_username, smtp_password
- [ ] T006 Create EmailNotificationEvent entity in src/backendng/src/main/kotlin/com/secman/domain/EmailNotificationEvent.kt
- [ ] T007 [P] Create SystemSettingRepository interface in src/backendng/src/main/kotlin/com/secman/repository/SystemSettingRepository.kt with queries: findByKey, findByKeyIn
- [ ] T008 [P] Create EmailNotificationEventRepository interface in src/backendng/src/main/kotlin/com/secman/repository/EmailNotificationEventRepository.kt with queries: findByTimestampBefore, findByRecipientEmail
- [ ] T009 Create SystemSettingsInitializer bean in src/backendng/src/main/kotlin/com/secman/config/SystemSettingsInitializer.kt (seeds default notification and SMTP settings on startup from database, not YAML)
- [ ] T010 Verify database tables created: Start backend and check logs for "Created table system_settings" and "Created table email_notification_events"

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Enable/Disable Email Notifications (Priority: P1) 🎯 MVP

**Goal**: Allow ADMIN users to enable/disable email notifications and configure sender address through the Admin UI. Settings persist across restarts and show enabled by default.

**Independent Test**: Log in as ADMIN, navigate to Admin Settings, toggle notification setting, refresh page - setting should persist. Delivers immediate value by giving admins control over notification behavior.

### Tests for User Story 1

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T011 [P] [US1] Contract test: GET /api/settings/notifications returns 200 with settings in src/backendng/src/test/kotlin/com/secman/contract/NotificationSettingsContractTest.kt
- [ ] T012 [P] [US1] Contract test: PUT /api/settings/notifications updates and returns 200 in src/backendng/src/test/kotlin/com/secman/contract/NotificationSettingsContractTest.kt
- [ ] T013 [P] [US1] Contract test: PUT /api/settings/notifications returns 403 for non-ADMIN in src/backendng/src/test/kotlin/com/secman/contract/NotificationSettingsContractTest.kt
- [ ] T014 [P] [US1] Contract test: PUT /api/settings/notifications returns 400 for invalid email format in src/backendng/src/test/kotlin/com/secman/contract/NotificationSettingsContractTest.kt
- [ ] T015 [P] [US1] Unit test: SystemSettingService caching behavior in src/backendng/src/test/kotlin/com/secman/service/SystemSettingServiceTest.kt

### Implementation for User Story 1

- [ ] T016 [P] [US1] Create NotificationSettingsDto in src/backendng/src/main/kotlin/com/secman/dto/NotificationSettingsDto.kt
- [ ] T017 [US1] Implement SystemSettingService with in-memory caching in src/backendng/src/main/kotlin/com/secman/service/SystemSettingService.kt
- [ ] T018 [US1] Implement NotificationSettingsController (GET and PUT endpoints) in src/backendng/src/main/kotlin/com/secman/controller/NotificationSettingsController.kt
- [ ] T019 [US1] Add RBAC annotations (@Secured) to NotificationSettingsController endpoints
- [ ] T020 [US1] Add email format validation to NotificationSettingsDto
- [ ] T021 [US1] Verify backend tests pass: ./gradlew test
- [ ] T022 [P] [US1] Create notificationSettingsService.ts API client in src/frontend/src/services/notificationSettingsService.ts
- [ ] T023 [P] [US1] Create NotificationSettings.tsx React component in src/frontend/src/components/NotificationSettings.tsx
- [ ] T024 [US1] Update src/frontend/src/pages/admin/settings.astro to include NotificationSettings component
- [ ] T025 [P] [US1] E2E test: Display notification settings in admin page in src/frontend/tests/e2e/admin-notifications.spec.ts
- [ ] T026 [P] [US1] E2E test: Toggle notifications on and off in src/frontend/tests/e2e/admin-notifications.spec.ts
- [ ] T027 [P] [US1] E2E test: Update sender email address in src/frontend/tests/e2e/admin-notifications.spec.ts
- [ ] T028 [US1] Verify frontend E2E tests pass: npm test

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently. ADMIN users can configure notification settings through the UI, and settings persist.

---

## Phase 4: User Story 2 - Receive Notifications for Manual User Creation (Priority: P1)

**Goal**: Send email notifications to all ADMIN users when a new user is created through the "Manage Users" UI. Emails contain user details (username, email, timestamp, creator). User creation never fails due to email errors (non-blocking).

**Independent Test**: Enable notifications via Admin Settings (US1), create a new user via "Manage Users" UI, verify all ADMIN users receive properly formatted email. Delivers immediate security value by ensuring admin awareness of manual account creation.

### Tests for User Story 2

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T029 [P] [US2] Unit test: EmailNotificationService sends notifications to all ADMIN users in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt
- [ ] T030 [P] [US2] Unit test: EmailNotificationService respects enabled/disabled setting in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt
- [ ] T031 [P] [US2] Unit test: EmailNotificationService skips invalid email addresses in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt
- [ ] T032 [P] [US2] Unit test: EmailNotificationService uses configured sender email in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt
- [ ] T033 [P] [US2] Integration test: createUser sends notifications to all ADMIN users in src/backendng/src/test/kotlin/com/secman/integration/UserCreationNotificationTest.kt
- [ ] T034 [P] [US2] Integration test: createUser succeeds even when email sending fails in src/backendng/src/test/kotlin/com/secman/integration/UserCreationNotificationTest.kt

### Implementation for User Story 2

- [ ] T035 [P] [US2] Create NotificationEmailData data class in src/backendng/src/main/kotlin/com/secman/service/EmailNotificationService.kt
- [ ] T036 [US2] Implement generateEmailHtml function (Kotlin string template) in src/backendng/src/main/kotlin/com/secman/service/EmailNotificationService.kt
- [ ] T037 [US2] Implement EmailNotificationService with @Async and @Transactional(REQUIRES_NEW) in src/backendng/src/main/kotlin/com/secman/service/EmailNotificationService.kt
- [ ] T038 [US2] Implement sendAdminNotification method with try-catch isolation in src/backendng/src/main/kotlin/com/secman/service/EmailNotificationService.kt
- [ ] T039 [US2] Implement audit logging (save EmailNotificationEvent records) in EmailNotificationService
- [ ] T040 [US2] Add helper method to query ADMIN users from UserRepository in src/backendng/src/main/kotlin/com/secman/repository/UserRepository.kt
- [ ] T041 [US2] Modify UserService.createUser to call emailNotificationService.sendAdminNotification (fire-and-forget) in src/backendng/src/main/kotlin/com/secman/service/UserService.kt
- [ ] T042 [US2] Verify backend tests pass: ./gradlew test
- [ ] T043 [US2] Manual test: Start MailHog, create user via UI, verify email received with correct details

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently. ADMIN users receive email notifications for manually created users, and user creation never fails due to email issues.

---

## Phase 5: User Story 3 - Receive Notifications for OAuth Registration (Priority: P2)

**Goal**: Send email notifications to all ADMIN users when a new user registers via OAuth (GitHub, Google, etc.). Emails clearly indicate OAuth provider and user information.

**Independent Test**: Enable notifications via Admin Settings (US1), complete OAuth registration flow for new user, verify all ADMIN users receive notification with OAuth provider details. Delivers value by extending admin awareness to OAuth-based user creation.

### Tests for User Story 3

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T044 [P] [US3] Integration test: OAuth registration sends notifications to all ADMIN users in src/backendng/src/test/kotlin/com/secman/integration/OAuthNotificationTest.kt
- [ ] T045 [P] [US3] Integration test: OAuth notification email indicates OAuth provider (GitHub/Google) in src/backendng/src/test/kotlin/com/secman/integration/OAuthNotificationTest.kt
- [ ] T046 [P] [US3] Integration test: OAuth notification omits "created by" field (self-registration) in src/backendng/src/test/kotlin/com/secman/integration/OAuthNotificationTest.kt

### Implementation for User Story 3

- [ ] T047 [US3] Identify OAuth registration completion point in src/backendng/src/main/kotlin/com/secman/controller/OAuthController.kt (or equivalent)
- [ ] T048 [US3] Add call to emailNotificationService.sendAdminNotification with method="OAuth/{provider}" in OAuth registration handler
- [ ] T049 [US3] Update generateEmailHtml to conditionally show/hide "created by" field based on registration method in src/backendng/src/main/kotlin/com/secman/service/EmailNotificationService.kt
- [ ] T050 [US3] Verify backend tests pass: ./gradlew test
- [ ] T051 [US3] Manual test: Complete OAuth flow, verify email received with OAuth provider indicated

**Checkpoint**: All P1 and P2 user stories (US1, US2, US3) should now be independently functional. Admins receive notifications for both manual and OAuth user creation.

---

## Phase 6: User Story 4 - Professional Email Formatting (Priority: P2)

**Goal**: Ensure notification emails use professional HTML formatting with clear structure (headings, tables/lists), correct subject lines, and all required details (username, email, method, timestamp, creator).

**Independent Test**: Trigger notification (create user), review email content for clarity, formatting, and completeness. Delivers value by making notifications immediately useful and easy to scan.

### Tests for User Story 4

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T052 [P] [US4] Unit test: Email subject line matches "New User Registered: [username]" in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt
- [ ] T053 [P] [US4] Unit test: Email body includes all required fields (username, email, method, timestamp) in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt
- [ ] T054 [P] [US4] Unit test: Email body includes creator username for manual creation in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt
- [ ] T055 [P] [US4] Unit test: Email body omits creator username for OAuth registration in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt

### Implementation for User Story 4

- [ ] T056 [P] [US4] Design professional HTML email template with CSS styling in src/backendng/src/main/kotlin/com/secman/service/EmailNotificationService.kt
- [ ] T057 [US4] Update generateEmailHtml to use professional template (tables, headings, branded colors) in src/backendng/src/main/kotlin/com/secman/service/EmailNotificationService.kt
- [ ] T058 [US4] Ensure email subject line follows pattern "New User Registered: {username}" in EmailNotificationService
- [ ] T059 [US4] Add timestamp formatting (human-readable, timezone-aware) to email body in EmailNotificationService
- [ ] T060 [US4] Add fallback template for missing data (simple HTML with basic fields) in EmailNotificationService
- [ ] T061 [US4] Verify backend tests pass: ./gradlew test
- [ ] T062 [US4] Manual test: Create user, open email in MailHog, verify professional appearance and all fields present

**Checkpoint**: Notification emails should now be professionally formatted with clear structure, making them actionable and easy to read.

---

## Phase 7: User Story 5 - Email Delivery Monitoring (Priority: P3)

**Goal**: Log all email notification attempts (success and failure) to EmailNotificationEvent table for audit trail and troubleshooting. Logs include timestamp, recipient, new user details, and failure reason (if failed).

**Independent Test**: Simulate email server failure, create user, verify failure logged with error details in database. Delivers value by making email delivery problems visible to operators.

### Tests for User Story 5

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T063 [P] [US5] Integration test: Successful email send creates audit record with status="sent" in src/backendng/src/test/kotlin/com/secman/integration/EmailAuditTest.kt
- [ ] T064 [P] [US5] Integration test: Failed email send creates audit record with status="failed" and error reason in src/backendng/src/test/kotlin/com/secman/integration/EmailAuditTest.kt
- [ ] T065 [P] [US5] Unit test: Audit record includes all required fields (recipient, subject, newUsername, timestamp) in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt

### Implementation for User Story 5

- [ ] T066 [US5] Verify EmailNotificationService already logs audit records in sendAdminNotification (should exist from US2)
- [ ] T067 [US5] Ensure audit logging happens in per-recipient try-catch block (success/failure per recipient) in src/backendng/src/main/kotlin/com/secman/service/EmailNotificationService.kt
- [ ] T068 [US5] Verify bodyPreview field truncated to 1000 chars in audit records
- [ ] T069 [US5] Add query method to EmailNotificationEventRepository for troubleshooting (findByRecipientEmailOrderByTimestampDesc) in src/backendng/src/main/kotlin/com/secman/repository/EmailNotificationEventRepository.kt
- [ ] T070 [US5] Verify backend tests pass: ./gradlew test
- [ ] T071 [US5] Manual test: Stop MailHog, create user, verify failure logged in email_notification_events table with error reason

**Checkpoint**: All user stories (P1-P3) should now be independently functional. Audit trail provides complete visibility into notification delivery status.

---

## Phase 8: Audit Log Cleanup (Cross-Cutting)

**Purpose**: Implement 30-day retention policy for EmailNotificationEvent records

- [ ] T072 [P] Create EmailNotificationCleanupTask scheduled task in src/backendng/src/main/kotlin/com/secman/task/EmailNotificationCleanupTask.kt
- [ ] T073 Implement @Scheduled(cron = "0 0 2 * * *") method to delete records older than 30 days in EmailNotificationCleanupTask
- [ ] T074 [P] Add query method deleteByTimestampBefore to EmailNotificationEventRepository in src/backendng/src/main/kotlin/com/secman/repository/EmailNotificationEventRepository.kt
- [ ] T075 [P] Unit test: Cleanup task deletes old records and preserves recent ones in src/backendng/src/test/kotlin/com/secman/task/EmailNotificationCleanupTaskTest.kt
- [ ] T076 Verify cleanup task runs: Wait 24+ hours or manually trigger, check logs for "Cleanup completed, deleted N records"

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] T077 [P] Update CLAUDE.md with new entities (SystemSetting, EmailNotificationEvent) and endpoints (GET/PUT /api/settings/notifications)
- [ ] T078 [P] Update README.md with SMTP configuration instructions (if not already documented)
- [ ] T079 [P] Add logging for notification configuration changes (who enabled/disabled, when) in SystemSettingService
- [ ] T080 [P] Add logging for notification send attempts (info level for success, warn for failure) in EmailNotificationService
- [ ] T081 [P] Verify edge case handling: No ADMIN users (log warning, don't crash) in EmailNotificationService
- [ ] T082 [P] Verify edge case handling: ADMIN user with invalid email (skip user, log warning, continue) in EmailNotificationService
- [ ] T083 [P] Verify edge case handling: Email template render failure (fallback to simple template) in EmailNotificationService
- [ ] T084 Performance test: User creation completes in <3 seconds (SC-004) even when sending to 100 ADMIN users
- [ ] T085 Performance test: Email delivery within 2 minutes (SC-002)
- [ ] T086 [P] Security review: Verify @Secured annotations on all admin-only endpoints
- [ ] T087 [P] Security review: Verify input validation on sender email address (prevent injection)
- [ ] T088 Run quickstart.md validation: Follow manual testing workflows from quickstart.md
- [ ] T089 [P] Code cleanup: Remove unused imports, format code per project standards
- [ ] T090 Final integration test: Complete end-to-end workflow (enable notifications → create user → receive email → disable notifications → verify no email)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-7)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed) or sequentially in priority order (P1 → P2 → P3)
- **Audit Cleanup (Phase 8)**: Can proceed in parallel with user stories (independent functionality)
- **Polish (Phase 9)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P1)**: Depends on US1 for configuration, but independently testable
- **User Story 3 (P2)**: Depends on US2 for email service, but independently testable
- **User Story 4 (P2)**: Extends US2/US3 email formatting, but independently testable
- **User Story 5 (P3)**: Audit logging should be integrated into US2, but can be enhanced independently

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD principle)
- Models before services
- Services before endpoints
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

- All Setup tasks marked [P] can run in parallel
- All Foundational tasks marked [P] can run in parallel (within Phase 2)
- Once Foundational phase completes, all user stories can start in parallel (if team capacity allows)
- All tests for a user story marked [P] can run in parallel
- Models within a story marked [P] can run in parallel
- Different user stories can be worked on in parallel by different team members
- Audit Cleanup (Phase 8) can proceed in parallel with user story implementation

---

## Parallel Example: User Story 1

```bash
# Launch all contract tests for User Story 1 together:
Task: "Contract test: GET /api/settings/notifications returns 200 with settings in src/backendng/src/test/kotlin/com/secman/contract/NotificationSettingsContractTest.kt"
Task: "Contract test: PUT /api/settings/notifications updates and returns 200 in src/backendng/src/test/kotlin/com/secman/contract/NotificationSettingsContractTest.kt"
Task: "Contract test: PUT /api/settings/notifications returns 403 for non-ADMIN in src/backendng/src/test/kotlin/com/secman/contract/NotificationSettingsContractTest.kt"
Task: "Contract test: PUT /api/settings/notifications returns 400 for invalid email format in src/backendng/src/test/kotlin/com/secman/contract/NotificationSettingsContractTest.kt"
Task: "Unit test: SystemSettingService caching behavior in src/backendng/src/test/kotlin/com/secman/service/SystemSettingServiceTest.kt"

# Then launch all parallel implementation tasks together:
Task: "Create NotificationSettingsDto in src/backendng/src/main/kotlin/com/secman/dto/NotificationSettingsDto.kt"
Task: "Create notificationSettingsService.ts API client in src/frontend/src/services/notificationSettingsService.ts"
Task: "Create NotificationSettings.tsx React component in src/frontend/src/components/NotificationSettings.tsx"
```

---

## Parallel Example: User Story 2

```bash
# Launch all unit tests for User Story 2 together:
Task: "Unit test: EmailNotificationService sends notifications to all ADMIN users in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt"
Task: "Unit test: EmailNotificationService respects enabled/disabled setting in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt"
Task: "Unit test: EmailNotificationService skips invalid email addresses in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt"
Task: "Unit test: EmailNotificationService uses configured sender email in src/backendng/src/test/kotlin/com/secman/service/EmailNotificationServiceTest.kt"
Task: "Integration test: createUser sends notifications to all ADMIN users in src/backendng/src/test/kotlin/com/secman/integration/UserCreationNotificationTest.kt"
Task: "Integration test: createUser succeeds even when email sending fails in src/backendng/src/test/kotlin/com/secman/integration/UserCreationNotificationTest.kt"

# Then launch parallel implementation tasks:
Task: "Create NotificationEmailData data class in src/backendng/src/main/kotlin/com/secman/service/EmailNotificationService.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1 (Configuration UI)
4. Complete Phase 4: User Story 2 (Manual user creation notifications)
5. **STOP and VALIDATE**: Test US1 + US2 independently
6. Deploy/demo if ready

**Rationale**: US1 + US2 together provide complete MVP (configure + receive notifications for manual user creation). OAuth (US3) and polish (US4/US5) are enhancements.

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (Configuration ready)
3. Add User Story 2 → Test independently → Deploy/Demo (MVP! Manual notifications working)
4. Add User Story 3 → Test independently → Deploy/Demo (OAuth notifications added)
5. Add User Story 4 → Test independently → Deploy/Demo (Professional formatting)
6. Add User Story 5 → Test independently → Deploy/Demo (Audit visibility)
7. Add Audit Cleanup → Deploy/Demo (Complete feature)
8. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (Configuration UI)
   - Developer B: User Story 2 (Manual notifications) - starts after US1 backend entities complete
   - Developer C: Audit Cleanup (Phase 8) - can proceed independently
3. After US1 + US2 complete:
   - Developer A: User Story 3 (OAuth notifications)
   - Developer B: User Story 4 (Email formatting)
   - Developer C: User Story 5 (Monitoring)
4. Stories complete and integrate independently

---

## Notes

- [P] tasks = different files, no dependencies - can run in parallel
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Tests MUST be written first and FAIL before implementing (TDD principle per Constitution)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- User creation MUST complete in <3 seconds (SC-004) regardless of email status
- Email delivery MUST be non-blocking with fire-and-forget pattern
- RBAC MUST be enforced: Only ADMIN users can configure settings
- All audit records automatically deleted after 30 days
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence

---

## Success Criteria Tracking

Map tasks to success criteria from spec.md:

- **SC-001** (Config in <30 sec): US1 tasks (T011-T028)
- **SC-002** (Email within 2 min): US2/US3 tasks (T029-T051), verify in T085
- **SC-003** (99% delivery): US5 tasks (T063-T071), monitored via audit logs
- **SC-004** (User creation <3 sec): US2 tasks (T034, T042), verify in T084
- **SC-005** (100% formatting): US4 tasks (T052-T062)
- **SC-006** (Zero failures): US2 tasks (T034, T042), verify in T090
- **SC-007** (Admin awareness): Qualitative - gather feedback post-deployment

---

## Total Task Count: 90 tasks

**By Phase**:
- Setup (Phase 1): 4 tasks
- Foundational (Phase 2): 6 tasks
- User Story 1 (Phase 3): 18 tasks
- User Story 2 (Phase 4): 15 tasks
- User Story 3 (Phase 5): 8 tasks
- User Story 4 (Phase 6): 11 tasks
- User Story 5 (Phase 7): 9 tasks
- Audit Cleanup (Phase 8): 5 tasks
- Polish (Phase 9): 14 tasks

**By User Story**:
- US1 (P1): 18 tasks (Configuration UI)
- US2 (P1): 15 tasks (Manual user creation notifications)
- US3 (P2): 8 tasks (OAuth notifications)
- US4 (P2): 11 tasks (Professional formatting)
- US5 (P3): 9 tasks (Audit monitoring)
- Setup/Foundation/Cross-cutting: 29 tasks

**Parallel Opportunities**: 46 tasks marked [P] can run in parallel (51% of total)

**Suggested MVP Scope**: Phase 1-4 (Setup + Foundational + US1 + US2) = 43 tasks
