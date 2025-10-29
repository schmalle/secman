# Data Model: Vulnerability Statistics Lense

**Feature**: 036-vuln-stats-lense
**Date**: 2025-10-28
**Status**: Design

## Overview

This feature uses **existing entities** only - no new database tables or JPA entities are required. All statistics are computed via aggregation queries on the existing `Vulnerability`, `Asset`, `User`, and `Workgroup` tables.

---

## Existing Entities Used

### 1. Vulnerability (Existing - No Changes)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Vulnerability.kt`

**Fields Used**:
```kotlin
@Entity
@Table(name = "vulnerability")
data class Vulnerability(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    val asset: Asset,                          // Used for: asset rankings, workgroup filtering

    @Column(name = "vulnerability_id", nullable = false)
    val vulnerabilityId: String,               // Used for: most common vulnerabilities (GROUP BY)

    @Column(name = "cvss_severity", length = 20)
    val cvssSeverity: String?,                 // Used for: severity distribution

    @Column(name = "vulnerable_product_versions", length = 1000)
    val vulnerableProductVersions: String?,    // Used for: vulnerability details

    @Column(name = "days_open")
    val daysOpen: Int?,                        // Not used in statistics

    @Column(name = "scan_timestamp")
    @Temporal(TemporalType.TIMESTAMP)
    val scanTimestamp: Date?                   // Used for: temporal trends (time-series data)
)
```

**Queries Required**:
- **Most common vulnerabilities**: `GROUP BY vulnerability_id, cvss_severity ORDER BY COUNT(*) DESC LIMIT 10`
- **Severity distribution**: `GROUP BY cvss_severity`
- **Temporal trends**: `GROUP BY DATE(scan_timestamp)` with date range filter
- **All queries**: Must JOIN with `asset` and `asset_workgroup` for access control

---

### 2. Asset (Existing - No Changes)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**Fields Used**:
```kotlin
@Entity
@Table(name = "asset")
data class Asset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "name", nullable = false)
    val name: String,                          // Used for: asset rankings display

    @Column(name = "type", length = 50)
    val type: String?,                         // Used for: vulnerabilities by asset type

    @Column(name = "ip", length = 45)
    val ip: String?,                           // Used for: asset details display

    @Column(name = "owner", length = 255)
    val owner: String?,                        // Not used in statistics

    @OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true)
    val vulnerabilities: List<Vulnerability> = emptyList(),  // Used for: counting vulnerabilities per asset

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "asset_workgroup",
        joinColumns = [JoinColumn(name = "asset_id")],
        inverseJoinColumns = [JoinColumn(name = "workgroup_id")]
    )
    val workgroups: Set<Workgroup> = emptySet()  // Used for: workgroup-based access control
)
```

**Queries Required**:
- **Top assets by vulnerability count**: `SELECT asset_id, COUNT(*) FROM vulnerability GROUP BY asset_id ORDER BY COUNT(*) DESC LIMIT 10`
- **Vulnerabilities by asset type**: `SELECT a.type, COUNT(*) FROM vulnerability v JOIN asset a ... GROUP BY a.type`
- **Workgroup filtering**: `JOIN asset_workgroup ON asset.id = asset_workgroup.asset_id WHERE workgroup_id IN (:userWorkgroups)`

---

### 3. User (Existing - No Changes)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Fields Used**:
```kotlin
@Entity
@Table(name = "user")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "username", nullable = false, unique = true)
    val username: String,                      // Used for: identifying current user

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
    @Column(name = "role")
    val roles: Set<String> = emptySet(),       // Used for: ADMIN vs VULN role check

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_workgroup",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "workgroup_id")]
    )
    val workgroups: Set<Workgroup> = emptySet()  // Used for: determining user's accessible workgroups
)
```

**Usage**:
- Determine if user has `ADMIN` role (sees all statistics)
- Get user's workgroup IDs for filtering (VULN role)

---

### 4. Workgroup (Existing - No Changes)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`

**Fields Used**:
```kotlin
@Entity
@Table(name = "workgroup")
data class Workgroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "name", nullable = false, unique = true)
    val name: String,                          // Not directly used in statistics

    @ManyToMany(mappedBy = "workgroups", fetch = FetchType.LAZY)
    val assets: Set<Asset> = emptySet(),       // Used via JOIN for filtering

    @ManyToMany(mappedBy = "workgroups", fetch = FetchType.LAZY)
    val users: Set<User> = emptySet()          // Used via JOIN for user access determination
)
```

**Usage**:
- JOIN table `asset_workgroup` for filtering assets by workgroup
- JOIN table `user_workgroup` for determining user's workgroups

---

## DTOs (Data Transfer Objects) - NEW

### 1. MostCommonVulnerabilityDto

