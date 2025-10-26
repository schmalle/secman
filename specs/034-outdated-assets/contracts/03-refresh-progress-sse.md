# API Contract: Refresh Progress Updates (SSE)

**Feature**: 034-outdated-assets
**Endpoint**: `GET /api/outdated-assets/refresh-progress`
**User Story**: User Story 3 - Manual Refresh of Outdated Assets (P2)

## Request

### HTTP Method
`GET`

### URL
`/api/outdated-assets/refresh-progress`

### Authentication
**Required**: Yes
- JWT token in Authorization header
- Roles: `ADMIN` or `VULN`

### Headers
```
Accept: text/event-stream
```

### Example Request

```http
GET /api/outdated-assets/refresh-progress HTTP/1.1
Host: example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Accept: text/event-stream
```

---

## Response

### Success Response (200 OK)

**Content-Type**: `text/event-stream`

**Structure**: Server-Sent Events stream

### Event Format

```
event: progress-update
data: {"jobId":42,"status":"RUNNING","progressPercentage":35,"assetsProcessed":3500,"totalAssets":10000,"message":"Processing assets..."}

event: progress-update
data: {"jobId":42,"status":"RUNNING","progressPercentage":70,"assetsProcessed":7000,"totalAssets":10000,"message":"Processing assets..."}

event: progress-update
data: {"jobId":42,"status":"COMPLETED","progressPercentage":100,"assetsProcessed":10000,"totalAssets":10000,"message":"Refresh completed successfully"}
```

### Event Data Fields

| Field | Type | Description |
|-------|------|-------------|
| `jobId` | `Long` | Refresh job identifier |
| `status` | `String` | Job status: RUNNING, COMPLETED, FAILED |
| `progressPercentage` | `Integer` | Progress: 0-100 |
| `assetsProcessed` | `Integer` | Number of assets processed |
| `totalAssets` | `Integer` | Total assets to process |
| `message` | `String` | Human-readable status message |

### Status Values

- `RUNNING`: Refresh is in progress
- `COMPLETED`: Refresh finished successfully
- `FAILED`: Refresh failed with error

### Completion Event (Success)

```
event: progress-update
data: {"jobId":42,"status":"COMPLETED","progressPercentage":100,"assetsProcessed":10000,"totalAssets":10000,"message":"Refresh completed successfully"}
```

### Completion Event (Failure)

```
event: progress-update
data: {"jobId":42,"status":"FAILED","progressPercentage":65,"assetsProcessed":6500,"totalAssets":10000,"message":"Database timeout during refresh"}
```

---

## Error Responses

### 401 Unauthorized

**Cause**: Missing or invalid JWT token

Browser will fail to establish SSE connection.

### 403 Forbidden

**Cause**: User does not have ADMIN or VULN role

Browser will fail to establish SSE connection.

---

## Business Rules

1. **Connection Lifecycle**:
   - Client connects to SSE endpoint
   - Server immediately sends current progress (if job running)
   - Server broadcasts updates as refresh progresses
   - Client disconnects after COMPLETED or FAILED event

2. **Event Frequency**:
   - Updates sent every batch (1000 assets)
   - Or every 5 seconds (whichever is more frequent)
   - Final COMPLETED/FAILED event always sent

3. **Multicast**:
   - Multiple clients can connect simultaneously
   - All clients receive same progress updates
   - Uses Reactor `Sinks.Many.multicast()`

4. **Reconnection**:
   - If connection drops, client should reconnect
   - Server sends latest progress on new connection
   - No event replay (only current state)

---

## Acceptance Criteria

**From Spec**:
- ✅ User Story 3, Scenario 2: Progress indicator shows percentage (e.g., "Refreshing... 35%")
- ✅ FR-007: Progress indicator with percentage displayed during refresh

---

## Client Usage (Frontend)

### JavaScript Example

```javascript
const eventSource = new EventSource('/api/outdated-assets/refresh-progress', {
  headers: {
    'Authorization': `Bearer ${jwtToken}`
  }
});

eventSource.addEventListener('progress-update', (event) => {
  const data = JSON.parse(event.data);

  if (data.status === 'RUNNING') {
    updateProgressBar(data.progressPercentage);
    updateMessage(`Refreshing... ${data.progressPercentage}%`);
  } else if (data.status === 'COMPLETED') {
    updateProgressBar(100);
    updateMessage('Refresh completed successfully!');
    eventSource.close();
    reloadOutdatedAssetsList();
  } else if (data.status === 'FAILED') {
    showError(`Refresh failed: ${data.message}`);
    eventSource.close();
  }
});

eventSource.onerror = (error) => {
  console.error('SSE connection error:', error);
  eventSource.close();
};
```

---

## Implementation Notes

**Controller**:
```kotlin
@Get("/refresh-progress", produces = [MediaType.TEXT_EVENT_STREAM])
@Secured(SecurityRule.IS_AUTHENTICATED)
fun streamRefreshProgress(): Publisher<Event<RefreshProgressData>> {
    // Get current job if any
    val currentJob = refreshJobRepository.findRunningJob()

    // Send initial event
    val initialEvent = currentJob.map { job ->
        Event.of(RefreshProgressData.from(job)).name("progress-update")
    }.orElse(null)

    // Merge initial event with future updates
    return if (initialEvent != null) {
        Flux.concat(
            Flux.just(initialEvent),
            progressSink.asFlux().map { data ->
                Event.of(data).name("progress-update")
            }
        )
    } else {
        progressSink.asFlux().map { data ->
            Event.of(data).name("progress-update")
        }
    }
}
```

**Event Publisher** (in MaterializedViewRefreshService):
```kotlin
@Inject
lateinit var eventPublisher: ApplicationEventPublisher

fun publishProgress(job: MaterializedViewRefreshJob) {
    val event = RefreshProgressEvent(
        jobId = job.id!!,
        status = job.status,
        progressPercentage = job.progressPercentage,
        assetsProcessed = job.assetsProcessed,
        totalAssets = job.totalAssets,
        message = when (job.status) {
            RefreshJobStatus.RUNNING -> "Processing assets..."
            RefreshJobStatus.COMPLETED -> "Refresh completed successfully"
            RefreshJobStatus.FAILED -> job.errorMessage ?: "Refresh failed"
        }
    )
    eventPublisher.publish(event)
}
```

**Event Listener** (in OutdatedAssetRefreshProgressHandler):
```kotlin
override fun onApplicationEvent(event: RefreshProgressEvent) {
    val data = RefreshProgressData(
        jobId = event.jobId,
        status = event.status.name,
        progressPercentage = event.progressPercentage,
        assetsProcessed = event.assetsProcessed,
        totalAssets = event.totalAssets,
        message = event.message
    )

    progressSink.tryEmitNext(data)
}
```

**Test Cases**:
1. SSE connection established successfully
2. Initial progress event sent on connection
3. Progress events broadcast to all clients
4. COMPLETED event triggers client disconnect
5. FAILED event triggers client disconnect
6. Reconnection sends latest progress
7. Multiple clients receive same events
