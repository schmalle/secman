# Quickstart Guide: AWS Instance ID Lookup

**Feature**: 041-falcon-instance-lookup
**Date**: 2025-11-03

---

## Overview

This guide provides practical integration scenarios, example requests, and troubleshooting tips for the AWS Instance ID lookup feature.

---

## Prerequisites

1. **CrowdStrike Falcon Configuration**:
   - OAuth2 credentials configured in FalconConfig entity
   - API scopes: `hosts:read`, `spotlight-vulnerabilities:read`
   - Active Falcon sensors on AWS EC2 instances

2. **User Authentication**:
   - JWT token obtained via `/api/auth/login`
   - User has ADMIN or VULN role

3. **AWS Instance ID Availability**:
   - Falcon sensor installed on EC2 instance
   - Instance metadata service (IMDS) accessible
   - `instance_id` field populated in CrowdStrike

---

## Integration Scenarios

### Scenario 1: Query by Hostname (Existing Functionality)

**Use Case**: Traditional hostname-based vulnerability lookup

**cURL Example**:
```bash
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=web-server-01' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Accept: application/json'
```

**Response** (HTTP 200):
```json
{
  "hostname": "web-server-01",
  "instanceId": null,
  "deviceCount": null,
  "vulnerabilities": [
    {
      "id": "vuln_123",
      "hostname": "web-server-01",
      "ip": "10.0.1.100",
      "cveId": "CVE-2024-1234",
      "severity": "Critical",
      "cvssScore": 9.8,
      "affectedProduct": "Apache HTTP Server 2.4.58",
      "daysOpen": "15 days",
      "detectedAt": "2024-10-15T10:30:00Z",
      "status": "OPEN",
      "hasException": false,
      "exceptionReason": null
    }
  ],
  "totalCount": 1,
  "queriedAt": "2025-11-03T14:30:00Z"
}
```

**Frontend Example** (React/TypeScript):
```typescript
import axios from 'axios';

async function queryByHostname(hostname: string) {
  const token = sessionStorage.getItem('jwt');
  const response = await axios.get('/api/vulnerabilities', {
    params: { hostname },
    headers: { Authorization: `Bearer ${token}` }
  });
  return response.data;
}

// Usage
const results = await queryByHostname('web-server-01');
console.log(`Found ${results.totalCount} vulnerabilities`);
```

---

### Scenario 2: Query by AWS Instance ID (Feature 041)

**Use Case**: Lookup vulnerabilities using AWS EC2 instance ID

**cURL Example**:
```bash
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Accept: application/json'
```

**Response** (HTTP 200):
```json
{
  "hostname": "web-server-01",
  "instanceId": "i-0048f94221fe110cf",
  "deviceCount": 1,
  "vulnerabilities": [
    {
      "id": "vuln_123",
      "hostname": "web-server-01",
      "ip": "10.0.1.100",
      "cveId": "CVE-2024-1234",
      "severity": "Critical",
      "cvssScore": 9.8,
      "affectedProduct": "Apache HTTP Server 2.4.58",
      "daysOpen": "15 days",
      "detectedAt": "2024-10-15T10:30:00Z",
      "status": "OPEN",
      "hasException": false,
      "exceptionReason": null
    }
  ],
  "totalCount": 1,
  "queriedAt": "2025-11-03T14:30:00Z"
}
```

**Frontend Example with Auto-Detection**:
```typescript
function detectQueryType(input: string): 'hostname' | 'instance_id' {
  const instanceIdPattern = /^i-[0-9a-fA-F]{8,17}$/i;
  return instanceIdPattern.test(input) ? 'instance_id' : 'hostname';
}

async function queryVulnerabilities(input: string) {
  const queryType = detectQueryType(input);
  console.log(`Detected query type: ${queryType}`);

  const token = sessionStorage.getItem('jwt');
  const response = await axios.get('/api/vulnerabilities', {
    params: { hostname: input }, // Backend auto-detects
    headers: { Authorization: `Bearer ${token}` }
  });

  return response.data;
}

// Usage
const results = await queryVulnerabilities('i-0048f94221fe110cf');
console.log(`Found ${results.totalCount} vulnerabilities for instance ${results.instanceId}`);
```

---

### Scenario 3: Query with Filtering

**Use Case**: Filter instance ID results by severity and product

**cURL Example**:
```bash
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf&severity=critical&product=apache&limit=50' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'Accept: application/json'
```

**Response** (HTTP 200 - filtered results):
```json
{
  "hostname": "web-server-01",
  "instanceId": "i-0048f94221fe110cf",
  "deviceCount": 1,
  "vulnerabilities": [
    {
      "id": "vuln_123",
      "hostname": "web-server-01",
      "cveId": "CVE-2024-1234",
      "severity": "Critical",
      "affectedProduct": "Apache HTTP Server 2.4.58",
      ...
    }
  ],
  "totalCount": 1,
  "queriedAt": "2025-11-03T14:30:00Z"
}
```

