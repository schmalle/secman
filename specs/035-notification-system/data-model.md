# Data Model: Outdated Asset Notification System

**Feature**: [035-notification-system](spec.md)
**Date**: 2025-10-26

## Overview

This feature introduces 3 new entities to support email notifications for outdated assets and new vulnerabilities. The entities track user preferences, reminder state progression, and audit logs.

## Entity Diagram

```
┌─────────────────────────────┐
│ User (existing)             │
│ ─────────────────────────── │
│ PK: id                      │
│     username                │
│     email                   │
│     roles                   │
└──────────┬──────────────────┘
           │
           │ 1:1
           │
           ▼
┌─────────────────────────────┐
│ NotificationPreference      │
│ ─────────────────────────── │
│ PK: id                      │
│ FK: userId (→ User.id)      │
│     enableNewVulnNotifs     │
│     lastVulnNotifSentAt     │
│     createdAt               │
│     updatedAt               │
└─────────────────────────────┘

┌─────────────────────────────┐         ┌──────────────────────────────┐
│ Asset (existing)            │         │ AssetReminderState           │
│ ─────────────────────────── │  1:1    │ ──────────────────────────── │
│ PK: id                      │◄────────│ PK: id                       │
│     name                    │         │ FK: assetId (→ Asset.id)     │
│     type                    │         │ UQ: assetId                  │
│     owner (awsAccountId)    │         │     level (1 or 2)           │
└─────────────────────────────┘         │     lastSentAt               │
                                        │     outdatedSince            │
                                        │     lastCheckedAt            │
                                        │     createdAt                │
                                        └──────────────────────────────┘

┌─────────────────────────────┐
│ NotificationLog             │
│ ─────────────────────────── │
│ PK: id                      │
│ FK: assetId (→ Asset.id)    │
│     assetName               │
│     ownerEmail              │
│     notificationType        │
│     sentAt                  │
│     status (SENT/FAILED)    │
│     errorMessage (nullable) │
└─────────────────────────────┘

┌─────────────────────────────┐
│ NotificationType (enum)     │
│ ─────────────────────────── │
│ - OUTDATED_LEVEL1           │
│ - OUTDATED_LEVEL2           │
│ - NEW_VULNERABILITY         │
└─────────────────────────────┘
```

## Entities

### 1. NotificationPreference

**Purpose**: Stores user preferences for notification delivery (currently only new vulnerability notifications; outdated reminders are always sent)

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | Long | PK, Auto-increment | Primary key |
| `userId` | Long | FK → User.id, NOT NULL, UNIQUE | Reference to User entity (one preference per user) |
| `enableNewVulnNotifications` | Boolean | NOT NULL, DEFAULT false | Whether user wants emails for new vulnerabilities on their assets |
| `lastVulnNotificationSentAt` | Timestamp | NULLABLE | Timestamp of last new vulnerability notification sent to this user |
| `createdAt` | Timestamp | NOT NULL, DEFAULT now() | Record creation timestamp |
| `updatedAt` | Timestamp | NOT NULL, DEFAULT now() | Record last update timestamp |

**Indexes**:
- Primary key on `id`
- Unique index on `userId`

**Relationships**:
- Many-to-One with User (FK: userId → User.id)

**Validation Rules**:
- `userId` must reference existing User
- `enableNewVulnNotifications` defaults to false (opt-in model)

**State Transitions**: N/A (simple on/off toggle)

**Notes**:
- If no NotificationPreference exists for a user, default to `enableNewVulnNotifications = false`
- Users can only update their own preferences (enforced by controller)

---

### 2. AssetReminderState

