# CrowdStrike Vulnerability Import

**Feature**: 048-prevent-duplicate-vulnerabilities
**Service**: `CrowdStrikeVulnerabilityImportService`
**Location**: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt`

This document explains how the CrowdStrike vulnerability import system prevents duplicate entries and ensures data consistency.

---

## Duplicate Prevention Mechanism

The import service uses a **transactional replace pattern** to prevent duplicate vulnerability entries. This approach ensures that each (Asset, CVE) combination exists exactly once in the database.

### How It Works

For each server in an import batch, the service performs the following operations within a single transaction:

```kotlin
@Transactional
open fun importVulnerabilitiesForServer(batch: CrowdStrikeVulnerabilityBatchDto): ServerImportResult {
    // 1. Find or create the asset
    val (asset, isNewAsset) = findOrCreateAsset(batch)

    // 2. DELETE all existing vulnerabilities for this asset
    val deletedCount = vulnerabilityRepository.deleteByAssetId(asset.id!!)

    // 3. INSERT new vulnerabilities from the import batch
    val vulnerabilities = vulnsWithCve.map { vulnDto ->
        Vulnerability(
            asset = asset,
            vulnerabilityId = vulnDto.cveId,
            // ... other fields
        )
    }
    vulnerabilityRepository.saveAll(vulnerabilities)

    return ServerImportResult(...)
}
```

### Key Guarantees

1. **Idempotency**: Running the same import multiple times produces identical database state
   - Import at T1 → 5 vulnerabilities
   - Import at T2 (same data) → Still 5 vulnerabilities (not 10)

2. **No Duplicates**: Each (Asset, CVE) combination exists exactly once
   - Asset A + CVE-2023-1234 → 1 record
   - Asset B + CVE-2023-1234 → 1 record (different asset, allowed)
   - Asset A + CVE-2023-1234 (imported again) → Still 1 record (replaced)

3. **Atomicity**: Transaction ensures all-or-nothing behavior
   - If delete succeeds but insert fails → transaction rolls back
   - If any error occurs → database remains unchanged
   - Per-server isolation: Failure on server A doesn't affect server B

4. **Remediation Tracking**: Missing CVEs indicate patched vulnerabilities
   - Initial import: CVE-2023-0001 through CVE-2023-0010 (10 vulnerabilities)
   - Later import: CVE-2023-0001 through CVE-2023-0007 (7 vulnerabilities)
   - Result: CVE-2023-0008, CVE-2023-0009, CVE-2023-0010 are removed (remediated)

---

## Why Transactional Replace?

The transactional replace pattern was chosen over alternative approaches for several reasons:

### Comparison with Alternatives

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **Transactional Replace** (chosen) | Simple logic, accurate remediation, bulk performance, clean state | Deletes then recreates records | ✅ **Selected** |
| **Upsert (Update or Insert)** | Preserves record IDs | Complex merge logic, slower individual operations, hard to detect remediation | ❌ Not chosen |
| **Soft Delete** | Keeps history | Database bloat, complex queries, stale data issues | ❌ Not chosen |
| **Differential Sync** | Only changes what's needed | Very complex, error-prone, doesn't guarantee consistency | ❌ Not chosen |

### Benefits of Transactional Replace

1. **Simpler Logic**: No need to compare existing vs new records
   - No complex UPDATE/INSERT detection
   - No need to identify which records to keep vs delete
   - Straightforward: delete all, insert new

2. **Accurate Remediation**: Missing CVEs clearly indicate fixed vulnerabilities
   - Import 1: CVE-A, CVE-B, CVE-C
   - Import 2: CVE-A, CVE-C
   - Result: CVE-B removed → vulnerability was patched

3. **Better Performance**: Bulk operations are faster than individual upserts
   - Single `DELETE FROM vulnerabilities WHERE asset_id = ?`
   - Batch `INSERT INTO vulnerabilities VALUES (...)` for all new records
   - No need to SELECT existing records and compare

4. **Cleaner State**: No orphaned or inconsistent records
   - Database always reflects latest CrowdStrike data
   - No partial updates leaving stale data
   - No need for cleanup jobs

---

## Critical Implementation Details

### JPA Cascade Configuration

**IMPORTANT**: The `Asset.vulnerabilities` relationship **MUST NOT** use JPA cascade operations.

#### The Problem

If the Asset entity uses `cascade = [CascadeType.ALL]` or `orphanRemoval = true` on the vulnerabilities relationship, it creates a critical conflict with the transactional replace pattern:

```kotlin
// ❌ WRONG - Causes 99% data loss
@OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true)
var vulnerabilities: MutableList<Vulnerability> = mutableListOf()
```

**What Happens:**
1. Import service calls `vulnerabilityRepository.deleteByAssetId(asset.id)` → Deletes old vulnerabilities
2. Import service calls `vulnerabilityRepository.saveAll(vulnerabilities)` → Inserts new vulnerabilities
3. Transaction commits
4. JPA cascade logic sees "orphaned" vulnerabilities (not in asset.vulnerabilities collection)
5. JPA cascade-deletes the newly inserted vulnerabilities
6. **Result**: 99% data loss (e.g., 166,812 imported → 1,819 retained)

#### The Solution

Remove cascade configuration and use explicit manual deletion:

```kotlin
// ✅ CORRECT - Manual cascade handling
@OneToMany(mappedBy = "asset", fetch = FetchType.LAZY)
var vulnerabilities: MutableList<Vulnerability> = mutableListOf()
```

**Manual Cascade in Service Layer:**
```kotlin
// CrowdStrikeVulnerabilityImportService.kt
vulnerabilityRepository.deleteByAssetId(asset.id!!)  // Explicit delete
vulnerabilityRepository.saveAll(vulnerabilities)      // Insert

