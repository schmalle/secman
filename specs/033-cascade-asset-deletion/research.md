# Phase 0: Research & Technical Decisions

**Feature**: Cascade Asset Deletion with Related Data
**Date**: 2025-10-24

## Research Questions

This document consolidates research findings for technical unknowns identified during planning. Each decision is documented with rationale and alternatives considered.

---

## R1: Pessimistic Locking Strategy in Hibernate/JPA

**Question**: How to implement database-level pessimistic row locking in Hibernate/JPA for MariaDB to prevent concurrent asset deletions?

**Decision**: Use `@Lock(LockModeType.PESSIMISTIC_WRITE)` with `entityManager.find()` or repository query methods

**Rationale**:
- Hibernate translates `PESSIMISTIC_WRITE` to `SELECT ... FOR UPDATE` in MariaDB
- Database-level lock prevents race conditions at the source (not application-level)
- Lock is released automatically when transaction commits or rolls back
- Second concurrent request waits (configurable timeout) or throws `PessimisticLockException`
- Integrates seamlessly with existing `@Transactional` annotations

**Implementation Pattern**:
```kotlin
@Transactional
fun deleteAssetWithLock(assetId: Long) {
    val asset = entityManager.find(
        Asset::class.java,
        assetId,
        LockModeType.PESSIMISTIC_WRITE
    ) ?: throw AssetNotFoundException(assetId)

    // Perform cascade deletions while lock is held
    cascadeDelete(asset)
}
```

**Alternatives Considered**:
- **Optimistic locking (@Version)**: Would fail second request with 409, but requires retry logic and doesn't prevent wasted work
- **Application-level semaphore (AtomicBoolean)**: Used in AssetBulkDeleteService but doesn't work across multiple instances/replicas
- **Database advisory locks**: MariaDB specific, less portable, requires manual lock management

**Best Practices**:
- Set lock timeout via `javax.persistence.lock.timeout` hint (e.g., 30 seconds)
- Always use within `@Transactional` boundary to ensure lock release
- Handle `PessimisticLockException` gracefully with user-friendly error message
- Minimize lock duration - perform only essential work within locked transaction

**References**:
- Hibernate ORM 6.x documentation: Locking strategies
- MariaDB InnoDB locking: `SELECT ... FOR UPDATE` behavior
- Spring Data JPA: `@Lock` annotation usage

---

## R2: Pre-Flight Count Estimation for Timeout Prevention

**Question**: How to estimate deletion time before executing cascade to warn users about potential 60-second timeout?

**Decision**: Execute fast COUNT queries for each related entity type, estimate time based on empirical per-record deletion benchmarks

**Rationale**:
- COUNT queries are fast (uses indexes) - typically <100ms even for large datasets
- Empirical benchmarking: ~100 records/second for cascaded deletion (vulnerability + exceptions + requests)
- Formula: `estimatedSeconds = (vulnCount + exceptionCount + requestCount) / 100`
- Warn if `estimatedSeconds > 60` (transaction timeout threshold)
- Allows user to make informed decision (proceed or cancel)

**Implementation Pattern**:
```kotlin
data class CascadeDeleteSummary(
    val assetId: Long,
    val assetName: String,
    val vulnerabilitiesCount: Int,
    val assetExceptionsCount: Int,
    val exceptionRequestsCount: Int,
    val estimatedDurationSeconds: Int,
    val exceedsTimeout: Boolean // true if >60s
)

fun estimateCascadeDeletion(assetId: Long): CascadeDeleteSummary {
    val vulnCount = vulnerabilityRepo.countByAssetId(assetId)
    val exceptionCount = exceptionRepo.countByAssetIdAndType(assetId, ExceptionType.ASSET)
    val requestCount = exceptionRequestRepo.countByVulnerabilityAssetId(assetId)

    val totalRecords = vulnCount + exceptionCount + requestCount
    val estimatedSeconds = (totalRecords / 100) + 1 // +1 for asset itself

    return CascadeDeleteSummary(
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

**Alternatives Considered**:
- **Fixed threshold (e.g., refuse >10K records)**: Too inflexible, doesn't account for varying system load
- **Dynamic timeout extension**: Requires infrastructure changes, breaks 60s constraint
- **Async deletion with status polling**: Adds complexity, violates transactional requirement

**Best Practices**:
- Cache benchmark constants (deletion rate per second) as configuration
- Re-benchmark periodically (quarterly) to adjust for database performance changes
- Add 10% safety buffer to estimates (e.g., multiply by 1.1)
- Log actual deletion times to validate estimates and adjust benchmarks

**References**:
- Spring Data JPA: `countBy*` query derivation
- MariaDB performance tuning: COUNT optimization with indexes
- Transaction timeout configuration: `spring.transaction.default-timeout`

---

## R3: Server-Sent Events (SSE) for Real-Time Bulk Progress

**Question**: How to stream real-time progress updates for bulk deletion operations to the frontend?

**Decision**: Use Micronaut Reactor SSE with Flux for server-to-client streaming, EventSource API on frontend

**Rationale**:
- SSE is perfect fit for one-way server→client streaming (no client→server needed)
- Lighter than WebSockets (HTTP/1.1, automatic reconnection)
- Micronaut Reactor provides `Flux<ServerSentEvent>` for streaming
- EventSource API natively supported in all modern browsers
- Allows long-running transactions while providing UI feedback

**Implementation Pattern**:

**Backend (Micronaut Reactor SSE)**:
```kotlin
@Controller("/api/assets")
class AssetController {

