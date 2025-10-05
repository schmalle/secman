# Quickstart: Enhanced MCP Tools for Security Data Access

**Feature**: 009-i-want-to
**Date**: 2025-10-05

## Prerequisites

1. **secman backend running** with MCP server enabled
2. **API key generated** for your user account
3. **MCP client** (e.g., Claude Desktop, custom automation script)
4. **Permissions granted**: ASSETS_READ, SCANS_READ, VULNERABILITIES_READ

---

## Step 1: Generate API Key

### Via Admin UI (Future Feature)
```
Navigate to: Settings → API Keys → Generate New Key
- Name: "AI Security Assistant"
- Permissions: ASSETS_READ, SCANS_READ, VULNERABILITIES_READ
- Expiration: Never (or set date)
- Rate Limit Tier: STANDARD

Copy the generated key (shown only once): sk_live_abc123...
```

### Via Database (Development Only)
```sql
-- Hash your desired key with BCrypt first (cost factor 12)
-- Example key: sk_dev_testkeyforlocaldev

INSERT INTO mcp_api_key (key_hash, user_id, name, rate_limit_tier, active, created_at, updated_at)
VALUES (
    '$2a$12$[bcrypt_hash_here]',
    1,  -- Your user ID
    'Development Test Key',
    'STANDARD',
    TRUE,
    NOW(),
    NOW()
);

INSERT INTO mcp_api_key_permissions (mcp_api_key_id, permission)
VALUES
    (LAST_INSERT_ID(), 'ASSETS_READ'),
    (LAST_INSERT_ID(), 'SCANS_READ'),
    (LAST_INSERT_ID(), 'VULNERABILITIES_READ');
```

---

## Step 2: Configure MCP Client

### Example: Claude Desktop Configuration
Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "secman": {
      "command": "docker",
      "args": [
        "exec",
        "-i",
        "secman-backend",
        "/app/mcp-server"
      ],
      "env": {
        "MCP_API_KEY": "sk_live_abc123..."
      }
    }
  }
}
```

### Example: Custom Python Client
```python
import asyncio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def main():
    server_params = StdioServerParameters(
        command="docker",
        args=["exec", "-i", "secman-backend", "/app/mcp-server"],
        env={"MCP_API_KEY": "sk_live_abc123..."}
    )

    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            # Initialize connection
            await session.initialize()

            # List available tools
            tools = await session.list_tools()
            print(f"Available tools: {[t.name for t in tools]}")

            # Call a tool
            result = await session.call_tool("get_all_assets_detail", {
                "page": 0,
                "pageSize": 10
            })
            print(result)