**Frontend Example**:
```typescript
async function queryWithFilters(
  input: string,
  filters: { severity?: string; product?: string; limit?: number }
) {
  const token = sessionStorage.getItem('jwt');
  const response = await axios.get('/api/vulnerabilities', {
    params: {
      hostname: input,
      ...filters
    },
    headers: { Authorization: `Bearer ${token}` }
  });
  return response.data;
}

// Usage
const results = await queryWithFilters('i-0048f94221fe110cf', {
  severity: 'critical',
  product: 'apache',
  limit: 50
});
```

---

### Scenario 4: Cache Behavior

**Use Case**: Understand cache freshness indicators

**First Request** (cache miss):
```bash
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN'
```

**Response**:
```json
{
  "hostname": "web-server-01",
  "instanceId": "i-0048f94221fe110cf",
  "queriedAt": "2025-11-03T14:30:00Z",  // Just fetched
  ...
}
```

**Second Request (within 15 minutes)** - served from cache:
```bash
# Same request 5 minutes later
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN'
```

**Response** (same data, cached):
```json
{
  "hostname": "web-server-01",
  "instanceId": "i-0048f94221fe110cf",
  "queriedAt": "2025-11-03T14:30:00Z",  // Original timestamp
  ...
}
```

**Frontend Cache Freshness Indicator**:
```typescript
function getCacheFreshness(queriedAt: string): 'fresh' | 'cached' {
  const queriedTime = new Date(queriedAt).getTime();
  const now = new Date().getTime();
  const ageMinutes = (now - queriedTime) / (1000 * 60);

  return ageMinutes <= 1 ? 'fresh' : 'cached';
}

function renderFreshnessBadge(queriedAt: string) {
  const freshness = getCacheFreshness(queriedAt);
  const ageMinutes = Math.floor((new Date().getTime() - new Date(queriedAt).getTime()) / (1000 * 60));

  if (freshness === 'fresh') {
    return <span className="badge bg-success">⚡ Live data</span>;
  } else {
    return <span className="badge bg-info">📋 Cached ({ageMinutes} min ago)</span>;
  }
}
```

---

### Scenario 5: Multiple Devices with Same Instance ID (Edge Case)

**Use Case**: Handle rare case where multiple devices report same instance ID

**cURL Example**:
```bash
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN'
```

**Response** (HTTP 200 - aggregated results):
```json
{
  "hostname": "web-server-01, web-server-02",
  "instanceId": "i-0048f94221fe110cf",
  "deviceCount": 2,  // Indicates multiple devices
  "vulnerabilities": [
    {
      "id": "vuln_123",
      "hostname": "web-server-01",
      ...
    },
    {
      "id": "vuln_456",
      "hostname": "web-server-02",
      ...
    }
  ],
  "totalCount": 2,
  "queriedAt": "2025-11-03T14:30:00Z"
}
```

**Frontend Handling**:
```typescript
function renderResults(response: CrowdStrikeQueryResponse) {
  return (
    <div>
      <h3>Vulnerabilities for {response.hostname}</h3>

      {response.deviceCount && response.deviceCount > 1 && (
        <div className="alert alert-info">
          <i className="bi bi-info-circle me-2"></i>
          Found {response.deviceCount} systems with instance ID {response.instanceId}.
          Showing aggregated vulnerabilities.
        </div>
      )}

      {/* Render vulnerability table */}
    </div>
  );
}
```

---

## Error Handling

### Error 1: Invalid Instance ID Format

**Request**:
```bash
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=i-invalid' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN'
```

**Response** (HTTP 400):
```json
{
  "error": "Invalid instance ID format. Expected 'i-' followed by 8 or 17 hexadecimal characters (e.g., i-0048f94221fe110cf)"
}
```

**Frontend Handling**:
```typescript
try {
  const results = await queryVulnerabilities(input);
} catch (error) {
  if (error.response?.status === 400) {
    alert(`Validation error: ${error.response.data.error}`);
  }
}
```

---

### Error 2: Instance ID Not Found

**Request**:
```bash
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=i-nonexistent123' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN'
```

**Response** (HTTP 404):
```json
{
  "error": "System not found with instance ID: i-nonexistent123"
}
```

**Frontend Handling**:
```typescript
try {
  const results = await queryVulnerabilities(input);
} catch (error) {
  if (error.response?.status === 404) {
    alert(`System not found: ${input}`);
  }
}
```

---

### Error 3: Rate Limit Exceeded

**Request**:
```bash
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN'
```

**Response** (HTTP 429):
```json
{
  "error": "CrowdStrike API rate limit exceeded. Try again in 60 seconds."
}
```

**Response Headers**:
```
Retry-After: 60
```

**Frontend Handling**:
```typescript
try {
  const results = await queryVulnerabilities(input);
} catch (error) {
  if (error.response?.status === 429) {
    const retryAfter = error.response.headers['retry-after'] || 60;
    alert(`Rate limit exceeded. Please wait ${retryAfter} seconds.`);
  }
}
```

---

### Error 4: CrowdStrike Configuration Missing

**Request**:
```bash
curl -X GET \
  'http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN'
```

**Response** (HTTP 500):
```json
{
  "error": "CrowdStrike API credentials not configured. Contact administrator."
}
```

**Resolution**: Admin must configure FalconConfig in database

---

## Input Validation Reference

