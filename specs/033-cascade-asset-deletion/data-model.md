# Phase 1: Data Model

**Feature**: Cascade Asset Deletion with Related Data
**Date**: 2025-10-24

## Entity Overview

This feature primarily works with existing entities but adds one new entity for audit logging. No schema migrations required for existing entities (Asset, Vulnerability, VulnerabilityException, VulnerabilityExceptionRequest already exist).

##

 Existing Entities (No Changes)

### Asset
**Table**: `asset`
**Purpose**: Primary entity being deleted
**Key Fields**:
- `id` (PK, Long)
- `name` (String, 255, not null)
- `type` (String, not null)
- `ip` (String, nullable)
- `owner` (String, 255, not null)

**Relationships**:
- `vulnerabilities`: OneToMany → Vulnerability (cascade ALL, orphan removal)
- `scanResults`: OneToMany → ScanResult (cascade ALL, orphan removal)
- `workgroups`: ManyToMany → Workgroup

**Cascade Behavior (Current)**:
- Vulnerability: Already cascades (CascadeType.ALL)
- ScanResult: Already cascades (CascadeType.ALL)
- Workgroups: ManyToMany (join table entry removed, workgroup preserved)

**Notes**: Existing cascade for vulnerabilities is sufficient. This feature adds service-layer cascade for exceptions and requests.

---

### Vulnerability
**Table**: `vulnerability`
**Purpose**: Related entity that must be cascade deleted
**Key Fields**:
- `id` (PK, Long)
- `asset`: ManyToOne → Asset (FK, cascade delete via parent)
- `vulnerabilityId` (String, CVE ID)
- `cvssSeverity` (String)
- `vulnerableProductVersions` (String, nullable)
- `daysOpen` (Integer)

**Relationships**:
- `asset`: ManyToOne → Asset (FK asset_id)
- Referenced by VulnerabilityExceptionRequest (FK vulnerability_id, ON DELETE SET NULL)

**Cascade Impact**:
- When Asset deleted → Vulnerability deleted (existing cascade)
- When Vulnerability deleted → VulnerabilityExceptionRequests have FK set to NULL (current behavior, will be enhanced to cascade delete in service layer)

**Indexes**: `asset_id`, `(asset_id, scan_timestamp)`

---

### VulnerabilityException
**Table**: `vulnerability_exception`
**Purpose**: Exception rules that may reference assets (ASSET-type only)
**Key Fields**:
- `id` (PK, Long)
- `exceptionType` (Enum: IP, PRODUCT, ASSET)
- `targetValue` (String, 512)
- `assetId` (Long, nullable) - NOT a FK, just reference for ASSET-type
- `expirationDate` (LocalDateTime, nullable)
- `reason` (String, 1024)

**Relationships**:
- `assetId`: NOT a foreign key, just Long reference

**Cascade Behavior**:
- **ASSET-type exceptions**: `assetId` matches deleted asset → DELETE via service layer
- **IP-type exceptions**: `targetValue` matches asset IP → PRESERVE (global rule)
- **PRODUCT-type exceptions**: `targetValue` matches vulnerability product → PRESERVE (global rule)

**Indexes**: `exception_type`, `expiration_date`, `asset_id`

**Notes**: Manual deletion required because assetId is not FK. Service layer must query and delete where `exceptionType = ASSET AND assetId = ?`.

---

### VulnerabilityExceptionRequest
**Table**: `vulnerability_exception_request`
**Purpose**: Request records that reference vulnerabilities (which belong to assets)
**Key Fields**:
- `id` (PK, Long)
- `vulnerability`: ManyToOne → Vulnerability (FK, nullable, ON DELETE SET NULL currently)
- `requestedByUser`: ManyToOne → User (FK, nullable)
- `scope` (Enum: SINGLE_VULNERABILITY, CVE_PATTERN)
- `status` (Enum: PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED)
- `reason` (String, 50-2048)
- `expirationDate` (LocalDateTime)

