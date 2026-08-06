# Research Report: CrowdStrike Falcon API - AWS Instance ID Metadata Queries

**Feature**: 041-falcon-instance-lookup
**Research Date**: 2025-11-03
**Researcher**: Claude Code Agent
**Status**: Complete

## Executive Summary

This research confirms that CrowdStrike Falcon API fully supports querying systems by AWS EC2 Instance ID metadata. The API provides dedicated fields (`instance_id`, `service_provider`, `service_provider_account_id`) for cloud infrastructure identification and supports FQL filter syntax for querying devices by these metadata fields.

**Key Finding**: AWS instance IDs are stored in the `instance_id` field (format: `i-[0-9a-fA-F]{8,17}`) and can be used directly in FQL filters via the Hosts API endpoint `/devices/queries/devices/v1`.

---

## Research Questions & Answers

### 1. CrowdStrike Falcon API - AWS Instance ID Metadata Query

**Question**: What is the exact API endpoint and query syntax for searching CrowdStrike systems by AWS instance ID metadata?

**Answer**:

**Primary Endpoint**: `/devices/queries/devices/v1` (GET)
- **Purpose**: Query devices using FQL filters to find device IDs matching criteria
- **Returns**: List of device IDs (AIDs - Agent IDs) matching the filter
- **Method**: GET

**Secondary Endpoint**: `/devices/combined/devices/v1` (GET)
- **Purpose**: Combined query that returns full device details in a single call
- **Returns**: Complete device information matching the filter
- **Method**: GET

**FQL Filter Syntax for Instance ID**:
```
instance_id:'i-0048f94221fe110cf'
```

**Complete Example Request**:
```bash
curl -X GET \
  "https://api.crowdstrike.com/devices/queries/devices/v1?filter=instance_id:'i-0048f94221fe110cf'" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H "Accept: application/json"
```

**FalconPy SDK Example** (Python):
```python
from falconpy import Hosts

hosts = Hosts(client_id=CLIENT_ID, client_secret=CLIENT_SECRET)

# Query by AWS instance ID
device_ids = hosts.query_devices_by_filter(
    filter="instance_id:'i-0048f94221fe110cf'"
)["body"]["resources"]

# Get detailed device information
device_details = hosts.get_device_details(ids=device_ids)
```

**PowerShell Example**:
```powershell
Get-FalconHost -Filter "instance_id:'i-0048f94221fe110cf'" -Detailed
```

**Source**:
- FalconPy Documentation: https://www.falconpy.io/Service-Collections/Hosts.html
- PSFalcon Wiki: https://github-wiki-see.page/m/CrowdStrike/psfalcon/wiki/Get-FalconHost

---

### 2. CrowdStrike Metadata Field Names

**Question**: What is the exact field name for AWS EC2 Instance ID in CrowdStrike system metadata?

**Answer**:

**Primary Field**: `instance_id`
- **Description**: Unique cloud instance identifier assigned by the cloud provider
- **Format for AWS EC2**: `i-[0-9a-fA-F]{8,17}` (legacy: 8 chars, current: 17 chars)
- **Example**: `i-0048f94221fe110cf`
- **Availability**: Present in device details response when the device is running on AWS EC2

**Related Cloud Provider Fields**:

| Field Name | Description | Example Value | Purpose |
|------------|-------------|---------------|---------|
| `instance_id` | Cloud instance identifier | `i-0048f94221fe110cf` | Primary query field for AWS instances |
| `service_provider` | Cloud platform identifier | `AWS_EC2` | Identifies cloud provider type |
| `service_provider_account_id` | Cloud account identifier | `123456789012` | AWS account ID (12 digits) |

**Example Device Details Response** (JSON excerpt):
```json
{
  "resources": [
    {
      "device_id": "abcde6b9a3427d8c4a1af416424d6231",
      "hostname": "web-server-01",
      "instance_id": "i-0048f94221fe110cf",
      "service_provider": "AWS_EC2",
      "service_provider_account_id": "123456789012",
      "platform_name": "Linux",
      "external_ip": "54.123.45.67",
      "local_ip": "10.0.1.100"
    }
  ]
}
```

