# Phase 1 Data Model: CLI manage-user-mappings --send-email Option

**Feature**: 085-cli-mappings-email
**Date**: 2026-04-08

## Overview

This feature introduces:

- **One new JPA entity** (`UserMappingStatisticsLog`) and its repository
- **Three new service-layer data classes** (statistics report, send result, recipient projection) — non-persistent
- **Two new controller DTOs** (request, response)
- **One new Flyway migration script**

No changes to existing entities. No changes to the `user_mapping` table or its columns.

---

## 1. Persistent Entity: `UserMappingStatisticsLog`

**Purpose**: One row per `/api/cli/user-mappings/send-statistics-email` invocation (including dry-runs and zero-recipient failures). Used for audit, forensics, and reproducibility of "what did we send on date X with what filters".

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/UserMappingStatisticsLog.kt`

**JPA / Hibernate annotations** (mirrors `AdminSummaryLog.kt:12-52`):

```kotlin
@Entity
@Table(
    name = "user_mapping_statistics_log",
    indexes = [
        Index(name = "idx_ums_log_executed_at", columnList = "executed_at"),
        Index(name = "idx_ums_log_status", columnList = "status")
    ]
)
@Serdeable
data class UserMappingStatisticsLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "executed_at", nullable = false)
    val executedAt: Instant,

    @Column(name = "invoked_by", nullable = false, length = 255)
    val invokedBy: String,                    // username of the CLI caller

    @Column(name = "filter_email", length = 255)
    val filterEmail: String? = null,          // filter parameter echo (nullable)

    @Column(name = "filter_status", length = 20)
    val filterStatus: String? = null,         // "ACTIVE" / "PENDING" / null

    @Column(name = "total_users", nullable = false)
    val totalUsers: Int,                      // distinct user count in the filtered set

    @Column(name = "total_mappings", nullable = false)
    val totalMappings: Int,

    @Column(name = "active_mappings", nullable = false)
    val activeMappings: Int,

    @Column(name = "pending_mappings", nullable = false)
    val pendingMappings: Int,

    @Column(name = "domain_mappings", nullable = false)
    val domainMappings: Int,

    @Column(name = "aws_account_mappings", nullable = false)
    val awsAccountMappings: Int,

    @Column(name = "recipient_count", nullable = false)
    val recipientCount: Int,                  // ADMIN+REPORT users with valid email

    @Column(name = "emails_sent", nullable = false)
    val emailsSent: Int = 0,

    @Column(name = "emails_failed", nullable = false)
    val emailsFailed: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: ExecutionStatus,              // reuse existing enum

    @Column(name = "dry_run", nullable = false)
    val dryRun: Boolean = false
)
```

**Reused**: `com.secman.domain.ExecutionStatus` (existing enum: `SUCCESS`, `PARTIAL_FAILURE`, `FAILURE`, `DRY_RUN` — see `AdminSummaryLog.kt:58-67`).

**Constraints**:
- `executed_at` NOT NULL — every row has a timestamp
- `invoked_by` NOT NULL — accountability
- `status` NOT NULL — clear outcome
- Index on `executed_at` — chronological queries
- Index on `status` — "show all failures" queries

**Lifecycle**: Insert-only. Rows are never updated or deleted by application code. Retention policy is out of scope for this feature (can be added via a separate cleanup job if the table grows).

**Relationships**: None. This is a standalone audit log. It does NOT foreign-key to `user` (`invoked_by` is stored as username text for durability — if the user is later deleted, the audit row survives).

---

## 2. Repository: `UserMappingStatisticsLogRepository`

**Location**: `src/backendng/src/main/kotlin/com/secman/repository/UserMappingStatisticsLogRepository.kt`

**Pattern**: Micronaut Data JPA (mirrors `AdminSummaryLogRepository`).

```kotlin
@Repository
interface UserMappingStatisticsLogRepository : JpaRepository<UserMappingStatisticsLog, Long> {
    fun findByStatus(status: ExecutionStatus): List<UserMappingStatisticsLog>
    fun findTop50ByOrderByExecutedAtDesc(): List<UserMappingStatisticsLog>
}
```

Only two finders are needed for the initial audit surface. More can be added later if a UI emerges.

---

## 3. Service-Layer Data Classes (non-persistent)

**Location**: nested inside `src/backendng/src/main/kotlin/com/secman/service/UserMappingStatisticsService.kt`

### 3.1 `UserMappingStatisticsReport`

Holds the full report payload passed to the email template renderer.

```kotlin
data class UserMappingStatisticsReport(
    val generatedAt: Instant,
    val appliedFilters: Map<String, String>,   // e.g., {"email": "foo@bar", "status": "ACTIVE"}
    val aggregates: Aggregates,
    val users: List<UserMappingEntry>          // per-user detail — resolved from the clarification Q2
)

data class Aggregates(
    val totalUsers: Int,
    val totalMappings: Int,
    val activeMappings: Int,
    val pendingMappings: Int,
    val domainMappings: Int,
    val awsAccountMappings: Int
)

data class UserMappingEntry(
    val email: String,
    val overallStatus: String,       // "ACTIVE" / "PENDING" / "MIXED"
    val domains: List<DomainEntry>,
    val awsAccounts: List<AwsAccountEntry>
)

