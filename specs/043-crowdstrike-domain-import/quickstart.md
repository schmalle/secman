# Quickstart Guide: CrowdStrike Domain Import Enhancement

**Feature**: 043-crowdstrike-domain-import
**Target Audience**: Developers implementing this feature
**Estimated Time**: 2-4 hours implementation + testing

---

## Overview

This feature enhances the existing CrowdStrike vulnerability import to automatically capture Active Directory domain information from the Falcon API, store it in the asset table, implement smart field-level updates to prevent data loss, enable manual domain editing in the UI, and display domain discovery statistics after imports.

**Key Changes**:
1. Extract `machine_domain` from CrowdStrike API responses
2. Store normalized domain in `Asset.adDomain` field (lowercase)
3. Track unique domains during import in a `Set<String>`
4. Add `uniqueDomainCount` and `discoveredDomains` fields to `ImportResultDto`
5. Display domain statistics in import results UI
6. Implement smart update logic (only modify changed fields during re-import)

---

## Prerequisites

- CrowdStrike API integration working (Feature 032)
- Asset entity with `adDomain` column (Feature 042)
- Asset Management UI with edit form (existing)
- Development environment: Kotlin 2.2.21, Node.js 18+, MariaDB 11.4

**Verify Prerequisites**:
```bash
# Backend builds successfully
./gradlew :backendng:build

# Frontend builds successfully
cd src/frontend && npm run build

# Database schema includes ad_domain column
mysql -u root -p secman -e "DESCRIBE asset" | grep ad_domain
```

---

## Implementation Steps

### Step 1: Enhance CrowdStrike API Response Mapping (Backend)

**File**: `src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClientImpl.kt`

**Location**: `mapResponseToDtos()` method (around line 1174)

**Add domain extraction**:
```kotlin
// After hostname extraction
val hostInfo = vuln["host_info"] as? Map<*, *>
val hostname = hostInfo?.get("hostname")?.toString() ?: "unknown"

// ADD THIS: Extract domain
val domain = hostInfo?.get("machine_domain")?.toString()
```

**Pass domain to DTO** (if using transport DTO):
```kotlin
CrowdStrikeVulnerabilityDto(
    hostname = hostname,
    ip = ip,
    cveId = cveId,
    severity = severity,
    domain = domain  // ADD THIS FIELD
)
```

---

### Step 2: Update ImportResultDto (Backend)

**File**: `src/backendng/src/main/kotlin/com/secman/dto/ImportResultDto.kt`

**Add fields**:
```kotlin
@Serdeable
data class ImportResultDto(
    val totalAssets: Int,
    val totalVulnerabilities: Int,
    val importedCount: Int,
    val skippedCount: Int,
    val errors: List<String>,
    // ADD THESE:
    val uniqueDomainCount: Int = 0,
    val discoveredDomains: List<String> = emptyList()
)
```

---

### Step 3: Implement Domain Statistics Tracking (Backend)

**File**: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt`

**Add class-level set**:
```kotlin
@Singleton
class CrowdStrikeVulnerabilityImportService(
    private val assetMergeService: AssetMergeService,
    // ... other dependencies
) {
    private val uniqueDomains = mutableSetOf<String>()

    @Transactional
    fun importVulnerabilities(...): ImportResultDto {
        uniqueDomains.clear()  // Reset for each import

        // ... existing import logic ...

        vulnerabilities.forEach { vuln ->
            val asset = assetMergeService.findOrCreateAsset(
                hostname = vuln.hostname,
                ip = vuln.ip,
                adDomain = vuln.domain  // Pass domain
            )

            // Track unique domains
            asset.adDomain?.let { domain ->
                uniqueDomains.add(domain.trim().uppercase())
            }

            // ... rest of vulnerability processing ...
        }

        return ImportResultDto(
            totalAssets = assets.size,
            totalVulnerabilities = vulnerabilities.size,
            importedCount = imported,
            skippedCount = skipped,
            errors = errors,
            uniqueDomainCount = uniqueDomains.size,
            discoveredDomains = uniqueDomains.sorted()
        )
    }
}
```

---

### Step 4: Add Domain Normalization to Asset Entity (Backend)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**Add lifecycle hook**:
```kotlin
@Entity
@Table(name = "asset")
class Asset(
    // ... existing fields ...

    @Column(nullable = true, length = 255)
    @Pattern(regexp = "^[a-zA-Z0-9.-]+$")
    var adDomain: String?,

    // ... other fields ...
) {
    // ADD THIS METHOD:
    @PrePersist
    @PreUpdate
    fun normalizeDomain() {
        adDomain = adDomain?.trim()?.lowercase()
    }

    // ... existing methods ...
}
```

---

### Step 5: Implement Smart Update Logic (Backend)

**File**: `src/backendng/src/main/kotlin/com/secman/service/AssetMergeService.kt`

**Enhance mergeAssetData() method**:
```kotlin
fun mergeAssetData(
    asset: Asset,
    newIp: String?,
    newGroups: String?,
    newAdDomain: String?,  // ADD THIS PARAMETER
    newCloudAccountId: String?,
    // ... other parameters
): Asset {
    var updated = false

    // Existing field comparisons...
    if (newIp != null && newIp != asset.ip) {
        asset.ip = newIp
        updated = true
    }

    // ADD THIS: Domain comparison
    if (newAdDomain != null && newAdDomain.isNotBlank() &&
        newAdDomain.lowercase() != asset.adDomain?.lowercase()) {
        log.debug("Updating AD domain for {}: {} -> {}",
            asset.name, asset.adDomain, newAdDomain)
        asset.adDomain = newAdDomain
        updated = true
    }

    // ... other field comparisons ...

    // Only write to DB if changed
    if (updated) {
        asset.updatedAt = LocalDateTime.now()
        return assetRepository.update(asset)
    }

    return asset  // No DB write
}
```

---

### Step 6: Display Domain Statistics in UI (Frontend)

**File**: `src/frontend/src/components/CrowdStrikeImportResults.tsx` (or similar)

**Add domain statistics section**:
```tsx
interface ImportResult {
  totalAssets: number;
  totalVulnerabilities: number;
  uniqueDomainCount: number;
  discoveredDomains: string[];
  errors: string[];
}

