# Tasks: Outdated Asset Notification System

**Input**: Design documents from `/specs/035-notification-system/`
**Prerequisites**: plan.md, spec.md, data-model.md, contracts/, research.md, quickstart.md

**Tests**: Tests are REQUIRED per TDD principle (Principle II). All test tasks are included and must be completed BEFORE implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Backend Tests**: `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Frontend Tests**: `src/frontend/tests/`
- **CLI**: `src/cli/src/main/kotlin/com/secman/cli/`
- **Email Templates**: `src/backendng/src/main/resources/email-templates/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and configuration for email notification system

- [X] T001 Configure SMTP settings in src/backendng/src/main/resources/application.yml (mail.smtp.host, port, auth, starttls)
- [X] T002 [P] Add JavaMail and Thymeleaf dependencies to src/backendng/build.gradle.kts
- [X] T003 [P] Add Playwright E2E test framework to src/frontend/package.json
- [X] T004 Create email templates directory structure: src/backendng/src/main/resources/email-templates/

**Checkpoint**: Basic infrastructure configured - ready for foundational implementation

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core entities, repositories, and services that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Domain Layer (JPA Entities)

- [X] T005 [P] Create NotificationType enum in src/backendng/src/main/kotlin/com/secman/domain/NotificationType.kt (OUTDATED_LEVEL1, OUTDATED_LEVEL2, NEW_VULNERABILITY)
- [X] T006 [P] Create NotificationPreference entity in src/backendng/src/main/kotlin/com/secman/domain/NotificationPreference.kt
- [X] T007 [P] Create AssetReminderState entity in src/backendng/src/main/kotlin/com/secman/domain/AssetReminderState.kt
- [X] T008 [P] Create NotificationLog entity in src/backendng/src/main/kotlin/com/secman/domain/NotificationLog.kt

### Repository Layer

- [X] T009 [P] Create NotificationPreferenceRepository in src/backendng/src/main/kotlin/com/secman/repository/NotificationPreferenceRepository.kt (findByUserId)
- [X] T010 [P] Create AssetReminderStateRepository in src/backendng/src/main/kotlin/com/secman/repository/AssetReminderStateRepository.kt (findByAssetId)
- [X] T011 [P] Create NotificationLogRepository in src/backendng/src/main/kotlin/com/secman/repository/NotificationLogRepository.kt (findBySentAtBetween, findByNotificationType)

### Core Services (Shared Across User Stories)

- [X] T012 Create EmailConfig in src/backendng/src/main/kotlin/com/secman/config/EmailConfig.kt (JavaMail Session configuration)
- [X] T013 Create EmailTemplateService in src/backendng/src/main/kotlin/com/secman/service/EmailTemplateService.kt (Thymeleaf rendering)
- [X] T014 Create ReminderStateService in src/backendng/src/main/kotlin/com/secman/service/ReminderStateService.kt (track reminder levels 1→2)
- [X] T015 Create NotificationLogService in src/backendng/src/main/kotlin/com/secman/service/NotificationLogService.kt (audit trail creation)

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - ADMIN Triggers Notification Run (Priority: P1) 🎯 MVP

**Goal**: Enable ADMIN to run CLI command that sends two-level email reminders for outdated assets, with email aggregation by owner

**Independent Test**: Run CLI command with test outdated assets (mix of new and 7+ days old), verify correct reminder levels sent, verify one email per owner

### Tests for User Story 1 (TDD - Write FIRST, ensure FAIL)

**Contract Tests**:
- [ ] T016 [P] [US1] Write contract test for CLI command execution in src/cli/src/test/kotlin/com/secman/cli/commands/SendNotificationsCommandTest.kt (verify exit codes, dry-run behavior)