// AssetCascadeDeleteService.kt
vulnerabilityRepository.deleteByAssetId(assetId)  // Explicit delete
assetRepository.delete(asset)                     // Delete asset
```

#### Why This Matters

- **Transactional Replace Pattern** requires manual control over deletion timing
- JPA cascade operations execute **after** transaction commit, interfering with manual operations
- Explicit repository calls provide predictable behavior and clear audit trail
- Manual cascade handling prevents silent data loss

---

## Timestamp Calculation Fix

### The Problem: Overdue Status Calculation Bug

**Date Fixed**: 2025-11-17

After the initial Feature 048 implementation, a critical bug was discovered where `scanTimestamp` was set to the current import execution time (`LocalDateTime.now()`) instead of the vulnerability discovery date. This caused severe overdue status calculation errors.

#### Symptoms

Vulnerabilities that were 901 days old appeared with "OK" status (green badge) instead of "OVERDUE" status (red badge) when the configured threshold was 30 days.

**Root Cause**:
```kotlin
// ❌ BEFORE FIX - Lines 228-246
val currentImportTime = LocalDateTime.now()
val scanTimestamp = currentImportTime  // Bug: Uses import time, not discovery date
```

**Impact**:
- Overdue calculation (`ChronoUnit.DAYS.between(scanTimestamp, now)`) calculated days since **last import**, not days since **vulnerability discovery**
- If imports ran daily, all vulnerabilities appeared to be 0-1 days old
- Threshold enforcement (30 days) was applied to wrong date range
- Critical vulnerabilities went undetected because status showed "OK"

#### The Solution: Discovery Date Calculation

`scanTimestamp` now correctly represents the **vulnerability discovery date**, calculated by working backwards from the current time using the `daysOpen` value from CrowdStrike API:

```kotlin
// ✅ AFTER FIX - Lines 228-248
val currentImportTime = LocalDateTime.now()
val (scanTimestamp, daysOpenText) = if (usePatchPublicationDate && patchPublicationDate != null) {
    // Use patch publication date as reference (Feature 041)
    Pair(patchPublicationDate, daysText)
} else {
    // Calculate discovery timestamp from daysOpen
    val discoveryTimestamp = currentImportTime.minusDays(vulnDto.daysOpen.toLong())
    Pair(discoveryTimestamp, daysText)
}
```

**Example**:
- Current time: 2025-11-17
- CrowdStrike reports `daysOpen = 901`
- Discovery timestamp: 2025-11-17 - 901 days = **2023-04-16**
- Overdue calculation: 2025-11-17 - 2023-04-16 = 901 days → **OVERDUE** (red badge)

#### Duplicate Prevention Impact

This fix maintains duplicate prevention guarantees while fixing overdue calculation:

- **Discovery timestamps may vary slightly between imports** as `daysOpen` increments
- **Duplicate detection relies on CVE+Asset+Product matching**, not timestamp comparison
- **Idempotency preserved**: Same import batch produces same vulnerabilities (though timestamps may differ by 1 day if import runs next day)

#### Data Migration

Existing vulnerabilities with incorrect timestamps must be migrated using the admin endpoint:

**Endpoint**: `POST /api/vulnerabilities/migrate-timestamps?dryRun=true`

**Process**:
1. Fetches all existing vulnerabilities
2. Parses `daysOpen` field (e.g., "901 days" → 901)
3. Calculates discovery timestamp: `currentTime - daysOpen`
4. Updates `scanTimestamp` to calculated discovery date
5. Handles `patchPublicationDate` if available (Feature 041)

**Usage**:
```bash
# Dry run - see what would change without saving
curl -X POST "https://secman.example.com/api/vulnerabilities/migrate-timestamps?dryRun=true" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"

