# Data Model: Account Vulns Severity Breakdown

**Feature**: 019-account-vulns-severity-breakdown
**Date**: 2025-10-14
**Phase**: 1 - Design

## Overview

This feature extends existing DTOs with severity breakdown fields. No database schema changes are required - we leverage the existing `vulnerabilities.severity` field for grouping and counting.

## Database Schema (No Changes Required)

### Vulnerabilities Table (Existing)

**Table**: `vulnerabilities`

**Relevant Fields**:
- `id` (BIGINT, PK)
- `asset_id` (BIGINT, FK → assets.id)
- `severity` (VARCHAR(50) or ENUM) - **Key field for this feature**
- Other fields (cve_id, description, etc.) - not relevant for this feature

**Severity Field**:
- **Type**: VARCHAR(50) or ENUM('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')
- **Values**: CRITICAL, HIGH, MEDIUM, LOW (case-insensitive)
- **Nullable**: YES (handled as UNKNOWN in aggregation)
- **Normalization**: Converted to UPPERCASE in query for consistent grouping

### Assets Table (Existing)

**Table**: `assets`

**Relevant Fields**:
- `id` (BIGINT, PK)
- `name` (VARCHAR(255))
- `type` (VARCHAR(100))
- `cloud_account_id` (VARCHAR(12)) - AWS account filtering

**Relationship**: One asset has many vulnerabilities

### Query Strategy

Single query with conditional aggregation:
```sql
SELECT 
    v.asset_id,
    COUNT(*) as total_count,
    SUM(CASE WHEN UPPER(v.severity) = 'CRITICAL' THEN 1 ELSE 0 END) as critical_count,
    SUM(CASE WHEN UPPER(v.severity) = 'HIGH' THEN 1 ELSE 0 END) as high_count,
    SUM(CASE WHEN UPPER(v.severity) = 'MEDIUM' THEN 1 ELSE 0 END) as medium_count
FROM vulnerabilities v
WHERE v.asset_id IN (${assetIds})
GROUP BY v.asset_id
```

## Backend DTOs (Kotlin)

### AssetVulnCountDto

**Purpose**: Represents a single asset with vulnerability counts broken down by severity.

**Package**: `com.secman.dto`

**Fields**:
```kotlin
@Serdeable
data class AssetVulnCountDto(
    val id: Long,                      // Existing - Asset ID
    val name: String,                  // Existing - Asset name
    val type: String,                  // Existing - Asset type (SERVER, WORKSTATION, etc.)
    val vulnerabilityCount: Int,       // Existing - Total vulnerability count
    
    // NEW FIELDS - Optional for backward compatibility
    val criticalCount: Int? = null,    // Count of CRITICAL severity vulnerabilities
    val highCount: Int? = null,        // Count of HIGH severity vulnerabilities
    val mediumCount: Int? = null       // Count of MEDIUM severity vulnerabilities
)
```

**Validation Rules**:
- `criticalCount + highCount + mediumCount + lowCount + unknownCount = vulnerabilityCount`
- All severity counts ≥ 0
- If severity fields are null, feature not yet implemented (backward compat)

**Example**:
```json
{
  "id": 42,
  "name": "web-server-01",
  "type": "SERVER",
  "vulnerabilityCount": 28,
  "criticalCount": 5,
  "highCount": 12,
  "mediumCount": 8
}
```

### AccountGroupDto

**Purpose**: Represents an AWS account group with aggregated severity totals across all assets in that account.

**Package**: `com.secman.dto`

**Fields**:
```kotlin
@Serdeable
data class AccountGroupDto(
    val awsAccountId: String,                  // Existing - 12-digit AWS account ID
    val assets: List<AssetVulnCountDto>,       // Existing - Assets with severity counts
    val totalAssets: Int,                      // Existing - Number of assets in account
    val totalVulnerabilities: Int,             // Existing - Total vulns across all assets
    
    // NEW FIELDS - Aggregated from assets
    val totalCritical: Int? = null,            // Sum of criticalCount across assets
    val totalHigh: Int? = null,                // Sum of highCount across assets
    val totalMedium: Int? = null               // Sum of mediumCount across assets
)
```

**Aggregation Logic**:
```kotlin
val totalCritical = assets.sumOf { it.criticalCount ?: 0 }
val totalHigh = assets.sumOf { it.highCount ?: 0 }
val totalMedium = assets.sumOf { it.mediumCount ?: 0 }
```

**Example**:
```json
{
  "awsAccountId": "123456789012",
  "totalAssets": 3,
  "totalVulnerabilities": 47,
  "totalCritical": 8,
  "totalHigh": 22,
  "totalMedium": 17,
  "assets": [...]
}
```

### AccountVulnsSummaryDto

**Purpose**: Top-level response containing all account groups with global severity totals.

**Package**: `com.secman.dto`

**Fields**:
```kotlin
@Serdeable
data class AccountVulnsSummaryDto(
    val accountGroups: List<AccountGroupDto>,  // Existing - Account groups with severity data
    val totalAssets: Int,                      // Existing - Total assets across all accounts
    val totalVulnerabilities: Int,             // Existing - Total vulns across all accounts
    
    // NEW FIELDS - Aggregated from account groups
    val globalCritical: Int? = null,           // Sum of totalCritical across accounts
    val globalHigh: Int? = null,               // Sum of totalHigh across accounts
    val globalMedium: Int? = null              // Sum of totalMedium across accounts
)
```

