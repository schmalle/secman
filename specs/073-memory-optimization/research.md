# Research: Memory and Heap Space Optimization

**Feature**: 073-memory-optimization
**Date**: 2026-02-03

## Research Tasks

### R1: JPA LAZY vs EAGER Loading with Feature Flag Toggle

**Question**: How to implement runtime-toggleable fetch strategies in Hibernate/JPA?

**Decision**: Use `@Transient` computed property with EntityGraph-based query methods

**Rationale**: JPA annotations are compile-time. Runtime toggle requires:
1. Entity stays LAZY (default optimized state)
2. Repository provides two query paths:
   - `findById(id)` - LAZY (default)
   - `findByIdWithWorkgroups(id)` - Uses `@EntityGraph` to JOIN FETCH
3. Feature flag controls which method is called in service layer

**Alternatives Considered**:
- Dynamic proxy: Too complex, maintenance burden
- Hibernate interceptor: Couples to Hibernate internals
- Separate entity classes: Code duplication

**Implementation Pattern**:
```kotlin
// Repository
@EntityGraph(attributePaths = ["workgroups"])
fun findByIdWithWorkgroups(id: Long): Optional<Asset>

// Config
@ConfigurationProperties("secman.memory")
class MemoryOptimizationConfig {
    var lazyLoadingEnabled: Boolean = true
}

// Service
fun getAsset(id: Long) = if (config.lazyLoadingEnabled)
    repo.findById(id) else repo.findByIdWithWorkgroups(id)
```

---

### R2: Streaming Excel Export with SXSSFWorkbook

**Question**: How to stream data directly to Excel without accumulating all records in memory?

**Decision**: Replace `allVulnerabilities.addAll()` accumulation with write-on-fetch pattern

**Rationale**: Current VulnerabilityExportService fetches all data into a List before calling `writeToExcel()`. SXSSFWorkbook already handles output streaming, but input is unbounded. Solution:
1. Open SXSSFWorkbook before fetching
2. Fetch page, write rows, discard page
3. No intermediate List accumulation

**Alternatives Considered**:
- Database cursor/scroll: Requires transaction held open, risk of timeout
- Reactive streams: Major refactoring, overkill for this use case
- File-based intermediate storage: Adds I/O overhead, cleanup complexity

**Implementation Pattern**:
```kotlin
fun exportVulnerabilitiesStreaming(auth: Authentication): ByteArrayOutputStream {
    val workbook = SXSSFWorkbook(100)
    val sheet = workbook.createSheet("Vulnerabilities")
    var rowNum = 1
    var page = 0

    while (hasMore) {
        val response = vulnerabilityService.getCurrentVulnerabilitiesOptimized(...)
        response.content.forEach { dto ->
            createVulnerabilityRow(sheet, rowNum++, dto, styles)
        }
        hasMore = response.hasNext
        page++
    }

    val output = ByteArrayOutputStream()
    workbook.write(output)
    workbook.close()
    return output
}
```

---

### R3: SQL-Level Exception Status Filtering

**Question**: How to move exception status filtering from in-memory to SQL?

**Decision**: Use NOT EXISTS subquery pattern for exception matching at database level

**Rationale**: Current code fetches UNPAGED results then filters in Kotlin. For 300K records, this loads all data. SQL approach:
1. Add repository method with exception status as parameter
2. Use SQL CASE WHEN + EXISTS to classify at query time
3. Filter in WHERE clause

**Alternatives Considered**:
- Materialized view: Additional schema, maintenance overhead
- Pre-computed exception status column: Denormalization, sync issues
- Stored procedure: Vendor-specific, testing complexity

**Implementation Pattern**:
```sql
-- Add to existing query via native query or JPQL
WHERE (:exceptionStatus IS NULL
   OR (CASE
       WHEN EXISTS (SELECT 1 FROM vulnerability_exception ve
                    WHERE ve.asset_id = v.asset_id
                    AND (ve.expiration_date IS NULL OR ve.expiration_date > NOW()))
       THEN 'EXCEPTED'
       ELSE 'NOT_EXCEPTED'
       END) = :exceptionStatus)
```

