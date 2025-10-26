# API Contract: Get Refresh Job Status

**Feature**: 034-outdated-assets
**Endpoint**: `GET /api/outdated-assets/refresh-status/{jobId}`
**User Story**: User Story 3 - Manual Refresh of Outdated Assets (P2)

## Request

### HTTP Method
`GET`

### URL
`/api/outdated-assets/refresh-status/{jobId}`

### Authentication
**Required**: Yes
- JWT token in Authorization header
- Roles: `ADMIN` or `VULN`

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `jobId` | `Long` | Yes | Refresh job identifier |

### Example Request

```http
GET /api/outdated-assets/refresh-status/42 HTTP/1.1
Host: example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## Response

### Success Response (200 OK) - Running Job

```json
{
  "jobId": 42,
  "status": "RUNNING",
  "triggeredBy": "Manual Refresh",
  "startedAt": "2025-10-26T14:35:00",
  "completedAt": null,
  "assetsProcessed": 5000,
  "totalAssets": 10000,
  "progressPercentage": 50,
  "durationMs": 15000,
  "errorMessage": null
}
```

### Success Response (200 OK) - Completed Job

```json
{
  "jobId": 42,
  "status": "COMPLETED",
  "triggeredBy": "Manual Refresh",
  "startedAt": "2025-10-26T14:35:00",
  "completedAt": "2025-10-26T14:35:28",
  "assetsProcessed": 10000,
  "totalAssets": 10000,
  "progressPercentage": 100,
  "durationMs": 28000,
  "errorMessage": null
}
```

### Success Response (200 OK) - Failed Job

```json
{
  "jobId": 42,
  "status": "FAILED",
  "triggeredBy": "Manual Refresh",
  "startedAt": "2025-10-26T14:35:00",
  "completedAt": "2025-10-26T14:37:00",
  "assetsProcessed": 6500,
  "totalAssets": 10000,
  "progressPercentage": 65,
  "durationMs": 120000,
  "errorMessage": "Query timeout after 120 seconds"
}
```

**Response Fields**:
| Field | Type | Description |
|-------|------|-------------|
| `jobId` | `Long` | Unique job identifier |
| `status` | `String` | RUNNING, COMPLETED, FAILED |
| `triggeredBy` | `String` | "Manual Refresh", "CLI Import", "Config Change" |
| `startedAt` | `ISO 8601 DateTime` | Job start timestamp |
| `completedAt` | `ISO 8601 DateTime` or `null` | Job completion timestamp (null if RUNNING) |
| `assetsProcessed` | `Integer` | Assets processed so far |
| `totalAssets` | `Integer` | Total assets to process |
| `progressPercentage` | `Integer` | 0-100 |
| `durationMs` | `Long` | Duration in milliseconds |
| `errorMessage` | `String` or `null` | Error details if FAILED |

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
  "message": "Forbidden: You need ADMIN or VULN role to view refresh status"
}
```

### 404 Not Found

**Cause**: Job ID does not exist

```json
{
  "message": "Refresh job not found",
  "jobId": 999
}
```

---

## Business Rules

1. **Polling Alternative**:
   - This endpoint is an alternative to SSE for clients that prefer polling
   - Frontend can poll every 2-5 seconds during refresh
   - Less efficient than SSE but simpler for some clients

2. **Historical Jobs**:
   - All jobs are retained (no automatic deletion)
   - Can query status of old completed/failed jobs
   - Useful for debugging and audit trail

3. **Duration Calculation**:
   - For RUNNING jobs: `durationMs = now() - startedAt`
   - For COMPLETED/FAILED jobs: `durationMs = completedAt - startedAt`

---

## Acceptance Criteria

**From Spec**:
- ✅ User Story 3, Scenario 2: Frontend can track refresh progress
- ✅ FR-017: Last refresh timestamp is tracked and displayed

---

## Implementation Notes

**Controller**:
```kotlin
@Get("/refresh-status/{jobId}")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun getRefreshStatus(@PathVariable jobId: Long): HttpResponse<RefreshJobStatusDto> {
    val job = refreshJobRepository.findById(jobId)
        .orElseThrow { NotFoundException("Refresh job not found") }

    return HttpResponse.ok(RefreshJobStatusDto.from(job))
}
```

**DTO**:
```kotlin
@Serdeable
data class RefreshJobStatusDto(
    val jobId: Long,
    val status: String,
    val triggeredBy: String,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val assetsProcessed: Int,
    val totalAssets: Int,
    val progressPercentage: Int,
    val durationMs: Long,
    val errorMessage: String?
) {
    companion object {
        fun from(job: MaterializedViewRefreshJob): RefreshJobStatusDto {
            return RefreshJobStatusDto(
                jobId = job.id!!,
                status = job.status.name,
                triggeredBy = job.triggeredBy,
                startedAt = job.startedAt,
                completedAt = job.completedAt,
                assetsProcessed = job.assetsProcessed,
                totalAssets = job.totalAssets,
                progressPercentage = job.progressPercentage,
                durationMs = job.durationMs ?: Duration.between(job.startedAt, LocalDateTime.now()).toMillis(),
                errorMessage = job.errorMessage
            )
        }
    }
}
```

**Test Cases**:
1. Get status of running job
2. Get status of completed job
3. Get status of failed job
4. 404 for non-existent job
5. Duration calculated correctly for running job
6. Duration calculated correctly for completed job
