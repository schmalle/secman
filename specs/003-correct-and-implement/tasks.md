# Tasks: Email Functionality Implementation

**Input**: Design documents from `/specs/003-correct-and-implement/`
**Prerequisites**: plan.md (required), research.md, data-model.md, contracts/

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → Tech stack: Kotlin 2.0.21, Micronaut 4.4.3, MariaDB, Astro 5.12.3, React 19.1.0
   → Structure: Web application (backend + frontend)
2. Load design documents:
   → data-model.md: 5 entities identified
   → contracts/: 2 API contracts (email notifications, test accounts)
   → quickstart.md: 5 test scenarios extracted
3. Generate tasks by category:
   → Setup: encryption, dependencies
   → Tests: contract tests, integration tests
   → Core: entities, services, controllers
   → Integration: events, UI fixes
   → Polish: performance, validation
4. Apply task rules:
   → Different files = mark [P] for parallel
   → Tests before implementation (TDD)
5. Number tasks sequentially (T001-T031)
6. Dependencies: Setup → Tests → Models → Services → Controllers → UI → Polish
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Paths use web application structure: `src/backendng/` and `src/frontend/`

## Phase 3.1: Setup & Infrastructure
- [x] T001 Create EncryptedStringConverter for sensitive data encryption in `src/backendng/src/main/kotlin/com/secman/util/EncryptedStringConverter.kt`
- [x] T002 [P] Add encryption configuration properties to `src/backendng/src/main/resources/application.yml`
- [x] T003 [P] Create EmailProvider and TestAccountStatus enums in `src/backendng/src/main/kotlin/com/secman/domain/enums/`

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### Contract Tests
- [x] T004 [P] Contract test GET /api/notifications/configs in `src/backendng/src/test/kotlin/com/secman/controller/NotificationConfigTest.kt`
- [x] T005 [P] Contract test POST /api/notifications/configs in `src/backendng/src/test/kotlin/com/secman/controller/NotificationConfigPostTest.kt`
- [x] T006 [P] Contract test POST /api/notifications/send in `src/backendng/src/test/kotlin/com/secman/controller/ManualNotificationTest.kt`
- [x] T007 [P] Contract test GET /api/notifications/logs in `src/backendng/src/test/kotlin/com/secman/controller/NotificationLogsTest.kt`
- [x] T008 [P] Contract test GET /api/test-email-accounts in `src/backendng/src/test/kotlin/com/secman/controller/TestEmailAccountsGetTest.kt`
- [x] T009 [P] Contract test POST /api/test-email-accounts in `src/backendng/src/test/kotlin/com/secman/controller/TestEmailAccountsPostTest.kt`
- [x] T010 [P] Contract test POST /api/test-email-accounts/{id}/test in `src/backendng/src/test/kotlin/com/secman/controller/TestEmailAccountsTestTest.kt`

### Integration Tests
- [x] T011 [P] Integration test admin UI email config access in `src/frontend/tests/admin-ui-access.spec.ts`
- [x] T012 [P] Integration test email configuration setup in `src/frontend/tests/email-config-setup.spec.ts`
- [x] T013 [P] Integration test automatic risk assessment notifications in `src/frontend/tests/auto-notifications.spec.ts`
- [x] T014 [P] Integration test email encryption verification in `src/frontend/tests/email-encryption.spec.ts`
- [x] T015 [P] Integration test test email account management in `src/frontend/tests/test-accounts.spec.ts`

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### Data Models & Repositories
- [x] T016 [P] TestEmailAccount entity in `src/backendng/src/main/kotlin/com/secman/domain/TestEmailAccount.kt`
- [x] T017 [P] EmailNotificationLog entity in `src/backendng/src/main/kotlin/com/secman/domain/EmailNotificationLog.kt`
- [x] T018 [P] RiskAssessmentNotificationConfig entity in `src/backendng/src/main/kotlin/com/secman/domain/RiskAssessmentNotificationConfig.kt`
- [x] T019 [P] TestEmailAccountRepository in `src/backendng/src/main/kotlin/com/secman/repository/TestEmailAccountRepository.kt`
- [x] T020 [P] EmailNotificationLogRepository in `src/backendng/src/main/kotlin/com/secman/repository/EmailNotificationLogRepository.kt`
- [x] T021 [P] RiskAssessmentNotificationConfigRepository in `src/backendng/src/main/kotlin/com/secman/repository/RiskAssessmentNotificationConfigRepository.kt`

### Events System
- [x] T022 Create RiskAssessmentCreatedEvent in `src/backendng/src/main/kotlin/com/secman/event/RiskAssessmentCreatedEvent.kt`
- [x] T023 Enhance EmailConfig entity with encryption support in existing `src/backendng/src/main/kotlin/com/secman/domain/EmailConfig.kt`
- [x] T024 Create EmailNotificationEventListener in `src/backendng/src/main/kotlin/com/secman/listener/EmailNotificationEventListener.kt`