**Purpose**: Transport data for top N most frequently occurring vulnerabilities

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/MostCommonVulnerabilityDto.kt`

```kotlin
package com.secman.dto

data class MostCommonVulnerabilityDto(
    val vulnerabilityId: String,        // CVE identifier (e.g., "CVE-2023-12345")
    val cvssSeverity: String,           // "CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"
    val occurrenceCount: Long,          // Total count across all accessible assets
    val affectedAssetCount: Long        // Number of distinct assets affected
)
```

**Validation Rules**:
- `vulnerabilityId`: Non-blank, max 255 chars
- `cvssSeverity`: One of valid severity values
- `occurrenceCount`: Must be > 0
- `affectedAssetCount`: Must be > 0 and ≤ occurrenceCount

**Example Response**:
```json
{
  "vulnerabilityId": "CVE-2023-12345",
  "cvssSeverity": "CRITICAL",
  "occurrenceCount": 47,
  "affectedAssetCount": 23
}
```

---

### 2. SeverityDistributionDto

**Purpose**: Transport severity breakdown statistics

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/SeverityDistributionDto.kt`

```kotlin
package com.secman.dto

data class SeverityDistributionDto(
    val critical: Long,      // Count of CRITICAL severity vulnerabilities
    val high: Long,          // Count of HIGH severity vulnerabilities
    val medium: Long,        // Count of MEDIUM severity vulnerabilities
    val low: Long,           // Count of LOW severity vulnerabilities
    val unknown: Long        // Count of vulnerabilities with null/unknown severity
) {
    // Computed property for total count
    val total: Long
        get() = critical + high + medium + low + unknown

    // Computed properties for percentages
    val criticalPercent: Double
        get() = if (total > 0) (critical.toDouble() / total) * 100 else 0.0

    val highPercent: Double
        get() = if (total > 0) (high.toDouble() / total) * 100 else 0.0

    val mediumPercent: Double
        get() = if (total > 0) (medium.toDouble() / total) * 100 else 0.0

    val lowPercent: Double
        get() = if (total > 0) (low.toDouble() / total) * 100 else 0.0

    val unknownPercent: Double
        get() = if (total > 0) (unknown.toDouble() / total) * 100 else 0.0
}
```

**Validation Rules**:
- All counts must be ≥ 0
- At least one count should be > 0 (or return empty state)

**Example Response**:
```json
{
  "critical": 15,
  "high": 42,
  "medium": 128,
  "low": 67,
  "unknown": 3,
  "total": 255,
  "criticalPercent": 5.88,
  "highPercent": 16.47,
  "mediumPercent": 50.20,
  "lowPercent": 26.27,
  "unknownPercent": 1.18
}
```

---

### 3. TopAssetByVulnerabilitiesDto

**Purpose**: Transport data for assets ranked by vulnerability count

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/TopAssetByVulnerabilitiesDto.kt`

```kotlin
package com.secman.dto

data class TopAssetByVulnerabilitiesDto(
    val assetId: Long,                  // Asset ID for navigation/linking
    val assetName: String,              // Display name
    val assetType: String?,             // Asset type (server, workstation, etc.)
    val assetIp: String?,               // IP address for display
    val totalVulnerabilityCount: Long,  // Total vulnerabilities on this asset
    val criticalCount: Long,            // Count of critical vulnerabilities
    val highCount: Long,                // Count of high vulnerabilities
    val mediumCount: Long,              // Count of medium vulnerabilities
    val lowCount: Long                  // Count of low vulnerabilities
)
```

**Validation Rules**:
- `assetId`: Must be > 0
- `assetName`: Non-blank, max 255 chars
- `totalVulnerabilityCount`: Must be > 0 and equal to sum of severity counts
- Severity counts: Must be ≥ 0

**Example Response**:
```json
{
  "assetId": 123,
  "assetName": "web-server-prod-01",
  "assetType": "server",
  "assetIp": "10.0.1.45",
  "totalVulnerabilityCount": 32,
  "criticalCount": 2,
  "highCount": 8,
  "mediumCount": 15,
  "lowCount": 7
}
```

---

### 4. VulnerabilityByAssetTypeDto

**Purpose**: Transport vulnerability statistics grouped by asset type

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/VulnerabilityByAssetTypeDto.kt`

```kotlin
package com.secman.dto

data class VulnerabilityByAssetTypeDto(
    val assetType: String,              // Asset type (e.g., "server", "workstation", "network_device")
    val assetCount: Long,               // Number of assets of this type
    val totalVulnerabilityCount: Long,  // Total vulnerabilities across all assets of this type
    val criticalCount: Long,            // Critical vulnerabilities
    val highCount: Long,                // High vulnerabilities
    val mediumCount: Long,              // Medium vulnerabilities
    val lowCount: Long,                 // Low vulnerabilities
    val averageVulnerabilitiesPerAsset: Double  // totalVulnerabilityCount / assetCount
)
```

