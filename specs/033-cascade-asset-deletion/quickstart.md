# Quickstart Guide: Cascade Asset Deletion

**Feature**: Cascade Asset Deletion with Related Data
**Date**: 2025-10-24
**Target Audience**: Developers implementing this feature

## Overview

This guide provides step-by-step instructions to implement cascade deletion for Asset entities. Follow the TDD approach: write tests first, then implement to make them pass.

## Prerequisites

- Feature branch `033-cascade-asset-deletion` checked out
- Development environment running (MariaDB, backend, frontend)
- Existing codebase familiar (Asset, Vulnerability entities)
- JUnit 5 + MockK for testing
- Micronaut 4.4 project structure

## Implementation Phases

### Phase 1: Backend - Audit Log Entity

**Goal**: Create audit log infrastructure for tracking cascade deletions

**Steps**:

1. **Create Entity** (`src/backendng/src/main/kotlin/com/secman/domain/AssetDeletionAuditLog.kt`):
   ```kotlin
   @Entity
   @Table(name = "asset_deletion_audit_log")
   data class AssetDeletionAuditLog(
       @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
       val id: Long? = null,
       @Column(name = "asset_id", nullable = false)
       val assetId: Long,
       // ... (see data-model.md for complete fields)
   )
   ```

2. **Create Repository** (`src/backendng/src/main/kotlin/com/secman/repository/AssetDeletionAuditLogRepository.kt`):
   ```kotlin
   @Repository
   interface AssetDeletionAuditLogRepository : JpaRepository<AssetDeletionAuditLog, Long> {
       fun findByAssetId(assetId: Long): List<AssetDeletionAuditLog>
       fun findByDeletedByUser(username: String): List<AssetDeletionAuditLog>
       fun findByBulkOperationId(bulkOpId: String): List<AssetDeletionAuditLog>
   }
   ```

3. **Test** (`src/backendng/src/test/kotlin/com/secman/domain/AssetDeletionAuditLogTest.kt`):
   - Unit test: Create audit log, save, verify JSON serialization
   - Integration test: Save/retrieve from database

**Verification**: Run `./gradlew test --tests AssetDeletionAuditLogTest`

---

### Phase 2: Backend - Cascade Delete Service

**Goal**: Implement core cascade deletion logic with locking

**Steps**:

1. **Write Contract Test** (`src/backendng/src/test/kotlin/com/secman/contract/AssetCascadeDeleteContractTest.kt`):
   ```kotlin
   @MicronautTest
   class AssetCascadeDeleteContractTest {
       @Test
       fun `DELETE asset should cascade delete vulnerabilities and exceptions`() {
           // Given: Asset with 5 vulnerabilities, 2 ASSET exceptions
           // When: DELETE /api/assets/{id}
           // Then: 200 OK, all related records deleted, audit log created
       }

       @Test
       fun `DELETE locked asset should return 409 Conflict`() {
           // Given: Asset locked by another transaction
           // When: DELETE /api/assets/{id}
           // Then: 409 Conflict with LOCKED error type
       }
   }
   ```

2. **Implement Service** (`src/backendng/src/main/kotlin/com/secman/service/AssetCascadeDeleteService.kt`):
   ```kotlin
   @Singleton
   @Transactional
   class AssetCascadeDeleteService(
       private val entityManager: EntityManager,
       private val assetRepo: AssetRepository,
       private val vulnRepo: VulnerabilityRepository,
       private val exceptionRepo: VulnerabilityExceptionRepository,
       private val requestRepo: VulnerabilityExceptionRequestRepository,
       private val auditService: AssetDeletionAuditService
   ) {
       fun deleteAsset(assetId: Long, username: String): CascadeDeletionResultDto {
           // 1. Acquire pessimistic lock
           val asset = entityManager.find(Asset::class.java, assetId, LockModeType.PESSIMISTIC_WRITE)
               ?: throw AssetNotFoundException(assetId)

           // 2. Collect IDs before deletion
           val vulnIds = vulnRepo.findIdsByAssetId(assetId)
           val exceptionIds = exceptionRepo.findIdsByAssetIdAndType(assetId, ExceptionType.ASSET)
           val requestIds = requestRepo.findIdsByVulnerabilityAssetId(assetId)

           // 3. Delete in dependency order
           requestRepo.deleteByIdIn(requestIds)
           exceptionRepo.deleteByIdIn(exceptionIds)
           vulnRepo.deleteByIdIn(vulnIds)
           assetRepo.delete(asset)

           // 4. Create audit log (async)
           val auditLogId = auditService.logCascadeDeletion(
               assetId, asset.name, username, vulnIds, exceptionIds, requestIds
           )

           return CascadeDeletionResultDto(/* ... */)
       }
   }
   ```