**Relationships**:
- `vulnerability`: ManyToOne → Vulnerability (FK vulnerability_id, ON DELETE SET NULL)
- `requestedByUser`: ManyToOne → User (FK requested_by_user_id)
- `reviewedByUser`: ManyToOne → User (FK reviewed_by_user_id, nullable)

**Cascade Behavior (Current)**:
- When Vulnerability deleted → `vulnerability` field set to NULL (ON DELETE SET NULL)
- Request preserved for audit purposes

**Cascade Behavior (Enhanced)**:
- Service layer will manually delete exception requests when asset deleted
- Query: `DELETE FROM vulnerability_exception_request WHERE vulnerability_id IN (SELECT id FROM vulnerability WHERE asset_id = ?)`

**Indexes**: `vulnerability_id`, `status`, `requested_by_user_id`, `reviewed_by_user_id`

**Notes**: Current FK constraint uses SET NULL for audit preservation. This feature changes behavior via service-layer deletion (not FK change).

---

## New Entity

### AssetDeletionAuditLog
**Table**: `asset_deletion_audit_log`
**Purpose**: Immutable audit trail for all cascade deletion operations
**Source**: NEW entity for Feature 033

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | Long | PK, auto-increment | Unique identifier |
| `assetId` | Long | NOT NULL, indexed | ID of deleted asset (preserved after deletion) |
| `assetName` | String(255) | NOT NULL | Name of deleted asset (preserved) |
| `deletedByUser` | String(255) | NOT NULL, indexed | Username who performed deletion |
| `deletionTimestamp` | LocalDateTime | NOT NULL, indexed | When deletion occurred |
| `vulnerabilitiesCount` | Integer | NOT NULL | Number of vulnerabilities deleted |
| `assetExceptionsCount` | Integer | NOT NULL | Number of ASSET-type exceptions deleted |
| `exceptionRequestsCount` | Integer | NOT NULL | Number of exception requests deleted |
| `deletedVulnerabilityIds` | JSON | NOT NULL | Array of vulnerability IDs deleted |
| `deletedExceptionIds` | JSON | NOT NULL | Array of exception IDs deleted |
| `deletedRequestIds` | JSON | NOT NULL | Array of request IDs deleted |
| `operationType` | Enum(STRING) | NOT NULL | SINGLE or BULK |
| `bulkOperationId` | String(36) | Nullable, indexed | UUID for bulk operations (correlates multiple deletions) |

**Relationships**: None (standalone audit table)

**Indexes**:
- PRIMARY KEY (`id`)
- INDEX (`asset_id`)
- INDEX (`deleted_by_user`)
- INDEX (`deletion_timestamp`)
- INDEX (`bulk_operation_id`) - for querying all deletions in single bulk operation

**Lifecycle**: INSERT only, never UPDATE or DELETE (immutable audit trail)

**JSON Storage Example**:
```json
{
  "deletedVulnerabilityIds": [12345, 12346, 12347],
  "deletedExceptionIds": [501, 502],
  "deletedRequestIds": [7001, 7002, 7003]
}
```

**Storage Considerations**:
- JSON columns supported in MariaDB 10.2.7+
- Use Hibernate `@Type(JsonType::class)` for mapping List<Long> to JSON
- Max JSON column size: 1GB (far exceeds needs, max ~1000 IDs per deletion)

**Query Patterns**:
- Audit by asset: `SELECT * FROM asset_deletion_audit_log WHERE asset_id = ?`
- Audit by user: `SELECT * FROM asset_deletion_audit_log WHERE deleted_by_user = ? ORDER BY deletion_timestamp DESC`
- Bulk operation details: `SELECT * FROM asset_deletion_audit_log WHERE bulk_operation_id = ?`
- Recent deletions: `SELECT * FROM asset_deletion_audit_log WHERE deletion_timestamp > ? ORDER BY deletion_timestamp DESC LIMIT 100`

---

## Data Transfer Objects (DTOs)

