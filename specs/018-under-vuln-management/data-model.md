# Data Model: Account Vulns

**Feature**: Account Vulns - AWS Account-Based Vulnerability Overview
**Phase**: 1 (Design & Contracts)
**Date**: 2025-10-14

## Overview

This feature uses **existing database entities** (no schema changes) and introduces **new DTOs** for API responses. The data model focuses on read-only aggregation of existing data.

## Existing Entities (No Changes)

### UserMapping
**Table**: `user_mapping`
**Purpose**: Maps user emails to AWS account IDs (Feature 013)

**Fields Used**:
- `email: String` - User's email address (FK to users, indexed)
- `awsAccountId: String?` - 12-digit AWS account ID (nullable, indexed)

**Query**: `SELECT DISTINCT awsAccountId FROM user_mapping WHERE email = :userEmail AND awsAccountId IS NOT NULL`

**Relationship**: Used to determine which AWS accounts the authenticated user can view.

---

### Asset
**Table**: `assets`
**Purpose**: Represents infrastructure systems/hosts (Feature 003)

**Fields Used**:
- `id: Long` - Primary key
- `name: String` - Asset name (displayed in UI)
- `type: String` - Asset type (e.g., SERVER, WORKSTATION)
- `cloudAccountId: String?` - AWS account ID (nullable, indexed)
- `vulnerabilities: List<Vulnerability>` - Relationship to vulnerabilities (OneToMany)

**Query**: `SELECT a FROM Asset a WHERE a.cloudAccountId IN (:accountIds) ORDER BY SIZE(a.vulnerabilities) DESC`

**Filtering**: Assets WITHOUT cloudAccountId (null) are excluded (FR-014).

---

### Vulnerability
**Table**: `vulnerabilities`
**Purpose**: Represents security vulnerabilities linked to assets (Feature 003)

**Fields Used**:
- `id: Long` - Primary key
- `asset: Asset` - FK to assets table (ManyToOne, indexed)
- `vulnerabilityId: String` - CVE ID (not exposed in this view, only counted)

**Aggregation Query**:
```sql
SELECT a.id, COUNT(v.id)
FROM Asset a
LEFT JOIN a.vulnerabilities v
WHERE a.cloudAccountId IN (:accountIds)
GROUP BY a.id
```

**Note**: LEFT JOIN ensures assets with 0 vulnerabilities appear with count = 0.

---

### User
**Table**: `users`
**Purpose**: Authenticated users with roles (Feature 001)

**Fields Used**:
- `email: String` - User's email (unique, used for user_mapping lookup)
- `roles: Set<Role>` - ENUM set (USER, ADMIN, VULN, RELEASE_MANAGER)

**Admin Check**: `authentication.roles.contains("ADMIN")` → Trigger 403 response

---

## New DTOs (API Responses)

### AccountVulnsSummaryDto
**Purpose**: Top-level response DTO containing all account groups

```kotlin
data class AccountVulnsSummaryDto(
    val accountGroups: List<AccountGroupDto>,
    val totalAssets: Int,
    val totalVulnerabilities: Int
)
```

**Fields**:
- `accountGroups`: List of AWS account groups with their assets (sorted by account ID ascending)
- `totalAssets`: Total number of assets across all accounts (for summary display)
- `totalVulnerabilities`: Total number of vulnerabilities across all assets (for summary display)

**Serialization**: Micronaut JSON (Jackson) - automatic serialization to JSON response

---

### AccountGroupDto
**Purpose**: Represents a single AWS account group with its assets

```kotlin
data class AccountGroupDto(
    val awsAccountId: String,
    val assets: List<AssetVulnCountDto>,
    val totalAssets: Int,
    val totalVulnerabilities: Int
)
```

**Fields**:
- `awsAccountId`: 12-digit AWS account ID (e.g., "123456789012")
- `assets`: List of assets in this account (sorted by vulnerability count descending)
- `totalAssets`: Number of assets in this account (for group summary header)
- `totalVulnerabilities`: Total vulnerabilities in this account (for group summary header)

**Sort Order**: Account groups sorted by `awsAccountId` (numerical/ascending) per clarification Q3.

**Example**:
```json
{
  "awsAccountId": "123456789012",
  "assets": [ /* asset list */ ],
  "totalAssets": 12,
  "totalVulnerabilities": 47
}
```

---

### AssetVulnCountDto
**Purpose**: Represents a single asset with its vulnerability count

```kotlin
data class AssetVulnCountDto(
    val id: Long,
    val name: String,
    val type: String,
    val vulnerabilityCount: Int
)
```

**Fields**:
- `id`: Asset ID (used for navigation to asset detail page)
- `name`: Asset name (displayed in table, clickable link)
- `type`: Asset type (displayed in table)
- `vulnerabilityCount`: Number of vulnerabilities for this asset (0 if none)

**Sort Order**: Assets within each account group sorted by `vulnerabilityCount` descending (highest risk first) per FR-012.

**Example**:
```json
{
  "id": 42,
  "name": "web-server-01",
  "type": "SERVER",
  "vulnerabilityCount": 25
}
```

---

## Data Flow

### 1. User Authenticates
- JWT token validated by Micronaut Security
- User email and roles extracted from `Authentication` principal

### 2. Admin Check
- If `authentication.roles.contains("ADMIN")` → Return 403 (Forbidden)
- Otherwise, proceed to step 3

