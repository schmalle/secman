# API Contract Changes: Memory Optimization

**Feature**: 073-memory-optimization
**Date**: 2026-02-03

## Overview

This feature is primarily internal optimization. API contracts remain backward compatible. The main visible change is in response payload structure (DTO streamlining).

## Endpoint Changes

### GET /api/vulnerabilities/current

**Change**: Response DTO structure modified

**Before**:
```json
{
  "content": [
    {
      "id": 12345,
      "asset": {
        "id": 100,
        "name": "server-001",
        "type": "SERVER",
        "ip": "192.168.1.100",
        "owner": "IT Team",
        "workgroups": [...]
      },
      "assetId": 100,
      "assetName": "server-001",
      "assetIp": "192.168.1.100",
      "cveId": "CVE-2024-1234",
      ...
    }
  ],
  ...
}
```

**After**:
```json
{
  "content": [
    {
      "id": 12345,
      "assetId": 100,
      "assetName": "server-001",
      "assetIp": "192.168.1.100",
      "cveId": "CVE-2024-1234",
      ...
    }
  ],
  ...
}
```

**Backward Compatibility**:
- Flat fields `assetId`, `assetName`, `assetIp` already present
- Clients using flat fields: No change required
- Clients using nested `asset` object: Must migrate to flat fields

**Migration Path**:
1. Frontend already uses flat fields in table display
2. No breaking change for existing integrations using standard fields

---

## New Endpoint

### GET /memory (Management Endpoint)

**Purpose**: Expose JVM heap memory metrics for monitoring

**Request**: No parameters

**Response**:
```json
{
  "heap": {
    "used": 256,
    "max": 512,
    "free": 128
  },
  "timestamp": 1706918400000
}
```

**Fields**:
| Field | Type | Description |
|-------|------|-------------|
| heap.used | integer | Used heap memory in MB |
| heap.max | integer | Maximum heap memory in MB |
| heap.free | integer | Free heap memory in MB |
| timestamp | long | Unix timestamp in milliseconds |

**Authentication**: Anonymous (management endpoint)

**Configuration**:
```yaml
endpoints:
  memory:
    enabled: true
    sensitive: false
```

---

## Query Parameter Behavior (Unchanged)

All existing query parameters on `/api/vulnerabilities/current` maintain identical behavior:
- `severity` - Filter by severity level
- `system` - Filter by asset name
- `exceptionStatus` - Filter by exception status (now SQL-level, same API)
- `product` - Filter by product
- `adDomain` - Filter by AD domain
- `cloudAccountId` - Filter by cloud account
- `page`, `size` - Pagination

**Performance Improvement**: `exceptionStatus` filter now executes at SQL level instead of loading all data and filtering in memory.

---

## Configuration Endpoints (Admin Only)

No new configuration endpoints. Feature flags are environment-variable based:

| Variable | Default | Description |
|----------|---------|-------------|
| `MEMORY_LAZY_LOADING` | true | Enable LAZY entity loading |
| `MEMORY_BATCH_SIZE` | 1000 | Batch size for large operations |
| `MEMORY_STREAMING_EXPORTS` | true | Enable streaming exports |

---

## Error Responses

No new error codes introduced. Existing error handling patterns maintained.