**Field Confirmation**: Confirmed via GitHub discussion #58 in CrowdStrike/falconpy repository showing actual API responses with redacted AWS instance IDs in format `i-REDACTED`.

**Note**: The field name is `instance_id`, NOT `aws_instance_id` or `ec2_instance_id`. This is a generic cloud field that works across cloud providers (AWS, Azure, GCP).

**Source**:
- GitHub Discussion: https://github.com/CrowdStrike/falconpy/discussions/58
- FalconPy Hosts API: https://www.falconpy.io/Service-Collections/Hosts.html

---

### 3. Cache Key Strategy for Instance IDs

**Question**: How should cache keys differentiate between hostname and instance ID queries?

**Answer**:

**Recommended Strategy**: Prefix-based cache key differentiation

**Cache Key Format**:
```kotlin
// Hostname query
val cacheKey = "hostname:${hostname.lowercase()}"

// Instance ID query
val cacheKey = "instance_id:${instanceId.lowercase()}"
```

**Rationale**:
1. **Collision Prevention**: Prevents cache collisions if a hostname theoretically matches an instance ID format (e.g., hostname "i-test-server")
2. **Clear Separation**: Explicit type indicator makes cache debugging easier
3. **Case Normalization**: Lowercase normalization ensures `i-ABC123` and `i-abc123` map to the same cache entry
4. **Future-Proof**: Allows adding other query types (e.g., `device_id:`, `ip_address:`) without conflicts

**Implementation Example** (Kotlin):
```kotlin
@Cacheable("crowdstrike-queries", keyGenerator = "queryKeyGenerator")
fun queryVulnerabilities(input: String, config: FalconConfigDto): CrowdStrikeQueryResponse {
    val (queryType, queryValue) = detectQueryType(input)
    val cacheKey = "$queryType:${queryValue.lowercase()}"

    // Query logic...
}

private fun detectQueryType(input: String): Pair<String, String> {
    return if (input.matches(Regex("^i-[0-9a-fA-F]{8,17}$", RegexOption.IGNORE_CASE))) {
        "instance_id" to input
    } else {
        "hostname" to input
    }
}
```

**Cache TTL**: 15 minutes (900 seconds) - consistent with existing hostname query caching

**Alternative Considered**: Hash-based keys (`MD5(input)`) - rejected because it obscures the query type and makes cache inspection/debugging difficult.

---

### 4. Multiple Systems with Same Instance ID

**Question**: What is the expected behavior when CrowdStrike returns multiple systems with the same AWS instance ID?

**Answer**:

**Expected Behavior**: This scenario should be **extremely rare** in practice but technically possible during instance lifecycle transitions (e.g., agent not yet uninstalled on terminated instance, reused instance ID).

**Recommended Strategy**: **Aggregate all vulnerabilities** from all matching devices

**Rationale**:
1. **Data Completeness**: Users searching by instance ID want ALL vulnerabilities associated with that cloud resource
2. **Safety First**: Showing vulnerabilities from all matching devices is safer than arbitrarily selecting one
3. **Transparency**: UI should indicate if multiple devices matched (e.g., "2 systems found with instance ID i-0048f94221fe110cf")

**Implementation Approach**:

```kotlin
fun queryByInstanceId(instanceId: String, config: FalconConfigDto): CrowdStrikeQueryResponse {
    val token = getAuthToken(config)

    // Step 1: Query for all devices with this instance ID
    val deviceIds = queryDevicesByFilter(
        filter = "instance_id:'$instanceId'",
        token = token
    )

    if (deviceIds.isEmpty()) {
        throw NotFoundException("Instance ID not found: $instanceId")
    }

    // Step 2: Get device details to extract hostnames
    val devices = getDeviceDetails(deviceIds, token)

    // Step 3: Query vulnerabilities for each device and aggregate
    val allVulnerabilities = mutableListOf<CrowdStrikeVulnerabilityDto>()
    val hostnames = mutableListOf<String>()

    devices.forEach { device ->
        val hostname = device.hostname ?: instanceId
        hostnames.add(hostname)

        val vulns = querySpotlightApi(device.aid, hostname, token)
        allVulnerabilities.addAll(vulns)
    }

    // Step 4: Return aggregated results
    return CrowdStrikeQueryResponse(
        hostname = hostnames.joinToString(", "), // Or use instance ID if multiple
        instanceId = instanceId,
        deviceCount = devices.size, // NEW FIELD to indicate multiple matches
        vulnerabilities = allVulnerabilities,
        totalCount = allVulnerabilities.size,
        queriedAt = LocalDateTime.now()
    )
}
```

