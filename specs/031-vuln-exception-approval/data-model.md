# Data Model: Vulnerability Exception Request & Approval Workflow

**Feature**: 031-vuln-exception-approval
**Date**: 2025-10-20

This document defines the data entities, relationships, and validation rules for the vulnerability exception request approval workflow.

---

## Entity Overview

```
┌──────────────────────────────┐
│ VulnerabilityExceptionRequest│──┐
│ (NEW)                        │  │
│ - id (PK)                    │  │
│ - status (enum)              │  │
│ - vulnerabilityId (FK)       │  │ Many-to-One
│ - requestedByUserId (FK)     │  │
│ - scope (enum)               │  │
│ - reason                     │  │
│ - expirationDate             │  │
│ - version (optimistic lock)  │  │
└──────────────────────────────┘  │
                │                  │
                │                  │
                ├─────────────────────┐
                │                     │
                ▼                     ▼
┌─────────────────────┐   ┌──────────────────┐
│    Vulnerability    │   │      User        │
│    (EXISTING)       │   │   (EXISTING)     │
│ - id (PK)           │   │ - id (PK)        │
│ - cveId             │   │ - username       │
│ - asset (FK)        │   │ - email          │
│ - severity          │   │ - roles          │
└─────────────────────┘   └──────────────────┘
        │
        │ Many-to-One
        ▼
┌─────────────────────┐
│       Asset         │
│    (EXISTING)       │
│ - id (PK)           │
│ - name              │
│ - ip                │
│ - type              │
└─────────────────────┘

┌────────────────────────────────┐
│ ExceptionRequestAuditLog       │
│ (NEW - Audit Trail)            │
│ - id (PK)                      │
│ - requestId (FK)               │
│ - eventType (enum)             │
│ - oldState, newState           │
│ - actorUsername, actorUserId   │
│ - timestamp                    │
│ - contextData (JSON)           │
└────────────────────────────────┘
```

---

## 1. VulnerabilityExceptionRequest (NEW)

### Purpose
Represents a request to create an exception for an overdue vulnerability. Tracks approval workflow from submission through approval/rejection to expiration.

### Table Name
`vulnerability_exception_request`

### Fields

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT | Primary key |
| `vulnerability_id` | BIGINT | FK, NOT NULL, INDEX | References vulnerability.id |
| `requested_by_user_id` | BIGINT | FK, NOT NULL, INDEX | References users.id (requester) |
| `requested_by_username` | VARCHAR(255) | NOT NULL | Denormalized for audit trail |
| `scope` | VARCHAR(20) | NOT NULL | Enum: SINGLE_VULNERABILITY, CVE_PATTERN |
| `reason` | TEXT | NOT NULL, 50-2048 chars | Business justification |
| `expiration_date` | DATETIME | NOT NULL | When exception expires |
| `status` | VARCHAR(20) | NOT NULL, INDEX | Enum: PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED |
| `auto_approved` | BOOLEAN | NOT NULL, DEFAULT FALSE | True if ADMIN/SECCHAMPION auto-approval |
| `reviewed_by_user_id` | BIGINT | FK, NULLABLE, INDEX | References users.id (reviewer) |
| `reviewed_by_username` | VARCHAR(255) | NULLABLE | Denormalized for audit trail |
| `review_date` | DATETIME | NULLABLE | When reviewed |
| `review_comment` | VARCHAR(1024) | NULLABLE | Reviewer's notes |
| `created_at` | DATETIME | NOT NULL, INDEX | Creation timestamp |
| `updated_at` | DATETIME | NOT NULL | Last update timestamp |
| `version` | BIGINT | NOT NULL, DEFAULT 0 | Optimistic locking version |

### Enums

```kotlin
enum class ExceptionScope {
    SINGLE_VULNERABILITY,  // Exception for this vuln on this asset only
    CVE_PATTERN           // Exception for all vulns with this CVE across assets
}

enum class ExceptionRequestStatus {
    PENDING,    // Awaiting ADMIN/SECCHAMPION approval
    APPROVED,   // Approved and exception created
    REJECTED,   // Rejected, no exception created
    EXPIRED,    // Past expiration date
    CANCELLED   // Cancelled by requester
}
```

