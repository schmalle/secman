# MCP Tool Contracts: Heatmap

## Tool: get_vulnerability_heatmap

**Permission**: VULNERABILITY_READ  
**Operation**: READ  
**User Delegation**: Required (X-MCP-User-Email mandatory)

### Input Schema

```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

No parameters — returns all accessible heatmap entries for the delegated user.

### Response (Success)

```json
{
  "entries": [
    {
      "assetId": "<number>",
      "assetName": "<string>",
      "assetType": "<string>",
      "criticalCount": "<number>",
      "highCount": "<number>",
      "mediumCount": "<number>",
      "lowCount": "<number>",
      "totalCount": "<number>",
      "heatLevel": "RED | YELLOW | GREEN"
    }
  ],
  "summary": {
    "totalAssets": "<number>",
    "redCount": "<number>",
    "yellowCount": "<number>",
    "greenCount": "<number>"
  },
  "lastCalculatedAt": "<ISO datetime string | null>"
}
```

### Access Control

- ADMIN/SECCHAMPION: See all heatmap entries
- Other users: See only entries for assets in their workgroups, owned assets, uploaded scans, matching AWS/AD mappings

---

## Tool: refresh_vulnerability_heatmap

**Permission**: VULNERABILITY_WRITE  
**Operation**: WRITE  
**User Delegation**: Required (X-MCP-User-Email mandatory)  
**Role Requirement**: ADMIN only

### Input Schema

```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

No parameters — triggers full heatmap recalculation.

### Response (Success)

```json
{
  "message": "Heatmap refreshed successfully",
  "entriesCreated": "<number>"
}
```

### Response (Error — insufficient permissions)

```json
{
  "code": "FORBIDDEN",
  "message": "Only ADMIN users can refresh the heatmap"
}
```
