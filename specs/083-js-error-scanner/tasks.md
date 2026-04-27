# Tasks: JavaScript Error Scanner

**Input**: Design documents from `/specs/083-js-error-scanner/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, quickstart.md

**Tests**: Not requested — no test tasks included per constitution Principle IV.

**Organization**: Tasks grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify prerequisites and establish the project structure

- [x] T001 Verify Playwright is importable from `tests/e2e/node_modules/playwright` by running `NODE_PATH=tests/e2e/node_modules node -e "import('playwright').then(() => console.log('OK'))"` — if it fails, run `cd tests/e2e && npm install`

**Checkpoint**: Playwright library is accessible via NODE_PATH — both files can now be created

---

## Phase 2: User Story 2 - 1Password Credential Integration (Priority: P1) 🎯 MVP Prerequisite

**Goal**: Create the bash wrapper that resolves credentials from 1Password and invokes the Node.js scanner script

**Independent Test**: Run `./tests/js-error-scanner.sh` and verify it checks for `op` CLI, resolves 1Password vault references, and attempts to invoke the Node.js script (which won't exist yet — expected to fail at that step)

### Implementation for User Story 2

- [x] T002 [US2] Create `tests/js-error-scanner.sh` with shebang (`#!/usr/bin/env bash`), `set -euo pipefail`, and `SCRIPT_DIR` resolution matching the pattern in `tests/e2e/run-e2e.sh`
- [x] T003 [US2] Add `op` CLI availability check in `tests/js-error-scanner.sh` — exit with clear error message if `op` command is not found (matching pattern from `tests/e2e/run-e2e.sh` lines 7-11)
- [x] T004 [US2] Add 1Password vault reference exports in `tests/js-error-scanner.sh`: `SECMAN_ADMIN_NAME="op://test/secman/SECMAN_ADMIN_NAME"`, `SECMAN_ADMIN_PASS="op://test/secman/SECMAN_ADMIN_PASS"`, `SECMAN_BACKEND_URL="op://test/secman/SECMAN_HOST"`, `SECMAN_INSECURE="op://test/secman/SECMAN_SSL_ACCEPT_ALL"` — matching the field names from `./scriptpp/secmanng`
- [x] T005 [US2] Add `NODE_PATH` setup and `op run -- node` invocation in `tests/js-error-scanner.sh` — set `NODE_PATH="${SCRIPT_DIR}/e2e/node_modules"` and invoke `op run -- node "${SCRIPT_DIR}/js-error-scanner.mjs"`, passing through the exit code
- [x] T006 [US2] Set execute permission on `tests/js-error-scanner.sh` via `chmod +x`

**Checkpoint**: Bash wrapper resolves 1Password credentials and invokes Node.js script — US2 is complete

---

## Phase 3: User Story 1 - Core Page Scanning (Priority: P1) 🎯 MVP

**Goal**: Create the Node.js script that launches a headless browser, logs into secman, visits all static pages, and captures JavaScript errors

**Independent Test**: Run `SECMAN_ADMIN_NAME=admin SECMAN_ADMIN_PASS=pass SECMAN_BACKEND_URL=http://localhost:4321 NODE_PATH=tests/e2e/node_modules node tests/js-error-scanner.mjs` and verify it logs in, visits pages, and captures errors

### Implementation for User Story 1

- [x] T007 [US1] Create `tests/js-error-scanner.mjs` with ES module imports for `playwright` (`chromium` from `playwright`), read environment variables `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`, `SECMAN_BACKEND_URL`, `SECMAN_INSECURE` — exit with error if username/password/URL are missing
- [x] T008 [US1] Implement browser launch and context creation in `tests/js-error-scanner.mjs` — use `chromium.launch({ headless: true })` and `browser.newContext()`, wrapping the entire scan in a try/finally that calls `browser.close()`
- [x] T009 [US1] Implement reachability pre-check in `tests/js-error-scanner.mjs` — navigate to the base URL and verify the page loads (catch navigation errors and exit with code 2 and clear message if host is unreachable)
- [x] T010 [US1] Implement browser-based login flow in `tests/js-error-scanner.mjs` — navigate to `/login`, wait for `networkidle`, fill `#username` and `#password`, click `button[type="submit"]`, wait for URL to no longer contain `/login` (15s timeout), matching the pattern from `tests/e2e/vuln-lense.spec.ts`
- [x] T011 [US1] Define the static page list array in `tests/js-error-scanner.mjs` — include all ~42 statically-routable pages derived from `src/frontend/src/pages/**/*.astro`, excluding dynamic `[id]`/`[token]` segments. Group by category (core, vulnerabilities, requirements, risk, releases, user, admin, about) per research.md section 2
- [x] T012 [US1] Implement page iteration loop in `tests/js-error-scanner.mjs` — for each URI: register `page.on('pageerror')` listener for uncaught exceptions, register `page.on('console')` listener filtered to `msg.type() === 'error'` for console errors, navigate with `page.goto(url, { waitUntil: 'networkidle', timeout: 30000 })`, catch timeout errors separately, collect results into `PageResult` objects (uri, status, uncaughtExceptions[], consoleErrors[], loadTimeMs)
- [x] T013 [US1] Implement session expiry detection in `tests/js-error-scanner.mjs` — after each page navigation, check if the URL redirected back to `/login` (indicating expired session), and report it clearly rather than counting it as a page error

**Checkpoint**: Core scanner visits all pages and captures JS errors — US1 is complete (with basic console output)

---

## Phase 4: User Story 3 - Self-Signed Certificate Support (Priority: P1)