### Relationships

- **Many-to-One** → `Vulnerability` (via `vulnerability_id`)
  - ON DELETE: CASCADE (if vulnerability deleted, request becomes orphaned but retained for audit)

- **Many-to-One** → `User` (via `requested_by_user_id`)
  - ON DELETE: SET NULL (if user deleted, preserve username for audit)

- **Many-to-One** → `User` (via `reviewed_by_user_id`)
  - ON DELETE: SET NULL (if reviewer deleted, preserve username for audit)

### Indexes

```sql
CREATE INDEX idx_vuln_req_vulnerability ON vulnerability_exception_request(vulnerability_id);
CREATE INDEX idx_vuln_req_status ON vulnerability_exception_request(status);
CREATE INDEX idx_vuln_req_requester ON vulnerability_exception_request(requested_by_user_id);
CREATE INDEX idx_vuln_req_reviewer ON vulnerability_exception_request(reviewed_by_user_id);
CREATE INDEX idx_vuln_req_created ON vulnerability_exception_request(created_at);
CREATE INDEX idx_vuln_req_expiration ON vulnerability_exception_request(expiration_date);
```

### Validation Rules

- **reason**: 50-2048 characters, plain text only (XSS sanitized)
- **expiration_date**: Must be future date at creation
- **status transitions**: Enforced by state machine
  - PENDING → APPROVED, REJECTED, CANCELLED
  - APPROVED → EXPIRED
  - REJECTED, CANCELLED, EXPIRED → (terminal states)
- **review_comment**: Required when status=REJECTED (10-1024 chars)
- **auto_approved**: True only if requester has ADMIN or SECCHAMPION role

### Unique Constraints

**None** - Multiple requests allowed for same vulnerability (e.g., after previous rejection)

### State Machine

```
    ┌──────────┐
    │ PENDING  │
    └────┬─────┘
         │
    ┌────┴────┬─────────┬──────────┐
    │         │         │          │
    ▼         ▼         ▼          ▼
┌─────────┐ ┌────────┐ ┌────────┐ ┌─────────┐
│APPROVED │ │REJECTED│ │CANCELLED│ │EXPIRED  │
└────┬────┘ └────────┘ └────────┘ └─────────┘
     │         (terminal states)
     │
     ▼
┌─────────┐
│EXPIRED  │
└─────────┘
```

### Lifecycle Timestamps

- **created_at**: Set via @PrePersist (never updated)
- **updated_at**: Set via @PrePersist, updated via @PreUpdate
- **review_date**: Set when status changes to APPROVED/REJECTED

---

## 2. ExceptionRequestAuditLog (NEW)

### Purpose
Permanent audit trail of all exception request lifecycle events for compliance and debugging.

### Table Name
`exception_request_audit`

### Fields

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT | Primary key |
| `request_id` | BIGINT | NOT NULL, INDEX | References vulnerability_exception_request.id |
| `event_type` | VARCHAR(30) | NOT NULL, INDEX | Enum: REQUEST_CREATED, APPROVED, REJECTED, etc. |
| `timestamp` | DATETIME | NOT NULL, INDEX | When event occurred |
| `old_state` | VARCHAR(20) | NULLABLE | Previous status (for transitions) |
| `new_state` | VARCHAR(20) | NOT NULL | New status |
| `actor_username` | VARCHAR(255) | NOT NULL | User who performed action |
| `actor_user_id` | BIGINT | NULLABLE, INDEX | References users.id |
| `context_data` | TEXT | NULLABLE | JSON: reason, comment, reviewer notes |
| `severity` | VARCHAR(10) | NOT NULL, DEFAULT 'INFO' | Enum: INFO, WARN, ERROR |
| `client_ip` | VARCHAR(45) | NULLABLE | Client IP (IPv6 compatible) |

### Enums

