# Implementation Plan: CrowdStrike Import Cleanup

**Branch**: `053-crowdstrike-import-cleanup` | **Date**: 2025-12-08 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/053-crowdstrike-import-cleanup/spec.md`

## Summary

**Bug Investigation Result**: Multiple issues were found causing inflated vulnerability counts:

1. **Query Issue**: The original `findLatestVulnerabilitiesForAssetIds()` query used timestamp equality comparison which could fail due to precision differences between Java LocalDateTime (nanoseconds) and MariaDB DATETIME (microseconds).

2. **Missing Transactional Replace**: `VulnerabilityImportService` (Excel import) did not use the delete-before-insert pattern.

3. **Grouping Issue**: `DomainVulnsService` grouped vulnerabilities by Asset object reference instead of Asset ID, which could cause mismatches with native query results.

4. **Performance Issue**: `DomainVulnsService` loaded ALL assets into memory for filtering instead of using database-level query.

**Fixes Applied**:
- Updated query to use `DENSE_RANK()` window function for robust filtering
- Added transactional replace pattern to `VulnerabilityImportService`
- Changed grouping to use asset ID instead of object reference
- Used database-level filtering for asset lookup

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA
**Storage**: MariaDB 12
**Testing**: Not required per constitution (Principle IV)
**Target Platform**: JVM backend server
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Domain vulnerability counts should match CrowdStrike Lookup counts exactly
**Constraints**: Query must filter to latest import timestamp per asset to exclude historical duplicates
**Scale/Scope**: Supports 100k+ vulnerabilities across 10k+ assets

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | No new user input, uses existing authenticated endpoints |
| III. API-First | PASS | No new API endpoints, internal service change only |
| IV. User-Requested Testing | PASS | No test planning required unless requested |
| V. RBAC | PASS | Uses existing @Secured annotations, no access control changes |
| VI. Schema Evolution | PASS | No schema changes required |

## Project Structure

### Documentation (this feature)

```text
specs/053-crowdstrike-import-cleanup/
├── plan.md              # This file
├── research.md          # Phase 0 output - investigation findings
├── spec.md              # Feature specification
└── checklists/
    └── requirements.md  # Specification quality checklist
```

### Source Code (affected files)

```text
src/backendng/src/main/kotlin/com/secman/
├── repository/
│   └── VulnerabilityRepository.kt    # NEW: findLatestVulnerabilitiesForAssetIds() query
└── service/
    └── DomainVulnsService.kt          # FIX: Use new query instead of findAll()
```

**Structure Decision**: Bug fix to existing web application backend. No new files created, only modifications to repository and service layers.

## Complexity Tracking

> No constitution violations. This is a straightforward query optimization fix.

| Aspect | Assessment |
|--------|------------|
| Scope | Minimal - 2 files modified |
| Risk | Low - query change isolated to DomainVulnsService |
| Testing | Manual verification via Domain Vulnerabilities UI |

## Root Cause Analysis

### Problem

The `DomainVulnsService.getDomainVulnsSummary()` method was loading ALL vulnerabilities:

```kotlin
// OLD CODE (BUGGY)
val allVulnerabilities = vulnerabilityRepository.findAll().toList()
    .filter { vuln -> assetIds.contains(vuln.asset.id) }