**UI Display Strategy**:
- If 1 device: Display as normal (e.g., "web-server-01")
- If 2+ devices: Display instance ID with count (e.g., "i-0048f94221fe110cf (2 systems)")

**Edge Case Handling**:
- **Null hostname**: Use instance ID as display name
- **Duplicate vulnerabilities**: De-duplicate by CVE + affected product (same vulnerability reported by multiple devices)

---

### 5. Legacy vs Current AWS Instance ID Format

**Question**: Are there validation differences needed for legacy (8-char) vs current (17-char) formats?

**Answer**:

**Validation Regex Pattern**:
```kotlin
val AWS_INSTANCE_ID_PATTERN = Regex("^i-[0-9a-fA-F]{8,17}$", RegexOption.IGNORE_CASE)
```

**Format Details**:

| Format | Pattern | Example | When Used | Notes |
|--------|---------|---------|-----------|-------|
| Legacy | `i-` + 8 hex chars | `i-1a2b3c4d` | Pre-2016 EC2 instances | Still valid, less common |
| Current | `i-` + 17 hex chars | `i-0048f94221fe110cf` | Post-2016 EC2 instances | Standard format today |

**Validation Implementation**:

```kotlin
/**
 * Validate AWS EC2 Instance ID format
 *
 * Supports both legacy (8 chars) and current (17 chars) formats
 * Case-insensitive (A-F and a-f both valid)
 */
fun isValidAwsInstanceId(input: String): Boolean {
    return input.matches(Regex("^i-[0-9a-fA-F]{8,17}$", RegexOption.IGNORE_CASE))
}

/**
 * Examples:
 * - "i-1a2b3c4d" -> true (legacy format)
 * - "i-0048f94221fe110cf" -> true (current format)
 * - "i-ABC" -> false (too short)
 * - "i-0048f94221fe110cf123" -> false (too long)
 * - "i-00G8f94221fe110cf" -> false (G is not hex)
 * - "I-0048f94221fe110cf" -> true (case-insensitive)
 */
```

**Frontend Validation Example** (TypeScript):
```typescript
const AWS_INSTANCE_ID_REGEX = /^i-[0-9a-fA-F]{8,17}$/i;

function isAwsInstanceId(input: string): boolean {
  return AWS_INSTANCE_ID_REGEX.test(input.trim());
}

function validateInput(input: string): string | null {
  const trimmed = input.trim();

  if (isAwsInstanceId(trimmed)) {
    return null; // Valid instance ID
  }

  if (trimmed.startsWith('i-')) {
    return "Invalid instance ID format. Expected: i- followed by 8 or 17 hexadecimal characters (e.g., i-0048f94221fe110cf)";
  }

  // Treat as hostname
  return null;
}
```

**No Special Handling Required**: The API treats both formats identically. The regex range `{8,17}` covers both cases without requiring separate code paths.

**CrowdStrike API Behavior**: CrowdStrike accepts and stores both formats without conversion, so queries must use the exact format stored in the system.

---

## Implementation Workflow

### Complete API Call Sequence

**Scenario**: User enters AWS instance ID `i-0048f94221fe110cf`

**Step 1: Detect Input Type**
```kotlin
val isInstanceId = input.matches(Regex("^i-[0-9a-fA-F]{8,17}$", RegexOption.IGNORE_CASE))
```

**Step 2: Query Devices by Instance ID**
```http
GET /devices/queries/devices/v1?filter=instance_id:'i-0048f94221fe110cf'
Authorization: Bearer {token}
Accept: application/json
```

**Response**:
```json
{
  "resources": ["device_id_1", "device_id_2"],
  "meta": {
    "query_time": 0.123
  }
}
```

**Step 3: Get Device Details** (to retrieve hostname)
```http
POST /devices/entities/devices/v1
Authorization: Bearer {token}
Content-Type: application/json

{
  "ids": ["device_id_1", "device_id_2"]
}
```

