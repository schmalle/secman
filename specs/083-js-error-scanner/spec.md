# Feature Specification: JavaScript Error Scanner Test Script

**Feature Branch**: `083-js-error-scanner`
**Created**: 2026-03-20
**Status**: Draft
**Input**: User description: "Create a test script that retrieves authentication data and SECMAN_HOST the same way as ./scriptpp/secmanng, logs in using SECMAN_USER and SECMAN_ADMIN_PASS from 1Password, visits every page in secman, and reports which subpages/URIs contain JavaScript errors. Must work with self-signed certificates."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Run JS Error Scan Against Secman Instance (Priority: P1)

A developer or QA engineer runs a single test script from the `tests/` directory to automatically authenticate against a secman instance and visit every page in the application. The script collects any JavaScript errors encountered on each page and produces a clear report showing which URIs had errors and which were clean. This allows the team to catch client-side regressions without manually clicking through every page.

**Why this priority**: This is the core purpose of the script — without page crawling and JS error detection, the tool has no value.

**Independent Test**: Can be fully tested by running the script against any running secman instance and verifying it produces a report listing pages visited and any JS errors found.

**Acceptance Scenarios**:

1. **Given** a running secman instance with valid credentials in 1Password, **When** the script is executed, **Then** it authenticates successfully and visits all known secman pages.
2. **Given** a page with a JavaScript error (e.g., undefined variable, failed API call), **When** the script visits that page, **Then** the error is captured and associated with the page URI in the report.
3. **Given** all pages load without JavaScript errors, **When** the script completes, **Then** it reports a clean result with zero errors and lists all pages visited.

---

### User Story 2 - 1Password Credential Integration (Priority: P1)

The script retrieves SECMAN_USER, SECMAN_ADMIN_PASS, and SECMAN_HOST from 1Password using the same `op://test/secman/` vault references as the existing `./scriptpp/secmanng` wrapper. This ensures credentials are never hardcoded and the script works consistently with the existing toolchain.

**Why this priority**: Without secure credential retrieval, the script cannot authenticate. This is a prerequisite for all other functionality.

**Independent Test**: Can be tested by running the script and observing it resolves 1Password references and authenticates — verified by a successful login (no auth errors in output).

**Acceptance Scenarios**:

1. **Given** 1Password CLI (`op`) is installed and the user is authenticated, **When** the script runs, **Then** credentials are resolved from `op://test/secman/` vault references.
2. **Given** 1Password CLI is not installed, **When** the script runs, **Then** it exits immediately with a clear error message explaining the dependency.
3. **Given** the 1Password session has expired, **When** the script runs, **Then** `op run` prompts for re-authentication before proceeding.

---

### User Story 3 - Self-Signed Certificate Support (Priority: P1)

The script works against secman instances using self-signed TLS certificates without failing on certificate validation errors. It reads the `SECMAN_SSL_ACCEPT_ALL` flag from 1Password (same as `./scriptpp/secmanng`) and configures the headless browser to accept self-signed certificates when enabled.

**Why this priority**: Many development and staging environments use self-signed certificates; without this, the script would fail in the most common testing scenarios.

**Independent Test**: Can be tested by pointing the script at a secman instance with a self-signed certificate and verifying it completes without TLS errors.

**Acceptance Scenarios**:

1. **Given** `SECMAN_SSL_ACCEPT_ALL` is set to `true`/`1`/`yes` in 1Password, **When** the script connects to an instance with a self-signed certificate, **Then** the connection succeeds without TLS errors.
2. **Given** `SECMAN_SSL_ACCEPT_ALL` is not set or is `false`, **When** the script connects to an instance with a self-signed certificate, **Then** the default certificate validation behavior applies.

---

### User Story 4 - Clear Error Report Output (Priority: P2)

After visiting all pages, the script produces a structured summary report showing: total pages visited, pages with errors (listing the URI and each error message), and pages without errors. The exit code reflects the result (0 = no errors found, non-zero = errors detected).

**Why this priority**: The report format determines whether the output is actionable. A clear, structured report makes it easy to identify and fix issues.

**Independent Test**: Can be tested by running the script and verifying the output format includes a summary section with page counts and error details grouped by URI.

**Acceptance Scenarios**:

1. **Given** the scan has completed, **When** results are displayed, **Then** the report groups errors by page URI, shows the error message for each, and labels each as either "UNCAUGHT EXCEPTION" or "CONSOLE ERROR."
2. **Given** errors were found on some pages, **When** the script exits, **Then** the exit code is non-zero (1).
3. **Given** no errors were found, **When** the script exits, **Then** the exit code is 0.

---

### Edge Cases

- What happens when a page requires specific data to render (e.g., `/outdated-assets/[id]`, `/releases/[id]`)? The script should skip dynamic-parameter pages that require specific IDs unless valid IDs can be retrieved from API queries.
- What happens when a page redirects to login because the session expired mid-scan? The script should detect auth failures and re-authenticate or report the failure clearly.
- What happens when a page takes very long to load? A per-page timeout should prevent the script from hanging indefinitely; timed-out pages should be reported as such rather than counted as JS errors.
- What happens when the secman instance is unreachable? The script should fail fast with a clear connectivity error rather than attempting to visit all pages.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Script MUST retrieve `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`, `SECMAN_HOST`, and `SECMAN_SSL_ACCEPT_ALL` from 1Password using `op://test/secman/` vault references, matching the pattern used in `./scriptpp/secmanng`.
- **FR-002**: Script MUST authenticate against the secman instance by performing a login using the retrieved credentials and storing the authentication token for subsequent page visits.
- **FR-003**: Script MUST visit all statically-routable pages in secman (pages without dynamic `[id]` parameters) using a headless browser that captures JavaScript console errors.
- **FR-004**: Script MUST capture both uncaught JavaScript exceptions and `console.error` messages on each page, labeling them separately in the report (e.g., "UNCAUGHT EXCEPTION" vs. "CONSOLE ERROR") and associating each with the page URI. Both types affect the exit code.
- **FR-005**: Script MUST support self-signed TLS certificates when `SECMAN_SSL_ACCEPT_ALL` is set to `true`/`1`/`yes` (case-insensitive).
- **FR-006**: Script MUST produce a summary report listing: total pages visited, count of pages with errors, count of clean pages, and for each errored page the URI and the specific error messages — with uncaught exceptions and console errors clearly distinguished by label.
- **FR-007**: Script MUST exit with code 0 when no JavaScript errors are found, and exit with code 1 when errors are detected.
- **FR-008**: Script MUST apply a per-page timeout to prevent hanging on unresponsive pages, reporting timed-out pages separately from JS errors.
- **FR-009**: Script MUST check for 1Password CLI availability before proceeding and display a clear error message if it is not installed.
- **FR-010**: Script MUST be a standalone executable script located in the `tests/` directory with a descriptive name.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The script completes a full scan of all static secman pages in under 5 minutes on a typical development instance.
- **SC-002**: 100% of JavaScript errors present on visited pages are captured and reported — no silent failures.
- **SC-003**: The script can be run by any team member with 1Password access using a single command with no manual setup beyond having the `op` CLI and a headless browser available.
- **SC-004**: The exit code accurately reflects the scan result: 0 for clean, non-zero for errors found — enabling use in automated checks.
- **SC-005**: The script successfully operates against instances with self-signed certificates without manual certificate installation or browser configuration by the user.

## Clarifications

### Session 2026-03-20

- Q: Should the scanner capture only uncaught JavaScript exceptions or also `console.error` messages? → A: Both, labeled separately in the report ("UNCAUGHT EXCEPTION" vs. "CONSOLE ERROR"). Both types affect the exit code.

## Assumptions

- The secman frontend is an Astro + React application with file-based routing; all page URIs can be derived from the `src/frontend/src/pages/` directory structure.
- Pages requiring dynamic parameters (e.g., `/outdated-assets/[id]`, `/releases/[id]`) will be skipped rather than requiring test data setup, as the goal is a quick smoke test of all static pages.
- The script will use a headless browser (Playwright is already a project dependency in `tests/e2e/`) with built-in console error capture and self-signed certificate support.
- The 1Password vault `test/secman` contains all required fields: `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`, `SECMAN_HOST`, and `SECMAN_SSL_ACCEPT_ALL`.
- Admin-only pages may show permission errors for non-admin users; permission-denied UI responses are not counted as JavaScript errors unless they cause uncaught exceptions.
