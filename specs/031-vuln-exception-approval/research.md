# Research: Vulnerability Exception Request & Approval Workflow

**Date**: 2025-10-20
**Feature**: 031-vuln-exception-approval

This document consolidates research findings for technical unknowns identified during implementation planning.

## Research Topics

1. Real-Time Badge Updates: SSE vs WebSocket
2. Optimistic Locking for First-Approver-Wins
3. Comprehensive Audit Logging for State Transitions

---

## 1. Real-Time Badge Updates: SSE vs WebSocket

### Decision: Server-Sent Events (SSE)

**Rationale**: SSE is optimal for this use case because it provides unidirectional server-to-client communication with automatic reconnection, minimal overhead, and simpler implementation compared to WebSocket.

### Use Case Alignment

- **Communication Pattern**: Unidirectional (server → client only) ✅
- **Update Frequency**: Low (every 5 seconds max after state changes) ✅
- **Payload Size**: Small (single integer badge count) ✅
- **No Bidirectional Needed**: Client never sends messages back ✅

### Technical Comparison

| Feature | SSE | WebSocket |
|---------|-----|-----------|
| Protocol | HTTP (standard) | Custom upgrade protocol |
| Directionality | Server → Client only | Bidirectional |
| Reconnection | Built-in (automatic) | Manual implementation |
| Browser Support | All modern (EventSource API) | All modern (WebSocket API) |
| Implementation Complexity | 50% less code | More complex |
| Proxy Compatibility | Excellent (standard HTTP) | Requires special config |
| Authentication | Standard HTTP headers | Custom handshake |
| Debugging | Standard HTTP tools | Custom tools needed |
| Performance (5s interval) | ~0.04 KB/s overhead | ~0.0012 KB/s overhead |

### Implementation Pattern

#### Backend: Micronaut SSE Controller

```kotlin
@Controller("/api/notifications")
@Secured(SecurityRule.IS_AUTHENTICATED)
class NotificationController(
    private val exceptionRequestService: ExceptionRequestService
) {
    private val logger = LoggerFactory.getLogger(NotificationController::class.java)

    /**
     * SSE endpoint for real-time badge count updates
     * Streams pending exception request count every 5 seconds
     */
    @Get("/badge-count")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    fun streamBadgeCount(authentication: Authentication): Flux<Event<BadgeCountDto>> {
        val username = authentication.name
        logger.info("SSE connection established for user: $username")

        return Flux.interval(Duration.ofSeconds(5))
            .map {
                val count = exceptionRequestService.getPendingRequestCount()
                Event.of(BadgeCountDto(count))
            }
            .doOnCancel { logger.info("SSE connection closed for user: $username") }
    }
}

data class BadgeCountDto(val count: Int)
```

#### Frontend: React Hook with EventSource

```typescript
// hooks/useExceptionBadgeCount.ts
import { useState, useEffect, useRef } from 'react';

export function useExceptionBadgeCount() {
  const [count, setCount] = useState<number>(0);
  const [connected, setConnected] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const eventSourceRef = useRef<EventSource | null>(null);
  const fallbackIntervalRef = useRef<number | null>(null);

  useEffect(() => {
    let reconnectAttempts = 0;
    const maxReconnectAttempts = 10;

    const connectSSE = () => {
      try {
        const eventSource = new EventSource('/api/notifications/badge-count', {
          withCredentials: true
        });

        eventSource.onopen = () => {
          setConnected(true);
          setError(null);
          reconnectAttempts = 0;
          logger.info('SSE connection established');
        };

        eventSource.onmessage = (event) => {
          const data = JSON.parse(event.data);
          setCount(data.count);
        };

        eventSource.onerror = () => {
          setConnected(false);
          reconnectAttempts++;

          if (reconnectAttempts >= maxReconnectAttempts) {
            eventSource.close();
            setError('Real-time updates unavailable. Using fallback polling.');
            startFallbackPolling();
          }
        };

        eventSourceRef.current = eventSource;

      } catch (err) {
        setError('Failed to establish real-time connection');
        startFallbackPolling();
      }
    };

    const startFallbackPolling = () => {
      // 30-second polling fallback
      fallbackIntervalRef.current = window.setInterval(async () => {
        try {
          const response = await fetch('/api/notifications/badge-count-sync');
          const data = await response.json();
          setCount(data.count);
        } catch (err) {
          console.error('Polling failed:', err);
        }
      }, 30000);
    };

    connectSSE();

    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
      if (fallbackIntervalRef.current) {
        clearInterval(fallbackIntervalRef.current);
      }
    };
  }, []);

  return { count, connected, error };
}
```

