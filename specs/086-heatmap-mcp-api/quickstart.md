# Quickstart: Heatmap MCP and API Exposure

## Prerequisites

- Running secman backend with heatmap data populated
- MCP API key with VULNERABILITY_READ permission
- Delegated user email configured for the API key

## 1. Query Heatmap via MCP

```bash
curl -X POST https://secman.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: your-api-key" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "get_vulnerability_heatmap",
      "arguments": {}
    }
  }'
```

## 2. Refresh Heatmap via MCP (ADMIN only)

```bash
curl -X POST https://secman.example.com/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: your-api-key" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "refresh_vulnerability_heatmap",
      "arguments": {}
    }
  }'
```

## 3. Query Heatmap via External REST Endpoint

```bash
curl -X GET https://secman.example.com/api/external/vulnerability-heatmap \
  -H "X-MCP-API-Key: your-api-key" \
  -H "X-MCP-User-Email: user@example.com"
```

## 4. Embed in External Web Page

```javascript
const response = await fetch('https://secman.example.com/api/external/vulnerability-heatmap', {
  headers: {
    'X-MCP-API-Key': 'your-api-key',
    'X-MCP-User-Email': 'user@example.com'
  }
});
const heatmap = await response.json();
console.log(`Total: ${heatmap.summary.totalAssets}, Critical: ${heatmap.summary.redCount}`);
```

## Implementation Files

| Component | File |
|-----------|------|
| MCP Tool (query) | `src/backendng/.../mcp/tools/GetVulnerabilityHeatmapTool.kt` |
| MCP Tool (refresh) | `src/backendng/.../mcp/tools/RefreshVulnerabilityHeatmapTool.kt` |
| External REST controller | `src/backendng/.../controller/ExternalHeatmapController.kt` |
| Tool registry update | `src/backendng/.../mcp/McpToolRegistry.kt` |
| CORS config | `src/backendng/.../resources/application.yml` |
| MCP docs | `docs/MCP.md` |
