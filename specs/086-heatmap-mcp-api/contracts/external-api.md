# External REST API Contract: Heatmap

## GET /api/external/vulnerability-heatmap

**Authentication**: MCP API key (`X-MCP-API-Key` header) + User delegation (`X-MCP-User-Email` header)  
**CORS**: Enabled for configured allowed origins  
**Content-Type**: application/json

### Request Headers

| Header              | Required | Description |
|---------------------|----------|-------------|
| X-MCP-API-Key       | Yes      | Valid MCP API key |
| X-MCP-User-Email    | Yes      | Email of delegated user for access control |

### Response (200 OK)

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

### Response (401 Unauthorized)

Missing or invalid API key.

### Response (403 Forbidden)

API key valid but delegated user has no accessible assets.

### CORS Headers

```
Access-Control-Allow-Origin: <configured origin>
Access-Control-Allow-Methods: GET, OPTIONS
Access-Control-Allow-Headers: X-MCP-API-Key, X-MCP-User-Email, Content-Type
Access-Control-Max-Age: 3600
```
