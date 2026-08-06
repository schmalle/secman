# Data Model: Prevent Duplicate Vulnerabilities in CrowdStrike Import

**Feature**: 048-prevent-duplicate-vulnerabilities
**Date**: 2025-11-16

## Overview

This feature uses **existing entities** - no new entities or schema changes required. The duplicate prevention mechanism is built into the import service logic using the transactional replace pattern.

## Existing Entities (No Changes)

### Vulnerability

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Vulnerability.kt`

**Purpose**: Represents a security vulnerability discovered on an asset

**Fields**:
- `id` (Long, PK, auto-generated) - Unique identifier
- `asset` (ManyToOne → Asset, required) - The asset affected by this vulnerability
- `vulnerabilityId` (String, required, max 255) - CVE identifier (e.g., "CVE-2023-0001")
- `cvssSeverity` (String, optional) - Severity level (e.g., "CRITICAL", "HIGH", "MEDIUM", "LOW")
- `vulnerableProductVersions` (String, optional, max 512) - Affected product/version info
- `daysOpen` (String, optional) - How long vulnerability has been open (e.g., "526 days")
- `scanTimestamp` (LocalDateTime, required) - When vulnerability was detected
- `patchPublicationDate` (LocalDateTime, optional) - When patch was published

**Relationships**:
- ManyToOne → Asset (cascade on delete: when asset deleted, vulnerabilities deleted)

**Indexes**:
- Primary key on `id`
- Foreign key index on `asset_id`
- **Note**: No unique constraint on (asset_id, vulnerability_id) because transactional replace pattern doesn't need it

**Lifecycle**:
1. Created during import: `vulnerabilityRepository.saveAll(vulnerabilities)`
2. Deleted during import: `vulnerabilityRepository.deleteByAssetId(asset.id!!)`
3. Never updated - always deleted and recreated

**Duplicate Prevention Role**:
- Delete operation removes ALL vulnerabilities for an asset before insert
- This ensures no duplicate (asset_id, vulnerability_id) combinations exist

### Asset

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**Purpose**: Represents a server or system being monitored for vulnerabilities

**Fields**:
- `id` (Long, PK, auto-generated) - Unique identifier
- `name` (String, required, unique) - Hostname/asset name
- `type` (String, required) - Asset type (e.g., "SERVER")
- `owner` (String, optional) - Asset owner
- `description` (String, optional) - Asset description
- `ip` (String, optional) - IP address
- `groups` (String, optional) - Asset groups
- `cloudAccountId` (String, optional) - AWS account ID
- `cloudInstanceId` (String, optional) - Cloud instance ID
- `adDomain` (String, optional) - Active Directory domain
- `osVersion` (String, optional) - Operating system version
- `lastSeen` (LocalDateTime, required) - Last time asset was seen in import
- `createdAt` (LocalDateTime, auto) - When asset was created
- `updatedAt` (LocalDateTime, auto) - When asset was last updated
- `manualCreator` (User, optional) - User who manually created this asset
- `scanUploader` (User, optional) - User who uploaded scan containing this asset

**Relationships**:
- OneToMany → Vulnerability (cascade on delete)
- ManyToMany → Workgroup
- ManyToOne → User (manualCreator)
- ManyToOne → User (scanUploader)

**Indexes**:
- Primary key on `id`
- Unique index on `name` (hostname must be unique)

**Lifecycle**:
1. Found or created during import: `findOrCreateAsset(batch)`
2. Updated if existing (IP, groups, cloud metadata)
3. Never deleted by import process

**Duplicate Prevention Role**:
- Serves as the anchor for vulnerability deletion (deleteByAssetId)
- Unique hostname constraint prevents duplicate assets
- Transaction boundary ensures atomicity of asset update + vulnerability replace

## Repository Operations

### VulnerabilityRepository

**Location**: `src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt`

**Key Methods**:

```kotlin
interface VulnerabilityRepository : CrudRepository<Vulnerability, Long> {
    // Delete all vulnerabilities for a specific asset
    fun deleteByAssetId(assetId: Long): Int

