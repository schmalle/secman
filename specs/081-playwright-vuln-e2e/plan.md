# Implementation Plan: Playwright E2E Test for Vulnmanagement Lense

**Branch**: `081-playwright-vuln-e2e` | **Date**: 2026-03-04 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/081-playwright-vuln-e2e/spec.md`

## Summary

Implement a Playwright E2E test suite that logs in as two different users (ADMIN and VULN-role), navigates to the Vulnerability Management "Lense" submenu, and verifies the page renders structurally with zero JavaScript console errors. Tests run on both Chrome and Edge. Credentials are injected via environment variables from `pass-cli` (Proton Pass).

## Technical Context

**Language/Version**: TypeScript (Playwright test files), Bash (runner script)
**Primary Dependencies**: @playwright/test 1.57.0 (Astro-compatible version)
**Storage**: N/A (no data persistence — test infrastructure only)
**Testing**: @playwright/test with built-in test runner (`npx playwright test`)
**Target Platform**: macOS/Linux (developer workstation or CI runner)
**Project Type**: Test infrastructure (standalone E2E tests against running frontend)
**Performance Goals**: Full test matrix (2 users × 2 browsers = 4 runs) completes in <2 minutes
**Constraints**: Requires running secman instance, `pass-cli` (Proton Pass) v2.x for credential injection
**Scale/Scope**: 1 test file, 1 config, 1 runner script; tests 2 users × 2 browsers = 4 combinations

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | **PASS** | No credentials hardcoded; `pass-cli` (Proton Pass) injection; env vars only |
| III. API-First | **N/A** | No new API endpoints — tests consume existing frontend |
| IV. User-Requested Testing | **PASS** | User explicitly requested this E2E test suite |
| V. RBAC | **N/A** | Tests verify existing RBAC behavior, no new access controls |
| VI. Schema Evolution | **N/A** | No database or schema changes |

No violations. No complexity tracking needed.

### Post-Design Re-check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | **PASS** | Credentials flow: Proton Pass → `pass-cli run` → env vars → Playwright. No secrets in files, logs, or reports. Runner script uses `pass://` URI pattern consistent with existing E2E tests. |
| III. API-First | **N/A** | No API changes |
| IV. User-Requested Testing | **PASS** | Entire feature is user-requested testing infrastructure |
| V. RBAC | **N/A** | Tests validate existing role behavior (ADMIN vs VULN menu visibility) |
| VI. Schema Evolution | **N/A** | No schema changes |

All gates pass post-design. No new violations introduced.

## Project Structure

### Documentation (this feature)

```text
specs/081-playwright-vuln-e2e/
├── plan.md              # This file
├── research.md          # Phase 0: Technical research
├── quickstart.md        # Phase 1: How to run the tests
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
tests/
├── e2e/
│   ├── playwright.config.ts        # Playwright config (Chrome + Edge projects)
│   ├── vuln-lense.spec.ts          # Test: login → navigate → Lense → verify
│   └── run-e2e.sh                  # Runner script with Proton Pass integration
```

**Structure Decision**: Tests live in `tests/e2e/` alongside existing `tests/*.sh` E2E scripts. This keeps all E2E tests in one location. The Playwright config and test files are self-contained — no changes to `src/frontend/` are needed since `@playwright/test` is installed locally in the test directory or invoked via npx.
