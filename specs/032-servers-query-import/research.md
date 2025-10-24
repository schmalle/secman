# Research: Servers Query Import

**Feature**: 032-servers-query-import
**Date**: 2025-10-21

## Overview

This document resolves technical unknowns and design decisions for the servers query import feature.

## Research Items

### 1. Workgroup Assignment for Imported Assets

**Question**: Should automatically imported server assets be assigned to workgroups?

**Research Findings**:

Looking at existing patterns in the codebase:
- Feature 008 (Workgroup-Based Access Control) introduced workgroup filtering
- Manual asset creation via UI allows workgroup assignment
- Scan imports (Nmap, Masscan) track the `scanUploader` user for ownership
- Assets can have ZERO workgroups (nullable relationship)
- Users see assets from: their workgroups + assets they created/uploaded

**Decision**: Do NOT automatically assign workgroups to CrowdStrike-imported assets

**Rationale**:
1. **Principle of Least Privilege**: Imported assets should be visible only to admins by default until explicitly assigned to workgroups
2. **Consistency**: Similar to scan imports - asset created, user tracked (scanUploader), but no automatic workgroup assignment
3. **Flexibility**: Admins can manually assign imported assets to appropriate workgroups after review
4. **Security**: Prevents accidental exposure of newly discovered servers to all users
5. **Explicit Assignment**: Workgroup membership is a deliberate access control decision, not automatic

**Implementation**:
- Create Asset records with empty workgroups set (`workgroups = mutableSetOf()`)
- Track the CLI operation as system-level import (no specific user)
- Set `owner` field to "CrowdStrike Import" to indicate automated origin
- Leave `manualCreator` and `scanUploader` fields null (not applicable for CLI imports)

**Alternatives Considered**:
- **Assign to all workgroups**: Rejected - violates least privilege, exposes sensitive data
- **Assign based on metadata**: Rejected - no reliable mapping from CrowdStrike groups to secman workgroups
- **Require workgroup parameter**: Rejected - adds complexity, CLI is system-level operation

---

### 2. Exponential Backoff Implementation Pattern

**Question**: What exponential backoff pattern should be used for CrowdStrike API rate limiting?

**Research Findings**:

Industry best practices for API rate limiting:
- AWS SDK: Exponential backoff with jitter (random delay variation)
- Google Cloud: Base delay 1s, multiplier 2x, max delay 64s
- Stripe API: Retry after delay from `Retry-After` header if present, else exponential backoff
- CrowdStrike docs: Recommend exponential backoff for 429 responses

**Decision**: Use simple exponential backoff: 1s, 2s, 4s, 8s (max 3 retries)

**Rationale**:
1. **Simplicity**: No jitter needed for single-threaded CLI application
2. **Fast recovery**: Short initial delay (1s) for transient rate limits
3. **Progressive backing off**: Doubles each retry to respect persistent rate limits
4. **Bounded**: Max 8s delay prevents indefinite waiting
5. **Limited retries**: 3 retries (4 total attempts) balances success rate vs. user experience
6. **Total time**: Max ~15s retry overhead (1+2+4+8) acceptable for batch import operation

**Implementation**:
```kotlin
fun <T> retryWithExponentialBackoff(maxRetries: Int = 3, block: () -> T): T {
    var delay = 1000L // Start with 1 second
    repeat(maxRetries) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            if (e is RateLimitException && attempt < maxRetries - 1) {
                Thread.sleep(delay)
                delay *= 2 // Double the delay
            } else {
                throw e // Re-throw if not rate limit or max retries exceeded
            }
        }
    }
    return block() // Final attempt without catch
}
```

**Alternatives Considered**:
- **Jittered backoff**: Rejected - unnecessary for single-threaded CLI
- **Fixed delay**: Rejected - doesn't respect persistent rate limiting
- **Unlimited retries**: Rejected - poor user experience, infinite loops
- **Retry-After header**: Accepted as enhancement - check header if present, fall back to exponential

---

### 3. Transaction Scope for Vulnerability Replacement

**Question**: What is the appropriate transaction boundary for replacing vulnerabilities?

**Research Findings**:

Existing patterns in secman codebase:
- AssetBulkDeleteService: Manual cascade delete with EntityManager.clear()
- AssetImportService: Batch persistence with Repository.saveAll()
- VulnerabilityExceptionRequestService: Optimistic locking with @Version

