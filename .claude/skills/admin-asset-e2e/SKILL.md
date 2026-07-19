---
name: admin-asset-e2e
description: >
  Run the admin asset and vulnerability E2E test that verifies an admin can
  add a system and vulnerability via the UI, and a normal user can see it.
  Starts services, runs the Playwright test, and iteratively fixes failures.
  Use this skill when the user says "run admin asset e2e", "test add system",
  "admin asset test", or similar.
context: fork
---
# Admin Asset & Vulnerability E2E Test — Iterative Fix Loop

You are an orchestration agent that brings up a full-stack environment, executes
the admin asset/vulnerability E2E test, and **iteratively fixes every failure**
until the suite is green or you've exhausted the retry budget.

## Test Overview

The E2E test (`tests/e2e/admin-asset-vuln.spec.ts`) performs:
1. Login as normal user → verify DUMMY asset does NOT exist in asset list
2. Login as admin → navigate to `/admin/add-system` → create asset "DUMMY" with normal user as owner
3. Same admin session → add a HIGH criticality vulnerability (60 days old) to DUMMY
4. Login as normal user → verify DUMMY asset IS now visible in asset list

## High-Level Loop

```
0. Kill any running backend/frontend (./scripts/stopbackenddev.sh +
   ./scripts/stopfrontenddev.sh) — always, even if ports look free
1. Start backend   (./scripts/startbackenddev.sh outside the sandbox)
2. Start frontend  (./scripts/startfrontenddev.sh outside the sandbox)
3. Wait for both to be healthy
4. Run E2E test
5. IF all green → done, report success
6. IF failure →
   a. Analyse the error output
   b. Determine if fix is backend, frontend, or test-script
   c. Apply the fix
   d. IF backend changed → restart backend
   e. IF frontend changed → frontend hot-reloads automatically
   f. Go to step 4
7. After N iterations without progress → stop and report remaining failures
```

## Detailed Instructions

### Phase 0 — Kill Running Services (mandatory, unconditional)

Never reuse an already-running backend or frontend — a running instance may
predate the current working tree and would invalidate the run. Always start
from a cold stack, even if the ports look free or the services look healthy:

```bash
./scripts/stopbackenddev.sh
./scripts/stopfrontenddev.sh
```

Both scripts graceful-kill first, force-kill anything still listening on
8080/4321, and are safe no-ops when nothing is running. Never call `kill` or
`lsof | xargs kill` inline. Wait ~3 seconds, then verify both ports are free
before proceeding to Phase 1:

```bash
lsof -iTCP:8080 -sTCP:LISTEN -n -P   # must print nothing
lsof -iTCP:4321 -sTCP:LISTEN -n -P   # must print nothing
```

### Phase 1 — Environment Setup

Always start services via the wrapper scripts below. The health URLs and
timeouts may still come from `e2e-runner.config.json`.

| Setting                  | Default                           |
| ------------------------ | --------------------------------- |
| `backend.start`          | `./scripts/startbackenddev.sh`   |
| `backend.healthUrl`      | `http://localhost:8080`           |
| `backend.healthTimeout`  | `120` (seconds)                   |
| `frontend.start`         | `./scripts/startfrontenddev.sh`  |
| `frontend.healthUrl`     | `http://localhost:4321`           |
| `frontend.healthTimeout` | `60` (seconds)                    |

**Starting services:**

**Outside-sandbox requirement:** Always start `./scripts/startbackenddev.sh`
and `./scripts/startfrontenddev.sh` outside the sandbox / with escalated
permissions (e.g. Bash tool `dangerouslyDisableSandbox: true`). Both scripts
source secrets via `pass-cli`, which a sandboxed shell cannot reach — do not
start either dev server inside the filesystem sandbox.

- Start each service in a **background process** using `bash` with `nohup` or `&`,
  redirecting stdout/stderr to log files under `.e2e-logs/`.
- Record the PID so you can kill it later.
- Use the health-check helper script at `scripts/wait-for-health.sh`.

### Phase 2 — Run Tests

Execute the specific admin asset test with Proton Pass secret injection:

