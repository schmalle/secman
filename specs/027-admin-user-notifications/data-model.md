# Data Model: Admin User Notification System

**Feature**: 027-admin-user-notifications
**Created**: 2025-10-19

## Overview

This feature introduces two new entities for configuration management and audit logging, plus modifications to existing user creation flows.

## New Entities

### SystemSetting

**Purpose**: Store system-wide configuration settings that can be modified through the Admin UI.

**Table**: `system_settings`

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | PRIMARY KEY, AUTO_INCREMENT | Unique identifier |
| key | String(255) | NOT NULL, UNIQUE | Setting key (e.g., "notify_admins_on_new_user") |
| value | String(1000) | NOT NULL | Setting value (stored as string, parsed by application) |
| description | String(500) | NULLABLE | Human-readable description of setting purpose |
| createdAt | Instant | NOT NULL | Timestamp when setting was created |
| updatedAt | Instant | NOT NULL | Timestamp when setting was last modified |
| updatedBy | String(100) | NULLABLE | Username of admin who last modified setting |

**Indexes**:
- Primary key on `id`
- Unique index on `key`

**Validation Rules**:
- `key` must be non-empty and alphanumeric with underscores
- `value` must be non-empty
- `key` uniqueness enforced at database level

**Initial Data**:
```sql
INSERT INTO system_settings (key, value, description, createdAt, updatedAt, updatedBy) VALUES
  ('notify_admins_on_new_user', 'true', 'Enable/disable email notifications to admins for new user registrations', NOW(), NOW(), 'system'),
  ('notification_sender_email', 'noreply@secman.local', 'Email address used as sender for notification emails', NOW(), NOW(), 'system');
```

**Kotlin Entity**:
```kotlin
@Entity
@Table(name = "system_settings")
data class SystemSetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(unique = true, nullable = false, length = 255)
    val key: String,

    @Column(nullable = false, length = 1000)
    val value: String,

    @Column(length = 500)
    val description: String? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    val updatedAt: Instant = Instant.now(),

    @Column(length = 100)
    val updatedBy: String? = null
)
```

---

### EmailNotificationEvent

**Purpose**: Audit trail for all notification email attempts (successful and failed).

**Table**: `email_notification_events`

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | PRIMARY KEY, AUTO_INCREMENT | Unique identifier |
| recipientEmail | String(255) | NOT NULL | Email address of notification recipient |
| subject | String(500) | NOT NULL | Email subject line |
| bodyPreview | String(1000) | NULLABLE | First 1000 chars of email body (for troubleshooting) |
| newUsername | String(100) | NOT NULL | Username of newly created user |
| newUserEmail | String(255) | NOT NULL | Email of newly created user |
| registrationMethod | String(50) | NOT NULL | "Manual" or OAuth provider name (e.g., "GitHub") |
| createdByUsername | String(100) | NULLABLE | Username of admin who created user (null for OAuth) |
| sendStatus | String(20) | NOT NULL | "sent" or "failed" |
| failureReason | String(1000) | NULLABLE | Error message if status is "failed" |
| timestamp | Instant | NOT NULL | When email send was attempted |

**Indexes**:
- Primary key on `id`
- Index on `timestamp` (for cleanup query performance)
- Index on `sendStatus` (for monitoring queries)
- Composite index on `(recipientEmail, timestamp)` (for recipient-specific audit queries)

**Validation Rules**:
- `recipientEmail` must be valid email format
- `newUserEmail` must be valid email format
- `sendStatus` must be either "sent" or "failed"
- `failureReason` required if `sendStatus` is "failed", null otherwise

**Retention Policy**:
- Records older than 30 days automatically deleted by scheduled task
- Cleanup runs daily at 2 AM via `EmailNotificationCleanupTask`

**Kotlin Entity**:
```kotlin
@Entity
@Table(
    name = "email_notification_events",
    indexes = [
        Index(name = "idx_timestamp", columnList = "timestamp"),
        Index(name = "idx_send_status", columnList = "sendStatus"),
        Index(name = "idx_recipient_timestamp", columnList = "recipientEmail,timestamp")
    ]
)
data class EmailNotificationEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 255)
    val recipientEmail: String,

    @Column(nullable = false, length = 500)
    val subject: String,

    @Column(length = 1000)
    val bodyPreview: String? = null,

    @Column(nullable = false, length = 100)
    val newUsername: String,

    @Column(nullable = false, length = 255)
    val newUserEmail: String,

    @Column(nullable = false, length = 50)
    val registrationMethod: String,

    @Column(length = 100)
    val createdByUsername: String? = null,

    @Column(nullable = false, length = 20)
    val sendStatus: String, // "sent" or "failed"

    @Column(length = 1000)
    val failureReason: String? = null,

    @Column(nullable = false)
    val timestamp: Instant = Instant.now()
)
```

---

## Modified Entities

### User (existing entity - NO schema changes)

**Modifications**: Behavioral changes only, no new fields.