**Response**:
```json
{
  "resources": [
    {
      "device_id": "device_id_1",
      "hostname": "web-server-01",
      "instance_id": "i-0048f94221fe110cf",
      "service_provider": "AWS_EC2"
    }
  ]
}
```

**Step 4: Query Vulnerabilities** (Spotlight API)
```http
GET /spotlight/combined/vulnerabilities/v1?filter=aid:'device_id_1'
Authorization: Bearer {token}
Accept: application/json
```

**Response**:
```json
{
  "resources": [
    {
      "id": "vuln_1",
      "cve": {
        "id": "CVE-2024-1234"
      },
      "severity": "CRITICAL",
      "created_timestamp": "2024-01-15T10:30:00Z"
    }
  ]
}
```

**Step 5: Return Aggregated Response**
```kotlin
CrowdStrikeQueryResponse(
    hostname = "web-server-01",
    instanceId = "i-0048f94221fe110cf",
    vulnerabilities = vulnerabilityDtos,
    totalCount = vulnerabilityDtos.size,
    queriedAt = LocalDateTime.now()
)
```

---

## API Endpoints Reference

### Hosts API (Device Queries)

| Endpoint | Method | Purpose | Parameters |
|----------|--------|---------|------------|
| `/devices/queries/devices/v1` | GET | Query device IDs by FQL filter | `filter` (FQL), `limit`, `offset` |
| `/devices/combined/devices/v1` | GET | Query full device details by FQL filter | `filter` (FQL), `limit`, `offset` |
| `/devices/entities/devices/v1` | POST | Get device details by IDs | `ids` (array of device IDs) |

### Spotlight Vulnerabilities API

| Endpoint | Method | Purpose | Parameters |
|----------|--------|---------|------------|
| `/spotlight/combined/vulnerabilities/v1` | GET | Query vulnerabilities by FQL filter | `filter` (FQL), `limit`, `offset` |
| `/spotlight/queries/vulnerabilities/v1` | GET | Query vulnerability IDs only | `filter` (FQL), `limit` |

### OAuth2 Authentication API

| Endpoint | Method | Purpose | Parameters |
|----------|--------|---------|------------|
| `/oauth2/token` | POST | Get access token | `client_id`, `client_secret` |

---

## FQL (Falcon Query Language) Reference

### Syntax Patterns

**Basic Filter**:
```
property:'value'
```

**Wildcard Search**:
```
hostname:'web-*'
```

**Multiple Conditions (AND)**:
```
instance_id:'i-abc123'+platform_name:'Linux'
```

**Multiple Values (OR)**:
```
severity:'CRITICAL,HIGH'
```

**Date Comparison**:
```
last_seen:>'last 7 days'
```

### Available Filter Properties (Hosts API)

Confirmed filterable fields for device queries:

- `instance_id` - Cloud instance identifier (AWS: `i-[0-9a-fA-F]{8,17}`)
- `hostname` - System hostname
- `external_ip` - Public IP address
- `local_ip` - Private IP address
- `platform_name` - Operating system (Windows, Linux, Mac)
- `os_version` - OS version string
- `agent_version` - Falcon sensor version
- `device_id` - Agent ID (AID)
- `first_seen` - First detection timestamp
- `last_seen` - Last seen timestamp
- `service_provider` - Cloud provider (AWS_EC2, AZURE, GCP)
- `service_provider_account_id` - Cloud account ID

**Source**: FalconPy Documentation, CrowdStrike API Reference

---

## Code Examples

### Kotlin Implementation (Backend Service)

