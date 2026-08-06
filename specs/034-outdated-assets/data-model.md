# Data Model: Outdated Assets View

**Feature**: 034-outdated-assets | **Date**: 2025-10-26
**Purpose**: Define database entities and relationships for the Outdated Assets feature

## Entity Schemas

### 1. OutdatedAssetMaterializedView

**Purpose**: Pre-calculated denormalized view of assets with overdue vulnerabilities for fast queries

**Table Name**: `outdated_asset_materialized_view`

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | `Long` | Primary Key, Auto-increment | Unique identifier |
| `assetId` | `Long` | NOT NULL, Index | Foreign key to Asset (not enforced to allow deletion) |
| `assetName` | `String(255)` | NOT NULL, Index | Asset name for display and search |
| `assetType` | `String(50)` | NOT NULL | Asset type (e.g., "SERVER") |
| `totalOverdueCount` | `Int` | NOT NULL | Total count of overdue vulnerabilities |
| `criticalCount` | `Int` | NOT NULL, Default: 0 | Count of overdue Critical vulnerabilities |
| `highCount` | `Int` | NOT NULL, Default: 0 | Count of overdue High vulnerabilities |
| `mediumCount` | `Int` | NOT NULL, Default: 0 | Count of overdue Medium vulnerabilities |
| `lowCount` | `Int` | NOT NULL, Default: 0 | Count of overdue Low vulnerabilities |
| `oldestVulnDays` | `Int` | NOT NULL | Age in days of oldest overdue vulnerability |
| `oldestVulnId` | `String(50)` | NULL | CVE ID of oldest vulnerability (for reference) |
| `workgroupIds` | `String(500)` | NULL | Comma-separated workgroup IDs (e.g., "1,3,5") |
| `lastCalculatedAt` | `LocalDateTime` | NOT NULL | Timestamp when this row was calculated |

**Indexes**:
```sql
-- Primary key
PRIMARY KEY (id)

-- For workgroup filtering + sorting
CREATE INDEX idx_outdated_workgroup_sort
ON outdated_asset_materialized_view (workgroup_ids(100), oldest_vuln_days DESC);

-- For asset name search
CREATE INDEX idx_outdated_asset_name
ON outdated_asset_materialized_view (asset_name);

-- For asset ID lookups
CREATE INDEX idx_outdated_asset_id
ON outdated_asset_materialized_view (asset_id);

-- For severity filtering
CREATE INDEX idx_outdated_severity
ON outdated_asset_materialized_view (critical_count, high_count);

-- For staleness checks
CREATE INDEX idx_outdated_calculated_at
ON outdated_asset_materialized_view (last_calculated_at);
```

**Kotlin Entity**:
```kotlin
package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "outdated_asset_materialized_view",
    indexes = [
        Index(name = "idx_outdated_asset_id", columnList = "asset_id"),
        Index(name = "idx_outdated_asset_name", columnList = "asset_name"),
        Index(name = "idx_outdated_severity", columnList = "critical_count, high_count")
    ]
)
data class OutdatedAssetMaterializedView(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "asset_id", nullable = false)
    var assetId: Long,

    @Column(name = "asset_name", nullable = false, length = 255)
    var assetName: String,

    @Column(name = "asset_type", nullable = false, length = 50)
    var assetType: String,

    @Column(name = "total_overdue_count", nullable = false)
    var totalOverdueCount: Int,

    @Column(name = "critical_count", nullable = false)
    var criticalCount: Int = 0,

    @Column(name = "high_count", nullable = false)
    var highCount: Int = 0,

    @Column(name = "medium_count", nullable = false)
    var mediumCount: Int = 0,

    @Column(name = "low_count", nullable = false)
    var lowCount: Int = 0,

    @Column(name = "oldest_vuln_days", nullable = false)
    var oldestVulnDays: Int,

    @Column(name = "oldest_vuln_id", length = 50)
    var oldestVulnId: String? = null,

    @Column(name = "workgroup_ids", length = 500)
    var workgroupIds: String? = null,

    @Column(name = "last_calculated_at", nullable = false)
    var lastCalculatedAt: LocalDateTime = LocalDateTime.now()
)
```

**Lifecycle**:
- Rows are deleted and recreated on each refresh
- No foreign key constraints (allows Asset deletion without cascade issues)
- Managed entirely by `MaterializedViewRefreshService`

---

### 2. MaterializedViewRefreshJob

**Purpose**: Track refresh operations for observability and progress indication