Hibernate transaction options:
- Method-level @Transactional: Automatic rollback on exception
- Programmatic transactions: Manual control with EntityManager
- Batch operations: tradeoff between atomicity and performance

**Decision**: Use method-level @Transactional per server (one transaction per server)

**Rationale**:
1. **Atomicity**: Delete + insert must both succeed or both fail for each server
2. **Isolation**: Each server's import is independent - failure of one doesn't affect others
3. **Performance**: Smaller transactions reduce lock contention on vulnerability table
4. **Error recovery**: Partial failures provide clear statistics (which servers succeeded/failed)
5. **Simplicity**: Declarative @Transactional is cleaner than manual transaction management

**Implementation**:
```kotlin
@Transactional
fun importVulnerabilitiesForServer(assetId: Long, vulnerabilities: List<VulnerabilityDto>): Int {
    // 1. Delete all existing vulnerabilities for this asset
    vulnerabilityRepository.deleteByAssetId(assetId)

    // 2. Create new vulnerability records
    val newVulnerabilities = vulnerabilities.map { dto ->
        Vulnerability(
            asset = assetRepository.findById(assetId).get(),
            vulnerabilityId = dto.cveId,
            cvssSeverity = dto.severity,
            vulnerableProductVersions = dto.affectedProduct,
            daysOpen = dto.daysOpen.toString(),
            scanTimestamp = LocalDateTime.now()
        )
    }

    // 3. Batch save
    vulnerabilityRepository.saveAll(newVulnerabilities)

    return newVulnerabilities.size
}
```

**Alternatives Considered**:
- **Single transaction for all servers**: Rejected - all-or-nothing failure unacceptable for large imports
- **No transaction**: Rejected - violates data consistency requirements
- **Manual transaction with EntityManager**: Rejected - @Transactional simpler and sufficient

---

### 4. CVE ID Validation and Filtering

**Question**: How should vulnerabilities with missing CVE IDs be handled during filtering?

**Research Findings**:

CVE identifier format:
- Standard: CVE-YYYY-NNNNN (e.g., CVE-2024-1234)
- CrowdStrike may return empty string, null, or non-CVE identifiers

Existing vulnerability handling:
- Vulnerability.vulnerabilityId is nullable (String?)
- Database allows NULL in vulnerability_id column
- No unique constraint on vulnerability_id (duplicates allowed)

**Decision**: Skip vulnerabilities with missing/empty CVE IDs during import (log warning)

**Rationale**:
1. **Data quality**: Per clarification session, only import vulnerabilities with valid CVE IDs
2. **Traceability**: CVE ID is essential for tracking and remediation
3. **Reporting**: Separate "skipped" count in statistics alerts user to data quality issues
4. **Visibility**: Warning logs provide audit trail for skipped items

**Implementation**:
```kotlin
fun filterValidVulnerabilities(vulnerabilities: List<CrowdStrikeVulnerabilityDto>): Pair<List<CrowdStrikeVulnerabilityDto>, Int> {
    val valid = mutableListOf<CrowdStrikeVulnerabilityDto>()
    var skipped = 0

    vulnerabilities.forEach { vuln ->
        if (vuln.cveId.isNullOrBlank()) {
            log.warn("Skipping vulnerability without CVE ID for asset ${vuln.hostname}")
            skipped++
        } else {
            valid.add(vuln)
        }
    }

    return Pair(valid, skipped)
}
```

**Alternatives Considered**:
- **Import with null CVE ID**: Rejected - per user clarification, skip instead
- **Use alternative identifier**: Rejected - no reliable fallback identifier in CrowdStrike response
- **Fail entire import**: Rejected - too strict, loses valid data

---

## Summary

All research items resolved. Key decisions:
1. **Workgroup assignment**: Do NOT auto-assign (empty workgroups, manual assignment later)
2. **Retry strategy**: Exponential backoff 1s, 2s, 4s, 8s (max 3 retries)
3. **Transaction scope**: Per-server @Transactional (atomic delete+insert)
4. **CVE ID handling**: Skip missing/empty, track in statistics, log warnings

No blocking unknowns remain. Ready for Phase 1 (data model and contracts).