---

### R4: Unified Access Control Query Pattern

**Question**: How to combine 4-5 separate access control queries into single query?

**Decision**: Use UNION DISTINCT with subqueries for each access path

**Rationale**: Current `getAccessibleAssets()` runs:
1. `findByWorkgroupsUsersIdOrManualCreatorIdOrScanUploaderId`
2. `findByCloudAccountIdIn`
3. `findByAdDomainInIgnoreCase`

Then concatenates + deduplicates in Kotlin. Single UNION query:
- Reduces round trips from 3-4 to 1
- Eliminates in-memory deduplication
- Database handles DISTINCT efficiently with indexes

**Alternatives Considered**:
- Complex OR join: Hard to optimize, potential full table scan
- Caching all assets per user: Memory-intensive, stale data risk
- Precomputed access table: Schema change, sync complexity

**Implementation Pattern**:
```kotlin
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
fun findAccessibleAssets(userId: Long, awsAccountIds: List<String>, domains: List<String>): List<Asset>
```

---

### R5: Batched Duplicate Cleanup Pattern

**Question**: How to replace `findAll()` in `cleanupDuplicates()` with batched processing?

**Decision**: Use window function SQL query with batch DELETE

**Rationale**: Current `cleanupDuplicates()` loads ALL vulnerabilities, groups in memory, identifies duplicates. For 300K records, this is ~150MB+. Database can do this efficiently:
1. Window function identifies duplicates (ROW_NUMBER() OVER PARTITION BY)
2. DELETE WHERE row_number > 1
3. Single transaction, no memory pressure

**Alternatives Considered**:
- Paginated Kotlin processing: Still loads pages into memory, slower
- Scheduled background job with small batches: Adds complexity, timing issues
- Prevent duplicates at insert time: Requires constraint changes

**Implementation Pattern**:
```sql
-- Identify and delete duplicates in single query
DELETE FROM vulnerability
WHERE id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (
            PARTITION BY asset_id, cve_id, product_version
            ORDER BY scan_timestamp DESC
        ) as rn
        FROM vulnerability
    ) ranked
    WHERE rn > 1
)
```

---

### R6: JVM Memory Metrics Endpoint

**Question**: How to expose heap memory metrics via health endpoint in Micronaut?

**Decision**: Use Micronaut Management `@Endpoint` with custom memory metrics

**Rationale**: Micronaut provides `/health` endpoint but doesn't include JVM memory by default. Options:
1. Enable management endpoints with metrics
2. Add custom endpoint for memory-specific data
3. Use Micrometer metrics auto-configured by Micronaut

**Implementation Pattern**:
```kotlin
@Endpoint(id = "memory")
class MemoryEndpoint {
    @Read
    fun memory(): Map<String, Any> {
        val runtime = Runtime.getRuntime()
        return mapOf(
            "heap" to mapOf(
                "used" to (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
                "max" to runtime.maxMemory() / 1024 / 1024,
                "free" to runtime.freeMemory() / 1024 / 1024
            ),
            "timestamp" to System.currentTimeMillis()
        )
    }
}
```

Add to application.yml:
```yaml
endpoints:
  memory:
    enabled: true
    sensitive: false
```

---

## Summary of Decisions

| Area | Decision | Risk Level |
|------|----------|------------|
| LAZY/EAGER toggle | Feature flag + EntityGraph queries | Low - Standard JPA pattern |
| Export streaming | Write-on-fetch, no intermediate List | Low - Refactoring only |
| Exception filtering | SQL NOT EXISTS subquery | Medium - Query complexity |
| Access control | UNION DISTINCT single query | Medium - Query performance validation needed |
| Duplicate cleanup | Window function batch DELETE | Low - Standard SQL pattern |
| Memory metrics | Custom Micronaut endpoint | Low - Standard management pattern |

All research items resolved. No NEEDS CLARIFICATION items remain.