**Goal**: Enable the scanner to work against secman instances using self-signed TLS certificates

**Independent Test**: Point the script at an HTTPS instance with a self-signed cert and verify no TLS errors occur when `SECMAN_INSECURE=true`

### Implementation for User Story 3

- [x] T014 [P] [US3] Add SSL flag parsing in `tests/js-error-scanner.mjs` — read `SECMAN_INSECURE` env var, parse `true`/`1`/`yes` case-insensitively, pass `ignoreHTTPSErrors: true` to `browser.newContext()` when enabled
- [x] T015 [P] [US3] Add `NODE_TLS_REJECT_UNAUTHORIZED=0` conditional export in `tests/js-error-scanner.sh` — inside the `op run` subshell, detect `SECMAN_INSECURE` value (matching the `case` pattern from `./scriptpp/secmanng` lines 26-29) and export the Node.js env var before invoking `node`

**Checkpoint**: Scanner works against instances with self-signed certificates — US3 is complete

---

## Phase 5: User Story 4 - Clear Error Report Output (Priority: P2)

**Goal**: Produce a structured, labeled summary report and meaningful exit codes

**Independent Test**: Run the scanner and verify the output matches the format in `quickstart.md` — progress lines during scan, grouped errors with labels, summary line, correct exit code

### Implementation for User Story 4

- [x] T016 [US4] Implement real-time progress output in `tests/js-error-scanner.mjs` — print a header (`=== Secman JavaScript Error Scanner ===`, host, page count), then for each page print a progress line showing `[N/total] /uri .... STATUS` with aligned dots, where STATUS is CLEAN, N errors, or TIMEOUT
- [x] T017 [US4] Implement final summary report in `tests/js-error-scanner.mjs` — after all pages visited, print `=== SCAN RESULTS ===` section listing each errored page with its URI and each error labeled as `[UNCAUGHT EXCEPTION]` or `[CONSOLE ERROR]`, followed by timeout pages labeled `[TIMEOUT]`
- [x] T018 [US4] Implement summary statistics line in `tests/js-error-scanner.mjs` — print `Summary: N pages scanned | X clean | Y errors | Z timeout` and set `process.exitCode` to 0 if no errors/timeouts, 1 if any errors or timeouts found

**Checkpoint**: Report output matches quickstart.md format — US4 is complete

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and edge case handling

- [x] T019 End-to-end validation: run `./tests/js-error-scanner.sh` against a running secman instance and verify the full flow matches `specs/083-js-error-scanner/quickstart.md` — credentials resolve, login succeeds, all pages visited, report printed, exit code correct
- [x] T020 Verify scanner handles edge cases: unreachable host (exit 2), login failure (clear error), mid-scan session expiry (reported per page)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — verify Playwright is available
- **US2 (Phase 2)**: Depends on Setup — creates the bash wrapper
- **US1 (Phase 3)**: Depends on Setup — creates the Node.js script (can run independently via `node` without US2's bash wrapper)
- **US3 (Phase 4)**: Depends on US1 and US2 — adds SSL config to both files
- **US4 (Phase 5)**: Depends on US1 — enhances the Node.js script's output
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **US2 (P1)**: Can start after Setup — no dependencies on other stories. Creates `tests/js-error-scanner.sh`
- **US1 (P1)**: Can start after Setup — no dependencies on US2 (can test with direct env vars). Creates `tests/js-error-scanner.mjs`
- **US3 (P1)**: Depends on both US1 and US2 existing — modifies both files
- **US4 (P2)**: Depends on US1 — modifies `tests/js-error-scanner.mjs`

### Within Each User Story

- Tasks within a story are sequential (same file)
- US2 and US1 can proceed in parallel (different files)
- US3 tasks T014 and T015 are parallel (different files)

### Parallel Opportunities

```text
After Setup (Phase 1) completes:
  ┌─ US2: T002→T003→T004→T005→T006 (tests/js-error-scanner.sh)
  │
  └─ US1: T007→T008→T009→T010→T011→T012→T013 (tests/js-error-scanner.mjs)

After US1 + US2 complete:
  ┌─ T014 [US3] SSL in .mjs
  └─ T015 [US3] SSL in .sh

After US3 completes:
  US4: T016→T017→T018 (report formatting in .mjs)
```

---

## Parallel Example: US1 + US2 Concurrent

```bash
# These two story phases work on different files and can run simultaneously:
# Agent A: US2 — Bash wrapper
Task: "Create tests/js-error-scanner.sh with 1Password integration"

# Agent B: US1 — Node.js scanner
Task: "Create tests/js-error-scanner.mjs with Playwright page scanning"
```

---

## Implementation Strategy

### MVP First (US2 + US1)

1. Complete Phase 1: Setup (verify Playwright)
2. Complete Phase 2: US2 (bash wrapper with 1Password)
3. Complete Phase 3: US1 (core scanner with basic output)
4. **STOP and VALIDATE**: Run `./tests/js-error-scanner.sh` — should authenticate and scan pages
5. Scanner is usable at this point with basic output

### Incremental Delivery

1. Setup → US2 + US1 (parallel) → Functional scanner (MVP!)
2. Add US3 → Self-signed cert support → Usable in dev/staging environments
3. Add US4 → Polished report output → Production-quality tool
4. Polish → Edge case validation → Battle-tested

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Only 2 source files: `tests/js-error-scanner.sh` and `tests/js-error-scanner.mjs`
- No tests included (not requested per constitution Principle IV)
- US2 and US1 can run in parallel since they create different files
- Commit after each completed user story phase