#### Usage in Component

```typescript
function NotificationBadge() {
  const { count, connected, error } = useExceptionBadgeCount();

  return (
    <Link href="/exception-approvals">
      Approve Exceptions
      {count > 0 && (
        <span className="badge bg-danger ms-2">{count}</span>
      )}
      {!connected && error && (
        <i className="bi bi-exclamation-triangle text-warning"
           title={error}></i>
      )}
    </Link>
  );
}
```

### Fallback Strategy

- **Primary**: SSE with 5-second interval
- **Fallback Trigger**: After 10 failed SSE reconnection attempts
- **Fallback Method**: 30-second HTTP polling
- **Fallback Endpoint**: `GET /api/notifications/badge-count-sync`

### Performance Considerations

- **Connection Overhead**: ~200 bytes per SSE connection
- **Message Overhead**: ~50 bytes per badge count update
- **Bandwidth at 5s interval**: 0.04 KB/s per user (negligible)
- **Concurrent Connections**: Supports 1000+ simultaneous SSE connections per server
- **Server Load**: Minimal (simple counter query every 5s)

### Alternatives Considered

**WebSocket**: Rejected because:
- Overkill for unidirectional updates
- More complex implementation (2x code)
- Requires custom reconnection logic
- Lower proxy/firewall compatibility

**Long Polling**: Rejected because:
- Higher latency than SSE
- More server resources (held connections)
- No browser-native support

**Short Polling**: Rejected because:
- Higher latency (30s minimum interval)
- More database queries
- No real-time feel

---

## 2. Optimistic Locking for First-Approver-Wins

### Decision: Hibernate @Version Annotation

**Rationale**: Industry-standard pattern for preventing concurrent modifications with automatic version checking, zero database locks, and excellent multi-instance deployment support.

### Implementation Pattern

#### Entity with @Version Field

```kotlin
@Entity
@Table(name = "vulnerability_exception_request")
data class VulnerabilityExceptionRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: RequestStatus,

    // ... other fields ...

    /**
     * Optimistic locking version field
     * - Automatically incremented by Hibernate on each update
     * - Used to detect concurrent modifications
     * - DO NOT modify this field manually
     */
    @Version
    @Column(name = "version")
    var version: Long? = null
)
```

**Key Design Decisions**:
- **Type**: `Long?` (nullable) - Recommended by Hibernate experts
- **Null Handling**: Null indicates new entity never persisted
- **Auto-increment**: Hibernate manages version automatically
- **No Manual Updates**: Never set version field in application code

#### Service Layer with OptimisticLockException Handling

