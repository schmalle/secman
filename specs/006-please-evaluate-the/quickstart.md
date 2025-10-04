# Quickstart: MCP Tools for Security Data

## Purpose
This quickstart validates the end-to-end functionality of MCP tools for accessing security data. Follow these steps to verify the feature works correctly.

## Prerequisites
- secman backend and frontend running via Docker Compose
- Test database populated with sample data
- Valid JWT authentication token
- MCP API key created with appropriate permissions

## Test Data Setup

### 1. Create Test User and API Key
```bash
# Login to get JWT token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'

# Extract token from response
TOKEN="<jwt_token_from_response>"

# Create MCP API key
curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "quickstart-test-key",
    "permissions": ["ASSETS_READ", "SCANS_READ", "VULNERABILITIES_READ"]
  }'

# Extract API key from response
API_KEY="<api_key_from_response>"
```

### 2. Import Test Scan Data
```bash
# Upload sample Nmap scan
curl -X POST http://localhost:8080/api/import/upload-nmap-xml \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test-data/sample-nmap-scan.xml"

# Upload sample Masscan scan
curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test-data/sample-masscan-scan.xml"

# Upload sample vulnerability data
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test-data/sample-vulnerabilities.xlsx"
```

## Test Scenarios

### Scenario 1: Asset Inventory Query
**Objective**: Retrieve asset list with filtering

```bash
# Test 1.1: Get all assets (first page)
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-1.1",
    "method": "tools/call",
    "params": {
      "name": "get_assets",
      "arguments": {
        "page": 0,
        "pageSize": 10
      }
    }
  }'

# Expected: HTTP 200, assets array with ≤10 items, pagination metadata

# Test 1.2: Filter assets by type
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-1.2",
    "method": "tools/call",
    "params": {
      "name": "get_assets",
      "arguments": {
        "type": "Server",
        "pageSize": 50
      }
    }
  }'

# Expected: Only assets with type="Server"

# Test 1.3: Search by partial name
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-1.3",
    "method": "tools/call",
    "params": {
      "name": "get_assets",
      "arguments": {
        "name": "web"
      }
    }
  }'

# Expected: Assets with "web" in name (case-insensitive)
```

### Scenario 2: Scan History Retrieval
**Objective**: Access historical scan data

```bash
# Test 2.1: Get recent scans
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-2.1",
    "method": "tools/call",
    "params": {
      "name": "get_scans",
      "arguments": {
        "page": 0,
        "pageSize": 20
      }
    }
  }'

# Expected: List of scans with metadata (scanType, filename, scanDate, etc.)

# Test 2.2: Filter by scan type
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-2.2",
    "method": "tools/call",
    "params": {
      "name": "get_scans",
      "arguments": {
        "scanType": "nmap"
      }
    }
  }'

# Expected: Only Nmap scans
```

### Scenario 3: Vulnerability Analysis
**Objective**: Query vulnerability data with filtering

```bash
# Test 3.1: Get all vulnerabilities
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-3.1",
    "method": "tools/call",
    "params": {
      "name": "get_vulnerabilities",
      "arguments": {
        "pageSize": 50
      }
    }
  }'

# Expected: List of vulnerabilities with CVE IDs, severities, etc.

# Test 3.2: Filter by severity
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-3.2",
    "method": "tools/call",
    "params": {
      "name": "get_vulnerabilities",
      "arguments": {
        "severity": ["Critical", "High"]
      }
    }
  }'

# Expected: Only Critical and High severity vulnerabilities

# Test 3.3: Search by CVE ID
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-3.3",
    "method": "tools/call",
    "params": {
      "name": "get_vulnerabilities",
      "arguments": {
        "cveId": "CVE-2024"
      }
    }
  }'

# Expected: Vulnerabilities with CVE IDs containing "CVE-2024"
```

### Scenario 4: Product Discovery
**Objective**: Locate services across infrastructure

```bash
# Test 4.1: Find SSH services
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-4.1",
    "method": "tools/call",
    "params": {
      "name": "search_products",
      "arguments": {
        "service": "ssh"
      }
    }
  }'

# Expected: All assets running SSH with version info

# Test 4.2: Find all open HTTP/HTTPS services
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-4.2",
    "method": "tools/call",
    "params": {
      "name": "search_products",
      "arguments": {
        "service": "http",
        "state": "open"
      }
    }
  }'

# Expected: Open HTTP/HTTPS services across all assets
```

