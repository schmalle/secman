# Quickstart: Account Vulns Severity Breakdown

**Feature**: 019-account-vulns-severity-breakdown
**Date**: 2025-10-14
**Estimated Time**: 4-6 hours

## Prerequisites

- ✅ Feature 018-under-vuln-management fully deployed and tested
- ✅ Development environment configured (Kotlin 2.1, Node.js, MariaDB 11.4)
- ✅ Access to test database with vulnerability data
- ✅ Familiarity with existing Account Vulns codebase

## Overview

This guide walks through implementing severity breakdowns for the Account Vulns feature. You'll modify existing services, DTOs, and UI components to display critical/high/medium vulnerability counts.

**Implementation Order**:
1. Backend: Extend DTOs with optional severity fields
2. Backend: Modify service to count vulnerabilities by severity
3. Backend: Add validation logic
4. Backend: Update tests
5. Frontend: Create severity badge component
6. Frontend: Update existing components to display badges
7. Frontend: Update TypeScript interfaces
8. Frontend: Update E2E tests

## Part 1: Backend Implementation

### Step 1: Extend DTOs (15 min)

**File**: `src/backendng/src/main/kotlin/com/secman/dto/AssetVulnCountDto.kt`

Add optional severity fields:

```kotlin
package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class AssetVulnCountDto(
    val id: Long,
    val name: String,
    val type: String,
    val vulnerabilityCount: Int,
    
    // NEW: Optional severity counts (nullable for backward compatibility)
    val criticalCount: Int? = null,
    val highCount: Int? = null,
    val mediumCount: Int? = null
)
```

**File**: `src/backendng/src/main/kotlin/com/secman/dto/AccountGroupDto.kt`

Add optional aggregate severity fields:

```kotlin
@Serdeable
data class AccountGroupDto(
    val awsAccountId: String,
    val assets: List<AssetVulnCountDto>,
    val totalAssets: Int,
    val totalVulnerabilities: Int,
    
    // NEW: Aggregated severity totals
    val totalCritical: Int? = null,
    val totalHigh: Int? = null,
    val totalMedium: Int? = null
)
```

**File**: `src/backendng/src/main/kotlin/com/secman/dto/AccountVulnsSummaryDto.kt`

Add optional global severity fields:

```kotlin
@Serdeable
data class AccountVulnsSummaryDto(
    val accountGroups: List<AccountGroupDto>,
    val totalAssets: Int,
    val totalVulnerabilities: Int,
    
    // NEW: Global severity totals
    val globalCritical: Int? = null,
    val globalHigh: Int? = null,
    val globalMedium: Int? = null
)
```

✅ **Checkpoint**: DTOs compile without errors.

### Step 2: Add Severity Counting Logic (45 min)

**File**: `src/backendng/src/main/kotlin/com/secman/service/AccountVulnsService.kt`

Add data class for severity counts:

```kotlin
/**
 * Vulnerability counts grouped by severity level.
 * Used internally for validation.
 */
private data class SeverityCounts(
    val total: Int,
    val critical: Int,
    val high: Int,
    val medium: Int,
    val low: Int,
    val unknown: Int
) {
    /**
     * Validate that severity counts sum to total.
     * Logs error if mismatch detected.
     */
    fun validate(assetId: Long): Boolean {
        val sum = critical + high + medium + low + unknown
        if (sum != total) {
            logger.error(
                "Severity count mismatch for asset {}: sum={}, total={} " +
                "(critical={}, high={}, medium={}, low={}, unknown={})",
                assetId, sum, total, critical, high, medium, low, unknown
            )
            return false
        }
        return true
    }
}
```

Add new method to count vulnerabilities by severity:

