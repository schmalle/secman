# API Contract: Get Asset Vulnerabilities

**Feature**: 034-outdated-assets
**Endpoint**: `GET /api/outdated-assets/{assetId}/vulnerabilities`
**User Story**: User Story 2 - View Asset Details and Vulnerabilities (P1)

## Request

### HTTP Method
`GET`

### URL
`/api/outdated-assets/{assetId}/vulnerabilities`

### Authentication
**Required**: Yes
- JWT token in Authorization header
- Roles: `ADMIN` or `VULN`

### Path Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `assetId` | `Long` | Yes | Asset identifier |

### Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | `Integer` | No | `0` | Page number (zero-indexed) |
| `size` | `Integer` | No | `50` | Page size (max 100) |
| `onlyOverdue` | `Boolean` | No | `true` | If true, show only overdue vulnerabilities |

### Example Requests

```http
GET /api/outdated-assets/1234/vulnerabilities HTTP/1.1
Host: example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

```http
GET /api/outdated-assets/1234/vulnerabilities?onlyOverdue=false&page=0&size=50 HTTP/1.1
Host: example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## Response

### Success Response (200 OK)

**Structure**: Paginated list of vulnerabilities for the asset

```json
{
  "assetId": 1234,
  "assetName": "server-prod-01.example.com",
  "overdueThreshold": 30,
  "vulnerabilities": {
    "content": [
      {
        "id": 5678,
        "vulnerabilityId": "CVE-2023-1234",
        "severity": "CRITICAL",
        "vulnerableProductVersions": "OpenSSL 1.1.1k",
        "daysOpen": "180 days",
        "scanTimestamp": "2025-04-29T10:00:00",
        "isOverdue": true,
        "daysOverdue": 150
      },
      {
        "id": 5679,
        "vulnerabilityId": "CVE-2023-5678",
        "severity": "HIGH",
        "vulnerableProductVersions": "Apache 2.4.41",
        "daysOpen": "120 days",
        "scanTimestamp": "2025-06-28T10:00:00",
        "isOverdue": true,
        "daysOverdue": 90
      },
      {
        "id": 5680,
        "vulnerabilityId": "CVE-2024-9999",
        "severity": "MEDIUM",
        "vulnerableProductVersions": "nginx 1.18.0",
        "daysOpen": "45 days",
        "scanTimestamp": "2025-09-11T10:00:00",
        "isOverdue": true,
        "daysOverdue": 15
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 50
    },
    "totalElements": 18,
    "totalPages": 1,
    "last": true,
    "size": 50,
    "number": 0,
    "first": true,
    "empty": false
  }
}
```

**Response Fields**:
| Field | Type | Description |
|-------|------|-------------|
| `assetId` | `Long` | Asset identifier |
| `assetName` | `String` | Asset name |
| `overdueThreshold` | `Integer` | Configured threshold in days (from VulnerabilityConfig) |
| `vulnerabilities` | `Page<VulnerabilityDto>` | Paginated vulnerability list |
| `vulnerabilities.content[].id` | `Long` | Vulnerability record ID |
| `vulnerabilities.content[].vulnerabilityId` | `String` | CVE ID |
| `vulnerabilities.content[].severity` | `String` | CRITICAL, HIGH, MEDIUM, LOW |
| `vulnerabilities.content[].vulnerableProductVersions` | `String` | Affected product/version |
| `vulnerabilities.content[].daysOpen` | `String` | Human-readable age (e.g., "180 days") |
| `vulnerabilities.content[].scanTimestamp` | `ISO 8601 DateTime` | When vulnerability was scanned |
| `vulnerabilities.content[].isOverdue` | `Boolean` | True if age > threshold |
| `vulnerabilities.content[].daysOverdue` | `Integer` | Days beyond threshold (0 if not overdue) |

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

**Cause**: User does not have access to this asset (workgroup restriction)

```json
{
  "message": "Forbidden: You do not have access to this asset"
}
```

### 404 Not Found

**Cause**: Asset ID does not exist

```json
{
  "message": "Asset not found",
  "assetId": 9999
}
```

---

## Business Rules

1. **Access Control**:
   - ADMIN users can view vulnerabilities for any asset
   - VULN users can only view vulnerabilities for assets in their workgroups
   - Return 403 Forbidden if user doesn't have access to asset

2. **Overdue Calculation**:
   - `daysOpen = today - scanTimestamp (in days)`
   - `isOverdue = daysOpen > overdueThreshold`
   - `daysOverdue = max(0, daysOpen - overdueThreshold)`
   - Threshold comes from VulnerabilityConfig.reminderOneDays

3. **Filtering**:
   - If `onlyOverdue=true` (default): Only return vulnerabilities where `isOverdue=true`
   - If `onlyOverdue=false`: Return all vulnerabilities for the asset

4. **Sorting**:
   - Always sort by `daysOpen DESC` (oldest first)
   - Ensures most critical overdue vulnerabilities appear first

5. **Pagination**:
   - Default page size: 50
   - For assets with 100+ vulnerabilities, pagination prevents UI performance issues

---

## Acceptance Criteria

**From Spec**:
- ✅ User Story 2, Scenario 4: Click on asset shows overdue vulnerability details
- ✅ Edge Case: Assets with 100+ overdue vulnerabilities show paginated details

---

## Implementation Notes

**Controller**:
```kotlin
@Get("/{assetId}/vulnerabilities")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun getAssetVulnerabilities(
    @PathVariable assetId: Long,
    @QueryValue page: Int = 0,
    @QueryValue size: Int = 50,
    @QueryValue onlyOverdue: Boolean = true,
    authentication: Authentication
): HttpResponse<AssetVulnerabilitiesDto>
```

**Service**:
```kotlin
fun getAssetVulnerabilities(
    assetId: Long,
    userRoles: Collection<String>,
    userWorkgroups: List<Long>,
    onlyOverdue: Boolean,
    pageable: Pageable
): AssetVulnerabilitiesDto {
    // Check access
    val asset = assetRepository.findById(assetId)
        .orElseThrow { NotFoundException("Asset not found") }

    if (!hasAccessToAsset(asset, userRoles, userWorkgroups)) {
        throw ForbiddenException("You do not have access to this asset")
    }

    // Get threshold
    val threshold = vulnerabilityConfigService.getReminderOneDays()

    // Query vulnerabilities
    val vulnerabilities = if (onlyOverdue) {
        vulnerabilityRepository.findOverdueByAssetId(assetId, threshold, pageable)
    } else {
        vulnerabilityRepository.findByAssetId(assetId, pageable)
    }

    return AssetVulnerabilitiesDto(asset, vulnerabilities, threshold)
}
```

**Repository Query**:
```kotlin
@Query("""
    SELECT v FROM Vulnerability v
    WHERE v.asset.id = :assetId
    AND FUNCTION('DATEDIFF', CURRENT_DATE, v.scanTimestamp) > :threshold
    ORDER BY v.scanTimestamp ASC
""")
fun findOverdueByAssetId(
    assetId: Long,
    threshold: Int,
    pageable: Pageable
): Page<Vulnerability>
```

**Test Cases**:
1. Admin can view any asset's vulnerabilities
2. VULN user can view vulnerabilities for workgroup assets
3. VULN user gets 403 for non-workgroup assets
4. onlyOverdue=true filters correctly
5. onlyOverdue=false returns all vulnerabilities
6. Pagination works correctly
7. Sorting by daysOpen DESC works
8. 404 for non-existent asset
