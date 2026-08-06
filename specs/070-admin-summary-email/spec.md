# Feature Specification: Admin Summary Email

**Feature Branch**: `070-admin-summary-email`
**Created**: 2026-01-27
**Status**: Draft
**Input**: User description: "implement a functionality usable via CLI to sent to all ADMIN users an update via email, summarizing user numbers, numbers of vulns, number of assets. Use feature branch nr 70 for it"

## Clarifications

### Session 2026-01-27

- Q: Should the system persist a log record of each admin summary email execution? → A: Yes, persist execution logs to database (audit trail)

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Send Admin Summary Email via CLI (Priority: P1/MVP)

An administrator wants to send a summary email to all admin users containing key system metrics (total users, total vulnerabilities, total assets) to keep leadership informed about the security posture.

The administrator runs a CLI command that gathers current statistics from the system and sends a formatted email to all users with the ADMIN role.

**Why this priority**: Core functionality - without this, the feature provides no value. This is the minimum viable product.

**Independent Test**: Can be fully tested by running the CLI command and verifying that all ADMIN users receive an email containing the correct statistics.

**Acceptance Scenarios**:

1. **Given** the system has users, vulnerabilities, and assets data, **When** an administrator runs the admin summary email command, **Then** all users with ADMIN role receive an email containing total user count, total vulnerability count, and total asset count.

2. **Given** no users have the ADMIN role, **When** the command is executed, **Then** the system reports that no recipients were found and exits gracefully without sending emails.

3. **Given** the email service is unavailable, **When** the command is executed, **Then** the system reports the failure with an appropriate error message.

---

### User Story 2 - Dry Run Mode for Testing (Priority: P2)

An administrator wants to preview what would be sent without actually sending emails, to verify the statistics and recipient list before committing to the email send.

**Why this priority**: Important for safe operations and testing, but not required for basic functionality.

**Independent Test**: Can be tested by running the command with dry-run flag and verifying output shows planned recipients and statistics without sending emails.

**Acceptance Scenarios**:

1. **Given** the system has data and ADMIN users exist, **When** the command is run with `--dry-run` flag, **Then** the system displays the list of recipients and the statistics that would be sent, but no emails are delivered.

2. **Given** dry-run mode is active, **When** the command completes, **Then** the output clearly indicates it was a dry run and no emails were sent.

---

### User Story 3 - Verbose Output for Troubleshooting (Priority: P3)

An administrator wants detailed logging to troubleshoot issues or confirm email delivery status for each recipient.

**Why this priority**: Enhancement for operations - useful but not essential for core functionality.

**Independent Test**: Can be tested by running the command with verbose flag and verifying detailed per-recipient status is displayed.

**Acceptance Scenarios**:

1. **Given** verbose mode is enabled, **When** the command sends emails, **Then** the output shows per-recipient delivery status (success/failure) with email addresses.

---

### Edge Cases

- What happens when the email configuration is missing or invalid? System should report configuration error clearly.
- What happens when some ADMIN users have no email address configured? Those users should be skipped with a warning message.
- What happens when the database is unreachable? System should report connection error and exit with non-zero status.
- What happens when there are zero users, zero vulnerabilities, or zero assets? The email should still be sent with counts showing 0.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a CLI command `send-admin-summary` to trigger the admin summary email
- **FR-002**: System MUST retrieve the total count of active users from the database
- **FR-003**: System MUST retrieve the total count of vulnerabilities from the database
- **FR-004**: System MUST retrieve the total count of assets from the database
- **FR-005**: System MUST identify all users with the ADMIN role as email recipients
- **FR-006**: System MUST send a formatted email to each ADMIN user containing the three statistics (users, vulnerabilities, assets)
- **FR-007**: System MUST support a `--dry-run` flag that displays planned actions without sending emails
- **FR-008**: System MUST support a `--verbose` flag for detailed per-recipient logging
- **FR-009**: System MUST report a summary at completion showing: recipients count, emails sent, failures
- **FR-010**: System MUST exit with non-zero status code if any email delivery fails
- **FR-011**: System MUST skip ADMIN users who have no email address configured and report them as skipped
- **FR-012**: System MUST provide both HTML and plain text versions of the email for compatibility
- **FR-013**: System MUST persist an execution log record to the database for each run, including: timestamp, recipient count, statistics sent (user/vuln/asset counts), success/failure status

### Key Entities

- **User**: System users with roles (including ADMIN role) and email addresses
- **Vulnerability**: Security vulnerabilities tracked in the system
- **Asset**: IT assets/systems being managed
- **Email Template**: The formatted message template for the admin summary email
- **AdminSummaryLog**: Execution log record capturing: execution timestamp, recipient count, user/vulnerability/asset counts sent, success/failure status

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Command completes within 30 seconds for systems with up to 100 ADMIN users
- **SC-002**: All ADMIN users with valid email addresses receive the summary email within 5 minutes of command execution
- **SC-003**: Email content accurately reflects the current database counts at time of execution
- **SC-004**: Dry-run mode produces output within 5 seconds without sending any emails
- **SC-005**: Command provides clear success/failure feedback that can be used in automated scheduling (cron jobs)

## Assumptions

- The existing email infrastructure (SMTP configuration, email service) from the notification system (feature 035) will be reused
- ADMIN users are identified by having "ADMIN" in their roles list
- The command will be run manually or via cron job by system operators
- Email addresses are stored in the User entity and are considered valid if present and non-empty
- Statistics are point-in-time counts (no historical comparison in this initial version)