**Unit Tests**:
- [ ] T017 [P] [US1] Write unit test for NotificationService email aggregation logic in src/backendng/src/test/kotlin/com/secman/service/NotificationServiceTest.kt
- [ ] T018 [P] [US1] Write unit test for ReminderStateService level escalation in src/backendng/src/test/kotlin/com/secman/service/ReminderStateServiceTest.kt
- [ ] T019 [P] [US1] Write unit test for EmailTemplateService template rendering in src/backendng/src/test/kotlin/com/secman/service/EmailTemplateServiceTest.kt

**Integration Tests**:
- [ ] T020 [P] [US1] Write integration test for email sending end-to-end in src/backendng/src/test/kotlin/com/secman/integration/EmailSendingIntegrationTest.kt (use mock SMTP)
- [ ] T021 [P] [US1] Write integration test for full notification workflow in src/backendng/src/test/kotlin/com/secman/integration/NotificationServiceIntegrationTest.kt (query OutdatedAssetMaterializedView, resolve emails, send)

**Run all tests above - they MUST FAIL before proceeding to implementation**

### Implementation for User Story 1

**Email Templates**:
- [ ] T022 [P] [US1] Create Level 1 reminder HTML template in src/backendng/src/main/resources/email-templates/outdated-reminder-level1.html (professional tone, severity badges, asset table)
- [ ] T023 [P] [US1] Create Level 1 reminder plain-text template in src/backendng/src/main/resources/email-templates/outdated-reminder-level1.txt
- [ ] T024 [P] [US1] Create Level 2 reminder HTML template in src/backendng/src/main/resources/email-templates/outdated-reminder-level2.html (urgent tone, red header, escalation notice)
- [ ] T025 [P] [US1] Create Level 2 reminder plain-text template in src/backendng/src/main/resources/email-templates/outdated-reminder-level2.txt

**Core Notification Service**:
- [ ] T026 [US1] Implement NotificationService.processOutdatedAssets() in src/backendng/src/main/kotlin/com/secman/service/NotificationService.kt (query OutdatedAssetMaterializedView, join UserMapping, aggregate by email, send)
- [ ] T027 [US1] Add email address resolution logic to NotificationService (Asset.owner → UserMapping.awsAccountId → email)
- [ ] T028 [US1] Add email aggregation logic to NotificationService (group assets by owner email, generate single EmailContext)
- [ ] T029 [US1] Add reminder level determination logic to NotificationService (check AssetReminderState, escalate if 7+ days)
- [ ] T030 [US1] Add duplicate prevention logic to NotificationService (check lastSentAt.date == today, skip if already sent)

**CLI Command**:
- [ ] T031 [US1] Create SendNotificationsCommand in src/cli/src/main/kotlin/com/secman/cli/commands/SendNotificationsCommand.kt (Picocli command with --dry-run, --verbose flags)
- [ ] T032 [US1] Create NotificationCliService in src/cli/src/main/kotlin/com/secman/cli/services/NotificationCliService.kt (CLI-specific orchestration, progress logging)
- [ ] T033 [US1] Add --dry-run implementation to SendNotificationsCommand (report planned emails without sending)
- [ ] T034 [US1] Add --verbose implementation to SendNotificationsCommand (detailed logging per asset)
- [ ] T035 [US1] Add summary statistics output to SendNotificationsCommand (emails sent, failures, assets processed)

**Error Handling & Edge Cases**:
- [ ] T036 [US1] Add SMTP failure handling to NotificationService (log errors, mark as FAILED in NotificationLog, continue processing)
- [ ] T037 [US1] Add missing UserMapping handling to NotificationService (log warning, skip asset)
- [ ] T038 [US1] Add null Asset.owner handling to NotificationService (log warning, skip asset)
- [ ] T039 [US1] Add state reset logic to NotificationService (delete AssetReminderState when asset becomes up-to-date)

**Verification**:
- [ ] T040 [US1] Run all User Story 1 tests - they MUST PASS
- [ ] T041 [US1] Manual test: Run CLI command with test data (5 outdated assets, 2 owners), verify aggregated emails sent
- [ ] T042 [US1] Manual test: Run CLI command twice in same day, verify no duplicate emails sent

