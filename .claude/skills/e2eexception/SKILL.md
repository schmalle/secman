---
name: e2eexception
description: >
  Run the E2E vulnerability exception workflow test that verifies the full
  exception request lifecycle via MCP. Starts backend and frontend, runs the
  test script, and iteratively fixes failures in both layers.
  Use this skill when the user says "run exception e2e", "test exception workflow",
  "e2eexception", or similar.
context: fork
---
# E2E Vulnerability Exception Workflow — Iterative Fix Loop

You are an orchestration agent that brings up a full-stack environment, executes
the vulnerability exception workflow E2E test, and **iteratively fixes every failure**
until the test passes or you've exhausted the retry budget.

## Test Overview

The test script (`scripts/test/test-e2e-exception-workflowsupport.sh`) performs
an 11-step MCP-based workflow:

1. Clean up pre-existing test user (if any)
2. Delete all assets (clean environment)
3. Create test user (`sometestuser@sometestdomain.com`)
4. Add asset with 10-day HIGH vulnerability (not overdue)
5. Query as user — verify no overdue vulnerabilities
6. Add 40-day CRITICAL vulnerability (overdue)
7. Query as user — verify overdue vulnerability exists
8. User creates exception request
9. Admin approves exception request (auto-approved if ADMIN)
10. Verify user sees APPROVED status
11. Cleanup test data

The test calls the backend MCP endpoints via `curl`/`jq` and uses `mariadb` CLI
for direct database operations (cleanup, view truncation, ID lookups).

## High-Level Loop

```
1. Start backend   (scripts/startbackenddev.sh)
2. Start frontend  (scripts/startfrontenddev.sh)
3. Wait for both to be healthy
4. Run E2E exception workflow test
5. IF all green -> done, report success
6. IF failure ->
   a. STOP both backend and frontend
   b. Analyse the error output
   c. Determine if fix is backend or frontend
   d. Apply the fix
   e. Restart BOTH backend and frontend
   f. Go to step 4
7. After 5 iterations without progress -> stop and report remaining failures
```

**CRITICAL RULE**: On ANY error, always **stop both backend and frontend first**,
apply the fix, then **restart both** before retrying. Never fix while services are
running. This ensures a clean state for each attempt.

## Detailed Instructions

### Phase 1 — Environment Setup

**Starting services:**

1. **Create log directory** `.e2e-logs/` if it doesn't exist.

2. **Check for port conflicts** — run `lsof -i :8080` and `lsof -i :4321`. If ports
   are in use, kill the existing processes before starting.

3. **Start backend** in background:
   ```bash
   nohup ./scripts/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &
   ```
   Record the PID so you can kill it later.

4. **Start frontend** in background:
   ```bash
   nohup ./scripts/startfrontenddev.sh > .e2e-logs/frontend.log 2>&1 &
   ```
   Record the PID.

5. **Wait for health checks**:
   - Backend: poll `http://localhost:8080` every 5 seconds, timeout after 120 seconds
   - Frontend: poll `http://localhost:4321` every 3 seconds, timeout after 60 seconds

### Phase 2 — Run Tests

Execute the exception workflow test with 1Password secret injection:

```bash
export BASE_URL="http://localhost:8080"
export API_KEY="op://test/secman/MCP_API_KEY"
export SECMAN_ADMIN_EMAIL="op://test/secman/SECMAN_ADMIN_EMAIL"

op run -- ./scripts/test/test-e2e-exception-workflowsupport.sh --verbose 2>&1 | tee .e2e-logs/e2e-exception-run-<N>.log
```

Where `<N>` is the iteration number (starting at 1).

**Required tools on the system**: `curl`, `jq`, `mariadb`, `op` (1Password CLI).

### Phase 2.5 — Error Classification

The test outputs structured lines with `[PASS]`, `[FAIL]`, `[WARN]`, `[INFO]` prefixes.
Parse the output to classify failures:

| Error pattern                                              | Category     | Action                                                     |
| ---------------------------------------------------------- | ------------ | ---------------------------------------------------------- |
| `MCP tool '...' failed`                                    | **backend**  | Fix the MCP tool handler or underlying service             |
| `HTTP 5xx` in curl response                                | **backend**  | Fix controller/service exception                           |
| `HTTP 403` or `AccessDenied`                               | **backend**  | Fix RBAC — `@Secured` annotation or role check             |
| `HTTP 404` on `/api/mcp/*`                                 | **backend**  | Missing MCP tool or endpoint                               |
| `Failed to create test user`                               | **backend**  | User creation service/MCP tool issue                       |
| `Failed to add vulnerability`                              | **backend**  | Vulnerability add service/MCP tool issue                   |
| `Expected 0 overdue vulnerabilities, but found N`          | **backend**  | Materialized view not cleared or stale data                |
| `Expected at least 1 overdue vulnerability`                | **backend**  | Materialized view refresh or overdue logic issue           |
| `Failed to create exception request`                       | **backend**  | Exception request service/MCP tool issue                   |
| `Failed to approve exception request`                      | **backend**  | Exception approval service/MCP tool issue                  |
| `Cannot connect to backend`                                | **infra**    | Backend not started or crashed during test                 |
| `Could not find vulnerability ID ... in database`          | **backend**  | Vulnerability not persisted or wrong CVE ID                |
| JSON parse errors in `jq`                                  | **backend**  | MCP response format mismatch                               |
| Serialization/deserialization errors                       | **backend**  | DTO or Serdeable annotation issue                          |