```kotlin
@Singleton
open class VulnerabilityExceptionRequestService(
    private val requestRepository: VulnerabilityExceptionRequestRepository
) {

    /**
     * Custom exception for concurrent approval conflicts
     */
    class ConcurrentApprovalException(
        val reviewedBy: String,
        val reviewedAt: LocalDateTime
    ) : RuntimeException("This request was already reviewed by $reviewedBy at $reviewedAt")

    @Transactional
    open fun approveRequest(
        requestId: Long,
        reviewerUsername: String,
        reviewNotes: String? = null
    ): VulnerabilityExceptionRequest {

        val request = requestRepository.findById(requestId)
            .orElseThrow { IllegalArgumentException("Request not found") }

        // Business validation
        if (request.status != RequestStatus.PENDING) {
            throw IllegalStateException("Request is not pending")
        }

        try {
            // Update status - Hibernate checks version here
            request.status = RequestStatus.APPROVED
            request.reviewedBy = reviewerUsername
            request.reviewedAt = LocalDateTime.now()

            val savedRequest = requestRepository.update(request)
            logger.info("Request $requestId approved by $reviewerUsername")

            return savedRequest

        } catch (e: OptimisticLockException) {
            // Another user already modified this request
            logger.warn("Concurrent approval detected for request $requestId")

            // Reload current state
            val currentState = requestRepository.findById(requestId)
                .orElseThrow { IllegalStateException("Request disappeared") }

            throw ConcurrentApprovalException(
                reviewedBy = currentState.reviewedBy ?: "Unknown",
                reviewedAt = currentState.reviewedAt ?: LocalDateTime.now()
            )
        }
    }
}
```

#### Controller with 409 Conflict Response

```kotlin
@Post("/{id}/approve")
@Secured("ADMIN", "SECCHAMPION")
fun approveRequest(
    @PathVariable id: Long,
    @Body approveDto: ApproveRequestDto,
    authentication: Authentication
): HttpResponse<*> {
    return try {
        val result = requestService.approveRequest(id, authentication.name)
        HttpResponse.ok(result)

    } catch (e: ConcurrentApprovalException) {
        // First-approver-wins conflict - return 409
        HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT)
            .body(ErrorResponse(
                message = "This request was already reviewed",
                details = "Reviewed by ${e.reviewedBy} at ${e.reviewedAt}"
            ))
    }
}
```

### How It Works

1. **Read**: User A and User B both load request (version=1)
2. **Update**: User A saves first → version becomes 2 ✅
3. **Conflict**: User B tries to save → Hibernate sees version mismatch (still 1) → Throws OptimisticLockException ❌
4. **Response**: User B receives 409 Conflict with message "Already reviewed by User A"

### Comparison to Alternatives

| Approach | Optimistic Lock | Pessimistic Lock | AtomicBoolean Semaphore |
|----------|----------------|------------------|------------------------|
| **Locking Type** | No database locks | Database row locks | In-memory flag |
| **Multi-Instance** | ✅ Works | ✅ Works | ❌ Broken |
| **Concurrency** | High (allows reads) | Low (blocks reads) | Low (blocks all) |
| **Contention** | Zero | High (deadlock risk) | Medium |
| **Granularity** | Per-record | Per-record | Global operation |
| **Use Case** | Approval workflows | Financial txns | Bulk operations |
| **Overhead** | 8 bytes (version) | Lock tables | Zero |
| **Recommendation** | ✅ Use for this feature | ❌ Overkill | ❌ Wrong pattern |

### Performance Implications

- **Storage**: 8 bytes per record (BIGINT version column)
- **Update Query**: Minimal overhead
  ```sql
  UPDATE vulnerability_exception_request
  SET status = ?, reviewed_by = ?, version = version + 1
  WHERE id = ? AND version = ?
  ```
- **Read Performance**: No impact
- **Conflict Rate**: Expected <1% in typical usage

### Testing Strategy

```kotlin
@Test
fun `concurrent approval should fail with first-approver-wins`() {
    val request = createPendingRequest()
    val latch = CountDownLatch(2)
    val results = mutableListOf<Result<VulnerabilityExceptionRequest>>()

    // Simulate two concurrent approvals
    thread {
        results.add(runCatching {
            requestService.approveRequest(request.id!!, "user1")
        })
        latch.countDown()
    }

    thread {
        results.add(runCatching {
            requestService.approveRequest(request.id!!, "user2")
        })
        latch.countDown()
    }

    latch.await(5, TimeUnit.SECONDS)

    // One succeeds, one fails with ConcurrentApprovalException
    assertEquals(1, results.count { it.isSuccess })
    assertEquals(1, results.count {
        it.isFailure && it.exceptionOrNull() is ConcurrentApprovalException
    })
}
```

---

