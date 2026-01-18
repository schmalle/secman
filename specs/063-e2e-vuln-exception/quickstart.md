# Quickstart: E2E Vulnerability Exception Workflow Test

## Prerequisites

1. **Backend running** at `http://localhost:8080`
2. **Admin MCP API key** configured with ADMIN role
3. **Database** populated with demo admin user (password: `Demopassword4321%`)

## Running the Test

```bash
# Basic usage (uses defaults)
./bin/test-e2e-exception-workflowsupport.sh

# With custom configuration
BASE_URL=http://localhost:8080 API_KEY=sk-your-admin-key ./bin/test-e2e-exception-workflowsupport.sh

# With verbose output
./bin/test-e2e-exception-workflowsupport.sh --verbose
```

## Test Workflow

The script executes these steps in sequence (fail-fast on any error):

| Step | Action | Expected Result |
|------|--------|-----------------|
| 0 | Cleanup pre-existing test data | Test user/assets removed if exist |
| 1 | Delete all assets | Asset count = 0 |
| 2 | Create user `apple@schmall.io` | User created with USER role |
| 3 | Add asset + 10-day vulnerability | Asset owned by test user |
| 4 | Query as test user | No overdue vulnerabilities |
| 5 | Add 40-day CRITICAL vulnerability | Vulnerability created |
| 6 | Query as test user | 1 overdue vulnerability |
| 7 | Create exception request | Request in PENDING status |
| 8 | Approve as admin | Request status = APPROVED |
| 9 | Verify approval | Test user sees approved request |
| 10 | Cleanup | All test entities deleted |

## Test Data

| Entity | Value |
|--------|-------|
| Test User Email | `apple@schmall.io` |
| Test User Password | `TestPassword123!` |
| Admin Password | `Demopassword4321%` |
| Non-overdue Vuln | 10 days, HIGH severity |
| Overdue Vuln | 40 days, CRITICAL severity |
| Overdue Threshold | 30 days for CRITICAL |

## Troubleshooting

### "API key not found"
Ensure the admin MCP API key is created and passed via `API_KEY` env var.

### "User already exists"
Previous test run may have failed mid-execution. The script handles this by cleaning up first.

### "Permission denied"
Ensure the API key has ADMIN role permissions.

## MCP Tools Used

| Tool | Purpose |
|------|---------|
| `delete_all_assets` | Clean environment (NEW) |
| `add_user` | Create test user |
| `add_vulnerability` | Add test vulnerabilities (NEW) |
| `get_overdue_assets` | Query overdue vulnerabilities |
| `create_exception_request` | Request exception |
| `get_my_exception_requests` | View own requests |
| `get_pending_exception_requests` | View pending (admin) |
| `approve_exception_request` | Approve request |
| `delete_user` | Cleanup test user |