**Aggregation Logic**:
```kotlin
val globalCritical = accountGroups.sumOf { it.totalCritical ?: 0 }
val globalHigh = accountGroups.sumOf { it.totalHigh ?: 0 }
val globalMedium = accountGroups.sumOf { it.totalMedium ?: 0 }
```

**Example**:
```json
{
  "totalAssets": 23,
  "totalVulnerabilities": 174,
  "globalCritical": 18,
  "globalHigh": 67,
  "globalMedium": 89,
  "accountGroups": [...]
}
```

## Frontend TypeScript Interfaces

### AssetVulnCount

**File**: `src/frontend/src/services/accountVulnsService.ts`

```typescript
export interface AssetVulnCount {
  id: number;
  name: string;
  type: string;
  vulnerabilityCount: number;
  
  // Optional - may be undefined if backend hasn't implemented yet
  criticalCount?: number;
  highCount?: number;
  mediumCount?: number;
}
```

### AccountGroup

```typescript
export interface AccountGroup {
  awsAccountId: string;
  assets: AssetVulnCount[];
  totalAssets: number;
  totalVulnerabilities: number;
  
  // Optional - aggregated severity totals
  totalCritical?: number;
  totalHigh?: number;
  totalMedium?: number;
}
```

### AccountVulnsSummary

```typescript
export interface AccountVulnsSummary {
  accountGroups: AccountGroup[];
  totalAssets: number;
  totalVulnerabilities: number;
  
  // Optional - global severity totals
  globalCritical?: number;
  globalHigh?: number;
  globalMedium?: number;
}
```

## Service Layer Logic

### AccountVulnsService (Modifications)

**File**: `src/backendng/src/main/kotlin/com/secman/service/AccountVulnsService.kt`

**New Method**: `countVulnerabilitiesBySeverity(assetIds: List<Long>): Map<Long, SeverityCounts>`

```kotlin
data class SeverityCounts(
    val total: Int,
    val critical: Int,
    val high: Int,
    val medium: Int,
    val low: Int,
    val unknown: Int
) {
    fun validate(): Boolean {
        val sum = critical + high + medium + low + unknown
        if (sum != total) {
            logger.error("Severity count mismatch: sum=$sum, total=$total")
            return false
        }
        return true
    }
}

fun countVulnerabilitiesBySeverity(assetIds: List<Long>): Map<Long, SeverityCounts> {
    // Execute SQL query with CASE aggregation
    // Return map of asset_id to SeverityCounts
    // Validate each result before returning
}
```

**Modified Method**: `getAccountVulnsSummary(authentication: Authentication): AccountVulnsSummaryDto`

Update to populate severity fields in all DTOs.

## Data Flow

```
1. Controller receives authenticated request
   ↓
2. Service extracts user email, gets AWS account mappings
   ↓
3. Service queries assets filtered by cloudAccountId
   ↓
4. Service calls countVulnerabilitiesBySeverity(assetIds) ← NEW
   ↓
5. Service builds AssetVulnCountDto with severity counts ← MODIFIED
   ↓
6. Service aggregates severity totals at account level ← NEW
   ↓
7. Service aggregates severity totals at global level ← NEW
   ↓
8. Return AccountVulnsSummaryDto with all severity data
   ↓
9. Frontend displays severity badges and summaries
```

## Validation & Constraints

### Backend Validation

1. **Sum Validation**: `criticalCount + highCount + mediumCount + lowCount + unknownCount = vulnerabilityCount`
   - Performed in service layer
   - Log error if mismatch detected
   - Don't fail request (return data with warning logged)

2. **Non-Negative Counts**: All severity counts must be ≥ 0
   - Enforced by query (COUNT/SUM never negative)

3. **Null Handling**: Missing severity values treated as UNKNOWN
   - Counted in `unknown` category
   - Included in total but not in critical/high/medium

### Frontend Validation

1. **Optional Chaining**: Always use `?.` for severity fields
   ```typescript
   const critical = asset.criticalCount ?? 0;
   ```

2. **Fallback Display**: If severity fields undefined, show only total
   ```tsx
   {asset.criticalCount !== undefined ? (
     <SeverityBadge severity="CRITICAL" count={asset.criticalCount} />
   ) : null}
   ```

## Migration Notes

### No Database Migration Required
- Uses existing `vulnerabilities.severity` field
- No new tables, columns, or indexes
- Backward compatible at DB level

### Application Deployment
- **Phase 1**: Deploy backend with optional severity fields (null initially)
- **Phase 2**: Deploy frontend that displays severity when available
- **Phase 3**: Verify data quality, add indexes if needed

### Rollback Plan
- Backend: Remove severity counting logic, return null for new fields
- Frontend: Already handles undefined severity fields
- No data cleanup required

## Testing Considerations

### Test Data Requirements

Fixtures should include assets with:
1. All severities present (critical, high, medium, low)
2. Only one severity (e.g., all critical)
3. Zero vulnerabilities
4. NULL/unknown severity values
5. Mixed case severity values ("Critical", "high", "MEDIUM")

### Test Scenarios

1. **Unit Tests**: Verify counting logic with various severity distributions
2. **Integration Tests**: Verify SQL query returns correct counts
3. **Contract Tests**: Verify API response includes severity fields
4. **E2E Tests**: Verify UI displays badges correctly

---

**Data Model Complete**: 2025-10-14
**Next**: Generate API contracts and quickstart guide