    @Delete("/bulk/stream")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    @Secured("ADMIN")
    fun bulkDeleteWithProgress(@Body assetIds: List<Long>): Flux<ServerSentEvent<BulkDeleteProgressDto>> {
        return Flux.create { emitter ->
            var completed = 0
            assetIds.forEach { assetId ->
                try {
                    cascadeDeleteService.deleteAsset(assetId)
                    completed++
                    emitter.next(ServerSentEvent.of(
                        BulkDeleteProgressDto(
                            total = assetIds.size,
                            completed = completed,
                            currentAssetId = assetId,
                            status = "SUCCESS"
                        )
                    ))
                } catch (e: Exception) {
                    emitter.next(ServerSentEvent.of(
                        BulkDeleteProgressDto(
                            total = assetIds.size,
                            completed = completed,
                            currentAssetId = assetId,
                            status = "FAILED",
                            error = e.message
                        )
                    ))
                    emitter.error(e)
                    return@forEach
                }
            }
            emitter.complete()
        }
    }
}
```

**Frontend (EventSource)**:
```typescript
function streamBulkDelete(assetIds: number[]): EventSource {
    const eventSource = new EventSource(`/api/assets/bulk/stream?ids=${assetIds.join(',')}`);

    eventSource.onmessage = (event) => {
        const progress: BulkDeleteProgressDto = JSON.parse(event.data);
        updateProgressUI(progress);
    };

    eventSource.onerror = () => {
        console.error('SSE connection error');
        eventSource.close();
    };

    return eventSource;
}
```

**Alternatives Considered**:
- **WebSockets**: Overkill for one-way streaming, requires more complex connection management
- **Polling**: High latency, wastes bandwidth, scales poorly
- **Long polling**: Better than polling but still inferior to SSE for this use case

**Best Practices**:
- Send progress events after each asset deletion (not batched) for real-time feel
- Include both success and failure events in stream
- Close SSE connection on frontend when operation completes or errors
- Set reasonable SSE timeout (e.g., 5 minutes for 100 assets)
- Handle reconnection on network interruption (EventSource auto-reconnects)

**References**:
- Micronaut Reactor documentation: SSE support with Flux
- MDN Web Docs: EventSource API
- HTTP/1.1 specification: Server-Sent Events

---

## R4: Audit Log Data Structure for Cascade Deletions

**Question**: What data structure should be used to store cascade deletion audit logs with counts and entity IDs?

**Decision**: Create dedicated `AssetDeletionAuditLog` entity with embedded DTO for structured JSON storage

**Rationale**:
- Separates cascade deletion audits from general application logs
- Uses JSON column type for flexible storage of deleted entity IDs (array of longs)
- Allows efficient querying by asset ID, user, timestamp
- Maintains referential integrity even after asset deletion
- Supports compliance requirements with immutable audit trail

**Data Model**:
```kotlin
@Entity
@Table(name = "asset_deletion_audit_log")
data class AssetDeletionAuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "asset_id", nullable = false)
    val assetId: Long, // Preserved even after asset deleted

    @Column(name = "asset_name", nullable = false, length = 255)
    val assetName: String,

    @Column(name = "deleted_by_user", nullable = false, length = 255)
    val deletedByUser: String, // Username preserved

    @Column(name = "deletion_timestamp", nullable = false)
    val deletionTimestamp: LocalDateTime,

    @Column(name = "vulnerabilities_count", nullable = false)
    val vulnerabilitiesCount: Int,

    @Column(name = "asset_exceptions_count", nullable = false)
    val assetExceptionsCount: Int,

    @Column(name = "exception_requests_count", nullable = false)
    val exceptionRequestsCount: Int,

    @Column(name = "deleted_vulnerability_ids", columnDefinition = "JSON")
    @Type(JsonType::class) // Hibernate JSON type
    val deletedVulnerabilityIds: List<Long>,

    @Column(name = "deleted_exception_ids", columnDefinition = "JSON")
    @Type(JsonType::class)
    val deletedExceptionIds: List<Long>,

    @Column(name = "deleted_request_ids", columnDefinition = "JSON")
    @Type(JsonType::class)
    val deletedRequestIds: List<Long>,

    @Column(name = "operation_type", length = 20)
    @Enumerated(EnumType.STRING)
    val operationType: OperationType, // SINGLE or BULK

    @Column(name = "bulk_operation_id", nullable = true)
    val bulkOperationId: String? = null // UUID for correlating bulk deletions
)

