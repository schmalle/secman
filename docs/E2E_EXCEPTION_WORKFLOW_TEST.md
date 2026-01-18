# E2E Vulnerability Exception Workflow Test

**Feature**: 063-e2e-vuln-exception
**Last Updated**: 2026-01-14

This document describes how to run the end-to-end test for the vulnerability exception workflow.

---

## Overview

The E2E test validates the complete vulnerability exception request workflow via MCP:

1. Admin deletes all assets (clean environment)
2. Admin creates test user
3. Admin adds asset with non-overdue vulnerability
4. User verifies no overdue vulnerabilities
5. Admin adds overdue vulnerability
6. User creates exception request
7. Admin approves exception request
8. Cleanup all test data

---

## Preconditions

### 1. Backend Running

The Secman backend must be running and accessible.

```bash
# Start the backend
cd src/backendng
./gradlew run

# Verify it's running
curl http://localhost:8080/health
# Expected: {"status":"UP","service":"secman-backend-ng","version":"0.1"}
```

### 2. Admin MCP API Key

You need an MCP API key with:
- **ADMIN role** (or delegation to an ADMIN user)
- **User Delegation enabled** (to act on behalf of test user)
- **Required permissions**: `ASSETS_READ`, `VULNERABILITIES_READ`, `USER_ACTIVITY`

#### Creating an API Key

**Option A: Via Web UI**
1. Log in as admin at `http://localhost:8080`
2. Navigate to **Settings > MCP Integration > API Keys**
3. Click **Create New API Key**
4. Configure:
   - Name: `e2e-test-key`
   - Permissions: Select all
   - User Delegation: **Enable**
   - Allowed Domains: `@schmall.io` (or leave empty for all)
5. Copy the generated key (starts with `sk-`)

**Option B: Via API**
```bash
# Get JWT token first
TOKEN=$(curl -s -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username": "adminuser", "password": "Demopassword4321%"}' | jq -r '.token')

# Create API key with delegation enabled
curl -X POST "http://localhost:8080/api/mcp/admin/api-keys" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "e2e-test-key",
    "permissions": ["REQUIREMENTS_READ", "REQUIREMENTS_WRITE", "ASSETS_READ", "VULNERABILITIES_READ", "SCANS_READ", "USER_ACTIVITY"],
    "delegationEnabled": true,
    "allowedDelegationDomains": "@schmall.io"
  }'
```

Save the returned `apiKey` value.

### 3. Demo Admin Account

The test uses the default demo admin account:
- **Username**: `adminuser`
- **Password**: `Demopassword4321%`

Ensure this account exists and has ADMIN role.

### 4. Required Tools

The test script requires:
- `curl` - for HTTP requests
- `jq` - for JSON parsing

```bash
# macOS
brew install curl jq

# Ubuntu/Debian
sudo apt-get install curl jq

# Verify installation
curl --version
jq --version
```

### 5. Database State

The test is designed to be **idempotent** - it cleans up before and after execution. However, for best results:

- The test user `apple@schmall.io` should NOT exist (script will delete if present)
- No critical production data should be in the system (script deletes ALL assets)

> **WARNING**: This test deletes ALL assets in the system. Only run against development/test environments.

---

## Running the Test

### Basic Usage

```bash
API_KEY=sk-your-api-key ./bin/test-e2e-exception-workflowsupport.sh
```

### With Custom Backend URL

```bash
BASE_URL=http://localhost:8080 API_KEY=sk-your-api-key ./bin/test-e2e-exception-workflowsupport.sh
```

### With Verbose Output

```bash
API_KEY=sk-your-api-key ./bin/test-e2e-exception-workflowsupport.sh --verbose
```

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `API_KEY` | Yes | - | Admin MCP API key with delegation enabled |
| `BASE_URL` | No | `http://localhost:8080` | Backend URL |
| `VERBOSE` | No | `false` | Enable debug output |

---

## Test Steps

| Step | Action | Verification |
|------|--------|--------------|
| 0 | Cleanup pre-existing test user | Delete `apple@schmall.io` if exists |
| 1 | Delete all assets | Asset count = 0 |
| 2 | Create test user | User `apple@schmall.io` created with USER role |
| 3 | Add 10-day HIGH vulnerability | Asset and vulnerability created |
| 4 | Query as test user | No overdue vulnerabilities (10-day HIGH is not overdue) |
| 5 | Add 40-day CRITICAL vulnerability | Vulnerability created |
| 6 | Query as test user | 1 overdue vulnerability (40-day CRITICAL exceeds 30-day threshold) |
| 7 | Create exception request | Request created with PENDING status |
| 8 | Verify pending request | User can see their pending request |
| 9 | Admin approves request | Request status changes to APPROVED |
| 10 | Verify approval | User sees APPROVED status |
| 11 | Cleanup | Delete user and all assets |

---

## Expected Output

### Successful Run