```kotlin
package com.secman.crowdstrike.client

import com.secman.crowdstrike.dto.CrowdStrikeQueryResponse
import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.NotFoundException

/**
 * Query vulnerabilities by AWS instance ID
 *
 * Feature: 041-falcon-instance-lookup
 *
 * @param instanceId AWS EC2 instance ID (format: i-[0-9a-fA-F]{8,17})
 * @param config CrowdStrike Falcon configuration
 * @return CrowdStrikeQueryResponse with vulnerabilities
 * @throws NotFoundException if instance ID not found
 */
fun queryVulnerabilitiesByInstanceId(
    instanceId: String,
    config: FalconConfigDto
): CrowdStrikeQueryResponse {
    require(isValidAwsInstanceId(instanceId)) {
        "Invalid AWS instance ID format: $instanceId"
    }

    log.info("Querying CrowdStrike by instance ID: {}", instanceId)

    // Authenticate
    val token = getAuthToken(config)

    // Query devices by instance ID filter
    val deviceIds = queryDeviceIdsByFilter(
        filter = "instance_id:'$instanceId'",
        token = token
    )

    if (deviceIds.isEmpty()) {
        throw NotFoundException("Instance ID not found in CrowdStrike: $instanceId")
    }

    log.info("Found {} device(s) with instance ID: {}", deviceIds.size, instanceId)

    // Get device details to extract hostname
    val devices = getDeviceDetails(deviceIds, token)
    val primaryDevice = devices.first()
    val hostname = primaryDevice.hostname ?: instanceId

    // Query vulnerabilities using device ID (AID)
    val vulnerabilities = querySpotlightApiByDeviceId(
        deviceId = primaryDevice.aid,
        hostname = hostname,
        token = token
    )

    return CrowdStrikeQueryResponse(
        hostname = hostname,
        instanceId = instanceId,
        vulnerabilities = vulnerabilities,
        totalCount = vulnerabilities.size,
        queriedAt = LocalDateTime.now()
    )
}

private fun isValidAwsInstanceId(input: String): Boolean {
    return input.matches(Regex("^i-[0-9a-fA-F]{8,17}$", RegexOption.IGNORE_CASE))
}

private fun queryDeviceIdsByFilter(filter: String, token: AuthToken): List<String> {
    val uri = UriBuilder.of("/devices/queries/devices/v1")
        .queryParam("filter", filter)
        .build()

    val request = HttpRequest.GET<Any>(uri)
        .header("Authorization", "Bearer ${token.accessToken}")

    val response = httpClient.toBlocking().exchange(request, DeviceIdsResponse::class.java)
    return response.body()?.resources ?: emptyList()
}
```

### TypeScript Implementation (Frontend)

```typescript
// Auto-detection and validation
interface QueryInput {
  value: string;
  type: 'hostname' | 'instance_id';
}

function detectQueryType(input: string): QueryInput {
  const trimmed = input.trim();
  const instanceIdPattern = /^i-[0-9a-fA-F]{8,17}$/i;

  if (instanceIdPattern.test(trimmed)) {
    return { value: trimmed, type: 'instance_id' };
  }

  return { value: trimmed, type: 'hostname' };
}

async function queryVulnerabilities(input: string): Promise<VulnerabilityResponse> {
  const { value, type } = detectQueryType(input);

  // Same endpoint for both query types
  const response = await axios.get('/api/crowdstrike/vulnerabilities', {
    params: { hostname: value }, // Backend detects type
    headers: { Authorization: `Bearer ${sessionStorage.getItem('jwt')}` }
  });

  return response.data;
}
```

---

## Testing Recommendations

### Unit Tests

1. **Input Detection Tests**:
   - Valid legacy instance ID (8 chars): `i-1a2b3c4d`
   - Valid current instance ID (17 chars): `i-0048f94221fe110cf`
   - Invalid formats: `i-ABC`, `i-tooshort`, `web-server-01`
   - Case insensitivity: `i-ABC123EF`, `I-abc123ef`

2. **API Client Tests**:
   - Mock CrowdStrike API responses for instance ID queries
   - Test device ID retrieval from instance ID filter
   - Test vulnerability aggregation from multiple devices

### Integration Tests

1. **End-to-End Flow**:
   - Query by instance ID → retrieve device IDs → get vulnerabilities
   - Cache behavior (15-minute TTL)
   - Error handling (404, 429, 500)

2. **Contract Tests**:
   - Verify API request structure for `/devices/queries/devices/v1?filter=instance_id:'...'`
   - Verify response parsing for device details
   - Verify Spotlight API queries by device ID

---

## Security Considerations

### Input Validation

**Frontend**:
- Regex validation before API call
- Sanitize input (trim whitespace)
- Clear error messages for invalid formats