asyncio.run(main())
```

---

## Step 3: Test Tools

### Tool 1: Get All Assets with Complete Details

**Request**:
```json
{
  "name": "get_all_assets_detail",
  "arguments": {
    "page": 0,
    "pageSize": 50,
    "filters": {
      "type": "SERVER"
    }
  }
}
```

**Expected Response**:
```json
{
  "assets": [
    {
      "id": 1,
      "name": "web-server-01.example.com",
      "type": "SERVER",
      "ip": "192.168.1.100",
      "owner": "Infrastructure Team",
      "description": "Production web server",
      "groups": ["production", "web-tier"],
      "cloudAccountId": "aws-123456",
      "cloudInstanceId": "i-0abc123",
      "adDomain": "EXAMPLE",
      "osVersion": "Ubuntu 22.04 LTS",
      "lastSeen": "2025-10-05T10:30:00Z",
      "workgroups": [
        {
          "id": 1,
          "name": "Infrastructure",
          "description": "Infrastructure workgroup"
        }
      ],
      "manualCreator": {
        "id": 5,
        "username": "admin",
        "email": "admin@example.com"
      },
      "scanUploader": null,
      "createdAt": "2025-01-15T08:00:00Z",
      "updatedAt": "2025-10-05T10:30:00Z"
    }
  ],
  "total": 42,
  "page": 0,
  "pageSize": 50,
  "totalPages": 1
}
```

**Validation**:
- ✅ Total count matches expected server count
- ✅ All workgroup-filtered assets included (user's workgroups only)
- ✅ Complete asset data returned (all fields present)
- ✅ Workgroups, creators properly nested

---

### Tool 2: Get Scan Results (Open Ports)

**Request**:
```json
{
  "name": "get_asset_scan_results",
  "arguments": {
    "page": 0,
    "pageSize": 100,
    "filters": {
      "assetId": 1,
      "port": 443
    }
  }
}
```

**Expected Response**:
```json
{
  "scanResults": [
    {
      "id": 123,
      "assetId": 1,
      "assetName": "web-server-01.example.com",
      "port": 443,
      "service": "https",
      "product": "nginx",
      "version": "1.24.0",
      "discoveredAt": "2025-10-04T22:15:00Z",
      "scanType": "nmap"
    }
  ],
  "total": 1,
  "page": 0,
  "pageSize": 100,
  "totalPages": 1
}
```

**Validation**:
- ✅ Correct asset scan results returned
- ✅ Port, service, product, version populated
- ✅ Discovery timestamp accurate
- ✅ Only user's accessible assets included

---

### Tool 3: Get All Vulnerabilities

**Request**:
```json
{
  "name": "get_all_vulnerabilities_detail",
  "arguments": {
    "page": 0,
    "pageSize": 100,
    "filters": {
      "severity": "CRITICAL",
      "minDaysOpen": 30,
      "exceptionStatus": "not_excepted"
    }
  }
}
```

**Expected Response**:
```json
{
  "vulnerabilities": [
    {
      "id": 456,
      "assetId": 1,
      "assetName": "web-server-01.example.com",
      "vulnerabilityId": "CVE-2024-12345",
      "cvssSeverity": "CRITICAL",
      "vulnerableProductVersions": "nginx < 1.25.0",
      "daysOpen": 45,
      "scanTimestamp": "2025-09-20T10:00:00Z",
      "isExcepted": false,
      "exceptionReason": null,
      "createdAt": "2025-09-20T10:05:00Z"
    }
  ],
  "total": 3,
  "page": 0,
  "pageSize": 100,
  "totalPages": 1
}
```

**Validation**:
- ✅ Only critical severity vulnerabilities returned
- ✅ Days open >= 30 filter applied
- ✅ Excepted vulnerabilities excluded
- ✅ Complete vulnerability data present

---

### Tool 4: Get Complete Asset Profile

**Request**:
```json
{
  "name": "get_asset_complete_profile",
  "arguments": {
    "assetId": 1,
    "includeVulnerabilities": true,
    "includeScanResults": true,
    "vulnerabilityFilters": {
      "severity": "CRITICAL",
      "exceptionStatus": "not_excepted"
    }
  }
}
```

**Expected Response**:
```json
{
  "asset": {
    "id": 1,
    "name": "web-server-01.example.com",
    "type": "SERVER",
    "ip": "192.168.1.100",
    "owner": "Infrastructure Team",
    "description": "Production web server",
    "groups": ["production", "web-tier"],
    "cloudAccountId": "aws-123456",
    "cloudInstanceId": "i-0abc123",
    "adDomain": "EXAMPLE",
    "osVersion": "Ubuntu 22.04 LTS",
    "lastSeen": "2025-10-05T10:30:00Z",
    "workgroups": [
      {"id": 1, "name": "Infrastructure", "description": "Infrastructure workgroup"}
    ],
    "manualCreator": {
      "id": 5,
      "username": "admin",
      "email": "admin@example.com"
    },
    "scanUploader": null,
    "createdAt": "2025-01-15T08:00:00Z",
    "updatedAt": "2025-10-05T10:30:00Z",
    "vulnerabilities": [
      {
        "id": 456,
        "vulnerabilityId": "CVE-2024-12345",
        "cvssSeverity": "CRITICAL",
        "vulnerableProductVersions": "nginx < 1.25.0",
        "daysOpen": 45,
        "scanTimestamp": "2025-09-20T10:00:00Z",
        "isExcepted": false,
        "exceptionReason": null
      }
    ],
    "scanResults": [
      {
        "id": 123,
        "port": 443,
        "service": "https",
        "product": "nginx",
        "version": "1.24.0",
        "discoveredAt": "2025-10-04T22:15:00Z",
        "scanType": "nmap"
      },
      {
        "id": 124,
        "port": 80,
        "service": "http",
        "product": "nginx",
        "version": "1.24.0",
        "discoveredAt": "2025-10-04T22:15:00Z",
        "scanType": "nmap"
      }
    ],
    "statistics": {
      "totalVulnerabilities": 1,
      "criticalVulnerabilities": 1,
      "highVulnerabilities": 0,
      "totalOpenPorts": 2
    }
  }
}
```

**Validation**:
- ✅ Complete asset profile returned in single request
- ✅ Vulnerabilities filtered by severity (CRITICAL only)
- ✅ All scan results (open ports) included
- ✅ Statistics calculated correctly
- ✅ Nested data properly structured

---

## Step 4: Test Error Scenarios

### Unauthorized Asset Access
**Request**:
```json
{
  "name": "get_asset_complete_profile",
  "arguments": {
    "assetId": 999
  }
}
```

**Expected Error**:
```json
{
  "isError": true,
  "code": "INSUFFICIENT_PERMISSIONS",
  "message": "Asset 999 is not accessible to your workgroups",
  "details": {
    "assetId": 999,
    "userWorkgroups": [1, 2],
    "assetWorkgroups": [5]
  }
}
```

### Rate Limit Exceeded
**Trigger**: Make 5001 requests in 1 minute

**Expected Error**:
```json
{
  "isError": true,
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "Rate limit exceeded: 5000 requests per minute",
  "details": {
    "limitType": "per_minute",
    "limit": 5000,
    "retryAfter": 45
  }
}
```

### Invalid Pagination
**Request**:
```json
{
  "name": "get_all_assets_detail",
  "arguments": {
    "page": 0,
    "pageSize": 2000
  }
}
```

**Expected Error**:
```json
{
  "isError": true,
  "code": "INVALID_PAGINATION",
  "message": "Page size must be between 1 and 1000",
  "details": {
    "requestedPageSize": 2000,
    "maxPageSize": 1000
  }
}
```

### Total Results Exceeded
**Request**: Query that would return >100K results

**Expected Error**:
```json
{
  "isError": true,
  "code": "TOTAL_RESULTS_EXCEEDED",
  "message": "Query would return more than 100,000 results. Please add more filters.",
  "details": {
    "totalResults": 150000,
    "maxResults": 100000
  }
}
```

---

## Step 5: Integration Test Scenarios

### Scenario 1: Security Audit Report Generation
```python
async def generate_security_audit():
    # 1. Get all critical vulnerabilities
    critical_vulns = await session.call_tool("get_all_vulnerabilities_detail", {
        "pageSize": 1000,
        "filters": {"severity": "CRITICAL", "exceptionStatus": "not_excepted"}
    })

    # 2. For each vulnerability, get asset details
    assets_with_critical = []
    for vuln in critical_vulns["vulnerabilities"]:
        asset = await session.call_tool("get_asset_complete_profile", {
            "assetId": vuln["assetId"]
        })
        assets_with_critical.append({
            "asset": asset["asset"],
            "vulnerability": vuln
        })

    # 3. Generate report
    return generate_report(assets_with_critical)