```kotlin
/**
 * Count vulnerabilities grouped by severity for given assets.
 *
 * Uses single SQL query with CASE-based conditional aggregation.
 * Normalizes severity to uppercase for consistent matching.
 *
 * @param assetIds Asset IDs to count vulnerabilities for
 * @return Map of asset ID to severity counts
 */
private fun countVulnerabilitiesBySeverity(assetIds: List<Long>): Map<Long, SeverityCounts> {
    if (assetIds.isEmpty()) {
        return emptyMap()
    }

    logger.debug("Counting vulnerabilities by severity for {} assets", assetIds.size)

    // Execute SQL query with conditional aggregation
    val query = """
        SELECT 
            v.asset_id,
            COUNT(*) as total_count,
            SUM(CASE WHEN UPPER(v.severity) = 'CRITICAL' THEN 1 ELSE 0 END) as critical_count,
            SUM(CASE WHEN UPPER(v.severity) = 'HIGH' THEN 1 ELSE 0 END) as high_count,
            SUM(CASE WHEN UPPER(v.severity) = 'MEDIUM' THEN 1 ELSE 0 END) as medium_count,
            SUM(CASE WHEN UPPER(v.severity) = 'LOW' THEN 1 ELSE 0 END) as low_count,
            SUM(CASE WHEN v.severity IS NULL OR UPPER(v.severity) NOT IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW') 
                THEN 1 ELSE 0 END) as unknown_count
        FROM vulnerabilities v
        WHERE v.asset_id IN (:assetIds)
        GROUP BY v.asset_id
    """.trimIndent()

    val results = vulnerabilityRepository.executeNativeQuery(query, mapOf("assetIds" to assetIds))
    
    return results.associate { row ->
        val assetId = row[0] as Long
        val counts = SeverityCounts(
            total = (row[1] as Number).toInt(),
            critical = (row[2] as Number).toInt(),
            high = (row[3] as Number).toInt(),
            medium = (row[4] as Number).toInt(),
            low = (row[5] as Number).toInt(),
            unknown = (row[6] as Number).toInt()
        )
        
        // Validate counts sum correctly
        counts.validate(assetId)
        
        assetId to counts
    }
}
```

**Note**: Adjust `executeNativeQuery` based on your repository pattern. You may need to use EntityManager or JPQL.

Modify existing `getAccountVulnsSummary` method to populate severity fields:

```kotlin
fun getAccountVulnsSummary(authentication: Authentication): AccountVulnsSummaryDto {
    // ... existing code to get user, AWS accounts, assets ...
    
    val assetIds = allAssets.map { it.id!! }
    
    // NEW: Get severity counts for all assets
    val severityCountsMap = countVulnerabilitiesBySeverity(assetIds)
    
    // Group assets by AWS account
    val accountGroups = allAssets
        .groupBy { it.cloudAccountId ?: throw IllegalStateException("Asset ${it.id} has null cloudAccountId") }
        .map { (accountId, assets) ->
            val assetDtos = assets.map { asset ->
                val counts = severityCountsMap[asset.id] ?: SeverityCounts(0, 0, 0, 0, 0, 0)
                
                AssetVulnCountDto(
                    id = asset.id!!,
                    name = asset.name,
                    type = asset.type,
                    vulnerabilityCount = counts.total,
                    // NEW: Add severity counts
                    criticalCount = counts.critical,
                    highCount = counts.high,
                    mediumCount = counts.medium
                )
            }.sortedByDescending { it.vulnerabilityCount }
            
            // NEW: Aggregate severity totals for account
            val totalCritical = assetDtos.sumOf { it.criticalCount ?: 0 }
            val totalHigh = assetDtos.sumOf { it.highCount ?: 0 }
            val totalMedium = assetDtos.sumOf { it.mediumCount ?: 0 }
            
            AccountGroupDto(
                awsAccountId = accountId,
                assets = assetDtos,
                totalAssets = assetDtos.size,
                totalVulnerabilities = assetDtos.sumOf { it.vulnerabilityCount },
                // NEW: Add account-level severity totals
                totalCritical = totalCritical,
                totalHigh = totalHigh,
                totalMedium = totalMedium
            )
        }
        .sortedBy { it.awsAccountId }
    
    // NEW: Aggregate global severity totals
    val globalCritical = accountGroups.sumOf { it.totalCritical ?: 0 }
    val globalHigh = accountGroups.sumOf { it.totalHigh ?: 0 }
    val globalMedium = accountGroups.sumOf { it.totalMedium ?: 0 }
    
    return AccountVulnsSummaryDto(
        accountGroups = accountGroups,
        totalAssets = allAssets.size,
        totalVulnerabilities = accountGroups.sumOf { it.totalVulnerabilities },
        // NEW: Add global severity totals
        globalCritical = globalCritical,
        globalHigh = globalHigh,
        globalMedium = globalMedium
    )
}
```