    // Batch save vulnerabilities
    override fun <S : Vulnerability> saveAll(entities: Iterable<S>): Iterable<S>
}
```

**Transaction Behavior**:
- `deleteByAssetId(assetId)`: Executes single DELETE query, returns count of deleted rows
- `saveAll(entities)`: Batch insert, optimized by Hibernate
- Both operations within same `@Transactional` method → atomic unit of work

### AssetRepository

**Location**: `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`

**Key Methods**:

```kotlin
interface AssetRepository : CrudRepository<Asset, Long> {
    // Find asset by hostname (case-insensitive)
    fun findByNameIgnoreCase(name: String): Asset?
}
```

**Usage in Import**:
- Used to find existing asset before vulnerability import
- If not found, new asset created
- Existing assets updated with latest metadata (IP, groups, etc.)

## Data Flow: Import Process

### Step-by-Step Data Transformations

```
1. CLI Layer (VulnerabilityStorageService)
   Input: Map<hostname, ServerVulnerabilityBatch>
   ├─ Chunk into batches of 50 servers
   ├─ Validate hostname, CVE IDs, severity
   └─ Send HTTP POST to /api/crowdstrike/servers/import

2. Controller Layer (CrowdStrikeController)
   Input: List<CrowdStrikeVulnerabilityBatchDto>
   └─ Delegate to CrowdStrikeVulnerabilityImportService

3. Service Layer (CrowdStrikeVulnerabilityImportService)
   For each server in batch:
     ├─ START TRANSACTION
     ├─ findOrCreateAsset(hostname)
     │   ├─ Query: SELECT * FROM asset WHERE name = ?
     │   ├─ If found: UPDATE asset SET ... WHERE id = ?
     │   └─ If not found: INSERT INTO asset VALUES (...)
     ├─ deleteByAssetId(asset.id)
     │   └─ Execute: DELETE FROM vulnerability WHERE asset_id = ?
     ├─ Filter vulnerabilities (skip if no CVE ID)
     ├─ Create Vulnerability entities from DTOs
     ├─ saveAll(vulnerabilities)
     │   └─ Execute: INSERT INTO vulnerability VALUES (...), (...), ...
     ├─ COMMIT TRANSACTION
     └─ Return ServerImportResult

4. Response
   Output: ImportStatisticsDto
   ├─ serversProcessed: total count
   ├─ serversCreated: new assets
   ├─ serversUpdated: existing assets
   ├─ vulnerabilitiesImported: successfully saved
   └─ vulnerabilitiesSkipped: filtered out
```

### Transaction Boundaries

```
@Transactional boundary per asset:
┌─────────────────────────────────────────────────┐
│ 1. Find/Create Asset                            │
│ 2. DELETE FROM vulnerability WHERE asset_id = ? │
│ 3. INSERT INTO vulnerability VALUES ...         │
└─────────────────────────────────────────────────┘
         ↓ Success → COMMIT
         ↓ Failure → ROLLBACK (no changes persist)
```

**Why This Prevents Duplicates**:
- Within transaction: DELETE removes all old data before INSERT adds new data
- Between transactions: Each import starts fresh (deletes all, inserts all)
- On failure: ROLLBACK ensures partial state never persists

## State Transitions

### Vulnerability State Lifecycle

```
State 1: NOT EXISTS
  ↓ Import with CVE-2023-0001
State 2: EXISTS (asset_id=1, cve=CVE-2023-0001)
  ↓ Import again with CVE-2023-0001
  ├─ DELETE (State 2 → State 1)
  └─ INSERT (State 1 → State 2)
Result: Same final state (idempotent)
```

### Asset State Lifecycle

```
State 1: NOT EXISTS (hostname="server01")
  ↓ First import
State 2: EXISTS (hostname="server01", ip="10.0.0.1")
  ↓ Import with updated IP="10.0.0.2"
State 3: EXISTS (hostname="server01", ip="10.0.0.2")
  ↓ Import with same data