## 3. Comprehensive Audit Logging for State Transitions

### Decision: Hybrid Event Bus + Async Audit Service

**Rationale**: Decouples audit logic from business logic, provides asynchronous non-blocking logging, and matches existing AdminNotificationService and McpAuditLog patterns in codebase.

### Architecture Pattern

**Flow**:
1. Service layer publishes domain events for state transitions
2. Event listener subscribes with @EventListener + @Async
3. Audit service logs to dedicated audit table
4. Main transaction commits immediately (non-blocking)

### Implementation Pattern

#### Audit Entity

```kotlin
@Entity
@Table(
    name = "exception_request_audit",
    indexes = [
        Index(name = "idx_audit_request_id", columnList = "request_id"),
        Index(name = "idx_audit_timestamp", columnList = "timestamp"),
        Index(name = "idx_audit_event_type", columnList = "event_type")
    ]
)
data class ExceptionRequestAuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "request_id", nullable = false)
    val requestId: Long,

    @Column(name = "event_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val eventType: AuditEventType,

    @Column(name = "timestamp", nullable = false)
    val timestamp: LocalDateTime = LocalDateTime.now(),

    @Column(name = "old_state")
    @Enumerated(EnumType.STRING)
    val oldState: RequestStatus?,

    @Column(name = "new_state", nullable = false)
    @Enumerated(EnumType.STRING)
    val newState: RequestStatus,

    @Column(name = "actor_username", nullable = false)
    val actorUsername: String,

    @Column(name = "actor_user_id")
    val actorUserId: Long?,

    @Column(name = "context_data", length = 2000)
    val contextData: String? = null,  // JSON: reason, comment, reviewer notes

    @Column(name = "client_ip")
    val clientIp: String? = null
) {
    enum class AuditEventType {
        REQUEST_CREATED,
        STATUS_CHANGED,
        APPROVED,
        REJECTED,
        CANCELLED,
        EXPIRED
    }
}
```

#### Domain Events

```kotlin
data class ExceptionRequestStateChangedEvent(
    val requestId: Long,
    val oldState: RequestStatus?,
    val newState: RequestStatus,
    val actorUsername: String,
    val actorUserId: Long?,
    val comment: String? = null,
    val reviewerNotes: String? = null,
    val clientIp: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now()
)
```

#### Service Layer with Event Publishing

```kotlin
@Singleton
open class VulnerabilityExceptionRequestService(
    private val requestRepository: VulnerabilityExceptionRequestRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    open fun approveRequest(
        requestId: Long,
        reviewerUsername: String,
        reviewNotes: String?
    ): VulnerabilityExceptionRequest {

        val request = requestRepository.findById(requestId).orElseThrow()
        val oldState = request.status

        request.status = RequestStatus.APPROVED
        request.reviewedBy = reviewerUsername

        val savedRequest = requestRepository.update(request)

        // Publish event (non-blocking)
        eventPublisher.publishEvent(
            ExceptionRequestStateChangedEvent(
                requestId = requestId,
                oldState = oldState,
                newState = RequestStatus.APPROVED,
                actorUsername = reviewerUsername,
                actorUserId = null,  // Get from security context
                reviewerNotes = reviewNotes
            )
        )

        return savedRequest
    }
}
```

#### Async Event Listener

```kotlin
@Singleton
open class ExceptionRequestAuditEventListener(
    private val auditService: ExceptionRequestAuditService
) {

    @EventListener
    @Async
    open fun onStateChanged(event: ExceptionRequestStateChangedEvent): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                auditService.logStateTransition(
                    requestId = event.requestId,
                    oldState = event.oldState,
                    newState = event.newState,
                    actorUsername = event.actorUsername,
                    actorUserId = event.actorUserId,
                    reviewerNotes = event.reviewerNotes
                )
            } catch (e: Exception) {
                logger.error("Audit logging failed", e)
                // Swallow exception - don't break business logic
            }
        }
    }
}
```

#### Audit Service

