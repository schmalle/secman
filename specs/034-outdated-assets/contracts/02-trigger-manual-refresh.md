# API Contract: Trigger Manual Refresh

**Feature**: 034-outdated-assets
**Endpoint**: `POST /api/outdated-assets/refresh`
**User Story**: User Story 3 - Manual Refresh of Outdated Assets (P2)

## Request

### HTTP Method
`POST`

### URL
`/api/outdated-assets/refresh`

### Authentication
**Required**: Yes
- JWT token in Authorization header
- Roles: `ADMIN` or `VULN`

### Request Body
None (empty body)

### Example Request

```http
POST /api/outdated-assets/refresh HTTP/1.1
Host: example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Length: 0
```

---

## Response

### Success Response (202 Accepted)

**Structure**: Job accepted for background processing

```json
{
  "jobId": 42,
  "status": "RUNNING",
  "message": "Refresh started in background",
  "startedAt": "2025-10-26T14:35:00",
  "estimatedDurationSeconds": 30
}
```

**Response Fields**:
| Field | Type | Description |
|-------|------|-------------|
| `jobId` | `Long` | Unique identifier for the refresh job |
| `status` | `String` | Job status: RUNNING, COMPLETED, FAILED |
| `message` | `String` | Human-readable message |
| `startedAt` | `ISO 8601 DateTime` | When the job started |
| `estimatedDurationSeconds` | `Integer` | Estimated completion time (seconds) |

---

## Error Responses

### 401 Unauthorized

**Cause**: Missing or invalid JWT token

```json
{
  "message": "Unauthorized"
}
```

### 403 Forbidden

**Cause**: User does not have ADMIN or VULN role

```json
{
  "message": "Forbidden: You need ADMIN or VULN role to refresh outdated assets"
}
```

### 409 Conflict

**Cause**: Another refresh job is already running

```json
{
  "message": "Refresh already in progress",
  "runningJobId": 41,
  "progressPercentage": 45,
  "startedAt": "2025-10-26T14:33:00"
}
```

### 500 Internal Server Error

**Cause**: Failed to start refresh job

```json
{
  "message": "Failed to start refresh job",
  "error": "Database connection error"
}
```

---

## Business Rules

1. **Concurrency Control**:
   - Only ONE refresh job can run at a time
   - If a job is already RUNNING, return 409 Conflict
   - Check for existing RUNNING jobs before creating new one

2. **Job Creation**:
   - Create `MaterializedViewRefreshJob` entity with status=RUNNING
   - Set `triggeredBy="Manual Refresh"`
   - Execute refresh asynchronously using `@Async`

3. **Progress Tracking**:
   - Job entity tracks progress (assetsProcessed, totalAssets, progressPercentage)
   - SSE endpoint (`/api/outdated-assets/refresh-progress`) streams updates
   - Frontend polls job status or subscribes to SSE

4. **Timeout**:
   - Refresh operations have 2-minute timeout
   - If timeout occurs, mark job as FAILED
   - User can retry after timeout

---

## Acceptance Criteria

**From Spec**:
- ✅ User Story 3, Scenario 1: "Refresh" button visible on page
- ✅ User Story 3, Scenario 2: Progress indicator shows during refresh
- ✅ User Story 3, Scenario 5: Duplicate refresh requests are prevented (409 Conflict)

---

## Related Endpoints

- `GET /api/outdated-assets/refresh-progress` - SSE endpoint for progress updates
- `GET /api/outdated-assets/refresh-status/{jobId}` - Poll job status

---

## Implementation Notes

**Controller**:
```kotlin
@Post("/refresh")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun triggerManualRefresh(authentication: Authentication): HttpResponse<RefreshJobResponseDto> {
    // Check for running job
    val existingJob = refreshJobRepository.findRunningJob()
    if (existingJob.isPresent) {
        return HttpResponse.status(HttpStatus.CONFLICT)
            .body(RefreshJobConflictDto(existingJob.get()))
    }

    // Create new job
    val job = materializedViewRefreshService.triggerAsyncRefresh("Manual Refresh")

    return HttpResponse.accepted(RefreshJobResponseDto(job))
}
```

**Service**:
```kotlin
@Async
fun triggerAsyncRefresh(triggeredBy: String): MaterializedViewRefreshJob {
    val job = MaterializedViewRefreshJob(triggeredBy = triggeredBy)
    refreshJobRepository.save(job)

    // Background execution
    executeRefresh(job)

    return job
}
```

**Test Cases**:
1. Successful refresh trigger returns 202 Accepted
2. Duplicate refresh returns 409 Conflict
3. Unauthorized user returns 401
4. VULN role can trigger refresh
5. Job entity is created with correct fields
6. Async execution starts in background
