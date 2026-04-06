---
name: e2ejs
description: >
  JavaScript error scanner that visits all application pages and fixes errors.
  Starts backend and frontend using dev scripts, runs the JS error scanner,
  and iteratively fixes every page error — restarting the backend after each fix.
  Use this skill when the user says "run js error scanner", "scan pages for errors",
  "e2ejs", "check all pages", "fix js errors", or similar.
context: fork
---
# E2E JavaScript Error Scanner — Iterative Fix Loop

You are an orchestration agent that brings up the full-stack development environment,
scans every application page for JavaScript and HTTP errors, and **iteratively fixes
every failure** until all pages are clean or you've exhausted the retry budget.

## High-Level Loop

```
1. Start backend   (./scripts/startbackenddev.sh)
2. Start frontend  (./scripts/startfrontenddev.sh)
3. Wait for both to be healthy
4. Run JS error scanner (./tests/js-error-scanner.sh)
5. IF all clean → done, report success
6. IF exit code 2 → fatal error (host unreachable / login failed), stop and report
7. IF exit code 1 (page errors) →
   a. Parse the structured error output
   b. Classify each error (backend vs frontend)
   c. Fix backend errors first, then frontend errors
   d. Restart backend (kill → restart ./scripts/startbackenddev.sh)
   e. Wait for backend health check
   f. Go to step 4
8. After 5 iterations without progress → stop and report remaining failures
```

## Detailed Instructions

### Phase 1 — Environment Setup

**Starting services:**

1. **Check for port conflicts** — run `lsof -i :8080` and `lsof -i :4321`. If ports
   are in use, kill the existing processes before starting.

2. **Start backend** in background:
   ```bash
   nohup ./scripts/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &
   ```
   Record the PID. The backend uses `op run -- gradle :backendng:clean :backendng:run`
   internally with 1Password secret injection.

3. **Start frontend** in background:
   ```bash
   nohup ./scripts/startfrontenddev.sh > .e2e-logs/frontend.log 2>&1 &
   ```
   Record the PID. The frontend uses `op run -- npm run dev` internally.

4. **Wait for health checks**:
   - Backend: `http://localhost:8080` — poll every 5 seconds, timeout after 120 seconds
   - Frontend: `http://localhost:4321` — poll every 3 seconds, timeout after 60 seconds

5. **Create log directory** `.e2e-logs/` if it doesn't exist.

### Phase 2 — Run Scanner

Execute the JS error scanner:
```bash
./tests/js-error-scanner.sh 2>&1 | tee .e2e-logs/scan-run-<N>.log
```
Where N is the iteration number (starting at 1).

**Important environment variables** — these are set by `js-error-scanner.sh` itself
using 1Password URIs. You do NOT need to set them manually.

**Exit code interpretation:**
- `0` — All pages clean, no errors found. Done!
- `1` — Page-level errors found (HTTP errors, JS exceptions, console errors). Enter fix loop.
- `2` — Fatal error: cannot reach host or login failed. Do NOT retry. Stop and report.

### Phase 2.5 — Error Classification

The scanner outputs structured error lines in this format:
```
  /page-path
    [HTTP 500] GET http://localhost:8080/api/endpoint — Internal Server Error
    [UNCAUGHT EXCEPTION] Cannot read properties of undefined (reading 'map')
    [CONSOLE ERROR] Failed to fetch
    [TIMEOUT] Page did not reach networkidle within 30s
    [SESSION EXPIRED] Redirected back to /login
```

Classify each error:

| Output pattern                                             | Category     | Action                                                                               |
| ---------------------------------------------------------- | ------------ | ------------------------------------------------------------------------------------ |
| `[HTTP 5xx]` (500, 502, 503)                               | **backend**  | Fix the backend controller/service that serves this endpoint                         |
| `[HTTP 403]`                                               | **backend**  | Check RBAC — missing `@Secured` annotation or role mismatch                         |
| `[HTTP 404]` on `/api/*`                                   | **backend**  | Missing endpoint — add controller method or fix route                               |
| `[UNCAUGHT EXCEPTION]` with React/JS stack                 | **frontend** | Fix the component (hydration, props, data shape)                                     |
| `[CONSOLE ERROR]` with "hydration"                         | **frontend** | SSR/client mismatch in Astro/React component                                         |
| `[CONSOLE ERROR]` with "Failed to fetch" or "NetworkError" | **backend**  | Endpoint unreachable or CORS issue                                                   |
| `[CONSOLE ERROR]` with `.map is not a function` or similar | **frontend** | API response shape mismatch — fix component to handle actual response shape          |
| `[TIMEOUT]`                                                | **infra**    | Page hangs — check for infinite loops or missing API responses                      |
| `[SESSION EXPIRED]`                                        | **infra**    | Session/JWT expired mid-scan — not a code bug, skip                                 |

