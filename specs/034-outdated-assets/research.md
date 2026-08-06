# Research: Outdated Assets View

**Feature**: 034-outdated-assets | **Date**: 2025-10-26
**Purpose**: Document technical unknowns requiring investigation before implementation

## Phase 0: Research Findings

### 1. Materialized View Implementation Strategy

**Question**: How should we implement the materialized view pattern in MariaDB with Hibernate/Micronaut Data?

**Options Evaluated**:
1. **Database materialized view** (MariaDB doesn't support materialized views natively)
2. **Pre-calculated table with refresh logic** ✅ RECOMMENDED
3. **Database triggers** (complex, error-prone)
4. **View with aggressive caching** (doesn't meet 10k asset performance requirement)

**Decision**: Use a regular JPA entity (`OutdatedAssetMaterializedView`) backed by a dedicated table with:
- Composite index on `(workgroupId, oldestVulnDays DESC)`
- Index on `lastCalculatedAt` for staleness checks
- Refresh logic in `MaterializedViewRefreshService` that deletes all rows and bulk inserts new data

**Why**:
- Native MariaDB materialized views don't exist
- Regular table with indexes gives full control over refresh strategy
- Micronaut Data supports efficient batch operations
- Existing pattern in codebase for entity-based data management

**References**:
- Existing entity pattern: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`
- Existing repository pattern: `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`

---

### 2. Asynchronous Refresh Job Architecture

**Question**: How to implement background refresh jobs with progress tracking in Micronaut?

**Options Evaluated**:
1. **@Scheduled methods** (no progress tracking, fire-and-forget)
2. **Micronaut Task framework** (async but limited progress support)
3. **Custom ExecutorService + RefreshJob entity** ✅ RECOMMENDED
4. **External job queue (RabbitMQ, etc.)** (overengineering for this use case)

**Decision**: Implement custom async pattern:
- `MaterializedViewRefreshJob` entity tracks state (RUNNING/COMPLETED/FAILED)
- `MaterializedViewRefreshService` uses `@Async` annotation for background execution
- Progress tracked via `assetsProcessed` and `totalAssets` fields
- SSE endpoint streams progress updates to frontend

**Why**:
- Existing SSE pattern in codebase (`ExceptionBadgeUpdateHandler.kt`)
- Simple, no external dependencies
- Full control over progress tracking
- Aligns with Micronaut's async execution model

**References**:
- SSE pattern: `src/backendng/src/main/kotlin/com/secman/controller/ExceptionBadgeUpdateHandler.kt`
- Micronaut async: https://docs.micronaut.io/latest/guide/#async

---

### 3. Workgroup-Based Access Control Integration

**Question**: How to apply existing workgroup filtering to materialized view queries?

**Investigation Needed**:
- ✅ Examine existing workgroup filtering in `VulnerabilityController.kt`
- ✅ Check if workgroup associations are available on `Asset` entity
- ✅ Determine if materialized view should store workgroup data denormalized or join at query time

**Findings**:
- `Asset` entity has `@ManyToMany` relationship with `Workgroup` (via `workgroups` field)
- Existing pattern: `VulnerabilityController` filters by workgroup at query time
- Materialized view should denormalize workgroup IDs as comma-separated string for performance

**Decision**: Store workgroup associations denormalized in materialized view:
```kotlin
@Column(name = "workgroup_ids")
var workgroupIds: String? = null  // e.g., "1,3,5"
```

At query time, filter using SQL `FIND_IN_SET()` or application-level filtering.

**References**:
- Asset entity: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt` (line 40+)
- Workgroup relationship: `@ManyToMany` pattern

---

### 4. CLI Import Integration Point

**Question**: Where in `CrowdStrikeVulnerabilityImportService` should we trigger the async refresh?

**Investigation**:
- ✅ Read `CrowdStrikeVulnerabilityImportService.kt` to find completion point
- ✅ Determine if trigger should happen per-server or after full batch

**Findings**:
- `importServerVulnerabilities()` processes full batch (line 45-87)
- Returns `ImportStatisticsDto` with summary (line 74-81)
- Ideal trigger point: after line 84 (before return statement)

**Decision**: Add async refresh trigger at end of `importServerVulnerabilities()`:
```kotlin
// After all servers processed, trigger async refresh
materializedViewRefreshService.triggerAsyncRefresh(triggeredBy = "CLI Import")
```

**Why**:
- Refresh once per CLI import (not once per server)
- Async means CLI returns immediately
- Captures all imported vulnerabilities in single refresh

**References**:
- Service file: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt:84`

---

### 5. SSE Progress Updates Implementation

**Question**: How to stream progress updates from background job to frontend?

**Investigation**:
- ✅ Examine existing SSE implementation in `ExceptionBadgeUpdateHandler.kt`
- ✅ Understand Sinks.Many pattern for multicast

**Findings**:
- Existing pattern uses Reactor `Sinks.Many.multicast()`
- Event listener pattern (`ApplicationEventListener<T>`)
- Custom event class (`ExceptionCountChangedEvent`)

**Decision**: Mirror existing SSE pattern:
1. Create `RefreshProgressEvent` domain event
2. Create `OutdatedAssetRefreshProgressHandler` SSE controller
3. Use `Sinks.Many.multicast()` for broadcasting
4. Emit events from `MaterializedViewRefreshService` as progress updates

**Event Structure**:
```kotlin
data class RefreshProgressEvent(
    val jobId: Long,
    val status: RefreshJobStatus,
    val progressPercentage: Int,
    val message: String?
)
```

**References**:
- SSE handler: `src/backendng/src/main/kotlin/com/secman/controller/ExceptionBadgeUpdateHandler.kt`
- Event pattern: `src/backendng/src/main/kotlin/com/secman/domain/ExceptionCountChangedEvent.kt`

---

### 6. Performance Optimization for 10,000+ Assets

**Question**: What indexes and query optimizations are needed for sub-2-second performance?

**Investigation**:
- ✅ Review existing index patterns in codebase
- ✅ Identify critical query patterns from user stories

**Findings**:
- Critical query: Get outdated assets filtered by workgroup, paginated, sorted
- Typical filters: minimum severity, search by asset name
- Sort options: oldestVulnDays DESC, assetName ASC

**Decision**: Create composite indexes:
```sql
CREATE INDEX idx_outdated_workgroup_sort
ON outdated_asset_materialized_view (workgroup_ids(100), oldest_vuln_days DESC);

CREATE INDEX idx_outdated_asset_name
ON outdated_asset_materialized_view (asset_name);

CREATE INDEX idx_outdated_severity
ON outdated_asset_materialized_view (critical_count, high_count);
```

**Query Pattern**:
```kotlin
@Query("""
    SELECT v FROM OutdatedAssetMaterializedView v
    WHERE (:workgroupIds IS NULL OR FIND_IN_SET(:workgroupId, v.workgroupIds) > 0)
    AND (:minSeverity IS NULL OR v.criticalCount > 0 OR v.highCount > 0)
    ORDER BY v.oldestVulnDays DESC
""")
fun findOutdatedAssets(workgroupIds: String?, minSeverity: String?, pageable: Pageable): Page<OutdatedAssetMaterializedView>
```

**References**:
- Existing pagination: Micronaut Data supports `Pageable` parameter

---

## Open Questions (To Resolve During Implementation)

### Q1: Concurrency Control for Refresh Operations
**Question**: How to prevent duplicate refresh jobs when user clicks "Refresh" multiple times?

**Approach**:
- Check for existing RUNNING job in database before starting new one
- Return 409 Conflict if refresh already in progress
- Use database-level locking if needed (`SELECT ... FOR UPDATE`)

**To Investigate**: Micronaut transaction isolation levels

---

### Q2: Refresh Timeout and Failure Handling
**Question**: What happens if refresh takes longer than 2 minutes?

**Approach**:
- Set timeout in `@Async` executor configuration
- Mark job as FAILED on timeout
- Log error details to `MaterializedViewRefreshJob.errorMessage`
- Frontend shows error message from job entity

**To Investigate**: Micronaut ExecutorService timeout configuration

---

### Q3: Staleness Indicator
**Question**: Should we show users when materialized view data is stale?

**Approach**:
- Include `lastRefreshedAt` timestamp in API response
- Frontend displays "Last updated: X minutes ago"
- Consider auto-refresh threshold (e.g., warn if > 1 hour old)

**Decision Deferred**: Implement basic timestamp first, add auto-refresh warning in future iteration

---

## Dependencies & External Resources

### Existing Code to Reference
- SSE pattern: `ExceptionBadgeUpdateHandler.kt`
- Event pattern: `ExceptionCountChangedEvent.kt`
- Import service: `CrowdStrikeVulnerabilityImportService.kt`
- RBAC pattern: `VulnerabilityController.kt` (workgroup filtering)
- Asset entity: `Asset.kt` (workgroup relationship)

### Micronaut Documentation
- Async execution: https://docs.micronaut.io/latest/guide/#async
- SSE: https://docs.micronaut.io/latest/guide/#sse
- Micronaut Data pagination: https://micronaut-projects.github.io/micronaut-data/latest/guide/#pagination

### Database Considerations
- MariaDB `FIND_IN_SET()` function for comma-separated list filtering
- Batch insert performance for 10k+ rows
- Index selectivity for workgroup filtering

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Refresh job timeout with 10k+ assets | Medium | High | Implement batch processing (1000 assets/chunk), timeout handling, progress tracking |
| Concurrent refresh requests | Medium | Medium | Database-level job locking, 409 Conflict response |
| Workgroup filtering performance degradation | Low | High | Denormalize workgroup IDs, create composite indexes, test with 10k dataset |
| SSE connection drops during long refresh | Medium | Low | Frontend reconnection logic, poll job status as fallback |
| Materialized view out of sync | Low | Low | Acceptable per spec (eventual consistency), manual refresh button available |

---

## Next Steps (Phase 1)

1. ✅ Create `data-model.md` with entity schemas
2. ✅ Define API contracts in `contracts/` directory
3. ✅ Update `.specify/agent-context/` files with feature context
4. Validate design against spec requirements
5. Proceed to `/speckit.tasks` for implementation planning
