# Research: Products Overview

**Feature**: 054-products-overview
**Date**: 2025-12-15

## Research Summary

All technical unknowns have been resolved through codebase exploration. No external research required as this feature extends existing patterns.

---

## Decision 1: Product Data Source

**Decision**: Extract unique product names from `Vulnerability.vulnerableProductVersions` field via SQL DISTINCT query.

**Rationale**:
- Field already exists and is indexed (`idx_vulnerability_product`)
- CrowdStrike imports populate this field with product/version strings
- No schema changes required
- Dynamic aggregation ensures product list stays current with vulnerability data

**Alternatives Considered**:
- Create separate Product entity → Rejected: unnecessary complexity, requires data sync
- Cache products in Redis → Rejected: overkill for expected scale, adds infrastructure dependency

---

## Decision 2: Access Control Pattern

**Decision**: Use existing `AssetFilterService.getAccessibleAssets()` pattern to filter systems by user permissions.

**Rationale**:
- Proven pattern used by CurrentVulnerabilitiesTable, OutdatedAssetsList
- Handles all 6 access vectors (ADMIN, workgroups, manual creator, scan uploader, AWS mappings, AD domain mappings)
- Consistent with unified access control model in CLAUDE.md

**Implementation Pattern** (from AssetFilterService.kt):
```kotlin
// ADMIN bypasses filtering
if (hasRole(authentication, "ADMIN")) {
    return allAssets
}
// Non-admin: combine workgroup + AWS + AD domain access
val workgroupAssets = assetRepository.findByWorkgroupsUsersId(...)
val awsAccountAssets = assetRepository.findByCloudAccountIdIn(awsAccountIds)
val domainAssets = assetRepository.findByAdDomainInIgnoreCase(userDomainsLowercase)
return (workgroupAssets + awsAccountAssets + domainAssets).distinctBy { it.id }
```

---

## Decision 3: Pagination Implementation

**Decision**: Server-side pagination with 50 items default, matching existing patterns.

**Rationale**:
- Specified in clarifications (FR-011)
- Matches OutdatedAssetsList and CurrentVulnerabilitiesTable patterns
- Required for performance with 10k+ assets

**Implementation Pattern** (from VulnerabilityManagementController.kt):
```kotlin
val pageNumber = maxOf(page ?: 0, 0)
val pageSize = minOf(maxOf(size ?: 50, 1), 500)
// ... call service with pageable
return PaginatedResponse(
    content = results,
    totalElements = page.totalElements,
    totalPages = page.totalPages,
    currentPage = pageNumber,
    pageSize = pageSize,
    hasNext = page.hasNext(),
    hasPrevious = page.hasPrevious()
)
```

---

## Decision 4: Product Search/Filter

**Decision**: Client-side filtering of pre-fetched product list (up to 500 products), with server fallback for larger datasets.

**Rationale**:
- ProductAutocomplete.tsx pattern fetches 100 products and filters client-side
- For this feature, fetch up to 500 products initially (covers most environments)
- Case-insensitive partial matching as specified (FR-010)
- Provides instant feedback without server round-trips

**Implementation Pattern** (from ProductAutocomplete.tsx):
```typescript
const filtered = allProducts.filter(product =>
    product.toLowerCase().includes(searchTerm.toLowerCase())
);
```

---

## Decision 5: Excel Export

**Decision**: Use Apache POI for Excel generation, following existing asset export pattern.

**Rationale**:
- Specified in clarifications (FR-009: Excel .xlsx format)
- Apache POI already in dependencies (poi 5.3)
- Matches existing export patterns in AssetController

**Implementation Pattern** (from existing exports):
```kotlin
@Get("/export")
@Produces("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
fun exportToExcel(...): HttpResponse<ByteArray> {
    val workbook = XSSFWorkbook()
    val sheet = workbook.createSheet("Products Systems")
    // ... populate rows
    val outputStream = ByteArrayOutputStream()
    workbook.write(outputStream)
    return HttpResponse.ok(outputStream.toByteArray())
        .header("Content-Disposition", "attachment; filename=\"product-systems.xlsx\"")
}
```

---

## Decision 6: Sidebar Menu Placement

**Decision**: Add "Products" as a new menu item within the Vulnerability Management expandable section.

**Rationale**:
- User explicitly requested placement under Vulnerability Management
- Role check: `userRoles.includes('ADMIN') || userRoles.includes('VULN') || userRoles.includes('SECCHAMPION')`
- Follows existing hasVuln pattern in Sidebar.tsx

**Implementation Pattern** (from Sidebar.tsx):
```tsx
{hasVuln && (
    <li>
        <div onClick={toggleVulnManagement} className="sidebar-section-header-clickable">
            VULNERABILITY MANAGEMENT
        </div>
        {vulnMenuOpen && (
            <ul className="list-unstyled ps-4">
                {/* ... existing items ... */}
                <li>
                    <a href="/products" className="d-flex align-items-center p-2 text-dark text-decoration-none rounded hover-bg-secondary">
                        <i className="bi bi-box-seam me-2"></i> Products
                    </a>
                </li>
            </ul>
        )}
    </li>
)}
```

---

## Decision 7: Query Optimization

**Decision**: Use two-phase query approach - products list query separate from systems-by-product query.

**Rationale**:
- Products list: `SELECT DISTINCT vulnerable_product_versions FROM vulnerability WHERE asset_id IN (accessible_asset_ids)`
- Systems by product: `SELECT DISTINCT a.* FROM asset a JOIN vulnerability v ON ... WHERE v.vulnerable_product_versions = :product`
- Separate queries allow caching of product list
- Index on `vulnerable_product_versions` supports efficient filtering

**SQL Patterns**:
```sql
-- Get distinct products for accessible assets
SELECT DISTINCT v.vulnerable_product_versions
FROM vulnerability v
WHERE v.asset_id IN (:accessibleAssetIds)
AND v.vulnerable_product_versions IS NOT NULL
ORDER BY v.vulnerable_product_versions ASC

-- Get assets by product with access control
SELECT DISTINCT a.id, a.name, a.ip, a.ad_domain
FROM asset a
JOIN vulnerability v ON v.asset_id = a.id
WHERE v.vulnerable_product_versions = :product
AND a.id IN (:accessibleAssetIds)
ORDER BY a.name ASC
```

---

## Existing Patterns to Reuse

| Pattern | Source File | Usage |
|---------|-------------|-------|
| Paginated response DTO | VulnerabilityExceptionDto.kt | PaginatedProductSystemsResponse |
| Access control | AssetFilterService.kt | Filter assets by user permissions |
| Table with pagination | OutdatedAssetsList.tsx | ProductsOverview component |
| Searchable dropdown | ProductAutocomplete.tsx | Product selector |
| Excel export | AssetController.kt | Export systems list |
| Sidebar menu item | Sidebar.tsx | Add Products link |
| API service | vulnerabilityManagementService.ts | productService.ts |

---

## No Remaining Unknowns

All NEEDS CLARIFICATION items have been resolved:
- Export format: Excel (.xlsx) ✓
- Pagination: Server-side, 50 items default ✓
- Access control: Existing AssetFilterService pattern ✓
- Product source: vulnerableProductVersions field ✓
