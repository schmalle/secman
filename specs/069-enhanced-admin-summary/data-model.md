# Data Model: Enhanced Admin Summary Email

**Feature**: 069-enhanced-admin-summary
**Date**: 2026-01-28

## Extended Entities

### SystemStatistics (modified)

The existing `SystemStatistics` data class in `AdminSummaryService` is extended with three new fields. No database schema changes — all data is derived from existing tables via read-only queries.

| Field | Type | Description |
|-------|------|-------------|
| userCount | Long | Total registered users (existing) |
| vulnerabilityCount | Long | Total active vulnerabilities (existing) |
| assetCount | Long | Total tracked assets (existing) |
| **vulnerabilityStatisticsUrl** | **String** | Constructed URL: `{baseUrl}/vulnerability-statistics` (new) |
| **topProducts** | **List\<ProductSummary\>** | Top 10 products by vulnerability count (new) |
| **topServers** | **List\<ServerSummary\>** | Top 10 servers by vulnerability count (new) |

### ProductSummary (new, inner data class)

Simplified projection of `MostVulnerableProductDto` for email display.

| Field | Type | Description |
|-------|------|-------------|
| name | String | Product name |
| vulnerabilityCount | Long | Total vulnerabilities affecting this product |

### ServerSummary (new, inner data class)

Simplified projection of `TopAssetByVulnerabilitiesDto` for email display.

| Field | Type | Description |
|-------|------|-------------|
| name | String | Server/asset name |
| vulnerabilityCount | Long | Total vulnerabilities on this server |

## Data Flow

```
VulnerabilityStatisticsService (existing queries, admin-level unfiltered)
  ├── getMostVulnerableProducts() → MostVulnerableProductDto
  │     → map to ProductSummary (name, count only)
  └── getTopAssetsByVulnerabilities() → TopAssetByVulnerabilitiesDto
        → map to ServerSummary (name, count only)

AppConfig.backend.baseUrl (existing config)
  → concatenate with "/vulnerability-statistics"

AdminSummaryService.getSystemStatistics()
  → returns extended SystemStatistics with all fields populated
  → passed to template rendering methods
  → template variables: ${vulnerabilityStatisticsUrl}, ${topProductsHtml}, ${topServersHtml}, etc.
```

## No Schema Changes

This feature adds no new database tables, columns, or indexes. All data is derived from existing entities:
- `vulnerability` table (for product/server counts)
- `asset` table (for server names)
- `users` table (for admin recipients — unchanged)