enum class OperationType {
    SINGLE,
    BULK
}
```

**Alternatives Considered**:
- **Plain text logs**: Not queryable, hard to parse, no structure
- **Single JSON column for everything**: Harder to query by specific fields
- **Separate tables for each entity type**: Over-normalized, complex joins

**Best Practices**:
- Use indexed columns for common queries (asset_id, deleted_by_user, deletion_timestamp)
- Store both counts (fast summary) and IDs (detailed investigation)
- Generate UUID for bulk operations to correlate all deletions in single batch
- Make audit log inserts async (@Async) to not slow down deletion operation
- Never delete audit log entries (immutable trail)

**References**:
- Hibernate JSON type mapping: `@Type(JsonType::class)`
- MariaDB JSON data type support
- Audit trail best practices: immutability, correlation IDs

---

## R5: Manual Cascade Deletion vs Database FK CASCADE

**Question**: Should cascade deletion be implemented via database foreign key ON DELETE CASCADE or manual service-layer deletion?

**Decision**: Use manual service-layer cascade deletion (NOT database FK CASCADE)

**Rationale**:
- **Explicit control**: Service layer can enforce business rules (preserve IP/PRODUCT exceptions)
- **Audit logging**: Can log exactly which records deleted and insert audit trail
- **Transaction boundaries**: Manual deletion works within single @Transactional method
- **Dependency ordering**: Can control deletion order (requests → exceptions → vulnerabilities → asset)
- **Error handling**: Can provide detailed error messages for each cascade step
- **Pre-flight validation**: Can count records before deletion (not possible with FK CASCADE)

**Implementation Pattern**:
```kotlin
@Transactional
fun cascadeDeleteAsset(assetId: Long, userId: String): CascadeDeletionResult {
    // 1. Lock asset
    val asset = assetRepo.findById(assetId, LockModeType.PESSIMISTIC_WRITE)
        ?: throw AssetNotFoundException(assetId)

    // 2. Collect IDs before deletion (for audit log)
    val vulnIds = vulnerabilityRepo.findIdsByAssetId(assetId)
    val exceptionIds = exceptionRepo.findIdsByAssetIdAndType(assetId, ExceptionType.ASSET)
    val requestIds = exceptionRequestRepo.findIdsByVulnerabilityAssetId(assetId)

    // 3. Delete in dependency order
    exceptionRequestRepo.deleteByIdIn(requestIds) // 1. Requests first
    exceptionRepo.deleteByIdIn(exceptionIds)      // 2. Then exceptions
    vulnerabilityRepo.deleteByIdIn(vulnIds)       // 3. Then vulnerabilities
    assetRepo.delete(asset)                        // 4. Finally asset

    // 4. Create audit log (async)
    auditService.logCascadeDeletion(assetId, asset.name, userId, vulnIds, exceptionIds, requestIds)

    return CascadeDeletionResult(
        assetId = assetId,
        deletedVulnerabilities = vulnIds.size,
        deletedExceptions = exceptionIds.size,
        deletedRequests = requestIds.size
    )
}
```

**Why NOT FK CASCADE**:
- Can't distinguish between ASSET-type and IP/PRODUCT-type exceptions (would delete all)
- No audit trail of what was deleted
- Can't provide detailed error messages
- Can't implement pre-flight validation
- Can't control transaction boundaries precisely
- Harder to test (database-dependent behavior)

**Tradeoffs**:
- **Manual approach**: More code, more test coverage needed, but full control
- **FK CASCADE**: Less code, database-enforced, but loss of control and auditability

**Best Practices**:
- Always delete in reverse dependency order (children before parents)
- Use `deleteByIdIn(List<Long>)` for batch deletion (single query per entity type)
- Keep transaction scope tight (only deletion operations, no external calls)
- Test rollback scenarios thoroughly (constraint violations, timeouts)

**References**:
- Hibernate batch deletion: `deleteByIdIn()` query derivation
- JPA transaction management: `@Transactional` best practices
- Database cascade deletion limitations

---

## Summary of Technical Decisions

| Decision Area | Choice | Key Rationale |
|---------------|--------|---------------|
| Concurrency Control | Pessimistic locking (SELECT FOR UPDATE) | Database-level guarantee, auto lock release |
| Timeout Prevention | Pre-flight COUNT + estimation | Fast, user-friendly, informed decisions |
| Progress Streaming | SSE with Micronaut Flux | Lightweight, real-time, browser-native |
| Audit Logging | Dedicated entity with JSON columns | Queryable, structured, immutable trail |
| Cascade Strategy | Manual service-layer deletion | Full control, auditable, business rule enforcement |

All decisions align with project constitution (Security-First, TDD, API-First) and leverage existing tech stack (Micronaut, Hibernate, React, MariaDB).
