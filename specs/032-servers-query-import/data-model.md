# Data Model: Servers Query Import

**Feature**: 032-servers-query-import
**Date**: 2025-10-21

## Overview

This feature reuses existing `Asset` and `Vulnerability` entities without schema changes. New DTOs are introduced for API communication between CLI and backend.

## Existing Entities (Reused)

### Asset

**Table**: `asset`
**Purpose**: Represents a server in the infrastructure

**Fields**:
| Field | Type | Nullable | Default | Notes |
|-------|------|----------|---------|-------|
| id | Long | No | Auto | Primary key |
| name | String(255) | No | - | Server hostname (unique identifier for this feature) |
| type | String | No | - | Set to "SERVER" for CrowdStrike imports |
| ip | String | Yes | null | IP address if available from CrowdStrike |
| ipNumeric | Long | Yes | null | Computed from IP for range queries |
| owner | String(255) | No | - | Set to "CrowdStrike Import" for this feature |
| description | String(1024) | Yes | null | Optional description |
| createdAt | LocalDateTime | No | Auto | Record creation timestamp |
| updatedAt | LocalDateTime | No | Auto | Last update timestamp |
| lastSeen | LocalDateTime | Yes | null | Scan timestamp (set to import time) |
| groups | String(512) | Yes | null | Comma-separated group names from CrowdStrike |
| cloudAccountId | String(255) | Yes | null | AWS/cloud account ID from CrowdStrike |
| cloudInstanceId | String(255) | Yes | null | Cloud instance ID from CrowdStrike |
| adDomain | String(255) | Yes | null | Active Directory domain from CrowdStrike |
| osVersion | String(255) | Yes | null | OS version from CrowdStrike |
| workgroups | ManyToMany | - | Empty set | NO automatic assignment per research decision |
| manualCreator | ManyToOne User | Yes | null | Not applicable for CLI imports |
| scanUploader | ManyToOne User | Yes | null | Not applicable for CLI imports |

**Indexes**:
- `idx_asset_ip_numeric` on `ip_numeric`
- No new indexes required

**Behavior**:
- **Duplicate detection**: Exact match on `name` field (case-sensitive)
- **Metadata preservation**: When asset exists, preserve existing `owner`, `description`, `workgroups`, `manualCreator`, `scanUploader`
- **Metadata update**: Update `groups`, `cloudAccountId`, `cloudInstanceId`, `adDomain`, `osVersion` from CrowdStrike response
- **Timestamp update**: Update `updatedAt` and `lastSeen` on every import

---

### Vulnerability

**Table**: `vulnerability`
**Purpose**: Represents a security vulnerability discovered on a server

**Fields**:
| Field | Type | Nullable | Default | Notes |
|-------|------|----------|---------|-------|
| id | Long | No | Auto | Primary key |
| asset | ManyToOne Asset | No | - | Foreign key to asset table |
| vulnerabilityId | String(255) | Yes | null | CVE identifier (e.g., "CVE-2024-1234") |
| cvssSeverity | String(50) | Yes | null | Severity level (HIGH, CRITICAL) |
| vulnerableProductVersions | String(512) | Yes | null | Affected product/version info |
| daysOpen | String(50) | Yes | null | Text representation (e.g., "58 days") |
| scanTimestamp | LocalDateTime | No | - | Import timestamp (when data retrieved from CrowdStrike) |
| createdAt | LocalDateTime | No | Auto | Record creation timestamp |

**Indexes**:
- `idx_vulnerability_asset` on `asset_id`
- `idx_vulnerability_asset_scan` on `(asset_id, scan_timestamp)`
- `idx_vulnerability_severity` on `cvss_severity`
- No new indexes required

**Behavior**:
- **Replacement pattern**: All existing vulnerabilities for an asset are DELETED before importing new ones
- **CVE validation**: Vulnerabilities without CVE ID are SKIPPED (not imported)
- **Duplicate handling**: Multiple vulnerabilities with same CVE for same asset are allowed (historical tracking)
- **Transaction scope**: Delete + insert wrapped in single transaction per asset

---

## New DTOs

### CrowdStrikeVulnerabilityBatchDto

**Purpose**: Request DTO for `/api/crowdstrike/vulnerabilities/save` endpoint
**Location**: `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeVulnerabilityBatchDto.kt`

```kotlin
@Serdeable
data class CrowdStrikeVulnerabilityBatchDto(
    @field:NotBlank
    @field:Size(min = 1, max = 255)
    val hostname: String,

    @field:Size(max = 512)
    val groups: String?,

    @field:Size(max = 255)
    val cloudAccountId: String?,

    @field:Size(max = 255)
    val cloudInstanceId: String?,

    @field:Size(max = 255)
    val adDomain: String?,

    @field:Size(max = 255)
    val osVersion: String?,

    @field:Size(max = 255)
    val ip: String?,

    @field:NotNull
    @field:Valid
    val vulnerabilities: List<VulnerabilityDto>
)

@Serdeable
data class VulnerabilityDto(
    @field:NotBlank
    @field:Size(max = 255)
    val cveId: String,

    @field:NotBlank
    @field:Size(max = 50)
    val severity: String,

    @field:Size(max = 512)
    val affectedProduct: String?,

    @field:Min(0)
    val daysOpen: Int
)
```

