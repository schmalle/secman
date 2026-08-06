---
name: e2eexception
description: >
  Run the narrow, MCP-only E2E exception test: an 11-step lifecycle covering one
  exception request through create, approve and verify. Starts backend and
  frontend, runs the test script, and iteratively fixes failures in both layers.
  Use this skill when the user says "run exception e2e", "e2eexception", or asks
  specifically for the quick MCP-only exception check. Prefer /e2evulnexception
  instead whenever the user wants the full lifecycle (approve + reject + cancel),
  the subject x scope matrix, authorization negatives, or any Web UI coverage —
  this skill covers none of those.
context: fork
---

> **Sync policy (two-way, mandatory)**: This file and
> `.claude/skills/e2eexception/SKILL.md` are one skill kept in two harness
> trees — Codex reads this copy, Claude Code reads the other. Whichever copy
> an agent edits, the same change is ported to the other **in the same
> commit**; translate harness-specific mechanics rather than copying verbatim
> (e.g. `sandbox_permissions: "require_escalated"` ↔ Bash tool
> `dangerouslyDisableSandbox: true`). `.claude/skills/` is the tie-breaker
> when the two disagree — that is a conflict rule, not a licence to edit one
> side only. Verify with `./scripts/check-skill-sync.sh` (exit 0) before
> calling the change done. See `CLAUDE.md` §"Tooling Conventions" and
> `AGENTS.md` §Skills.

# E2E Vulnerability Exception Workflow — Iterative Fix Loop

You are an orchestration agent that brings up a full-stack environment, executes
the vulnerability exception workflow E2E test, and **iteratively fixes every failure**
until the test passes or you've exhausted the retry budget.

> **⚠️ This test deletes every asset in the target database (step 2).** It is
> only safe against a disposable instance. Confirm what `SECMAN_HOST` points at
> before running — if there is any chance it is a shared or production-like
> instance, stop and ask the user rather than proceeding.

> **Read `../_shared/stack-lifecycle.md` in full before touching the stack.** It
> defines the cold-start sequence, port-bind liveness, credentials, logging and
> the 5-iteration budget this skill assumes.

**Scope check before you start.** This is the *narrow* exception skill: MCP only,
one request, approve path only. If the user wants reject/cancel, the subject ×
scope matrix, authorization negatives, or UI verification, `/e2evulnexception` is
the right skill and this one will report a pass while covering none of it.

## Test Overview

The test script (`scripts/test/test-e2e-exception-workflowsupport.sh`) performs
an 11-step MCP-based workflow:

1. Clean up pre-existing test user (if any)
2. Delete all assets (clean environment)
3. Create test user (`sometestuser@e2e.test`)
4. Add asset with 10-day HIGH vulnerability (not overdue)
5. Query as user — verify no overdue vulnerabilities
6. Add 40-day CRITICAL vulnerability (overdue)
7. Query as user — verify overdue vulnerability exists
8. User creates exception request (two-axis subject × scope model; scopes include
   `OS` = case-insensitive substring match against `Asset.osVersion`, spanning all
   matching assets — the full subject×scope matrix is covered by /e2evulnexception)
9. Admin approves exception request (auto-approved if ADMIN)
10. Verify user sees APPROVED status
11. Cleanup test data

The test calls the backend MCP endpoints via `curl`/`jq` and uses `mariadb` CLI
for direct database operations (cleanup, view truncation, ID lookups).

## High-Level Loop

```
0. Kill any running backend/frontend (./scripts/stopbackenddev.sh +
   ./scripts/stopfrontenddev.sh) — always, even if ports look free
1. Start backend   (scripts/startbackenddev.sh outside the sandbox)
2. Start frontend  (scripts/startfrontenddev.sh outside the sandbox)
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

Follow `../_shared/stack-lifecycle.md` §1–3 in full: unconditional cold start
(both stop scripts first, even if ports look free), start both dev servers
outside the sandbox in the background under `.e2e-logs/{backend,frontend}.log`,
then confirm liveness by port bind — `lsof -iTCP:8080 -sTCP:LISTEN -n -P`
(120s) and `lsof -iTCP:4321 …` (60s), checking `:4322` per §4 before
concluding the frontend failed to start. Create `.e2e-logs/` first if it
doesn't exist.

### Phase 2 — Run Tests

Execute the exception workflow test with Proton Pass secret injection.

**`BASE_URL` must be set explicitly.** The driver script defaults it to
`http://localhost:8080` (`test-e2e-exception-workflowsupport.sh:33`) — that
default predates the current convention and CLAUDE.md Hard Principle 6 forbids
it. Overriding it here is what keeps the run pointed at the real host:

```bash
export BASE_URL="pass://Test/SECMAN/SECMAN_HOST"
export SECMAN_MCP_KEY="pass://Test/SECMAN/SECMAN_MCP_KEY"
export SECMAN_ADMIN_EMAIL="pass://Test/SECMAN/SECMAN_ADMIN_EMAIL"

pass-cli run -- ./scripts/test/test-e2e-exception-workflowsupport.sh --verbose 2>&1 | tee .e2e-logs/e2e-exception-run-<N>.log
```

