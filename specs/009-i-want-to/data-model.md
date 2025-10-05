# Data Model: Enhanced MCP Tools for Security Data Access

**Feature**: 009-i-want-to
**Date**: 2025-10-05
**Status**: Design Complete

## Overview
This feature extends existing domain entities with API key authentication and exposes complete asset, scan, and vulnerability data through MCP tools. No changes to existing entities, only new entities and read-only access patterns.

---

## New Entities

### McpApiKey

**Purpose**: Authentication credentials for MCP tool access with permission scoping

**Fields**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | PK, auto-increment | Unique identifier |
| keyHash | String | NOT NULL, unique, length=60 | BCrypt hash of API key (never store plaintext) |
| userId | Long | FK → User, NOT NULL | Owner of this API key |
| name | String | NOT NULL, length 1-100 | Human-readable key name (e.g., "Production AI Assistant") |
| permissions | Set<String> | ElementCollection, NOT NULL | Permission scopes: ASSETS_READ, SCANS_READ, VULNERABILITIES_READ |
| rateLimit Tier | String | Enum, NOT NULL | Rate limit tier: STANDARD(5000/min), HIGH(10000/min), UNLIMITED |
| active | Boolean | NOT NULL, default=true | Whether key is currently active |
| expiresAt | Instant | Nullable | Expiration timestamp (null = never expires) |
| lastUsedAt | Instant | Nullable | Timestamp of last successful use |
| createdAt | Instant | NOT NULL | Creation timestamp |
| updatedAt | Instant | NOT NULL | Last update timestamp |

**Relationships**:
- ManyToOne → User (owner of the key)
- User relationship provides: user roles (ADMIN, USER, VULN), workgroup memberships

**Validation Rules**:
- Key hash must be BCrypt format (60 chars)
- Name: alphanumeric + spaces + hyphens, unique per user
- Permissions: at least one required, only valid values allowed
- Rate limit tier: STANDARD | HIGH | UNLIMITED
- Active keys with past expiration dates are automatically deactivated

**Indexes**:
- `idx_apikey_hash` on `keyHash` (unique, for fast lookup)
- `idx_apikey_user` on `userId` (for user key management)
- `idx_apikey_active` on `active` (for filtering active keys)

**Security Notes**:
- API keys generated with `SecureRandom` (256-bit entropy)
- Only hash stored in database (BCrypt cost factor 12)
- Original key shown ONCE at creation, never retrievable
- Users can rotate keys (create new, revoke old)

---

## Existing Entities (Read-Only Access)

These entities are NOT modified, only queried by new MCP tools:

### Asset (from Feature 002, 003, 008)

**Fields Exposed via MCP**:
```kotlin
data class AssetResponse(
    val id: Long,
    val name: String,
    val type: String,
    val ip: String?,
    val owner: String?,
    val description: String?,
    val groups: List<String>,             // Comma-separated string split to list
    val cloudAccountId: String?,
    val cloudInstanceId: String?,
    val adDomain: String?,
    val osVersion: String?,
    val lastSeen: Instant?,
    val workgroups: List<WorkgroupSummary>,  // From ManyToMany relationship
    val manualCreator: UserSummary?,         // From FK relationship
    val scanUploader: UserSummary?,          // From FK relationship
    val createdAt: Instant,
    val updatedAt: Instant,

    // Optional: nested data for complete profile queries
    val vulnerabilities: List<VulnerabilityResponse>?,  // From OneToMany
    val scanResults: List<ScanResultResponse>?          // From OneToMany
)
```

**Access Control**:
- Users see assets from their workgroups + assets they created/uploaded
- Applied via `AssetFilterService` (Feature 008)
- Unauthorized access returns `INSUFFICIENT_PERMISSIONS` error

### ScanResult (from Feature 002)

