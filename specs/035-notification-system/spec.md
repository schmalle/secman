# Feature Specification: Outdated Asset Notification System

**Feature Branch**: `035-notification-system`
**Created**: 2025-10-26
**Status**: Draft
**Input**: User description: "i want to have a notification logic, which is triggered via the commandline via a new command line option. If the option is selected, the logic searches for outdated systems and sends an email an email to the assetowner once the system becomes outdated. If the system had after 7 days still the same status, a second stronger formulated email will be sent. Additionally every asset owner can configure, if the asset owner wants to recieve a notification for every new vulnerability of his assets. If the asset owner has more systems, the asset owners gets a combined email. For users with role ADMIN a logfile who recieved when the first and 2nd level reminder emails. Logfiles must be able to be logged."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - ADMIN Triggers Notification Run (Priority: P1)

An administrator runs a command-line tool to check for outdated assets and send notification emails to all asset owners who have outdated systems. The tool identifies which assets have exceeded the outdated threshold, determines which reminder level each asset owner should receive (first or second), and sends aggregated emails.

**Why this priority**: This is the core mechanism that drives all notifications. Without this, no emails are sent and the feature has no value.

**Independent Test**: Can be fully tested by running the CLI command with test data (a few outdated assets with known owners) and verifying that appropriate emails are sent. Delivers immediate value by enabling basic notification functionality.

**Acceptance Scenarios**:

1. **Given** there are assets that have become outdated within the last 24 hours, **When** ADMIN runs the notification command, **Then** first-level reminder emails are sent to the respective asset owners
2. **Given** there are assets that have been outdated for 7+ days and already received a first-level reminder, **When** ADMIN runs the notification command, **Then** second-level reminder emails (with stronger language) are sent to the respective asset owners
3. **Given** an asset owner has multiple outdated assets, **When** ADMIN runs the notification command, **Then** the owner receives a single combined email listing all their outdated assets
4. **Given** an asset owner has no email address in the UserMapping table, **When** ADMIN runs the notification command, **Then** no email is sent for that owner's assets and a warning is logged
5. **Given** the notification command is run with the `--dry-run` flag, **When** the command executes, **Then** it reports what emails would be sent without actually sending them

---

### User Story 2 - Asset Owner Configures Notification Preferences (Priority: P2)

An asset owner (represented by a User in the system) can log into the application and configure whether they want to receive notifications for new vulnerabilities discovered on their assets. This preference is independent of the outdated asset reminders, which are always sent.

**Why this priority**: This enables customization and prevents email fatigue for owners who don't want vulnerability alerts. It's lower priority because the core outdated notifications work without it.

**Independent Test**: Can be tested by logging in as a user, toggling the preference, then importing vulnerabilities for that user's assets and verifying whether emails are sent or suppressed based on the preference.

**Acceptance Scenarios**:

1. **Given** a user is logged in, **When** they navigate to the notification preferences page, **Then** they see a toggle for "Notify me of new vulnerabilities on my assets" with their current setting
2. **Given** a user enables new vulnerability notifications, **When** new vulnerabilities are detected for their assets, **Then** they receive an email notification summarizing the new vulnerabilities
3. **Given** a user disables new vulnerability notifications, **When** new vulnerabilities are detected for their assets, **Then** they do NOT receive any email about those vulnerabilities
4. **Given** a user has multiple assets, **When** new vulnerabilities are detected across those assets, **Then** they receive a single combined email (not one per asset)

---

### User Story 3 - ADMIN Reviews Notification Audit Logs (Priority: P3)

An administrator can view comprehensive logs showing which asset owners received notifications, when they were sent, what type of notification (first/second reminder, new vulnerability), and the delivery status. This supports compliance auditing and troubleshooting.

**Why this priority**: Important for compliance and troubleshooting, but the system can function without it. Logs can be generated even if no UI exists initially (file-based logs).

**Independent Test**: Can be tested by running the notification command, then viewing the audit log interface and verifying that all sent notifications are recorded with correct timestamps, recipient emails, and notification types.

**Acceptance Scenarios**:

1. **Given** notifications have been sent, **When** an ADMIN views the notification log, **Then** they see a list of all notifications with columns: timestamp, recipient email, asset name/ID, notification type (L1/L2 reminder, new vulnerability), and delivery status
2. **Given** an ADMIN wants to audit reminders for a specific time period, **When** they filter the log by date range, **Then** only notifications sent within that range are displayed
3. **Given** an ADMIN needs to export audit data, **When** they click the export button, **Then** a CSV file is downloaded containing all log entries with full details
4. **Given** multiple notification runs have occurred, **When** an ADMIN views the log, **Then** entries are sorted by timestamp (newest first) by default

---

### Edge Cases

