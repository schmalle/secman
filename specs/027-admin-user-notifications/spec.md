# Feature Specification: Admin User Notification System

**Feature Branch**: `027-admin-user-notifications`
**Created**: 2025-10-19
**Status**: Draft
**Input**: User description: "when a new user is added via oauth or via the 'Manage users' UI, secman man send a nicely formatted info email to all users with the ADMIN role. Please make this feature configurable in the Admin UI."

## Clarifications

### Session 2025-10-19

- Q: When this feature is first deployed to production, should admin user notifications be enabled or disabled by default? → A: Enabled by default (opt-out) - Notifications start immediately, admins can disable if unwanted
- Q: What should be the maximum number of retry attempts before giving up on a failed notification email? → A: No retries - Single attempt only, fail fast
- Q: What email address should appear in the "From:" field of notification emails sent to admins? → A: Configurable "noreply@" address - e.g., noreply@secman.company.com
- Q: How long should notification audit records be retained before automatic cleanup? → A: 30 days retention - Minimal retention for recent troubleshooting
- Q: Should the system provide both HTML and plain-text versions of notification emails? → A: HTML only - Modern email clients only, simpler implementation
- Q: Should SMTP configuration be in application.yml or database? → A: Database-driven - Use existing EmailConfig table (emails_configs) that already stores SMTP settings, no new configuration needed

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enable/Disable Email Notifications (Priority: P1)

As an administrator, I want to enable or disable email notifications for new user registrations through a configuration setting in the Admin UI, so that I can control whether admins receive these notifications without requiring code changes.

**Why this priority**: Core configuration capability that controls the entire feature. Without this, admins cannot control notification behavior, making the feature inflexible and potentially spammy.

**Independent Test**: Can be fully tested by accessing the Admin UI, toggling the notification setting, and verifying the setting persists. Delivers immediate value by giving admins control over notification behavior.

**Acceptance Scenarios**:

1. **Given** I am logged in as an ADMIN user, **When** I navigate to the Admin Settings page, **Then** I see clear options to enable/disable new user notification emails and configure the sender email address
2. **Given** the feature is newly deployed and no configuration has been changed, **When** I view the Admin Settings page, **Then** the notification setting shows as enabled by default with a default "noreply@" sender address
3. **Given** notification emails are enabled, **When** I disable them in the Admin UI, **Then** the setting is saved and no notification emails are sent for subsequent new user registrations
4. **Given** notification emails are disabled, **When** I enable them in the Admin UI, **Then** the setting is saved and notification emails resume for new user registrations
5. **Given** I have changed the notification settings (enabled/disabled or sender address), **When** I refresh the page, **Then** the settings reflect my most recent choices
6. **Given** I configure a custom sender address, **When** a notification email is sent, **Then** the email's From: field uses my configured address

---

### User Story 2 - Receive Notifications for Manual User Creation (Priority: P1)

As an administrator, I want to receive an email notification when a new user is created through the "Manage Users" UI, so that I am aware of all new users added to the system and can verify they should have access.

**Why this priority**: Essential for security awareness and audit trail. Manual user creation is a deliberate administrative action that all admins should be aware of.

**Independent Test**: Can be fully tested by creating a new user via the "Manage Users" UI and verifying all ADMIN users receive a properly formatted email. Delivers immediate security value by ensuring admin awareness of manual account creation.

**Acceptance Scenarios**:

1. **Given** email notifications are enabled and I have the ADMIN role, **When** any user creates a new account through the "Manage Users" UI, **Then** I receive an email notification with user details
2. **Given** there are 3 users with ADMIN role, **When** a new user is created manually, **Then** all 3 admin users receive the notification email
3. **Given** a new user is created with username "john.doe" and email "john.doe@example.com", **When** the notification email is sent, **Then** it includes these details in a clear, readable format
4. **Given** a user without ADMIN role exists, **When** a new user is created, **Then** non-admin users do NOT receive notification emails

---

### User Story 3 - Receive Notifications for OAuth Registration (Priority: P2)

As an administrator, I want to receive an email notification when a new user registers via OAuth (e.g., GitHub, Google), so that I am aware of all new users entering the system through external authentication providers.

**Why this priority**: Important for security monitoring of OAuth-based registrations. While slightly lower priority than manual creation (which is more controlled), it's still critical for complete visibility into user onboarding.

**Independent Test**: Can be fully tested by completing an OAuth registration flow and verifying all ADMIN users receive the notification. Delivers value by extending admin awareness to OAuth-based user creation.

**Acceptance Scenarios**:

1. **Given** email notifications are enabled and I have the ADMIN role, **When** a new user completes OAuth registration, **Then** I receive an email notification with OAuth provider details and user information
2. **Given** a user registers via GitHub OAuth, **When** the notification is sent, **Then** the email clearly indicates the authentication provider was OAuth/GitHub
3. **Given** a user registers via OAuth with username "jane.smith", **When** the notification email is sent, **Then** it includes the username, email, and OAuth provider in a readable format

---

### User Story 4 - Professional Email Formatting (Priority: P2)

As an administrator receiving new user notifications, I want the emails to be professionally formatted with clear structure and relevant details, so that I can quickly understand who joined, how they joined, and when.

**Why this priority**: Ensures notifications are actionable and professional. Well-formatted emails improve admin efficiency and make the feature feel polished rather than an afterthought.

**Independent Test**: Can be fully tested by triggering a notification and reviewing the email content for clarity, formatting, and completeness. Delivers value by making notifications immediately useful and easy to scan.

**Acceptance Scenarios**:

1. **Given** a notification email is sent, **When** I open the email, **Then** it has a clear subject line like "New User Registered: [username]"
2. **Given** a notification email is sent, **When** I read the email body, **Then** it includes: username, email address, registration method (Manual/OAuth provider), timestamp, and who created the account (for manual creation)
3. **Given** a notification email is sent, **When** I view the email, **Then** it uses proper HTML formatting with headings, tables or lists for structured data, and a professional email template
4. **Given** multiple admins receive the notification, **When** we compare our emails, **Then** all emails are identical in content and formatting

---

### User Story 5 - Email Delivery Monitoring (Priority: P3)

As an administrator, I want notification email failures to be logged clearly so that I can monitor delivery issues and take corrective action if needed.

**Why this priority**: Nice-to-have for production observability. While important, the core feature works without this - it provides visibility into delivery problems for operational awareness.

**Independent Test**: Can be fully tested by simulating email server failures and verifying failures are logged with appropriate details. Delivers value by making email delivery problems visible to operators.

**Acceptance Scenarios**:

1. **Given** the email server is temporarily unavailable, **When** a new user is created, **Then** the notification attempt fails and the failure is logged with error details
2. **Given** an email fails to send, **When** the failure is logged, **Then** the log entry includes: timestamp, recipient email, new user details, and failure reason
3. **Given** multiple email failures occur, **When** reviewing logs, **Then** system administrators can identify patterns and investigate the root cause

---

### Edge Cases

- What happens when no users have the ADMIN role (system misconfiguration)?
  - System should log a warning but not crash or block user creation
- What happens when an ADMIN user has an invalid/missing email address?
  - System should skip that admin user, log a warning, and continue sending to other admins
- What happens if a user is created while email notifications are being toggled?
  - Use the notification setting that was active at the moment of user creation
- What happens if the same user registers multiple times via OAuth (account linking)?
  - Only send notification on first registration (new account creation), not on subsequent logins
- What happens when the email template fails to render (missing data)?
  - Fall back to a simple HTML email with basic user information (username, email, timestamp)
- What happens when user creation and email sending are part of a transaction?
  - Email sending should be asynchronous/fire-and-forget to avoid blocking user creation if email fails

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow ADMIN users to enable or disable new user notification emails through the Admin UI
- **FR-002**: System MUST persist the notification email preference (enabled/disabled) across server restarts, with notifications enabled by default on initial deployment
- **FR-003**: System MUST send notification emails to ALL users with the ADMIN role when a new user is created, if notifications are enabled
- **FR-004**: System MUST send notifications when users are created through the "Manage Users" UI
- **FR-005**: System MUST send notifications when users are created through OAuth authentication flows
- **FR-006**: System MUST NOT send notification emails to users without the ADMIN role
- **FR-007**: Email notifications MUST include: username, email address, registration timestamp, and registration method (Manual UI / OAuth provider name)
- **FR-008**: Email notifications for manual user creation MUST include the username of the admin who created the account
- **FR-009**: Email notifications MUST use a professional HTML email template with proper formatting (HTML format only, no plain-text alternative required)
- **FR-010**: Email subject line MUST clearly indicate a new user registration (e.g., "New User Registered: [username]")
- **FR-011**: System MUST NOT block or fail user creation if email sending fails
- **FR-012**: System MUST log successful and failed email notification attempts for audit purposes (single attempt only, no retries)
- **FR-013**: System MUST skip sending to ADMIN users who have invalid or missing email addresses, but continue sending to other admins
- **FR-016**: System MUST attempt email delivery once per recipient; failed delivery attempts are logged but not retried
- **FR-014**: System MUST only send notifications for new user creation, not for existing user logins or updates
- **FR-015**: The notification email preference MUST be configurable only by users with the ADMIN role
- **FR-017**: System MUST allow ADMIN users to configure the sender email address (From: field) used for notification emails through the Admin UI
- **FR-018**: Notification emails MUST use the configured sender address; if not configured, system MUST use a default "noreply@" address
- **FR-019**: System MUST automatically delete EmailNotificationEvent records older than 30 days to prevent unbounded database growth
- **FR-020**: SMTP configuration (host, port, auth credentials) MUST be stored in SystemSetting entity and retrieved at runtime, NOT hardcoded in application.yml
- **FR-021**: System MUST support configurable SMTP connection parameters: host, port, authentication, TLS/SSL settings, all retrieved from database

### Key Entities

- **EmailConfig** (existing): Database table `emails_configs` stores SMTP configuration including:
  - SMTP host, port, username, password (encrypted)
  - TLS/SSL settings
  - Sender email address and name
  - Active/inactive status
  - Used directly by EmailService for sending notifications

- **EmailNotificationLog** (existing): Database table `email_notification_logs` logs all notification attempts with:
  - Recipient email, subject, status (sent/failed)
  - Failure reason if delivery failed
  - Timestamps for audit trail
  - Risk assessment ID for linking to originating event

- **Feature-Specific Configuration** (new): A simple toggle (UI setting) to enable/disable notifications for new users:
  - Stored as feature flag or admin UI preference
  - When enabled, triggers email notifications to all ADMIN users
  - Uses existing EmailConfig for sender details

- **User** (existing, extended): Already contains username, email, roles. No structural changes needed, but user creation events will trigger notification logic.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can enable or disable email notifications in under 30 seconds through the Admin UI
- **SC-002**: All ADMIN users receive new user notification emails within 2 minutes of user creation
- **SC-003**: Email notifications are delivered successfully in 99% of attempts under normal conditions
- **SC-004**: User creation completes in under 3 seconds regardless of email sending status (non-blocking)
- **SC-005**: Notification emails are formatted professionally and contain all required information (username, email, method, timestamp) in 100% of cases
- **SC-006**: Zero user creation failures caused by email notification errors
- **SC-007**: Administrators report improved awareness of new user activity (qualitative feedback post-deployment)