```
[INFO] Starting E2E Vulnerability Exception Workflow Test
[INFO] Backend URL: http://localhost:8080
[INFO] Checking backend connectivity...
[PASS] Backend is reachable
[INFO] Step 0: Cleaning up pre-existing test user (if exists)...
[PASS] Step 0: Pre-existing cleanup complete
[INFO] Step 1: Deleting all assets to prepare clean environment...
[INFO] Deleted: 5 assets, 12 vulnerabilities, 3 scan results
[PASS] Step 1: All assets deleted
[INFO] Step 2: Creating test user apple@schmall.io...
[INFO] Created user with ID: 42
[PASS] Step 2: Test user created
[INFO] Step 3: Adding asset with 10-day HIGH vulnerability (not overdue)...
[INFO] Created asset: test-asset-e2e-workflow, vulnerability ID: 123
[PASS] Step 3: Asset and 10-day vulnerability created
[INFO] Step 4: Querying as apple@schmall.io - verifying no overdue vulnerabilities...
[PASS] Step 4: Verified user has no overdue vulnerabilities
[INFO] Step 5: Adding 40-day CRITICAL vulnerability (overdue)...
[INFO] Created overdue vulnerability ID: 124
[PASS] Step 5: 40-day CRITICAL vulnerability created
[INFO] Step 6: Querying as apple@schmall.io - verifying overdue vulnerability exists...
[INFO] Found overdue vulnerability: 124
[PASS] Step 6: Verified user has overdue vulnerability
[INFO] Step 7: Creating exception request as apple@schmall.io...
[INFO] Created exception request ID: 1 with status: PENDING
[PASS] Step 7: Exception request created with PENDING status
[INFO] Step 8: Verifying user can see their pending request...
[PASS] Step 8: User can see pending exception request
[INFO] Step 9: Admin approving exception request...
[INFO] Exception request approved
[PASS] Step 9: Exception request approved by admin
[INFO] Step 10: Verifying user sees APPROVED status...
[PASS] Step 10: User sees APPROVED exception request
[INFO] Step 11: Cleaning up test data...
[INFO] Deleted test user: true
[INFO] Deleted 1 assets
[PASS] Step 11: Cleanup complete

============================================
  E2E VULNERABILITY EXCEPTION TEST PASSED
============================================

Total elapsed time: 8 seconds
Test user: apple@schmall.io
Test asset: test-asset-e2e-workflow

Workflow completed:
  1. Clean environment (deleted all assets)
  2. Created test user with USER role
  3. Added 10-day HIGH vulnerability (not overdue)
  4. Verified no overdue vulnerabilities for user
  5. Added 40-day CRITICAL vulnerability (overdue)
  6. Verified user sees overdue vulnerability
  7. User created exception request (PENDING)
  8. Admin approved exception request
  9. Verified user sees APPROVED status
 10. Cleaned up all test data
```

---

## Troubleshooting

### "API_KEY environment variable is required"

Set the API key:
```bash
export API_KEY=sk-your-api-key
./bin/test-e2e-exception-workflowsupport.sh
```

### "Cannot connect to backend"

1. Verify the backend is running:
   ```bash
   curl http://localhost:8080/health
   ```

2. Check the BASE_URL is correct:
   ```bash
   BASE_URL=http://localhost:8080 API_KEY=sk-xxx ./bin/test-e2e-exception-workflowsupport.sh
   ```

### "DELEGATION_REQUIRED" or "User Delegation must be enabled"

The API key must have delegation enabled:
1. Create a new API key with `delegationEnabled: true`
2. Or update the existing key to enable delegation

### "ADMIN_REQUIRED" or "ROLE_REQUIRED"

The delegated user (or API key's default user) must have ADMIN role for:
- `delete_all_assets`
- `add_user`
- `delete_user`
- `approve_exception_request`

Ensure you're using an admin API key or delegating to an admin user.

### "Rate limit exceeded"

Wait for the rate limit to reset, or increase the rate limit in `application.yml`:
```yaml
mcp:
  rate-limiting:
    default-requests-per-hour: 10000
```

### "jq: command not found"

Install jq:
```bash
# macOS
brew install jq

# Ubuntu/Debian
sudo apt-get install jq
```

### Test Hangs or Times Out

1. Check backend logs for errors
2. Enable verbose mode:
   ```bash
   API_KEY=sk-xxx ./bin/test-e2e-exception-workflowsupport.sh --verbose
   ```
3. Check network connectivity to the backend

---

## Test Data

| Entity | Value |
|--------|-------|
| Test User Email | `apple@schmall.io` |
| Test User Password | `TestPassword123!` |
| Test User Role | `USER` |
| Test Asset Hostname | `test-asset-e2e-workflow` |
| Non-overdue CVE | `CVE-2024-0001` (10 days, HIGH) |
| Overdue CVE | `CVE-2024-0002` (40 days, CRITICAL) |
| Overdue Threshold | 30 days for CRITICAL severity |
| Exception Reason | `Testing exception workflow - E2E test suite` |

---

## MCP Tools Used

| Tool | Purpose | Required Role |
|------|---------|---------------|
| `delete_all_assets` | Clean environment | ADMIN |
| `add_user` | Create test user | ADMIN |
| `add_vulnerability` | Add test vulnerabilities | ADMIN or VULN |
| `get_overdue_assets` | Query overdue vulnerabilities | USER |
| `create_exception_request` | Request exception | USER |
| `get_my_exception_requests` | View own requests | USER |
| `get_pending_exception_requests` | View pending (admin) | ADMIN |
| `approve_exception_request` | Approve request | ADMIN |
| `delete_user` | Cleanup test user | ADMIN |

---

## CI/CD Integration

To run in CI/CD pipelines:

```yaml
# GitHub Actions example
- name: Run E2E Exception Workflow Test
  env:
    API_KEY: ${{ secrets.MCP_ADMIN_API_KEY }}
    BASE_URL: http://localhost:8080
  run: |
    ./bin/test-e2e-exception-workflow.sh
```

Ensure the backend is running before executing the test step.

---

## See Also

- [MCP Integration Guide](./MCP.md) - Full MCP documentation
- [Feature Specification](../specs/063-e2e-vuln-exception/spec.md) - Detailed requirements
- [Quickstart](../specs/063-e2e-vuln-exception/quickstart.md) - Quick reference