**Checkpoint**: User Story 1 (MVP) is complete and independently testable. CLI command sends two-level reminders for outdated assets.

---

## Phase 4: User Story 2 - Asset Owner Configures Notification Preferences (Priority: P2)

**Goal**: Enable users to configure whether they want new vulnerability notifications via web UI, with preference storage and retrieval

**Independent Test**: Log in as user, toggle preference on/off, import new vulnerabilities for user's assets, verify emails sent/suppressed based on preference

### Tests for User Story 2 (TDD - Write FIRST, ensure FAIL)

**Contract Tests**:
- [ ] T043 [P] [US2] Write contract test for GET /api/notification-preferences in src/backendng/src/test/kotlin/com/secman/contract/NotificationPreferenceContractTest.kt (verify 200 response, default values)
- [ ] T044 [P] [US2] Write contract test for PUT /api/notification-preferences in src/backendng/src/test/kotlin/com/secman/contract/NotificationPreferenceContractTest.kt (verify 200 response, preference update)
- [ ] T045 [P] [US2] Write contract test for authentication requirement in src/backendng/src/test/kotlin/com/secman/contract/NotificationPreferenceContractTest.kt (verify 401 when not authenticated)

**E2E Tests**:
- [ ] T046 [P] [US2] Write Playwright E2E test for preference toggle in src/frontend/tests/notification-preferences.spec.ts (log in, navigate, toggle, verify save)

**Unit Tests**:
- [ ] T047 [P] [US2] Write unit test for new vulnerability detection logic in src/backendng/src/test/kotlin/com/secman/service/NotificationServiceTest.kt (query vulnerabilities since lastVulnNotificationSentAt)
- [ ] T048 [P] [US2] Write unit test for preference-based email filtering in src/backendng/src/test/kotlin/com/secman/service/NotificationServiceTest.kt (skip if enableNewVulnNotifications=false)

**Run all tests above - they MUST FAIL before proceeding to implementation**

### Implementation for User Story 2