```kotlin
enum class AuditEventType {
    REQUEST_CREATED,   // New request submitted
    STATUS_CHANGED,    // Generic status change
    APPROVED,          // Request approved
    REJECTED,          // Request rejected
    CANCELLED,         // Request cancelled
    EXPIRED,           // Request expired
    MODIFIED           // Request metadata modified
}

enum class AuditSeverity {
    INFO,   // Normal operations
    WARN,   // Rejections, security events
    ERROR   // System errors, failures
}
```

### Indexes

```sql
CREATE INDEX idx_audit_request ON exception_request_audit(request_id);
CREATE INDEX idx_audit_timestamp ON exception_request_audit(timestamp);
CREATE INDEX idx_audit_event_type ON exception_request_audit(event_type);
CREATE INDEX idx_audit_actor ON exception_request_audit(actor_user_id);
CREATE INDEX idx_audit_composite ON exception_request_audit(request_id, timestamp, event_type);
```

### Context Data Format (JSON)

```json
{
  "reason": "Legacy system cannot be patched",
  "comment": "Approved due to accepted risk",
  "reviewerNotes": "Compensating controls in place",
  "additionalData": {
    "ticketId": "SEC-1234",
    "approvedBy": "Security Team"
  }
}
```

### Retention Policy

- **Permanent**: No automatic deletion
- **Manual Cleanup**: Retention script for records older than 7 years (compliance requirement)
- **Immutable**: No UPDATE or DELETE operations allowed (except retention cleanup)

---

## 3. Existing Entity Extensions

### VulnerabilityException (EXISTING - Minor Extension)

**No Schema Changes Required** - Link established via business logic

The existing `VulnerabilityException` table remains unchanged. When a request is approved:
- A new `VulnerabilityException` record is created
- The exception's `reason` field stores the request reason
- The exception's `created_by` field stores the reviewer username
- No direct FK relationship (audit trail via `ExceptionRequestAuditLog`)

### Vulnerability (EXISTING - No Changes)

No changes to `Vulnerability` entity. Requests reference vulnerability via FK.

### User (EXISTING - No Changes)

No changes to `User` entity. Requests reference users via FK for requester and reviewer.

### Asset (EXISTING - No Changes)

No changes to `Asset` entity. Assets accessed via vulnerability relationship.

---

## 4. Data Integrity Rules

### Referential Integrity

1. **Vulnerability Deletion**:
   - When vulnerability deleted, set `vulnerability_id` to NULL
   - Preserve request record for audit trail
   - Mark as "Vulnerability No Longer Exists" in UI

2. **User Deletion**:
   - When user deleted, set user_id fields to NULL
   - Preserve username fields for audit trail
   - Mark as "User Account Inactive" in UI

3. **Audit Log Immutability**:
   - Audit logs never deleted (except retention cleanup)
   - No updates allowed after creation
   - Enforced via database triggers or permissions

### Concurrency Control

1. **Optimistic Locking**:
   - `version` field incremented on every update
   - Hibernate throws `OptimisticLockException` on version mismatch
   - Application converts to `ConcurrentApprovalException` with user-friendly message

2. **Transaction Isolation**:
   - Use READ_COMMITTED (database default)
   - No explicit locks required (optimistic locking handles concurrency)

### Business Rules

1. **Duplicate Prevention**:
   - Only one active (PENDING or APPROVED) request allowed per vulnerability
   - Check before creating new request
   - Show error: "This vulnerability already has an active exception"

2. **Status Validation**:
   - Enforce valid state transitions via service layer
   - Throw `IllegalStateException` for invalid transitions
   - Log attempted invalid transitions to audit log

3. **Expiration Enforcement**:
   - Daily scheduled job checks `expiration_date < NOW()` for APPROVED requests
   - Update status to EXPIRED
   - Create audit log entry
   - Send notification to requester

---

## 5. Performance Characteristics

### Expected Data Volumes

- **Requests per Month**: 100-500
- **Requests per Year**: 1,200-6,000
- **Total Requests (5 years)**: ~30,000
- **Audit Logs (5 years)**: ~150,000 (5 events per request average)