✅ **Checkpoint**: Service compiles, run unit tests.

### Step 3: Update Backend Tests (30 min)

**File**: `src/backendng/src/test/kotlin/com/secman/fixtures/AccountVulnsTestFixtures.kt`

Add test vulnerabilities with severity:

```kotlin
fun createVulnerabilitiesWithSeverity(asset: Asset): List<Vulnerability> {
    return listOf(
        Vulnerability(cveId = "CVE-2024-0001", severity = "CRITICAL", asset = asset),
        Vulnerability(cveId = "CVE-2024-0002", severity = "CRITICAL", asset = asset),
        Vulnerability(cveId = "CVE-2024-0003", severity = "HIGH", asset = asset),
        Vulnerability(cveId = "CVE-2024-0004", severity = "HIGH", asset = asset),
        Vulnerability(cveId = "CVE-2024-0005", severity = "HIGH", asset = asset),
        Vulnerability(cveId = "CVE-2024-0006", severity = "MEDIUM", asset = asset),
        Vulnerability(cveId = "CVE-2024-0007", severity = "LOW", asset = asset),
        Vulnerability(cveId = "CVE-2024-0008", severity = null, asset = asset) // UNKNOWN
    )
}
```

**File**: `src/backendng/src/test/kotlin/com/secman/service/AccountVulnsServiceTest.kt`

Add test for severity counting:

```kotlin
@Test
fun `should count vulnerabilities by severity correctly`() {
    // Arrange
    val user = createTestUser()
    val awsAccountId = "123456789012"
    val userMapping = createUserMapping(user.email, awsAccountId)
    val asset = createAsset(awsAccountId, "test-server")
    val vulns = createVulnerabilitiesWithSeverity(asset)
    
    // Act
    val summary = service.getAccountVulnsSummary(authentication)
    
    // Assert
    val assetDto = summary.accountGroups.first().assets.first()
    assertEquals(8, assetDto.vulnerabilityCount)
    assertEquals(2, assetDto.criticalCount)
    assertEquals(3, assetDto.highCount)
    assertEquals(1, assetDto.mediumCount)
}

@Test
fun `should aggregate severity counts at account level`() {
    // ... test account-level aggregation ...
}

@Test
fun `should aggregate severity counts globally`() {
    // ... test global aggregation ...
}
```

**File**: `src/backendng/src/test/kotlin/com/secman/contract/AccountVulnsContractTest.kt`

Update contract test to verify severity fields:

```kotlin
@Test
fun `GET account-vulns returns severity breakdown`() {
    // ... setup test data ...
    
    val response = client.toBlocking().exchange(
        HttpRequest.GET<Any>("/api/account-vulns")
            .bearerAuth(jwtToken),
        AccountVulnsSummaryDto::class.java
    )
    
    assertEquals(HttpStatus.OK, response.status)
    val summary = response.body()!!
    
    // Verify severity fields present
    assertNotNull(summary.globalCritical)
    assertNotNull(summary.globalHigh)
    assertNotNull(summary.globalMedium)
    
    summary.accountGroups.forEach { group ->
        assertNotNull(group.totalCritical)
        assertNotNull(group.totalHigh)
        assertNotNull(group.totalMedium)
        
        group.assets.forEach { asset ->
            assertNotNull(asset.criticalCount)
            assertNotNull(asset.highCount)
            assertNotNull(asset.mediumCount)
            
            // Verify counts are non-negative
            assertTrue(asset.criticalCount!! >= 0)
            assertTrue(asset.highCount!! >= 0)
            assertTrue(asset.mediumCount!! >= 0)
        }
    }
}
```