**Validation Rules**:
- `assetType`: Non-blank; if null in DB, use "Unknown"
- `assetCount`: Must be > 0
- `totalVulnerabilityCount`: Must be > 0
- `averageVulnerabilitiesPerAsset`: Computed as `totalVulnerabilityCount.toDouble() / assetCount`

**Example Response**:
```json
{
  "assetType": "server",
  "assetCount": 45,
  "totalVulnerabilityCount": 892,
  "criticalCount": 12,
  "highCount": 87,
  "mediumCount": 543,
  "lowCount": 250,
  "averageVulnerabilitiesPerAsset": 19.82
}
```

---

### 5. TemporalTrendDataPointDto

**Purpose**: Single data point for time-series vulnerability trend

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/TemporalTrendDataPointDto.kt`

```kotlin
package com.secman.dto

import java.time.LocalDate

data class TemporalTrendDataPointDto(
    val date: LocalDate,                // Date of the data point (no time component)
    val totalCount: Long,               // Total vulnerability count on this date
    val criticalCount: Long,            // Critical vulnerabilities
    val highCount: Long,                // High vulnerabilities
    val mediumCount: Long,              // Medium vulnerabilities
    val lowCount: Long                  // Low vulnerabilities
)
```

**Validation Rules**:
- `date`: Must be a valid date; cannot be in the future
- All counts: Must be ≥ 0
- `totalCount`: Should equal sum of severity counts

**Example Response** (array):
```json
[
  {
    "date": "2025-10-01",
    "totalCount": 245,
    "criticalCount": 8,
    "highCount": 42,
    "mediumCount": 135,
    "lowCount": 60
  },
  {
    "date": "2025-10-02",
    "totalCount": 243,
    "criticalCount": 7,
    "highCount": 41,
    "mediumCount": 137,
    "lowCount": 58
  }
]
```

---

### 6. TemporalTrendsDto

**Purpose**: Container for temporal trend data with metadata

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/TemporalTrendsDto.kt`

```kotlin
package com.secman.dto

import java.time.LocalDate

data class TemporalTrendsDto(
    val startDate: LocalDate,                       // Start of time range
    val endDate: LocalDate,                         // End of time range
    val days: Int,                                  // Number of days (30, 60, or 90)
    val dataPoints: List<TemporalTrendDataPointDto> // Daily data points
)
```

**Validation Rules**:
- `days`: Must be 30, 60, or 90
- `startDate`: Must be before `endDate`
- `dataPoints`: Should have up to `days` entries (may have fewer if no data for some days)

**Example Response**:
```json
{
  "startDate": "2025-09-28",
  "endDate": "2025-10-28",
  "days": 30,
  "dataPoints": [
    { "date": "2025-09-28", "totalCount": 250, ... },
    { "date": "2025-09-29", "totalCount": 248, ... },
    ...
  ]
}
```

---

## Database Schema - No Changes

**Important**: This feature does **NOT** require any database migrations or schema changes. All statistics are computed via aggregation queries on existing tables.

### Existing Tables Used

1. **vulnerability** (primary data source)
   - Indexed on: `vulnerability_id`, `cvss_severity`, `scan_timestamp`, `asset_id` (FK)

2. **asset** (for asset details and type grouping)
   - Indexed on: `id` (PK), `type`, `name`

3. **asset_workgroup** (for access control)
   - Indexed on: `asset_id`, `workgroup_id` (composite FK)