**Table Name**: `materialized_view_refresh_job`

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | `Long` | Primary Key, Auto-increment | Unique identifier |
| `status` | `String(20)` | NOT NULL, Index | Status: RUNNING, COMPLETED, FAILED |
| `triggeredBy` | `String(50)` | NOT NULL | Source: "CLI Import", "Manual Refresh", "Config Change" |
| `startedAt` | `LocalDateTime` | NOT NULL | Job start timestamp |
| `completedAt` | `LocalDateTime` | NULL | Job completion timestamp |
| `assetsProcessed` | `Int` | NOT NULL, Default: 0 | Number of assets processed so far |
| `totalAssets` | `Int` | NOT NULL, Default: 0 | Total assets to process |
| `progressPercentage` | `Int` | NOT NULL, Default: 0 | Calculated: (assetsProcessed / totalAssets) * 100 |
| `errorMessage` | `String(1000)` | NULL | Error details if status = FAILED |
| `durationMs` | `Long` | NULL | Duration in milliseconds (completedAt - startedAt) |

**Indexes**:
```sql
-- Primary key
PRIMARY KEY (id)

-- For finding running jobs
CREATE INDEX idx_refresh_job_status
ON materialized_view_refresh_job (status);

-- For recent job lookups
CREATE INDEX idx_refresh_job_started
ON materialized_view_refresh_job (started_at DESC);
```

**Kotlin Entity**:
```kotlin
package com.secman.domain

import jakarta.persistence.*
import java.time.LocalDateTime

enum class RefreshJobStatus {
    RUNNING,
    COMPLETED,
    FAILED
}

@Entity
@Table(
    name = "materialized_view_refresh_job",
    indexes = [
        Index(name = "idx_refresh_job_status", columnList = "status"),
        Index(name = "idx_refresh_job_started", columnList = "started_at")
    ]
)
data class MaterializedViewRefreshJob(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: RefreshJobStatus = RefreshJobStatus.RUNNING,

    @Column(name = "triggered_by", nullable = false, length = 50)
    var triggeredBy: String,

    @Column(name = "started_at", nullable = false)
    var startedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,

    @Column(name = "assets_processed", nullable = false)
    var assetsProcessed: Int = 0,

    @Column(name = "total_assets", nullable = false)
    var totalAssets: Int = 0,

    @Column(name = "progress_percentage", nullable = false)
    var progressPercentage: Int = 0,

    @Column(name = "error_message", length = 1000)
    var errorMessage: String? = null,

    @Column(name = "duration_ms")
    var durationMs: Long? = null
) {
    /**
     * Update progress and calculate percentage
     */
    fun updateProgress(processed: Int) {
        assetsProcessed = processed
        progressPercentage = if (totalAssets > 0) {
            ((processed.toDouble() / totalAssets) * 100).toInt()
        } else {
            0
        }
    }

    /**
     * Mark job as completed and calculate duration
     */
    fun markCompleted() {
        status = RefreshJobStatus.COMPLETED
        completedAt = LocalDateTime.now()
        durationMs = java.time.Duration.between(startedAt, completedAt).toMillis()
        progressPercentage = 100
    }

    /**
     * Mark job as failed with error message
     */
    fun markFailed(error: String) {
        status = RefreshJobStatus.FAILED
        completedAt = LocalDateTime.now()
        errorMessage = error.take(1000)  // Truncate to column size
        durationMs = java.time.Duration.between(startedAt, completedAt).toMillis()
    }
}
```

**Lifecycle**:
- Created when refresh is triggered
- Updated during refresh with progress
- Final status set on completion/failure
- Never deleted (audit trail)

---

### 3. RefreshProgressEvent (Domain Event)

**Purpose**: Event published during refresh for SSE streaming

**Not a database entity** - Used for application-level eventing

**Kotlin Class**:
```kotlin
package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class RefreshProgressEvent(
    val jobId: Long,
    val status: RefreshJobStatus,
    val progressPercentage: Int,
    val assetsProcessed: Int,
    val totalAssets: Int,
    val message: String? = null
)
```

**Usage**:
- Published by `MaterializedViewRefreshService` during refresh
- Consumed by `OutdatedAssetRefreshProgressHandler` (SSE endpoint)
- Broadcast to all connected SSE clients

---

## Relationships

### Conceptual Relationships (Not Enforced)

```
OutdatedAssetMaterializedView
├─> Asset (assetId) - reference only, no FK constraint
└─> Workgroup (workgroupIds) - denormalized, no FK constraint

MaterializedViewRefreshJob
└─> [No relationships] - standalone audit entity
```

**Why No Foreign Keys**:
- Materialized view is a cache, not source of truth
- Asset deletion should not cascade to materialized view (stale data acceptable until next refresh)
- Workgroup changes handled on next refresh

---

## Data Flow

### Refresh Process

1. **Trigger** (CLI Import, Manual, Config Change)
   ```
   MaterializedViewRefreshService.triggerAsyncRefresh(triggeredBy)
   ```

2. **Job Creation**
   ```kotlin
   val job = MaterializedViewRefreshJob(
       triggeredBy = "CLI Import",
       totalAssets = assetRepository.countOutdatedAssets(threshold)
   )
   refreshJobRepository.save(job)
   ```