**Validation Rules**:
- `hostname`: Required, 1-255 chars
- `vulnerabilities`: Required, non-null array
- `cveId`: Required per vulnerability (empty CVE IDs filtered before DTO creation)
- `severity`: Required, must be HIGH or CRITICAL
- `daysOpen`: Required, non-negative integer

---

### ImportStatisticsDto

**Purpose**: Response DTO for `/api/crowdstrike/vulnerabilities/save` endpoint
**Location**: `src/backendng/src/main/kotlin/com/secman/dto/ImportStatisticsDto.kt`

```kotlin
@Serdeable
data class ImportStatisticsDto(
    val serversProcessed: Int,
    val serversCreated: Int,
    val serversUpdated: Int,
    val vulnerabilitiesImported: Int,
    val vulnerabilitiesSkipped: Int,
    val errors: List<String>
)
```

**Field Semantics**:
- `serversProcessed`: Total servers in request (serversCreated + serversUpdated + error count)
- `serversCreated`: New Asset records created
- `serversUpdated`: Existing Asset records reused/updated
- `vulnerabilitiesImported`: Total Vulnerability records created (across all servers)
- `vulnerabilitiesSkipped`: Count of vulnerabilities without CVE ID (filtered before backend call)
- `errors`: List of error messages for failed server imports (transaction rollbacks)

---

## Data Flow

### Import Flow (CLI → Backend)

```
1. CLI queries CrowdStrike API
   ↓
2. CLI filters vulnerabilities (skip missing CVE IDs)
   ↓
3. CLI groups vulnerabilities by server hostname
   ↓
4. CLI sends POST /api/crowdstrike/vulnerabilities/save
   ↓
5. Backend validates CrowdStrikeVulnerabilityBatchDto
   ↓
6. For each server in batch:
   a. Find or create Asset by hostname
   b. @Transactional: delete old vulnerabilities + create new ones
   c. Collect statistics
   ↓
7. Backend returns ImportStatisticsDto
   ↓
8. CLI displays formatted statistics
```

### Transaction Boundaries

```
Controller: No transaction
    ↓
Service: @Transactional per server
    ├─ Delete vulnerabilities for asset_id
    ├─ Create new vulnerability records
    └─ Rollback on exception (atomicity)
```

### Error Handling

- **Validation errors**: HTTP 400, rejected before processing
- **Transaction rollback**: Server skipped, error added to `errors` list, statistics exclude failed server
- **Partial success**: Response includes both successes and errors

---

## Database Operations

### Find or Create Asset

```sql
-- Check if asset exists
SELECT * FROM asset WHERE name = ?

-- If exists: UPDATE
UPDATE asset
SET groups = ?, cloud_account_id = ?, cloud_instance_id = ?,
    ad_domain = ?, os_version = ?, ip = ?, ip_numeric = ?,
    last_seen = ?, updated_at = ?
WHERE id = ?

-- If not exists: INSERT
INSERT INTO asset (name, type, owner, groups, cloud_account_id,
                   cloud_instance_id, ad_domain, os_version, ip,
                   ip_numeric, last_seen, created_at, updated_at)
VALUES (?, 'SERVER', 'CrowdStrike Import', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
```

### Replace Vulnerabilities (Transactional)

```sql
-- BEGIN TRANSACTION

-- 1. Delete old vulnerabilities
DELETE FROM vulnerability WHERE asset_id = ?

-- 2. Insert new vulnerabilities (batch)
INSERT INTO vulnerability (asset_id, vulnerability_id, cvss_severity,
                           vulnerable_product_versions, days_open,
                           scan_timestamp, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?, ?), ...

-- COMMIT (or ROLLBACK on exception)
```

---

## Constraints and Invariants

### Data Integrity

1. **Asset uniqueness**: `name` field must be unique (enforced by application logic, not DB constraint)
2. **Vulnerability-Asset relationship**: Cascade delete (when Asset deleted, all Vulnerabilities deleted)
3. **Transaction atomicity**: Delete + insert must both succeed or both fail per asset
4. **CVE ID requirement**: Only vulnerabilities with non-empty CVE ID are imported

### Performance Considerations

1. **Batch size**: Expected 5-50 vulnerabilities per server (manageable transaction size)
2. **Delete performance**: Indexed on `asset_id` (fast DELETE WHERE asset_id = ?)
3. **Insert performance**: Batch insert via `saveAll()` (single SQL statement)
4. **Concurrency**: Per-server transactions reduce lock contention

---

## Schema Changes

**Required**: NONE

**Rationale**: Existing schema fully supports this feature. All required fields, indexes, and relationships already exist from previous features (Feature 003: Vulnerability Management, Feature 008: Workgroup-Based Access Control).

---

## Migration Plan

No database migration required. This feature is a pure application-layer enhancement reusing existing schema.
