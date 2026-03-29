# Tasks: Playwright E2E Test for Vulnmanagement Lense

**Input**: Design documents from `/specs/081-playwright-vuln-e2e/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, quickstart.md

**Tests**: This feature IS a test suite. No additional test tasks needed — the deliverable itself is the test infrastructure.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Initialize the Playwright test project with its own isolated dependencies

- [x] T001 Create `tests/e2e/package.json` with `@playwright/test` as devDependency and `"type": "module"` — include `"test": "npx playwright test"` script (ref: research.md R1)
- [x] T002 [P] Create `tests/e2e/.gitignore` to exclude `node_modules/`, `test-results/`, `playwright-report/`, and `playwright/.cache/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Playwright configuration that defines browser matrix, environment variable reading, timeouts, and reporting — MUST be complete before any test can run

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T003 Create `tests/e2e/playwright.config.ts` — configure two projects: `chrome` with `channel: 'chrome'` and `msedge` with `channel: 'msedge'`; read `SECMAN_BACKEND_URL` env var as `baseURL` (default `http://localhost:4321`); set `navigationTimeout: 30000`; set `actionTimeout: 10000`; enable HTML reporter; set `retries: 0` for deterministic results (implements FR-006, FR-008, SC-004, SC-005; delivers US3 cross-browser requirement via project matrix — ref: research.md R2)

**Checkpoint**: `npx playwright test` should run (and fail with "no tests found") on both Chrome and Edge projects

---

## Phase 3: User Story 1 — Admin User Navigates to Lense (Priority: P1) 🎯 MVP

**Goal**: An admin user can log in, navigate to the Vulnerability Management sidebar, click "Lense", and reach the Vulnerability Statistics page with zero JS console errors

**Independent Test**: Run `SECMAN_ADMIN_NAME=... SECMAN_ADMIN_PASS=... npx playwright test --project=chrome -g "Admin"` — should pass on Chrome alone

### Implementation for User Story 1

- [x] T004 [US1] Create `tests/e2e/vuln-lense.spec.ts` with environment variable validation at module level — read `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`, `SECMAN_USER_USER`, `SECMAN_USER_PASS` from `process.env`; throw descriptive error naming each missing variable if any are undefined (implements FR-007, FR-010, SC-003)
- [x] T005 [US1] In `tests/e2e/vuln-lense.spec.ts`, implement `test.describe('Admin user')` block containing a single test `'login and navigate to Vulnmanagement Lense'` that: (1) sets up `consoleErrors: string[]` array with `page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()) })` listener; (2) navigates to `/login`; (3) fills `#username` with admin user, `#password` with admin pass; (4) clicks `button[type="submit"]`; (5) waits for navigation away from `/login`; (6) clicks `page.getByText('VULNERABILITY MANAGEMENT')` to expand sidebar submenu; (7) clicks `page.getByRole('link', { name: 'Lense' })` to navigate; (8) asserts `page.getByRole('heading', { name: /Vulnerability Statistics Lense/ })` is visible; (9) asserts `expect(consoleErrors).toEqual([])` (implements FR-001, FR-003, FR-004, FR-005, FR-011; ref: research.md R3–R6)

**Checkpoint**: Admin user test passes on Chrome — `npx playwright test --project=chrome -g "Admin"` shows green. This is the MVP.

---

## Phase 4: User Story 2 — Normal User Navigates to Lense (Priority: P1)

**Goal**: A non-admin user with VULN role follows the same login-navigate-verify flow and reaches Lense with zero JS errors

**Independent Test**: Run `SECMAN_USER_USER=... SECMAN_USER_PASS=... npx playwright test --project=chrome -g "Normal"` — should pass on Chrome alone

### Implementation for User Story 2

- [x] T006 [US2] In `tests/e2e/vuln-lense.spec.ts`, add `test.describe('Normal user')` block with identical test flow as admin but using `SECMAN_USER_USER` / `SECMAN_USER_PASS` credentials — same console error monitoring, same sidebar navigation, same heading assertion (implements FR-002)

**Checkpoint**: Both admin and normal user tests pass on Chrome — `npx playwright test --project=chrome` shows 2 passing tests

---

## Phase 5: User Story 3 — Cross-Browser Execution (Priority: P2)

**Goal**: All tests pass on both Chrome and Edge browsers

**Independent Test**: Run `npx playwright test` with no `--project` flag — all 4 combinations (2 users × 2 browsers) should pass

**Note**: Cross-browser support is already implemented by the Playwright config (T003). This phase is a verification checkpoint — install browsers and run the full matrix.

### Implementation for User Story 3

