# Implementation Plan: JavaScript Error Scanner

**Branch**: `083-js-error-scanner` | **Date**: 2026-03-20 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/083-js-error-scanner/spec.md`

## Summary

Create a standalone test script that authenticates against a secman instance using 1Password-stored credentials, visits all statically-routable frontend pages via a headless browser, captures both uncaught JavaScript exceptions and `console.error` messages (labeled separately), and produces a structured report with an exit code reflecting the result. Must support self-signed certificates.

**Technical Approach**: A bash wrapper script (`tests/js-error-scanner.sh`) handles 1Password credential resolution via `op run`, then invokes a Node.js script (`tests/js-error-scanner.mjs`) that uses the Playwright library API to drive a headless Chromium browser. The Node.js script performs form-based login, iterates through a hardcoded list of static page URIs (derived from `src/frontend/src/pages/`), captures `pageerror` events and `console.error` messages per page, and outputs a grouped summary report.

## Technical Context

**Language/Version**: Bash 5.x (wrapper), Node.js (script using Playwright API)
**Primary Dependencies**: Playwright (from existing `tests/e2e/node_modules/`), 1Password CLI (`op`)
**Storage**: N/A (no persistence)
**Testing**: Manual execution against running secman instance
**Target Platform**: macOS / Linux (developer workstation)
**Project Type**: Test tooling / CLI script
**Performance Goals**: Full scan of ~40 static pages in under 5 minutes
**Constraints**: Must work with self-signed certificates; no new npm dependencies beyond existing Playwright
**Scale/Scope**: ~40 static pages, single user session, single browser instance

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Applicable? | Status | Notes |
|-----------|-------------|--------|-------|
| I. Security-First | Yes | PASS | Credentials from 1Password, never hardcoded; no user input accepted beyond env vars |
| III. API-First | No | N/A | This is a test script, not an API endpoint |
| IV. User-Requested Testing | Yes | PASS | This IS the user-requested test tooling |
| V. RBAC | No | N/A | Script uses existing auth, doesn't modify access control |
| VI. Schema Evolution | No | N/A | No database changes |

No violations. All applicable gates pass.

## Project Structure

### Documentation (this feature)

```text
specs/083-js-error-scanner/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output (minimal — no data model)
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
tests/
├── js-error-scanner.sh      # Bash wrapper: 1Password creds, op run, SSL config
└── js-error-scanner.mjs     # Node.js script: Playwright browser automation & report
```

**Structure Decision**: Two files in `tests/` directory, following the existing pattern of standalone test scripts (e.g., `release-e2e-test.sh`, `mcp-e2e-workgroup-test.sh`). The `.mjs` extension enables ES module imports for Playwright. Reuses the existing Playwright installation from `tests/e2e/node_modules/` — no new `package.json` or `npm install` required.

## Key Design Decisions

### 1. Bash Wrapper + Node.js Script (not pure Bash, not Playwright Test Runner)

**Why not pure Bash (curl)?** JavaScript error detection requires a real browser that executes JS. `curl` only fetches HTML and cannot detect runtime JS errors.

**Why not `@playwright/test` runner?** The Playwright test runner (`npx playwright test`) is designed for assertion-based tests with pass/fail per test case. The scanner needs custom output formatting (grouped error report), custom exit code logic (0 = clean, 1 = errors found), and iterates through ~40 pages in a single flow. A direct Playwright library script gives full control.

**Why reuse `tests/e2e/node_modules/`?** Playwright is already installed there (v1.58.2). The Node.js script resolves it via `NODE_PATH` or relative path, avoiding duplicate installations.

### 2. Hardcoded Page List (not dynamic crawling)

The page list is derived from `src/frontend/src/pages/*.astro` at development time and hardcoded in the script. This is more reliable than dynamic link crawling because:
- Astro pages may not all be linked from a single entry point
- Admin pages require specific roles to see navigation links
- Dynamic crawling could miss pages behind conditional rendering

Pages with `[id]`, `[token]` dynamic segments are excluded from the list.

### 3. Authentication via Browser Form (not API + cookie injection)

The script logs in through the actual login form (`/login` page) rather than calling `/api/auth/login` directly. This is because:
- The JWT is stored in an HttpOnly cookie (`secman_auth`) — JavaScript cannot read it
- Playwright's browser context automatically retains cookies after form login
- This tests the real login flow as a bonus

### 4. Self-Signed Certificate Support

- **Bash wrapper**: Reads `SECMAN_SSL_ACCEPT_ALL` from 1Password, sets `NODE_TLS_REJECT_UNAUTHORIZED=0` when enabled
- **Playwright**: Uses `ignoreHTTPSErrors: true` in browser context launch options when SSL flag is set
- Follows the same `true`/`1`/`yes` case-insensitive pattern as `./scripts/secmanng`

### 5. Error Classification

Two distinct capture channels, labeled separately:
- **UNCAUGHT EXCEPTION**: Captured via `page.on('pageerror', err => ...)` — real unhandled JS errors
- **CONSOLE ERROR**: Captured via `page.on('console', msg => { if (msg.type() === 'error') ... })` — explicit `console.error()` calls

Both types affect the exit code per clarification decision.