**Purpose**: Tracks the reminder level progression for each outdated asset to enable two-level reminders and prevent duplicates

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | Long | PK, Auto-increment | Primary key |
| `assetId` | Long | FK → Asset.id, NOT NULL, UNIQUE | Reference to Asset entity (one state per asset) |
| `level` | Integer | NOT NULL, CHECK (level IN (1, 2)) | Current reminder level: 1 = first reminder, 2 = escalated reminder |
| `lastSentAt` | Timestamp | NOT NULL | Timestamp when last notification was sent for this asset |
| `outdatedSince` | Timestamp | NOT NULL | Timestamp when asset first became outdated (used to calculate 7-day escalation threshold) |
| `lastCheckedAt` | Timestamp | NOT NULL, DEFAULT now() | Timestamp of last CLI run that processed this asset |
| `createdAt` | Timestamp | NOT NULL, DEFAULT now() | Record creation timestamp |

**Indexes**:
- Primary key on `id`
- Unique index on `assetId` (one state per asset)
- Index on `lastSentAt` (for duplicate prevention queries)
- Index on `outdatedSince` (for escalation threshold queries)

**Relationships**:
- Many-to-One with Asset (FK: assetId → Asset.id, CASCADE DELETE)

**Validation Rules**:
- `assetId` must reference existing Asset
- `level` must be 1 or 2
- `lastSentAt` <= now()
- `outdatedSince` <= `lastSentAt`

**State Transitions**:

```
[No State] → Create with level=1, outdatedSince=now(), lastSentAt=now()
           ↓
       [Level 1]
           │
           │ (7 days elapsed AND asset still outdated)
           ↓
       [Level 2] (update level=2, lastSentAt=now())
           │
           │ (asset becomes up-to-date)
           ↓
      [Deleted] (state reset for future)
```

**Notes**:
- State is deleted when asset transitions from outdated → up-to-date (allows fresh start if asset becomes outdated again later)
- Duplicate prevention: If `lastSentAt.date == today`, skip sending notification

---

### 3. NotificationLog

**Purpose**: Audit trail for all notifications sent; supports compliance reporting and troubleshooting

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | Long | PK, Auto-increment | Primary key |
| `assetId` | Long | FK → Asset.id, NULLABLE (SET NULL) | Reference to Asset (nullable because asset may be deleted after notification sent) |
| `assetName` | String | NOT NULL, MAX 255 | Asset name at time of notification (denormalized for audit trail) |
| `ownerEmail` | String | NOT NULL, MAX 255 | Owner email at time of notification (denormalized for audit trail) |
| `notificationType` | NotificationType | NOT NULL | Type of notification: OUTDATED_LEVEL1, OUTDATED_LEVEL2, NEW_VULNERABILITY |
| `sentAt` | Timestamp | NOT NULL, DEFAULT now() | Timestamp when notification was sent (or attempted) |
| `status` | String | NOT NULL, CHECK (status IN ('SENT', 'FAILED', 'PENDING')) | Delivery status |
| `errorMessage` | String | NULLABLE, MAX 1024 | Error message if status = FAILED |

**Indexes**:
- Primary key on `id`
- Index on `assetId` (for asset-specific audit queries)
- Index on `sentAt` (for time-based filtering)
- Index on `ownerEmail` (for owner-specific audit queries)
- Composite index on `(notificationType, sentAt)` (for notification type filtering with time range)

**Relationships**:
- Many-to-One with Asset (FK: assetId → Asset.id, SET NULL on delete)

**Validation Rules**:
- `assetName` must not be empty
- `ownerEmail` must be valid email format
- `notificationType` must be one of the enum values
- `status` must be SENT, FAILED, or PENDING
- If `status = FAILED`, `errorMessage` should be populated

**State Transitions**: N/A (log entries are immutable after creation)

**Notes**:
- `assetName` and `ownerEmail` are denormalized to preserve audit trail even if asset or UserMapping is deleted
- Retention policy: Keep logs for 1 year (configurable; not implemented in this feature)

---

### 4. NotificationType (Enum)

**Purpose**: Enumeration of notification types for type-safe categorization

**Values**:

| Value | Description |
|-------|-------------|
| `OUTDATED_LEVEL1` | First reminder for outdated asset (professional tone) |
| `OUTDATED_LEVEL2` | Second reminder for outdated asset (urgent tone, sent after 7 days) |
| `NEW_VULNERABILITY` | Notification about new vulnerabilities discovered on user's assets |

**Usage**:
- Stored in `NotificationLog.notificationType`
- Used to select email template (level 1 vs level 2 vs new vuln)
- Used for audit log filtering

---

## Database Schema (SQL)

```sql
-- NotificationPreference table
CREATE TABLE notification_preference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    enable_new_vuln_notifications BOOLEAN NOT NULL DEFAULT FALSE,
    last_vuln_notification_sent_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_notif_pref_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    CONSTRAINT uq_notif_pref_user UNIQUE (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AssetReminderState table
CREATE TABLE asset_reminder_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    level INT NOT NULL CHECK (level IN (1, 2)),
    last_sent_at TIMESTAMP NOT NULL,
    outdated_since TIMESTAMP NOT NULL,
    last_checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reminder_state_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
    CONSTRAINT uq_reminder_state_asset UNIQUE (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_reminder_state_last_sent ON asset_reminder_state(last_sent_at);
CREATE INDEX idx_reminder_state_outdated_since ON asset_reminder_state(outdated_since);

-- NotificationLog table
CREATE TABLE notification_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NULL,
    asset_name VARCHAR(255) NOT NULL,
    owner_email VARCHAR(255) NOT NULL,
    notification_type VARCHAR(50) NOT NULL CHECK (notification_type IN ('OUTDATED_LEVEL1', 'OUTDATED_LEVEL2', 'NEW_VULNERABILITY')),
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL CHECK (status IN ('SENT', 'FAILED', 'PENDING')),
    error_message VARCHAR(1024) NULL,
    CONSTRAINT fk_notif_log_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_notif_log_asset ON notification_log(asset_id);
CREATE INDEX idx_notif_log_sent_at ON notification_log(sent_at);
CREATE INDEX idx_notif_log_owner_email ON notification_log(owner_email);
CREATE INDEX idx_notif_log_type_sent_at ON notification_log(notification_type, sent_at);
```

**Note**: Hibernate auto-migration will generate these tables from JPA entities. The SQL above is for reference only.

---

## Integration with Existing Entities

### User (existing)

**Relationship**: One-to-One with NotificationPreference
- A User MAY have one NotificationPreference
- If no preference exists, default to `enableNewVulnNotifications = false`

**No Schema Changes**: User table is not modified by this feature

### Asset (existing)

**Relationships**:
- One-to-One with AssetReminderState (optional; only exists if asset has been outdated)
- One-to-Many with NotificationLog (one asset can have many log entries)

**No Schema Changes**: Asset table is not modified by this feature

### UserMapping (existing)

**Relationship**: Used for email resolution via join on `Asset.owner = UserMapping.awsAccountId`

**No Schema Changes**: UserMapping table is not modified by this feature

### OutdatedAssetMaterializedView (existing, Feature 034)

**Relationship**: Read-only query source for identifying outdated assets

**No Schema Changes**: Materialized view is not modified by this feature

---

## Data Flow

### Outdated Reminder Workflow

1. CLI command queries `OutdatedAssetMaterializedView` for assets with `totalOverdueCount > 0`
2. For each asset, join with `Asset` and `UserMapping` to get owner email
3. Check `AssetReminderState`:
   - If no state exists: Create with level=1, outdatedSince=now()
   - If state exists AND `lastSentAt.date == today`: Skip (already sent)
   - If state exists AND level=1 AND `outdatedSince >= 7 days ago`: Escalate to level=2
4. Group assets by owner email
5. For each owner, send aggregated email with appropriate template (level 1 or 2)
6. Create `NotificationLog` entry with status=SENT or FAILED
7. Update `AssetReminderState.lastSentAt = now()`