```

This loaded every vulnerability record including:
- Historical imports with older `importTimestamp`
- Duplicates from multiple import cycles
- Remediated vulnerabilities that should no longer appear

### Solution

Added a new repository method with a native SQL query that filters to only the latest import per asset:

```kotlin
// NEW: VulnerabilityRepository.kt
@Query(
    value = """
    SELECT v.* FROM vulnerability v
    INNER JOIN (
        SELECT asset_id, MAX(import_timestamp) as max_ts
        FROM vulnerability
        WHERE asset_id IN :assetIds
        GROUP BY asset_id
    ) latest ON v.asset_id = latest.asset_id AND v.import_timestamp = latest.max_ts
    WHERE v.asset_id IN :assetIds
    """,
    nativeQuery = true
)
fun findLatestVulnerabilitiesForAssetIds(assetIds: Set<Long>): List<Vulnerability>
```

Updated DomainVulnsService to use the new method:

```kotlin
// NEW CODE (FIXED)
val allVulnerabilities = if (assetIds.isNotEmpty()) {
    vulnerabilityRepository.findLatestVulnerabilitiesForAssetIds(assetIds.toSet())
} else {
    emptyList()
}
```

### Why This Works

1. **Transactional Replace Pattern**: Each import deletes old vulnerabilities and inserts new ones with the same `importTimestamp`
2. **Latest Import Query**: Groups by `asset_id`, finds `MAX(import_timestamp)`, and joins only those records
3. **Result**: Only current vulnerabilities are returned, matching CrowdStrike Lookup counts exactly

## Implementation Status

All fixes have been implemented and verified with successful build:

| File | Status | Change |
|------|--------|--------|
| VulnerabilityRepository.kt | Modified | Updated `findLatestVulnerabilitiesForAssetIds()` to use `DENSE_RANK()` window function |
| DomainVulnsService.kt | Modified | Use asset ID for grouping, use database-level asset filtering |
| VulnerabilityImportService.kt | Modified | Added transactional replace pattern (delete-before-insert) |

## Fixes Applied

### 1. VulnerabilityRepository.kt - Robust Query

Changed from timestamp equality comparison to `DENSE_RANK()` window function:

```sql
SELECT v.* FROM vulnerability v
WHERE v.id IN (
    SELECT id FROM (
        SELECT id, asset_id, import_timestamp,
               DENSE_RANK() OVER (PARTITION BY asset_id ORDER BY COALESCE(import_timestamp, '1970-01-01') DESC) as rnk
        FROM vulnerability
        WHERE asset_id IN :assetIds
    ) ranked
    WHERE rnk = 1
)
```

**Benefits**:
- Handles NULL import_timestamp (treated as oldest)
- No precision issues between Java and MariaDB
- Returns all vulnerabilities from latest import per asset

### 2. DomainVulnsService.kt - Better Grouping & Performance

```kotlin
// OLD: Group by object reference (unreliable with native queries)
val vulnsByAsset = allVulnerabilities.groupBy { it.asset }

// NEW: Group by asset ID (reliable)
val vulnsByAssetId = allVulnerabilities.groupBy { it.asset.id }
```

```kotlin
// OLD: Load ALL assets into memory
val allAssets = assetRepository.findAll().toList()
val assetsInDomains = allAssets.filter { ... }

// NEW: Database-level filtering
val assetsInDomains = assetRepository.findByAdDomainInIgnoreCase(domains)
```

### 3. VulnerabilityImportService.kt - Transactional Replace

Added delete-before-insert for Excel imports:

```kotlin
@Transactional
open fun importFromExcel(fileInputStream: InputStream, scanDate: LocalDateTime): VulnerabilityImportResponse {
    // ... parse rows ...

    // Delete existing vulnerabilities for each asset being imported
    val vulnsByAsset = vulnerabilities.groupBy { it.asset.id }
    for ((assetId, _) in vulnsByAsset) {
        if (assetId != null) {
            vulnerabilityRepository.deleteByAssetId(assetId)
        }
    }

    // Save all new vulnerabilities
    vulnerabilityRepository.saveAll(vulnerabilities)
}
```

## Next Steps

1. **Deploy the fix**: Restart the application with the new code
2. **Verify counts**: Check Domain Vulnerabilities view matches CrowdStrike Lookup
3. **Commit changes**: Once verified, commit with descriptive message

## Related Features

- **Feature 048**: Transactional replace pattern for duplicate prevention (correctly implemented)
- **Feature 043**: Domain-based vulnerability import
- **Feature 032**: CrowdStrike servers query import