# Actual migration
curl -X POST "https://secman.example.com/api/vulnerabilities/migrate-timestamps" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
```

**Response**:
```json
{
  "totalProcessed": 113,
  "migrated": 113,
  "skipped": 0,
  "errors": 0,
  "errorMessages": [],
  "dryRun": false
}
```

#### Verification

After migration, verify overdue status calculations are correct:

1. **Check database timestamps**:
   ```sql
   SELECT id, vulnerability_id, days_open, scan_timestamp,
          DATEDIFF(NOW(), scan_timestamp) as calculated_days
   FROM vulnerability
   ORDER BY scan_timestamp DESC
   LIMIT 10;
   ```

2. **Verify UI displays**: Vulnerabilities > 30 days old should show red "OVERDUE" badge

3. **Check threshold configuration**: Admin > Vulnerability Settings > Threshold should be 30 days

#### Configuration Options

The timestamp calculation respects the `VULN_USE_PATCH_PUBLICATION_DATE` environment variable (Feature 041):

| Setting | Behavior | Use Case |
|---------|----------|----------|
| `false` (default) | Use `daysOpen` from CrowdStrike API | Standard vulnerability age tracking |
| `true` | Use `patchPublicationDate` if available | Track age from patch availability, not vulnerability discovery |

---

## Edge Case Handling

### Same Hostname in Batch

If the same hostname appears multiple times in a single import batch, only the last occurrence is processed:

```kotlin
// Each server processed in separate transaction
for (batch in batches) {
    val result = importVulnerabilitiesForServer(batch)
    // Last batch with hostname "server01" wins
}
```

**Recommendation**: Ensure import batches contain unique hostnames.

### Concurrent Imports

Multiple concurrent imports for the same asset are serialized by database transactions:

```kotlin
@Transactional  // Each import acquires transaction lock
open fun importVulnerabilitiesForServer(batch: CrowdStrikeVulnerabilityBatchDto)
```

- Import A starts → acquires lock on asset
- Import B starts → waits for Import A to complete
- Import A commits → releases lock
- Import B proceeds → replaces data from Import A

**Result**: Last import to commit wins. No duplicates created.

### Vulnerabilities Without CVE IDs

Vulnerabilities without CVE IDs are automatically filtered and skipped:

```kotlin
var vulnsWithCve = batch.vulnerabilities.filter { !it.cveId.isNullOrBlank() }
var skippedCount = batch.vulnerabilities.size - vulnsWithCve.size
```

**Tracking**: Skipped vulnerabilities are counted in `ImportStatisticsDto.vulnerabilitiesSkipped`

### Transaction Rollback

If any error occurs during import, the entire transaction rolls back:

```kotlin
try {
    val result = importVulnerabilitiesForServer(batch)
    // Success - transaction commits
} catch (e: Exception) {
    // Error - transaction rolls back automatically
    // Database remains in pre-import state
}
```

**Guarantee**: Either all vulnerabilities are imported, or none are.

---

## Idempotency Guarantees

The import operation is idempotent, meaning:

> **Same Input → Same Output**

Running the same import multiple times produces identical results:

```kotlin
// Import 1
importServerVulnerabilities(batch)
// → Result: 5 vulnerabilities in database

// Import 2 (same batch)
importServerVulnerabilities(batch)
// → Result: Still 5 vulnerabilities in database (not 10)

// Import 3 (same batch)
importServerVulnerabilities(batch)
// → Result: Still 5 vulnerabilities in database (not 15)
```

### Import Statistics Consistency

Even though database state is identical, import statistics accurately reflect the operation:

```kotlin
// First import
ImportStatisticsDto(
    serversCreated = 1,
    serversUpdated = 0,
    vulnerabilitiesImported = 5
)

// Second import (same data)
ImportStatisticsDto(
    serversCreated = 0,
    serversUpdated = 1,  // Asset already exists
    vulnerabilitiesImported = 5  // Replaced 5 vulnerabilities
)
```

---

## Usage Examples

### Basic Import

```kotlin
val batch = CrowdStrikeVulnerabilityBatchDto(
    hostname = "web-server-01",
    ip = "10.0.1.100",
    vulnerabilities = listOf(
        VulnerabilityDto(cveId = "CVE-2023-1234", severity = "HIGH", ...),
        VulnerabilityDto(cveId = "CVE-2023-5678", severity = "CRITICAL", ...)
    )
)