### CascadeDeleteSummaryDto
**Purpose**: Pre-flight count summary for UI warning
**Fields**:
- `assetId`: Long
- `assetName`: String
- `vulnerabilitiesCount`: Int
- `assetExceptionsCount`: Int
- `exceptionRequestsCount`: Int
- `estimatedDurationSeconds`: Int
- `exceedsTimeout`: Boolean (true if >60s)

**Usage**: Returned by `GET /api/assets/{id}/cascade-summary` before deletion

---

### DeletionErrorDto
**Purpose**: Structured error response for failed deletions
**Fields**:
- `errorType`: String ("LOCKED", "TIMEOUT", "CONSTRAINT_VIOLATION", "INTERNAL_ERROR")
- `assetId`: Long
- `assetName`: String
- `cause`: String (user-friendly description)
- `suggestedAction`: String (what user should do next)
- `technicalDetails`: String (for ADMIN users only, optional)

**Usage**: Returned in HTTP 409, 422, 500 error responses

---

### BulkDeleteProgressDto
**Purpose**: Real-time progress update for bulk operations (SSE stream)
**Fields**:
- `total`: Int (total assets to delete)
- `completed`: Int (assets deleted so far)
- `currentAssetId`: Long (asset being processed)
- `currentAssetName`: String
- `status`: String ("PROCESSING", "SUCCESS", "FAILED")
- `error`: String? (present only if status=FAILED)

**Usage**: Streamed via SSE in `DELETE /api/assets/bulk/stream`

---

### CascadeDeletionResultDto
**Purpose**: Final result of single asset cascade deletion
**Fields**:
- `assetId`: Long
- `assetName`: String
- `deletedVulnerabilities`: Int
- `deletedExceptions`: Int
- `deletedRequests`: Int
- `auditLogId`: Long (ID of created audit log entry)

**Usage**: Returned by `DELETE /api/assets/{id}` on success

---

## Validation Rules

### Asset Deletion Pre-Flight
1. Asset must exist: Query `SELECT id FROM asset WHERE id = ?`
2. Asset must not be locked: Pessimistic lock acquisition (SELECT FOR UPDATE)
3. Count related records:
   - Vulnerabilities: `SELECT COUNT(*) FROM vulnerability WHERE asset_id = ?`
   - ASSET exceptions: `SELECT COUNT(*) FROM vulnerability_exception WHERE exception_type = 'ASSET' AND asset_id = ?`
   - Exception requests: `SELECT COUNT(*) FROM vulnerability_exception_request WHERE vulnerability_id IN (SELECT id FROM vulnerability WHERE asset_id = ?)`
4. Estimate duration: `totalRecords / 100 + 1` seconds
5. Warn if >60 seconds: Set `exceedsTimeout = true` in summary DTO

### Bulk Deletion Pre-Flight
1. All asset IDs must exist: `SELECT id FROM asset WHERE id IN (?)`
2. All assets must be accessible by user (workgroup check or ownership)
3. Transaction timeout: Estimate total time for all assets, error if >600 seconds (10 minutes max)
4. Concurrency check: No other bulk deletion in progress (AtomicBoolean semaphore)

### Audit Log Constraints
1. `assetId` must be positive
2. `deletedByUser` must not be empty
3. `deletionTimestamp` must be in past or present (not future)
4. Counts must be non-negative
5. JSON arrays must be valid JSON (Hibernate validates on persist)
6. `bulkOperationId` must be valid UUID if present

---

## State Transitions

### Asset Deletion Lifecycle

