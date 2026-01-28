# MCP Tool Contracts: Lense Reports

**Feature Branch**: `069-mcp-lense-reports`
**Date**: 2026-01-27

## Tool 1: get_risk_assessment_summary

Retrieves aggregated risk assessment summary report.

### Input Schema

```json
{
  "type": "object",
  "properties": {},
  "required": []
}
```

No input parameters - returns full summary for accessible assets.

### Output Schema

```json
{
  "type": "object",
  "properties": {
    "assessmentSummary": {
      "type": "object",
      "properties": {
        "total": { "type": "integer" },
        "statusBreakdown": {
          "type": "object",
          "additionalProperties": { "type": "integer" }
        }
      }
    },
    "riskSummary": {
      "type": "object",
      "properties": {
        "total": { "type": "integer" },
        "statusBreakdown": {
          "type": "object",
          "additionalProperties": { "type": "integer" }
        },
        "riskLevelBreakdown": {
          "type": "object",
          "additionalProperties": { "type": "integer" }
        }
      }
    },
    "assetCoverage": {
      "type": "object",
      "properties": {
        "totalAssets": { "type": "integer" },
        "assetsWithAssessments": { "type": "integer" },
        "coveragePercentage": { "type": "number" }
      }
    },
    "recentAssessments": {
      "type": "array",
      "maxItems": 10,
      "items": {
        "type": "object",
        "properties": {
          "id": { "type": "integer" },
          "assetName": { "type": "string" },
          "status": { "type": "string" },
          "assessor": { "type": "string" },
          "startDate": { "type": "string", "format": "date" },
          "endDate": { "type": "string", "format": "date" }
        }
      }
    },
    "highPriorityRisks": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "id": { "type": "integer" },
          "name": { "type": "string" },
          "assetName": { "type": "string" },
          "riskLevel": { "type": "integer", "minimum": 1, "maximum": 4 },
          "riskLevelText": { "type": "string", "enum": ["Low", "Medium", "High", "Critical"] },
          "status": { "type": "string" },
          "owner": { "type": "string", "nullable": true },
          "severity": { "type": "string", "nullable": true },
          "deadline": { "type": "string", "format": "date", "nullable": true }
        }
      }
    }
  }
}
```

### Authorization

- **Operation**: READ
- **Permission**: ASSESSMENTS_READ
- **Access Control**: Filters to delegated user's accessible assets

### Error Codes

| Code | Message |
|------|---------|
| EXECUTION_ERROR | Failed to retrieve risk assessment summary: {details} |

---

## Tool 2: get_risk_mitigation_status

Retrieves risk mitigation status report with open risks tracking.

### Input Schema

```json
{
  "type": "object",
  "properties": {
    "status": {
      "type": "string",
      "description": "Filter by risk status (optional)",
      "enum": ["OPEN", "MITIGATED", "CLOSED"]
    }
  },
  "required": []
}
```

### Output Schema

```json
{
  "type": "object",
  "properties": {
    "summary": {
      "type": "object",
      "properties": {
        "totalOpenRisks": { "type": "integer" },
        "overdueRisks": { "type": "integer" },
        "unassignedRisks": { "type": "integer" }
      }
    },
    "risks": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "id": { "type": "integer" },
          "name": { "type": "string" },
          "description": { "type": "string", "nullable": true },
          "assetName": { "type": "string" },
          "riskLevel": { "type": "integer", "minimum": 1, "maximum": 4 },
          "riskLevelText": { "type": "string" },
          "status": { "type": "string" },
          "owner": { "type": "string", "nullable": true },
          "severity": { "type": "string", "nullable": true },
          "deadline": { "type": "string", "format": "date", "nullable": true },
          "isOverdue": { "type": "boolean" },
          "likelihood": { "type": "integer", "minimum": 1, "maximum": 5 },
          "impact": { "type": "integer", "minimum": 1, "maximum": 5 }
        }
      }
    }
  }
}
```

### Authorization

- **Operation**: READ
- **Permission**: ASSESSMENTS_READ
- **Access Control**: Filters to delegated user's accessible assets

### Error Codes

| Code | Message |
|------|---------|
| INVALID_STATUS | Invalid status filter: {value}. Must be OPEN, MITIGATED, or CLOSED |
| EXECUTION_ERROR | Failed to retrieve risk mitigation status: {details} |

---

## Tool 3: get_vulnerability_statistics

Retrieves aggregated vulnerability statistics from the Lense dashboard.

### Input Schema

```json
{
  "type": "object",
  "properties": {
    "domain": {
      "type": "string",
      "description": "Filter by AD domain (optional, case-insensitive)"
    }
  },
  "required": []
}
```

### Output Schema

