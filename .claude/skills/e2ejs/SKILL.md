---
name: e2ejs
description: >
  JavaScript error scanner that visits all application pages and fixes errors.
  Starts local backend and frontend dev servers, then scans them via the shared
  URL https://secman.covestro.net (port 443), which resolves to 127.0.0.1 on
  this host. Iteratively fixes every page error — restarting the backend after
  each fix. Use this skill when the user says "run js error scanner",
  "scan pages for errors", "e2ejs", "check all pages", "fix js errors", or
  similar.
context: fork
---
# E2E JavaScript Error Scanner — Iterative Fix Loop

You are an orchestration agent that brings up the full-stack development
environment on loopback, scans every application page **via the shared URL
`https://secman.covestro.net` (port 443)**, and **iteratively fixes every
failure** until all pages are clean or you've exhausted the retry budget.

## Target Host — Fixed

All scanner traffic targets `https://secman.covestro.net` (HTTPS, port 443)
for **both production and development**. On developer machines this hostname
is mapped to `127.0.0.1` via `/etc/hosts`, so the URL is served by the local
stack (some combination of a local TLS-terminating reverse proxy in front of
the Astro dev server on `:4321` and the Micronaut backend on `:8080`, or the
frontend configured to listen on `:443` directly).

Rules:

- The scanner URL is **always** `https://secman.covestro.net`. Do not target
  `http://localhost:4321`, `http://localhost:8080`, or any other variant.
- Always export `SECMAN_BACKEND_URL=https://secman.covestro.net` before
  invoking the scanner. This is required to override the scanner's built-in
  local-auto-detect branch in `tests/js-error-scanner-pp.sh`, which would
  otherwise flip the target to `http://localhost:4321` as soon as the Astro
  dev server opens that port.
- Do **NOT** set `SECMAN_INSECURE=true`. The shared hostname must present a
  valid certificate end-to-end — if TLS verification fails, fix the cert /
  proxy setup rather than disabling verification.
- Local services **are** started (they serve the traffic behind the shared
  URL), but they must **never** be addressed directly by the scanner.

## High-Level Loop

```
1. Start backend   (./scriptpp/startbackenddev.sh)
2. Start frontend  (./scriptpp/startfrontenddev.sh)
3. Wait for both to be healthy (via shared URL, not localhost)
4. Export SECMAN_BACKEND_URL=https://secman.covestro.net
5. Run JS error scanner (./tests/js-error-scanner-pp.sh)
6. IF all clean → done, report success
7. IF exit code 2 → fatal error (host unreachable / login failed), stop and report
8. IF exit code 1 (page errors) →
   a. Parse the structured error output
   b. Classify each error (backend vs frontend)
   c. Fix backend errors first, then frontend errors
   d. Restart backend (kill → restart ./scriptpp/startbackenddev.sh)
   e. Wait for backend health check via https://secman.covestro.net
   f. Go to step 5
9. After 5 iterations without progress → stop and report remaining failures
```

## Detailed Instructions

### Phase 1 — Environment Setup

**Starting services:**

1. **Verify hostname mapping** — confirm `secman.covestro.net` resolves to
   `127.0.0.1`:
   ```bash
   getent hosts secman.covestro.net || dscacheutil -q host -a name secman.covestro.net
   ```
   If it does not resolve to loopback, stop and ask the user to add the
   `/etc/hosts` entry. Do not fall back to `localhost`.

2. **Check for port conflicts** — run `lsof -i :8080`, `lsof -i :4321`, and
   `lsof -i :443`. If ports are in use by stale processes from a previous run,
   kill them before starting. Port 443 may legitimately be held by the local
   reverse proxy the user runs — only kill it if it is your own leftover.

3. **Start backend** in background:
   ```bash
   nohup ./scriptpp/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &
   ```
   Record the PID. The backend uses `pass-cli run -- gradle :backendng:clean :backendng:run`
   internally with Proton Pass secret injection.

4. **Start frontend** in background:
   ```bash
   nohup ./scriptpp/startfrontenddev.sh > .e2e-logs/frontend.log 2>&1 &
   ```
   Record the PID. The frontend uses `pass-cli run -- npm run dev` internally.

5. **Wait for health checks via the shared URL** — not localhost. The
   scanner will use this URL, so that is what must be reachable:
   - Backend health: `https://secman.covestro.net/` — poll every 5s,
     timeout 120s. Any 2xx/3xx/401 response means the proxy + backend are up.
   - Frontend health: also via `https://secman.covestro.net/` — poll every
     3s, timeout 60s. The Astro root page (HTML or 302 to `/login`) counts
     as healthy.

   If the shared URL never becomes reachable, check the reverse proxy
   configuration and logs — do not work around it by pointing at localhost.

6. **Create log directory** `.e2e-logs/` if it doesn't exist.

### Phase 2 — Run Scanner

Execute the JS error scanner with the shared URL pinned:
```bash
SECMAN_BACKEND_URL=https://secman.covestro.net \
  ./tests/js-error-scanner-pp.sh 2>&1 | tee .e2e-logs/scan-run-<N>.log
```
Where N is the iteration number (starting at 1).

Setting `SECMAN_BACKEND_URL` to an explicit `https://` URL puts the scanner
script's `USER_PROVIDED_HTTP=true` branch in effect and suppresses the
`localhost:4321` auto-substitution that would otherwise trigger the moment
the Astro dev server opens its port.

