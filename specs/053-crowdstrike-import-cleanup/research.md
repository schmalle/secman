# Research: CrowdStrike Import Cleanup

**Feature**: 053-crowdstrike-import-cleanup
**Date**: 2025-12-08
**Type**: Bug Investigation

## Executive Summary

The investigation confirmed that the transactional replace pattern (Feature 048) is **correctly implemented**. The root cause of inflated vulnerability counts was in `DomainVulnsService.getDomainVulnsSummary()` which loaded ALL vulnerabilities via `findAll()` instead of filtering to the latest import per asset.

## Investigation Findings

### 1. Transactional Replace Pattern (Feature 048)

**Decision**: Pattern is correctly implemented in `CrowdStrikeVulnerabilityImportService`
**Rationale**: Code review confirmed delete-before-insert within `@Transactional` boundary
**Alternatives considered**: None - pattern is working as designed

**Key code path verified**:
```
CrowdStrikeVulnerabilityImportService.importVulnerabilitiesForServer()
├── Line 199: findOrCreateAsset(batch)
├── Line 202: vulnerabilityRepository.deleteByAssetId(asset.id!!)  ← DELETE
├── Lines 232-268: Create new Vulnerability entities
└── Line 272: vulnerabilityRepository.saveAll(vulnerabilities)    ← INSERT
```

### 2. CLI Import Call Chain

**Decision**: CLI correctly routes to the transactional import service
**Rationale**: Full call chain traced from CLI command to database operations

**Call chain**:
```
ServersCommand.execute()
  └── VulnerabilityStorageService.storeServerVulnerabilities()
        └── HTTP POST /api/crowdstrike/servers/import
              └── CrowdStrikeController.importServerVulnerabilities()
                    └── CrowdStrikeVulnerabilityImportService.importServerVulnerabilities()
                          └── importVulnerabilitiesForServer() [per server, @Transactional]
                                └── vulnerabilityRepository.deleteByAssetId()
                                └── vulnerabilityRepository.saveAll()
```

### 3. Root Cause Identified

**Decision**: Bug was in DomainVulnsService query, not the import pattern
**Rationale**: `findAll()` loads historical imports; need to filter to latest import per asset

**Buggy code** (DomainVulnsService.kt lines 120-122):
```kotlin
val allVulnerabilities = vulnerabilityRepository.findAll().toList()
    .filter { vuln -> assetIds.contains(vuln.asset.id) }
```

**Problems**:
1. Loads ALL vulnerabilities into memory (performance issue at scale)
2. Includes historical imports with older `importTimestamp`
3. Does not filter to current state per asset

### 4. Fix Implementation

**Decision**: Add new repository query `findLatestVulnerabilitiesForAssetIds()`
**Rationale**: Native SQL subquery filters to MAX(import_timestamp) per asset
**Alternatives considered**:
- JPQL tuple comparison (not supported in Hibernate)
- Window functions (less compatible across databases)

**New query** (VulnerabilityRepository.kt):
```sql
SELECT v.* FROM vulnerability v
INNER JOIN (
    SELECT asset_id, MAX(import_timestamp) as max_ts
    FROM vulnerability
    WHERE asset_id IN :assetIds
    GROUP BY asset_id
) latest ON v.asset_id = latest.asset_id AND v.import_timestamp = latest.max_ts
WHERE v.asset_id IN :assetIds
```

**Query behavior**:
- Groups vulnerabilities by asset
- Finds the latest `import_timestamp` for each asset
- Returns only vulnerabilities with that timestamp
- Result: Current state, no duplicates from historical imports

## Files Modified

| File | Change | Lines |
|------|--------|-------|
| `VulnerabilityRepository.kt` | Added `findLatestVulnerabilitiesForAssetIds()` | +28 lines |
| `DomainVulnsService.kt` | Use new query instead of `findAll()` | +5/-2 lines |

## Verification Steps

1. Build the backend: `./gradlew build`
2. Start the application
3. Navigate to Domain Vulnerabilities view
4. Compare counts with CrowdStrike Lookup (should match exactly)
5. Run an import cycle and verify counts remain consistent

## Related Documentation

- **Feature 048 docs**: `docs/CROWDSTRIKE_IMPORT.md` - Transactional replace pattern
- **CLAUDE.md**: Duplicate Prevention Pattern section
- **Feature 032**: CrowdStrike servers query import

## Conclusion

The transactional replace pattern is correctly preventing duplicates at import time. The inflated counts were caused by a query-side bug that loaded historical data instead of filtering to current state. The fix has been implemented in uncommitted changes and is ready for verification and commit.