### Service Layer
- [ ] T025 [P] TestEmailAccountService for test account management in `src/backendng/src/main/kotlin/com/secman/service/TestEmailAccountService.kt`
- [ ] T026 [P] NotificationConfigService for notification configs in `src/backendng/src/main/kotlin/com/secman/service/NotificationConfigService.kt`
- [ ] T027 Enhance EmailService with notification support and retry logic in existing `src/backendng/src/main/kotlin/com/secman/service/EmailService.kt`

### API Controllers
- [ ] T028 [P] NotificationController for notification endpoints in `src/backendng/src/main/kotlin/com/secman/controller/NotificationController.kt`
- [ ] T029 [P] TestEmailAccountController for test account endpoints in `src/backendng/src/main/kotlin/com/secman/controller/TestEmailAccountController.kt`

## Phase 3.4: Integration & Event Flow
- [ ] T030 Add event publishing to RiskAssessmentController in existing `src/backendng/src/main/kotlin/com/secman/controller/RiskAssessmentController.kt`
- [ ] T031 Fix admin route authentication in `src/frontend/src/pages/admin/email-config.astro`
- [ ] T032 [P] Enhance EmailConfigManagement component for improved UX in `src/frontend/src/components/EmailConfigManagement.tsx`
- [ ] T033 [P] Create TestEmailAccountManagement component in `src/frontend/src/components/TestEmailAccountManagement.tsx`
- [ ] T034 [P] Create NotificationConfigManagement component in `src/frontend/src/components/NotificationConfigManagement.tsx`

## Phase 3.5: Polish & Validation
- [ ] T035 [P] Unit tests for encryption functionality in `src/backendng/src/test/kotlin/com/secman/util/EncryptedStringConverterTest.kt`
- [ ] T036 [P] Performance tests for email sending (<30 seconds delivery) in `src/backendng/src/test/kotlin/com/secman/service/EmailServicePerformanceTest.kt`
- [ ] T037 [P] Security tests for credential encryption in `src/backendng/src/test/kotlin/com/secman/security/EmailEncryptionSecurityTest.kt`
- [ ] T038 Execute quickstart validation scenarios as documented in quickstart.md
- [ ] T039 [P] Add database indexes for email notification queries in database migration
- [ ] T040 [P] Add comprehensive error handling and logging for email operations
- [ ] T041 Run linting and formatting checks for all new code

## Dependencies

### Critical Path
```
T001-T003 (Setup)
    ↓
T004-T015 (Tests - MUST FAIL)
    ↓
T016-T021 (Entities) → T022-T024 (Events) → T025-T027 (Services) → T028-T029 (Controllers)
    ↓
T030-T034 (Integration)
    ↓
T035-T041 (Polish)
```

### Parallel Execution Groups
- **Group 1 (Setup)**: T001, T002, T003 can run together
- **Group 2 (Contract Tests)**: T004-T010 can run together
- **Group 3 (Integration Tests)**: T011-T015 can run together
- **Group 4 (Entities)**: T016-T021 can run together (different files)
- **Group 5 (Services)**: T025, T026 can run together (different files)
- **Group 6 (Controllers)**: T028, T029 can run together (different files)
- **Group 7 (UI Components)**: T032, T033, T034 can run together (different files)
- **Group 8 (Polish)**: T035-T037, T039 can run together (different files)

## Parallel Example
```bash
# Launch Group 2 contract tests together:
Task: "Contract test GET /api/notifications/configs in src/backendng/src/test/kotlin/com/secman/controller/NotificationConfigTest.kt"
Task: "Contract test POST /api/notifications/configs in src/backendng/src/test/kotlin/com/secman/controller/NotificationConfigPostTest.kt"
Task: "Contract test POST /api/notifications/send in src/backendng/src/test/kotlin/com/secman/controller/ManualNotificationTest.kt"
Task: "Contract test GET /api/notifications/logs in src/backendng/src/test/kotlin/com/secman/controller/NotificationLogsTest.kt"
```

## Key Implementation Notes

### Security Requirements
- All sensitive fields (SMTP credentials) MUST use EncryptedStringConverter
- JWT authentication required for all admin endpoints
- Input validation on all email addresses and configurations
- No plaintext credentials in logs or database dumps

### TDD Requirements
- ALL contract tests (T004-T010) MUST fail before implementation
- Integration tests (T011-T015) must validate quickstart scenarios
- Tests must be written to match OpenAPI contract specifications exactly

### Event-Driven Architecture
- RiskAssessmentCreatedEvent must be published on new assessments
- EmailNotificationEventListener handles async email sending
- No blocking operations in risk assessment creation flow

### UI/UX Requirements
- Admin email configuration must be accessible (fix authentication)
- Test email accounts isolated from production user management
- Clear error messaging for email delivery failures
- Progress indicators for async email operations

## Validation Checklist
*GATE: Checked before task execution*

- [x] All contracts have corresponding tests (T004-T010)
- [x] All entities have model tasks (T016-T021)
- [x] All tests come before implementation (T004-T015 before T016+)
- [x] Parallel tasks truly independent (different file paths)
- [x] Each task specifies exact file path
- [x] No task modifies same file as another [P] task
- [x] Setup tasks prepare infrastructure (T001-T003)
- [x] Critical path respects dependencies
- [x] Constitutional requirements addressed (security, TDD, API-first)