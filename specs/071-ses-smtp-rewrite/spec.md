# Feature Specification: SES SMTP Rewrite

**Feature Branch**: `071-ses-smtp-rewrite`
**Created**: 2026-01-29
**Status**: Draft
**Input**: User description: "Rewrite the AWS SES Email send code to use SMTP instead of ses:SendRawEmail"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Send Emails via SMTP Instead of SES API (Priority: P1)

As a system administrator, I want the email sending system to use standard SMTP protocol when configured with AWS SES, instead of the AWS SES API (`ses:SendRawEmail`), so that the IAM user only needs SMTP credentials and does not require the `ses:SendRawEmail` IAM permission.

**Why this priority**: This is the core change. The current SES API-based sending fails with `554 Access denied` when the IAM user lacks `ses:SendRawEmail` permission. Switching to SMTP eliminates this IAM dependency entirely, using SMTP credentials (derived from IAM credentials) instead.

**Independent Test**: Can be tested by configuring an AWS SES email configuration in the admin UI and sending a test email. The email should be delivered successfully without requiring `ses:SendRawEmail` IAM permission on the sending user.

**Acceptance Scenarios**:

1. **Given** an AWS SES email configuration is active, **When** the system sends an email, **Then** it uses the SMTP protocol (port 587 with STARTTLS) to connect to the SES SMTP endpoint instead of calling the SES API.
2. **Given** the SES SMTP credentials are correctly configured, **When** a test email is sent, **Then** the email is delivered successfully.
3. **Given** the SES configuration includes a region, **When** the system connects, **Then** it uses the correct regional SMTP endpoint (e.g., `email-smtp.eu-central-1.amazonaws.com` for eu-central-1).
4. **Given** incorrect SMTP credentials, **When** the system attempts to send, **Then** a clear authentication error is returned.

---

### User Story 2 - Backward-Compatible Configuration (Priority: P2)

As a system administrator, I want existing SES configurations stored in the database to continue working after the migration, so that no manual reconfiguration is required beyond providing SMTP credentials.

**Why this priority**: Existing deployments have SES configurations in the database. The migration should reuse as much of the existing configuration as possible (region, from-email, from-name) while adding the new SMTP credential fields.

**Independent Test**: Can be tested by verifying that an existing SES configuration in the database is still recognized and can be updated with SMTP credentials through the admin UI.

**Acceptance Scenarios**:

1. **Given** an existing SES configuration in the database, **When** the system loads it, **Then** the configuration is recognized and the admin can update it with SMTP credentials.
2. **Given** an SES configuration with a region set, **When** the SMTP endpoint is derived, **Then** it maps to `email-smtp.{region}.amazonaws.com`.
3. **Given** the admin updates the SES configuration with SMTP username and password, **When** the configuration is saved, **Then** the credentials are stored encrypted (same as existing credential storage).

---

### Edge Cases

- What happens when the SES SMTP endpoint is unreachable? The system should return a connection error with the endpoint hostname, and retry logic should apply (same as existing retry behavior).
- What happens when the configured region is invalid? The system should report a configuration validation error indicating the region is not recognized.
- What happens when sending fails due to throttling? Standard SMTP error codes should be surfaced, and the retry mechanism should handle transient failures.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST send emails via SMTP protocol when an SES email provider is configured, instead of using the AWS SES API (`ses:SendRawEmail`).
- **FR-002**: The system MUST derive the SES SMTP endpoint from the configured region using the pattern `email-smtp.{region}.amazonaws.com`.
- **FR-003**: The system MUST use port 587 with STARTTLS for SES SMTP connections.
- **FR-004**: The system MUST support SES SMTP credentials (username and password) stored in the email configuration, encrypted at rest.
- **FR-005**: The system MUST reuse existing retry logic, timeout settings, and error handling for SMTP-based SES sending.
- **FR-006**: The system MUST remove the dependency on the AWS SES SDK for email sending (the `ses:SendRawEmail` API call must no longer be used).
- **FR-007**: The system MUST support sending test emails through the SES SMTP path to validate configuration.
- **FR-008**: The system MUST log email send attempts and failures consistently, regardless of whether SMTP or SES SMTP is used.

### Key Entities

- **EmailConfig** (modified): The existing email configuration entity, where the SES provider path now uses SMTP credentials (username/password) instead of AWS access key/secret key, and derives the SMTP host from the region.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All emails sent via the SES provider path are delivered using SMTP, with zero calls to the AWS SES API.
- **SC-002**: Email delivery success rate remains the same or improves compared to the SES API-based approach.
- **SC-003**: No IAM `ses:SendRawEmail` permission is required for the email-sending user.
- **SC-004**: Existing SES configurations can be migrated to SMTP-based sending with only credential updates (no full reconfiguration).

## Assumptions

- AWS SES SMTP credentials are distinct from AWS IAM access keys. The administrator will generate SMTP credentials from the AWS SES console (IAM user → SMTP credentials). These are a username and password pair.
- The SES SMTP endpoint follows the standard AWS naming pattern: `email-smtp.{region}.amazonaws.com`.
- The existing `sesAccessKey` and `sesSecretKey` fields in EmailConfig can be repurposed or replaced with SMTP username/password fields. The existing encrypted storage mechanism applies.
- The AWS SES SDK dependency can be removed from the project since SES API calls will no longer be made for email sending.
- All other email providers (standard SMTP) continue to work unchanged.
