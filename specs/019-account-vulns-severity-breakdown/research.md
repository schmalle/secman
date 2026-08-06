# Research: Account Vulns Severity Breakdown

**Feature**: 019-account-vulns-severity-breakdown
**Date**: 2025-10-14
**Status**: Phase 0 Complete

## Overview

This document resolves technical unknowns and research questions identified during planning for the severity breakdown enhancement to the Account Vulns feature.

## 1. Database Query Optimization for Severity Grouping

### Decision
Use single SQL query with conditional aggregation (CASE statements) within a single GROUP BY clause.

### Rationale
- **Performance**: Single query execution is faster than multiple queries or subqueries
- **Maintainability**: All severity counts computed in one place, easier to debug
- **Database Efficiency**: Single table scan vs multiple scans
- **Consistency**: All counts from same snapshot, avoiding race conditions

### Implementation Pattern

```sql
SELECT 
    v.asset_id,
    COUNT(*) as total_count,
    SUM(CASE WHEN UPPER(v.severity) = 'CRITICAL' THEN 1 ELSE 0 END) as critical_count,
    SUM(CASE WHEN UPPER(v.severity) = 'HIGH' THEN 1 ELSE 0 END) as high_count,
    SUM(CASE WHEN UPPER(v.severity) = 'MEDIUM' THEN 1 ELSE 0 END) as medium_count,
    SUM(CASE WHEN UPPER(v.severity) = 'LOW' THEN 1 ELSE 0 END) as low_count,
    SUM(CASE WHEN v.severity IS NULL OR UPPER(v.severity) NOT IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW') THEN 1 ELSE 0 END) as unknown_count
FROM vulnerabilities v
WHERE v.asset_id IN (...)
GROUP BY v.asset_id
```

### Alternatives Considered
1. **Multiple Queries**: Separate COUNT queries per severity → Rejected due to multiple DB round trips
2. **Subqueries**: One query with multiple scalar subqueries → Rejected due to N+1 problem
3. **Application-Level Grouping**: Fetch all vulns, group in code → Rejected due to memory overhead

### Performance Characteristics
- **Complexity**: O(n) where n = number of vulnerabilities
- **Index Usage**: Can use composite index on (asset_id, severity) if needed
- **Expected Execution Time**: <50ms for 10,000 vulnerabilities (estimated)

## 2. Index Analysis for Severity Field

### Decision
**Defer** adding dedicated index on `vulnerabilities.severity` field. Monitor query performance and add if needed.

### Rationale
- **Existing Index**: `asset_id` index already exists and will be used for WHERE clause filtering
- **Selectivity**: Severity field has low cardinality (4-5 values), index may not provide significant benefit
- **GROUP BY Optimization**: MariaDB 11.4 can optimize GROUP BY without requiring index on grouped column
- **Cost-Benefit**: Index adds write overhead; premature optimization before measuring actual performance

### Monitoring Plan
1. Profile existing query execution time as baseline
2. Profile new query with severity grouping
3. If query time increases >10%, consider composite index: `(asset_id, severity)`
4. Use `EXPLAIN` to verify query plan utilizes existing indexes

### Optional Composite Index (if needed)
```sql
CREATE INDEX idx_vuln_asset_severity 
ON vulnerabilities(asset_id, severity);
```

**When to Add**:
- Query execution time exceeds 100ms for typical workload
- EXPLAIN shows filesort or full table scan
- Large datasets (>100K vulnerabilities) cause performance issues

### Alternatives Considered
1. **Immediate Index Creation**: → Deferred to avoid premature optimization
2. **No Index Ever**: → Keep as option if performance acceptable
3. **Severity-Only Index**: → Less useful than composite index

## 3. Severity Field Normalization Strategy

### Decision
Normalize severity values to UPPERCASE in **service layer** before grouping/comparison.

### Rationale
- **Flexibility**: Service layer normalization allows for future changes without DB migration
- **Testability**: Easy to unit test normalization logic
- **Database Agnostic**: Works regardless of DB collation settings
- **Backward Compatibility**: No schema changes required

### Implementation Location
**Service Layer** (`AccountVulnsService.kt`):
```kotlin
private fun normalizeSeverity(severity: String?): String {
    return severity?.uppercase()?.trim() ?: "UNKNOWN"
}

// In query:
// CASE WHEN UPPER(severity) = 'CRITICAL' THEN 1 ELSE 0 END
```

