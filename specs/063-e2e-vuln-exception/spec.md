# Feature Specification: E2E Vulnerability Exception Workflow Test Suite

**Feature Branch**: `063-e2e-vuln-exception`
**Created**: 2026-01-14
**Status**: Draft
**Input**: User description: "i want to have a test suite and corresponding backend and MCP / CLI functionality for the following use case. I want to delete all assets via CLI / MCP (ADMIN role required), i want to add a user (apple@schmall.io), i want to add an asset and a vulnerability which is 10 days open, asset assigned to apple@schmall.io, i want to access as apple@schmall.io via mcp and see the vulnerability and see that apple@schmall.io does not have an overdue, then as adminuser i want to add another vulnerability to the asset from apple@schmall.io, which is 40 days open and critical. I want then to see via MCP as apple@schmall.io that i have an overdue vulnerability and ask for an exception for this dedicated vulnerability. Then login as adminuser via MCP again (password Demopassword4321%) and approve the exception. After the entire test is run, delete the user apple@schmall.io, delete the vulnerability, the asset and the exception."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Admin Prepares Clean Test Environment (Priority: P1)

As an administrator, I want to delete all existing assets via CLI/MCP to prepare a clean testing environment before running end-to-end tests.

**Why this priority**: Foundation for all subsequent tests - without a clean state, test results cannot be deterministic or repeatable.

**Independent Test**: Can be fully tested by invoking the bulk delete operation and verifying asset count returns zero.

**Acceptance Scenarios**:

1. **Given** an authenticated ADMIN user via MCP/CLI, **When** they invoke the "delete all assets" command, **Then** all assets in the system are removed and subsequent asset count queries return zero.
2. **Given** a non-ADMIN user (e.g., USER role only), **When** they attempt to invoke "delete all assets", **Then** the system returns an authorization error and no assets are deleted.
3. **Given** assets with associated vulnerabilities and exception requests, **When** admin deletes all assets, **Then** all related vulnerabilities and exception requests are cascade-deleted.

---

### User Story 2 - Admin Creates Test User and Assets (Priority: P1)

As an administrator, I want to create a test user (apple@schmall.io) and assign them assets with specific vulnerability states to set up the test scenario.

**Why this priority**: Core setup for the vulnerability exception workflow test - required to simulate the user experience.

**Independent Test**: Can be tested by creating the user, creating an asset owned by that user, and querying via MCP as that user.

**Acceptance Scenarios**:

1. **Given** an authenticated ADMIN user, **When** they create a new user with email "apple@schmall.io", **Then** the user is created and can be authenticated.
2. **Given** an authenticated ADMIN user, **When** they create an asset with owner set to "apple@schmall.io" (direct ownership), **Then** that user can see the asset when querying via MCP.
3. **Given** an authenticated ADMIN user, **When** they add a vulnerability with 10 days open (non-overdue), **Then** the vulnerability appears in the system with the correct age.

---

### User Story 3 - Standard User Views Non-Overdue Vulnerability (Priority: P2)

As apple@schmall.io, I want to view my assets and vulnerabilities via MCP and confirm I have no overdue vulnerabilities when the vulnerability is only 10 days old.

**Why this priority**: Validates the non-overdue case which is necessary before testing the overdue scenario.

**Independent Test**: Can be tested by authenticating as apple@schmall.io and querying vulnerabilities to confirm non-overdue status.

**Acceptance Scenarios**:

1. **Given** apple@schmall.io is authenticated via MCP, **When** they query their assets, **Then** they see the assigned asset with its vulnerability.
2. **Given** the vulnerability is 10 days old, **When** apple@schmall.io queries for overdue vulnerabilities, **Then** the vulnerability is NOT marked as overdue.
3. **Given** apple@schmall.io queries their overdue summary, **When** reviewing the results, **Then** the count shows zero overdue vulnerabilities.

---

### User Story 4 - Admin Adds Critical Overdue Vulnerability (Priority: P2)

As an administrator, I want to add a second vulnerability to apple@schmall.io's asset that is 40 days old and critical severity, which qualifies as overdue.

