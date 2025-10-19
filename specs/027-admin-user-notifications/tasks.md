# Tasks: Admin User Notification System

**Input**: Design documents from `/specs/027-admin-user-notifications/`
**Prerequisites**: plan.md (tech stack), spec.md (user stories), data-model.md (entities), contracts/ (API specs)

**Important**: NO TESTS - Implementation only. Secman has working email infrastructure (EmailService, EmailConfig table).

**Organization**: Tasks are grouped by user story to enable independent implementation.

## Path Conventions
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`

---

## Phase 1: Setup & Context

**Purpose**: Understand existing infrastructure, no new dependencies needed

- [ ] T001 Review existing EmailService in src/backendng/src/main/kotlin/com/secman/service/EmailService.kt
- [ ] T002 Verify EmailConfig table structure (emails_configs) - contains SMTP settings
- [ ] T003 Verify EmailNotificationLog table structure (email_notification_logs) - for audit trail
- [ ] T004 Understand existing User entity and roles (ADMIN role already exists)

---

## Phase 2: Core Implementation - User Story 1 (P1): Configuration Toggle

**Goal**: Allow ADMIN users to enable/disable email notifications for new users

### Backend Implementation

- [ ] T005 Create NotificationPreference.kt enum in src/backendng/src/main/kotlin/com/secman/domain/ (or use feature flag)
- [ ] T006 Create AdminNotificationConfig.kt data class in src/backendng/src/main/kotlin/com/secman/dto/ for request/response
- [ ] T007 Create AdminNotificationController.kt in src/backendng/src/main/kotlin/com/secman/controller/ with:
  - GET /api/admin/notification-settings (get current toggle state)
  - PUT /api/admin/notification-settings (update toggle state)
- [ ] T008 Add @Secured(SecurityRule.IS_AUTHENTICATED) and ADMIN role checks to controller endpoints
- [ ] T009 Create AdminNotificationService.kt in src/backendng/src/main/kotlin/com/secman/service/ to manage toggle state
- [ ] T010 Implement caching for notification toggle preference (in-memory cache)

### Frontend Implementation

- [ ] T011 Create AdminNotificationSettings.tsx React component in src/frontend/src/components/
- [ ] T012 Create adminNotificationSettingsService.ts in src/frontend/src/services/ (Axios API client)
- [ ] T013 Update src/frontend/src/pages/admin/settings.astro to include AdminNotificationSettings component
- [ ] T014 Add form controls for toggle (enable/disable notifications)
- [ ] T015 Add loading states and success/error messages

---

## Phase 3: Core Implementation - User Story 2 (P1): Manual User Creation Notifications

**Goal**: Send email to all ADMIN users when user created via "Manage Users" UI

### Backend Implementation

- [ ] T016 Locate UserService.createUser() method in src/backendng/src/main/kotlin/com/secman/service/UserService.kt
- [ ] T017 Create AdminNotificationService.sendNewUserNotification() method:
  - Check if notifications enabled (use AdminNotificationService.isNotificationEnabled())
  - Query all ADMIN users from repository
  - For each ADMIN user with valid email:
    - Generate HTML email (username, email, created-by, timestamp)
    - Call EmailService.sendNotificationEmail() to send to ADMIN
    - Handle failures gracefully (log but don't fail user creation)
- [ ] T018 Add non-blocking async call to sendNewUserNotification() in UserService.createUser():
  - Use @Async or CompletableFuture to make it fire-and-forget
  - Ensure user creation never fails due to email errors
- [ ] T019 Create AdminNotificationEmailGenerator.kt with HTML template for new user notifications
- [ ] T020 Ensure UserRepository has method to query all users with ADMIN role

---

## Phase 4: Core Implementation - User Story 3 (P2): OAuth Registration Notifications

**Goal**: Send email to all ADMIN users when user registers via OAuth

### Backend Implementation

- [ ] T021 Locate OAuth registration completion handler (likely in OAuthController.kt or similar)
- [ ] T022 Add call to AdminNotificationService.sendNewUserNotification() when OAuth registration completes
- [ ] T023 Update email template to indicate OAuth provider (GitHub, Google, etc.)
- [ ] T024 Ensure registration method is correctly identified (OAuth vs Manual) in notification

---

## Phase 5: Email Content & Formatting

**Goal**: Ensure notifications are professional and contain all required information

### Backend Implementation

- [ ] T025 Update AdminNotificationEmailGenerator to include:
  - Professional HTML header with branding
  - Username, email, registration timestamp
  - Registration method (Manual / OAuth provider name)
  - For manual: Name of admin who created the user
  - Call-to-action or summary info
  - Professional footer with branding
- [ ] T026 Verify email subject line format: "New User Registered: [username]"
- [ ] T027 Test email rendering in different email clients (Outlook, Gmail, etc.)

---

## Phase 6: Admin User Retrieval & Email Logic

**Goal**: Ensure notifications reach all appropriate ADMIN users

### Backend Implementation

- [ ] T028 Create method in UserRepository to findAllByRoleIn(['ADMIN']):
  - Return all users with ADMIN role
  - Include email validation (skip users with null/empty email)
- [ ] T029 Implement email validation in AdminNotificationService:
  - Skip ADMIN users with invalid/empty email addresses
  - Log warning but continue with other recipients
  - Never fail notification delivery due to missing admin email
- [ ] T030 Ensure no duplicate notifications sent to same admin
- [ ] T031 Log all email send attempts (success/failure) to EmailNotificationLog

---

## Phase 7: Feature Flag/Configuration Storage

**Goal**: Persist the enable/disable toggle state

### Backend Implementation

- [ ] T032 Decide storage mechanism:
  - Option A: Database table `admin_notification_settings` with boolean field
  - Option B: Feature flag service (if exists)
  - Option C: In-memory only with default enabled
- [ ] T033 Implement AdminNotificationService.isNotificationEnabled():
  - Retrieve current toggle state
  - Return true by default (opt-out model)
  - Cache result for performance
- [ ] T034 Implement cache invalidation when toggle changed

---

## Phase 8: Edge Cases & Error Handling

**Purpose**: Handle failure scenarios gracefully

- [ ] T035 Handle case: No ADMIN users in system (log warning, don't crash)
- [ ] T036 Handle case: ADMIN user email invalid (skip that user, continue)
- [ ] T037 Handle case: Email configuration not active (log error, user creation still succeeds)
- [ ] T038 Handle case: EmailService fails (log error, user creation still succeeds - non-blocking)
- [ ] T039 Handle case: Settings retrieved before database ready (use safe default)
- [ ] T040 Add comprehensive logging at each step for troubleshooting

---

## Phase 9: Integration & Validation

**Purpose**: Ensure feature works end-to-end

- [ ] T041 Verify flow: Admin enables notifications → Create user → Admins receive email
- [ ] T042 Verify flow: Admin disables notifications → Create user → No emails sent
- [ ] T043 Verify flow: Manual user creation → All ADMIN users get email
- [ ] T044 Verify flow: OAuth registration → All ADMIN users get email
- [ ] T045 Verify flow: Multiple ADMIN users → All receive identical emails
- [ ] T046 Verify flow: Email config changes → Notifications still work
- [ ] T047 Verify UI: Admin can toggle notifications on/off
- [ ] T048 Verify UI: Settings persist across page refresh
- [ ] T049 Verify logs: All email attempts logged to email_notification_logs table
- [ ] T050 Verify performance: User creation completes in <3 seconds (non-blocking)

---

## Phase 10: Documentation & Deployment

**Purpose**: Document feature and prepare for production

- [ ] T051 Update README.md with feature documentation
- [ ] T052 Update CLAUDE.md with new controllers, services, endpoints
- [ ] T053 Document API endpoints in OpenAPI/Swagger
- [ ] T054 Verify backend builds successfully: ./gradlew build
- [ ] T055 Verify frontend builds successfully: npm run build
- [ ] T056 Verify no lint errors: npm run lint
- [ ] T057 Create database migration if needed for notification settings table
- [ ] T058 Test with actual email service (not just logging)

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2 Only)

1. **Phase 1**: Understand existing infrastructure (T001-T004)
2. **Phase 2**: Implement configuration toggle UI (T005-T015)
3. **Phase 3**: Hook into user creation, send notifications (T016-T020)
4. **Phase 4-5**: Email content and formatting (T025-T027)
5. **Phase 6-8**: User retrieval, edge cases, error handling (T028-T040)
6. **Phase 9-10**: Validation and documentation (T041-T058)

**Estimated Time**: 3-5 days for MVP (US1 + US2)

### Incremental Additions

- Phase 4: OAuth notifications (+1 day) - US3
- Documentation and polish (+1-2 days) - Phase 10

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1**: No dependencies - start immediately (understand existing code)
- **Phase 2**: Depends on Phase 1 completion - implementation can begin
- **Phase 3+**: All depend on Phase 2 completion

### Within Each Phase

- Backend implementation before frontend
- Services before controllers
- Core logic before edge cases
- Validation last

### Parallel Opportunities

- Backend and frontend can be developed in parallel after Phase 1
- Multiple backend services can be implemented in parallel if needed

---

## Key Technical Notes

- **No retries**: EmailService supports retries but user wants single attempt only
  - Modify EmailService call to use maxAttempts=1 (or skip retry wrapper)
  - Or use sendEmail() instead of sendNotificationEmail() if available

- **Non-blocking delivery**: User creation MUST NOT fail due to email errors
  - Use @Async annotation or CompletableFuture
  - Wrap in try-catch to prevent exception propagation
  - Log failures for audit trail

- **Email configuration**: Use existing EmailConfig table (emails_configs)
  - No new SMTP config needed
  - EmailService already retrieves active config
  - Notifications use active email configuration

- **ADMIN user discovery**: Query existing User table for role = 'ADMIN'
  - No new tables needed
  - Filter out users with null/empty email
  - Handle gracefully if no admins found

---

## Total Tasks: 58

All tasks are implementation-only. No tests. Uses existing EmailService and EmailConfig infrastructure.
