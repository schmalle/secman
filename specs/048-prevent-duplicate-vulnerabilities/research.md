# Research: Prevent Duplicate Vulnerabilities in CrowdStrike Import

**Date**: 2025-11-16
**Feature**: 048-prevent-duplicate-vulnerabilities

## Current Implementation Analysis

### Duplicate Prevention Mechanism (ALREADY IMPLEMENTED)

**Location**: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt`

**Pattern**: Transactional Replace (Delete + Insert)

```kotlin
@Transactional
open fun importVulnerabilitiesForServer(batch: CrowdStrikeVulnerabilityBatchDto): ServerImportResult {
    // 1. Find or create asset
    val (asset, isNewAsset) = findOrCreateAsset(batch)

    // 2. Delete ALL existing vulnerabilities for this asset
    val deletedCount = vulnerabilityRepository.deleteByAssetId(asset.id!!)

    // 3. Filter and create NEW vulnerability records
    val vulnerabilities = vulnsWithCve.map { /* create Vulnerability entities */ }

    // 4. Save all vulnerabilities in batch
    vulnerabilityRepository.saveAll(vulnerabilities)

    return ServerImportResult(...)
}
```

**Key Characteristics**:
- Each asset processed in its own transaction (`@Transactional` annotation)
- Complete replacement: old vulnerabilities deleted before new ones inserted
- Atomic operation: both delete and insert succeed or both fail (transaction rollback)
- Per-asset isolation: System A's vulnerabilities independent from System B's

**Why This Prevents Duplicates**:
1. Delete operation removes ALL vulnerabilities for the asset (line 169: `vulnerabilityRepository.deleteByAssetId(asset.id!!)`)
2. Insert operation creates fresh records from import data
3. No merging or updating of individual vulnerability records
4. If same import runs twice, second run deletes first run's data and recreates it

**Trade-offs**:
- ✅ Simple logic - no complex merge algorithms
- ✅ Guaranteed consistency - no partial states
- ✅ Idempotent - same input = same output
- ⚠️ Brief window where asset has no vulnerabilities (during delete → insert)
- ⚠️ Audit trail lost - can't track when individual CVE was first/last seen (acceptable for this use case)

### Database Transaction Isolation

**Configuration**: `src/backendng/src/main/resources/application.yml`

**Expected Behavior**:
- Micronaut + Hibernate JPA defaults to READ_COMMITTED isolation level
- Transactions are isolated from each other during execution
- If transaction fails, all operations rolled back (delete + insert both reverted)

**Concurrent Import Handling**:
- Different assets: Can process in parallel (no shared locks)
- Same asset: Second transaction waits for first to complete (row-level locking)
- Transaction timeout prevents indefinite waits

### Batch Processing Pattern

**Location**: `src/cli/src/main/kotlin/com/secman/cli/service/VulnerabilityStorageService.kt`

**Pattern**: Chunked Batch Processing (lines 248-296)

```kotlin
fun storeServerVulnerabilities(
    serverBatches: Map<String, ServerVulnerabilityBatch>,
    batchSize: Int = 50
): BatchStorageResult {
    val serverChunks = serverBatches.entries.chunked(batchSize)

    serverChunks.forEachIndexed { index, chunk ->
        val chunkResult = processSingleBatch(chunkMap, backendUrl, authToken)
        // Aggregate results
    }
}
```

**Why This Matters**:
- Prevents 413 Request Entity Too Large errors
- Each chunk is independent - failure of one doesn't affect others
- Backend processes each server in the chunk sequentially with individual transactions

## Testing Strategy Research

### Decision: Integration Testing Approach

**Chosen**: JUnit 5 + Micronaut Test + H2 In-Memory Database

**Rationale**:
- Micronaut Test provides transaction management for tests
- H2 in-memory database allows fast test execution without external dependencies
- Can test actual transaction behavior (delete + insert atomicity)
- Existing test infrastructure in project

**Test Categories**:

1. **Idempotent Import Tests**
   - Same data imported multiple times
   - Verify final state matches expected state
   - Verify no duplicate records exist

2. **Concurrent Import Tests**
   - Multiple threads importing data for different assets
   - Verify no race conditions or deadlocks
   - Verify each asset has correct final state

3. **Transaction Rollback Tests**
   - Simulate failures during import (after delete, during insert)
   - Verify rollback leaves database in consistent state
   - Verify no partial data or orphaned records

4. **Performance Tests**
   - Large dataset imports (1000 assets, 10000 vulnerabilities)
   - Measure execution time
   - Verify completion within 5-minute target

### Alternatives Considered

**Alternative 1: Upsert Pattern**
- Check if vulnerability exists, update if yes, insert if no
- Rejected: More complex, requires unique constraint on (asset_id, cve_id)
- Rejected: Doesn't handle remediated vulnerabilities (would keep old data)

**Alternative 2: Soft Delete Pattern**
- Mark vulnerabilities as deleted instead of removing
- Rejected: Adds complexity, requires filtering in all queries
- Rejected: Doesn't match project requirements (hard delete is acceptable)

**Alternative 3: Differential Import**
- Compare current state with new state, apply only differences
- Rejected: Complex diff algorithm required
- Rejected: Risk of drift if source of truth changes

**Why Transactional Replace Wins**:
- Simplest implementation (delete + insert)
- Source of truth is CrowdStrike data, not our database
- Idempotent by design
- No complex merge logic needed

## Database Constraints Research

### Current Schema (Vulnerability Entity)

```kotlin
@Entity
@Table(name = "vulnerability")
class Vulnerability(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    var asset: Asset,

    @Column(name = "vulnerability_id", nullable = false, length = 255)
    var vulnerabilityId: String,

    // ... other fields
)
```

**Key Points**:
- No unique constraint on (asset_id, vulnerability_id) combination
- Foreign key to Asset with cascade behavior
- Transactional replace doesn't need unique constraint (always deletes first)

**Decision**: No schema changes needed
- Current schema supports transactional replace pattern
- Unique constraint not required because we delete all before inserting
- Adding constraint would provide extra safety but isn't necessary

## Performance Considerations

### Current Performance Characteristics

From spec.md Success Criteria:
- Target: 1,000 assets with 10,000 vulnerabilities in < 5 minutes
- Current batch size: 50 servers per batch (configurable)

**Bottlenecks**:
1. Delete operation: O(n) where n = vulnerabilities per asset
2. Insert operation: Batch insert (efficient)
3. Network roundtrips: Chunked to avoid 413 errors

**Optimization Notes**:
- `deleteByAssetId()` is already efficient (single DELETE WHERE query)
- `saveAll()` uses batch insert (Hibernate optimization)
- Transaction overhead is per-asset (acceptable for idempotency)

**Decision**: Current implementation meets performance requirements
- No optimization needed for duplicate prevention
- If performance issues arise, increase batch size (current: 50)

## Edge Case Handling

### Edge Case 1: Concurrent Imports for Same Asset

**Scenario**: Two import processes try to update the same asset simultaneously

**Current Behavior**:
- Database row-level locking on Asset entity
- Second transaction waits for first to complete
- Sequential execution guaranteed by database

**Test Coverage Needed**: Verify second import doesn't corrupt first import's data

### Edge Case 2: Transaction Failure During Import

**Scenario**: Delete succeeds but insert fails (e.g., constraint violation, network error)

**Current Behavior**:
- `@Transactional` annotation ensures rollback
- Both delete and insert reverted
- Asset returns to pre-import state

**Test Coverage Needed**: Simulate failure scenarios and verify rollback

### Edge Case 3: Same Asset in Single Import Batch

**Scenario**: Import data contains same hostname multiple times

**Current Behavior**:
- Processed sequentially by backend
- Last entry wins (overwrites previous)
- No duplicate prevention needed (already handled by replace pattern)

**Test Coverage Needed**: Verify last entry is the final state

### Edge Case 4: Vulnerabilities Without CVE IDs

**Scenario**: Import data contains vulnerabilities with null/blank CVE IDs

**Current Behavior** (line 175 in CrowdStrikeVulnerabilityImportService.kt):
```kotlin
var vulnsWithCve = batch.vulnerabilities.filter { !it.cveId.isNullOrBlank() }
```

**Test Coverage Needed**: Verify these are skipped correctly

## Documentation Requirements

### What to Document

1. **Architecture Documentation** (`docs/CROWDSTRIKE_IMPORT.md`):
   - Transactional replace pattern explanation
   - Why this pattern prevents duplicates
   - When to use vs. alternatives (upsert, differential)
   - Performance characteristics
   - Edge case handling

2. **Code Comments** (in `CrowdStrikeVulnerabilityImportService.kt`):
   - Add KDoc to `importVulnerabilitiesForServer()` method
   - Explain duplicate prevention strategy
   - Document transaction behavior
   - Reference architecture doc for details

3. **README Updates** (if needed):
   - Add note about idempotent import behavior
   - Document CLI usage for repeated imports

## Summary of Research Findings

| Question | Answer | Source |
|----------|--------|--------|
| Does current implementation prevent duplicates? | ✅ Yes, via transactional replace pattern | CrowdStrikeVulnerabilityImportService.kt:168-172 |
| Is implementation idempotent? | ✅ Yes, same input always produces same output | Transaction atomicity + delete-then-insert |
| Are there race conditions? | ❌ No, database row-level locking handles concurrency | Micronaut @Transactional + JPA |
| Does it handle failures correctly? | ✅ Yes, transaction rollback on failure | @Transactional annotation |
| What testing is needed? | Integration tests for idempotency, concurrency, rollback | JUnit 5 + Micronaut Test |
| Are schema changes needed? | ❌ No, current schema sufficient | Existing Vulnerability + Asset entities |
| Is documentation needed? | ✅ Yes, architecture doc + code comments | New CROWDSTRIKE_IMPORT.md |

## Next Steps for Implementation

1. ✅ Research complete - no NEEDS CLARIFICATION items remaining
2. → Proceed to Phase 1: Design (data-model.md, contracts, quickstart.md)
3. → Phase 2: Tasks (test implementation, documentation)