**Backend**:
- Re-validate input (never trust client)
- Use parameterized queries (prevent FQL injection)
- Rate limiting per existing patterns

### Authentication & Authorization

- Preserve existing RBAC: `@Secured("ADMIN", "VULN")`
- No new permission scopes required
- Same JWT authentication flow

### Data Exposure

- AWS instance IDs are **non-sensitive** identifiers (safe to log)
- Redact tokens in logs
- Apply same data filtering as hostname queries

---

## Performance Considerations

### API Call Overhead

**Hostname Query**: 1 API call (direct hostname → device ID lookup)
**Instance ID Query**: 2+ API calls
1. Query devices by instance ID filter → Get device IDs
2. Get device details → Extract hostname
3. Query vulnerabilities by device ID

**Optimization**: Cache device ID ↔ instance ID mappings to reduce lookups

### Cache Strategy

- **Cache Key**: `instance_id:{normalized_value}`
- **TTL**: 15 minutes (consistent with hostname queries)
- **Invalidation**: Time-based only (no manual invalidation)

### Expected Response Times

- **Cached**: <500ms
- **Fresh API call**: 2-4 seconds (3 sequential API calls)
- **Rate limit**: Exponential backoff with retry

---

## Dependencies & Prerequisites

### CrowdStrike Configuration

**Required API Scopes**:
- `hosts:read` - Query devices by filters
- `spotlight-vulnerabilities:read` - Query vulnerability data

**Existing Configuration** (confirmed):
- OAuth2 credentials stored in `FalconConfig` entity
- Authentication service in shared module
- 15-minute access token caching

### CrowdStrike Agent Requirements

**For Instance ID Availability**:
- Falcon sensor installed on AWS EC2 instance
- Agent must detect and report AWS metadata
- Instance metadata service (IMDS) accessible to sensor

**Note**: Instances without Falcon sensor will not appear in queries (same behavior as hostname queries)

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Instance ID not populated in CrowdStrike | Medium | Display clear error message; document prerequisites |
| Multiple devices with same instance ID | Low | Aggregate vulnerabilities; show device count in UI |
| Increased API call count | Medium | 15-minute caching; batch device details requests |
| FQL injection via malformed input | High | Strict regex validation; parameterized queries |
| Cache key collisions | Low | Prefix-based keys (`instance_id:` vs `hostname:`) |

---

## Documentation Sources

1. **FalconPy Service Collection - Hosts**: https://www.falconpy.io/Service-Collections/Hosts.html
2. **FalconPy FQL Documentation**: https://www.falconpy.io/Usage/Falcon-Query-Language.html
3. **PSFalcon Get-FalconHost**: https://github-wiki-see.page/m/CrowdStrike/psfalcon/wiki/Get-FalconHost
4. **GitHub Discussion #58**: https://github.com/CrowdStrike/falconpy/discussions/58 (instance_id field examples)
5. **Spotlight Vulnerabilities API**: https://www.falconpy.io/Service-Collections/Spotlight-Vulnerabilities.html
6. **CrowdStrike Falcon Discover for AWS**: AWS Implementation Guide (d1.awsstatic.com)

---

## Conclusion

All research questions have been successfully answered with concrete implementation guidance. The CrowdStrike Falcon API provides robust support for querying systems by AWS instance ID via the `instance_id` metadata field with standard FQL filter syntax.

**Key Takeaways**:
1. Use `/devices/queries/devices/v1?filter=instance_id:'i-XXXXXXXX'` to find device IDs
2. Field name is `instance_id` (not `aws_instance_id`)
3. Supports both legacy (8 chars) and current (17 chars) AWS formats
4. Validation regex: `^i-[0-9a-fA-F]{8,17}$` (case-insensitive)
5. Cache keys should use prefix: `instance_id:{value}` vs `hostname:{value}`
6. Aggregate vulnerabilities if multiple devices match same instance ID

**Next Phase**: Proceed to Phase 1 (Design & Contracts) - create data-model.md, API contracts, and quickstart.md based on these research findings.

---

**Research Status**: ✅ COMPLETE
**Ready for Phase 1**: YES
**Constitutional Gates**: All passed (no schema changes, backward compatible, API-first)