```kotlin
@Singleton
open class ExceptionRequestAuditService(
    private val auditRepository: ExceptionRequestAuditRepository
) {

    @Async
    open fun logStateTransition(
        requestId: Long,
        oldState: RequestStatus?,
        newState: RequestStatus,
        actorUsername: String,
        actorUserId: Long?,
        reviewerNotes: String? = null
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val contextData = buildContextJson(reviewerNotes)

            val auditLog = ExceptionRequestAuditLog(
                requestId = requestId,
                eventType = mapStatusToEventType(newState),
                oldState = oldState,
                newState = newState,
                actorUsername = actorUsername,
                actorUserId = actorUserId,
                contextData = contextData
            )

            auditRepository.save(auditLog)
            logger.info("Audit logged: $requestId, $oldState -> $newState by $actorUsername")
        }
    }
}
```

### Audit Trail Query Patterns

```kotlin
// Get complete history for a request
fun getRequestHistory(requestId: Long): List<AuditEntryDto> {
    return auditRepository.findAuditTrailByRequestId(requestId)
        .map { audit ->
            AuditEntryDto(
                timestamp = audit.timestamp,
                eventType = audit.eventType,
                actor = audit.actorUsername,
                oldState = audit.oldState,
                newState = audit.newState,
                context = parseContextJson(audit.contextData)
            )
        }
}

// Compliance report
fun generateComplianceReport(startDate: LocalDateTime, endDate: LocalDateTime): ComplianceReportDto {
    val allEvents = auditRepository.findByTimestampBetween(startDate, endDate)

    return ComplianceReportDto(
        totalEvents = allEvents.size,
        approvedCount = allEvents.count { it.eventType == APPROVED },
        rejectedCount = allEvents.count { it.eventType == REJECTED },
        topActors = allEvents.groupBy { it.actorUsername }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(10)
    )
}
```

### Events to Audit

All state transitions logged:
- **REQUEST_CREATED**: User submits new exception request
- **STATUS_CHANGED**: Generic state transition
- **APPROVED**: ADMIN/SECCHAMPION approves request
- **REJECTED**: ADMIN/SECCHAMPION rejects request
- **CANCELLED**: User cancels own pending request
- **EXPIRED**: Scheduled job expires request past expiration date

### Performance Characteristics

- **Asynchronous**: Zero impact on user-facing transactions
- **Batch Inserts**: Configure Hibernate batch_size=50 for bulk operations
- **Storage**: ~200 bytes per audit entry
- **Retention**: Permanent (no automatic deletion, manual cleanup only)
- **Query Performance**: Indexed on request_id, timestamp, event_type

### Comparison to Alternatives

| Approach | Event Bus + Async | Entity Listeners | Synchronous Logging |
|----------|------------------|------------------|---------------------|
| **Decoupling** | ✅ High | ❌ Tight coupling | ❌ Tight coupling |
| **Performance** | ✅ Non-blocking | ❌ Blocks transaction | ❌ Blocks transaction |
| **Testability** | ✅ Easy unit tests | ❌ Hard to test | ❌ Hard to test |
| **Flexibility** | ✅ Multiple listeners | ❌ Single callback | ❌ Single callback |
| **Context Capture** | ✅ Rich domain events | ⚠️ Limited context | ⚠️ Limited context |
| **Recommendation** | ✅ Use for this feature | ❌ Too coupled | ❌ Too slow |

---

## Summary of Decisions

| Research Topic | Decision | Rationale |
|----------------|----------|-----------|
| **Real-Time Updates** | Server-Sent Events (SSE) | Unidirectional, automatic reconnection, simpler than WebSocket |
| **Concurrency Control** | Hibernate @Version | Industry standard, zero locks, multi-instance safe |
| **Audit Logging** | Event Bus + Async Service | Decoupled, non-blocking, matches codebase patterns |

All decisions prioritize:
- ✅ Simplicity over complexity
- ✅ Industry best practices
- ✅ Existing codebase patterns
- ✅ Performance and scalability
- ✅ Testability and maintainability
