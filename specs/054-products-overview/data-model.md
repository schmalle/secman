# Data Model: Products Overview

**Feature**: 054-products-overview
**Date**: 2025-12-15

## Overview

This feature does not introduce new database entities. It aggregates data from existing `Vulnerability` and `Asset` entities to provide a product-centric view.

---

## Existing Entities Used

### Vulnerability (Read-Only)

**Table**: `vulnerability`

| Field | Type | Description | Used By Feature |
|-------|------|-------------|-----------------|
| id | Long | Primary key | - |
| asset_id | Long | FK to asset | Join to get asset details |
| vulnerable_product_versions | VARCHAR(512) | Product/version string from CrowdStrike | **Primary data source for products** |
| import_timestamp | DATETIME | When vulnerability was imported | Filter for "current" vulnerabilities |

**Existing Index**: `idx_vulnerability_product` on `vulnerable_product_versions`

### Asset (Read-Only)

**Table**: `asset`

| Field | Type | Description | Used By Feature |
|-------|------|-------------|-----------------|
| id | Long | Primary key | Access control filtering |
| name | VARCHAR(255) | System name | Display in table |
| ip | VARCHAR(255) | IP address | Display in table |
| ad_domain | VARCHAR(255) | Active Directory domain | Display in table |
| cloud_account_id | VARCHAR(255) | AWS account ID | Access control |
| manual_creator_id | Long | FK to user | Access control |
| scan_uploader_id | Long | FK to user | Access control |

**Existing Indexes**: Multiple indexes support access control queries

---

## DTOs (New)

### ProductListResponse

Response for GET /api/products endpoint.

```kotlin
@Serdeable
data class ProductListResponse(
    val products: List<String>,      // Distinct product names, alphabetically sorted
    val totalCount: Int              // Total number of unique products
)
```

### ProductSystemDto

Represents a system running a specific product.

```kotlin
@Serdeable
data class ProductSystemDto(
    val assetId: Long,
    val name: String,                // Asset name
    val ip: String?,                 // IP address (nullable)
    val adDomain: String?            // AD domain (nullable)
)
```

### PaginatedProductSystemsResponse

Paginated response for GET /api/products/{product}/systems endpoint.

```kotlin
@Serdeable
data class PaginatedProductSystemsResponse(
    val content: List<ProductSystemDto>,
    val totalElements: Long,
    val totalPages: Int,
    val currentPage: Int,
    val pageSize: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val productName: String          // Echo back the selected product
)
```

---

## Query Patterns

### Get Distinct Products (with access control)

```sql
-- For non-ADMIN users
SELECT DISTINCT v.vulnerable_product_versions AS product
FROM vulnerability v
WHERE v.asset_id IN (:accessibleAssetIds)
  AND v.vulnerable_product_versions IS NOT NULL
  AND v.vulnerable_product_versions <> ''
ORDER BY product ASC

-- For ADMIN users (no filtering)
SELECT DISTINCT v.vulnerable_product_versions AS product
FROM vulnerability v
WHERE v.vulnerable_product_versions IS NOT NULL
  AND v.vulnerable_product_versions <> ''
ORDER BY product ASC
```

### Get Systems by Product (with access control and pagination)

```sql
-- For non-ADMIN users
SELECT DISTINCT a.id, a.name, a.ip, a.ad_domain
FROM asset a
INNER JOIN vulnerability v ON v.asset_id = a.id
WHERE v.vulnerable_product_versions = :product
  AND a.id IN (:accessibleAssetIds)
ORDER BY a.name ASC
LIMIT :pageSize OFFSET :offset

-- Count query for pagination
SELECT COUNT(DISTINCT a.id)
FROM asset a
INNER JOIN vulnerability v ON v.asset_id = a.id
WHERE v.vulnerable_product_versions = :product
  AND a.id IN (:accessibleAssetIds)
```

### Search Products (case-insensitive partial match)

```sql
SELECT DISTINCT v.vulnerable_product_versions AS product
FROM vulnerability v
WHERE v.asset_id IN (:accessibleAssetIds)
  AND v.vulnerable_product_versions IS NOT NULL
  AND LOWER(v.vulnerable_product_versions) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
ORDER BY product ASC
LIMIT 100
```

---

## Access Control Integration

The feature uses `AssetFilterService.getAccessibleAssets(authentication)` to get the set of asset IDs the current user can access. This set is then passed to repository queries as the `:accessibleAssetIds` parameter.

**Access Control Flow**:
1. Controller receives authenticated request
2. Call `assetFilterService.getAccessibleAssets(authentication)` to get accessible asset IDs
3. Pass asset IDs to service/repository for filtering
4. ADMIN users bypass filtering (empty accessibleAssetIds = no filter applied)

---

## No Schema Migrations Required

This feature operates entirely on existing data structures. No Flyway migrations or Hibernate schema changes needed.