```
┌─────────────────┐
│ Asset Exists    │
│ (In DB)         │
└────────┬────────┘
         │
         │ DELETE request
         │ (ADMIN user)
         ▼
┌─────────────────┐
│ Pre-Flight      │
│ Validation      │◄──── Warn if >60s
└────────┬────────┘
         │
         │ User confirms
         │
         ▼
┌─────────────────┐
│ Acquire         │
│ Pessimistic Lock│◄──── Blocks concurrent deletes
└────────┬────────┘
         │
         │ Lock acquired
         │
         ▼
┌─────────────────┐
│ Cascade Delete  │
│ (4 steps)       │
└────────┬────────┘
         │
         ├──► 1. Delete VulnerabilityExceptionRequests
         ├──► 2. Delete VulnerabilityExceptions (ASSET-type)
         ├──► 3. Delete Vulnerabilities (existing cascade)
         └──► 4. Delete Asset
         │
         │ All deletes succeed
         │
         ▼
┌─────────────────┐
│ Create Audit Log│
│ (async)         │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Commit TX       │
│ Release Lock    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Asset Deleted   │
│ (Audit preserved│
└─────────────────┘


Error Paths:
─────────────
Lock timeout ──────► 409 Conflict (DeletionErrorDto)
TX timeout ────────► 422 Unprocessable (DeletionErrorDto)
Constraint ────────► 500 Internal Error (DeletionErrorDto)
```

### Bulk Deletion Lifecycle

```
┌─────────────────┐
│ Bulk Request    │
│ (List<AssetId>) │
└────────┬────────┘
         │
         │ Sequential processing
         │
         ▼
    ╔════════════╗
    ║  For Each  ║
    ║   Asset    ║
    ╚════╤═══════╝
         │
         ├──► Stream Progress (SSE): "1/100 completed"
         │
         ├──► Delete Asset (same as single deletion)
         │    ├─ Success: Stream progress, continue
         │    └─ Failure: Stream error, ROLLBACK ALL, abort
         │
         │ All assets deleted
         │
         ▼
┌─────────────────┐
│ Commit TX       │
│ Close SSE       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ All Deleted     │
│ (Audit for each)│
└─────────────────┘
```

**Key Invariants**:
1. Asset deletion is atomic (all-or-nothing)
2. Bulk deletion is atomic across all assets (one failure = rollback all)
3. Audit log created for every deletion (even if TX rolls back - separate async TX)
4. Lock held throughout cascade operation
5. IP/PRODUCT exceptions never deleted (only ASSET-type)

---

## Performance Considerations

### Index Usage
- `asset_id` indexes on vulnerability, vulnerability_exception, vulnerability_exception_request tables ensure fast cascade queries
- `exception_type` index on vulnerability_exception ensures fast ASSET-type filtering
- Composite index `(asset_id, scan_timestamp)` on vulnerability not used here (no timestamp filtering)

### Query Optimization
- Use `deleteByIdIn(List<Long>)` for batch deletion (single DELETE query per entity type)
- Avoid `deleteAll(List<Entity>)` which generates N individual DELETE statements
- COUNT queries use indexes (no table scans)

### Transaction Management
- Keep TX scope tight: lock → delete → audit → commit
- Async audit log insert uses separate TX (@Async + @Transactional(propagation=REQUIRES_NEW))
- No external API calls within TX (no email notifications during deletion)

### Scaling Limits
- Max 1000 vulnerabilities per asset × 100 deletion rate = 10 seconds per asset
- Bulk of 100 assets × 10 seconds = 1000 seconds (exceeds timeout)
- Mitigation: Pre-flight warning prevents timeout, user can delete in smaller batches

---

## Migration Notes

**No schema migrations required** for existing entities. New table creation only:

```sql
CREATE TABLE asset_deletion_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    asset_name VARCHAR(255) NOT NULL,
    deleted_by_user VARCHAR(255) NOT NULL,
    deletion_timestamp DATETIME NOT NULL,
    vulnerabilities_count INT NOT NULL,
    asset_exceptions_count INT NOT NULL,
    exception_requests_count INT NOT NULL,
    deleted_vulnerability_ids JSON NOT NULL,
    deleted_exception_ids JSON NOT NULL,
    deleted_request_ids JSON NOT NULL,
    operation_type VARCHAR(20) NOT NULL,
    bulk_operation_id VARCHAR(36),
    INDEX idx_audit_asset_id (asset_id),
    INDEX idx_audit_user (deleted_by_user),
    INDEX idx_audit_timestamp (deletion_timestamp),
    INDEX idx_audit_bulk_op (bulk_operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

**Hibernate auto-migration** (ddl-auto=update) will create this table automatically.