State 3: UNCHANGED (idempotent)
```

## Validation Rules

### Input Validation (CLI Layer)

From `VulnerabilityStorageService.kt` lines 342-396:

1. **Hostname Validation**:
   - Must not be blank
   - Must be ≤ 255 characters
   - Invalid → skip server

2. **CVE ID Validation**:
   - Must not be blank
   - Must be ≤ 255 characters
   - Invalid → skip vulnerability

3. **Severity Validation**:
   - Must not be blank
   - Must be ≤ 50 characters
   - Invalid → skip vulnerability

4. **Affected Product Validation**:
   - Optional field
   - If present, truncate to 512 characters

### Database Constraints

From `Vulnerability` entity annotations:

1. **NOT NULL Constraints**:
   - `asset_id` (foreign key)
   - `vulnerability_id` (CVE)
   - `scan_timestamp`

2. **Foreign Key Constraint**:
   - `asset_id` REFERENCES `asset(id)` ON DELETE CASCADE

3. **Length Constraints**:
   - `vulnerability_id`: VARCHAR(255)
   - `vulnerable_product_versions`: VARCHAR(512)

## Performance Considerations

### Query Performance

1. **DELETE Operation**:
   ```sql
   DELETE FROM vulnerability WHERE asset_id = ?
   ```
   - Uses foreign key index on `asset_id`
   - O(n) where n = vulnerabilities per asset
   - Typical: 10-50 vulnerabilities per asset

2. **INSERT Operation**:
   ```kotlin
   vulnerabilityRepository.saveAll(vulnerabilities)
   ```
   - Hibernate batch insert optimization
   - Single transaction for all inserts
   - Typical: 10-50 inserts per asset

3. **SELECT Operation**:
   ```sql
   SELECT * FROM asset WHERE name = ?
   ```
   - Uses unique index on `name`
   - O(log n) lookup

### Scalability

**Current Performance** (from research.md):
- Target: 1,000 assets with 10,000 vulnerabilities in < 5 minutes
- Batch size: 50 servers per HTTP request
- Transaction per asset: ~20ms average

**Bottlenecks**:
- Network latency (CLI → Backend): Mitigated by batching
- Database writes: Mitigated by batch insert
- Transaction overhead: Acceptable for idempotency guarantee

**No Optimization Needed**: Current implementation meets requirements

## Testing Implications

### Test Data Requirements

1. **Idempotent Import Test**:
   - Create 10 assets with 5 vulnerabilities each
   - Import same data 3 times
   - Verify final count = 50 vulnerabilities (not 150)

2. **Concurrent Import Test**:
   - Create 2 threads
   - Each imports 5 different assets
   - Verify no race conditions, all 10 assets imported correctly

3. **Transaction Rollback Test**:
   - Import asset with 5 vulnerabilities
   - Simulate failure during vulnerability insert
   - Verify rollback: asset exists with 0 vulnerabilities (not partial state)

4. **Edge Case Test**:
   - Import asset with vulnerabilities missing CVE IDs
   - Verify these are skipped, not saved with null CVE

### Database State Verification

Tests must verify:
```sql
-- No duplicate (asset_id, vulnerability_id) combinations
SELECT asset_id, vulnerability_id, COUNT(*)
FROM vulnerability
GROUP BY asset_id, vulnerability_id
HAVING COUNT(*) > 1;
-- Expected: 0 rows

-- Vulnerability count matches imported count
SELECT COUNT(*) FROM vulnerability WHERE asset_id = ?;
-- Expected: count from import data (after filtering)
```

## Summary

| Aspect | Details |
|--------|---------|
| **New Entities** | None - uses existing Vulnerability and Asset |
| **Schema Changes** | None required |
| **Indexes** | Existing indexes sufficient |
| **Constraints** | Existing constraints sufficient (no unique constraint needed) |
| **Transaction Pattern** | Delete + Insert within @Transactional per asset |
| **Duplicate Prevention** | Transactional replace ensures no duplicates |
| **Idempotency** | Guaranteed by delete-then-insert pattern |
| **Performance** | Meets requirements (< 5 min for 1000 assets, 10k vulnerabilities) |