function CrowdStrikeImportResults({ result }: { result: ImportResult }) {
  return (
    <div className="import-results">
      <h4>Import Summary</h4>
      <ul>
        <li>Assets: {result.totalAssets}</li>
        <li>Vulnerabilities: {result.totalVulnerabilities}</li>
      </ul>

      {/* ADD THIS SECTION */}
      <h5>Domain Discovery</h5>
      <div className="domain-stats">
        <p><strong>Unique Domains:</strong> {result.uniqueDomainCount}</p>
        {result.uniqueDomainCount > 0 && (
          <p><strong>Domains:</strong> {result.discoveredDomains.join(', ')}</p>
        )}
        {result.uniqueDomainCount === 0 && (
          <p className="text-muted">No domains discovered</p>
        )}
      </div>

      {/* Existing error display */}
    </div>
  );
}
```

---

### Step 7: Verify Domain Field in Asset Edit Form (Frontend)

**File**: `src/frontend/src/components/AssetManagement.tsx`

**Ensure domain field is editable**:
```tsx
<div className="mb-3">
  <label htmlFor="adDomain" className="form-label">
    Active Directory Domain <span className="text-muted">(optional)</span>
  </label>
  <input
    type="text"
    id="adDomain"
    className={`form-control ${domainError ? 'is-invalid' : ''}`}
    value={asset.adDomain || ''}
    onChange={(e) => {
      setAsset({ ...asset, adDomain: e.target.value });
      validateDomain(e.target.value);
    }}
    placeholder="e.g., contoso, sales.domain"
  />
  {domainError && (
    <div className="invalid-feedback">{domainError}</div>
  )}
  <small className="form-text text-muted">
    Enter the Active Directory domain name (alphanumeric, dots, hyphens)
  </small>
</div>