3. **Run Tests**: `./gradlew test --tests AssetCascadeDeleteContractTest`
   - Should FAIL initially (service not complete)
   - Implement service until tests pass

**Verification**: All contract tests green

---

### Phase 3: Backend - Pre-Flight Validation

**Goal**: Implement count queries and timeout estimation

**Steps**:

1. **Add Repository Methods**:
   ```kotlin
   // VulnerabilityRepository
   fun countByAssetId(assetId: Long): Int

   // VulnerabilityExceptionRepository
   fun countByAssetIdAndExceptionType(assetId: Long, type: ExceptionType): Int

   // VulnerabilityExceptionRequestRepository
   @Query("SELECT COUNT(r) FROM VulnerabilityExceptionRequest r WHERE r.vulnerability.asset.id = :assetId")
   fun countByVulnerabilityAssetId(@Param("assetId") assetId: Long): Int
   ```

2. **Implement Pre-Flight Service**:
   ```kotlin
   fun estimateCascadeDeletion(assetId: Long): CascadeDeleteSummaryDto {
       val asset = assetRepo.findById(assetId).orElseThrow()
       val vulnCount = vulnRepo.countByAssetId(assetId)
       val exceptionCount = exceptionRepo.countByAssetIdAndExceptionType(assetId, ExceptionType.ASSET)
       val requestCount = requestRepo.countByVulnerabilityAssetId(assetId)

       val totalRecords = vulnCount + exceptionCount + requestCount
       val estimatedSeconds = (totalRecords / 100) + 1 // Benchmark: 100 records/sec

       return CascadeDeleteSummaryDto(
           assetId = assetId,
           assetName = asset.name,
           vulnerabilitiesCount = vulnCount,
           assetExceptionsCount = exceptionCount,
           exceptionRequestsCount = requestCount,
           estimatedDurationSeconds = estimatedSeconds,
           exceedsTimeout = estimatedSeconds > 60
       )
   }
   ```

3. **Test**:
   ```kotlin
   @Test
   fun `estimate should warn when deletion exceeds 60 seconds`() {
       // Given: Asset with 7000 records (70 seconds estimated)
       // When: GET /api/assets/{id}/cascade-summary
       // Then: exceedsTimeout = true
   }
   ```

**Verification**: `./gradlew test --tests AssetPreFlightValidationTest`

---

### Phase 4: Backend - Bulk Delete with SSE

**Goal**: Implement bulk deletion with real-time progress streaming

**Steps**:

1. **Create DTOs**:
   ```kotlin
   data class BulkDeleteProgressDto(
       val total: Int,
       val completed: Int,
       val currentAssetId: Long,
       val currentAssetName: String,
       val status: String, // "PROCESSING", "SUCCESS", "FAILED"
       val error: String? = null
   )
   ```

