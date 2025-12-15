# Quickstart: Products Overview

**Feature**: 054-products-overview
**Date**: 2025-12-15

## Prerequisites

- SECMAN backend running (`./gradlew run`)
- SECMAN frontend running (`npm run dev`)
- Vulnerability data imported (CrowdStrike import with `vulnerableProductVersions` populated)
- User with ADMIN, VULN, or SECCHAMPION role

## Implementation Order

### Phase 1: Backend API

1. **Create DTOs** (`src/backendng/src/main/kotlin/com/secman/dto/ProductDto.kt`)
   - ProductListResponse
   - ProductSystemDto
   - PaginatedProductSystemsResponse

2. **Add Repository Methods** (`VulnerabilityRepository.kt`)
   - `findDistinctProducts(accessibleAssetIds: Set<Long>): List<String>`
   - `findDistinctProductsFiltered(accessibleAssetIds: Set<Long>, searchTerm: String): List<String>`

3. **Add Repository Methods** (`AssetRepository.kt`)
   - `findByProductWithPagination(product: String, accessibleAssetIds: Set<Long>, pageable: Pageable): Page<Asset>`
   - `countByProduct(product: String, accessibleAssetIds: Set<Long>): Long`

4. **Create Service** (`src/backendng/src/main/kotlin/com/secman/service/ProductService.kt`)
   - `getProducts(authentication, searchTerm?): ProductListResponse`
   - `getProductSystems(authentication, product, page, size): PaginatedProductSystemsResponse`
   - `exportProductSystems(authentication, product): ByteArray`

5. **Create Controller** (`src/backendng/src/main/kotlin/com/secman/controller/ProductController.kt`)
   - `GET /api/products` - List products
   - `GET /api/products/{product}/systems` - Get systems by product
   - `GET /api/products/{product}/systems/export` - Export to Excel

### Phase 2: Frontend

6. **Create API Service** (`src/frontend/src/services/productService.ts`)
   - `getProducts(search?): Promise<ProductListResponse>`
   - `getProductSystems(product, page?, size?): Promise<PaginatedProductSystemsResponse>`
   - `exportProductSystems(product): Promise<Blob>`

7. **Create Page Component** (`src/frontend/src/components/ProductsOverview.tsx`)
   - Searchable product dropdown
   - Paginated systems table
   - Export button
   - Empty states

8. **Create Astro Page** (`src/frontend/src/pages/products.astro`)
   - Layout wrapper
   - ProductsOverview client:load

9. **Update Sidebar** (`src/frontend/src/components/Sidebar.tsx`)
   - Add "Products" link under Vulnerability Management section

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/products` | List unique products |
| GET | `/api/products?search=apache` | Search products |
| GET | `/api/products/{product}/systems` | Get systems for product |
| GET | `/api/products/{product}/systems?page=0&size=50` | Paginated systems |
| GET | `/api/products/{product}/systems/export` | Export to Excel |

## Testing Checklist

- [ ] Products dropdown shows unique products from vulnerability data
- [ ] Product search filters results (case-insensitive)
- [ ] Selecting product shows systems in paginated table
- [ ] Pagination controls work (next/prev/page numbers)
- [ ] Export downloads Excel file with correct data
- [ ] Non-admin users only see accessible systems
- [ ] Empty state shows when no products available
- [ ] Empty state shows when no systems for selected product
- [ ] Sidebar shows "Products" for VULN/SECCHAMPION/ADMIN roles
- [ ] Sidebar hides "Products" for other roles

## Key Files to Modify/Create

### Backend (New)
- `src/backendng/src/main/kotlin/com/secman/dto/ProductDto.kt`
- `src/backendng/src/main/kotlin/com/secman/service/ProductService.kt`
- `src/backendng/src/main/kotlin/com/secman/controller/ProductController.kt`

### Backend (Modify)
- `src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt`
- `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`

### Frontend (New)
- `src/frontend/src/services/productService.ts`
- `src/frontend/src/components/ProductsOverview.tsx`
- `src/frontend/src/pages/products.astro`

### Frontend (Modify)
- `src/frontend/src/components/Sidebar.tsx`

## Patterns to Follow

| Pattern | Reference File |
|---------|----------------|
| Controller with @Secured | `VulnerabilityManagementController.kt` |
| Paginated response | `VulnerabilityExceptionDto.kt` |
| Access control filtering | `AssetFilterService.kt` |
| Excel export | `AssetController.kt` (exportAssets) |
| React table with pagination | `OutdatedAssetsList.tsx` |
| Searchable dropdown | `ProductAutocomplete.tsx` |
| API service | `vulnerabilityManagementService.ts` |