**Why this priority**: Creates the overdue state required to test the exception request workflow.

**Independent Test**: Can be tested by adding the vulnerability and querying to confirm it appears as overdue.

**Acceptance Scenarios**:

1. **Given** an authenticated ADMIN user, **When** they add a vulnerability with 40 days open and CRITICAL severity to the existing asset, **Then** the vulnerability is created with correct age and severity.
2. **Given** the 40-day-old critical vulnerability exists, **When** querying for overdue vulnerabilities, **Then** this vulnerability IS marked as overdue.

---

### User Story 5 - Standard User Requests Exception for Overdue Vulnerability (Priority: P1)

As apple@schmall.io, I want to view my overdue vulnerabilities via MCP and submit an exception request for the specific overdue vulnerability.

**Why this priority**: Core business functionality - the exception request is the primary workflow being tested.

**Independent Test**: Can be tested by querying overdue vulnerabilities and submitting an exception request, then verifying the request exists in pending state.

**Acceptance Scenarios**:

1. **Given** apple@schmall.io is authenticated via MCP, **When** they query for overdue vulnerabilities, **Then** they see the 40-day-old critical vulnerability in the overdue list.
2. **Given** apple@schmall.io identifies the overdue vulnerability, **When** they submit an exception request with a reason, **Then** the request is created with PENDING status.
3. **Given** an exception request was submitted, **When** apple@schmall.io queries their exception requests, **Then** they can see the pending request for that vulnerability.

---

### User Story 6 - Admin Approves Exception Request (Priority: P1)

As an administrator, I want to review and approve the pending exception request via MCP to complete the exception workflow.

**Why this priority**: Completes the core business workflow - approval is the resolution of the exception request.

**Independent Test**: Can be tested by authenticating as admin, viewing pending requests, and approving one.

**Acceptance Scenarios**:

1. **Given** an ADMIN user authenticates via MCP (with password "Demopassword4321%"), **When** they query pending exception requests, **Then** they see apple@schmall.io's request.
2. **Given** the admin views the pending request, **When** they approve it, **Then** the request status changes to APPROVED.
3. **Given** the exception is approved, **When** apple@schmall.io queries their exception requests, **Then** they see the request with APPROVED status.

---

### User Story 7 - Test Cleanup (Priority: P3)

As an administrator, after tests complete, I want to delete all test data (user, assets, vulnerabilities, exceptions) to leave the system in a clean state.

**Why this priority**: Lower priority as this is cleanup, but essential for test repeatability.

**Independent Test**: Can be tested by running cleanup operations and verifying all related entities are removed.

**Acceptance Scenarios**:

1. **Given** the test has completed, **When** admin deletes the user "apple@schmall.io", **Then** the user no longer exists in the system.
2. **Given** test assets exist, **When** admin deletes the assets, **Then** associated vulnerabilities and exception requests are also removed (cascade delete).
3. **Given** cleanup completes, **When** querying for test entities, **Then** none of the test data remains.

---

### Edge Cases

- What happens when attempting to delete all assets while a bulk delete is already in progress?
- How does the system handle creating a user that already exists (duplicate email)?
- What happens when requesting an exception for a vulnerability that is not overdue?
- How does the system handle approving an exception request that was already cancelled or approved?
- What happens if the test user is deleted before their exception requests are resolved?

## Requirements *(mandatory)*

### Functional Requirements

**MCP/CLI Asset Management:**
- **FR-001**: System MUST provide an MCP tool `delete_all_assets` that removes all assets (ADMIN role required)
- **FR-002**: System MUST provide a CLI command to delete all assets (ADMIN role required)
- **FR-003**: The delete all assets operation MUST cascade to remove associated vulnerabilities and exception requests

**MCP/CLI User Management:**
- **FR-004**: System MUST provide an MCP tool or CLI command to create a new user with specified email and password
- **FR-005**: System MUST provide an MCP tool or CLI command to delete a user by email

**MCP/CLI Vulnerability Management:**
- **FR-006**: System MUST allow adding vulnerabilities via CLI/MCP with configurable `daysOpen` value
- **FR-007**: System MUST allow specifying vulnerability criticality (e.g., CRITICAL, HIGH, MEDIUM, LOW)
- **FR-008**: System MUST correctly calculate overdue status based on daysOpen and severity thresholds

