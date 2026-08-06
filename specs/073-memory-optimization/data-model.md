# Data Model: Memory and Heap Space Optimization

**Feature**: 073-memory-optimization
**Date**: 2026-02-03

## Overview

This feature involves modifications to existing entities and DTOs rather than new data structures. The changes optimize memory usage through fetch strategy adjustments and DTO streamlining.

## Entity Modifications

### Asset (Existing - Modified)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**Change**: workgroups relationship fetch type

| Field | Current | New | Rationale |
|-------|---------|-----|-----------|
| workgroups | `@ManyToMany(fetch = FetchType.EAGER)` | `@ManyToMany(fetch = FetchType.LAZY)` | Prevent N+1 loading in list operations |

**Impact**:
- List endpoints no longer trigger workgroup loading
- Detail/update endpoints must explicitly fetch via `@EntityGraph`
- Existing `getEffectiveCriticality()` method requires workgroups - must ensure loaded before call

**Migration**: No schema change. Behavioral change only.

---

### User (Existing - Modified)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Change**: workgroups relationship fetch type

| Field | Current | New | Rationale |
|-------|---------|-----|-----------|
| workgroups | `@ManyToMany(fetch = FetchType.EAGER)` | `@ManyToMany(fetch = FetchType.LAZY)` | Reduce session memory per user |

**Impact**:
- Authentication/authorization code must explicitly fetch when needed
- Access control queries handle workgroup joins at SQL level

**Migration**: No schema change. Behavioral change only.

---

## DTO Modifications

### VulnerabilityWithExceptionDto (Existing - Modified)

**File**: Currently defined inline in `VulnerabilityService.kt`, may need extraction

**Current Structure**:
```kotlin
data class VulnerabilityWithExceptionDto(
    val id: Long?,
    val asset: Asset,          // FULL OBJECT - REMOVE
    val assetId: Long,         // Keep (flat)
    val assetName: String,     // Keep (flat)
    val assetIp: String?,      // Keep (flat)
    val cveId: String?,
    val criticality: String?,
    val productVersion: String?,
    // ... other fields
)
```

**New Structure**:
```kotlin
data class VulnerabilityWithExceptionDto(
    val id: Long?,
    // REMOVED: val asset: Asset
    val assetId: Long,
    val assetName: String,
    val assetIp: String?,
    val cveId: String?,
    val criticality: String?,
    val productVersion: String?,
    // ... other fields unchanged
)
```

**Impact**:
- JSON response size reduced ~40%+
- Frontend must use flat fields (already available)
- No circular reference serialization issues

---

## New Configuration Entity

### MemoryOptimizationConfig (New)

**File**: `src/backendng/src/main/kotlin/com/secman/config/MemoryOptimizationConfig.kt`

```kotlin
@ConfigurationProperties("secman.memory")
@Serdeable
data class MemoryOptimizationConfig(
    /** Enable LAZY loading for entity relationships (default: true) */
    var lazyLoadingEnabled: Boolean = true,

    /** Batch size for large data operations (default: 1000) */
    var batchSize: Int = 1000,

    /** Enable streaming exports (default: true) */
    var streamingExportsEnabled: Boolean = true
)
```

**Configuration in application.yml**:
```yaml
secman:
  memory:
    lazy-loading-enabled: ${MEMORY_LAZY_LOADING:true}
    batch-size: ${MEMORY_BATCH_SIZE:1000}
    streaming-exports-enabled: ${MEMORY_STREAMING_EXPORTS:true}
```

---

## Repository Query Additions

### AssetRepository (Modified)

**New Query Methods**:

```kotlin
/**
 * Unified access control query - single database round trip
 * Replaces 4-5 separate queries in AssetFilterService
 */
@Query("""
    SELECT DISTINCT a FROM Asset a
    WHERE a.id IN (
        SELECT a2.id FROM Asset a2 JOIN a2.workgroups w JOIN w.users u WHERE u.id = :userId
        UNION
        SELECT a3.id FROM Asset a3 WHERE a3.manualCreator.id = :userId
        UNION
        SELECT a4.id FROM Asset a4 WHERE a4.scanUploader.id = :userId
        UNION
        SELECT a5.id FROM Asset a5 WHERE a5.cloudAccountId IN :awsAccountIds
        UNION
        SELECT a6.id FROM Asset a6 WHERE LOWER(a6.adDomain) IN :domains
    )
    ORDER BY a.name
""")
fun findAccessibleAssets(
    userId: Long,
    awsAccountIds: List<String>,
    domains: List<String>
): List<Asset>

/**
 * EntityGraph query for detail view with workgroups
 */
@EntityGraph(attributePaths = ["workgroups"])
fun findByIdWithWorkgroups(id: Long): Optional<Asset>
```

### VulnerabilityRepository (Modified)

**New Query Methods**:

```kotlin
/**
 * Query with SQL-level exception status filtering
 * Replaces in-memory filtering pattern
 */
@Query(nativeQuery = true, value = """
    SELECT v.* FROM vulnerability v
    JOIN asset a ON v.asset_id = a.id
    WHERE (:exceptionStatus IS NULL OR
           (CASE WHEN EXISTS (
               SELECT 1 FROM vulnerability_exception ve
               WHERE (ve.asset_id = v.asset_id OR ve.product_version = v.product_version)
               AND (ve.expiration_date IS NULL OR ve.expiration_date > NOW())
           ) THEN 'excepted' ELSE 'not_excepted' END) = :exceptionStatus)
    ORDER BY v.scan_timestamp DESC
    LIMIT :limit OFFSET :offset
""")
fun findWithExceptionStatusFilter(
    exceptionStatus: String?,
    limit: Int,
    offset: Int
): List<Vulnerability>

/**
 * Batched duplicate cleanup using window function
 * Returns IDs of duplicates to delete
 */
@Query(nativeQuery = true, value = """
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (
            PARTITION BY asset_id, cve_id, product_version
            ORDER BY scan_timestamp DESC
        ) as rn
        FROM vulnerability
    ) ranked
    WHERE rn > 1
    LIMIT :batchSize
""")
fun findDuplicateIds(batchSize: Int): List<Long>
```

---

## State Transitions

No new state machines introduced. Export jobs already have status tracking (PENDING → PROCESSING → COMPLETED/FAILED).

---

## Validation Rules

| Entity/DTO | Field | Rule | Enforcement |
|------------|-------|------|-------------|
| MemoryOptimizationConfig | batchSize | 100 ≤ batchSize ≤ 10000 | Config validation |
| MemoryOptimizationConfig | lazyLoadingEnabled | boolean | Type system |

---

## Data Volume Assumptions

| Entity | Expected Count | Memory Impact |
|--------|---------------|---------------|
| Vulnerability | 300K-500K | Primary optimization target |
| Asset | 10K-50K | Workgroup loading optimization |
| VulnerabilityException | 1K-10K | Cache-appropriate |
| User | 100-500 | Session memory optimization |