```

**Validation**:
- ✅ All critical vulnerabilities retrieved
- ✅ Asset details enriched for each vulnerability
- ✅ Workgroup access control enforced throughout
- ✅ Report generated successfully

### Scenario 2: Port Exposure Analysis
```python
async def analyze_port_exposure():
    # 1. Get all assets
    all_assets = await session.call_tool("get_all_assets_detail", {
        "pageSize": 1000
    })

    # 2. Get scan results for common risky ports
    risky_ports = [21, 23, 3389, 5900]  # FTP, Telnet, RDP, VNC
    exposed_services = []

    for port in risky_ports:
        results = await session.call_tool("get_asset_scan_results", {
            "pageSize": 1000,
            "filters": {"port": port}
        })
        exposed_services.extend(results["scanResults"])

    return analyze_exposure(exposed_services)
```

**Validation**:
- ✅ All accessible assets queried
- ✅ Risky ports identified
- ✅ Exposure analysis completed

### Scenario 3: Compliance Dashboard
```python
async def build_compliance_dashboard():
    # 1. Get all assets by type
    servers = await session.call_tool("get_all_assets_detail", {
        "pageSize": 1000,
        "filters": {"type": "SERVER"}
    })

    # 2. Check vulnerability status for each server
    compliance_data = []
    for asset in servers["assets"]:
        profile = await session.call_tool("get_asset_complete_profile", {
            "assetId": asset["id"],
            "includeVulnerabilities": True
        })

        compliance_data.append({
            "asset": asset["name"],
            "criticalCount": profile["asset"]["statistics"]["criticalVulnerabilities"],
            "highCount": profile["asset"]["statistics"]["highVulnerabilities"],
            "isCompliant": profile["asset"]["statistics"]["criticalVulnerabilities"] == 0
        })

    return build_dashboard(compliance_data)
```

**Validation**:
- ✅ All servers enumerated
- ✅ Vulnerability counts accurate
- ✅ Compliance status determined
- ✅ Dashboard built successfully

---

## Performance Benchmarks

### Expected Response Times (p95)
- `get_all_assets_detail` (100 items): <150ms
- `get_asset_scan_results` (100 items): <100ms
- `get_all_vulnerabilities_detail` (100 items): <120ms
- `get_asset_complete_profile` (1 asset, 50 vulns, 30 ports): <180ms

### Rate Limit Behavior
- STANDARD tier: 5000 req/min, 100K req/hour
- Burst allowed: Up to 100 requests in 1 second (token bucket)
- Recovery: Gradual refill, not fixed window

---

## Troubleshooting

### Issue: "INVALID_API_KEY" error
**Cause**: API key not found or inactive
**Solution**:
1. Verify key copied correctly (no spaces)
2. Check key active in database: `SELECT active FROM mcp_api_key WHERE key_hash = ?`
3. Regenerate key if necessary

### Issue: "INSUFFICIENT_PERMISSIONS" error
**Cause**: API key missing required permission
**Solution**:
1. Check permissions: `SELECT permission FROM mcp_api_key_permissions WHERE mcp_api_key_id = ?`
2. Add missing permission via admin UI or database

### Issue: Empty results despite data existing
**Cause**: Workgroup access control filtering
**Solution**:
1. Verify user workgroup memberships: `SELECT workgroup_id FROM user_workgroups WHERE user_id = ?`
2. Check asset workgroups: `SELECT workgroup_id FROM asset_workgroups WHERE asset_id = ?`
3. Ensure overlap exists

### Issue: "RATE_LIMIT_EXCEEDED" error
**Cause**: Exceeded 5000 req/min or 100K req/hour
**Solution**:
1. Implement exponential backoff
2. Cache results on client side
3. Request HIGH tier rate limit (10K req/min)

---

**Status**: Quickstart complete, ready for user acceptance testing
