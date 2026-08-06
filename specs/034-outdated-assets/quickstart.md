# Quickstart Guide: Outdated Assets View

**Feature**: 034-outdated-assets
**Branch**: `034-outdated-assets`
**Date**: 2025-10-26

## Overview

This guide helps developers quickly understand and implement the Outdated Assets View feature. It provides a high-level architecture overview, implementation sequence, and key integration points.

## What This Feature Does

**User-Facing**: Adds a new "Outdated Assets" submenu under "Vuln Management" showing all assets with vulnerabilities older than the configured threshold (default: 30 days). Provides fast queries (< 2 seconds for 10,000+ assets) using a pre-calculated materialized view.

**Technical**: Implements materialized view pattern with asynchronous background refresh triggered by CLI imports and manual user actions. Includes SSE-based progress tracking for user feedback.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Frontend (Astro/React)                      │
├─────────────────────────────────────────────────────────────────────┤
│  outdated-assets.astro                                              │
│    ├─> OutdatedAssetsList.tsx (main table, filters, search)        │
│    ├─> OutdatedAssetDetail.tsx (vulnerability details)             │
│    └─> EventSource (SSE for progress updates)                      │
└───────────────────────┬─────────────────────────────────────────────┘
                        │ REST API + SSE
┌───────────────────────▼─────────────────────────────────────────────┐
│                    Backend (Kotlin/Micronaut)                        │
├─────────────────────────────────────────────────────────────────────┤
│  Controllers:                                                        │
│    ├─> OutdatedAssetController (CRUD, list, filter)                │
│    └─> OutdatedAssetRefreshProgressHandler (SSE endpoint)          │
│                                                                      │
│  Services:                                                           │
│    ├─> OutdatedAssetService (business logic, access control)       │
│    ├─> MaterializedViewRefreshService (@Async refresh logic)       │
│    └─> CrowdStrikeVulnerabilityImportService (MODIFIED)            │
│                                                                      │
│  Repositories:                                                       │
│    ├─> OutdatedAssetMaterializedViewRepository                     │
│    └─> MaterializedViewRefreshJobRepository                        │
│                                                                      │
│  Entities:                                                           │
│    ├─> OutdatedAssetMaterializedView (pre-calculated data)         │
│    └─> MaterializedViewRefreshJob (progress tracking)              │
└───────────────────────┬─────────────────────────────────────────────┘
                        │
