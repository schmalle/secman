# Research: Outdated Asset Notification System

**Date**: 2025-10-26
**Feature**: [035-notification-system](spec.md)

## Overview

This document consolidates research findings for implementing the notification system, focusing on email delivery best practices, reminder state management, and integration patterns with existing features.

## Key Decisions

### 1. Email Delivery Architecture

**Decision**: Use JavaMail API with SMTP transport for email delivery

**Rationale**:
- **Native Micronaut Support**: Micronaut has built-in support for JavaMail via `micronaut-email` module
- **Mature & Reliable**: JavaMail is the standard for Java email sending with 20+ years of production use
- **Template Support**: Works seamlessly with Thymeleaf or FreeMarker for HTML email templating
- **SMTP Compatibility**: Works with any SMTP server (SendGrid, AWS SES, on-premise SMTP)
- **Error Handling**: Comprehensive exception hierarchy for bounce handling, timeout detection, authentication failures

**Alternatives Considered**:
- **External Email Service (SendGrid API)**: Rejected because it requires vendor lock-in and additional external dependencies. JavaMail with SMTP is more flexible and allows switching providers easily.
- **AWS SES SDK**: Rejected because it assumes AWS infrastructure. The current architecture should support any SMTP provider.
- **Apache Commons Email**: Rejected because JavaMail is more feature-complete and has better Micronaut integration.

**Implementation Notes**:
- Use `jakarta.mail.Session` with SMTP configuration from `application.yml`
- Implement retry logic for transient failures (3 retries with exponential backoff)
- Use connection pooling for SMTP connections (Micronaut manages this)
- Set reasonable timeouts (30 seconds for connection, 30 seconds for send)

### 2. Email Template Engine

**Decision**: Use Thymeleaf for HTML email templates

**Rationale**:
- **Server-Side Rendering**: Thymeleaf is designed for server-side template rendering, perfect for email generation
- **Spring/Micronaut Compatible**: Works well with Micronaut's dependency injection
- **Type-Safe**: Supports strongly-typed model objects (EmailContext data class)
- **Email-Specific Features**: Supports inlining CSS, embedding images, plain-text fallback generation
- **Maintainability**: Non-technical users can edit templates without touching code

**Alternatives Considered**:
- **FreeMarker**: Rejected because Thymeleaf has better HTML5 support and more natural syntax for email templates
- **String Templates (Kotlin)**: Rejected because it mixes presentation logic with business logic and is harder to maintain
- **Mustache**: Rejected because it's too minimalistic for complex email layouts with conditionals and loops