### New Vulnerability Notification Workflow

1. CLI command queries `NotificationPreference` for users with `enableNewVulnNotifications = true`
2. For each user, query `Vulnerability` table for vulnerabilities created since `lastVulnNotificationSentAt`
3. Join with `Asset` to get vulnerabilities for assets owned by this user (via `UserMapping`)
4. Group vulnerabilities by owner email
5. For each owner, send aggregated email with new vulnerability summary
6. Create `NotificationLog` entries for each asset with new vulnerabilities
7. Update `NotificationPreference.lastVulnNotificationSentAt = now()`

### Asset State Reset Workflow

1. When an asset transitions from outdated → up-to-date (determined by absence from `OutdatedAssetMaterializedView`)
2. Delete corresponding `AssetReminderState` record
3. This resets the reminder level so future outdated occurrences start fresh with level 1

---

## Migration Strategy

**Hibernate Auto-Migration**: Enabled via `jpa.default.properties.hibernate.hbm2ddl.auto=update`

**Deployment Steps**:
1. Deploy new code with JPA entities
2. Hibernate auto-creates 3 new tables on first startup
3. No data migration needed (all new tables)
4. No downtime required

**Rollback Plan**:
1. Deploy previous code version
2. Manually drop 3 new tables if needed: `DROP TABLE notification_log, asset_reminder_state, notification_preference;`

**Testing**: Test migration in development environment before production deployment

---

## Performance Considerations

### Query Optimization

- **Indexes**: All foreign keys and frequently queried columns are indexed
- **Joins**: Use single query with JOINs instead of N+1 queries for UserMapping
- **Batch Processing**: Process assets in batches of 1,000 to limit memory usage

### Storage Estimates

- **NotificationPreference**: ~1,000 users × 100 bytes = ~100 KB
- **AssetReminderState**: ~10,000 assets × 150 bytes = ~1.5 MB (at most; only outdated assets)
- **NotificationLog**: ~100 runs/month × 10,000 assets × 200 bytes = ~200 MB/year

**Total Additional Storage**: <300 MB/year (negligible for MariaDB)

### Retention Policy

- **NotificationPreference**: Keep indefinitely (small table)
- **AssetReminderState**: Auto-cleaned when asset becomes up-to-date
- **NotificationLog**: Implement 1-year retention policy (future enhancement; not in this feature)

---

## Validation Rules Summary

| Entity | Field | Validation |
|--------|-------|------------|
| NotificationPreference | userId | Must reference existing User |
| NotificationPreference | enableNewVulnNotifications | Boolean (true/false) |
| AssetReminderState | assetId | Must reference existing Asset |
| AssetReminderState | level | Must be 1 or 2 |
| AssetReminderState | lastSentAt | Must be <= now() |
| AssetReminderState | outdatedSince | Must be <= lastSentAt |
| NotificationLog | assetName | Must not be empty, max 255 chars |
| NotificationLog | ownerEmail | Must be valid email format, max 255 chars |
| NotificationLog | notificationType | Must be OUTDATED_LEVEL1, OUTDATED_LEVEL2, or NEW_VULNERABILITY |
| NotificationLog | status | Must be SENT, FAILED, or PENDING |

---

## Security Considerations

- **RBAC**: NotificationPreference is user-specific (users can only view/update their own)
- **ADMIN Access**: NotificationLog is only accessible to ADMIN role
- **No PII Exposure**: Email addresses in NotificationLog are only visible to ADMIN
- **Audit Trail**: All notification sends are logged (no silent failures)

---

## Future Enhancements (Out of Scope)

- Notification templates for other events (asset created, vulnerability resolved)
- User-configurable notification frequency (daily/weekly digest)
- Notification delivery channels (Slack, Teams, SMS)
- Retention policy automation (auto-delete logs older than 1 year)
- Email bounce handling (mark email addresses as invalid after repeated bounces)