┌───────────────────────▼─────────────────────────────────────────────┐
│                      Database (MariaDB)                              │
├─────────────────────────────────────────────────────────────────────┤
│  Tables:                                                             │
│    ├─> outdated_asset_materialized_view (cached data)              │
│    ├─> materialized_view_refresh_job (job tracking)                │
│    ├─> assets (source data)                                        │
│    └─> vulnerabilities (source data)                               │
└─────────────────────────────────────────────────────────────────────┘
```

## Data Flow

### 1. CLI Import Triggers Refresh (Automatic)

```
1. User runs: falcon-vulns query --save
2. CrowdStrikeVulnerabilityImportService.importServerVulnerabilities()
3. After import completes → materializedViewRefreshService.triggerAsyncRefresh("CLI Import")
4. Background job starts, publishes progress events via SSE
5. Materialized view updated with new outdated asset data
```

### 2. Manual Refresh (User-Triggered)

```
1. User clicks "Refresh" button on Outdated Assets page
2. Frontend: POST /api/outdated-assets/refresh
3. Backend: Check for running job → 409 Conflict if exists
4. Backend: Create MaterializedViewRefreshJob entity
5. Backend: Start @Async refresh in background
6. Frontend: Connect to SSE endpoint /api/outdated-assets/refresh-progress
7. Backend: Publish progress events (0%, 25%, 50%, 75%, 100%)
8. Frontend: Update progress bar in real-time
9. On completion: Reload outdated assets list
```

### 3. Query Outdated Assets (User Viewing)

```
1. User navigates to "Vuln Management > Outdated Assets"
2. Frontend: GET /api/outdated-assets?page=0&size=20&sort=oldestVulnDays,desc
3. Backend: Apply workgroup filtering (VULN role) or show all (ADMIN role)
4. Backend: Query materialized view with indexes
5. Backend: Return paginated results (<2 seconds)
6. Frontend: Render table with asset name, overdue counts, oldest vuln age
```

## Implementation Sequence (TDD)

### Phase 1: Backend Entities & Repositories (P1)
**Goal**: Set up data layer

1. Create `OutdatedAssetMaterializedView` entity (domain/OutdatedAssetMaterializedView.kt)
2. Create `MaterializedViewRefreshJob` entity (domain/MaterializedViewRefreshJob.kt)
3. Create `RefreshProgressEvent` domain event (domain/RefreshProgressEvent.kt)
4. Create repositories (OutdatedAssetMaterializedViewRepository.kt, MaterializedViewRefreshJobRepository.kt)
5. Write contract tests verifying entity schemas

**Tests First**: Contract tests → Unit tests → Implementation

### Phase 2: Service Layer (P1)
**Goal**: Implement business logic

1. Create `OutdatedAssetService` (CRUD, workgroup filtering, threshold calculation)
2. Create `MaterializedViewRefreshService` (async refresh, progress tracking)
3. Modify `CrowdStrikeVulnerabilityImportService` (add refresh trigger)
4. Write service unit tests (MockK for dependencies)

**Tests First**: Service tests → Implementation → Refactor

### Phase 3: Controllers & API (P1)
**Goal**: Expose REST endpoints

1. Create `OutdatedAssetController` (GET list, GET vulnerabilities)
2. Create `OutdatedAssetRefreshProgressHandler` (SSE endpoint)
3. Add refresh trigger endpoint (POST /api/outdated-assets/refresh)
4. Write contract tests (API validation)

**Tests First**: Controller contract tests → Implementation

### Phase 4: Frontend Components (P1)
**Goal**: Build UI

1. Create `outdated-assets.astro` page
2. Create `OutdatedAssetsList.tsx` (table, pagination, filters)
3. Create `OutdatedAssetDetail.tsx` (vulnerability list modal/page)
4. Create `outdatedAssetsApi.ts` (Axios client)
5. Add menu item to left navigation
6. Write Playwright E2E tests

**Tests First**: E2E test scenarios → Component implementation

### Phase 5: SSE Progress Tracking (P2)
**Goal**: Real-time progress updates

1. Implement SSE connection in frontend (EventSource API)
2. Implement progress bar component
3. Test SSE reconnection on connection drop
4. Test multiple concurrent SSE clients

### Phase 6: Integration & Performance Testing (P2)
**Goal**: Validate requirements

1. Load test with 10,000 assets (verify <2s page load)
2. Test refresh operation with 10,000 assets (verify <30s)
3. Test filter/search performance (verify <1s)
4. Test workgroup access control (VULN role filtering)

## Key Integration Points

### 1. Existing VulnerabilityConfigService
**Location**: `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityConfigService.kt`
**Usage**: Get `reminderOneDays` threshold for overdue calculation

```kotlin
val threshold = vulnerabilityConfigService.getReminderOneDays()
```

### 2. Existing RBAC Pattern
**Reference**: `VulnerabilityController.kt`
**Usage**: Apply workgroup filtering for VULN users

```kotlin
if (!authentication.roles.contains("ADMIN")) {
    // Filter by user's workgroups
    val userWorkgroups = authentication.attributes["workgroups"] as List<Long>
    // Apply workgroup filter
}
```

### 3. Existing SSE Pattern
**Reference**: `ExceptionBadgeUpdateHandler.kt`
**Usage**: Stream progress updates using Reactor Sinks.Many

```kotlin
private val progressSink: Many<RefreshProgressData> = Sinks.many()
    .multicast()
    .onBackpressureBuffer()
```

### 4. CrowdStrike CLI Import Hook
**Location**: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt:84`
**Modification**: Add async refresh trigger after import completes

```kotlin
// After line 84 (before return statement)
materializedViewRefreshService.triggerAsyncRefresh("CLI Import")
```

## Configuration

### Database Indexes (Auto-created by Hibernate)
```sql
CREATE INDEX idx_outdated_workgroup_sort
ON outdated_asset_materialized_view (workgroup_ids(100), oldest_vuln_days DESC);

CREATE INDEX idx_outdated_asset_name
ON outdated_asset_materialized_view (asset_name);

CREATE INDEX idx_outdated_severity
ON outdated_asset_materialized_view (critical_count, high_count);
```

### Micronaut Async Configuration (application.yml)
```yaml
micronaut:
  executors:
    refresh:
      type: scheduled
      core-pool-size: 2
```

## Testing Strategy

### Unit Tests (JUnit 5 + MockK)
- Service layer logic (OutdatedAssetService, MaterializedViewRefreshService)
- Entity methods (MaterializedViewRefreshJob.updateProgress(), etc.)
- Access control logic (workgroup filtering)

### Contract Tests
- Entity schemas (OutdatedAssetMaterializedView, MaterializedViewRefreshJob)
- API request/response formats (all 5 endpoints)
- DTO serialization (@Serdeable validation)

