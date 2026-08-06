# Data Model: Heatmap MCP and API Exposure

**Date**: 2026-04-10  
**Feature**: 086-heatmap-mcp-api

## Existing Entities (no changes)

### AssetHeatmapEntry

Pre-calculated heatmap data per asset. **No schema changes required** — this feature exposes existing data through new channels.

| Field              | Type          | Nullable | Description |
|--------------------|---------------|----------|-------------|
| id                 | BIGINT (PK)   | No       | Auto-generated ID |
| asset_id           | BIGINT (UK)   | No       | Foreign key to asset |
| asset_name         | VARCHAR(255)  | No       | Denormalized asset name |
| asset_type         | VARCHAR(50)   | No       | Denormalized asset type |
| critical_count     | INT           | No       | CRITICAL severity vulnerabilities |
| high_count         | INT           | No       | HIGH severity vulnerabilities |
| medium_count       | INT           | No       | MEDIUM severity vulnerabilities |
| low_count          | INT           | No       | LOW severity vulnerabilities |
| total_count        | INT           | No       | Total vulnerabilities |
| heat_level         | VARCHAR(10)   | No       | RED, YELLOW, or GREEN |
| cloud_account_id   | VARCHAR(255)  | Yes      | For access control filtering |
| ad_domain          | VARCHAR(255)  | Yes      | For access control filtering |
| owner              | VARCHAR(255)  | Yes      | For access control filtering |
| workgroup_ids      | VARCHAR(500)  | Yes      | Comma-separated, for access control |
| manual_creator_id  | BIGINT        | Yes      | For access control filtering |
| scan_uploader_id   | BIGINT        | Yes      | For access control filtering |
| last_calculated_at | DATETIME      | No       | When heatmap was last recalculated |

### Heat Level Rules

- **RED**: critical_count > 0 OR high_count > 100
- **YELLOW**: high_count > 0 (and not RED)
- **GREEN**: no CRITICAL or HIGH vulnerabilities

## New Entities

None. This feature adds no database tables or columns. It exposes existing data via MCP tools and a CORS-enabled REST endpoint.

## Response Shapes

### MCP `get_vulnerability_heatmap` Response

```json
{
  "entries": [
    {
      "assetId": 42,
      "assetName": "server-01",
      "assetType": "SERVER",
      "criticalCount": 3,
      "highCount": 15,
      "mediumCount": 40,
      "lowCount": 12,
      "totalCount": 70,
      "heatLevel": "RED"
    }
  ],
  "summary": {
    "totalAssets": 1962,
    "redCount": 1002,
    "yellowCount": 960,
    "greenCount": 0
  },
  "lastCalculatedAt": "2026-04-10T19:11:00"
}
```

### MCP `refresh_vulnerability_heatmap` Response

```json
{
  "message": "Heatmap refreshed successfully",
  "entriesCreated": 1962
}
```

### External REST `GET /api/external/vulnerability-heatmap` Response

Same JSON shape as the MCP `get_vulnerability_heatmap` response (identical to existing `GET /api/vulnerability-heatmap`).