### Database Collation Check
MariaDB default collation (utf8mb4_general_ci) is case-insensitive for comparisons, so `UPPER()` in SQL provides explicit normalization visible in query logs.

### Alternatives Considered
1. **Database Collation**: Change collation to case-sensitive → Rejected (requires migration, affects other queries)
2. **Application Layer Only**: Normalize after fetching → Rejected (can't use in SQL GROUP BY)
3. **Hibernate Converter**: JPA AttributeConverter → Rejected (not needed for read-only aggregation)

## 4. Color-Blind Accessibility Patterns

### Decision
Use **icons + colors + consistent positioning** for severity indicators, following WCAG 2.1 Level AA guidelines.

### Implementation

#### Icon Choices
- **Critical**: `⚠️` (Warning Triangle) or `🔴` (Red Circle) + text "Critical"
- **High**: `⬆️` (Up Arrow) or `🟠` (Orange Circle) + text "High"  
- **Medium**: `➖` (Minus/Dash) or `🟡` (Yellow Circle) + text "Medium"

**Rationale**: Shape + color + text provides triple redundancy for accessibility.

#### Bootstrap Badge Classes
```tsx
<span className="badge bg-danger">
  <i className="bi bi-exclamation-triangle-fill me-1" aria-hidden="true"></i>
  Critical: {count}
</span>

<span className="badge bg-warning text-dark">
  <i className="bi bi-arrow-up-circle-fill me-1" aria-hidden="true"></i>
  High: {count}
</span>

<span className="badge bg-info text-dark">
  <i className="bi bi-dash-circle-fill me-1" aria-hidden="true"></i>
  Medium: {count}
</span>
```

#### WCAG Compliance Checklist
- ✅ **1.4.1 Use of Color**: Icons + text labels don't rely solely on color
- ✅ **1.4.3 Contrast**: Bootstrap badges meet 4.5:1 contrast ratio
- ✅ **1.4.11 Non-text Contrast**: Icon shapes distinguishable at 3:1 contrast
- ✅ **2.4.6 Headings and Labels**: Clear "Critical", "High", "Medium" labels

#### Testing Tools
- **Chrome DevTools**: Emulate color vision deficiencies (protanopia, deuteranopia, tritanopia)
- **axe DevTools**: Automated accessibility testing
- **Manual Testing**: Grayscale filter to verify distinguishability

### Alternatives Considered
1. **Color Only**: → Rejected (WCAG violation)
2. **Emoji Icons**: → Considered but Bootstrap Icons more professional and consistent
3. **Patterns/Textures**: → Deferred (icons + text sufficient for MVP)

## 5. Backward Compatibility Testing Strategy

### Decision
Use **optional/nullable DTO fields** with automated contract tests to verify backward compatibility.

### Implementation Strategy

#### DTO Field Design
```kotlin
@Serdeable
data class AssetVulnCountDto(
    val id: Long,
    val name: String,
    val type: String,
    val vulnerabilityCount: Int,
    val criticalCount: Int? = null,  // Optional - null for old consumers
    val highCount: Int? = null,
    val mediumCount: Int? = null
)
```

#### Micronaut Serde Behavior
- **Serialization**: Null fields are **included** in JSON with null value by default
- **Alternative**: Use `@JsonInclude(JsonInclude.Include.NON_NULL)` to omit null fields entirely
- **Deserialization**: Old consumers without these fields simply ignore them

#### Contract Test Pattern
```kotlin
@Test
fun `API response includes optional severity fields`() {
    val response = client.toBlocking().exchange(
        HttpRequest.GET<Any>("/api/account-vulns"),
        AccountVulnsSummaryDto::class.java
    )
    
    val summary = response.body()!!
    summary.accountGroups.forEach { group ->
        group.assets.forEach { asset ->
            // New fields should be present (may be 0, not null after implementation)
            assertNotNull(asset.criticalCount)
            assertNotNull(asset.highCount)
            assertNotNull(asset.mediumCount)
            
            // Counts should sum correctly
            val severitySum = asset.criticalCount!! + asset.highCount!! + 
                             asset.mediumCount!! + lowCount + unknownCount
            assertEquals(asset.vulnerabilityCount, severitySum)
        }
    }
}

@Test
fun `old consumers can deserialize response without severity fields`() {
    // Simulate old DTO without severity fields
    data class OldAssetVulnCountDto(
        val id: Long,
        val name: String,
        val type: String,
        val vulnerabilityCount: Int
    )
    
    val response = client.toBlocking().exchange(
        HttpRequest.GET<Any>("/api/account-vulns"),
        String::class.java  // Get raw JSON
    )
    
    // Parse with old DTO structure
    val oldDto = objectMapper.readValue(response.body(), OldAssetVulnCountDto::class.java)
    assertNotNull(oldDto.vulnerabilityCount)
}
```

#### Validation Rules
1. **New fields optional**: Clients MUST NOT fail if fields are missing
2. **Null handling**: Frontend uses optional chaining: `asset.criticalCount ?? 0`
3. **Type safety**: TypeScript interfaces mark fields as optional: `criticalCount?: number`

### Alternatives Considered
1. **API Versioning**: /api/v2/account-vulns → Rejected (overkill for additive change)
2. **Query Parameter**: ?includeSeverity=true → Rejected (complicates caching, not needed)
3. **Required Fields**: → Rejected (breaks backward compatibility)

## 6. Current Database State Analysis

### Findings

#### Schema Check
```sql
DESCRIBE vulnerabilities;
-- Expected: severity VARCHAR(50) or ENUM('CRITICAL','HIGH','MEDIUM','LOW')
```

**Assumption** (to be verified during implementation):
- Field exists as `severity VARCHAR(50)`
- Values are stored with mixed case: "Critical", "high", "MEDIUM", etc.
- Some NULL values may exist (handled as UNKNOWN)

#### Data Distribution Query
```sql
SELECT 
    UPPER(severity) as normalized_severity,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM vulnerabilities), 2) as percentage
FROM vulnerabilities
GROUP BY UPPER(severity)
ORDER BY count DESC;
```

**Expected Output** (example):
```
normalized_severity | count | percentage
--------------------|-------|----------
HIGH                | 4521  | 45.21%
MEDIUM              | 3210  | 32.10%
CRITICAL            | 1543  | 15.43%
LOW                 | 620   | 6.20%
NULL                | 106   | 1.06%
```

#### NULL/Empty Value Handling
- **Strategy**: COUNT with CASE handles NULL as UNKNOWN category
- **Validation**: Log warning if unknown_count > 5% of total vulnerabilities

### Verification Script
```sql
-- Check for severity field existence
SELECT COUNT(*) FROM information_schema.COLUMNS 
WHERE TABLE_NAME = 'vulnerabilities' 
AND COLUMN_NAME = 'severity';

-- Check for inconsistent values
SELECT DISTINCT severity 
FROM vulnerabilities 
WHERE severity IS NOT NULL 
AND UPPER(severity) NOT IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW');
```

## 7. Performance Baseline

### Current Implementation
- **Endpoint**: GET /api/account-vulns
- **Query**: Simple COUNT(*) grouped by asset_id
- **Expected Response Time**: <100ms for 50 assets with 1000 total vulnerabilities

### New Implementation Impact
- **Additional Computation**: 5 CASE statements per row (negligible CPU cost)
- **Expected Response Time**: <110ms (≤10% increase) ✅
- **Response Size Increase**: ~60 bytes per asset (3 new int fields) → ~3KB for 50 assets ✅

### Measurement Plan
1. **Before**: Measure baseline with existing implementation
2. **After**: Measure with severity grouping
3. **Compare**: Ensure <10% increase in response time, <30% in size

### Load Testing
```bash
# Baseline
ab -n 1000 -c 10 -H "Authorization: Bearer $TOKEN" \
   http://localhost:8080/api/account-vulns

# After severity implementation
ab -n 1000 -c 10 -H "Authorization: Bearer $TOKEN" \
   http://localhost:8080/api/account-vulns
```

## Summary

All research tasks completed. Key decisions:

| Area | Decision | Status |
|------|----------|--------|
| Query Optimization | Single query with CASE aggregation | ✅ Ready |
| Index Strategy | Defer; monitor performance | ✅ Ready |
| Normalization | Service layer UPPER() in SQL | ✅ Ready |
| Accessibility | Icons + colors + text labels | ✅ Ready |
| Backward Compatibility | Optional/nullable fields + contract tests | ✅ Ready |
| Database State | Verify during implementation | ⚠️ Verify |
| Performance Baseline | Measure before/after | ⚠️ Measure |

**Unknowns Resolved**: 5/5 critical research questions answered
**Blockers**: None
**Next Phase**: Phase 1 - Design & Contracts (data-model.md, quickstart.md, contracts/)

---

**Research Complete**: 2025-10-14
