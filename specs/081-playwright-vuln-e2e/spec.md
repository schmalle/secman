# Feature Specification: Playwright E2E Test for Vulnmanagement Lense

**Feature Branch**: `081-playwright-vuln-e2e`
**Created**: 2026-03-03
**Status**: Draft
**Input**: User description: "Implement an end-to-end test case to login via Playwright and then go to the Vulnmanagement Lense submenu. No JavaScript errors must occur. Two different accounts: one with admin role and one normal user. Credentials must be passed via command line injected from 1Password. Playwright must use Edge and Chrome browsers for testing."

## Clarifications

### Session 2026-03-03

- Q: What defines "Vulnerability Statistics page loads successfully"? → A: A key page element (heading or main container) becomes visible, confirming structural rendering without coupling to data state.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Admin User Navigates to Vulnmanagement Lense (Priority: P1)

A QA engineer runs the Playwright E2E test suite with admin credentials injected from 1Password. The test logs in as an admin user, navigates to the Vulnerability Management section in the sidebar, clicks the "Lense" submenu item, and verifies the Vulnerability Statistics page loads successfully. The browser console is monitored throughout — no JavaScript errors must occur during the entire flow.

**Why this priority**: Admin users have full access to all Vulnerability Management menu items. Testing the admin path first validates the complete navigation structure and confirms the Lense page renders without errors for the most privileged role.

**Independent Test**: Can be fully tested by running the Playwright test with admin credentials against a running secman instance, verifying login success, menu navigation, page load, and zero JS console errors.

**Acceptance Scenarios**:

1. **Given** the secman application is running and accessible, **When** the test logs in with valid admin credentials, **Then** the user is authenticated and the dashboard loads
2. **Given** the admin user is logged in, **When** the test clicks the Vulnerability Management menu section in the sidebar, **Then** the submenu expands showing all vulnerability menu items including "Lense"
3. **Given** the Vulnerability Management submenu is expanded, **When** the test clicks the "Lense" menu item, **Then** the page navigates to `/vulnerability-statistics` and a key page element (heading or main container) becomes visible
4. **Given** the entire test flow from login to Lense page load, **When** the browser console is monitored, **Then** zero JavaScript errors are recorded

---

### User Story 2 - Normal User Navigates to Vulnmanagement Lense (Priority: P1)

A QA engineer runs the same Playwright E2E test suite with a normal (non-admin) user account that has vulnerability access (VULN role). The test logs in, navigates to the Vulnerability Management section, clicks the "Lense" submenu item, and verifies the page loads without JavaScript errors. The normal user sees a slightly different menu (e.g., no "Vuln overview" item, but "Account vulns" and "WG vulns" are visible instead).

**Why this priority**: Equal priority to admin because testing both privilege levels is essential to confirm the Lense page works regardless of role, and that role-based menu rendering does not cause JS errors.

**Independent Test**: Can be fully tested by running the Playwright test with normal user credentials, verifying login, menu navigation to Lense, and zero JS console errors.

**Acceptance Scenarios**:

1. **Given** the secman application is running, **When** the test logs in with valid normal user credentials (VULN role), **Then** the user is authenticated and the dashboard loads
2. **Given** the normal user is logged in, **When** the test clicks the Vulnerability Management menu section, **Then** the submenu expands showing the menu items appropriate for the user's role
3. **Given** the submenu is expanded, **When** the test clicks "Lense", **Then** the page navigates to `/vulnerability-statistics` and a key page element (heading or main container) becomes visible
4. **Given** the entire flow, **When** the browser console is monitored, **Then** zero JavaScript errors are recorded

---

### User Story 3 - Cross-Browser Execution (Priority: P2)

The E2E test suite runs on both Microsoft Edge and Google Chrome browsers. Each test scenario (admin login + Lense navigation, normal user login + Lense navigation) executes on both browsers to verify cross-browser compatibility. Both browsers must complete all tests with zero JavaScript errors.

**Why this priority**: Cross-browser validation is important but secondary to core functional verification. Once the tests pass on one browser, running them on a second browser catches rendering and compatibility issues.

**Independent Test**: Can be tested by running the Playwright test suite with the browser project flag for each browser, verifying identical pass/fail results across Chrome and Edge.

**Acceptance Scenarios**:

1. **Given** the test suite is configured for multiple browsers, **When** tests run on Google Chrome, **Then** all login and navigation scenarios pass with zero JS errors
2. **Given** the test suite is configured for multiple browsers, **When** tests run on Microsoft Edge, **Then** all login and navigation scenarios pass with zero JS errors

---

### User Story 4 - 1Password Credential Injection (Priority: P2)

The test suite accepts credentials via command-line environment variables or arguments. These credentials are injected from 1Password using the `op` CLI tool (e.g., `op run` or `op read`), ensuring no credentials are hardcoded in test files, environment files, or CI configuration. The test runner script demonstrates the 1Password integration pattern.

**Why this priority**: Secure credential management is critical for a security tool's own test infrastructure, but the injection mechanism is an infrastructure concern that can be refined after core tests work.

**Independent Test**: Can be tested by running the test with `op run` wrapper or by manually providing environment variables, confirming the tests pick up credentials from the environment.

**Acceptance Scenarios**:

1. **Given** credentials are set via environment variables, **When** the test suite runs, **Then** it uses those credentials for login without any hardcoded fallback
2. **Given** 1Password CLI is available, **When** the test is invoked through the provided runner script with `op` integration, **Then** credentials are securely injected at runtime
3. **Given** no credentials are provided, **When** the test suite starts, **Then** it fails immediately with a clear error message indicating which credentials are missing

---

### Edge Cases

- What happens when the application is not reachable at the configured URL? The test should fail fast with a clear timeout error.
- What happens when credentials are invalid? The test should detect the login failure and report it clearly rather than timing out on page navigation.
- What happens when the user account lacks vulnerability access (no VULN, ADMIN, or SECCHAMPION role)? The Vulnerability Management menu section would not appear, and the test should fail with a meaningful assertion.
- What happens when a JavaScript warning (not error) is logged? Warnings should not cause test failure — only `error`-level console messages should fail the test.
- What happens when the page loads slowly? The test should use reasonable timeouts for navigation and element visibility.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The test suite MUST authenticate users via the login form (username/password submission to the application)
- **FR-002**: The test suite MUST support two separate user accounts — one with ADMIN role and one with a non-admin role that has vulnerability access (e.g., VULN role)
- **FR-003**: The test suite MUST navigate to the Vulnerability Management section in the sidebar and click the "Lense" submenu item
- **FR-004**: The test suite MUST verify that the Vulnerability Statistics page (`/vulnerability-statistics`) renders structurally after clicking "Lense" — confirmed by a key page element (heading or main container) becoming visible, independent of data loading state
- **FR-005**: The test suite MUST monitor the browser console throughout the entire test flow and fail if any JavaScript error-level messages are detected
- **FR-006**: The test suite MUST run on both Google Chrome and Microsoft Edge browsers
- **FR-007**: The test suite MUST accept user credentials via environment variables (e.g., `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS`, `SECMAN_USER_USER`, `SECMAN_USER_PASS`)
- **FR-008**: The test suite MUST accept the application base URL via environment variable (e.g., `SECMAN_BACKEND_URL`)
- **FR-009**: The test suite MUST provide a runner script demonstrating 1Password CLI (`op`) integration for credential injection
- **FR-010**: The test suite MUST fail with a clear error message when required credentials or base URL are not provided
- **FR-011**: JavaScript warnings and informational console messages MUST NOT cause test failure — only `error`-level messages should fail the test

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Both admin and normal user test scenarios complete successfully on Chrome and Edge with zero JavaScript console errors
- **SC-002**: The test suite runs to completion (all 4 combinations: 2 users x 2 browsers) in under 2 minutes on a standard development machine
- **SC-003**: When credentials are missing, the test fails within 5 seconds with a message naming the missing variable
- **SC-004**: When the application is unreachable, the test fails within 30 seconds with a connection error rather than hanging
- **SC-005**: The test produces a clear pass/fail report identifying which browser/user combinations succeeded or failed

## Assumptions

- The secman application is already running and accessible at a known URL before the test suite is executed (the tests do not start/stop the application)
- The admin and normal user accounts already exist in the system with appropriate roles assigned
- The normal user account has at least the VULN role so the Vulnerability Management menu section is visible
- 1Password CLI (`op`) v2.x is installed on the machine where the runner script is used
- The target environment has the necessary 1Password vault access configured for the QA engineer
- Microsoft Edge is Chromium-based (as all modern Edge versions are), so Playwright's `msedge` channel is used
- The application login uses the standard username/password form (not OAuth/OIDC) for these E2E tests
