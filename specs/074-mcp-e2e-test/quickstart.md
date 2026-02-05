# Quickstart: MCP E2E Test - User-Asset-Workgroup Workflow

**Feature Branch**: `074-mcp-e2e-test`
**Date**: 2026-02-04

## Prerequisites

### Required Tools
- `curl` - HTTP client (usually pre-installed)
- `jq` - JSON processor (`brew install jq` on macOS)
- `op` - 1Password CLI v2.x (`brew install 1password-cli`)

### 1Password Setup
```bash
# Login to 1Password
op signin

# Verify access to vault
op vault list
```

### Environment Variables
Set these in your shell profile or before running the test:
```bash
export SECMAN_USERNAME="op://test/secman/SECMAN_USERNAME"
export SECMAN_PASSWORD="op://test/secman/SECMAN_PASSWORD"
export SECMAN_API_KEY="op://test/secman/SECMAN_API_KEY"
```

### Running Secman Backend
Ensure the backend is running on localhost:
```bash
cd src/backendng
./gradlew run
```

## Running the E2E Test

### Quick Run
```bash
# From repository root
./tests/mcp-e2e-workgroup-test.sh
```

### With Verbose Output
```bash
DEBUG=1 ./tests/mcp-e2e-workgroup-test.sh
```

### Expected Output
```
=== MCP E2E Test: User-Asset-Workgroup Workflow ===
[INFO] Checking prerequisites...
[INFO] Resolving credentials from 1Password...
[INFO] Authenticating to secman...
[INFO] Step 1: Creating TEST user with VULN role...
[INFO] Step 2: Creating test asset with CRITICAL vulnerability...
[INFO] Step 3: Creating test workgroup...
[INFO] Step 4: Assigning asset to workgroup...
[INFO] Step 5: Assigning TEST user to workgroup...
[INFO] Step 6: Switching to TEST user context...
[INFO] Step 7: Verifying asset access...
[PASS] TEST user sees exactly 1 asset
[INFO] Step 8: Cleanup...
[INFO] Deleted workgroup
[INFO] Deleted asset
[INFO] Deleted user
=== TEST PASSED ===
```

## Manual Testing Guide

### Step 1: Authenticate
```bash
# Resolve credentials
USERNAME=$(op read "op://test/secman/SECMAN_USERNAME")
PASSWORD=$(op read "op://test/secman/SECMAN_PASSWORD")

# Get JWT token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" | jq -r '.token')
```

### Step 2: Create User via MCP
```bash
curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "name": "add_user",
    "arguments": {
      "username": "TEST",
      "email": "test@example.com",
      "password": "TestPass123!",
      "roles": ["VULN"]
    }
  }' | jq
```

### Step 3: Create Asset with Vulnerability via MCP
```bash
curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "name": "add_vulnerability",
    "arguments": {
      "hostname": "e2e-test-server",
      "cve": "CVE-2024-0001",
      "criticality": "CRITICAL",
      "daysOpen": 10
    }
  }' | jq
```

### Step 4: Create Workgroup via MCP (NEW)
```bash
curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "name": "create_workgroup",
    "arguments": {
      "name": "E2E-Test-Workgroup",
      "description": "Workgroup for E2E testing"
    }
  }' | jq
```

### Step 5: Assign Asset to Workgroup via MCP (NEW)
```bash
curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "name": "assign_assets_to_workgroup",
    "arguments": {
      "workgroupId": <WORKGROUP_ID>,
      "assetIds": [<ASSET_ID>]
    }
  }' | jq
```

### Step 6: Assign User to Workgroup via MCP (NEW)
```bash
curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "name": "assign_users_to_workgroup",
    "arguments": {
      "workgroupId": <WORKGROUP_ID>,
      "userIds": [<USER_ID>]
    }
  }' | jq
```

### Step 7: Verify Access as TEST User
```bash
# Switch delegation to TEST user
curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-MCP-User-Email: test@example.com" \
  -d '{
    "name": "get_assets",
    "arguments": {}
  }' | jq '.content.assets | length'
# Expected output: 1
```

### Step 8: Cleanup via MCP (NEW)
```bash
# Delete workgroup
curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "name": "delete_workgroup",
    "arguments": {"workgroupId": <WORKGROUP_ID>}
  }' | jq

# Delete asset
curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "name": "delete_asset",
    "arguments": {"assetId": <ASSET_ID>}
  }' | jq

# Delete user
curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-MCP-User-Email: admin@example.com" \
  -d '{
    "name": "delete_user",
    "arguments": {"userId": <USER_ID>}
  }' | jq
```

## Troubleshooting

### 1Password CLI Issues
```bash
# Check if signed in
op whoami

# Re-authenticate if needed
op signin
```

### Backend Not Reachable
```bash
# Check if backend is running
curl -s http://localhost:8080/health | jq

# Start backend if needed
cd src/backendng && ./gradlew run
```

### Permission Denied Errors
- Ensure API key has `WORKGROUPS_WRITE` permission
- Ensure user delegation is properly set via `X-MCP-User-Email` header
- Ensure delegated user has ADMIN role

### Stale Test Data
If a previous test run failed:
```bash
# Manually clean up via admin UI or API
# Or run with cleanup-first flag (if implemented)
./tests/mcp-e2e-workgroup-test.sh --cleanup-first
```