val result = importService.importServerVulnerabilities(listOf(batch))
// Result: 2 vulnerabilities imported for web-server-01
```

### Remediation Tracking

```kotlin
// Initial import
val batch1 = CrowdStrikeVulnerabilityBatchDto(
    hostname = "web-server-01",
    vulnerabilities = listOf(
        VulnerabilityDto(cveId = "CVE-2023-1234", ...),
        VulnerabilityDto(cveId = "CVE-2023-5678", ...),
        VulnerabilityDto(cveId = "CVE-2023-9999", ...)
    )
)
importService.importServerVulnerabilities(listOf(batch1))
// Database: 3 vulnerabilities

// Later import (CVE-2023-9999 was patched)
val batch2 = CrowdStrikeVulnerabilityBatchDto(
    hostname = "web-server-01",
    vulnerabilities = listOf(
        VulnerabilityDto(cveId = "CVE-2023-1234", ...),
        VulnerabilityDto(cveId = "CVE-2023-5678", ...)
    )
)
importService.importServerVulnerabilities(listOf(batch2))
// Database: 2 vulnerabilities (CVE-2023-9999 removed)
```

### Handling Import Errors

```kotlin
val batches = listOf(
    CrowdStrikeVulnerabilityBatchDto(hostname = "server-01", ...),
    CrowdStrikeVulnerabilityBatchDto(hostname = "server-02", ...),
    CrowdStrikeVulnerabilityBatchDto(hostname = "server-03", ...)
)

val result = importService.importServerVulnerabilities(batches)

// Check for errors
if (result.errors.isNotEmpty()) {
    println("Import completed with ${result.errors.size} errors:")
    result.errors.forEach { println("  - $it") }
}

// Check statistics
println("Processed: ${result.serversProcessed}")
println("Created: ${result.serversCreated}")
println("Updated: ${result.serversUpdated}")
println("Imported: ${result.vulnerabilitiesImported}")
println("Skipped: ${result.vulnerabilitiesSkipped}")
```

---

## Performance Characteristics

### Expected Performance

For typical enterprise deployments:

| Dataset Size | Expected Time | Bottleneck |
|--------------|---------------|------------|
| 10 servers, 50 vulnerabilities | < 5 seconds | Network I/O |
| 100 servers, 500 vulnerabilities | < 30 seconds | Database writes |
| 1000 servers, 5000 vulnerabilities | < 5 minutes | Database writes |

### Optimization Notes

1. **Batch Processing**: Import operations are batched by server
   - Each server is a separate transaction
   - Failures isolated to individual servers
   - Parallel processing possible (different servers)

2. **Bulk Operations**: Delete and insert use bulk SQL
   - `DELETE FROM vulnerabilities WHERE asset_id = ?`
   - `INSERT INTO vulnerabilities VALUES (...), (...), (...)`
   - More efficient than individual statements

3. **Per-Server Transactions**: Transaction scope limited to single server
   - Smaller transaction = faster commit
   - Reduced lock contention
   - Better concurrency

4. **Index Usage**: Database indexes on key columns
   - `asset_id` for delete operations
   - `(asset_id, vulnerability_id)` for uniqueness

---

## Testing

### Integration Tests

Comprehensive integration tests verify duplicate prevention:

**Test File**: `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportServiceTest.kt`

**Coverage**:
- ✅ Idempotent imports (same data twice)
- ✅ Initial import creates records
- ✅ Remediation removes vulnerabilities
- ✅ Per-asset isolation (different assets, same CVEs)
- ✅ Invalid data filtering (null CVE IDs)
- ✅ Expansion (adding new vulnerabilities)

**Run Tests**:
```bash
./gradlew test --tests "CrowdStrikeVulnerabilityImportServiceTest"
```

---

## See Also

- [CLI Reference](./CLI.md) - CrowdStrike query commands
- [Environment Variables](./ENVIRONMENT.md) - CrowdStrike configuration
- [Architecture](./ARCHITECTURE.md) - Import patterns and design
- [Troubleshooting](./TROUBLESHOOTING.md) - Common import issues

**Internal References:**
- Feature Spec: `/specs/048-prevent-duplicate-vulnerabilities/spec.md`
- Service Code: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt`