```bash
export SECMAN_ADMIN_NAME="pass://Test/SECMAN/SECMAN_ADMIN_NAME"
export SECMAN_ADMIN_PASS="pass://Test/SECMAN/SECMAN_ADMIN_PASS"
export SECMAN_USER_USER="pass://Test/SECMAN/SECMAN_USER_USER"
export SECMAN_USER_PASS="pass://Test/SECMAN/SECMAN_USER_PASS"

cd tests/e2e && pass-cli run -- npx playwright test admin-asset-vuln.spec.ts --project=chrome
```

Credentials used by Playwright:
- `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` (admin credentials)
- `SECMAN_USER_USER` / `SECMAN_USER_PASS` (normal user credentials)

Capture **both stdout and stderr** into `.e2e-logs/e2e-run-<N>.log`.

### Phase 2.5 — Error Classification

| Output pattern                                             | Category     | Action                                                     |
| ---------------------------------------------------------- | ------------ | ---------------------------------------------------------- |
| `[HTTP 5xx]` (500, 502, 503)                               | **backend**  | Fix controller/service                                     |
| `[HTTP 403]`                                               | **backend**  | Check RBAC — `@Secured` annotation or role mismatch        |
| `[HTTP 404]` on `/api/*`                                   | **backend**  | Missing endpoint                                           |
| `[UNCAUGHT EXCEPTION]` with React/JS stack                 | **frontend** | Fix component                                              |
| `[CONSOLE ERROR]` with "hydration"                         | **frontend** | SSR/client mismatch                                        |
| `[CONSOLE ERROR]` with "Failed to fetch"                   | **backend**  | Endpoint unreachable or CORS                               |
| `[TIMEOUT]`                                                | **infra**    | Page hangs                                                 |

### Phase 3 — Fix Loop

Fix in priority order: **backend errors first**, then frontend.

#### Key Files for This Test

- **Admin UI page**: `src/frontend/src/pages/admin/add-system.astro`
- **Admin UI component**: `src/frontend/src/components/admin/AdminAddSystem.tsx`
- **Asset controller**: `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- **Vulnerability controller**: `src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityManagementController.kt`
- **Vulnerability service**: `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityService.kt`
- **Asset filter service**: `src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt`
- **Sidebar**: `src/frontend/src/components/Sidebar.tsx`
- **E2E test**: `tests/e2e/admin-asset-vuln.spec.ts`

#### Restart Rules

- `backend` → run `./scripts/stopbackenddev.sh`, then `./scripts/startbackenddev.sh`
  (outside the sandbox), wait for health check. Never `kill` inline.
- `frontend` → before relying on hot-reload or a restart, verify the build is
  clean: `cd src/frontend && npm ci && npm run build` must exit 0. This catches
  TypeScript errors, missing imports, and broken Astro/React components that
  Vite's dev server won't surface. Then Astro/Vite hot-reloads — wait 3
  seconds and proceed. If a full restart is required, use
  `./scripts/stopfrontenddev.sh` then `./scripts/startfrontenddev.sh`
  (outside the sandbox).
- `test` → no service restart needed.

#### Guard Rails

- Track which errors you've already attempted to fix. If the same error
  persists after two attempts, flag it for the user.
- After 5 total iterations, stop and present a summary.

### Phase 4 — Teardown & Report

- Stop backend and frontend via `./scripts/stopbackenddev.sh` and
  `./scripts/stopfrontenddev.sh` (never raw `kill`).
- Print a summary table:

```
| Iteration | Tests Run | Passed | Failed | Fix Applied |
|-----------|-----------|--------|--------|-------------|
| 1         | 5         | 3      | 2      | backend: AssetController.kt |
| 2         | 5         | 5      | 0      | — (all green) |
```

## Important Notes

- **Never commit or push** — only edit files locally.
- **Secrets are handled by Proton Pass** — `scripts/startbackenddev.sh` and
  `scripts/startfrontenddev.sh` use `pass-cli run` to inject secrets into the
  backend/frontend. The Playwright test invocation is also wrapped with
  `pass-cli run` to resolve `SECMAN_*` credentials.
- **Fresh services always**: Phase 0 is unconditional — kill any running
  backend/frontend via the stop scripts and start both fresh. Never attach the
  test run to services you did not start in this invocation.
- Prefer reading `scripts/wait-for-health.sh` for health-checking logic.
