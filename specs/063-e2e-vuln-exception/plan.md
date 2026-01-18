# Implementation Plan: E2E Vulnerability Exception Workflow Test Suite

**Branch**: `063-e2e-vuln-exception` | **Date**: 2026-01-14 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/063-e2e-vuln-exception/spec.md`

## Summary

Implement a CLI executable test script that validates the complete vulnerability exception workflow via MCP tools. This requires creating 2 new MCP tools (`add_vulnerability`, `delete_all_assets`) and a shell script that orchestrates the E2E test scenario using existing admin delegation patterns.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25 (backend), Bash (test script)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, PicoCLI (CLI)
**Storage**: MariaDB 11.4
**Testing**: Bash script with curl/jq for MCP calls
**Target Platform**: Linux/macOS (CLI script)
**Project Type**: Web application (backend + CLI)
**Performance Goals**: Complete E2E test in under 60 seconds (SC-001)
**Constraints**: Fail-fast on first error, idempotent (clean up before starting)
**Scale/Scope**: Single test scenario with 7 sequential steps

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | ADMIN role required for destructive operations, RBAC enforced via MCP delegation |
| III. API-First | PASS | New MCP tools follow existing RESTful patterns |
| IV. User-Requested Testing | PASS | User explicitly requested this test suite |
| V. RBAC | PASS | All tools enforce role checks via @Secured and delegation |
| VI. Schema Evolution | N/A | No schema changes required |

## Project Structure

### Documentation (this feature)

```text
specs/063-e2e-vuln-exception/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output (N/A - no new entities)
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── mcp/tools/
│   ├── AddVulnerabilityTool.kt      # NEW - MCP wrapper for add vulnerability
│   └── DeleteAllAssetsTool.kt       # NEW - MCP wrapper for bulk delete
├── service/
│   ├── VulnerabilityService.kt      # EXISTING - addVulnerabilityFromCli()
│   └── AssetBulkDeleteService.kt    # EXISTING - deleteAllAssets()
└── controller/
    └── VulnerabilityManagementController.kt  # EXISTING - CLI endpoint

src/cli/src/main/kotlin/com/secman/cli/commands/
└── AddVulnerabilityCommand.kt       # EXISTING - supports --days-open

bin/
└── test-e2e-exception-workflow.sh   # NEW - E2E test script
```

**Structure Decision**: Web application structure. New MCP tools follow existing patterns in `mcp/tools/`. Test script in `bin/` alongside existing CLI scripts.

## Complexity Tracking

No constitutional violations. Implementation uses existing patterns and services.

---

## Implementation Components

### Component 1: `add_vulnerability` MCP Tool (NEW)

**Purpose**: Enable adding vulnerabilities via MCP with daysOpen parameter

**Location**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/AddVulnerabilityTool.kt`

**Design**:
- Wraps existing `VulnerabilityService.addVulnerabilityFromCli()`
- Requires ADMIN or VULN role via User Delegation
- Parameters: hostname, cve, criticality, daysOpen (optional)
- Returns: vulnerability ID, asset ID, status

**Pattern Reference**: Follow `AddUserTool.kt` implementation pattern

### Component 2: `delete_all_assets` MCP Tool (NEW)

**Purpose**: Enable bulk deletion of all assets via MCP for test cleanup

**Location**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteAllAssetsTool.kt`

**Design**:
- Wraps existing `AssetBulkDeleteService.deleteAllAssets()`
- Requires ADMIN role via User Delegation
- Parameters: confirmation (boolean, must be true)
- Returns: deleted count, cascade counts

**Pattern Reference**: Follow `DeleteAllRequirementsTool.kt` implementation pattern

### Component 3: E2E Test Script (NEW)

**Purpose**: Orchestrate the complete exception workflow test

**Location**: `bin/test-e2e-exception-workflow.sh`

**Design**:
```bash
#!/bin/bash
# E2E Vulnerability Exception Workflow Test
# Usage: ./bin/test-e2e-exception-workflowsupport.sh [--base-url URL] [--api-key KEY]

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-sk-admin-key}"  # Admin MCP API key
TEST_USER_EMAIL="apple@schmall.io"
TEST_USER_PASSWORD="TestPassword123!"
ADMIN_PASSWORD="Demopassword4321%"

# Helper functions
fail() { echo "FAILED: $1"; exit 1; }
success() { echo "OK: $1"; }
mcp_call() { curl -s -X POST "$BASE_URL/mcp" -H "X-MCP-API-Key: $API_KEY" ... }

# Step 0: Cleanup any pre-existing test data
# Step 1: Delete all assets (clean environment)
# Step 2: Create test user apple@schmall.io
# Step 3: Create asset owned by test user + add 10-day vulnerability
# Step 4: Query as test user - verify no overdue
# Step 5: Add 40-day CRITICAL vulnerability
# Step 6: Query as test user - verify overdue
# Step 7: Create exception request (as test user via delegation)
# Step 8: Approve exception (as admin)
# Step 9: Verify approval status
# Step 10: Cleanup
```

### Component 4: MCP Tool Registration

**Location**: `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`

**Changes**: Register new tools with appropriate permissions
- `add_vulnerability`: ADMIN, VULN roles
- `delete_all_assets`: ADMIN role only

---

## Existing Tools Verification

Based on codebase exploration, these tools already exist and are ready:

| Tool | Status | Notes |
|------|--------|-------|
| `add_user` | EXISTS | Creates user with roles |
| `delete_user` | EXISTS | Deletes user by ID |
| `list_users` | EXISTS | Lists all users |
| `get_overdue_assets` | EXISTS | Gets assets with overdue vulnerabilities |
| `create_exception_request` | EXISTS | Creates exception request |
| `get_my_exception_requests` | EXISTS | Gets user's own requests |
| `get_pending_exception_requests` | EXISTS | Gets pending for approvers |
| `approve_exception_request` | EXISTS | Approves request |
| `reject_exception_request` | EXISTS | Rejects request |
| `cancel_exception_request` | EXISTS | Cancels request |
| `add-vulnerability` CLI | EXISTS | Supports --days-open parameter |

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Admin API key not configured | Medium | High | Document prerequisite, provide setup instructions |
| Overdue threshold differs | Low | Medium | Use existing 30-day config (verified in codebase) |
| Concurrent test runs conflict | Low | Low | Fail-fast + idempotent cleanup handles this |