4. **user_workgroup** (for determining user's workgroups)
   - Indexed on: `user_id`, `workgroup_id` (composite FK)

5. **user** (for role and workgroup checks)
   - Indexed on: `id` (PK), `username` (unique)

### Existing Indexes Sufficient

Current indexes are sufficient for performance with 10,000+ vulnerabilities:
- `vulnerability.vulnerability_id` - used in GROUP BY for most common vulnerabilities
- `vulnerability.cvss_severity` - used in GROUP BY for severity distribution
- `vulnerability.scan_timestamp` - used for temporal trends filtering
- `vulnerability.asset_id` - used in JOINs for asset rankings
- `asset_workgroup(asset_id, workgroup_id)` - used for workgroup filtering

**Performance Expectation**: Aggregation queries should complete in <1s for 10,000 vulnerabilities with existing indexes.

---

## State Transitions - Not Applicable

This feature has no state transitions. All statistics are read-only, computed dynamically, and represent current state of vulnerability data.

---

## Relationships

```
User (1) ---- (N) User_Workgroup (N) ---- (1) Workgroup
                                                  |
                                                  | (N)
                                                  |
Asset_Workgroup (N) ---- (1) Asset (1) ---- (N) Vulnerability
```

**Access Control Flow**:
1. Get current user's workgroup IDs via `user_workgroup` table
2. Filter assets via `asset_workgroup` table WHERE `workgroup_id IN (:userWorkgroups)`
3. Only count vulnerabilities for accessible assets
4. ADMIN role bypasses workgroup filtering (sees all)

---

## Aggregation Query Examples

### Most Common Vulnerabilities (with Workgroup Filtering)

```sql
-- For VULN role (workgroup filtering applied)
SELECT
    v.vulnerability_id,
    v.cvss_severity,
    COUNT(*) as occurrence_count,
    COUNT(DISTINCT v.asset_id) as affected_asset_count
FROM vulnerability v
JOIN asset a ON v.asset_id = a.id
JOIN asset_workgroup aw ON a.id = aw.asset_id
WHERE aw.workgroup_id IN (:workgroupIds)
GROUP BY v.vulnerability_id, v.cvss_severity
ORDER BY occurrence_count DESC
LIMIT 10;

-- For ADMIN role (no filtering)
SELECT
    v.vulnerability_id,
    v.cvss_severity,
    COUNT(*) as occurrence_count,
    COUNT(DISTINCT v.asset_id) as affected_asset_count
FROM vulnerability v
GROUP BY v.vulnerability_id, v.cvss_severity
ORDER BY occurrence_count DESC
LIMIT 10;
```

### Severity Distribution

```sql
-- For VULN role
SELECT
    COALESCE(v.cvss_severity, 'UNKNOWN') as severity,
    COUNT(*) as count
FROM vulnerability v
JOIN asset a ON v.asset_id = a.id
JOIN asset_workgroup aw ON a.id = aw.asset_id
WHERE aw.workgroup_id IN (:workgroupIds)
GROUP BY v.cvss_severity;

-- For ADMIN role
SELECT
    COALESCE(v.cvss_severity, 'UNKNOWN') as severity,
    COUNT(*) as count
FROM vulnerability v
GROUP BY v.cvss_severity;
```

### Temporal Trends (30-day example)

```sql
-- For VULN role
SELECT
    DATE(v.scan_timestamp) as date,
    COUNT(*) as total_count,
    SUM(CASE WHEN v.cvss_severity = 'CRITICAL' THEN 1 ELSE 0 END) as critical_count,
    SUM(CASE WHEN v.cvss_severity = 'HIGH' THEN 1 ELSE 0 END) as high_count,
    SUM(CASE WHEN v.cvss_severity = 'MEDIUM' THEN 1 ELSE 0 END) as medium_count,
    SUM(CASE WHEN v.cvss_severity = 'LOW' THEN 1 ELSE 0 END) as low_count
FROM vulnerability v
JOIN asset a ON v.asset_id = a.id
JOIN asset_workgroup aw ON a.id = aw.asset_id
WHERE aw.workgroup_id IN (:workgroupIds)
  AND v.scan_timestamp >= CURRENT_DATE - INTERVAL 30 DAY
GROUP BY DATE(v.scan_timestamp)
ORDER BY date ASC;
```

---

## Error Handling

### Empty State Conditions

1. **No vulnerabilities in system** (FR-012):
   - Return empty DTO with zero counts
   - Frontend displays: "No vulnerability data available. Please import vulnerability scans to view statistics."

2. **No accessible vulnerabilities (workgroup filtered)**:
   - Return empty DTO with zero counts
   - Frontend displays: "No vulnerabilities found for your assigned workgroups."

3. **No data for selected time range**:
   - Return empty `dataPoints` array in `TemporalTrendsDto`
   - Frontend displays: "No vulnerability data available for the selected time period."

### Missing/Null Data

1. **Null `cvss_severity`**:
   - Categorize as "UNKNOWN" in severity distribution
   - Display as separate category in charts

2. **Null `asset.type`**:
   - Categorize as "Unknown" in asset type grouping
   - Display as separate category in charts

3. **Deleted assets (soft delete)**:
   - Exclude from statistics if `asset.deleted = true` (if soft delete implemented)
   - Otherwise, CASCADE DELETE removes vulnerabilities automatically

---

## Summary

- **New Entities**: 0 (no new tables)
- **New DTOs**: 6 (for API responses)
- **Schema Changes**: None (uses existing tables and indexes)
- **Access Control**: Via existing `asset_workgroup` and `user_workgroup` tables
- **Performance**: Existing indexes sufficient for <1s query times up to 10,000 vulnerabilities

**Complexity**: Low - Read-only aggregation feature with no state management or data persistence beyond existing entities.