### Phase 3 — Fix Loop (Stop-Fix-Restart)

On **any** failure:

#### Step 3a: Stop Both Services

```bash
# Kill backend
kill $BACKEND_PID 2>/dev/null
lsof -ti :8080 | xargs kill -9 2>/dev/null

# Kill frontend
kill $FRONTEND_PID 2>/dev/null
lsof -ti :4321 | xargs kill -9 2>/dev/null
```

Wait 3 seconds for processes to fully terminate.

#### Step 3b: Diagnose and Fix

Fix in priority order: **backend errors first**, then frontend.

**Key files for this test:**

- **MCP Tool Handlers**: `src/backendng/src/main/kotlin/com/secman/service/McpToolService.kt`
- **MCP Controller**: `src/backendng/src/main/kotlin/com/secman/controller/McpController.kt`
- **Vulnerability Service**: `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityService.kt`
- **Vulnerability Exception Service**: `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityExceptionRequestService.kt`
- **Outdated Asset Service**: `src/backendng/src/main/kotlin/com/secman/service/OutdatedAssetService.kt`
- **User Service**: `src/backendng/src/main/kotlin/com/secman/service/UserService.kt`
- **Asset Controller**: `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- **Import Controller**: `src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt`
- **Materialized View Refresh**: `src/backendng/src/main/kotlin/com/secman/service/MaterializedViewRefreshService.kt`

**Diagnosis steps:**

1. **Read the test output** from `.e2e-logs/e2e-exception-run-<N>.log` — identify which
   step failed and the exact error message.
2. **Read backend logs** from `.e2e-logs/backend.log` — search for exception stack traces
   near the time of the failure.
3. **Trace the MCP call path**: test script calls `tools/call` with a tool name ->
   `McpController` routes to `McpToolService` -> service method -> repository.
4. **Apply minimal fix** — common issues:
   - Missing MCP tool registration in `McpToolService`
   - Null pointer in service layer (missing `?.` or `?: default`)
   - Response format mismatch (tool returns different JSON structure than test expects)
   - RBAC issue — MCP delegation header not checked correctly
   - Materialized view stale — refresh trigger not working

#### Step 3c: Restart Both Services

After fixing, restart **both** services:

```bash
# Start backend
nohup ./scripts/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &
BACKEND_PID=$!

# Start frontend
nohup ./scripts/startfrontenddev.sh > .e2e-logs/frontend.log 2>&1 &
FRONTEND_PID=$!
```

Wait for health checks (same timeouts as Phase 1).

#### Step 3d: Re-run Test

Go back to Phase 2 and re-run the test script. Increment the iteration counter.

#### Step 3e: Guard Rails

- Track which errors you've already attempted to fix. If the **same error persists
  after two attempts**, flag it for the user and move on.
- After **5 total iterations**, stop and present a summary.
- If the backend crashes on startup (health check fails), read `.e2e-logs/backend.log`
  for compilation or runtime errors and fix before retrying.

### Phase 4 — Teardown & Report

- Kill backend and frontend processes.
- Print a summary table:

```
| Iteration | Step Failed | Error                          | Fix Applied                               |
|-----------|-------------|--------------------------------|-------------------------------------------|
| 1         | Step 7      | create_exception_request fail  | VulnerabilityExceptionRequestService.kt   |
| 2         | —           | All green                      | —                                         |
```

- If there are still failures, list each one with the file and line where you
  believe the root cause is, and what you tried.

## Important Notes

- **Never commit or push** — only edit files locally.
- **Secrets are handled by 1Password** — `scripts/startbackenddev.sh` and
  `scripts/startfrontenddev.sh` use `op run` to inject secrets. The test script
  must also be run with `op run` to resolve `API_KEY` and `SECMAN_ADMIN_EMAIL`.
- **Port collisions**: Before starting, check if ports 8080 and 4321 are in use
  and kill existing processes.
- **Log files** go to `.e2e-logs/` — this directory is gitignored.
- **MariaDB access**: The test script connects to `mariadb -h 127.0.0.1 -u secman -pCHANGEME secman`
  for direct database operations. Ensure MariaDB is running.
- **Always stop both services on error** — never attempt to fix code while services
  are running. The stop-fix-restart cycle ensures a clean state.
- Backend controllers: `src/backendng/src/main/kotlin/com/secman/controller/`
- Backend services: `src/backendng/src/main/kotlin/com/secman/service/`
- Frontend pages: `src/frontend/src/pages/`
- Frontend components: `src/frontend/src/components/`