**Backend API**:
- [ ] T049 [P] [US2] Create NotificationPreferenceController in src/backendng/src/main/kotlin/com/secman/controller/NotificationPreferenceController.kt (@Secured IS_AUTHENTICATED)
- [ ] T050 [P] [US2] Implement GET /api/notification-preferences endpoint in NotificationPreferenceController (return user's preferences or defaults)
- [ ] T051 [P] [US2] Implement PUT /api/notification-preferences endpoint in NotificationPreferenceController (update enableNewVulnNotifications)

**Frontend UI**:
- [ ] T052 [P] [US2] Create notificationService.ts API client in src/frontend/src/services/notificationService.ts (Axios calls for GET/PUT preferences)
- [ ] T053 [US2] Create NotificationPreferences React component in src/frontend/src/components/NotificationPreferences.tsx (toggle switch, save button, loading states)
- [ ] T054 [US2] Create notification-preferences.astro page in src/frontend/src/pages/notification-preferences.astro (render NotificationPreferences component)
- [ ] T055 [US2] Add navigation link to preferences page in frontend menu/header

**New Vulnerability Notification Logic**:
- [ ] T056 [P] [US2] Create new-vulnerabilities.html template in src/backendng/src/main/resources/email-templates/new-vulnerabilities.html (informational tone, blue header, vulnerability table)
- [ ] T057 [P] [US2] Create new-vulnerabilities.txt template in src/backendng/src/main/resources/email-templates/new-vulnerabilities.txt
- [ ] T058 [US2] Implement NotificationService.processNewVulnerabilities() in src/backendng/src/main/kotlin/com/secman/service/NotificationService.kt (query NotificationPreference, find new vulns, aggregate, send)
- [ ] T059 [US2] Add preference check to NotificationService (only send if enableNewVulnNotifications=true)
- [ ] T060 [US2] Add lastVulnNotificationSentAt update logic to NotificationService (update timestamp after sending)
- [ ] T061 [US2] Integrate processNewVulnerabilities() into SendNotificationsCommand (call after processOutdatedAssets)

**Verification**:
- [ ] T062 [US2] Run all User Story 2 tests - they MUST PASS
- [ ] T063 [US2] Manual test: Log in as user, enable preference, import new vulnerability, verify email sent
- [ ] T064 [US2] Manual test: Log in as user, disable preference, import new vulnerability, verify NO email sent

**Checkpoint**: User Story 2 is complete and independently testable. Users can configure new vulnerability notifications.

---

## Phase 5: User Story 3 - ADMIN Reviews Notification Audit Logs (Priority: P3)

**Goal**: Enable ADMIN users to view, filter, and export notification audit logs via web UI for compliance and troubleshooting

**Independent Test**: Run notification command to generate logs, log in as ADMIN, view logs with filters (date range, type, status), export to CSV, verify all data present

### Tests for User Story 3 (TDD - Write FIRST, ensure FAIL)

**Contract Tests**:
- [ ] T065 [P] [US3] Write contract test for GET /api/notification-logs in src/backendng/src/test/kotlin/com/secman/contract/NotificationLogContractTest.kt (verify 200 response, pagination, filters)
- [ ] T066 [P] [US3] Write contract test for GET /api/notification-logs/export in src/backendng/src/test/kotlin/com/secman/contract/NotificationLogContractTest.kt (verify CSV format, Content-Disposition header)
- [ ] T067 [P] [US3] Write contract test for ADMIN-only access in src/backendng/src/test/kotlin/com/secman/contract/NotificationLogContractTest.kt (verify 403 for non-ADMIN users)

**E2E Tests**:
- [ ] T068 [P] [US3] Write Playwright E2E test for log viewing in src/frontend/tests/notification-logs.spec.ts (log in as ADMIN, view logs, apply filters)
- [ ] T069 [P] [US3] Write Playwright E2E test for CSV export in src/frontend/tests/notification-logs.spec.ts (click export, verify download)

**Unit Tests**:
- [ ] T070 [P] [US3] Write unit test for log filtering logic in src/backendng/src/test/kotlin/com/secman/service/NotificationLogServiceTest.kt (filter by date range, type, status)

**Run all tests above - they MUST FAIL before proceeding to implementation**

### Implementation for User Story 3

**Backend API**:
- [ ] T071 [P] [US3] Create NotificationLogController in src/backendng/src/main/kotlin/com/secman/controller/NotificationLogController.kt (@Secured ADMIN)
- [ ] T072 [US3] Implement GET /api/notification-logs endpoint in NotificationLogController (pagination, sorting, filtering by notificationType/status/ownerEmail/dateRange)
- [ ] T073 [US3] Implement GET /api/notification-logs/export endpoint in NotificationLogController (CSV generation, Content-Disposition header)
- [ ] T074 [US3] Add pagination support to NotificationLogRepository (Pageable parameter, Page<NotificationLog> return type)
- [ ] T075 [US3] Add filtering support to NotificationLogRepository (Specification API or custom query methods)

**Frontend UI**:
- [ ] T076 [P] [US3] Add notificationLog API methods to notificationService.ts in src/frontend/src/services/notificationService.ts (listLogs, exportLogs)
- [ ] T077 [US3] Create NotificationLogTable React component in src/frontend/src/components/NotificationLogTable.tsx (table, pagination, filters, export button)
- [ ] T078 [US3] Create notification-logs.astro page in src/frontend/src/pages/admin/notification-logs.astro (render NotificationLogTable, ADMIN-only route)
- [ ] T079 [US3] Add navigation link to audit logs in ADMIN menu

**Filtering & Export**:
- [ ] T080 [US3] Add date range filter UI to NotificationLogTable (start/end date pickers)
- [ ] T081 [US3] Add notification type filter UI to NotificationLogTable (dropdown: OUTDATED_LEVEL1/2, NEW_VULNERABILITY)
- [ ] T082 [US3] Add status filter UI to NotificationLogTable (dropdown: SENT, FAILED, PENDING)
- [ ] T083 [US3] Add owner email search to NotificationLogTable (text input with debounce)
- [ ] T084 [US3] Implement CSV export handler in NotificationLogTable (trigger download on button click)

**Verification**:
- [ ] T085 [US3] Run all User Story 3 tests - they MUST PASS
- [ ] T086 [US3] Manual test: Log in as ADMIN, view logs, verify all columns present (timestamp, email, asset, type, status)
- [ ] T087 [US3] Manual test: Apply date range filter, verify only logs in range displayed
- [ ] T088 [US3] Manual test: Export logs to CSV, verify all data present and correctly formatted

**Checkpoint**: User Story 3 is complete and independently testable. ADMIN can view and export notification audit logs.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories, final testing, documentation

- [ ] T089 [P] Add comprehensive error messages to all notification operations (user-friendly messages in frontend, detailed logs in backend)
- [ ] T090 [P] Add rate limiting to notification API endpoints to prevent abuse (Micronaut @RateLimit annotation)
- [ ] T091 [P] Optimize OutdatedAssetMaterializedView query for 10,000+ assets (add missing indexes, analyze query plan)
- [ ] T092 [P] Add performance monitoring to NotificationService (log execution time, asset count, email count)
- [ ] T093 [P] Update CLAUDE.md with new entities, endpoints, and CLI command usage
- [ ] T094 Run quickstart.md validation workflow (verify all setup steps work, test scenarios pass)
- [ ] T095 [P] Add email deliverability checks (SPF/DKIM/DMARC validation for SMTP server)
- [ ] T096 [P] Add security hardening: input sanitization for email addresses, SQL injection prevention in custom queries
- [ ] T097 Code cleanup and refactoring (remove dead code, improve naming, add KDoc comments)
- [ ] T098 Performance testing: Run CLI command with 10,000 outdated assets, verify <2 minute completion time
- [ ] T099 Run full test suite (all contract, unit, integration, E2E tests) and verify ≥80% coverage

**Checkpoint**: All user stories complete, polished, and production-ready

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational phase completion - No dependencies on other stories
- **User Story 2 (Phase 4)**: Depends on Foundational phase completion - Integrates with US1 (uses same NotificationService) but independently testable
- **User Story 3 (Phase 5)**: Depends on Foundational phase completion - Reads NotificationLog created by US1/US2 but independently testable
- **Polish (Phase 6)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories ✅ MVP
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - Integrates with NotificationService from US1 but independently testable
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - Reads NotificationLog entities but independently testable

**Recommendation**: Complete US1 first (MVP), then US2 and US3 can be developed in parallel if team capacity allows.

### Within Each User Story

- **TDD Order**: Tests MUST be written and FAIL before implementation
- **Entity Order**: Entities → Repositories → Services → Controllers/UI
- **Template Order**: Email templates before service implementation that uses them
- **Integration Order**: Core implementation before error handling and edge cases

### Parallel Opportunities

**Setup Phase**:
- T002, T003 (dependency management) can run in parallel

**Foundational Phase**:
- T005, T006, T007, T008 (all entities) can run in parallel
- T009, T010, T011 (all repositories) can run in parallel after entities complete

**User Story 1**:
- All test tasks (T016-T021) can run in parallel
- All email template tasks (T022-T025) can run in parallel
- T036, T037, T038, T039 (error handling) can run in parallel

**User Story 2**:
- All test tasks (T043-T048) can run in parallel
- T049, T052 (controller and API client) can run in parallel
- T056, T057 (email templates) can run in parallel

**User Story 3**:
- All test tasks (T065-T070) can run in parallel
- T071, T076 (controller and API client) can run in parallel

**Polish Phase**:
- T089, T090, T091, T092, T093, T095, T096 can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together (must FAIL initially):
Task: "Write contract test for CLI command execution in SendNotificationsCommandTest.kt"
Task: "Write unit test for NotificationService email aggregation logic"
Task: "Write unit test for ReminderStateService level escalation"
Task: "Write unit test for EmailTemplateService template rendering"
Task: "Write integration test for email sending end-to-end"
Task: "Write integration test for full notification workflow"

# Launch all email templates together:
Task: "Create Level 1 reminder HTML template in outdated-reminder-level1.html"
Task: "Create Level 1 reminder plain-text template in outdated-reminder-level1.txt"
Task: "Create Level 2 reminder HTML template in outdated-reminder-level2.html"
Task: "Create Level 2 reminder plain-text template in outdated-reminder-level2.txt"

# Launch all error handling tasks together:
Task: "Add SMTP failure handling to NotificationService"
Task: "Add missing UserMapping handling to NotificationService"
Task: "Add null Asset.owner handling to NotificationService"
Task: "Add state reset logic to NotificationService"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T004)
2. Complete Phase 2: Foundational (T005-T015) - CRITICAL BLOCKER
3. Complete Phase 3: User Story 1 (T016-T042)
4. **STOP and VALIDATE**: Run CLI command with test data, verify emails sent correctly
5. Deploy/demo if ready - Core notification functionality is complete