data class DomainEntry(val domain: String, val status: String)
data class AwsAccountEntry(val awsAccountId: String, val status: String)
```

**Source of truth**: Aggregates are computed in Kotlin from the result of a single `UserMappingRepository` query using the same filter logic as `UserMappingController.kt:99-110`. Per-user grouping mirrors `ListCommand.kt:118-150`.

### 3.2 `UserMappingStatisticsSendResult`

Service-layer return type (mirrors `AdminSummaryService.AdminSummaryResult`).

```kotlin
data class UserMappingStatisticsSendResult(
    val recipientCount: Int,
    val emailsSent: Int,
    val emailsFailed: Int,
    val status: ExecutionStatus,
    val recipients: List<String>,
    val failedRecipients: List<String>,
    val appliedFilters: Map<String, String>
)
```

---

## 4. Controller DTOs

**Location**: nested inside `src/backendng/src/main/kotlin/com/secman/controller/CliController.kt` (follows the existing pattern for other CLI DTOs in that file).

### 4.1 Request: `SendUserMappingStatisticsRequest`

```kotlin
@Serdeable
data class SendUserMappingStatisticsRequest(
    val filterEmail: String? = null,
    val filterStatus: String? = null,    // "ACTIVE" / "PENDING" / null
    val dryRun: Boolean = false,
    val verbose: Boolean = false
)
```

**Validation**:
- `filterStatus`, if non-null, must be one of `ACTIVE`, `PENDING` (case-insensitive). Invalid values → `HTTP 400` with consistent error format.
- `filterEmail`, if non-null, is passed through to the repository filter — no format validation at the endpoint (the repository handles "no match" gracefully by returning an empty list).
- No length limits beyond the existing DB column widths.

### 4.2 Response: `UserMappingStatisticsResultDto`

```kotlin
@Serdeable
data class UserMappingStatisticsResultDto(
    val status: String,                    // ExecutionStatus enum name
    val recipientCount: Int,
    val emailsSent: Int,
    val emailsFailed: Int,
    val recipients: List<String>,
    val failedRecipients: List<String>,
    val appliedFilters: Map<String, String>,
    val aggregates: AggregatesDto          // echoed so the CLI can print a summary
)

@Serdeable
data class AggregatesDto(
    val totalUsers: Int,
    val totalMappings: Int,
    val activeMappings: Int,
    val pendingMappings: Int,
    val domainMappings: Int,
    val awsAccountMappings: Int
)
```

**Why echo aggregates?** The CLI's post-send summary line ("Sent statistics for 42 users / 137 mappings to 3 recipients") needs these numbers. Returning them avoids a second round-trip.

---

## 5. Flyway Migration

**Location**: `src/backendng/src/main/resources/db/migration/V[next]__create_user_mapping_statistics_log.sql`

The exact version number is determined during the tasks phase by inspecting the highest existing migration number. Rough content:

```sql
CREATE TABLE user_mapping_statistics_log (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    executed_at            DATETIME(6)   NOT NULL,
    invoked_by             VARCHAR(255)  NOT NULL,
    filter_email           VARCHAR(255),
    filter_status          VARCHAR(20),
    total_users            INT           NOT NULL,
    total_mappings         INT           NOT NULL,
    active_mappings        INT           NOT NULL,
    pending_mappings       INT           NOT NULL,
    domain_mappings        INT           NOT NULL,
    aws_account_mappings   INT           NOT NULL,
    recipient_count        INT           NOT NULL,
    emails_sent            INT           NOT NULL DEFAULT 0,
    emails_failed          INT           NOT NULL DEFAULT 0,
    status                 VARCHAR(20)   NOT NULL,
    dry_run                BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id)
);

CREATE INDEX idx_ums_log_executed_at ON user_mapping_statistics_log (executed_at);
CREATE INDEX idx_ums_log_status ON user_mapping_statistics_log (status);
```

**Rationale for a Flyway script even though Hibernate auto-migration exists**: Constitution VI mandates Flyway migration scripts. Hibernate will still create the table on startup in dev, but Flyway is the source of truth for production schema history.

---

## 6. Validation Rules (derived from spec FRs)

| Rule | Enforced where | Source |
|---|---|---|
| Invoker must hold ADMIN | Controller `@Secured("ADMIN")` | FR-008 |
| Recipients must hold ADMIN or REPORT | Service (reuses `AdminSummaryService.getAdminRecipients()`) | FR-003, Clarification Q1 |
| Recipient must have non-empty email | Service (filter in `getAdminRecipients`) | FR-003 |
| `filterStatus` must be ACTIVE / PENDING / null | Controller → Service validation layer | FR-004 |
| Email body must include per-user detail | Service renders template from `UserMappingStatisticsReport.users` | FR-005, Clarification Q2 |
| Dry-run must not dispatch | Service branch at top of send method | FR-006 |
| Audit log must be written on every invocation | Service always calls `logExecution(...)` in a `finally` or before return | FR-016, Constitution I |

---

## 7. State Transitions

None. Every entity in this feature is insert-only. The `ExecutionStatus` enum is computed once at the end of the send method and never changes after write.

---

## 8. Open Questions for Tasks Phase

None — all data model decisions are concrete.