**New Behavior**:
- When a User is created (via manual UI or OAuth registration), trigger `EmailNotificationService.sendAdminNotification(...)`
- Trigger is fire-and-forget (async, non-blocking)
- User creation succeeds even if notification fails

**Integration Points**:
- `UserService.createUser()` - Manual user creation via "Manage Users" UI
- `OAuthController.callback()` - OAuth registration completion
- Both call `emailNotificationService.sendAdminNotification(user, createdBy, method)`

---

## Relationships

```
SystemSetting (standalone - no relationships)

EmailNotificationEvent (standalone - no relationships)
  ↓ references (soft/denormalized):
  - User.username (via newUsername field)
  - User.email (via newUserEmail field)
  - User.username (via createdByUsername field)
  - User.email (via recipientEmail field)

Note: No foreign keys to preserve audit integrity if users are deleted
```

**Design Decision**: EmailNotificationEvent uses denormalized fields (usernames, emails) rather than foreign keys to User entity. This ensures audit trail persists even if users are deleted, providing complete historical record.

---

## Data Access Patterns

### High-Frequency Queries

1. **Check if notifications enabled** (on every user creation):
   ```kotlin
   systemSettingRepository.findByKey("notify_admins_on_new_user")
   // Optimized with in-memory cache
   ```

2. **Get sender email** (on every notification send):
   ```kotlin
   systemSettingRepository.findByKey("notification_sender_email")
   // Optimized with in-memory cache
   ```

3. **Get all ADMIN users** (on every notification send):
   ```kotlin
   userRepository.findByRolesContaining("ADMIN")
   // Existing query, no new index needed
   ```

### Medium-Frequency Queries

4. **Save notification event** (per recipient, per notification):
   ```kotlin
   emailNotificationEventRepository.save(event)
   ```

5. **Get notification settings** (when admin opens settings page):
   ```kotlin
   systemSettingRepository.findByKeyIn(listOf("notify_admins_on_new_user", "notification_sender_email"))
   ```

### Low-Frequency Queries

6. **Update notification settings** (when admin saves settings):
   ```kotlin
   systemSettingRepository.updateValueByKey(key, newValue, updatedBy)
   ```

7. **Cleanup old events** (once per day at 2 AM):
   ```kotlin
   emailNotificationEventRepository.deleteByTimestampBefore(cutoffDate)
   ```

8. **Audit query: Recent notification history** (admin troubleshooting):
   ```kotlin
   emailNotificationEventRepository.findByRecipientEmailOrderByTimestampDesc(email, limit = 100)
   ```

---

## Migration Strategy

**Database Changes**:
- Two new tables: `system_settings`, `email_notification_events`
- No changes to existing tables
- Hibernate auto-migration will create tables on application startup

**Initial Data Seeding**:
- Option 1: Flyway/Liquibase migration script (if used)
- Option 2: Application startup bean that inserts default settings if not exist
- Recommended: Option 2 for consistency with existing Secman patterns

**Rollback Plan**:
- If feature needs to be disabled: Set `notify_admins_on_new_user` to `false` via Admin UI
- If feature needs to be removed: Drop tables (no foreign keys to worry about)
- User creation flow unchanged, so rollback is non-breaking

---

## Performance Considerations

**Caching Strategy**:
- `SystemSetting` values cached in-memory (ConcurrentHashMap)
- Cache invalidated on setting updates
- Reduces DB queries from ~1000/month to ~10/month for high-frequency checks

**Index Strategy**:
- `system_settings.key` unique index: O(1) lookups for configuration
- `email_notification_events.timestamp` index: Efficient cleanup query (range scan)
- `email_notification_events.sendStatus` index: Monitoring queries (count failures)
- Composite index `(recipientEmail, timestamp)`: Efficient per-admin audit queries

**Scaling Estimates**:
- 1000 new users/month × 10 ADMIN users = 10,000 notification events/month
- At 30-day retention: ~10,000 active records in `email_notification_events`
- Table size estimate: ~10,000 rows × 2 KB/row = ~20 MB (negligible)
- Cleanup runs in <1 second for 10K rows (DELETE with indexed WHERE clause)

**Transaction Isolation**:
- Notification sending uses `@Transactional(propagation = REQUIRES_NEW)`
- Separate transaction ensures email failure doesn't rollback user creation
- Async execution (@Async) prevents blocking user creation flow

---

## Security Considerations

**Access Control**:
- `SystemSetting`: Only ADMIN users can read/write via API
- `EmailNotificationEvent`: Only ADMIN users can read via API (not exposed in MVP)
- No public access to either entity

**Data Sensitivity**:
- `SystemSetting`: Low sensitivity (configuration, no user data)
- `EmailNotificationEvent`: Medium sensitivity (contains email addresses, usernames)
- `bodyPreview` truncated to 1000 chars to avoid storing full email content

**Audit Trail Integrity**:
- No CASCADE DELETE from User → EmailNotificationEvent
- Audit records preserved even if users deleted
- Provides historical accountability