3. **Delete Old Data**
   ```kotlin
   outdatedAssetMaterializedViewRepository.deleteAll()
   ```

4. **Calculate & Insert New Data** (in batches)
   ```kotlin
   val threshold = vulnerabilityConfigService.getReminderOneDays()
   val outdatedAssets = assetRepository.findAssetsWithOverdueVulnerabilities(threshold)

   outdatedAssets.chunked(1000).forEachIndexed { index, batch ->
       val views = batch.map { asset ->
           OutdatedAssetMaterializedView(
               assetId = asset.id!!,
               assetName = asset.name,
               totalOverdueCount = asset.calculateOverdueCount(threshold),
               criticalCount = asset.calculateOverdueBySeverity("CRITICAL", threshold),
               // ... etc
           )
       }
       outdatedAssetMaterializedViewRepository.saveAll(views)

       // Update progress
       job.updateProgress(processed = (index + 1) * 1000)
       refreshJobRepository.update(job)

       // Publish progress event
       eventPublisher.publish(RefreshProgressEvent(job.id!!, job.status, job.progressPercentage, ...))
   }
   ```

5. **Completion**
   ```kotlin
   job.markCompleted()
   refreshJobRepository.update(job)
   eventPublisher.publish(RefreshProgressEvent(job.id!!, COMPLETED, 100, ...))
   ```

---

## Query Patterns

### 1. Get Outdated Assets (Paginated, Filtered, Sorted)

```kotlin
@Query("""
    SELECT v FROM OutdatedAssetMaterializedView v
    WHERE (:workgroupIds IS NULL
        OR :workgroupIds = ''
        OR v.workgroupIds IS NULL
        OR v.workgroupIds LIKE CONCAT('%', :workgroupId, '%'))
    AND (:searchTerm IS NULL OR v.assetName LIKE CONCAT('%', :searchTerm, '%'))
    AND (:minSeverity IS NULL OR
        (CASE
            WHEN :minSeverity = 'CRITICAL' THEN v.criticalCount > 0
            WHEN :minSeverity = 'HIGH' THEN (v.criticalCount > 0 OR v.highCount > 0)
            WHEN :minSeverity = 'MEDIUM' THEN (v.criticalCount > 0 OR v.highCount > 0 OR v.mediumCount > 0)
            ELSE true
        END))
    ORDER BY v.oldestVulnDays DESC
""")
fun findOutdatedAssets(
    workgroupId: String?,
    searchTerm: String?,
    minSeverity: String?,
    pageable: Pageable
): Page<OutdatedAssetMaterializedView>
```

### 2. Get Running Refresh Job

```kotlin
@Query("SELECT j FROM MaterializedViewRefreshJob j WHERE j.status = 'RUNNING' ORDER BY j.startedAt DESC")
fun findRunningJob(): Optional<MaterializedViewRefreshJob>
```

### 3. Get Latest Refresh Timestamp

```kotlin
@Query("SELECT MAX(v.lastCalculatedAt) FROM OutdatedAssetMaterializedView v")
fun getLastRefreshTimestamp(): Optional<LocalDateTime>
```

---

## Migration Strategy

**Schema Creation**: Hibernate auto-migration will create tables on first deployment

**Indexes**: Created via `@Index` annotations in entity classes

**Initial Data**: Empty tables until first refresh triggered

**Rollback**: Drop tables if feature is reverted:
```sql
DROP TABLE IF EXISTS materialized_view_refresh_job;
DROP TABLE IF EXISTS outdated_asset_materialized_view;
```

---

## Performance Considerations

### Expected Row Counts
- `OutdatedAssetMaterializedView`: Up to 10,000 rows (worst case: all assets outdated)
- `MaterializedViewRefreshJob`: Grows indefinitely (audit trail) - consider retention policy

### Index Selectivity
- `workgroup_ids`: Low selectivity (few workgroups)
- `oldest_vuln_days`: High selectivity (wide distribution)
- Composite index `(workgroup_ids, oldest_vuln_days)` optimized for primary query

### Refresh Performance
- Batch size: 1000 assets per chunk
- Expected duration: <30 seconds for 10,000 assets
- Bottleneck: Overdue vulnerability calculation per asset

---

## Validation Rules

### OutdatedAssetMaterializedView
- `totalOverdueCount` = `criticalCount + highCount + mediumCount + lowCount`
- `oldestVulnDays` > configured threshold (reminder_one_days)
- `assetId` must reference existing Asset (validated during refresh, not enforced)

### MaterializedViewRefreshJob
- `completedAt` must be after `startedAt`
- `progressPercentage` must be 0-100
- `durationMs` = `completedAt - startedAt` (in milliseconds)
- `status = COMPLETED` implies `progressPercentage = 100`

---

## Next Steps

1. ✅ Create API contracts in `contracts/` directory
2. Implement repository interfaces
3. Implement service layer with refresh logic
4. Write contract tests validating schemas