**Fields Exposed via MCP**:
```kotlin
data class ScanResultResponse(
    val id: Long,
    val assetId: Long,
    val assetName: String,           // Denormalized for convenience
    val port: Int,
    val service: String?,
    val product: String?,
    val version: String?,
    val discoveredAt: Instant,
    val scanType: String             // "nmap" | "masscan"
)
```

**Access Control**:
- Inherits from parent Asset access control
- If user can't see asset, they can't see its scan results

### Vulnerability (from Feature 003)

**Fields Exposed via MCP**:
```kotlin
data class VulnerabilityResponse(
    val id: Long,
    val assetId: Long,
    val assetName: String,            // Denormalized for convenience
    val vulnerabilityId: String,      // CVE-YYYY-NNNNN format
    val cvssSeverity: String,         // CRITICAL | HIGH | MEDIUM | LOW
    val vulnerableProductVersions: String?,
    val daysOpen: Int,
    val scanTimestamp: Instant,
    val isExcepted: Boolean,          // From VulnerabilityException check
    val exceptionReason: String?,     // If excepted, why?
    val createdAt: Instant
)
```

**Access Control**:
- Inherits from parent Asset access control
- Filter by exception status: include, exclude, or only excepted vulns

### Workgroup (from Feature 008)

**Fields Exposed via MCP** (summary only):
```kotlin
data class WorkgroupSummary(
    val id: Long,
    val name: String,
    val description: String?
)
```

**Access Control**:
- Users only see workgroups they belong to
- No direct workgroup querying (only as nested data in Asset responses)

### User (existing)

**Fields Exposed via MCP** (summary only):
```kotlin
data class UserSummary(
    val id: Long,
    val username: String,
    val email: String
)
```

**Access Control**:
- No direct user querying
- Only as nested data (asset creator/uploader)

---

## New Service-Layer DTOs

### McpAuthContext

**Purpose**: Carries authentication/authorization context through MCP tool execution

```kotlin
data class McpAuthContext(
    val apiKey: McpApiKey,
    val user: User,
    val permissions: Set<String>,
    val workgroupIds: Set<Long>,
    val rateLimitTier: String
)
```

### McpPaginationParams

**Purpose**: Standardized pagination parameters with validation

```kotlin
data class McpPaginationParams(
    val page: Int = 0,              // 0-indexed
    val pageSize: Int = 100,        // Default 100, max 1000
    val totalLimit: Int = 100000    // Max total results across all pages
) {
    init {
        require(page >= 0) { "Page must be 0 or greater" }
        require(pageSize in 1..1000) { "Page size must be 1-1000" }
        require(totalLimit in 1..100000) { "Total limit must be 1-100,000" }
    }
}
```

### McpPaginatedResponse<T>

**Purpose**: Standardized paginated response format

```kotlin
data class McpPaginatedResponse<T>(
    val items: List<T>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int,
    val hasMore: Boolean
)
```

---

## State Transitions

### McpApiKey Lifecycle

```
[CREATED] → active=true, expiresAt=null
    ↓
[ACTIVE] → lastUsedAt updated on each use
    ↓
[EXPIRED] → active=false (auto-deactivated when expiresAt < now)
    ↓
[REVOKED] → active=false (manual user action)
```

**Transitions**:
1. **Creation**: Admin or user generates new key → stored with BCrypt hash
2. **Usage**: Each MCP tool call updates `lastUsedAt` timestamp
3. **Expiration**: Background job checks `expiresAt`, sets `active=false`
4. **Revocation**: User/admin sets `active=false` immediately

**Business Rules**:
- Expired keys cannot be reactivated (must create new key)
- Inactive keys immediately fail authentication
- Users can have multiple active keys (for different AI assistants)
- Admin users can revoke any key, regular users only their own

---

## Relationships Summary

