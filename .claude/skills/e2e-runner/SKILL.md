---
name: e2e-runner
description: >
  End-to-end test orchestrator for full-stack projects with a backend (Gradle/Micronaut)
  and frontend (Astro/React). Starts services, runs shell-based E2E tests, and
  iteratively fixes failures — restarting the backend when Kotlin/Micronaut changes are
  needed. Use this skill whenever the user says "run e2e tests", "run end to end",
  "test the whole stack", "integration test", "e2e loop", or similar. Also trigger
  when the user asks to "fix all failing tests" or "get the tests green".
context: fork
---
# E2E Test Runner — Iterative Fix Loop

You are an orchestration agent that brings up a full-stack environment, executes
end-to-end tests, and **iteratively fixes every failure** until the suite is green
or you've exhausted a configurable retry budget.

## High-Level Loop

```
1. Start backend   (gradle :backendng:clean :backendng:run)
2. Start frontend  (npm run dev)
3. Wait for both to be healthy
4. Run E2E test script
5. IF all green → done, report success
6. IF failure →
   a. Analyse the error output
   b. Determine if fix is backend, frontend, or test-script
   c. Apply the fix
   d. IF backend changed → restart backend (kill → clean → run)
   e. IF frontend changed → frontend hot-reloads automatically (no restart needed)
   f. Go to step 4
7. After N iterations without progress → stop and report remaining failures
```

## Detailed Instructions

### Phase 1 — Environment Setup

Read the project-specific configuration from `e2e-runner.config.json` in the
project root (see references/config-schema.md for the schema). Fall back to
sensible defaults if the file is missing:


| Setting                  | Default                                  |
| ------------------------ | ---------------------------------------- |
| `backend.start`          | `gradle :backendng:clean :backendng:run` |
| `backend.healthUrl`      | `http://localhost:8080`                  |
| `backend.healthTimeout`  | `120` (seconds)                          |
| `frontend.start`         | `npm run dev`                            |
| `frontend.healthUrl`     | `http://localhost:4321`                  |
| `frontend.healthTimeout` | `60` (seconds)                           |
| `e2e.script`             | `./scripts/e2e-test.sh`                  |
| `e2e.maxRetries`         | `5`                                      |
| `e2e.retryDelay`         | `5` (seconds)                            |

**Starting services:**

- Start each service in a **background process** using `bash` with `nohup` or `&`,
  redirecting stdout/stderr to log files under `.e2e-logs/`.
- Record the PID so you can kill it later.
- Use the health-check helper script at `scripts/wait-for-health.sh`.

### Phase 2 — Run Tests

Execute the E2E test script. Capture **both stdout and stderr** into
`.e2e-logs/e2e-run-<N>.log` where N is the iteration number.

Parse the output to identify:

1. Which test(s) / pages failed
2. The error message / stack trace
3. The error **category** (see classification rules below)

### Phase 2.5 — Error Classification

The JS error scanner outputs structured error lines. Classify each error:


| Output pattern                                             | Category     | Action                                                                               |
| ---------------------------------------------------------- | ------------ | ------------------------------------------------------------------------------------ |
| `[HTTP 5xx]` (500, 502, 503)                               | **backend**  | Fix the backend controller/service that serves this endpoint                         |
| `[HTTP 403]`                                               | **backend**  | Check RBAC — missing`@Secured` annotation or role mismatch                          |
| `[HTTP 404]` on `/api/*`                                   | **backend**  | Missing endpoint — add controller method or fix route                               |
| `[UNCAUGHT EXCEPTION]` with React/JS stack                 | **frontend** | Fix the component (hydration, props, data shape)                                     |
| `[CONSOLE ERROR]` with "hydration"                         | **frontend** | SSR/client mismatch in Astro/React component                                         |
| `[CONSOLE ERROR]` with "Failed to fetch" or "NetworkError" | **backend**  | Endpoint unreachable or CORS issue                                                   |
| `[CONSOLE ERROR]` with `.map is not a function` or similar | **frontend** | API response shape mismatch — fix the component to handle the actual response shape |
| `[TIMEOUT]`                                                | **infra**    | Page hangs — check for infinite loops or missing API responses                      |
| `[SESSION EXPIRED]`                                        | **infra**    | Session/JWT expired mid-scan — not a code bug, skip                                 |

**Backend errors** (`[HTTP 5xx]`, `[HTTP 403]`, `[HTTP 404]` on API routes) are the most
important to fix because they cause downstream frontend errors. Always fix backend
errors before frontend errors in a given iteration. Additionally, if we find any stack traces in the backend, also fix the root cause for it.


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
6. **Backend always needs restart** after Kotlin/Java changes.

#### 3b. Frontend Fixes (JS errors, hydration, console errors)

1. **Map the URL path to the Astro page** in `src/frontend/src/pages/`.
2. **Trace into React components** in `src/frontend/src/components/`.
3. **Fix** the component — common patterns:
   - API returns `{data: [...]}` but component expects raw array → unwrap
   - Hydration mismatch → ensure SSR and client render the same initial HTML
   - Missing null check on API response data

#### 3c. Classify and Restart

After applying fixes:

- `backend` → kill the backend process (`kill $BACKEND_PID`), re-run the start
  command, wait for health check before proceeding.
- `frontend` → Astro/Vite hot-reloads. Wait 3 seconds, then proceed.
- `test` → no service restart needed.

#### 3d. Re-run and Verify

Re-run the E2E suite and check if the failure count decreased.

#### 3e. Guard Rails

- Track which errors you've already attempted to fix. If the same error
  persists after two attempts, flag it for the user and move on.
- After `maxRetries` total iterations, stop and present a summary.

### Phase 4 — Teardown & Report

- Kill backend and frontend processes.
- Print a summary table:

```
| Iteration | Tests Run | Passed | Failed | Fix Applied |
|-----------|-----------|--------|--------|-------------|
| 1         | 12        | 8      | 4      | backend: AuthController.java |
| 2         | 12        | 11     | 1      | frontend: LoginForm.tsx |
| 3         | 12        | 12     | 0      | — (all green) |
```

- If there are still failures, list each one with the file and line where you
  believe the root cause is, and what you tried.

## Important Notes

- **Never commit or push** — only edit files locally.
- **Secrets**: If the backend needs secrets via `op run`, the user's
  `e2e-runner.config.json` should wrap the start command, e.g.
  `"backend.start": "op run -- gradle :backendng:clean :backendng:run"`.
- **Port collisions**: Before starting, check if the configured ports are already
  in use (`lsof -i :<port>`) and warn the user.
- **Gradle daemon**: Use `--no-daemon` if the user has configured it, to avoid
  stale builds.
- Prefer reading the scripts/wait-for-health.sh helper for health-checking logic.
