# API Contract: Get Outdated Assets

**Feature**: 034-outdated-assets
**Endpoint**: `GET /api/outdated-assets`
**User Story**: User Story 1 - View Outdated Assets (P1)

## Request

### HTTP Method
`GET`

### URL
`/api/outdated-assets`

### Authentication
**Required**: Yes
- JWT token in Authorization header
- Roles: `ADMIN` or `VULN`

### Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | `Integer` | No | `0` | Page number (zero-indexed) |
| `size` | `Integer` | No | `20` | Page size (max 100) |
| `sort` | `String` | No | `oldestVulnDays,desc` | Sort field and direction |
| `workgroupId` | `Long` | No | `null` | Filter by workgroup (ADMIN sees all if null) |
| `searchTerm` | `String` | No | `null` | Search asset name (case-insensitive partial match) |
| `minSeverity` | `String` | No | `null` | Minimum severity: CRITICAL, HIGH, MEDIUM, LOW |

**Sort Options**:
- `oldestVulnDays,desc` (default)
- `oldestVulnDays,asc`
- `assetName,asc`
- `assetName,desc`
- `totalOverdueCount,desc`
- `totalOverdueCount,asc`

### Example Requests

```http
GET /api/outdated-assets?page=0&size=20&sort=oldestVulnDays,desc HTTP/1.1
Host: example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

```http
GET /api/outdated-assets?searchTerm=server-prod&minSeverity=HIGH&workgroupId=5 HTTP/1.1
Host: example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## Response

### Success Response (200 OK)

**Structure**: Paginated response with outdated assets

```json
{
  "content": [
    {
      "id": 142,
      "assetId": 1234,
      "assetName": "server-prod-01.example.com",
      "assetType": "SERVER",
      "totalOverdueCount": 18,
      "criticalCount": 5,
      "highCount": 10,
      "mediumCount": 3,
      "lowCount": 0,
      "oldestVulnDays": 180,
      "oldestVulnId": "CVE-2023-1234",
      "lastCalculatedAt": "2025-10-26T14:30:00"
    },
    {
      "id": 143,
      "assetId": 1235,
      "assetName": "server-prod-02.example.com",
      "assetType": "SERVER",
      "totalOverdueCount": 7,
      "criticalCount": 2,
      "highCount": 3,
      "mediumCount": 2,
      "lowCount": 0,
      "oldestVulnDays": 120,
      "oldestVulnId": "CVE-2023-5678",
      "lastCalculatedAt": "2025-10-26T14:30:00"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "sorted": true,
      "unsorted": false,
      "empty": false
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 234,
  "totalPages": 12,
  "last": false,
  "size": 20,
  "number": 0,
  "sort": {
    "sorted": true,
    "unsorted": false,
    "empty": false
  },
  "numberOfElements": 20,
  "first": true,
  "empty": false
}
```

### Success Response - No Outdated Assets (200 OK)

```json
{
  "content": [],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "sorted": false,
      "unsorted": true,
      "empty": true
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 0,
  "totalPages": 0,
  "last": true,
  "size": 20,
  "number": 0,
  "sort": {
    "sorted": false,
    "unsorted": true,
    "empty": true
  },
  "numberOfElements": 0,
  "first": true,
  "empty": true
}
```

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
  "message": "Forbidden: You need ADMIN or VULN role to view outdated assets"
}
```

### 400 Bad Request

**Cause**: Invalid query parameters

```json
{
  "message": "Invalid parameter: size must be between 1 and 100",
  "violations": [
    {
      "field": "size",
      "message": "must be between 1 and 100"
    }
  ]
}
```

---

## Business Rules

1. **Access Control**:
   - ADMIN users see all outdated assets
   - VULN users see only assets from their assigned workgroups
   - If `workgroupId` parameter provided, filter by that workgroup (VULN users can only see their own workgroups)

2. **Filtering**:
   - `searchTerm`: Case-insensitive partial match on `assetName`
   - `minSeverity=CRITICAL`: Show only assets with at least 1 Critical overdue vulnerability
   - `minSeverity=HIGH`: Show assets with Critical OR High overdue vulnerabilities
   - `minSeverity=MEDIUM`: Show assets with Critical, High, OR Medium overdue vulnerabilities
   - `minSeverity=LOW`: Show all outdated assets (equivalent to no filter)

3. **Performance**:
   - Query must complete in <2 seconds for 10,000 assets
   - Use database indexes on workgroup_ids, oldest_vuln_days, asset_name

4. **Staleness**:
   - Data is from materialized view (may be stale)
   - `lastCalculatedAt` timestamp indicates freshness
   - Frontend should display age (e.g., "Last updated 5 minutes ago")

---

## Acceptance Criteria

**From Spec**:
- ✅ User Story 1, Scenario 2: Page loads and displays results
- ✅ User Story 1, Scenario 5: Page loads in under 2 seconds for 100 assets
- ✅ User Story 4, Scenario 1: Search by asset name works
- ✅ User Story 4, Scenario 2: Filter by severity works
- ✅ User Story 4, Scenario 3: Sort by column works
- ✅ User Story 4, Scenario 4: Filter/search updates in under 1 second
- ✅ User Story 5, Scenario 1: VULN users see only their workgroup assets
- ✅ User Story 5, Scenario 2: ADMIN users see all assets

---

## Implementation Notes

**Controller**:
```kotlin
@Get
@Secured(SecurityRule.IS_AUTHENTICATED)
fun getOutdatedAssets(
    @QueryValue page: Int = 0,
    @QueryValue size: Int = 20,
    @QueryValue sort: String = "oldestVulnDays,desc",
    @QueryValue workgroupId: Long? = null,
    @QueryValue searchTerm: String? = null,
    @QueryValue minSeverity: String? = null,
    authentication: Authentication
): Page<OutdatedAssetDto>
```

**Service**:
```kotlin
fun getOutdatedAssets(
    userRoles: Collection<String>,
    userWorkgroups: List<Long>,
    workgroupId: Long?,
    searchTerm: String?,
    minSeverity: String?,
    pageable: Pageable
): Page<OutdatedAssetDto>
```

**Test Cases**:
1. Admin user gets all outdated assets
2. VULN user gets only workgroup assets
3. Search filtering works correctly
4. Severity filtering works correctly
5. Pagination and sorting work correctly
6. Performance test: <2s for 10,000 assets
