# Research: Playwright E2E Test for Vulnmanagement Lense

**Date**: 2026-03-04 | **Feature**: 081-playwright-vuln-e2e

## R1: Playwright Installation Strategy

**Decision**: Install `@playwright/test` as a devDependency in `tests/e2e/` with its own `package.json`, keeping it isolated from the frontend project.

**Rationale**: The frontend (`src/frontend/`) uses Astro which has `@playwright/test` as a transitive devDependency, but relying on a transitive dependency is fragile. A dedicated `package.json` in `tests/e2e/` makes the test suite self-contained and avoids polluting frontend dependencies.

**Alternatives considered**:
- Install in `src/frontend/package.json` — Rejected: mixes test infra with production frontend
- Use global Playwright install — Rejected: not reproducible across machines
- Use Astro's transitive `@playwright/test` — Rejected: version coupling, fragile

## R2: Browser Configuration (Chrome + Edge)

**Decision**: Use Playwright's `channel` option to run real Chrome and Edge browsers instead of Playwright's bundled Chromium.

**Rationale**: The spec requires Chrome and Edge specifically. Playwright supports `channel: 'chrome'` for Google Chrome and `channel: 'msedge'` for Microsoft Edge. Both are Chromium-based but use the system-installed browser binaries, matching real user conditions.

**Configuration**:
```typescript
projects: [
  { name: 'chrome', use: { channel: 'chrome' } },
  { name: 'msedge', use: { channel: 'msedge' } },
]
```

**Alternatives considered**:
- Use Playwright bundled Chromium — Rejected: doesn't satisfy "Chrome and Edge" requirement
- Use WebKit/Firefox as additional targets — Rejected: not requested, adds complexity

## R3: Console Error Monitoring Pattern

**Decision**: Attach a `page.on('console')` listener at test setup that collects `error`-level messages into an array, then assert the array is empty after each test step.

**Rationale**: Playwright's console event fires for all console message types. Filtering to `msg.type() === 'error'` satisfies FR-005 (fail on errors) and FR-011 (ignore warnings/info). Collecting errors in an array allows a single assertion at the end with full error details in the failure message.

**Pattern**:
```typescript
const consoleErrors: string[] = [];
page.on('console', msg => {
  if (msg.type() === 'error') {
    consoleErrors.push(`${msg.text()}`);
  }
});
// ... test steps ...
expect(consoleErrors).toEqual([]);
```

**Alternatives considered**:
- `page.on('pageerror')` only — Rejected: catches uncaught exceptions but misses `console.error()` calls
- Screenshot comparison for error states — Rejected: over-engineered for this use case

## R4: Login Flow Selectors

**Decision**: Use ID-based selectors for login form fields, matching the existing pattern from `debug-login.js`.

**Rationale**: The login form has stable `id` attributes: `input[id="username"]`, `input[id="password"]`, `button[type="submit"]`. These are already proven in the existing debug script.

**Selectors**:
| Element | Selector | Source |
|---------|----------|--------|
| Username field | `#username` | Login.tsx line 193 |
| Password field | `#password` | Login.tsx line 205 |
| Submit button | `button[type="submit"]` | Login.tsx line 223 |

## R5: Sidebar Navigation Selectors

**Decision**: Use text-based Playwright locators for sidebar navigation, with `getByRole('link')` for the Lense menu item.

**Rationale**: The sidebar has no `data-testid` attributes. Text-based selectors match the user-visible behavior and are resilient to CSS class changes. The "VULNERABILITY MANAGEMENT" header uses a clickable div, and "Lense" is a standard `<a>` link.

**Selectors**:
| Element | Selector | Notes |
|---------|----------|-------|
| Vuln Management header | `page.getByText('VULNERABILITY MANAGEMENT')` | Clickable div to expand submenu |
| Lense menu item | `page.getByRole('link', { name: 'Lense' })` | `<a href="/vulnerability-statistics">` |

## R6: Page Load Verification

**Decision**: Assert the `<h1>` heading "Vulnerability Statistics Lense" becomes visible, confirming structural rendering.

**Rationale**: Per clarification session (2026-03-03), "loads successfully" means a key page element becomes visible. The `<h1>` with text "Vulnerability Statistics Lense" is the most stable structural marker — it renders on component mount regardless of API data state.

**Selector**: `page.getByRole('heading', { name: /Vulnerability Statistics Lense/ })`

**Source**: `VulnerabilityStatisticsPage.tsx` line 66-69

## R7: 1Password Integration Pattern

**Decision**: Follow the existing project pattern using `op://test/secman/...` URI format with a bash runner script that resolves credentials before invoking Playwright.

**Rationale**: The project already uses this pattern in `tests/mcp-e2e-workgroup-test.sh`. Consistency reduces cognitive overhead. The runner script uses `op run` which auto-resolves `op://` URIs in environment variables.

**Environment variables**:
| Variable | 1Password URI | Purpose |
|----------|---------------|---------|
| `SECMAN_BACKEND_URL` | (plain value) | Frontend URL, e.g., `http://localhost:4321` |
| `SECMAN_ADMIN_NAME` | `op://test/secman/SECMAN_ADMIN_NAME` | Admin username |
| `SECMAN_ADMIN_PASS` | `op://test/secman/SECMAN_ADMIN_PASS` | Admin password |
| `SECMAN_USER_USER` | `op://test/secman/SECMAN_USER_USER` | Normal user username |
| `SECMAN_USER_PASS` | `op://test/secman/SECMAN_USER_PASS` | Normal user password |

## R8: Test File Structure

**Decision**: Single test file `vuln-lense.spec.ts` with parameterized test using `test.describe` blocks per user role, running across both browser projects.

**Rationale**: The test flow is identical for both users (login → navigate → verify). Using Playwright's project matrix (2 browsers) × describe blocks (2 users) naturally produces the 4 combinations specified in SC-001. A single file keeps related tests together.

**Test structure**:
```
describe('Admin user')
  test('login and navigate to Vulnmanagement Lense')

describe('Normal user')
  test('login and navigate to Vulnmanagement Lense')
```

Playwright config projects (`chrome`, `msedge`) run each describe block → 4 total test runs.