✅ **Checkpoint**: Run `./gradlew test` - all tests pass.

## Part 2: Frontend Implementation

### Step 4: Update TypeScript Interfaces (10 min)

**File**: `src/frontend/src/services/accountVulnsService.ts`

Update interfaces to include optional severity fields:

```typescript
export interface AssetVulnCount {
  id: number;
  name: string;
  type: string;
  vulnerabilityCount: number;
  
  // NEW: Optional severity counts
  criticalCount?: number;
  highCount?: number;
  mediumCount?: number;
}

export interface AccountGroup {
  awsAccountId: string;
  assets: AssetVulnCount[];
  totalAssets: number;
  totalVulnerabilities: number;
  
  // NEW: Optional account-level severity totals
  totalCritical?: number;
  totalHigh?: number;
  totalMedium?: number;
}

export interface AccountVulnsSummary {
  accountGroups: AccountGroup[];
  totalAssets: number;
  totalVulnerabilities: number;
  
  // NEW: Optional global severity totals
  globalCritical?: number;
  globalHigh?: number;
  globalMedium?: number;
}
```

✅ **Checkpoint**: TypeScript compiles without errors.

### Step 5: Create Severity Badge Component (30 min)

**File**: `src/frontend/src/components/SeverityBadge.tsx`

Create new reusable component:

```tsx
import React from 'react';

export type SeverityLevel = 'CRITICAL' | 'HIGH' | 'MEDIUM';

interface SeverityBadgeProps {
  severity: SeverityLevel;
  count: number;
  className?: string;
}

const severityConfig = {
  CRITICAL: {
    bgClass: 'bg-danger',
    icon: 'bi-exclamation-triangle-fill',
    label: 'Critical',
    ariaLabel: 'Critical severity',
  },
  HIGH: {
    bgClass: 'bg-warning text-dark',
    icon: 'bi-arrow-up-circle-fill',
    label: 'High',
    ariaLabel: 'High severity',
  },
  MEDIUM: {
    bgClass: 'bg-info text-dark',
    icon: 'bi-dash-circle-fill',
    label: 'Medium',
    ariaLabel: 'Medium severity',
  },
};

/**
 * Severity badge component for displaying vulnerability counts by severity level.
 * 
 * Features:
 * - Color-coded badges (red/orange/yellow)
 * - Bootstrap Icons for accessibility
 * - Always displays count even if 0
 * - Accessible labels for screen readers
 */
const SeverityBadge: React.FC<SeverityBadgeProps> = ({ severity, count, className = '' }) {
  const config = severityConfig[severity];

  return (
    <span className={`badge ${config.bgClass} ${className}`} title={`${count} ${config.label} vulnerabilities`}>
      <i className={`bi ${config.icon} me-1`} aria-hidden="true"></i>
      <span className="visually-hidden">{config.ariaLabel}:</span>
      {config.label}: {count}
    </span>
  );
};

export default SeverityBadge;
```

✅ **Checkpoint**: Component renders correctly in isolation.

### Step 6: Update AccountVulnsView Component (45 min)

**File**: `src/frontend/src/components/AccountVulnsView.tsx`

Update summary cards to show global severity:

