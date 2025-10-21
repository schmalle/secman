# Quickstart: Vulnerability Exception Request & Approval Workflow

**Feature**: 031-vuln-exception-approval
**Date**: 2025-10-20

This quickstart guide provides developers with essential implementation patterns and usage examples for the vulnerability exception request approval workflow.

---

## Table of Contents

1. [Overview](#overview)
2. [Backend Implementation](#backend-implementation)
3. [Frontend Implementation](#frontend-implementation)
4. [Common Usage Patterns](#common-usage-patterns)
5. [Testing Strategies](#testing-strategies)
6. [Troubleshooting](#troubleshooting)

---

## Overview

### Key Capabilities

- ✅ **Request Exceptions**: Users can request exceptions for overdue vulnerabilities
- ✅ **Auto-Approval**: ADMIN/SECCHAMPION requests are automatically approved
- ✅ **Approval Workflow**: Regular user requests require ADMIN/SECCHAMPION approval
- ✅ **Real-Time Updates**: Badge count updates via SSE (Server-Sent Events)
- ✅ **Concurrency Control**: First-approver-wins prevents duplicate approvals
- ✅ **Audit Trail**: Complete lifecycle logging for compliance

### Architecture Components

```
Frontend (React + Astro)
    ↓ HTTP/REST
Backend (Micronaut + Kotlin)
    ↓ JPA
Database (MariaDB)
    ↓ SSE
Frontend (Real-time Badge Updates)
```

---

## Backend Implementation

### 1. Entity Definition (JPA)

```kotlin
// src/backendng/src/main/kotlin/com/secman/domain/VulnerabilityExceptionRequest.kt
@Entity
@Table(name = "vulnerability_exception_request")
@Serdeable
data class VulnerabilityExceptionRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vulnerability_id")
    var vulnerability: Vulnerability? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id")
    var requestedBy: User? = null,

    @Column(name = "requested_by_username", nullable = false)
    var requestedByUsername: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    var scope: ExceptionScope,

    @Column(name = "reason", nullable = false, length = 2048)
    @Size(min = 50, max = 2048)
    var reason: String,

    @Column(name = "expiration_date", nullable = false)
    var expirationDate: LocalDateTime,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: RequestStatus = RequestStatus.PENDING,

    @Column(name = "auto_approved", nullable = false)
    var autoApproved: Boolean = false,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    var reviewedBy: User? = null,

    @Column(name = "reviewed_by_username")
    var reviewedByUsername: String? = null,

    @Column(name = "review_date")
    var reviewDate: LocalDateTime? = null,

    @Column(name = "review_comment", length = 1024)
    var reviewComment: String? = null,

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null,

    /**
     * Optimistic locking version - DO NOT modify manually
     */
    @Version
    @Column(name = "version")
    var version: Long? = null
) {
    @PrePersist
    fun onCreate() {
        val now = LocalDateTime.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }
}

enum class ExceptionScope {
    SINGLE_VULNERABILITY,  // This vuln on this asset only
    CVE_PATTERN           // All vulns with this CVE
}

enum class RequestStatus {
    PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED
}
```

### 2. Service Layer with Optimistic Locking

```kotlin
// src/backendng/src/main/kotlin/com/secman/service/VulnerabilityExceptionRequestService.kt
@Singleton
open class VulnerabilityExceptionRequestService(
    private val requestRepository: VulnerabilityExceptionRequestRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val exceptionRepository: VulnerabilityExceptionRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Create exception request with role-based auto-approval
     */
    @Transactional
    open fun createRequest(
        vulnerabilityId: Long,
        scope: ExceptionScope,
        reason: String,
        expirationDate: LocalDateTime,
        authentication: Authentication
    ): VulnerabilityExceptionRequest {
        val username = authentication.name
        val isPrivileged = authentication.roles.contains("ADMIN") ||
                          authentication.roles.contains("SECCHAMPION")

        val vulnerability = vulnerabilityRepository.findById(vulnerabilityId)
            .orElseThrow { IllegalArgumentException("Vulnerability not found") }

        // Check for duplicate active exception
        checkDuplicateException(vulnerabilityId)

        val request = VulnerabilityExceptionRequest(
            vulnerability = vulnerability,
            requestedByUsername = username,
            scope = scope,
            reason = reason,
            expirationDate = expirationDate,
            status = if (isPrivileged) RequestStatus.APPROVED else RequestStatus.PENDING,
            autoApproved = isPrivileged
        )

        if (isPrivileged) {
            request.reviewedByUsername = username
            request.reviewDate = LocalDateTime.now()
        }

        val savedRequest = requestRepository.save(request)

        // Publish event for audit logging
        eventPublisher.publishEvent(
            ExceptionRequestCreatedEvent(
                requestId = savedRequest.id!!,
                status = savedRequest.status,
                actorUsername = username
            )
        )

        // If auto-approved, create actual exception
        if (isPrivileged) {
            createVulnerabilityException(savedRequest)
        }

        return savedRequest
    }

    /**
     * Approve request with first-approver-wins concurrency control
     */
    @Transactional
    open fun approveRequest(
        requestId: Long,
        reviewerUsername: String,
        reviewComment: String?
    ): VulnerabilityExceptionRequest {
        val request = requestRepository.findById(requestId)
            .orElseThrow { IllegalArgumentException("Request not found") }

        if (request.status != RequestStatus.PENDING) {
            throw IllegalStateException("Request is not pending: ${request.status}")
        }

        try {
            // Update status - version check happens here
            request.status = RequestStatus.APPROVED
            request.reviewedByUsername = reviewerUsername
            request.reviewDate = LocalDateTime.now()
            request.reviewComment = reviewComment

            val savedRequest = requestRepository.update(request)

            // Create actual exception
            createVulnerabilityException(savedRequest)

            // Publish audit event
            eventPublisher.publishEvent(
                ExceptionRequestApprovedEvent(
                    requestId = requestId,
                    reviewerUsername = reviewerUsername
                )
            )

            logger.info("Request $requestId approved by $reviewerUsername")
            return savedRequest

        } catch (e: OptimisticLockException) {
            // Another reviewer got here first
            logger.warn("Concurrent approval detected for request $requestId")

            val currentState = requestRepository.findById(requestId).orElseThrow()
            throw ConcurrentApprovalException(
                reviewedBy = currentState.reviewedByUsername ?: "Unknown",
                reviewedAt = currentState.reviewDate ?: LocalDateTime.now()
            )
        }
    }

    private fun createVulnerabilityException(request: VulnerabilityExceptionRequest) {
        val exception = VulnerabilityException(
            exceptionType = when (request.scope) {
                ExceptionScope.SINGLE_VULNERABILITY -> VulnerabilityException.ExceptionType.ASSET
                ExceptionScope.CVE_PATTERN -> VulnerabilityException.ExceptionType.PRODUCT
            },
            targetValue = when (request.scope) {
                ExceptionScope.SINGLE_VULNERABILITY -> request.vulnerability?.asset?.id.toString()
                ExceptionScope.CVE_PATTERN -> request.vulnerability?.cveId ?: ""
            },
            reason = request.reason,
            expirationDate = request.expirationDate,
            createdBy = request.reviewedByUsername ?: request.requestedByUsername
        )
        exceptionRepository.save(exception)
    }

    class ConcurrentApprovalException(
        val reviewedBy: String,
        val reviewedAt: LocalDateTime
    ) : RuntimeException("Already reviewed by $reviewedBy at $reviewedAt")
}
```

### 3. REST Controller

```kotlin
// src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityExceptionRequestController.kt
@Controller("/api/vulnerability-exception-requests")
@Secured(SecurityRule.IS_AUTHENTICATED)
class VulnerabilityExceptionRequestController(
    private val requestService: VulnerabilityExceptionRequestService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Post
    fun createRequest(
        @Body dto: CreateExceptionRequestDto,
        authentication: Authentication
    ): HttpResponse<ExceptionRequestDto> {
        return try {
            val request = requestService.createRequest(
                vulnerabilityId = dto.vulnerabilityId,
                scope = dto.scope,
                reason = dto.reason,
                expirationDate = dto.expirationDate,
                authentication = authentication
            )
            HttpResponse.created(toDto(request))
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid request"))
        }
    }

    @Post("/{id}/approve")
    @Secured("ADMIN", "SECCHAMPION")
    fun approveRequest(
        @PathVariable id: Long,
        @Body(required = false) dto: ReviewExceptionRequestDto?,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            val request = requestService.approveRequest(
                requestId = id,
                reviewerUsername = authentication.name,
                reviewComment = dto?.reviewComment
            )
            HttpResponse.ok(toDto(request))

        } catch (e: ConcurrentApprovalException) {
            HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT)
                .body(ErrorResponse(
                    message = "This request was already reviewed",
                    details = "Reviewed by ${e.reviewedBy} at ${e.reviewedAt}"
                ))

        } catch (e: IllegalStateException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid state"))
        }
    }

    @Get("/pending")
    @Secured("ADMIN", "SECCHAMPION")
    fun getPendingRequests(): List<ExceptionRequestDto> {
        return requestService.getPendingRequests()
            .map { toDto(it) }
    }
}
```

### 4. SSE Endpoint for Real-Time Badge Updates

```kotlin
// src/backendng/src/main/kotlin/com/secman/controller/NotificationController.kt
@Controller("/api/notifications")
@Secured(SecurityRule.IS_AUTHENTICATED)
class NotificationController(
    private val requestService: VulnerabilityExceptionRequestService
) {
    @Get("/badge-count")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    fun streamBadgeCount(): Flux<Event<BadgeCountDto>> {
        return Flux.interval(Duration.ofSeconds(5))
            .map {
                val count = requestService.getPendingRequestCount()
                Event.of(BadgeCountDto(count))
            }
    }

    @Get("/badge-count-sync")
    fun getBadgeCountSync(): BadgeCountDto {
        return BadgeCountDto(requestService.getPendingRequestCount())
    }
}

data class BadgeCountDto(val count: Int)
```

---

## Frontend Implementation

### 1. API Service

```typescript
// src/frontend/src/services/exceptionRequestService.ts
import axios from 'axios';

export interface ExceptionRequest {
  id: number;
  vulnerabilityId: number;
  vulnerabilityCve: string;
  assetName: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';
  reason: string;
  expirationDate: string;
  requestedByUsername: string;
  reviewedByUsername?: string;
  reviewDate?: string;
  reviewComment?: string;
  autoApproved: boolean;
  createdAt: string;
}

export interface CreateExceptionRequest {
  vulnerabilityId: number;
  scope: 'SINGLE_VULNERABILITY' | 'CVE_PATTERN';
  reason: string;
  expirationDate: string;
}

export const exceptionRequestService = {
  createRequest: async (data: CreateExceptionRequest): Promise<ExceptionRequest> => {
    const response = await axios.post('/api/vulnerability-exception-requests', data);
    return response.data;
  },

  getMyRequests: async (page = 0, size = 20): Promise<{ content: ExceptionRequest[], totalElements: number }> => {
    const response = await axios.get('/api/vulnerability-exception-requests/my-requests', {
      params: { page, size }
    });
    return response.data;
  },

  getPendingRequests: async (): Promise<ExceptionRequest[]> => {
    const response = await axios.get('/api/vulnerability-exception-requests/pending');
    return response.data;
  },

  approveRequest: async (requestId: number, reviewComment?: string): Promise<ExceptionRequest> => {
    const response = await axios.post(`/api/vulnerability-exception-requests/${requestId}/approve`, {
      reviewComment
    });
    return response.data;
  },

  rejectRequest: async (requestId: number, reviewComment: string): Promise<ExceptionRequest> => {
    const response = await axios.post(`/api/vulnerability-exception-requests/${requestId}/reject`, {
      reviewComment
    });
    return response.data;
  },

  cancelRequest: async (requestId: number): Promise<void> => {
    await axios.delete(`/api/vulnerability-exception-requests/${requestId}`);
  }
};
```

### 2. SSE Badge Hook

```typescript
// src/frontend/src/hooks/useExceptionBadgeCount.ts
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
      fallbackIntervalRef.current = window.setInterval(async () => {
        try {
          const response = await fetch('/api/notifications/badge-count-sync');
          const data = await response.json();
          setCount(data.count);
        } catch (err) {
          console.error('Polling failed:', err);
        }
      }, 30000);  // 30-second polling
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

### 3. React Component Example

```tsx
// src/frontend/src/components/ExceptionRequestModal.tsx
import React, { useState } from 'react';
import { exceptionRequestService, CreateExceptionRequest } from '../services/exceptionRequestService';

interface Props {
  show: boolean;
  onHide: () => void;
  vulnerabilityId: number;
  onSuccess: () => void;
}

export function ExceptionRequestModal({ show, onHide, vulnerabilityId, onSuccess }: Props) {
  const [formData, setFormData] = useState<CreateExceptionRequest>({
    vulnerabilityId,
    scope: 'SINGLE_VULNERABILITY',
    reason: '',
    expirationDate: ''
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);

    try {
      await exceptionRequestService.createRequest(formData);
      onSuccess();
      onHide();
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to submit request');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className={`modal ${show ? 'show d-block' : ''}`} tabIndex={-1}>
      <div className="modal-dialog">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">Request Exception</h5>
            <button type="button" className="btn-close" onClick={onHide} />
          </div>

          <form onSubmit={handleSubmit}>
            <div className="modal-body">
              {error && <div className="alert alert-danger">{error}</div>}

              <div className="mb-3">
                <label className="form-label">Exception Scope</label>
                <div>
                  <div className="form-check">
                    <input
                      className="form-check-input"
                      type="radio"
                      value="SINGLE_VULNERABILITY"
                      checked={formData.scope === 'SINGLE_VULNERABILITY'}
                      onChange={(e) => setFormData({ ...formData, scope: e.target.value as any })}
                    />
                    <label className="form-check-label">
                      This vulnerability only
                    </label>
                  </div>
                  <div className="form-check">
                    <input
                      className="form-check-input"
                      type="radio"
                      value="CVE_PATTERN"
                      checked={formData.scope === 'CVE_PATTERN'}
                      onChange={(e) => setFormData({ ...formData, scope: e.target.value as any })}
                    />
                    <label className="form-check-label">
                      All assets with this CVE
                    </label>
                  </div>
                </div>
              </div>

              <div className="mb-3">
                <label htmlFor="reason" className="form-label">
                  Business Justification *
                </label>
                <textarea
                  id="reason"
                  className="form-control"
                  rows={5}
                  minLength={50}
                  maxLength={2048}
                  required
                  value={formData.reason}
                  onChange={(e) => setFormData({ ...formData, reason: e.target.value })}
                  placeholder="Explain why this vulnerability requires an exception..."
                />
                <div className="form-text">
                  {formData.reason.length}/2048 characters (minimum 50)
                </div>
              </div>

              <div className="mb-3">
                <label htmlFor="expirationDate" className="form-label">
                  Expiration Date *
                </label>
                <input
                  type="datetime-local"
                  id="expirationDate"
                  className="form-control"
                  required
                  value={formData.expirationDate}
                  onChange={(e) => setFormData({ ...formData, expirationDate: e.target.value })}
                />
              </div>
            </div>

            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" onClick={onHide}>
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={loading}>
                {loading ? 'Submitting...' : 'Submit Request'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
```

---

## Common Usage Patterns

### Pattern 1: Request Exception Button in Vulnerability Table

```tsx
{vulnerabilities.map(vuln => (
  <tr key={vuln.id}>
    <td>{vuln.cve}</td>
    <td>{vuln.assetName}</td>
    <td>
      {vuln.exceptionStatus === 'APPROVED' ? (
        <span className="badge bg-success">Excepted</span>
      ) : vuln.exceptionStatus === 'PENDING' ? (
        <span className="badge bg-warning">Pending Exception</span>
      ) : (
        <button
          className="btn btn-sm btn-primary"
          onClick={() => handleRequestException(vuln.id)}
          disabled={!vuln.isOverdue}
        >
          Request Exception
        </button>
      )}
    </td>
  </tr>
))}
```

### Pattern 2: Approval Dashboard

```tsx
function ExceptionApprovalDashboard() {
  const [pendingRequests, setPendingRequests] = useState<ExceptionRequest[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadPendingRequests();
  }, []);

  const loadPendingRequests = async () => {
    const requests = await exceptionRequestService.getPendingRequests();
    setPendingRequests(requests);
    setLoading(false);
  };

  const handleApprove = async (requestId: number) => {
    try {
      await exceptionRequestService.approveRequest(requestId);
      loadPendingRequests();  // Refresh list
    } catch (err: any) {
      if (err.response?.status === 409) {
        alert('Another user already reviewed this request');
        loadPendingRequests();
      }
    }
  };

  return (
    <div>
      <h2>Pending Exception Requests ({pendingRequests.length})</h2>
      <table className="table">
        <thead>
          <tr>
            <th>CVE</th>
            <th>Asset</th>
            <th>Requested By</th>
            <th>Days Pending</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {pendingRequests.map(req => (
            <tr key={req.id}>
              <td>{req.vulnerabilityCve}</td>
              <td>{req.assetName}</td>
              <td>{req.requestedByUsername}</td>
              <td>{calculateDaysPending(req.createdAt)}</td>
              <td>
                <button
                  className="btn btn-sm btn-success me-2"
                  onClick={() => handleApprove(req.id)}
                >
                  Approve
                </button>
                <button
                  className="btn btn-sm btn-danger"
                  onClick={() => handleReject(req.id)}
                >
                  Reject
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

---

## Testing Strategies

### Backend Contract Test

```kotlin
@MicronautTest
class ExceptionRequestContractTest {

    @Inject
    lateinit var client: HttpClient

    @Test
    fun `create exception request returns 201`() {
        val dto = CreateExceptionRequestDto(
            vulnerabilityId = 1L,
            scope = ExceptionScope.SINGLE_VULNERABILITY,
            reason = "Legacy system scheduled for migration. Patching not feasible.",
            expirationDate = LocalDateTime.now().plusMonths(6)
        )

        val response = client.toBlocking().exchange(
            HttpRequest.POST("/api/vulnerability-exception-requests", dto)
                .bearerAuth(getJwtToken()),
            ExceptionRequestDto::class.java
        )

        assertEquals(HttpStatus.CREATED, response.status)
        assertEquals("PENDING", response.body()?.status)
    }

    @Test
    fun `concurrent approval returns 409 conflict`() {
        val requestId = createPendingRequest()

        // First approval
        val response1 = client.toBlocking().exchange(
            HttpRequest.POST("/api/vulnerability-exception-requests/$requestId/approve", null)
                .bearerAuth(getAdminToken()),
            ExceptionRequestDto::class.java
        )
        assertEquals(HttpStatus.OK, response1.status)

        // Second approval (concurrent) - should fail
        val response2 = client.toBlocking().exchange(
            HttpRequest.POST("/api/vulnerability-exception-requests/$requestId/approve", null)
                .bearerAuth(getAdminToken()),
            ErrorResponse::class.java
        )
        assertEquals(HttpStatus.CONFLICT, response2.status)
        assertTrue(response2.body()?.message?.contains("already reviewed") == true)
    }
}
```

### Frontend E2E Test (Playwright)

```typescript
// tests/e2e/exception-request-workflow.spec.ts
import { test, expect } from '@playwright/test';

test('regular user can request exception', async ({ page }) => {
  await page.goto('/login');
  await page.fill('input[name="username"]', 'john.doe');
  await page.fill('input[name="password"]', 'password');
  await page.click('button[type="submit"]');

  // Navigate to vulnerability table
  await page.goto('/vulnerabilities/current');

  // Click "Request Exception" on first overdue vulnerability
  await page.click('button:has-text("Request Exception")');

  // Fill form
  await page.fill('textarea[id="reason"]', 'Legacy system cannot be patched until Q2 2026 migration completes.');
  await page.fill('input[id="expirationDate"]', '2026-06-30T23:59');
  await page.click('button:has-text("Submit Request")');

  // Verify success
  await expect(page.locator('.alert-success')).toContainText('Exception request submitted');

  // Verify badge shows "Pending Exception"
  await expect(page.locator('span.badge')).toContainText('Pending Exception');
});

test('ADMIN can approve pending request', async ({ page }) => {
  await loginAsAdmin(page);

  await page.goto('/exception-approvals');

  // Verify pending requests shown
  await expect(page.locator('table tbody tr')).toHaveCount(1);

  // Click approve
  await page.click('button:has-text("Approve")');

  // Verify success
  await expect(page.locator('.alert-success')).toContainText('Request approved');
});
```

---

## Troubleshooting

### Issue: SSE Connection Drops Frequently

**Symptom**: Badge count doesn't update, EventSource connection closes repeatedly

**Solution**:
1. Check nginx/proxy configuration for SSE support
2. Verify JWT token is valid and not expired
3. Check browser console for CORS errors
4. Fallback polling should activate after 10 failed reconnections

### Issue: OptimisticLockException Not Caught

**Symptom**: 500 error instead of 409 Conflict on concurrent approval

**Solution**:
1. Ensure try-catch wraps `repository.update()`
2. Verify `@Version` field present on entity
3. Check transaction isolation level (should be READ_COMMITTED)

### Issue: Audit Logs Not Created

**Symptom**: No entries in `exception_request_audit` table

**Solution**:
1. Verify `@EventListener` method has `@Async` annotation
2. Check event publisher is injected correctly
3. Verify audit service catches and logs exceptions (shouldn't throw)
4. Check database permissions for INSERT on audit table

### Issue: Badge Count Always Zero

**Symptom**: SSE stream works but count always returns 0

**Solution**:
1. Check user authentication in SSE request
2. Verify `getPendingRequestCount()` query includes correct status filter
3. Test with direct API call: `GET /api/notifications/badge-count-sync`

---

## Next Steps

1. **Implement Backend**: Start with Entity → Repository → Service → Controller
2. **Write Contract Tests**: Create failing tests before implementing
3. **Implement Frontend**: Services → Hooks → Components
4. **Write E2E Tests**: Test complete user workflows
5. **Deploy & Monitor**: Check SSE connections, audit logs, performance

For detailed implementation tasks, see `tasks.md` (generated by `/speckit.tasks` command).