### Integration Tests
- Full refresh workflow (CLI import → async refresh → materialized view updated)
- SSE connection lifecycle (connect → progress → disconnect)
- Concurrent refresh prevention (409 Conflict)

### E2E Tests (Playwright)
- Navigate to Outdated Assets page → verify data displayed
- Click "Refresh" → verify progress bar → verify data updated
- Filter by severity → verify results
- Search by asset name → verify results
- Pagination → verify page navigation

### Performance Tests
- Load 10,000 assets → verify page load <2s
- Refresh 10,000 assets → verify completion <30s
- Filter/search on 10,000 assets → verify response <1s

## Common Pitfalls & Solutions

### Pitfall 1: Refresh Timeout on Large Datasets
**Problem**: Refresh operation times out with 10,000+ assets

**Solution**: Batch processing (1000 assets per chunk) with progress updates
```kotlin
outdatedAssets.chunked(1000).forEachIndexed { index, batch ->
    // Process batch
    job.updateProgress(processed = (index + 1) * 1000)
    refreshJobRepository.update(job)
    eventPublisher.publish(RefreshProgressEvent(...))
}
```

### Pitfall 2: Workgroup Filtering Performance
**Problem**: Slow queries when filtering by workgroup with comma-separated IDs

**Solution**: Denormalize workgroup IDs, use indexed queries
```kotlin
WHERE v.workgroupIds IS NULL OR v.workgroupIds LIKE CONCAT('%', :workgroupId, '%')
```

### Pitfall 3: SSE Connection Drops
**Problem**: Frontend loses progress updates when SSE connection drops

**Solution**: Implement reconnection logic + polling fallback
```javascript
eventSource.onerror = () => {
    eventSource.close();
    // Fall back to polling GET /api/outdated-assets/refresh-status/{jobId}
};
```

### Pitfall 4: Stale Data After Config Change
**Problem**: Materialized view not updated when threshold changes

**Solution**: Trigger refresh in VulnerabilityConfigService.updateConfig()
```kotlin
fun updateConfig(newConfig: VulnerabilityConfig) {
    configRepository.update(newConfig)
    materializedViewRefreshService.triggerAsyncRefresh("Config Change")
}
```

## Performance Targets

| Metric | Target | How to Measure |
|--------|--------|----------------|
| Page load time | <2s | Playwright E2E with 10k assets |
| Refresh duration | <30s | Integration test with 10k assets |
| Filter/search response | <1s | API test with 10k assets |
| Concurrent users | 50+ | Load test with concurrent SSE connections |

## Rollout Plan

### Development
1. Implement on `034-outdated-assets` branch
2. Test with 1,000 assets
3. Test with 10,000 assets
4. Code review

### Staging
1. Deploy to staging environment
2. Load test with production-sized dataset
3. UAT with security team

### Production
1. Deploy during maintenance window
2. Monitor refresh job completion times
3. Monitor page load times
4. Gather user feedback

## Troubleshooting

### Issue: Materialized view is empty
**Check**:
1. Run refresh manually: `POST /api/outdated-assets/refresh`
2. Check job status: `GET /api/outdated-assets/refresh-status/{jobId}`
3. Verify vulnerabilities exist with `daysOpen > threshold`
4. Check backend logs for errors

### Issue: Page loads slowly
**Check**:
1. Verify indexes exist: `SHOW INDEX FROM outdated_asset_materialized_view;`
2. Check query plan: `EXPLAIN SELECT ...`
3. Verify materialized view has data: `SELECT COUNT(*) FROM outdated_asset_materialized_view;`

### Issue: Refresh job stuck in RUNNING
**Check**:
1. Query job: `SELECT * FROM materialized_view_refresh_job WHERE status = 'RUNNING';`
2. Check backend logs for timeout/errors
3. Manually mark as FAILED if stuck: `UPDATE materialized_view_refresh_job SET status = 'FAILED' WHERE id = ?;`

## Related Documentation

- [spec.md](spec.md) - Feature specification
- [data-model.md](data-model.md) - Entity schemas
- [contracts/](contracts/) - API contracts
- [research.md](research.md) - Technical research
- [plan.md](plan.md) - Implementation plan

## Next Steps

1. Run `/speckit.tasks` to generate detailed task breakdown
2. Start with Phase 1 (Entities & Repositories)
3. Follow TDD workflow (Tests → Implementation → Refactor)
4. Validate each phase before proceeding

---

**Ready to implement?** Run `/speckit.tasks` to generate actionable task list.