```tsx
{/* Summary Stats - UPDATE THIS SECTION */}
<div className="row mb-4">
  <div className="col-md-3">
    <div className="card text-center">
      <div className="card-body">
        <h5 className="card-title text-muted">AWS Accounts</h5>
        <p className="display-6">{summary.accountGroups.length}</p>
      </div>
    </div>
  </div>
  <div className="col-md-3">
    <div className="card text-center">
      <div className="card-body">
        <h5 className="card-title text-muted">Total Assets</h5>
        <p className="display-6">{summary.totalAssets}</p>
      </div>
    </div>
  </div>
  <div className="col-md-3">
    <div className="card text-center">
      <div className="card-body">
        <h5 className="card-title text-muted">Total Vulnerabilities</h5>
        <p className="display-6 text-danger">{summary.totalVulnerabilities}</p>
      </div>
    </div>
  </div>
  {/* NEW: Global severity breakdown */}
  <div className="col-md-3">
    <div className="card text-center">
      <div className="card-body">
        <h5 className="card-title text-muted">By Severity</h5>
        <div className="d-flex flex-column gap-1">
          <SeverityBadge severity="CRITICAL" count={summary.globalCritical ?? 0} />
          <SeverityBadge severity="HIGH" count={summary.globalHigh ?? 0} />
          <SeverityBadge severity="MEDIUM" count={summary.globalMedium ?? 0} />
        </div>
      </div>
    </div>
  </div>
</div>
```

Update account group header to show severity:

```tsx
{/* Account Groups - UPDATE CARD HEADER */}
{summary.accountGroups.map((group) => (
  <div key={group.awsAccountId} className="card mb-4">
    <div className="card-header bg-primary text-white">
      <div className="d-flex justify-content-between align-items-center">
        <h5 className="mb-0">
          <i className="bi bi-cloud-fill me-2"></i>
          AWS Account: {group.awsAccountId}
        </h5>
        {/* UPDATE: Add severity breakdown */}
        <div className="d-flex gap-2">
          <span className="badge bg-light text-dark">
            {group.totalAssets} asset{group.totalAssets !== 1 ? 's' : ''}
          </span>
          <SeverityBadge severity="CRITICAL" count={group.totalCritical ?? 0} className="bg-danger-subtle" />
          <SeverityBadge severity="HIGH" count={group.totalHigh ?? 0} className="bg-warning-subtle" />
          <SeverityBadge severity="MEDIUM" count={group.totalMedium ?? 0} className="bg-info-subtle" />
        </div>
      </div>
    </div>
    {/* ... rest of card ... */}
  </div>
))}
```

Don't forget to import SeverityBadge at the top:

```tsx
import SeverityBadge from './SeverityBadge';
```

### Step 7: Update AssetVulnTable Component (30 min)

**File**: `src/frontend/src/components/AssetVulnTable.tsx`

Add severity badges to each asset row. Assuming table structure, add a new column or inline display:

```tsx
{/* Modify existing table row to include severity badges */}
<tr key={asset.id}>
  <td>
    <a href={`/asset-details?id=${asset.id}`}>
      {asset.name}
    </a>
  </td>
  <td>{asset.type}</td>
  <td>
    <span className="badge bg-secondary">
      Total: {asset.vulnerabilityCount}
    </span>
  </td>
  {/* NEW: Severity badges column */}
  <td>
    <div className="d-flex gap-1">
      <SeverityBadge severity="CRITICAL" count={asset.criticalCount ?? 0} />
      <SeverityBadge severity="HIGH" count={asset.highCount ?? 0} />
      <SeverityBadge severity="MEDIUM" count={asset.mediumCount ?? 0} />
    </div>
  </td>
</tr>
```

Update table header:

```tsx
<thead>
  <tr>
    <th>Asset Name</th>
    <th>Type</th>
    <th>Total Vulnerabilities</th>
    <th>Severity Breakdown</th> {/* NEW */}
  </tr>
</thead>
```

✅ **Checkpoint**: Frontend displays severity badges correctly.

### Step 8: Update E2E Tests (30 min)

**File**: `tests/e2e/account-vulns.spec.ts`

Add tests for severity display:

```typescript
test('should display severity badges for each asset', async ({ page }) => {
  await page.goto('/account-vulns');
  
  // Wait for page to load
  await page.waitForSelector('.card');
  
  // Check that severity badges are visible
  const criticalBadges = page.locator('text=/Critical:/');
  await expect(criticalBadges.first()).toBeVisible();
  
  const highBadges = page.locator('text=/High:/');
  await expect(highBadges.first()).toBeVisible();
  
  const mediumBadges = page.locator('text=/Medium:/');
  await expect(mediumBadges.first()).toBeVisible();
});

test('should display account-level severity totals', async ({ page }) => {
  await page.goto('/account-vulns');
  
  // Check account header shows severity breakdown
  const accountHeader = page.locator('.card-header.bg-primary').first();
  await expect(accountHeader.locator('text=/Critical:/')).toBeVisible();
  await expect(accountHeader.locator('text=/High:/')).toBeVisible();
  await expect(accountHeader.locator('text=/Medium:/')).toBeVisible();
});

test('should display global severity summary', async ({ page }) => {
  await page.goto('/account-vulns');
  
  // Check summary cards show global severity
  const severityCard = page.locator('.card:has-text("By Severity")');
  await expect(severityCard).toBeVisible();
  
  await expect(severityCard.locator('text=/Critical:/')).toBeVisible();
  await expect(severityCard.locator('text=/High:/')).toBeVisible();
  await expect(severityCard.locator('text=/Medium:/')).toBeVisible();
});

test('should display 0 counts for severity badges', async ({ page }) => {
  // Test with asset that has 0 critical vulnerabilities
  await page.goto('/account-vulns');
  
  const badge = page.locator('text="Critical: 0"');
  await expect(badge.first()).toBeVisible();
});
```

✅ **Checkpoint**: Run `npm run test:e2e` - all tests pass.

## Part 3: Testing & Validation

### Manual Testing Checklist

- [ ] Backend unit tests pass: `./gradlew test`
- [ ] Backend contract tests pass: `./gradlew test --tests "*Contract*"`
- [ ] Backend starts without errors: `./gradlew run`
- [ ] Frontend E2E tests pass: `npm run test:e2e`
- [ ] Manual: Visit /account-vulns page
- [ ] Manual: Verify global severity summary displays
- [ ] Manual: Verify account group headers show severity totals
- [ ] Manual: Verify each asset row shows severity badges
- [ ] Manual: Verify 0 counts are displayed (not hidden)
- [ ] Manual: Test with multiple AWS accounts
- [ ] Manual: Test mobile responsiveness (320px width)
- [ ] Manual: Test color-blind mode (Chrome DevTools)

### Performance Testing

```bash
# Measure baseline
curl -w "@curl-format.txt" -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/account-vulns > baseline.json

# Measure with severity
curl -w "@curl-format.txt" -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/account-vulns > with-severity.json

# Compare response times and sizes
```

**Acceptance Criteria**:
- Response time increase ≤ 10%
- Response size increase ≤ 30%

### Troubleshooting

**Problem**: Severity counts don't sum to total
- **Solution**: Check logs for validation errors, verify query CASE logic

**Problem**: Badges not displaying
- **Solution**: Check Network tab for API response, verify TypeScript interfaces match

**Problem**: Performance regression
- **Solution**: Add index on vulnerabilities.severity, check query execution plan

## Deployment

### Pre-Deployment Checklist

- [ ] All tests passing (unit, contract, E2E)
- [ ] Code reviewed and approved
- [ ] API contract documented
- [ ] Performance benchmarks acceptable

### Deployment Steps

1. Deploy backend changes (DTOs + service)
2. Verify /api/account-vulns returns severity fields
3. Deploy frontend changes (components)
4. Smoke test in production

### Rollback Plan

If issues arise:
1. Revert frontend changes (severity display optional)
2. Revert backend changes (DTO fields nullable)
3. No data cleanup required

## Success Metrics

After deployment, verify:
- ✅ SC-001: Users identify critical vulns within 5 seconds
- ✅ SC-003: 100% accuracy in severity counts
- ✅ SC-004: ≤10% page load increase
- ✅ SC-008: Color-blind accessible

---

**Estimated Total Time**: 4-6 hours
**Difficulty**: Medium (modifying existing feature)
**Risk**: Low (backward compatible, optional fields)

**Questions?** See `research.md` and `data-model.md` for technical details.
