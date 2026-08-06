# MCP Tool Contract: get_overdue_assets

**Tool Name**: `get_overdue_assets`
**Operation**: READ
**Spec Reference**: FR-001 through FR-005

## Description

Get assets with overdue vulnerabilities. Respects workgroup-based access control.

## Access Control

- **Requires User Delegation**: Yes
- **Allowed Roles**: ADMIN, VULN
- **Access Scope**:
  - ADMIN sees all overdue assets
  - VULN sees only assets from assigned workgroups

## Input Schema

```json
{
  "type": "object",
  "properties": {
    "page": {
      "type": "number",
      "description": "Page number (0-indexed, default: 0)"
    },
    "size": {
      "type": "number",
      "description": "Page size (default: 20, max: 100)"
    },
    "minSeverity": {
      "type": "string",
      "enum": ["CRITICAL", "HIGH", "MEDIUM", "LOW"],
      "description": "Minimum severity filter"
    },
    "searchTerm": {
      "type": "string",
      "description": "Search by asset name (case-insensitive)"
    }
  }
}
```

## Response Format

### Success Response

```json
{
  "assets": [
    {
      "id": 123,
      "assetId": 456,
      "name": "server-prod-01",
      "ip": "192.168.1.100",
      "type": "SERVER",
      "totalVulnerabilities": 25,
      "criticalCount": 3,
      "highCount": 8,
      "mediumCount": 10,
      "lowCount": 4,
      "oldestVulnDays": 45,
      "maxSeverity": "CRITICAL"
    }
  ],
  "totalElements": 150,
  "totalPages": 8,
  "page": 0,
  "size": 20
}
```

### Error Responses

| Code | Message |
|------|---------|
| `DELEGATION_REQUIRED` | "User Delegation must be enabled to use this tool" |
| `ROLE_REQUIRED` | "ADMIN or VULN role required to view overdue assets" |
| `EXECUTION_ERROR` | "Failed to retrieve overdue assets: {message}" |

## Implementation Notes

- Delegates to `OutdatedAssetService.getOutdatedAssets()`
- Creates minimal `Authentication` adapter from `McpExecutionContext`
- Returns paginated results from `OutdatedAssetMaterializedView`
