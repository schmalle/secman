# Implementation Plan: Products Overview

**Branch**: `054-products-overview` | **Date**: 2025-12-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/054-products-overview/spec.md`

## Summary

Add a "Products" menu item to the Vulnerability Management sidebar section for users with VULN, SECCHAMPION, or ADMIN roles. The feature provides a searchable dropdown to select a product (extracted from CrowdStrike vulnerability data's `vulnerableProductVersions` field) and displays a paginated table of systems running that product with Name, IP Address, and Domain columns. Includes Excel export functionality.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), TypeScript (frontend)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA (backend); Astro 5.14, React 19, Bootstrap 5.3 (frontend)
**Storage**: MariaDB 11.4 (existing Vulnerability and Asset entities)
**Testing**: User-requested only per constitution
**Target Platform**: Web application
**Project Type**: Web (backend + frontend)
**Performance Goals**: Product list loads in <2s for 10k assets; search filters in <500ms for 5k products
**Constraints**: Server-side pagination (50 items/page default); Excel export for up to 10k systems in <5s
**Scale/Scope**: Up to 10,000 assets, 5,000 unique products

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | Uses existing @Secured annotations, RBAC check for VULN/SECCHAMPION/ADMIN roles, access control via AssetFilterService |
| III. API-First | PASS | RESTful endpoints with consistent error formats, follows existing patterns |
| IV. User-Requested Testing | PASS | No test planning included unless requested |
| V. RBAC | PASS | Endpoint secured with @Secured, frontend role checks, AssetFilterService for data filtering |
| VI. Schema Evolution | PASS | No schema changes required - uses existing entities |

## Project Structure

### Documentation (this feature)

```text
specs/054-products-overview/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── controller/
│   │   └── ProductController.kt          # NEW: Products API endpoints
│   ├── service/
│   │   └── ProductService.kt             # NEW: Product aggregation logic
│   ├── repository/
│   │   └── VulnerabilityRepository.kt    # MODIFY: Add product query methods
│   └── dto/
│       └── ProductDto.kt                 # NEW: Response DTOs

src/frontend/
├── src/
│   ├── pages/
│   │   └── products.astro                # NEW: Products page
│   ├── components/
│   │   └── ProductsOverview.tsx          # NEW: Main React component
│   ├── services/
│   │   └── productService.ts             # NEW: API client
│   └── components/
│       └── Sidebar.tsx                   # MODIFY: Add Products menu item
```

**Structure Decision**: Web application pattern - extends existing backend/frontend structure with new controller, service, page, and component files.

## Complexity Tracking

> No constitution violations. Feature uses existing patterns and entities.

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| No new entities | Aggregate from Vulnerability.vulnerableProductVersions | Avoids schema changes, products derived dynamically |
| Server-side pagination | 50 items default | Matches existing patterns (OutdatedAssetsList, CurrentVulnerabilitiesTable) |
| Excel export | Apache POI | Matches existing export patterns (assets, requirements) |