### 3. Retrieve AWS Account Mappings
- Query: `userMappingRepository.findByEmail(userEmail)`
- Filter: Select only records where `awsAccountId IS NOT NULL`
- Extract: List of distinct AWS account IDs

### 4. No Mappings Check
- If list is empty → Return 404 (Not Found) with error message
- Otherwise, proceed to step 5

### 5. Retrieve Assets
- Query: `assetRepository.findByCloudAccountIdIn(accountIds)`
- Filter: Exclude assets with null/empty cloudAccountId (WHERE clause)
- Result: List of Asset entities

### 6. Count Vulnerabilities
- Query: `SELECT a.id, COUNT(v.id) FROM Asset a LEFT JOIN a.vulnerabilities v WHERE a.id IN (:assetIds) GROUP BY a.id`
- Result: Map<Long, Long> (assetId → vulnerabilityCount)

### 7. Group by AWS Account
- Group assets by `asset.cloudAccountId`
- For each group:
  - Sort assets by vulnerability count (descending)
  - Calculate group totals (asset count, vulnerability sum)
  - Create `AccountGroupDto`

### 8. Sort Account Groups
- Sort groups by `awsAccountId` (numerical/ascending)

### 9. Build Response
- Create `AccountVulnsSummaryDto` with:
  - Sorted account groups
  - Total assets across all groups
  - Total vulnerabilities across all groups
- Return 200 (OK) with JSON response

---

## Pagination Data Model (Frontend State)

**Note**: Pagination is client-side (per clarification Q2 + research decision #3).

### React State Structure

```typescript
interface PaginationState {
  [accountId: string]: {
    currentPage: number;  // 0-indexed
    pageSize: number;     // Fixed at 20 per FR-008b
  };
}
```

**Example**:
```typescript
{
  "123456789012": { currentPage: 0, pageSize: 20 },  // Show first 20
  "987654321098": { currentPage: 1, pageSize: 20 }   // Show 21-40 (Load More clicked)
}
```

**Logic**:
- Initial state: All accounts at page 0
- "Load More" click: Increment `currentPage` for that account
- Display slice: `assets.slice(0, (currentPage + 1) * pageSize)`

---

## Error Response Models

### No Mapping Error (404)
```json
{
  "message": "No AWS accounts are mapped to your user account. Please contact your administrator.",
  "status": 404
}
```

### Admin Redirect Error (403)
```json
{
  "message": "Please use System Vulns view",
  "redirectUrl": "/system-vulns",
  "status": 403
}
```

### Unauthorized Error (401)
```json
{
  "message": "Authentication required",
  "status": 401
}
```

---

## Validation Rules

### Input Validation
- **User Email**: Validated by JWT authentication (trusted after auth)
- **AWS Account IDs**: Retrieved from database (12-digit string constraint enforced by UserMapping entity)
- **No user-supplied filters**: Endpoint takes no query parameters (no validation needed)

### Output Validation
- **Non-null checks**: All DTO fields are non-nullable (Kotlin compiler enforces)
- **Empty collections**: Empty lists returned as `[]` (valid JSON, handled by frontend)
- **Zero counts**: Assets with 0 vulnerabilities have `vulnerabilityCount: 0` (explicit value)

---

## Performance Characteristics

### Query Complexity
- **User mapping lookup**: O(1) - indexed email, returns ~1-50 rows
- **Asset filtering**: O(n) where n = number of assets in user's AWS accounts (~1-500 per spec)
- **Vulnerability counting**: O(m) where m = number of vulnerabilities (~1-10 per asset)
- **Sorting/grouping**: O(n log n) - in-memory sort of assets by account and vuln count

### Estimated Response Size
- **Single account, 100 assets**: ~20 KB JSON
- **50 accounts, 500 assets**: ~100 KB JSON
- **Compression**: gzip reduces to ~30 KB over network

### Caching Strategy (Future Optimization)
- **Not implemented in MVP**: Data changes frequently (new vulns imported daily)
- **Future**: Add ETag/Last-Modified headers for browser caching (30s stale acceptable)
- **Future**: Redis cache for vulnerability counts (invalidate on import)

---

## Security Considerations

### Data Exposure
- **Only aggregated counts**: Individual vulnerability details (CVE IDs, severity) NOT exposed in this view
- **AWS account filtering**: Users can ONLY see assets in their mapped accounts (no bypass via query manipulation)
- **No PII**: Asset names/types are operational data, not personally identifiable

### Access Control
- **Authentication required**: JWT token validated before any data access
- **Admin exclusion**: Admins cannot access this view (enforce use of System Vulns)
- **No workgroup filtering**: Per clarification Q1, AWS account mapping overrides workgroup restrictions

### SQL Injection Prevention
- **Parameterized queries**: All queries use JPA/JPQL with named parameters (`:userEmail`, `:accountIds`)
- **No string concatenation**: Micronaut Data prevents SQL injection by design

---

## Future Extensions (Out of Scope for MVP)

1. **Filter by severity**: Add query parameter `?severity=CRITICAL` to filter vulnerability counts
2. **Export to CSV**: Add `/api/account-vulns/export` endpoint for Excel download
3. **Real-time updates**: WebSocket push for new vulnerabilities (requires socket infrastructure)
4. **Asset search**: Add search bar to filter assets by name within account groups
5. **Sort options**: Allow user to change sort order (by name, type, IP in addition to vuln count)

---

## Next Steps

Proceed to API contract generation (`contracts/account-vulns-api.yaml`).