```json
{
  "type": "object",
  "properties": {
    "severityDistribution": {
      "type": "object",
      "properties": {
        "critical": { "type": "integer" },
        "high": { "type": "integer" },
        "medium": { "type": "integer" },
        "low": { "type": "integer" },
        "unknown": { "type": "integer" },
        "total": { "type": "integer" }
      }
    },
    "mostCommonVulnerabilities": {
      "type": "array",
      "maxItems": 10,
      "items": {
        "type": "object",
        "properties": {
          "vulnerabilityId": { "type": "string" },
          "cvssSeverity": { "type": "string" },
          "occurrenceCount": { "type": "integer" },
          "affectedAssetCount": { "type": "integer" }
        }
      }
    },
    "mostVulnerableProducts": {
      "type": "array",
      "maxItems": 10,
      "items": {
        "type": "object",
        "properties": {
          "product": { "type": "string" },
          "vulnerabilityCount": { "type": "integer" },
          "affectedAssetCount": { "type": "integer" },
          "criticalCount": { "type": "integer" },
          "highCount": { "type": "integer" }
        }
      }
    },
    "topAssetsByVulnerabilities": {
      "type": "array",
      "maxItems": 10,
      "items": {
        "type": "object",
        "properties": {
          "assetId": { "type": "integer" },
          "assetName": { "type": "string" },
          "assetType": { "type": "string", "nullable": true },
          "assetIp": { "type": "string", "nullable": true },
          "totalVulnerabilityCount": { "type": "integer" },
          "criticalCount": { "type": "integer" },
          "highCount": { "type": "integer" },
          "mediumCount": { "type": "integer" },
          "lowCount": { "type": "integer" }
        }
      }
    },
    "vulnerabilitiesByAssetType": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "assetType": { "type": "string" },
          "assetCount": { "type": "integer" },
          "totalVulnerabilityCount": { "type": "integer" },
          "criticalCount": { "type": "integer" },
          "highCount": { "type": "integer" },
          "mediumCount": { "type": "integer" },
          "lowCount": { "type": "integer" },
          "averageVulnerabilitiesPerAsset": { "type": "number" }
        }
      }
    },
    "topServersByVulnerabilities": {
      "type": "array",
      "maxItems": 50,
      "items": {
        "type": "object",
        "properties": {
          "assetId": { "type": "integer" },
          "serverName": { "type": "string" },
          "serverIp": { "type": "string", "nullable": true },
          "totalVulnerabilityCount": { "type": "integer" },
          "criticalCount": { "type": "integer" },
          "highCount": { "type": "integer" }
        }
      }
    }
  }
}
```

### Authorization

- **Operation**: READ
- **Permission**: VULNERABILITIES_READ
- **Access Control**: Filters to delegated user's accessible assets + optional domain filter

### Error Codes

| Code | Message |
|------|---------|
| EXECUTION_ERROR | Failed to retrieve vulnerability statistics: {details} |

---

## Tool 4: get_exception_statistics

Retrieves vulnerability exception request statistics.

### Input Schema

```json
{
  "type": "object",
  "properties": {
    "start_date": {
      "type": "string",
      "format": "date",
      "description": "Start date for statistics range (optional, ISO-8601)"
    },
    "end_date": {
      "type": "string",
      "format": "date",
      "description": "End date for statistics range (optional, ISO-8601)"
    }
  },
  "required": []
}
```

### Output Schema

```json
{
  "type": "object",
  "properties": {
    "totalRequests": { "type": "integer" },
    "approvalRatePercent": { "type": "number", "nullable": true },
    "averageApprovalTimeHours": { "type": "number", "nullable": true },
    "requestsByStatus": {
      "type": "object",
      "properties": {
        "PENDING": { "type": "integer" },
        "APPROVED": { "type": "integer" },
        "REJECTED": { "type": "integer" },
        "EXPIRED": { "type": "integer" },
        "CANCELLED": { "type": "integer" }
      }
    },
    "topRequesters": {
      "type": "array",
      "maxItems": 10,
      "items": {
        "type": "object",
        "properties": {
          "username": { "type": "string" },
          "count": { "type": "integer" }
        }
      }
    },
    "topCVEs": {
      "type": "array",
      "maxItems": 10,
      "items": {
        "type": "object",
        "properties": {
          "cveId": { "type": "string" },
          "count": { "type": "integer" }
        }
      }
    }
  }
}
```

### Authorization

- **Operation**: READ
- **Permission**: VULNERABILITIES_READ
- **Access Control**: Filters to delegated user's accessible assets

### Error Codes

| Code | Message |
|------|---------|
| INVALID_DATE_FORMAT | Date must be in ISO-8601 format (YYYY-MM-DD) |
| INVALID_DATE_RANGE | End date must be after or equal to start date |
| EXECUTION_ERROR | Failed to retrieve exception statistics: {details} |

---

## Permission Mapping

| Tool | Permission | Notes |
|------|------------|-------|
| get_risk_assessment_summary | ASSESSMENTS_READ | Risk assessments are part of assessment domain |
| get_risk_mitigation_status | ASSESSMENTS_READ | Risks linked to assessments |
| get_vulnerability_statistics | VULNERABILITIES_READ | Vulnerability data domain |
| get_exception_statistics | VULNERABILITIES_READ | Exception requests for vulnerabilities |

## Access Control Matrix

| User Type | get_risk_assessment_summary | get_risk_mitigation_status | get_vulnerability_statistics | get_exception_statistics |
|-----------|----------------------------|---------------------------|------------------------------|-------------------------|
| ADMIN | All data | All data | All data | All data |
| Non-delegated API Key | All data | All data | All data | All data |
| Delegated User | Filtered by accessible assets | Filtered by accessible assets | Filtered by accessible assets | Filtered by accessible assets |
| No permissions | Error: Unauthorized | Error: Unauthorized | Error: Unauthorized | Error: Unauthorized |