Before trusting the run, confirm the driver echoed the host you expected — it
logs `Backend URL: <…>` at startup. A run that silently fell back to localhost
tests a different backend than the one you cold-started, and will look like it
passed.

Where `<N>` is the iteration number (starting at 1).

**Required tools on the system**: `curl`, `jq`, `mariadb`, `pass-cli` (Proton Pass CLI).

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

Always stop via the canonical scripts in `./scripts/` — never call `kill`
or `lsof | xargs kill` inline:

```bash
./scripts/stopbackenddev.sh
./scripts/stopfrontenddev.sh
```

Both scripts target the dev ports (8080 and 4321), graceful-kill first, then
force-kill if anything is still listening. They are no-ops when nothing is
running. Wait 3 seconds for processes to fully terminate.

#### Step 3b: Diagnose and Fix

Fix in priority order: **backend errors first**, then frontend.

**Key files for this test:**

- **MCP tool implementations**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/`
  (one file per tool) — auto-registered as `@Singleton` `McpTool` beans;
  per-tool permissions in `com/secman/mcp/McpToolPermissions.kt`
- **MCP controllers**: `controller/McpStreamableHttpController.kt` (the
  Streamable-HTTP JSON-RPC endpoint the test drives) and
  `controller/McpController.kt`
- **MCP auth / delegation / permissions**: `service/McpAuthenticationService.kt`,
  `service/McpDelegationService.kt`, `service/McpToolPermissionService.kt`
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
3. **Trace the MCP call path**: test script calls `tools/call` with a tool name →
   `McpStreamableHttpController` authenticates (API key + `X-MCP-User-Email`
   delegation) → `McpToolPermissions.CALLING` authorizes the name →
   `McpToolRegistry` resolves it → the tool class under
   `mcp/tools/` → service → repository.
4. **Apply minimal fix** — common issues:
   - Tool missing from `McpToolPermissions.CALLING` (rejected with
     `PERMISSION_DENIED`), or the name in the test does not match `override val name`
   - Null pointer in service layer (missing `?.` or `?: default`)
   - Response format mismatch (tool returns different JSON structure than test expects)
   - RBAC issue — MCP delegation header not checked correctly
   - Materialized view stale — refresh trigger not working

#### Step 3c: Restart Both Services

If the fix touched any file under `src/frontend/`, first verify the build is
clean: `cd src/frontend && npm ci && npm run build` must exit 0 before
restarting. This catches TypeScript errors, missing imports, and broken
Astro/React components that a running dev server alone won't surface.

After fixing (and the frontend build check above, if applicable), restart
**both** services (outside the sandbox):

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

Per `../_shared/stack-lifecycle.md` §6 — **5 iterations total for the whole
run.** The two-attempt rule operates inside that budget: if one error survives
two fixes, stop working on *that error* and spend the remaining iterations on the
others. "Move on" means move on to a different failure, not start a fresh budget.

- If the backend port never binds, do not assume a crash. `startbackenddev.sh`
  does a full `clean` build every time, so a slow build can exceed the 120s
  budget while still compiling — check the gradle process and whether the log is
  still growing before reading `.e2e-logs/backend.log` for a real error
  (`../_shared/stack-lifecycle.md` §4).

### Phase 4 — Teardown & Report

- Stop backend and frontend via `./scripts/stopbackenddev.sh` and
  `./scripts/stopfrontenddev.sh` (never raw `kill`).
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
- **Secrets are handled by Proton Pass** — `scripts/startbackenddev.sh` and
  `scripts/startfrontenddev.sh` use `pass-cli run` to inject secrets. The test script
  must also be run with `pass-cli run` to resolve `SECMAN_MCP_KEY` and `SECMAN_ADMIN_EMAIL`.
- **Fresh services always**: killing any running backend/frontend via the stop
  scripts and starting both fresh is an unconditional part of Phase 1 — never
  attach the test run to services you did not start in this invocation.
- **Log files** go to `.e2e-logs/` — this directory is gitignored.
- **MariaDB access**: the driver script performs direct DB operations and carries
  the local dev credentials inline (see `test-e2e-exception-workflowsupport.sh`).
  Ensure MariaDB is running. Do not copy those credentials into new code or into
  your report — the repo convention is `pass-cli`, and the inline literals are a
  legacy of this script predating that convention.
- **Always stop both services on error** — never attempt to fix code while services
  are running. The stop-fix-restart cycle ensures a clean state.
- Backend controllers: `src/backendng/src/main/kotlin/com/secman/controller/`
- Backend services: `src/backendng/src/main/kotlin/com/secman/service/`
- Frontend pages: `src/frontend/src/pages/`
- Frontend components: `src/frontend/src/components/`