**MCP Vulnerability Query:**
- **FR-009**: System MUST provide an MCP tool to query vulnerabilities for the authenticated user's accessible assets
- **FR-010**: System MUST provide an MCP tool to query specifically overdue vulnerabilities
- **FR-011**: System MUST provide an MCP tool to get a count/summary of overdue vulnerabilities

**MCP Exception Request Workflow:**
- **FR-012**: System MUST provide an MCP tool to create an exception request for a specific vulnerability
- **FR-013**: System MUST provide an MCP tool for users to view their own exception requests
- **FR-014**: System MUST provide an MCP tool for ADMIN users to view all pending exception requests
- **FR-015**: System MUST provide an MCP tool for ADMIN users to approve an exception request
- **FR-016**: System MUST provide an MCP tool for ADMIN users to reject an exception request

**Authentication:**
- **FR-017**: MCP MUST support user delegation to allow admin API keys to act on behalf of specific users
- **FR-018**: MCP MUST support direct authentication for regular users via their own API keys

### Key Entities

- **User**: Represents a system user with email, password, roles (USER, ADMIN, etc.), and workgroup assignments
- **Asset**: Represents a managed asset with owner reference, workgroup assignments, and vulnerability associations
- **Vulnerability**: Represents a security vulnerability with CVE, severity, daysOpen, and asset reference
- **VulnerabilityExceptionRequest**: Represents a request to exempt a specific vulnerability from overdue tracking, with status (PENDING, APPROVED, REJECTED, CANCELLED)

## Clarifications

### Session 2026-01-14

- Q: What is the overdue threshold for CRITICAL severity vulnerabilities? → A: 30 days (40-day vuln is overdue, 10-day is not)
- Q: What should happen when a test step fails? → A: Fail fast - stop immediately on first failure with clear error message
- Q: What if test entities already exist from a previous failed run? → A: Delete and recreate - remove any pre-existing test entities before starting
- Q: How should test user apple@schmall.io authenticate via MCP? → A: Admin delegation - admin API key with X-MCP-User-Email header
- Q: What password should test user apple@schmall.io have? → A: TestPassword123!

## Design Decisions

- **Asset Access Method**: Direct ownership via the asset's `owner` field (simpler, more direct than workgroup membership)
- **Test Type**: CLI executable script that can run against any environment (not a Testcontainers integration test)
- **Admin User**: Use the existing demo admin account with password "Demopassword4321%" (no fresh admin creation)
- **Failure Behavior**: Fail fast - stop immediately on first failure with clear error message (later steps depend on earlier ones)
- **Idempotency**: Delete and recreate any pre-existing test entities before starting (ensures clean slate after failed runs)
- **Test User Auth**: Admin delegation via X-MCP-User-Email header (no separate API key needed for apple@schmall.io)
- **Test User Password**: `TestPassword123!` for apple@schmall.io

## Assumptions

- The existing MCP exception request tools (`create_exception_request`, `get_my_exception_requests`, `get_pending_exception_requests`, `approve_exception_request`, `reject_exception_request`) are already implemented based on the codebase exploration
- The existing `add-vulnerability` CLI command can be extended to support specifying `daysOpen`
- Overdue threshold for CRITICAL severity is 30 days (vulnerabilities open longer than 30 days are overdue)
- The test user "apple@schmall.io" will have the USER role by default
- The demo admin account already exists in the test environment

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The complete end-to-end test scenario (all 7 user stories) can be executed in under 60 seconds
- **SC-002**: All RBAC checks correctly prevent unauthorized operations (100% of unauthorized attempts blocked)
- **SC-003**: The test can be run repeatedly without manual cleanup between runs
- **SC-004**: All MCP tools return appropriate success/error responses with clear status messages
- **SC-005**: The exception request workflow correctly transitions through all states (PENDING -> APPROVED)
- **SC-006**: Overdue detection correctly identifies vulnerabilities based on age and severity thresholds