**Important environment variables** — `SECMAN_ADMIN_NAME` and
`SECMAN_ADMIN_PASS` are resolved by `js-error-scanner-pp.sh` itself via
Proton Pass. Do NOT set them manually.

**Exit code interpretation:**
- `0` — All pages clean, no errors found. Done!
- `1` — Page-level errors found (HTTP errors, JS exceptions, console errors). Enter fix loop.
- `2` — Fatal error: cannot reach `secman.covestro.net` or login failed. Do NOT retry. Stop and report.

### Phase 2.5 — Error Classification

The scanner outputs structured error lines in this format:
```
  /page-path
    [HTTP 500] GET https://secman.covestro.net/api/endpoint — Internal Server Error
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
| `[CONSOLE ERROR]` with "Failed to fetch" or "NetworkError" | **backend**  | Endpoint unreachable or CORS issue (or proxy misrouting — check the local proxy)     |
| `[CONSOLE ERROR]` with `.map is not a function` or similar | **frontend** | API response shape mismatch — fix component to handle actual response shape          |
| `[TIMEOUT]`                                                | **infra**    | Page hangs — check for infinite loops or missing API responses                      |
| `[SESSION EXPIRED]`                                        | **infra**    | Session/JWT expired mid-scan — not a code bug, skip                                 |

**Priority**: Fix **backend errors first** in each iteration, because they often cause
downstream frontend errors. Then fix frontend errors.

### Phase 3 — Fix Loop

For each failure, fix in priority order: **backend errors first**, then frontend.

#### 3a. Backend Fixes (HTTP 5xx, 403, 404)

1. **Extract the failing endpoint** from the `[HTTP ...]` line
   (e.g., `GET /api/releases`). The host will be `secman.covestro.net` —
   strip it to get the path.
2. **Check backend logs** in `.e2e-logs/backend.log` — search for the exception
   stack trace near the timestamp of the error. Common patterns:
   - `ClassCastException` → Hibernate native query result type mismatch
   - `NullPointerException` → missing entity or uninitialized field
   - `AccessDeniedException` / 403 → `@Secured` annotation too restrictive or missing role
   - `HttpStatusException(404)` → endpoint not registered, typo in `@Controller` path
3. **Locate the controller** — use `Grep` to find `@Controller` + `@Get`/`@Post`
   matching the failing path. Controllers are in
   `src/backendng/src/main/kotlin/com/secman/controller/`.
4. **Trace into the service layer** — the controller calls a service class
   (`*Service.kt`), which calls a repository. The bug is usually in the
   service or its query.
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

1. **Stop the backend** via the canonical script (handles graceful + force kill,
   port 8080):
   ```bash
   ./scriptpp/stopbackenddev.sh
   ```
   Never call `kill` or `lsof | xargs kill` inline — always go through the script.
2. **Restart backend**:
   ```bash
   nohup ./scriptpp/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &
   ```
3. **Wait for backend health check via the shared URL** —
   poll `https://secman.covestro.net/` until it responds (120s timeout).
   Do not poll `http://localhost:8080` directly; the scanner will not use it.
4. Frontend hot-reloads via Vite through the proxy — no restart needed,
   but wait 3 seconds for changes to propagate.

#### 3d. Re-run and Verify

Re-run the scanner (same invocation as Phase 2, with
`SECMAN_BACKEND_URL=https://secman.covestro.net` exported) and check if the
error count decreased.

#### 3e. Guard Rails

- Track which errors you've already attempted to fix. If the **same error persists
  after two attempts**, flag it for the user and move on.
- After **5 total iterations** without all pages being clean, stop and present a summary.
- If exit code is 2 (fatal), do NOT retry — stop immediately.

### Phase 4 — Teardown & Report

- Stop backend and frontend via `./scriptpp/stopbackenddev.sh` and
  `./scriptpp/stopfrontenddev.sh` (never raw `kill`).
- Leave any user-managed local reverse proxy alone.
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

- **Scanner URL is fixed**: `https://secman.covestro.net` (port 443). No
  `http://`, no `localhost`, no alternate ports. Both production and
  development share this URL; on dev machines it resolves to `127.0.0.1`.
- **Never commit or push** — only edit files locally.
- **Secrets are handled by the dev scripts** — `scriptpp/startbackenddev.sh`
  and `scriptpp/startfrontenddev.sh` use `pass-cli run` to inject Proton Pass
  secrets. Do not set secrets manually.
- **Always use the Proton Pass variants** (`scriptpp/*.sh` and
  `tests/js-error-scanner-pp.sh`). Do not fall back to the 1Password scripts
  in `scripts/` — this skill is pinned to Proton Pass.
- **Port collisions**: Before starting, check if ports 8080 and 4321 are
  already in use and kill existing processes. Do not kill whatever owns :443
  unless you are sure it is your own leftover — it is likely the user's
  reverse proxy.
- **TLS**: do not set `SECMAN_INSECURE=true`. The shared hostname must present
  a valid certificate. If TLS verification fails, fix the cert/proxy rather
  than disabling verification.
- **Log files** go to `.e2e-logs/` — this directory is gitignored.
- **Scanner pages list** is hardcoded in `tests/js-error-scanner.mjs` in the
  `PAGES` array. If the scanner reports a page error, the fix is in the
  application code, not the scanner.
- Backend controllers: `src/backendng/src/main/kotlin/com/secman/controller/`
- Frontend pages: `src/frontend/src/pages/`
- Frontend components: `src/frontend/src/components/`
