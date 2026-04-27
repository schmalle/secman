# Research: JavaScript Error Scanner

**Branch**: `083-js-error-scanner` | **Date**: 2026-03-20

## Research Findings

### 1. Authentication Mechanism

**Decision**: Use browser-based form login, not direct API token injection.

**Rationale**: The secman backend stores JWT tokens in HttpOnly cookies (`secman_auth`), which are inaccessible to JavaScript. Playwright's browser context automatically retains cookies set by the server after form submission, making browser-based login the natural approach. This also validates the real login flow.

**Alternatives considered**:
- Direct API call to `/api/auth/login` + manual cookie injection into Playwright context → Rejected because HttpOnly cookies cannot be set via `page.evaluate()` or standard Playwright cookie APIs without extracting the `Set-Cookie` header from a raw HTTP response, adding unnecessary complexity.
- Using Playwright's `storageState` to persist auth → Useful for test suites with multiple test files, but overkill for a single sequential scan.

**Login flow details**:
- Navigate to `/login`
- Fill `#username` and `#password` inputs
- Click `button[type="submit"]`
- Wait for URL to no longer contain `/login` (15s timeout)
- Cookie `secman_auth` is set automatically by the backend response

### 2. Page Discovery Strategy

**Decision**: Hardcode the static page list in the Node.js script, derived from `src/frontend/src/pages/**/*.astro`.

**Rationale**: Astro uses file-based routing. The complete list of ~40 static pages can be enumerated at development time. Dynamic crawling via link-following would miss admin-only pages (sidebar links hidden for non-admin users) and pages behind conditional rendering.

**Static pages identified** (excluding `[id]`/`[token]` dynamic segments):
- Root: `/`
- Auth: `/login`, `/login/success`
- Core: `/assets`, `/asset`, `/scans`, `/import`, `/export`, `/import-export`
- Vulnerabilities: `/vulnerabilities/current`, `/vulnerabilities/domain`, `/vulnerabilities/system`, `/vulnerabilities/exceptions`, `/vulnerability-statistics`, `/wg-vulns`, `/account-vulns`
- Requirements: `/requirements`, `/standards`, `/norms`, `/usecases`, `/demands`, `/products`, `/reqdl`
- Risk: `/risks`, `/risk-assessments`, `/riskassessment`
- Releases: `/releases`, `/releases/compare`
- User: `/profile`, `/notification-preferences`, `/notification-logs`, `/my-exception-requests`, `/exception-approvals`, `/workgroups`, `/user-management`, `/aws-account-sharing`
- Reports: `/reports`, `/public-classification`
- Admin: `/admin`, `/admin/user-management`, `/admin/identity-providers`, `/admin/email-config`, `/admin/falcon-config`, `/admin/vulnerability-config`, `/admin/translation-config`, `/admin/notification-settings`, `/admin/maintenance-banners`, `/admin/requirements`, `/admin/releases`, `/admin/user-mappings`, `/admin/test-email-accounts`, `/admin/classification-rules`, `/admin/config-bundle`, `/admin/mcp-api-keys`, `/admin/app-settings`, `/admin/aws-account-sharing`, `/admin/ec2-compliance`
- About: `/about`

**Excluded** (dynamic segments): `/outdated-assets/[id]`, `/releases/[id]`, `/releases/[id]/alignment`, `/standards/[id]`, `/respond/[token]`, `/alignment/review/[token]`, `/alignment/results/[token]`, `/admin/ec2-compliance/[id]`

**Also excluded**: `/outdated-assets` (kept — it's a static index page)

### 3. Playwright Library API vs Test Runner

**Decision**: Use Playwright's library API (`playwright` package) directly, not `@playwright/test`.

**Rationale**: The test runner enforces a test-per-assertion model with automatic parallelization and reporter formatting. The scanner needs a single sequential flow with custom report formatting and exit code logic. The library API (`chromium.launch()`, `browser.newContext()`, `context.newPage()`) provides full control.

**Alternatives considered**:
- `@playwright/test` with custom reporter → Would work but adds unnecessary abstraction; the reporter API is designed for test results, not page-scan reports.
- Puppeteer → Not installed in the project; Playwright already is.

### 4. Self-Signed Certificate Handling

**Decision**: Dual-layer approach — `NODE_TLS_REJECT_UNAUTHORIZED=0` in bash + `ignoreHTTPSErrors: true` in Playwright.

**Rationale**: `NODE_TLS_REJECT_UNAUTHORIZED=0` handles any Node.js-level HTTPS calls (e.g., if the script makes direct fetch calls). `ignoreHTTPSErrors: true` in the Playwright browser context handles the browser's certificate validation. Both are needed for complete coverage.

**Detection**: Read `SECMAN_SSL_ACCEPT_ALL` from environment (resolved by `op run` from 1Password). Parse `true`/`1`/`yes` case-insensitively, matching the pattern in `./scriptpp/secmanng`.

### 5. 1Password Credential Fields

**Decision**: Use `SECMAN_ADMIN_NAME` and `SECMAN_ADMIN_PASS` from `op://test/secman/` vault, plus `SECMAN_HOST` and `SECMAN_SSL_ACCEPT_ALL`.

**Rationale**: These are the same fields used by `./scriptpp/secmanng`. The e2e tests use different fields (`SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`) but the user explicitly requested the `./scriptpp/secmanng` pattern.

**Fields**:
- `SECMAN_ADMIN_NAME` → `op://test/secman/SECMAN_ADMIN_NAME`
- `SECMAN_ADMIN_PASS` → `op://test/secman/SECMAN_ADMIN_PASS`
- `SECMAN_BACKEND_URL` → `op://test/secman/SECMAN_HOST` (the backend/frontend host)
- `SECMAN_INSECURE` → `op://test/secman/SECMAN_SSL_ACCEPT_ALL`

### 6. Page Load Wait Strategy

**Decision**: Use `page.waitForLoadState('networkidle')` with a 30-second per-page timeout.

**Rationale**: `networkidle` waits until there are no more than 0 network connections for 500ms, which catches async data fetches that React components make on mount. This is the same pattern used in the existing `vuln-lense.spec.ts`. The 30-second timeout matches Playwright's default navigation timeout and is generous enough for data-heavy pages.

**Alternatives considered**:
- `domcontentloaded` → Too early; React components haven't hydrated or fetched data yet.
- Fixed `setTimeout` → Unreliable; some pages load faster, some slower.
- `load` event → Doesn't wait for async fetches after initial page load.

### 7. Existing Playwright Installation

**Decision**: Reuse `tests/e2e/node_modules/playwright` via `NODE_PATH`.

**Rationale**: Playwright v1.58.2 is already installed in `tests/e2e/`. Setting `NODE_PATH=tests/e2e/node_modules` in the bash wrapper allows the standalone `.mjs` script to import from it without a separate `npm install`.

**Browser binaries**: Playwright browsers are installed globally in `~/.cache/ms-playwright/`. If the user has run `npx playwright install` from `tests/e2e/`, browsers are already available.