- What happens when an asset owner email address is invalid or bounces? System should log the bounce and mark the notification as failed in the audit log. ADMIN should be able to see failed deliveries.
- How does the system handle assets that transition from "outdated" back to "up-to-date" (e.g., vulnerabilities are remediated)? The reminder state should be reset so that if the asset becomes outdated again later, it starts fresh with a first-level reminder.
- What happens when an asset owner has 100+ outdated assets? The combined email should still send, but may need formatting considerations (e.g., truncation with a link to view full list, or CSV attachment).
- How does the system prevent duplicate emails if the CLI command is run multiple times in quick succession? Track last-sent timestamp per asset and reminder level; do not send again within the same day.
- What happens if the SMTP server is unavailable when the CLI command runs? Log the failure, mark notifications as "pending retry" in the audit log, and allow ADMIN to manually re-trigger.
- How are assets with no owner (Asset.owner is null) handled? They should be skipped with a warning logged, or optionally send to a default ADMIN email.
- What happens when a user has new vulnerability notifications disabled but their assets are outdated? They still receive outdated asset reminders (which are not configurable), but no new vulnerability alerts.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a CLI command that can be executed to trigger the notification process for outdated assets and new vulnerabilities
- **FR-002**: System MUST support a `--dry-run` flag that reports planned notifications without sending actual emails
- **FR-003**: System MUST query the OutdatedAssetMaterializedView to identify assets that have exceeded the outdated threshold
- **FR-004**: System MUST match Asset.owner field to UserMapping.awsAccountId to resolve the owner's email address
- **FR-005**: System MUST track the reminder level (1 or 2) for each outdated asset to determine which email template to use
- **FR-006**: System MUST send a first-level reminder email when an asset first appears in the outdated view
- **FR-007**: System MUST send a second-level reminder email (with stronger language) if an asset has been outdated for 7+ days and the first reminder was already sent
- **FR-008**: System MUST aggregate multiple assets owned by the same person into a single email per notification run
- **FR-009**: System MUST allow users to configure a preference for receiving new vulnerability notifications (enable/disable)
- **FR-010**: System MUST send new vulnerability notifications only to asset owners who have enabled this preference
- **FR-011**: System MUST aggregate new vulnerabilities across all of an owner's assets into a single email
- **FR-012**: System MUST log every notification sent with: recipient email, asset identifier, notification type, timestamp, and delivery status
- **FR-013**: System MUST provide an ADMIN-accessible interface to view notification audit logs with filtering and export capabilities
- **FR-014**: System MUST reset the reminder state for an asset if it transitions from outdated back to up-to-date
- **FR-015**: System MUST skip sending emails for assets with no owner or owners with no email address, logging a warning
- **FR-016**: System MUST prevent duplicate email sends by tracking last-sent timestamp per asset and reminder level (do not re-send within same day)
- **FR-017**: System MUST use HTML email templates with plain-text fallbacks for all notifications
- **FR-018**: System MUST distinguish between first-level reminders (professional, actionable tone) and second-level reminders (urgent, escalation tone)
- **FR-019**: System MUST include in each email: list of affected assets, severity breakdown, oldest vulnerability age, and actionable next steps
- **FR-020**: System MUST handle SMTP failures gracefully by logging errors and marking notifications as failed in the audit log

### Key Entities

- **NotificationPreference**: Represents a user's notification settings. Attributes include: user reference (links to User entity), enableNewVulnNotifications flag (boolean), lastUpdated timestamp.

- **NotificationLog**: Audit trail entry for each notification sent. Attributes include: asset identifier, asset name, owner email, notification type (OUTDATED_LEVEL1, OUTDATED_LEVEL2, NEW_VULNERABILITY), sent timestamp, email delivery status (SENT, FAILED, PENDING), error message (if failed).

- **AssetReminderState**: Tracks the reminder progression for each asset. Attributes include: asset reference (links to Asset entity), current reminder level (1 or 2), last sent timestamp, outdated since timestamp (when asset first became outdated), last checked timestamp.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: ADMIN can execute the notification CLI command and complete processing for 10,000 assets in under 2 minutes
- **SC-002**: Asset owners receive their first reminder email within 24 hours of their asset appearing in the outdated view (when ADMIN runs the command daily)
- **SC-003**: Asset owners with multiple outdated assets receive exactly one combined email per notification run, regardless of the number of assets
- **SC-004**: Second-level reminder emails are sent to owners whose assets have been outdated for 7+ days and who received a first-level reminder at least 7 days prior
- **SC-005**: Zero duplicate emails are sent to the same recipient within the same notification run
- **SC-006**: ADMIN can view a complete audit trail of all notifications sent, including timestamp, recipient, asset details, and delivery status
- **SC-007**: 95% of emails are successfully delivered (status = SENT) when SMTP server is healthy
- **SC-008**: Asset owners who disable new vulnerability notifications receive zero emails for newly discovered vulnerabilities
- **SC-009**: Asset owners who enable new vulnerability notifications receive an email summarizing new vulnerabilities within 24 hours of detection (when ADMIN runs the command after vulnerability imports)
- **SC-010**: Email templates clearly distinguish between first-level (professional) and second-level (urgent) reminders based on tone and language

## Assumptions

- The OutdatedAssetMaterializedView (Feature 034) is operational and accurately reflects which assets are outdated
- The UserMapping table (Feature 013/016) is populated with asset owner email addresses linked to AWS account IDs
- Asset.owner field contains the AWS account ID that matches UserMapping.awsAccountId
- SMTP server configuration is already set up in the application configuration (application.yml)
- ADMIN users will run the CLI command on a regular schedule (e.g., daily via cron job) to ensure timely notifications
- Email delivery failures are acceptable for a small percentage of notifications (5%) due to invalid addresses or temporary SMTP issues
- The 7-day threshold for second-level reminders is a fixed business rule (not configurable in this feature)
- Assets with null owner or owners with no email will not block the notification process; they are simply skipped
- HTML email clients are available for most recipients; plain-text fallback is provided for accessibility
- New vulnerability notifications are triggered after CLI vulnerability imports, not in real-time
- Notification preferences apply per User entity, not per individual Asset (one preference for all assets owned by a user)