2. **Implement SSE Endpoint**:
   ```kotlin
   @Delete("/bulk/stream")
   @Produces(MediaType.TEXT_EVENT_STREAM)
   @Secured("ADMIN")
   fun bulkDeleteWithProgress(@QueryValue assetIds: List<Long>): Flux<ServerSentEvent<BulkDeleteProgressDto>> {
       return Flux.create { emitter ->
           var completed = 0
           val bulkOpId = UUID.randomUUID().toString()

           assetIds.forEach { assetId ->
               try {
                   val asset = assetRepo.findById(assetId).orElseThrow()
                   cascadeDeleteService.deleteAsset(assetId, getUsername(), bulkOpId)
                   completed++

                   emitter.next(ServerSentEvent.of(BulkDeleteProgressDto(
                       total = assetIds.size,
                       completed = completed,
                       currentAssetId = assetId,
                       currentAssetName = asset.name,
                       status = "SUCCESS"
                   )))
               } catch (e: Exception) {
                   emitter.next(ServerSentEvent.of(BulkDeleteProgressDto(
                       total = assetIds.size,
                       completed = completed,
                       currentAssetId = assetId,
                       currentAssetName = "",
                       status = "FAILED",
                       error = e.message
                   )))
                   emitter.error(e)
                   return@forEach
               }
           }
           emitter.complete()
       }
   }
   ```

3. **Test**:
   ```kotlin
   @Test
   fun `bulk delete should stream progress events`() {
       // Given: 10 assets to delete
       // When: DELETE /bulk/stream
       // Then: Receive 10 SSE events, each with completed count
   }
   ```

**Verification**: `./gradlew test --tests BulkDeleteProgressTest`

---

### Phase 5: Frontend - Confirmation Modal

**Goal**: Add cascade count warnings to delete confirmation

**Steps**:

1. **Update Service** (`src/frontend/src/services/assetService.ts`):
   ```typescript
   async getCascadeSummary(assetId: number): Promise<CascadeDeleteSummaryDto> {
       const response = await axios.get(`/api/assets/${assetId}/cascade-summary`);
       return response.data;
   }

   async deleteAsset(assetId: number, forceTimeout: boolean = false): Promise<CascadeDeletionResultDto> {
       const response = await axios.delete(`/api/assets/${assetId}`, {
           params: { forceTimeout }
       });
       return response.data;
   }
   ```

2. **Enhance Modal** (`src/frontend/src/components/AssetDeleteConfirmModal.tsx`):
   ```tsx
   function AssetDeleteConfirmModal({ assetId, onConfirm, onCancel }) {
       const [summary, setSummary] = useState(null);
       const [loading, setLoading] = useState(true);

       useEffect(() => {
           assetService.getCascadeSummary(assetId).then(setSummary).finally(() => setLoading(false));
       }, [assetId]);

       if (loading) return <Spinner />;

       return (
           <Modal>
               <h3>Confirm Deletion</h3>
               <p>The following related data will be deleted:</p>
               <ul>
                   <li>{summary.vulnerabilitiesCount} vulnerabilities</li>
                   <li>{summary.assetExceptionsCount} ASSET-type exceptions</li>
                   <li>{summary.exceptionRequestsCount} exception requests</li>
               </ul>
               {summary.exceedsTimeout && (
                   <Alert variant="warning">
                       ⚠️ Estimated deletion time ({summary.estimatedDurationSeconds}s) exceeds timeout (60s).
                       This operation may fail. Contact administrator if needed.
                   </Alert>
               )}
               <Button onClick={() => onConfirm(summary.exceedsTimeout)}>Delete</Button>
               <Button variant="secondary" onClick={onCancel}>Cancel</Button>
           </Modal>
       );
   }
   ```

3. **Test** (`src/frontend/tests/e2e/asset-cascade-delete.spec.ts`):
   ```typescript
   test('delete confirmation shows cascade counts', async ({ page }) => {
       // Given: Asset with 15 vulns, 3 exceptions
       // When: Click delete button
       // Then: Modal shows "15 vulnerabilities", "3 ASSET-type exceptions"
   });
   ```

**Verification**: `npm test tests/e2e/asset-cascade-delete.spec.ts`

---

### Phase 6: Frontend - Bulk Progress UI

**Goal**: Display real-time progress for bulk operations

**Steps**:

1. **Create SSE Service** (`src/frontend/src/services/bulkDeleteProgressService.ts`):
   ```typescript
   function streamBulkDelete(
       assetIds: number[],
       onProgress: (progress: BulkDeleteProgressDto) => void,
       onComplete: () => void,
       onError: (error: string) => void
   ): EventSource {
       const eventSource = new EventSource(`/api/assets/bulk/stream?assetIds=${assetIds.join(',')}`);

       eventSource.onmessage = (event) => {
           const progress: BulkDeleteProgressDto = JSON.parse(event.data);
           onProgress(progress);

           if (progress.status === 'FAILED') {
               eventSource.close();
               onError(progress.error);
           } else if (progress.completed === progress.total) {
               eventSource.close();
               onComplete();
           }
       };

       eventSource.onerror = () => {
           eventSource.close();
           onError('Connection error');
       };

       return eventSource;
   }
   ```

2. **Create Modal** (`src/frontend/src/components/BulkDeleteProgressModal.tsx`):
   ```tsx
   function BulkDeleteProgressModal({ assetIds, onClose }) {
       const [progress, setProgress] = useState({ total: assetIds.length, completed: 0 });
       const [error, setError] = useState(null);

       useEffect(() => {
           const eventSource = streamBulkDelete(
               assetIds,
               setProgress,
               () => { /* Success */ },
               setError
           );
           return () => eventSource.close();
       }, [assetIds]);

       const percentage = (progress.completed / progress.total) * 100;

       return (
           <Modal>
               <h3>Deleting {progress.total} Assets</h3>
               <ProgressBar now={percentage} label={`${progress.completed}/${progress.total}`} />
               {progress.currentAssetName && <p>Deleting: {progress.currentAssetName}</p>}
               {error && <Alert variant="danger">{error}</Alert>}
           </Modal>
       );
   }
   ```

3. **Test**: Playwright E2E test verifying progress bar updates

**Verification**: `npm test tests/e2e/bulk-delete-progress.spec.ts`

---

## Integration Checklist

Before marking this feature complete, verify:

- [ ] All contract tests pass (`./gradlew test`)
- [ ] All E2E tests pass (`npm test`)
- [ ] Pessimistic locking prevents concurrent deletions
- [ ] Pre-flight validation warns for >60s operations
- [ ] Audit log created for every deletion
- [ ] Bulk operations show real-time progress
- [ ] Error messages are detailed and actionable
- [ ] ADMIN role required for all deletion endpoints
- [ ] IP/PRODUCT exceptions preserved (not deleted)
- [ ] Transaction rollback works correctly on failures

## Common Issues & Solutions

### Issue: PessimisticLockException during tests

**Solution**: Ensure tests use separate transactions or clear EntityManager between tests

```kotlin
@AfterEach
fun cleanup() {
    entityManager.clear()
}
```

---

### Issue: SSE connection times out

**Solution**: Increase SSE timeout in Micronaut config

```yaml
micronaut:
  server:
    netty:
      idle-timeout: 300s # 5 minutes
```

---

### Issue: JSON type not mapping in Hibernate

**Solution**: Add Hibernate JSON type dependency

```kotlin
// build.gradle.kts
implementation("com.vladmihalcea:hibernate-types-60:2.21.1")

// Entity annotation
@Type(JsonType::class)
```

---

## Performance Benchmarks

Target metrics (verify during testing):

- Single asset deletion: <2s (for assets with <100 related records)
- Bulk 100 assets: <30s (average 300ms per asset)
- Pre-flight count query: <100ms
- SSE event latency: <50ms per event

## Next Steps

After completing implementation:

1. Run full test suite: `./gradlew test && npm test`
2. Manual QA: Delete assets via UI, verify cascade behavior
3. Review audit logs in database
4. Performance test with large datasets (1000+ vulnerabilities)
5. Create PR with conventional commit message: `feat(assets): add cascade deletion with audit logging`

## References

- [Feature Specification](./spec.md)
- [Research Document](./research.md)
- [Data Model](./data-model.md)
- [API Contracts](./contracts/cascade-delete-api.yaml)
- [Micronaut SSE Documentation](https://docs.micronaut.io/latest/guide/#sse)
- [Hibernate Locking](https://docs.jboss.org/hibernate/orm/6.0/userguide/html_single/Hibernate_User_Guide.html#locking)
