# API Contract: CrowdStrike Server Import (Existing Endpoint)

**Feature**: 048-prevent-duplicate-vulnerabilities
**Note**: This feature does NOT create new API endpoints. This document describes the existing endpoint behavior being verified.

## Endpoint

**POST** `/api/crowdstrike/servers/import`

**Purpose**: Import vulnerability data from CrowdStrike for multiple servers with automatic asset creation/update

**Authentication**: Required (JWT token in Authorization header)

**Authorization**: User must have appropriate permissions (ADMIN or VULN role typically)

## Request

### Headers

```
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

### Request Body

**Type**: `List<CrowdStrikeVulnerabilityBatchDto>`

**Structure**:

```json
[
  {
    "hostname": "server01.example.com",
    "ip": "10.0.0.1",
    "groups": "Production,WebServers",
    "cloudAccountId": "123456789012",
    "cloudInstanceId": "i-1234567890abcdef0",
    "adDomain": "CORP",
    "osVersion": "Ubuntu 22.04 LTS",
    "vulnerabilities": [
      {
        "cveId": "CVE-2023-0001",
        "severity": "CRITICAL",
        "affectedProduct": "openssl 1.1.1",
        "daysOpen": 45,
        "patchPublicationDate": "2023-01-15T00:00:00"
      },
      {
        "cveId": "CVE-2023-0002",
        "severity": "HIGH",
        "affectedProduct": "nginx 1.18.0",
        "daysOpen": 30,
        "patchPublicationDate": null
      }
    ]
  },
  {
    "hostname": "server02.example.com",
    "vulnerabilities": [...]
  }
]
```

### Field Descriptions

**Server-Level Fields** (per batch item):

| Field | Type | Required | Max Length | Description |
|-------|------|----------|------------|-------------|
| hostname | String | Yes | 255 | Server hostname (must be unique) |
| ip | String | No | 45 | IP address (IPv4 or IPv6) |
| groups | String | No | 512 | Comma-separated group names |
| cloudAccountId | String | No | 255 | AWS/cloud account identifier |
| cloudInstanceId | String | No | 255 | Cloud instance identifier |
| adDomain | String | No | 255 | Active Directory domain |
| osVersion | String | No | 255 | Operating system version |
| vulnerabilities | Array | Yes | - | List of vulnerabilities for this server |

**Vulnerability-Level Fields** (per vulnerability item):

| Field | Type | Required | Max Length | Description |
|-------|------|----------|------------|-------------|
| cveId | String | Yes | 255 | CVE identifier (e.g., "CVE-2023-0001") |
| severity | String | Yes | 50 | Severity level (CRITICAL/HIGH/MEDIUM/LOW) |
| affectedProduct | String | No | 512 | Affected product and version |
| daysOpen | Integer | Yes | - | Number of days vulnerability has been open |
| patchPublicationDate | DateTime | No | - | ISO 8601 date when patch was published |

### Validation Rules

**Server-Level Validation**:
1. `hostname` must not be blank
2. `hostname` must be ≤ 255 characters
3. `vulnerabilities` array must not be empty
4. If provided, optional fields must meet length constraints

**Vulnerability-Level Validation**:
1. `cveId` must not be blank
2. `cveId` must be ≤ 255 characters
3. `severity` must not be blank
4. `severity` must be ≤ 50 characters
5. `affectedProduct` if > 512 characters, will be truncated
6. `daysOpen` must be non-negative integer
7. Vulnerabilities with blank `cveId` will be skipped (not saved)

## Response

### Success Response

**Status**: `200 OK`

**Body**: `ImportStatisticsDto`

```json
{
  "serversProcessed": 2,
  "serversCreated": 1,
  "serversUpdated": 1,
  "vulnerabilitiesImported": 15,
  "vulnerabilitiesSkipped": 2,
  "vulnerabilitiesWithPatchDate": 10,
  "uniqueDomainCount": 1,
  "discoveredDomains": ["CORP"],
  "errors": []
}
```

**Field Descriptions**:

| Field | Type | Description |
|-------|------|-------------|
| serversProcessed | Integer | Total number of servers in the import batch |
| serversCreated | Integer | Number of new assets created |
| serversUpdated | Integer | Number of existing assets updated |
| vulnerabilitiesImported | Integer | Number of vulnerabilities successfully saved |
| vulnerabilitiesSkipped | Integer | Number of vulnerabilities filtered out (no CVE ID, no patch date if required) |
| vulnerabilitiesWithPatchDate | Integer | Number of vulnerabilities with patch publication date |
| uniqueDomainCount | Integer | Number of unique AD domains discovered |
| discoveredDomains | Array[String] | List of unique AD domains (sorted) |
| errors | Array[String] | List of error messages (empty on success) |

### Error Responses

**400 Bad Request**

```json
{
  "error": "Validation failed",
  "violations": [
    "hostname must not be blank",
    "cveId must not be blank"
  ]
}
```

**401 Unauthorized**

```json
{
  "error": "Unauthorized",
  "message": "Authentication required"
}
```

**403 Forbidden**

```json
{
  "error": "Forbidden",
  "message": "Insufficient permissions"
}
```

**413 Request Entity Too Large**

```json
{
  "error": "Request too large",
  "message": "Batch size exceeds limit. Use smaller batches (recommended: 50 servers per request)"
}
```

**500 Internal Server Error**

```json
{
  "error": "Internal server error",
  "message": "Failed to process import"
}
```

## Idempotency Behavior (Focus of This Feature)

### Duplicate Prevention Guarantee

**Contract**: Running the same import multiple times produces identical final state

**Mechanism**: Transactional replace pattern
1. For each server in the batch:
   - Find or create Asset by hostname
   - **DELETE all existing vulnerabilities** for that asset
   - **INSERT new vulnerabilities** from import data
   - Both operations in single transaction (atomic)

**Implications**:
- Importing 10 vulnerabilities for server01 twice → server01 has 10 vulnerabilities (not 20)
- Importing 8 vulnerabilities when server01 previously had 10 → server01 has 8 vulnerabilities (2 removed)
- Import failure rolls back both delete and insert → asset retains pre-import state

### Example Scenarios

**Scenario 1: First Import**

Request:
```json
[
  {
    "hostname": "server01",
    "vulnerabilities": [
      {"cveId": "CVE-2023-0001", "severity": "HIGH", "daysOpen": 10},
      {"cveId": "CVE-2023-0002", "severity": "MEDIUM", "daysOpen": 5}
    ]
  }
]
```

Database State After:
- Asset "server01" created
- 2 vulnerabilities created

Response:
```json
{
  "serversProcessed": 1,
  "serversCreated": 1,
  "serversUpdated": 0,
  "vulnerabilitiesImported": 2,
  "vulnerabilitiesSkipped": 0,
  "errors": []
}
```

**Scenario 2: Repeated Import (Same Data)**

Request: (same as Scenario 1)

Database State After:
- Asset "server01" exists (updated metadata: lastSeen, IP if changed)
- 2 vulnerabilities (OLD deleted, NEW inserted - final state identical)

Response:
```json
{
  "serversProcessed": 1,
  "serversCreated": 0,
  "serversUpdated": 1,
  "vulnerabilitiesImported": 2,
  "vulnerabilitiesSkipped": 0,
  "errors": []
}
```

**Key Observation**: `vulnerabilitiesImported: 2` reported both times because:
- Import always reports what was inserted (not delta)
- Idempotent: final database state is identical after both imports

**Scenario 3: Import with New Vulnerability Added**

Request:
```json
[
  {
    "hostname": "server01",
    "vulnerabilities": [
      {"cveId": "CVE-2023-0001", "severity": "HIGH", "daysOpen": 12},
      {"cveId": "CVE-2023-0002", "severity": "MEDIUM", "daysOpen": 7},
      {"cveId": "CVE-2023-0003", "severity": "CRITICAL", "daysOpen": 1}
    ]
  }
]
```

Database State After:
- Asset "server01" exists
- 3 vulnerabilities (old 2 deleted, new 3 inserted)

Response:
```json
{
  "serversProcessed": 1,
  "serversCreated": 0,
  "serversUpdated": 1,
  "vulnerabilitiesImported": 3,
  "vulnerabilitiesSkipped": 0,
  "errors": []
}
```

**Scenario 4: Import with Vulnerability Removed (Remediated)**

Request:
```json
[
  {
    "hostname": "server01",
    "vulnerabilities": [
      {"cveId": "CVE-2023-0001", "severity": "HIGH", "daysOpen": 14}
    ]
  }
]
```

Database State After:
- Asset "server01" exists
- 1 vulnerability (old 3 deleted, new 1 inserted)
- CVE-2023-0002 and CVE-2023-0003 removed (remediated)

Response:
```json
{
  "serversProcessed": 1,
  "serversCreated": 0,
  "serversUpdated": 1,
  "vulnerabilitiesImported": 1,
  "vulnerabilitiesSkipped": 0,
  "errors": []
}
```

## Transaction Behavior

### Atomicity Guarantee

**Contract**: Each server is processed in an isolated transaction

**Implementation**: `@Transactional` annotation on `importVulnerabilitiesForServer()` method

**Behavior**:
- Transaction per server (not per batch)
- If server01 import fails, server02 import can still succeed
- Within a server's transaction:
  - Asset create/update
  - Vulnerability delete
  - Vulnerability insert
  - All succeed together OR all fail together (rollback)

### Rollback Scenarios

**Scenario**: Database constraint violation during vulnerability insert

```
1. Asset "server01" found/created ✅
2. DELETE vulnerabilities for server01 ✅
3. INSERT vulnerability with invalid data ❌ (constraint violation)
4. ROLLBACK transaction:
   - Asset changes reverted (if created) or kept (if updated)
   - Delete operation reverted (old vulnerabilities restored)
   - Insert operation reverted (no partial data)