**Estimated Effort**: ~60% of total project (MVP delivers core value)

### Incremental Delivery

1. **Sprint 1**: Setup + Foundational → Foundation ready (T001-T015)
2. **Sprint 2**: User Story 1 → Test independently → Deploy/Demo (MVP!) (T016-T042)
3. **Sprint 3**: User Story 2 → Test independently → Deploy/Demo (T043-T064)
4. **Sprint 4**: User Story 3 → Test independently → Deploy/Demo (T065-T088)
5. **Sprint 5**: Polish → Production-ready (T089-T099)

Each story adds value without breaking previous stories.

### Parallel Team Strategy

With multiple developers (after Foundational phase complete):

1. **Team completes Setup + Foundational together** (T001-T015)
2. **Once Foundational is done**:
   - Developer A: User Story 1 (CLI + outdated reminders)
   - Developer B: User Story 2 (frontend preferences + new vuln notifications)
   - Developer C: User Story 3 (ADMIN audit log UI)
3. Stories complete and integrate independently

**Note**: US1 should be prioritized (it's the MVP). US2 and US3 can be done in parallel if team size allows.

---

## Task Summary

### Total Tasks: 99

**By Phase**:
- Phase 1 (Setup): 4 tasks
- Phase 2 (Foundational): 11 tasks
- Phase 3 (User Story 1 - MVP): 27 tasks
- Phase 4 (User Story 2): 22 tasks
- Phase 5 (User Story 3): 24 tasks
- Phase 6 (Polish): 11 tasks

**By Type**:
- Tests: 28 tasks (contract, unit, integration, E2E)
- Domain/Repository: 7 tasks
- Services: 15 tasks
- Controllers: 6 tasks
- CLI: 7 tasks
- Frontend: 12 tasks
- Email Templates: 6 tasks
- Configuration: 4 tasks
- Polish/Documentation: 11 tasks
- Verification: 3 tasks

**Parallelizable Tasks**: 51 tasks marked [P]

**Critical Path**: Setup → Foundational → User Story 1 (MVP) = 42 tasks minimum for MVP

---

## Notes

- **[P] tasks**: Different files, no dependencies - can run in parallel
- **[Story] label**: Maps task to specific user story for traceability
- **TDD enforced**: All tests written BEFORE implementation (Principle II)
- **Each user story independently completable and testable**
- **Verify tests fail before implementing** (Red-Green-Refactor)
- **Commit after each task or logical group**
- **Stop at any checkpoint to validate story independently**
- **Performance target**: Process 10,000 assets in <2 minutes (verified in T098)
- **Coverage target**: ≥80% (verified in T099)

**Anti-patterns to avoid**:
- ❌ Implementing before writing tests
- ❌ Creating cross-story dependencies that break independence
- ❌ Working on same file in parallel (not marked [P])
- ❌ Skipping checkpoints (validate each story before moving on)