```
User (existing)
  └─┬→ McpApiKey (1:N) - user owns API keys
    └─→ WorkgroupMembership (M:N) - user belongs to workgroups

Asset (existing)
  ├─→ ScanResult (1:N) - asset has scan results
  ├─→ Vulnerability (1:N) - asset has vulnerabilities
  ├─→ Workgroup (M:N) - asset belongs to workgroups
  ├─→ User (manualCreator, FK) - asset created by user
  └─→ User (scanUploader, FK) - asset uploaded via scan

Vulnerability (existing)
  └─→ VulnerabilityException (evaluated at query time)
```

---

## Validation Rules

### Input Validation (MCP Tool Parameters)

| Parameter | Type | Validation |
|-----------|------|------------|
| page | Int | ≥0 |
| pageSize | Int | 1-1000 |
| assetId | Long | Must exist, user must have access |
| cveId | String | Optional, format CVE-YYYY-NNNNN |
| severity | String | Enum: CRITICAL, HIGH, MEDIUM, LOW |
| dateRange.start | Instant | Optional, must be ≤ dateRange.end |
| dateRange.end | Instant | Optional, must be ≥ dateRange.start |
| includeExcepted | Boolean | Default false |
| filterBy.* | String | Max length 255, SQL injection sanitized |

### Response Validation

- All timestamps serialized as ISO 8601 strings
- Null values explicitly included (for optional fields)
- Total count accurate before pagination applied
- Metadata includes: query execution time, total results warning if >50K

---

## Performance Constraints

### Query Optimization

| Entity | Index Used | Query Pattern |
|--------|-----------|---------------|
| McpApiKey | idx_apikey_hash | O(1) lookup by hash |
| Asset | idx_asset_name, idx_asset_type | O(log n) filtered queries |
| ScanResult | idx_scanresult_asset_timestamp | O(log n) per asset |
| Vulnerability | idx_vulnerability_asset_timestamp | O(log n) per asset |

### Result Set Limits

- Single query: max 1000 items per page
- Total results: max 100,000 items (across all pages)
- Nested data: max 100 vulnerabilities per asset, max 100 scan results per asset
- Timeout: queries exceeding 10 seconds return timeout error

---

## Error Scenarios

| Scenario | Error Code | HTTP Status | Description |
|----------|-----------|-------------|-------------|
| Invalid API key | INVALID_API_KEY | 401 | Key not found or inactive |
| Missing permission | INSUFFICIENT_PERMISSIONS | 403 | Key lacks required permission scope |
| Rate limit exceeded | RATE_LIMIT_EXCEEDED | 429 | Too many requests per minute/hour |
| Invalid pagination | INVALID_PAGINATION | 400 | Page/pageSize out of bounds |
| Total limit exceeded | TOTAL_RESULTS_EXCEEDED | 400 | Query would return >100K results |
| Asset not found | ASSET_NOT_FOUND | 404 | Asset ID doesn't exist |
| No access to asset | INSUFFICIENT_PERMISSIONS | 403 | Asset in different workgroup |
| Query timeout | QUERY_TIMEOUT | 504 | Database query exceeded 10s |

---

## Schema Migration (Hibernate Auto-Migration)

### New Tables

```sql
CREATE TABLE mcp_api_key (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_hash VARCHAR(60) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    rate_limit_tier VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMP NULL,
    last_used_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    INDEX idx_apikey_hash (key_hash),
    INDEX idx_apikey_user (user_id),
    INDEX idx_apikey_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE mcp_api_key_permissions (
    mcp_api_key_id BIGINT NOT NULL,
    permission VARCHAR(50) NOT NULL,
    FOREIGN KEY (mcp_api_key_id) REFERENCES mcp_api_key(id) ON DELETE CASCADE,
    PRIMARY KEY (mcp_api_key_id, permission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### Existing Tables (No Changes)
- `asset` - no schema changes
- `scan_result` - no schema changes
- `vulnerability` - no schema changes
- `workgroup` - no schema changes
- `user` - no schema changes

---

**Status**: Data model design complete, ready for contract generation
