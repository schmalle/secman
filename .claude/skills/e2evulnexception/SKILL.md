---
name: e2evulnexception
description: >
  Run the full vulnerability + exception E2E test loop covering both MCP and
  Web UI. Sets up a clean testbed with two users, two assets, and three
  vulnerability rows; exercises the exception lifecycle (approve, reject,
  cancel) plus authorization negatives via MCP; then verifies the same state
  through the Astro/React UI with Playwright. Cleans up before and after.
  Use this skill when the user says "run vuln exception e2e", "e2evulnexception",
  "test vulnerability + exception workflow", or similar.
context: fork
---
# E2E Vulnerability + Exception — Iterative Fix Loop

You are an orchestration agent. Bring up the full stack, run the combined MCP +
Web UI test, and **iteratively fix every failure** until it passes or the retry
budget is exhausted.

**Start tool calls immediately.** Do not pre-read the `references/` files —
load them only when a specific phase needs them.

## High-Level Loop

```
0. Kill anything listening on 8080 / 4321 (via stop scripts, never `kill`).
1. Start backend  (./scripts/startbackenddev.sh)
2. Start frontend (./scripts/startfrontenddev.sh)
3. Wait for both ports to be BOUND (port binding, not HTTP)
4. Run driver:
     pass-cli run --env-file ./secmanpp.env -- \
       ./scripts/test/test-e2e-vuln-exception-full.sh --verbose \
       2>&1 | tee .e2e-logs/e2e-vuln-exception-run-<N>.log
5. All green → done.
6. Failure → stop services, classify, fix, restart, re-run (max 5 iterations).
```

**CRITICAL**: on any failure, **stop both services BEFORE editing**, then
**restart both** before retrying. Never edit code while services are running.

## Phase 1 — Start services

This skill is pinned to Proton Pass. Never hardcode `localhost:8080` /
`localhost:4321`. The driver and Playwright config respect `BASE_URL` /
`FRONTEND_URL` / `SECMAN_BASE_URL`.

| Setting               | Default                            |
|-----------------------|------------------------------------|
| Backend start         | `./scripts/startbackenddev.sh`     |
| Backend port wait     | `lsof -iTCP:8080 -sTCP:LISTEN -n -P`, 120s |
| Frontend start        | `./scripts/startfrontenddev.sh`    |
| Frontend port wait    | `lsof -iTCP:4321 -sTCP:LISTEN -n -P`, 60s  |

Steps:

1. `mkdir -p .e2e-logs`
2. Check ports 8080 / 4321; if occupied, run the canonical stop scripts.
3. `nohup ./scripts/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &`
4. `nohup ./scripts/startfrontenddev.sh > .e2e-logs/frontend.log 2>&1 &`
5. Poll `lsof` until both ports bind (exit as soon as bound — don't sleep the full window).
6. If `tests/e2e/node_modules` is missing: `(cd tests/e2e && npm install)`.

## Phase 2 — Run the driver

```bash
pass-cli run --env-file ./secmanpp.env -- \
    ./scripts/test/test-e2e-vuln-exception-full.sh --verbose 2>&1 \
    | tee .e2e-logs/e2e-vuln-exception-run-<N>.log
```

Driver reads from `secmanpp.env`: `SECMAN_MCP_KEY`, `SECMAN_ADMIN_EMAIL`,
`SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`.

Required tools: `curl`, `jq`, `mariadb`, `pass-cli`, `node`/`npx`.

For the full testbed layout (users, assets, vulnerabilities, AWS sharing IDs,
phase-by-phase coverage), read `references/testbed.md` only if you need it.

## Phase 2.5 — Classify the failure

Find the first `[FAIL]` in `.e2e-logs/e2e-vuln-exception-run-<N>.log`.

Match it against `references/error-patterns.md` — that file maps every known
`[FAIL]` line to **backend / frontend / infra** and points at the code to fix.
Read it on demand, not preemptively.

## Phase 3 — Stop, Fix, Restart

### 3a. Stop both
```bash
./scripts/stopbackenddev.sh
./scripts/stopfrontenddev.sh
```
Wait ~3 seconds. Never call `kill` directly.

### 3b. Fix
Fix priority: **backend first**, then frontend.

- Latest driver log: `.e2e-logs/e2e-vuln-exception-run-<N>.log`
- Backend stack traces: `.e2e-logs/backend.log`
- Playwright artifacts: `tests/e2e/test-results/`

For the full file index (services, controllers, MCP tools, UI components,
migrations) read `references/key-files.md`. It also lists common fix categories.

Apply a **minimal** fix. Don't refactor adjacent code.

### 3c. Restart both
```bash
nohup ./scripts/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &
nohup ./scripts/startfrontenddev.sh > .e2e-logs/frontend.log 2>&1 &
```
Wait for ports to bind (same as Phase 1).

### 3d. Re-run
Increment `<N>`, return to Phase 2.

### 3e. Guard rails
- Same error twice → flag for the user and move on.
- 5 iterations total → stop and present the summary.
- Backend won't start (port never binds) → read `.e2e-logs/backend.log` and fix
  compile/runtime errors before retrying.

## Phase 4 — Teardown & Report

The driver's `trap EXIT` removes test data even on failure. Then:

```bash
./scripts/stopbackenddev.sh
./scripts/stopfrontenddev.sh
```

Summary table:

```
| Iter | MCP phase failed | UI phase failed | Fix applied                                       |
|------|------------------|-----------------|---------------------------------------------------|
| 1    | Phase 4 (approve)| —               | VulnerabilityExceptionRequestService.kt:approve() |
| 2    | —                | —               | — (all green)                                     |
```

If you stop short of green, list each remaining failure with the `[FAIL]` line,
suspected root-cause file, and what you tried.

## Idempotency check & important constraints

Once green, re-run immediately to verify cleanup. Details + the full list of
non-negotiable constraints (no commits, Proton Pass only, no localhost literals,
role pinning, Phase 10 destructive baseline, AWS scoping invariant) live in
`references/notes.md`. **Read it before merging** any fix that touches cleanup,
roles, or AWS sharing.