```

**Result**: Asset remains in consistent state (either pre-import state or no state if new)

## Concurrency Behavior

### Concurrent Imports for Different Assets

**Contract**: Safe to import multiple assets concurrently

**Behavior**:
- Each asset has independent transaction
- No shared locks between different assets
- Parallel processing allowed

**Example**:
- Thread 1: Import server01
- Thread 2: Import server02
- Both succeed without conflicts

### Concurrent Imports for Same Asset

**Contract**: Sequential processing enforced by database locking

**Behavior**:
- Database row-level lock on Asset during transaction
- Second transaction waits for first to complete
- No data corruption or race conditions

**Example**:
- Thread 1: Import server01 (START transaction at 10:00:00.000)
- Thread 2: Import server01 (WAIT for Thread 1 to complete)
- Thread 1: COMMIT at 10:00:00.050
- Thread 2: START transaction at 10:00:00.051

**Result**: Sequential execution guaranteed, last import wins

## Performance Characteristics

**Typical Response Times** (based on research):
- 1 server with 10 vulnerabilities: ~20-50ms
- 50 servers with 500 vulnerabilities: ~1-2 seconds
- 1,000 servers with 10,000 vulnerabilities: ~3-5 minutes

**Batch Size Recommendations**:
- Optimal: 50 servers per request (default)
- Maximum: 100 servers per request (avoid 413 errors)
- For large imports: Use chunking in CLI layer

**Bottlenecks**:
- Network latency (CLI → Backend HTTP)
- Database writes (mitigated by batch insert)
- Transaction overhead (acceptable for idempotency)

## Testing Contract

### Test Scenarios Required

This feature adds test coverage to verify the contract:

1. **Idempotent Import Test**
   - Import same data twice
   - Verify: `vulnerabilitiesImported` same both times
   - Verify: Database has correct count (not doubled)

2. **Concurrent Import Test**
   - Import different assets in parallel
   - Verify: All assets imported correctly
   - Verify: No race conditions or deadlocks

3. **Transaction Rollback Test**
   - Simulate failure during vulnerability insert
   - Verify: Rollback restores consistent state
   - Verify: No partial data persists

4. **Vulnerability Filtering Test**
   - Import vulnerabilities without CVE IDs
   - Verify: These are skipped (not saved)
   - Verify: `vulnerabilitiesSkipped` count accurate

5. **Asset Update Test**
   - Import server with IP 10.0.0.1
   - Import again with IP 10.0.0.2
   - Verify: IP updated to 10.0.0.2
   - Verify: Only one asset exists (not duplicated)

## Summary

| Aspect | Contract |
|--------|----------|
| **Endpoint** | POST /api/crowdstrike/servers/import |
| **Idempotency** | ✅ Same input → same output (guaranteed) |
| **Duplicate Prevention** | ✅ Transactional replace (delete + insert) |
| **Transaction Scope** | Per server (isolation between servers) |
| **Rollback Behavior** | ✅ All-or-nothing (no partial states) |
| **Concurrency** | ✅ Safe for different assets, sequential for same asset |
| **Performance** | < 5 minutes for 1000 assets (meets requirements) |
| **Changes for This Feature** | ❌ None - existing endpoint verified, not modified |