### Scenario 5: Asset Profile
**Objective**: Get comprehensive asset information

```bash
# Test 5.1: Get profile for asset ID 1
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-5.1",
    "method": "tools/call",
    "params": {
      "name": "get_asset_profile",
      "arguments": {
        "assetId": 1
      }
    }
  }'

# Expected: Complete profile with:
# - Asset details
# - Latest scan info
# - Open ports
# - Current vulnerabilities
# - Discovered products
# - Statistics (total scans, vuln counts by severity, etc.)
```

## Edge Case Testing

### Test 6: Pagination Limits
```bash
# Test 6.1: Exceed page size limit
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-6.1",
    "method": "tools/call",
    "params": {
      "name": "get_assets",
      "arguments": {
        "pageSize": 600
      }
    }
  }'

# Expected: HTTP 400 or error response "Page size must not exceed 500"
```

### Test 7: Permission Enforcement
```bash
# Test 7.1: Create API key without VULNERABILITIES_READ
curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "limited-key",
    "permissions": ["ASSETS_READ", "SCANS_READ"]
  }'

LIMITED_KEY="<api_key_from_response>"

# Test 7.2: Try to access vulnerabilities with limited key
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $LIMITED_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-7.2",
    "method": "tools/call",
    "params": {
      "name": "get_vulnerabilities",
      "arguments": {}
    }
  }'

# Expected: HTTP 403 Forbidden or PERMISSION_DENIED error
```

### Test 8: Rate Limiting
```bash
# Test 8.1: Rapid fire 1000+ requests
for i in {1..1100}; do
  curl -X POST http://localhost:8080/api/mcp/tools/call \
    -H "X-MCP-API-Key: $API_KEY" \
    -H "Content-Type: application/json" \
    -d "{
      \"jsonrpc\": \"2.0\",
      \"id\": \"rate-test-$i\",
      \"method\": \"tools/call\",
      \"params\": {
        \"name\": \"get_assets\",
        \"arguments\": {\"pageSize\": 1}
      }
    }" &
done

# Expected: After ~1000 requests in 1 minute, should get rate limit errors
```

### Test 9: Empty Results
```bash
# Test 9.1: Query for non-existent asset type
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-9.1",
    "method": "tools/call",
    "params": {
      "name": "get_assets",
      "arguments": {
        "type": "NonExistentType"
      }
    }
  }'

# Expected: HTTP 200, empty assets array, total=0

# Test 9.2: Query for non-existent asset ID
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-9.2",
    "method": "tools/call",
    "params": {
      "name": "get_asset_profile",
      "arguments": {
        "assetId": 999999
      }
    }
  }'

# Expected: HTTP 404 or ASSET_NOT_FOUND error
```

## Success Criteria
- [ ] All asset queries return correctly formatted responses
- [ ] Pagination works with limits enforced (max 500/page)
- [ ] Filtering by all supported parameters works
- [ ] Vulnerability severity filtering returns correct results
- [ ] Product discovery finds services across assets
- [ ] Asset profile returns complete cross-referenced data
- [ ] Permission checks block unauthorized access
- [ ] Rate limiting triggers after configured thresholds
- [ ] Empty result sets return valid responses (not errors)
- [ ] Invalid parameters return clear error messages
- [ ] All responses follow MCP protocol format
- [ ] Audit logs capture all tool calls

## Performance Validation
Run these tests with populated database (~10K assets, ~100K scans, ~500K vulnerabilities):

```bash
# Test typical query performance
time curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "perf-test",
    "method": "tools/call",
    "params": {
      "name": "get_assets",
      "arguments": {"pageSize": 100}
    }
  }'

# Expected: Response time < 5 seconds
```

## Cleanup
```bash
# Revoke test API key
curl -X DELETE http://localhost:8080/api/mcp/admin/api-keys/<key_id> \
  -H "Authorization: Bearer $TOKEN"
```

## Notes
- All timestamps should be in ISO-8601 format
- Pagination metadata should include total, page, pageSize, totalPages
- Error responses should follow MCP error format with code and message
- Audit logs should be visible in admin interface after testing