{/* Add validation function */}
<script>
function validateDomain(value: string): boolean {
  if (!value) return true;  // Optional field

  const regex = /^[a-zA-Z0-9.-]+$/;
  if (!regex.test(value)) {
    setDomainError('Domain must contain only letters, numbers, dots, and hyphens');
    return false;
  }

  if (value.startsWith('.') || value.endsWith('.')) {
    setDomainError('Domain cannot start or end with a dot');
    return false;
  }

  setDomainError('');
  return true;
}
</script>
```

---

## Testing Checklist

### Backend Tests

1. **Domain Extraction Test** (`CrowdStrikeImportIntegrationTest.kt`):
```kotlin
@Test
fun `should extract domain from Falcon API response`() {
    // Given: API response with machine_domain
    val response = mockFalconApiResponse(
        hostname = "server1",
        domain = "contoso.com"
    )

    // When: Import processed
    val result = importService.importVulnerabilities(response)

    // Then: Asset has domain
    val asset = assetRepository.findByName("server1").get()
    asset.adDomain shouldEqual "contoso.com"
}
```

2. **Smart Update Test** (`AssetSmartUpdateTest.kt`):
```kotlin
@Test
fun `should only update domain when changed`() {
    // Given: Asset with no domain
    val asset = createAsset(name = "server1", domain = null)

    // When: Import with domain
    importService.findOrCreateAsset(hostname = "server1", domain = "contoso")

    // Then: Domain updated
    asset.adDomain shouldEqual "contoso"

    // When: Re-import with same domain
    val updatedAtBefore = asset.updatedAt
    importService.findOrCreateAsset(hostname = "server1", domain = "contoso")

    // Then: No database write
    asset.updatedAt shouldEqual updatedAtBefore
}
```

3. **Domain Statistics Test** (`ImportStatisticsTest.kt`):
```kotlin
@Test
fun `should track unique domains during import`() {
    // Given: Multiple assets with overlapping domains
    val response = mockApiResponse(
        listOf("server1" to "contoso", "server2" to "contoso", "server3" to "fabrikam")
    )

    // When: Import executed
    val result = importService.importVulnerabilities(response)

    // Then: Statistics accurate
    result.uniqueDomainCount shouldEqual 2
    result.discoveredDomains shouldEqual listOf("CONTOSO", "FABRIKAM")
}
```

### Frontend Tests

1. **Domain Display Test** (`crowdstrike-import.spec.ts`):
```typescript
test('should display domain statistics after import', async ({ page }) => {
  await page.goto('/crowdstrike-import');
  await page.click('#runImportButton');

  await expect(page.locator('.domain-stats')).toContainText('Unique Domains: 5');
  await expect(page.locator('.domain-stats')).toContainText('CONTOSO, FABRIKAM');
});
```

2. **Domain Edit Test** (`asset-domain-edit.spec.ts`):
```typescript
test('should validate domain input in asset form', async ({ page }) => {
  await page.goto('/assets/42/edit');

  // Valid domain
  await page.fill('#adDomain', 'contoso');
  await expect(page.locator('.invalid-feedback')).not.toBeVisible();

  // Invalid domain
  await page.fill('#adDomain', '.contoso');
  await expect(page.locator('.invalid-feedback')).toContainText('cannot start with a dot');
});
```

---

## Build & Run

### Backend

```bash
# Build
./gradlew :backendng:build

# Run
./gradlew :backendng:run
```

### Frontend

```bash
# Build
cd src/frontend && npm run build

# Dev server
npm run dev
```

### Full Stack

```bash
# Terminal 1: Backend
./gradlew :backendng:run

# Terminal 2: Frontend
cd src/frontend && npm run dev
```

---

## Verification Steps

1. **Import CrowdStrike Data**:
   - Navigate to CrowdStrike import page
   - Run import with test hostname
   - Verify import summary shows domain statistics

2. **Check Asset Table**:
   ```sql
   SELECT name, ad_domain FROM asset WHERE ad_domain IS NOT NULL LIMIT 10;
   ```
   - Should see lowercase domain names

3. **Manual Edit**:
   - Open Asset Management
   - Edit an asset
   - Add/change domain field
   - Verify validation and save

4. **Re-import Test**:
   - Import same assets again
   - Verify `updatedAt` doesn't change for unchanged assets
   - Verify only domain updates if it was missing before

---

## Troubleshooting

### Issue: Domain not extracted from API

**Check**:
- Verify `machine_domain` field exists in API response (log raw JSON)
- Check `mapResponseToDtos()` has domain extraction code
- Verify domain is passed to `findOrCreateAsset()`

**Solution**: Add debug logging:
```kotlin
log.debug("Extracted domain: {} from hostname: {}", domain, hostname)
```

### Issue: Domain statistics show 0 even with domains

**Check**:
- Verify `uniqueDomains.add()` is called after asset merge
- Check domain is not null before adding to set
- Verify `uniqueDomains` is cleared at start of each import

**Solution**: Log domain collection:
```kotlin
asset.adDomain?.let { domain ->
    log.debug("Tracking domain: {}", domain)
    uniqueDomains.add(domain.uppercase())
}
```

### Issue: Smart update not working (all fields updated)

**Check**:
- Verify `updated` flag is set correctly
- Check field comparison uses `!=` not identity check
- Ensure `repository.update()` only called when `updated == true`

**Solution**: Add logging for each field comparison:
```kotlin
if (newDomain != null && newDomain != asset.adDomain) {
    log.debug("Domain changed: {} -> {}", asset.adDomain, newDomain)
    updated = true
}
```

---

## Next Steps

After implementation:
1. Run `/speckit.tasks` to generate task breakdown
2. Implement tasks in dependency order
3. Write tests (JUnit for backend, Playwright for frontend)
4. Create pull request with constitutional compliance check
5. Deploy to staging for E2E validation

---

## Additional Resources

- [CrowdStrike Falcon API Docs](https://www.falconpy.io/)
- [Feature 032: CrowdStrike Import](../032-servers-query-import/)
- [Feature 042: Domain-Based Access Control](../042-future-user-mappings/)
- [OpenAPI Contracts](./contracts/)
- [Data Model](./data-model.md)
- [Research Decisions](./research.md)
