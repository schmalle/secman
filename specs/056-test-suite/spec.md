# Feature Specification: Test Suite for Secman

**Feature Branch**: `056-test-suite`
**Created**: 2025-12-28
**Status**: Draft
**Input**: User description: "please carefully plan and implement a test suite for secman. Please plan a minimal set of test cases, which need to be covered. Functionality from CLI and web must be tested. This includes add a vulnerability via CLI for a system A with high criticality and open duration of 60 days."

## Clarifications

### Session 2025-12-29

- Q: Which edge cases should be tested in this minimal test suite? → A: All 5 edge cases (comprehensive coverage)
- Q: How should test data (users, assets) be set up for tests? → A: Programmatic setup in @BeforeEach methods (per-test isolation)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - CLI Add Vulnerability for System A (Priority: P1)

A security engineer needs to add a vulnerability via the CLI tool for an asset named "system-a" with HIGH criticality that has been open for 60 days. This is the core user requirement specified by the stakeholder.

**Why this priority**: This is the explicit user requirement and represents the primary CLI functionality that must be tested.

**Independent Test**: Can be tested by executing the CLI command with specified parameters and verifying the vulnerability appears in the database with correct attributes.

**Acceptance Scenarios**:

1. **Given** a running backend with an authenticated user having ADMIN or VULN role, **When** executing `add-vulnerability --hostname system-a --cve CVE-2024-TEST001 --criticality HIGH --days-open 60`, **Then** asset "system-a" is created (or found), vulnerability is recorded with severity "High", scan timestamp is 60 days in the past, and days-open displays as "60 days"

2. **Given** asset "system-a" already exists with a vulnerability, **When** executing the same CLI command with a different CVE, **Then** a second vulnerability is added to the existing asset without duplicating the asset

3. **Given** the CLI command is executed with invalid credentials, **When** authentication fails, **Then** an appropriate error message is displayed and exit code is non-zero

---

### User Story 2 - Web API Vulnerability Management (Priority: P2)

An administrator needs to query and manage vulnerabilities through the web API to verify that vulnerabilities added via CLI appear correctly in the web interface.

**Why this priority**: The web API is the complement to CLI functionality - vulnerabilities added via CLI must be retrievable via web.

**Independent Test**: Can be tested by making HTTP requests to vulnerability endpoints and verifying correct responses.

**Acceptance Scenarios**:

1. **Given** a vulnerability exists for "system-a", **When** calling `GET /api/vulnerabilities/current`, **Then** the response includes the vulnerability with correct hostname, CVE, severity, and days-open information

2. **Given** a user with ADMIN role, **When** calling the CLI-add endpoint directly via `POST /api/vulnerabilities/cli-add`, **Then** the vulnerability is created/updated and the response contains operation status

3. **Given** a user with USER role (not ADMIN or VULN), **When** attempting to call protected vulnerability endpoints, **Then** access is denied with appropriate HTTP status code

---

### User Story 3 - Authentication Flow Testing (Priority: P3)

A developer needs to verify that the authentication system works correctly for both CLI and web access to ensure secure access to vulnerability management features.

**Why this priority**: Authentication is a prerequisite for all other operations but is foundational infrastructure rather than a primary feature.

**Independent Test**: Can be tested by attempting login with valid/invalid credentials and verifying token generation.

**Acceptance Scenarios**:

1. **Given** valid username and password, **When** calling `POST /api/auth/login`, **Then** a JWT token is returned with user roles

2. **Given** invalid credentials, **When** attempting to authenticate, **Then** an appropriate error is returned without revealing which field was incorrect

3. **Given** a valid JWT token, **When** making authenticated requests, **Then** the request succeeds and user identity is correctly extracted

---

### Edge Cases

The following edge cases MUST be tested (comprehensive coverage):

- **EC-001**: Hostname with special characters (e.g., `server-01.domain.local`, `server_name`) and maximum length (255 chars)
- **EC-002**: Concurrent CLI commands adding vulnerabilities to the same asset (verify no race conditions or duplicates)
- **EC-003**: days-open = 0 (newly discovered vulnerability) - verify scanTimestamp equals current time
- **EC-004**: Database connection temporarily unavailable - verify graceful error handling and appropriate exit code
- **EC-005**: Lowercase CVE ID input (e.g., `cve-2024-1234`) - verify case-insensitive handling or normalization

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Test infrastructure MUST support JUnit 5 with Mockk for mocking in Kotlin
- **FR-002**: Integration tests MUST use Testcontainers with MariaDB to mirror production environment
- **FR-003**: CLI tests MUST verify parameter validation (required fields, criticality enum, non-negative days-open)
- **FR-004**: Service tests MUST verify the upsert pattern (create new vs update existing vulnerability)
- **FR-005**: Controller tests MUST verify correct HTTP status codes and response bodies
- **FR-006**: Authentication tests MUST verify JWT token generation and validation
- **FR-007**: RBAC tests MUST verify role-based access (ADMIN and VULN can add vulnerabilities, USER cannot)
- **FR-008**: Test suite MUST include the specific test case: CLI add vulnerability for "system-a" with HIGH criticality and 60 days open
- **FR-009**: Tests MUST verify asset auto-creation with correct defaults (type=SERVER, owner=CLI-IMPORT)
- **FR-010**: Tests MUST verify criticality mapping (CRITICAL to "Critical", HIGH to "High", etc.)
- **FR-011**: Tests MUST verify scan timestamp calculation from days-open parameter
- **FR-012**: Build system MUST execute all tests during `./gradlew build`
- **FR-013**: Tests MUST cover all 5 edge cases defined in EC-001 through EC-005
- **FR-014**: Test data (users, assets, vulnerabilities) MUST be created programmatically in @BeforeEach methods for per-test isolation

### Key Entities *(include if feature involves data)*

- **Asset**: Represents a system/server; key attributes include name (hostname), type, owner, lastSeen
- **Vulnerability**: Represents a security vulnerability; key attributes include vulnerabilityId (CVE), cvssSeverity, daysOpen, scanTimestamp, importTimestamp
- **User**: Represents an authenticated user; key attributes include username, passwordHash, roles

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All tests pass when running `./gradlew build` with exit code 0
- **SC-002**: Test coverage for VulnerabilityService.addVulnerabilityFromCli() includes all code paths (create asset, find asset, create vuln, update vuln)
- **SC-003**: The specific test case (system-a, HIGH, 60 days) executes successfully and verifies correct data in database
- **SC-004**: Integration tests complete within 2 minutes on standard development hardware
- **SC-005**: Authentication tests verify both success and failure scenarios
- **SC-006**: RBAC tests confirm that unauthorized roles receive HTTP 403/401 responses
- **SC-007**: All 5 edge cases (EC-001 through EC-005) have passing tests

## Assumptions

- Test execution requires Docker for Testcontainers (MariaDB container)
- Test database is isolated and does not affect production data
- The existing "Never write testcases" project principle is explicitly overridden for this feature per user request
- Test data (users, assets, vulnerabilities) is created programmatically in @BeforeEach methods with known credentials (e.g., testadmin/testpass)
- Each test runs with a clean database state to ensure isolation and reproducibility
