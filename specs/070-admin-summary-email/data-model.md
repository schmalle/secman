# Data Model: Admin Summary Email

**Feature**: 070-admin-summary-email
**Date**: 2026-01-27

## New Entities

### AdminSummaryLog

Execution log for admin summary email sends. One record per CLI execution.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | PK, auto-generated | Unique identifier |
| executedAt | Instant | NOT NULL | Timestamp of execution start |
| recipientCount | Int | NOT NULL | Number of ADMIN users targeted |
| userCount | Long | NOT NULL | Total users statistic at time of send |
| vulnerabilityCount | Long | NOT NULL | Total vulnerabilities at time of send |
| assetCount | Long | NOT NULL | Total assets at time of send |
| emailsSent | Int | NOT NULL, default 0 | Number of successfully sent emails |
| emailsFailed | Int | NOT NULL, default 0 | Number of failed email sends |
| status | ExecutionStatus | NOT NULL | Overall execution status |
| dryRun | Boolean | NOT NULL, default false | Whether this was a dry run (no emails sent) |

**Enum: ExecutionStatus**
- `SUCCESS` - All emails sent successfully
- `PARTIAL_FAILURE` - Some emails failed, some succeeded
- `FAILURE` - All emails failed or no recipients found
- `DRY_RUN` - Dry run mode, no emails sent

**Indexes**:
- `idx_admin_summary_log_executed_at` on `executedAt` (query recent executions)

## Existing Entities Used (Read-Only)

### User
- Query: `findByRolesContaining(User.Role.ADMIN)` for recipients
- Query: `count()` for total user statistic

### Vulnerability
- Query: `count()` for total vulnerability statistic

### Asset
- Query: `count()` for total asset statistic

### EmailConfig
- Used by EmailService to get SMTP configuration

## Entity Relationships

```
AdminSummaryLog (new)
    └── No direct relationships (standalone audit log)

User ←── query ←── AdminSummaryService
    └── findByRolesContaining(ADMIN)
    └── count()

Vulnerability ←── query ←── AdminSummaryService
    └── count()

Asset ←── query ←── AdminSummaryService
    └── count()
```

## Database Table

```sql
CREATE TABLE admin_summary_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    executed_at TIMESTAMP NOT NULL,
    recipient_count INT NOT NULL,
    user_count BIGINT NOT NULL,
    vulnerability_count BIGINT NOT NULL,
    asset_count BIGINT NOT NULL,
    emails_sent INT NOT NULL DEFAULT 0,
    emails_failed INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_admin_summary_log_executed_at (executed_at)
);
```

*Note: Table will be auto-created by Hibernate based on entity annotations.*
