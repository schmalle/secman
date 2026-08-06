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

> **Sync policy**: This file mirrors `.claude/skills/admin-asset-e2e/SKILL.md`,
> which is the **leading, authoritative** copy for this repo (see
> `CLAUDE.md` §"Tooling Conventions"). Whenever the Claude Code version
> changes, port the same change here, translating Claude-specific mechanics
> to their Codex equivalent (e.g. Bash tool `dangerouslyDisableSandbox: true`
> ↔ `sandbox_permissions: "require_escalated"`). Never let this file diverge
> ahead of the Claude Code version.
# Admin Asset & Vulnerability E2E Test — Iterative Fix Loop

You are an orchestration agent that brings up a full-stack environment, executes
the admin asset/vulnerability E2E test, and **iteratively fixes every failure**
until the suite is green or you've exhausted the retry budget.

> **Read `../_shared/stack-lifecycle.md` in full before touching the stack.** It
> defines the cold-start sequence, port-bind liveness, credentials, logging and
> the 5-iteration budget this skill assumes. In short: stop both services
> unconditionally, verify the ports freed, start outside the sandbox, log to
> `.e2e-logs/`, target `SECMAN_HOST` from `pass-cli` and never `localhost`.

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
7. After 5 iterations → stop and report remaining failures
```

## Detailed Instructions

### Phase 0 / 1 — Cold-start the stack

Follow `../_shared/stack-lifecycle.md` §1–§4 exactly: stop both services
unconditionally, confirm the ports actually freed, start both outside the
sandbox with logs under `.e2e-logs/`, and wait on the port-bind liveness checks
(120s backend, 60s frontend).

Liveness is **port-bind, not HTTP**. Ignore `e2e-runner.config.json` — it is
stale pre-script-era config that points at a `./frontend` directory which does
not exist and invokes `gradle :backendng:run` directly, which the Tooling
Conventions forbid. Nothing in this skill should read it.

### Phase 2 — Run Tests

Execute the specific admin asset test with Proton Pass secret injection.

Note the asymmetry on the third line: the env var is `SECMAN_USER_USER` but the
**vault field is `SECMAN_USER_NAME`**. They do not match, and using the env var
name as the vault path fails to resolve — which surfaces as a login failure that
reads like an application bug.

```bash
export SECMAN_ADMIN_NAME="pass://Test/SECMAN/SECMAN_ADMIN_NAME"
export SECMAN_ADMIN_PASS="pass://Test/SECMAN/SECMAN_ADMIN_PASS"
export SECMAN_USER_USER="pass://Test/SECMAN/SECMAN_USER_NAME"
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

Budget rules, per `../_shared/stack-lifecycle.md` §6 — **5 iterations total for
the whole run.** The two-attempt rule operates inside that budget, not alongside
it: if one error survives two fixes, stop working on *that error* and spend the
remaining iterations on the others. It does not end the run, and it does not buy
extra iterations.

### Phase 4 — Teardown & Report

Stop both services via the stop scripts (`../_shared/stack-lifecycle.md` §7) —
on the failure path too, not only when green.

Then print the summary. Include the failing test name and the file:line you
changed, not just a count: a table of numbers cannot distinguish a suite that
passed from one that never ran.

```
| Iteration | Tests Run | Passed | Failed | Failing test | Fix Applied |
|---|---|---|---|---|---|
| 1 | 5 | 3 | 2 | admin adds vulnerability | backend: AssetController.kt:214 |
| 2 | 5 | 5 | 0 | — | — (all green) |
```

State explicitly whether the run ended green, ended on the budget, or aborted —
and if any of the four test steps did not execute, say which. Per
`../_shared/stack-lifecycle.md` §8, a step that was skipped must not look like a
step that passed.

## Important Notes

- **Never commit or push** — only edit files locally.
- **Secrets are handled by Proton Pass** — `scripts/startbackenddev.sh` and
  `scripts/startfrontenddev.sh` use `pass-cli run` to inject secrets into the
  backend/frontend. The Playwright test invocation is also wrapped with
  `pass-cli run` to resolve `SECMAN_*` credentials.
- **Fresh services always**: Phase 0 is unconditional — kill any running
  backend/frontend via the stop scripts and start both fresh. Never attach the
  test run to services you did not start in this invocation.
- Health-checking logic lives in `../_shared/stack-lifecycle.md` §3–§4. There is
  no `scripts/wait-for-health.sh`; earlier versions of this skill pointed at one
  that never existed.