**Priority**: Fix **backend errors first** in each iteration, because they often cause
downstream frontend errors. Then fix frontend errors.

### Phase 3 — Fix Loop

For each failure, fix in priority order: **backend errors first**, then frontend.

#### 3a. Backend Fixes (HTTP 5xx, 403, 404)

1. **Extract the failing endpoint** from the `[HTTP ...]` line (e.g., `GET /api/releases`).
2. **Check backend logs** in `.e2e-logs/backend.log` — search for the exception stack
   trace near the timestamp of the error. Common patterns:
   - `ClassCastException` → Hibernate native query result type mismatch
   - `NullPointerException` → missing entity or uninitialized field
   - `AccessDeniedException` / 403 → `@Secured` annotation too restrictive or missing role
   - `HttpStatusException(404)` → endpoint not registered, typo in `@Controller` path
3. **Locate the controller** — use `Grep` to find `@Controller` + `@Get`/`@Post` matching
   the failing path. Controllers are in `src/backendng/src/main/kotlin/com/secman/controller/`.
4. **Trace into the service layer** — the controller calls a service class (`*Service.kt`),
   which calls a repository. The bug is usually in the service or its query.
5. **Fix** with a minimal edit. Common fixes:
   - Cast native query results correctly
   - Add null-safety (`?.` or `?: emptyList()`)
   - Adjust `@Secured` roles to match what the logged-in user has
   - Add missing endpoint methods
6. **Backend ALWAYS needs restart** after Kotlin/Java changes.

#### 3b. Frontend Fixes (JS errors, hydration, console errors)

1. **Map the URL path to the Astro page** in `src/frontend/src/pages/`.
2. **Trace into React components** in `src/frontend/src/components/`.
3. **Fix** the component — common patterns:
   - API returns `{data: [...]}` but component expects raw array → unwrap
   - Hydration mismatch → ensure SSR and client render the same initial HTML
   - Missing null check on API response data

#### 3c. Restart Backend After Every Fix

After applying fixes (whether backend or frontend):

1. **Kill the backend process** — find and kill the entire process tree:
   ```bash
   kill $BACKEND_PID
   # Also kill any orphaned gradle/java processes on port 8080
   lsof -ti :8080 | xargs kill -9 2>/dev/null
   ```
2. **Restart backend**:
   ```bash
   nohup ./scripts/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &
   ```
3. **Wait for backend health check** — poll `http://localhost:8080` until healthy (120s timeout).
4. Frontend hot-reloads via Vite — no restart needed, but wait 3 seconds for changes to propagate.

#### 3d. Re-run and Verify

Re-run the scanner (`./tests/js-error-scanner.sh`) and check if the error count decreased.

#### 3e. Guard Rails

- Track which errors you've already attempted to fix. If the **same error persists
  after two attempts**, flag it for the user and move on.
- After **5 total iterations** without all pages being clean, stop and present a summary.
- If exit code is 2 (fatal), do NOT retry — stop immediately.

### Phase 4 — Teardown & Report

- Kill backend and frontend processes.
- Print a summary table:

```
| Iteration | Pages Scanned | Clean | Errors | Timeout | Fix Applied                    |
|-----------|---------------|-------|--------|---------|--------------------------------|
| 1         | 48            | 44    | 4      | 0       | backend: AssetController.kt    |
| 2         | 48            | 47    | 1      | 0       | frontend: VulnTable.tsx         |
| 3         | 48            | 48    | 0      | 0       | — (all clean)                  |
```

- If there are still failures, list each one with the file and line where you
  believe the root cause is, and what you tried.

## Important Notes

- **Never commit or push** — only edit files locally.
- **Secrets are handled by the dev scripts** — `startbackenddev.sh` and `startfrontenddev.sh`
  use `op run` to inject 1Password secrets. Do not set secrets manually.
- **Port collisions**: Before starting, check if ports 8080 and 4321 are already
  in use and kill existing processes.
- **Log files** go to `.e2e-logs/` — this directory is gitignored.
- **Scanner pages list** is hardcoded in `tests/js-error-scanner.mjs` in the `PAGES` array.
  If the scanner reports a page error, the fix is in the application code, not the scanner.
- Backend controllers: `src/backendng/src/main/kotlin/com/secman/controller/`
- Frontend pages: `src/frontend/src/pages/`
- Frontend components: `src/frontend/src/components/`
