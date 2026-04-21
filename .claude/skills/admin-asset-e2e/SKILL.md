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
1. Start backend   (./scriptpp/startbackenddev.sh)
2. Start frontend  (./scriptpp/startfrontenddev.sh)
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

### Phase 1 — Environment Setup

This skill is **pinned to Proton Pass** — always start services via the
Proton Pass wrapper scripts below, ignoring `backend.start` / `frontend.start`
in `e2e-runner.config.json` (which still references the 1Password `op run`
wrapper). The health URLs and timeouts may still come from the config file.

| Setting                  | Default                           |
| ------------------------ | --------------------------------- |
| `backend.start`          | `./scriptpp/startbackenddev.sh`   |
| `backend.healthUrl`      | `http://localhost:8080`           |
| `backend.healthTimeout`  | `120` (seconds)                   |
| `frontend.start`         | `./scriptpp/startfrontenddev.sh`  |
| `frontend.healthUrl`     | `http://localhost:4321`           |
| `frontend.healthTimeout` | `60` (seconds)                    |

**Starting services:**

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

- `backend` → kill process, re-run start command, wait for health check
- `frontend` → Astro/Vite hot-reloads. Wait 3 seconds, then proceed.
- `test` → no service restart needed.

#### Guard Rails

- Track which errors you've already attempted to fix. If the same error
  persists after two attempts, flag it for the user.
- After 5 total iterations, stop and present a summary.

### Phase 4 — Teardown & Report

- Kill backend and frontend processes.
- Print a summary table:

```
| Iteration | Tests Run | Passed | Failed | Fix Applied |
|-----------|-----------|--------|--------|-------------|
| 1         | 5         | 3      | 2      | backend: AssetController.kt |
| 2         | 5         | 5      | 0      | — (all green) |
```

## Important Notes

- **Never commit or push** — only edit files locally.
- **Secrets are handled by Proton Pass** — `scriptpp/startbackenddev.sh` and
  `scriptpp/startfrontenddev.sh` use `pass-cli run` to inject secrets into the
  backend/frontend. The Playwright test invocation is also wrapped with
  `pass-cli run` to resolve `SECMAN_*` credentials.
- **Always use the Proton Pass variants** (`scriptpp/*.sh`). Do not fall back
  to the 1Password scripts in `scripts/` — this skill is pinned to Proton Pass.
- **Port collisions**: Before starting, check if ports are in use.
- Prefer reading `scripts/wait-for-health.sh` for health-checking logic.