### Query Performance

| Query Type | Estimated Rows | Index Used | Expected Time |
|------------|---------------|------------|---------------|
| Get pending requests | 10-50 | idx_vuln_req_status | <10ms |
| Get user's requests | 50-200 | idx_vuln_req_requester | <20ms |
| Get request by ID | 1 | PRIMARY | <5ms |
| Get audit trail | 5-20 | idx_audit_request | <10ms |
| Compliance report | 1,000-5,000 | idx_audit_timestamp | <100ms |

### Storage Estimates

- **VulnerabilityExceptionRequest**: ~500 bytes per record
- **ExceptionRequestAuditLog**: ~200 bytes per record
- **5-Year Storage**: ~500KB requests + ~30MB audit logs = ~30.5MB total

---

## 6. Migration Strategy

### Step 1: Create New Tables

```sql
-- Create exception request table
CREATE TABLE vulnerability_exception_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vulnerability_id BIGINT,
    requested_by_user_id BIGINT,
    requested_by_username VARCHAR(255) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    reason TEXT NOT NULL,
    expiration_date DATETIME NOT NULL,
    status VARCHAR(20) NOT NULL,
    auto_approved BOOLEAN NOT NULL DEFAULT FALSE,
    reviewed_by_user_id BIGINT,
    reviewed_by_username VARCHAR(255),
    review_date DATETIME,
    review_comment VARCHAR(1024),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,

    FOREIGN KEY (vulnerability_id) REFERENCES vulnerability(id) ON DELETE SET NULL,
    FOREIGN KEY (requested_by_user_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (reviewed_by_user_id) REFERENCES users(id) ON DELETE SET NULL
);

-- Create audit log table
CREATE TABLE exception_request_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    old_state VARCHAR(20),
    new_state VARCHAR(20) NOT NULL,
    actor_username VARCHAR(255) NOT NULL,
    actor_user_id BIGINT,
    context_data TEXT,
    severity VARCHAR(10) NOT NULL DEFAULT 'INFO',
    client_ip VARCHAR(45)
);
```

### Step 2: Create Indexes

```sql
-- Request indexes
CREATE INDEX idx_vuln_req_vulnerability ON vulnerability_exception_request(vulnerability_id);
CREATE INDEX idx_vuln_req_status ON vulnerability_exception_request(status);
CREATE INDEX idx_vuln_req_requester ON vulnerability_exception_request(requested_by_user_id);
CREATE INDEX idx_vuln_req_reviewer ON vulnerability_exception_request(reviewed_by_user_id);
CREATE INDEX idx_vuln_req_created ON vulnerability_exception_request(created_at);
CREATE INDEX idx_vuln_req_expiration ON vulnerability_exception_request(expiration_date);

-- Audit indexes
CREATE INDEX idx_audit_request ON exception_request_audit(request_id);
CREATE INDEX idx_audit_timestamp ON exception_request_audit(timestamp);
CREATE INDEX idx_audit_event_type ON exception_request_audit(event_type);
CREATE INDEX idx_audit_actor ON exception_request_audit(actor_user_id);
CREATE INDEX idx_audit_composite ON exception_request_audit(request_id, timestamp, event_type);
```

### Step 3: Validate Migration

- **No Existing Data**: New tables start empty
- **No Schema Changes**: Existing tables unchanged
- **Backward Compatible**: Zero impact on existing features
- **Rollback Strategy**: Simply drop new tables if needed

---

## Summary

This data model provides:

✅ **Complete Audit Trail**: Every state transition logged permanently
✅ **Concurrency Control**: Optimistic locking prevents race conditions
✅ **Referential Integrity**: Foreign keys with proper cascade/set null behavior
✅ **Performance**: Indexed for all common query patterns
✅ **Compliance**: Immutable audit logs, permanent retention
✅ **Scalability**: Handles 30K requests + 150K audit logs with minimal storage
✅ **Data Integrity**: State machine validation, business rule enforcement

The model is designed for simplicity, reliability, and long-term maintainability while supporting all functional requirements from the specification.
