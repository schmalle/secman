# Quickstart: MCP Lense Reports

**Feature Branch**: `069-mcp-lense-reports`
**Date**: 2026-01-27

## Overview

This feature adds 4 new MCP tools to expose Lense UI reports:

| Tool | Purpose | Permission |
|------|---------|------------|
| `get_risk_assessment_summary` | Risk assessment overview with metrics | ASSESSMENTS_READ |
| `get_risk_mitigation_status` | Open risks and mitigation tracking | ASSESSMENTS_READ |
| `get_vulnerability_statistics` | Vulnerability distribution and top assets | VULNERABILITIES_READ |
| `get_exception_statistics` | Exception request statistics | VULNERABILITIES_READ |

## Prerequisites

- Running secman backend with MCP enabled
- Valid MCP API key with required permissions
- (Optional) User delegation configured for access-controlled queries

## Quick Test

### 1. Get Risk Assessment Summary

```bash
# Via MCP server
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: your-api-key" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "get_risk_assessment_summary",
      "arguments": {}
    },
    "id": 1
  }'
```

Expected response:
```json
{
  "assessmentSummary": {
    "total": 25,
    "statusBreakdown": {"STARTED": 5, "IN_PROGRESS": 8, "COMPLETED": 12}
  },
  "riskSummary": {
    "total": 45,
    "statusBreakdown": {"OPEN": 20, "MITIGATED": 15, "CLOSED": 10},
    "riskLevelBreakdown": {"Low": 10, "Medium": 20, "High": 10, "Critical": 5}
  },
  "assetCoverage": {
    "totalAssets": 100,
    "assetsWithAssessments": 40,
    "coveragePercentage": 40.0
  },
  "recentAssessments": [...],
  "highPriorityRisks": [...]
}
```

### 2. Get Risk Mitigation Status

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: your-api-key" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "get_risk_mitigation_status",
      "arguments": {"status": "OPEN"}
    },
    "id": 1
  }'
```

### 3. Get Vulnerability Statistics

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: your-api-key" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "get_vulnerability_statistics",
      "arguments": {"domain": "corp.example.com"}
    },
    "id": 1
  }'
```

### 4. Get Exception Statistics

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: your-api-key" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "get_exception_statistics",
      "arguments": {
        "start_date": "2026-01-01",
        "end_date": "2026-01-27"
      }
    },
    "id": 1
  }'
```

## With User Delegation

Add the `X-MCP-User-Email` header to filter results based on user permissions:

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: your-api-key" \
  -H "X-MCP-User-Email: user@example.com" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "get_vulnerability_statistics",
      "arguments": {}
    },
    "id": 1
  }'
```

## Development Setup

### Build and Run

```bash
# Build backend
./gradlew build

# Run backend
./gradlew :backendng:run

# Verify MCP tools are registered
curl http://localhost:8080/mcp \
  -H "X-MCP-API-Key: your-api-key" \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

### Key Files

| Component | Path |
|-----------|------|
| MCP Tools | `src/backendng/src/main/kotlin/com/secman/mcp/tools/Get*Tool.kt` |
| Report Service | `src/backendng/src/main/kotlin/com/secman/service/ReportService.kt` |
| DTOs | `src/backendng/src/main/kotlin/com/secman/dto/reports/*.kt` |
| Tool Registry | `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt` |
| Documentation | `docs/MCP.md` |

## Verification Checklist

- [ ] All 4 tools appear in `tools/list` response
- [ ] Tools return data matching Lense UI reports
- [ ] User delegation filters results correctly
- [ ] Domain filter works for vulnerability statistics
- [ ] Date range filter works for exception statistics
- [ ] Response time under 5 seconds
- [ ] Documentation updated in docs/MCP.md