### Valid AWS Instance IDs

| Format | Example | Description |
|--------|---------|-------------|
| Legacy (8 chars) | `i-1234567a` | Pre-2018 EC2 instances |
| Current (17 chars) | `i-0048f94221fe110cf` | Post-2018 EC2 instances |
| Uppercase | `i-ABC123EF` | Accepted (case-insensitive) |
| Mixed case | `i-0048F94221FE110CF` | Accepted (normalized to lowercase) |

### Invalid Inputs

| Input | Reason | Error Message |
|-------|--------|---------------|
| `i-ABC` | Too short (only 3 chars) | Invalid format |
| `i-123456789` | Invalid length (9 chars) | Invalid format |
| `i-0048g94221fe110cf` | Invalid character ('g') | Invalid format |
| `I-0048f94221fe110cf` | Uppercase prefix | Treated as hostname (not instance ID) |

**Frontend Validation**:
```typescript
function validateInstanceId(input: string): string | null {
  const pattern = /^i-[0-9a-fA-F]{8,17}$/i;

  if (!input.trim()) {
    return "Please enter a hostname or instance ID";
  }

  // If starts with i-, validate as instance ID
  if (input.startsWith('i-') && !pattern.test(input)) {
    return "Invalid instance ID format. Expected 'i-' followed by 8 or 17 hexadecimal characters (e.g., i-0048f94221fe110cf)";
  }

  return null; // Valid (either valid instance ID or treated as hostname)
}
```

---

## Performance Considerations

### Cache Hit Scenario

**First Request** (3-5 seconds - API call):
```
User → Frontend → Backend → CrowdStrike API → Backend → Frontend → User
       [------------ 3-5 seconds ----------------]
```

**Second Request** (< 500ms - cache hit):
```
User → Frontend → Backend (cache) → Frontend → User
       [---- < 500ms ----]
```

### Refresh Button Behavior

**Purpose**: Force cache bypass for fresh data

**Implementation**:
```typescript
async function handleRefresh() {
  // Clear local cache reference (if any)
  setQueryResponse(null);

  // Re-execute query (backend handles cache invalidation)
  await handleSearch();
}
```

**Backend**: Refresh button generates new query timestamp, creating new cache key

---

## Testing with curl

### Complete Test Sequence

```bash
# 1. Obtain JWT token
TOKEN=$(curl -X POST http://localhost:4321/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r '.token')

# 2. Query by instance ID
curl -X GET \
  "http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf" \
  -H "Authorization: Bearer $TOKEN" | jq .

# 3. Query with filters
curl -X GET \
  "http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf&severity=critical&limit=10" \
  -H "Authorization: Bearer $TOKEN" | jq .

# 4. Test cache (repeat same query immediately)
curl -X GET \
  "http://localhost:4321/api/vulnerabilities?hostname=i-0048f94221fe110cf" \
  -H "Authorization: Bearer $TOKEN" | jq '.queriedAt'  # Should match previous

# 5. Test invalid format
curl -X GET \
  "http://localhost:4321/api/vulnerabilities?hostname=i-invalid" \
  -H "Authorization: Bearer $TOKEN"  # Expect 400

# 6. Test not found
curl -X GET \
  "http://localhost:4321/api/vulnerabilities?hostname=i-nonexistent123abc" \
  -H "Authorization: Bearer $TOKEN"  # Expect 404
```

---

## Troubleshooting Guide

### Issue: Instance ID not found (404 error)

**Symptoms**:
```json
{
  "error": "System not found with instance ID: i-0048f94221fe110cf"
}
```

**Possible Causes**:
1. Instance ID doesn't exist in CrowdStrike
2. Falcon sensor not installed on EC2 instance
3. Instance metadata not populated

**Resolution**:
- Verify instance ID in AWS console
- Check Falcon sensor status on instance
- Ensure IMDS is accessible to sensor
- Wait 5-10 minutes for metadata sync after sensor installation

---

### Issue: Empty vulnerabilities list

**Symptoms**:
```json
{
  "hostname": "web-server-01",
  "vulnerabilities": [],
  "totalCount": 0
}
```

**Possible Causes**:
1. No vulnerabilities detected by CrowdStrike
2. All vulnerabilities patched/resolved
3. Filters too restrictive

**Resolution**:
- Check CrowdStrike console for this system
- Remove filters and re-query
- Verify Spotlight Vulnerabilities is enabled in CrowdStrike

---

### Issue: Cache not refreshing

**Symptoms**: Old data displayed after 15+ minutes

**Possible Causes**:
1. System clock drift
2. Cache misconfiguration
3. Browser caching response

**Resolution**:
- Verify server time matches real time
- Check `application.yml` cache configuration
- Use browser dev tools to inspect response headers
- Force refresh with Ctrl+F5

---

## Next Steps

1. **Implementation**: Proceed to `/speckit.tasks` for detailed task breakdown
2. **Testing**: Write contract tests per TDD principles
3. **Deployment**: Follow standard deployment workflow
4. **Monitoring**: Track cache hit rates and API response times

---

**Quickstart Status**: ✅ COMPLETE
**Ready for Implementation**: YES
