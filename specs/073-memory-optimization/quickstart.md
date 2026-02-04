# Quickstart Guide: Memory Optimization Implementation

**Feature**: 073-memory-optimization
**Date**: 2026-02-03

## Prerequisites

- Kotlin 2.3.0 / Java 25
- Micronaut 4.10
- MariaDB 11.4 running with secman database
- Existing secman backend running

## Implementation Order

Follow this order to minimize risk and enable incremental testing:

### Phase 1: Configuration & Metrics (Low Risk)

1. **Add MemoryOptimizationConfig**
   - Create `src/backendng/src/main/kotlin/com/secman/config/MemoryOptimizationConfig.kt`
   - Add configuration to `application.yml`
   - All flags default to `true` (optimized mode)

2. **Add Memory Metrics Endpoint**
   - Create `src/backendng/src/main/kotlin/com/secman/controller/MemoryController.kt`
   - Enable in `application.yml` endpoints section
   - Test: `curl http://localhost:8080/memory`

### Phase 2: DTO Streamlining (Low-Medium Risk)

3. **Remove Asset from VulnerabilityWithExceptionDto**
   - Locate DTO in `VulnerabilityService.kt`
   - Remove `asset: Asset` field
   - Update all construction sites to remove asset parameter
   - Verify frontend uses flat fields (assetId, assetName, assetIp)

### Phase 3: Query Optimizations (Medium Risk)

4. **Add SQL-level Exception Filtering**
   - Add `findWithExceptionStatusFilter()` to `VulnerabilityRepository`
   - Modify `VulnerabilityService.getCurrentVulnerabilities()` to use new query
   - Feature flag controls fallback to original behavior

5. **Unify Access Control Query**
   - Add `findAccessibleAssets()` to `AssetRepository`
   - Modify `AssetFilterService.getAccessibleAssets()` to use single query
   - Compare results with original implementation for validation

6. **Add Batched Duplicate Cleanup**
   - Add `findDuplicateIds()` to `VulnerabilityRepository`
   - Modify cleanup routine to process in batches
   - Replace `findAll()` with batched approach

### Phase 4: Entity Loading (Medium Risk - Feature Flagged)

7. **Change Asset.workgroups to LAZY**
   - Modify `Asset.kt`: `fetch = FetchType.LAZY`
   - Add `@EntityGraph` queries to `AssetRepository`
   - Update all code paths that need workgroups:
     - `getEffectiveCriticality()` callers
     - Asset detail endpoints
     - Asset update operations
   - Feature flag enables rollback to EAGER behavior

8. **Change User.workgroups to LAZY**
   - Modify `User.kt`: `fetch = FetchType.LAZY`
   - Update authentication/authorization paths
   - Test all login and permission check flows

### Phase 5: Export Streaming (Low Risk)

9. **Implement Streaming Export**
   - Modify `VulnerabilityExportService.exportVulnerabilities()`
   - Remove intermediate `allVulnerabilities` list
   - Write to SXSSFWorkbook directly during fetch loop

## Verification Commands

```bash
# Build and test
cd src/backendng
./gradlew build

# Memory endpoint
curl -s http://localhost:8080/memory | jq

# Vulnerability query (check response time and size)
curl -s "http://localhost:8080/api/vulnerabilities/current?page=0&size=50" \
  -H "Authorization: Bearer $TOKEN" | jq '.content | length'

# Export with timing
time curl -s "http://localhost:8080/api/vulnerabilities/export" \
  -H "Authorization: Bearer $TOKEN" \
  -o vulnerabilities.xlsx
```

## Rollback Procedures

### Immediate Rollback (Runtime)
```yaml
# Set in environment or application.yml
secman:
  memory:
    lazy-loading-enabled: false
    streaming-exports-enabled: false
```

### Code Rollback
```bash
# Revert entity changes
git checkout main -- src/backendng/src/main/kotlin/com/secman/domain/Asset.kt
git checkout main -- src/backendng/src/main/kotlin/com/secman/domain/User.kt
```

## Success Indicators

| Metric | Before | Target | How to Measure |
|--------|--------|--------|----------------|
| Query memory spike | 100MB+ | <50MB | JMX/memory endpoint during query |
| Export memory spike | 179MB+ | <100MB | JMX/memory endpoint during export |
| API response size | X bytes | 0.6X bytes | `curl -w '%{size_download}'` |
| Asset list queries | 51 queries | 2-3 queries | Hibernate SQL logging |

## Common Issues

### LazyInitializationException
**Symptom**: Exception when accessing workgroups outside transaction
**Solution**:
1. Use `@EntityGraph` query for that code path
2. Or toggle `lazy-loading-enabled: false` for rollback

### Export Timeout
**Symptom**: Large export times out
**Solution**: Streaming should reduce memory, but if still slow:
1. Check database query performance
2. Increase timeout in nginx/load balancer

### Access Control Query Performance
**Symptom**: Unified query slower than separate queries
**Solution**:
1. Check index usage: `EXPLAIN` the query
2. Consider adding composite index on access control columns
3. Fall back to separate queries if needed