**Implementation Notes**:
- Store templates in `src/backendng/src/main/resources/email-templates/`
- Create separate templates for: `outdated-reminder-level1.html`, `outdated-reminder-level2.html`, `new-vulnerabilities.html`
- Generate plain-text fallbacks automatically using Thymeleaf's text mode
- Use inline CSS (email clients don't support external stylesheets)
- Test templates with email client previews (Gmail, Outlook, Apple Mail)

### 3. Reminder State Management

**Decision**: Use AssetReminderState entity with database-backed state tracking

**Rationale**:
- **Persistence**: State must survive application restarts (CLI command runs intermittently)
- **Idempotency**: Prevents duplicate emails if CLI command is run multiple times in same day
- **Auditability**: State changes are logged (when reminder level escalates from 1 to 2)
- **Reset Logic**: Allows resetting state when asset transitions from outdated → up-to-date
- **Scalability**: Database queries are more efficient than in-memory tracking for 10,000+ assets

**Alternatives Considered**:
- **In-Memory State (Redis)**: Rejected because it requires additional infrastructure (Redis server) and doesn't provide audit trail
- **File-Based State (JSON)**: Rejected because it doesn't scale well, has concurrency issues, and is hard to query
- **No State Tracking**: Rejected because it would cause duplicate emails on every CLI run

**Implementation Notes**:
- Primary key: `assetId` (one reminder state per asset)
- Fields: `level` (1 or 2), `lastSentAt` (timestamp), `outdatedSince` (timestamp), `lastCheckedAt` (timestamp)
- Index on `assetId` for fast lookups
- When asset becomes up-to-date: delete AssetReminderState row (reset for future)
- When asset is outdated for 7+ days and level=1: update to level=2 and update `lastSentAt`

### 4. Asset Owner Email Resolution

**Decision**: Join Asset.owner → UserMapping.awsAccountId → UserMapping.email

**Rationale**:
- **Reuses Existing Data**: UserMapping (Feature 013/016) already contains owner-to-email mappings
- **ADMIN-Managed**: Email addresses are managed centrally by ADMIN via Excel/CSV import
- **Single Source of Truth**: No duplicate email storage across tables
- **Validated Data**: UserMapping already validates email format during import

**Alternatives Considered**:
- **Separate AssetOwner Entity**: Rejected because it duplicates data already in UserMapping
- **Email in Asset Table**: Rejected because it violates normalization and is harder to maintain
- **Email in User Table**: Rejected because asset owners may not be User entities (external contacts)

**Implementation Notes**:
- Query: `SELECT um.email FROM Asset a JOIN UserMapping um ON a.owner = um.awsAccountId WHERE a.id = ?`
- Handle null cases: If no UserMapping found for asset.owner, log warning and skip notification
- Cache UserMapping lookups during single CLI run (avoid redundant queries for same owner)
- Group assets by email address for aggregation (one email per owner)

### 5. Email Aggregation Strategy

**Decision**: Group assets by owner email in memory, then send one email per owner

**Rationale**:
- **User Experience**: Receiving 50 individual emails for 50 assets is annoying; one combined email is better
- **Performance**: Sending one email with 50 assets is faster than sending 50 individual emails
- **SMTP Rate Limits**: Reduces risk of hitting SMTP rate limits (many providers limit emails/minute)
- **Reduced Load**: Less load on SMTP server and recipient mail server

**Alternatives Considered**:
- **One Email Per Asset**: Rejected because it causes email fatigue and poor UX
- **Database-Side Aggregation**: Rejected because it's more complex; in-memory grouping is simpler and sufficient for 1,000 owners

**Implementation Notes**:
- Algorithm:
  1. Query all outdated assets from OutdatedAssetMaterializedView
  2. Resolve owner email for each asset (join with UserMapping)
  3. Group assets by email address using `Map<String, List<Asset>>`
  4. For each email address, generate one email with list of assets
  5. Send email and log NotificationLog entry
- Email should include: total count, severity breakdown, table of assets (name, type, vulnerability count, oldest vuln age)
- If owner has >100 assets, consider pagination or CSV attachment (edge case handling)

### 6. New Vulnerability Notification Trigger

**Decision**: Trigger new vulnerability notifications during CLI command run (batch mode)

**Rationale**:
- **Consistency**: Both outdated reminders and new vulnerability notifications use same execution model (CLI-triggered)
- **Simplicity**: No need for event listeners or real-time triggers
- **Performance**: Batch processing is more efficient than per-vulnerability triggers
- **User Control**: ADMIN controls when notifications are sent (can run after vulnerability imports)

**Alternatives Considered**:
- **Real-Time Event Listener**: Rejected because it requires continuous process, adds complexity, and is overkill for daily notification cadence
- **Separate CLI Command**: Rejected because it doubles the operational overhead (ADMIN runs two commands instead of one)

**Implementation Notes**:
- During CLI run, check NotificationPreference for each asset owner
- If `enableNewVulnNotifications = true`, query for vulnerabilities added since last notification run
- Track "last vulnerability notification sent" timestamp per user (add field to NotificationPreference)
- Aggregate new vulnerabilities by owner email (same aggregation logic as outdated reminders)
- Send email with new vulnerability summary (grouped by severity)

### 7. Audit Logging Strategy

**Decision**: Create NotificationLog entry for every email sent, synchronously

**Rationale**:
- **Compliance**: ADMIN needs complete audit trail for regulatory compliance
- **Troubleshooting**: Logs help diagnose email delivery failures
- **Synchronous**: Ensures log entry is created immediately (no async lag)
- **Searchable**: Database storage allows filtering by date, recipient, type

**Alternatives Considered**:
- **File-Based Logging**: Rejected because it's harder to query and filter
- **Async Logging**: Rejected because it risks log loss if application crashes before async write completes
- **No Logging**: Rejected because it violates functional requirement FR-012

**Implementation Notes**:
- Create NotificationLog entry immediately after email send succeeds/fails
- Fields: `assetId`, `assetName`, `ownerEmail`, `notificationType`, `sentAt`, `status` (SENT/FAILED), `errorMessage`
- Index on `sentAt` for time-based queries
- ADMIN UI: paginated table with filters (date range, status, notification type)
- Export to CSV for offline analysis

### 8. CLI Command Design

**Decision**: Implement as Gradle task with Picocli for argument parsing

**Rationale**:
- **Consistency**: Existing CLI commands in `src/cli/` use Picocli
- **Argument Parsing**: Picocli provides robust argument validation and help text
- **Integration**: Easy to inject Micronaut services into Picocli commands
- **Gradle Integration**: Runs via `./gradlew cli:run --args='send-notifications'`

**Alternatives Considered**:
- **Standalone Script**: Rejected because it duplicates database connection logic and is harder to test
- **HTTP Endpoint**: Rejected because it bypasses CLI requirement and requires web server to be running

**Implementation Notes**:
- Command name: `send-notifications`
- Arguments:
  - `--dry-run`: Reports planned notifications without sending emails
  - `--verbose`: Detailed logging (default: summary only)
- Exit codes: 0 (success), 1 (failure)
- Output: Summary statistics (emails sent, failures, assets processed)

### 9. Duplicate Prevention Strategy

**Decision**: Check AssetReminderState.lastSentAt before sending; skip if same day

**Rationale**:
- **Idempotency**: Prevents duplicate emails if CLI command is accidentally run twice
- **Simple Logic**: Date comparison is straightforward and fast
- **Flexible**: ADMIN can still manually re-run if needed (will send next day)

**Alternatives Considered**:
- **Distributed Lock**: Rejected because it's overkill for single-server deployment
- **No Duplicate Prevention**: Rejected because it causes poor UX (duplicate emails)

**Implementation Notes**:
- Before sending email for an asset, check: `AssetReminderState.lastSentAt.date == today`
- If true, skip and log: "Notification already sent today for asset {id}"
- If false or null, send email and update `lastSentAt = now()`

### 10. Performance Optimization

**Decision**: Use batch queries and connection pooling for database access

**Rationale**:
- **N+1 Problem**: Avoid querying UserMapping individually for each asset (use JOIN)
- **Connection Pool**: Reuse database connections across queries (Micronaut default)
- **Batch Size**: Process assets in batches of 1,000 to limit memory usage

**Alternatives Considered**:
- **Sequential Processing**: Rejected because it's too slow for 10,000 assets
- **Parallel Processing**: Rejected because it complicates error handling and doesn't significantly improve performance for I/O-bound operations

**Implementation Notes**:
- Use single query to fetch all outdated assets + owner emails (JOIN)
- Process in batches of 1,000 assets
- Use SMTP connection pooling (configure in application.yml)
- Target: <2 minutes for 10,000 assets (per success criterion SC-001)

## Integration Points

### With Feature 034 (Outdated Assets)

- **Dependency**: Reads from `OutdatedAssetMaterializedView` to identify outdated assets
- **Query**: `SELECT * FROM outdated_asset_materialized_view WHERE totalOverdueCount > 0`
- **No Modifications**: Feature 034 is read-only from this feature's perspective
- **Refresh Consideration**: If materialized view is stale, notifications may be delayed (acceptable trade-off)

### With Feature 013/016 (UserMapping)

- **Dependency**: Joins with `UserMapping` table to resolve asset owner emails
- **Query**: `JOIN UserMapping ON Asset.owner = UserMapping.awsAccountId`
- **No Modifications**: UserMapping is read-only from this feature's perspective
- **Missing Emails**: If UserMapping doesn't have an entry for an asset owner, skip notification and log warning

### With User Entity

- **Dependency**: NotificationPreference links to User entity (foreign key)
- **Query**: User must be authenticated to access/update their NotificationPreference
- **No Modifications**: User table is not modified by this feature
- **Preference Defaults**: If no NotificationPreference exists for a user, default to `enableNewVulnNotifications = false`

## Best Practices Applied

### Email Deliverability

- **SPF/DKIM/DMARC**: Ensure SMTP server is configured with proper email authentication
- **From Address**: Use a verified sender address (e.g., `noreply@company.com`)
- **Reply-To**: Set reply-to address for user responses (e.g., `security-team@company.com`)
- **Unsubscribe**: Not applicable (outdated reminders are mandatory; new vuln notifications are opt-in via preferences)
- **Subject Line**: Clear and actionable (e.g., "Action Required: 5 Outdated Assets Detected")

### Email Content Best Practices

- **Mobile-Responsive**: Use responsive HTML tables (Bootstrap email templates)
- **Call-to-Action**: Include clear link to asset management dashboard
- **Tone Differentiation**: Level 1 = professional, Level 2 = urgent (use color coding: yellow → red)
- **Actionable**: Provide specific next steps ("Review these assets in the dashboard")
- **Branding**: Include company logo and consistent styling

### Security Considerations

- **Email Address Validation**: Validate format before sending (reject invalid addresses)
- **SMTP Credentials**: Store in environment variables, not in code
- **Injection Prevention**: Sanitize asset names and descriptions before including in emails
- **Sensitive Data**: Do not include vulnerability details in email (link to dashboard instead)

### Error Handling

- **SMTP Timeout**: Set 30-second timeout for connection and send operations
- **Bounce Handling**: Log bounce errors in NotificationLog (status = FAILED)
- **Retry Logic**: Retry transient errors (connection refused, timeout) up to 3 times
- **Permanent Failures**: Do not retry permanent failures (invalid email, blocked sender)
- **Rollback**: If email sending fails, NotificationLog should still record the attempt (for audit)

### Testing Strategy

- **Mock SMTP**: Use mock SMTP server (Greenmail) for integration tests
- **Template Testing**: Test email templates with real data (ensure no rendering errors)
- **Aggregation Testing**: Test with 100+ assets for single owner (verify performance)
- **Duplicate Prevention**: Test running CLI command twice in same day (verify idempotency)
- **Edge Cases**: Test with missing UserMapping, null Asset.owner, invalid email addresses

## Technical Specifications

### SMTP Configuration (application.yml)

```yaml
mail:
  smtp:
    host: ${SMTP_HOST:smtp.example.com}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    auth: true
    starttls:
      enable: true
      required: true
    timeout: 30000
    connection-timeout: 30000
  from:
    email: ${MAIL_FROM:noreply@example.com}
    name: ${MAIL_FROM_NAME:Security Management System}
```

### Email Template Data Model

```kotlin
data class EmailContext(
    val recipientEmail: String,
    val recipientName: String?,
    val assets: List<AssetEmailData>,
    val notificationType: NotificationType,
    val reminderLevel: Int? = null, // 1 or 2 for outdated reminders
    val totalCount: Int,
    val criticalCount: Int,
    val highCount: Int,
    val mediumCount: Int,
    val lowCount: Int,
    val dashboardUrl: String
)

data class AssetEmailData(
    val id: Long,
    val name: String,
    val type: String,
    val vulnerabilityCount: Int,
    val oldestVulnDays: Int,
    val oldestVulnId: String
)
```

### Notification Processing Algorithm

1. **Fetch Outdated Assets**:
   ```sql
   SELECT a.*, um.email
   FROM outdated_asset_materialized_view oamv
   JOIN Asset a ON oamv.assetId = a.id
   JOIN UserMapping um ON a.owner = um.awsAccountId
   WHERE oamv.totalOverdueCount > 0
   ```

2. **Check Reminder State**:
   - For each asset, check `AssetReminderState`:
     - If `lastSentAt.date == today`: Skip (already notified)
     - If `level == 1` AND `outdatedSince >= 7 days ago`: Escalate to level 2
     - If no state: Create new state with level 1

3. **Group by Owner Email**:
   ```kotlin
   val groupedAssets = assets.groupBy { it.ownerEmail }
   ```

4. **Send Emails**:
   - For each owner, generate email context
   - Render template with Thymeleaf
   - Send via JavaMail
   - Log to NotificationLog

5. **Update State**:
   - Update `AssetReminderState.lastSentAt = now()`
   - Update `AssetReminderState.level` if escalated

## References

- JavaMail API Documentation: https://javaee.github.io/javamail/
- Micronaut Email Module: https://micronaut-projects.github.io/micronaut-email/latest/guide/
- Thymeleaf Email Templates: https://www.thymeleaf.org/doc/articles/springmail.html
- Email Best Practices: https://www.campaignmonitor.com/resources/guides/email-marketing-best-practices/
- SMTP Error Codes: https://www.iana.org/assignments/smtp-enhanced-status-codes/smtp-enhanced-status-codes.xhtml