- [x] T007 [US3] Run `npx playwright install chrome msedge` to install browser binaries, then execute `npx playwright test` to verify all 4 test combinations pass (2 users × 2 browsers); fix any Edge-specific issues if discovered (validates FR-006, SC-001, SC-002)

**Checkpoint**: Full matrix green — `npx playwright test` shows 4 passing tests across Chrome and Edge

---

## Phase 6: User Story 4 — 1Password Credential Injection (Priority: P2)

**Goal**: A runner script wraps Playwright execution with 1Password credential injection so no secrets are hardcoded

**Independent Test**: Run `./tests/e2e/run-e2e.sh` on a machine with 1Password CLI configured — full test suite passes with credentials resolved from vault

### Implementation for User Story 4

- [x] T008 [US4] Create `tests/e2e/run-e2e.sh` — bash script with `#!/usr/bin/env bash` and `set -euo pipefail`; check `op --version` is available (exit with error if not); export env vars using `op://test/secman/...` URI format for `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`, `SECMAN_USER_USER`, `SECMAN_USER_PASS`; accept optional `SECMAN_BACKEND_URL` (default `http://localhost:4321`); run `op run -- npx playwright test "$@"` to pass through CLI args; make script executable with `chmod +x` (implements FR-009; ref: research.md R7)

**Checkpoint**: `./tests/e2e/run-e2e.sh` executes full test matrix with 1Password-resolved credentials

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and documentation updates

- [x] T009 Validate full test matrix matches quickstart.md instructions — run both `./run-e2e.sh` and manual env var invocation; verify HTML report opens with `npx playwright show-report`
- [x] T010 [P] Update CLAUDE.md test commands section to include E2E test commands: `cd tests/e2e && npm install && npx playwright install chrome msedge` for setup, `./tests/e2e/run-e2e.sh` for execution

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup (T001 must complete for `npm install`) — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Foundational (Phase 2) — No dependencies on other stories
- **US2 (Phase 4)**: Depends on US1 (Phase 3) — same file, US1 establishes test structure
- **US3 (Phase 5)**: Depends on US1 + US2 (needs both test blocks to verify full matrix); cross-browser config already exists from Phase 2
- **US4 (Phase 6)**: Depends on Foundational (Phase 2) only — runner script is an independent file, but practically useful after US1+US2 exist
- **Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

```
Phase 1 (Setup) ──► Phase 2 (Config) ──┬──► Phase 3 (US1: Admin) ──► Phase 4 (US2: Normal) ──► Phase 5 (US3: Verify Matrix)
                                        │                                                              │
                                        └──► Phase 6 (US4: Runner Script) ─────────────────────────────┘
                                                                                                       │
                                                                                                       ▼
                                                                                              Phase 7 (Polish)
```

### Parallel Opportunities

- T001 and T002 can run in parallel (different files, no dependencies)
- T008 (US4: runner script) can be developed in parallel with T005/T006 (US1/US2: test file) since they are separate files
- T009 and T010 can run in parallel

---

## Parallel Example: Setup Phase

```bash
# Launch setup tasks together:
Task T001: "Create tests/e2e/package.json"
Task T002: "Create tests/e2e/.gitignore"
```

## Parallel Example: After Foundational Phase

```bash
# These can be developed in parallel (different files):
Task T005: "Implement admin test in tests/e2e/vuln-lense.spec.ts"  (US1)
Task T008: "Create tests/e2e/run-e2e.sh runner script"             (US4)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (`package.json`, `.gitignore`)
2. Complete Phase 2: Foundational (`playwright.config.ts`)
3. Complete Phase 3: User Story 1 (admin test in `vuln-lense.spec.ts`)
4. **STOP and VALIDATE**: `npx playwright test --project=chrome -g "Admin"` should pass
5. MVP delivered — admin login and Lense navigation verified on Chrome

### Incremental Delivery

1. Setup + Foundational → Project ready
2. Add US1 (admin test) → Validate on Chrome → **MVP!**
3. Add US2 (normal user test) → Both users verified on Chrome
4. Run US3 (cross-browser) → Full 4-combination matrix green
5. Add US4 (1Password runner) → Secure credential injection works
6. Polish → Documentation updated, quickstart validated

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- US3 (cross-browser) has no dedicated code artifact — it's delivered by the Playwright config's `projects` array in Phase 2
- Total files created: 5 (`package.json`, `.gitignore`, `playwright.config.ts`, `vuln-lense.spec.ts`, `run-e2e.sh`)
- No changes to existing source code — this is a pure addition of test infrastructure
- Commit after each phase completion for clean git history
