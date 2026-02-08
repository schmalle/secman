# Feature Specification: REQADMIN Role for Release Management

**Feature Branch**: `079-reqadmin-release-role`
**Created**: 2026-02-06
**Status**: Draft
**Input**: User description: "Add a role REQADMIN. Only users with REQADMIN role and/or ADMIN role are allowed to add / delete releases. Ensure this role feature is fully implemented in the UI, all endpoints and in MCP code. Release feature must be fully reflected in MCP. MCP documentation must be updated accordingly. Extended full end-to-end test suite for releases."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - REQADMIN users can create and delete releases (Priority: P1)

A user with the REQADMIN role logs into SecMan and navigates to the Release Management page. They can see the "Create New Release" button, create a new release in PREPARATION status, and delete releases that are not in ACTIVE status. This is the core authorization change: only ADMIN or REQADMIN users are allowed to create and delete releases.

**Why this priority**: This is the fundamental permission change that all other deliverables depend on. Without this, the role has no effect.

**Independent Test**: Can be tested by assigning REQADMIN role to a user, logging in, and verifying they can create and delete releases through the UI and API.

**Acceptance Scenarios**:

1. **Given** a user with REQADMIN role, **When** they navigate to Release Management, **Then** they see the "Create New Release" button and can create a release.
2. **Given** a user with REQADMIN role, **When** they attempt to delete a PREPARATION release, **Then** the release is deleted successfully.
3. **Given** a user with ADMIN role (no REQADMIN), **When** they attempt to create or delete a release, **Then** the operation succeeds (ADMIN retains full access).
4. **Given** a user with only RELEASE_MANAGER role (no ADMIN, no REQADMIN), **When** they attempt to create or delete a release, **Then** the operation is denied.
5. **Given** a user with only USER role, **When** they navigate to Release Management, **Then** they do not see create/delete controls and API calls are rejected.

---

### User Story 2 - REQADMIN authorization enforced across all REST endpoints (Priority: P1)

All release-related REST API endpoints that previously required ADMIN or RELEASE_MANAGER for create/delete operations are updated. Create and delete release endpoints now require ADMIN or REQADMIN. Read-only endpoints (list, get, compare) remain accessible to all authenticated users. Status management endpoints (activate, alignment) retain their current ADMIN/RELEASE_MANAGER authorization since those are release lifecycle operations, not creation/deletion.

**Why this priority**: API-level security is the enforcement layer. Without this, the role is only cosmetic in the UI.

**Independent Test**: Can be tested by making direct API calls with Bearer tokens for users of different roles, verifying success vs rejection responses.

**Acceptance Scenarios**:

1. **Given** a user with REQADMIN role, **When** they call the create release endpoint, **Then** the release is created successfully.
2. **Given** a user with REQADMIN role, **When** they call the delete release endpoint, **Then** the release is deleted successfully.
3. **Given** a user with only RELEASE_MANAGER role, **When** they call the create release endpoint, **Then** they receive an authorization denial.
4. **Given** a user with only RELEASE_MANAGER role, **When** they call the delete release endpoint, **Then** they receive an authorization denial.
5. **Given** a user with RELEASE_MANAGER role, **When** they call the update release status endpoint, **Then** the operation succeeds (status management remains available to RELEASE_MANAGER).

---

### User Story 3 - REQADMIN authorization enforced in MCP tools (Priority: P1)

All release-related MCP tools that perform create or delete operations are updated to require ADMIN or REQADMIN role via User Delegation. MCP tools for read operations (list, get, compare) and status management (set_release_status) retain their current ADMIN/RELEASE_MANAGER authorization. The MCP documentation is updated to reflect the correct role requirements for each tool.

**Why this priority**: MCP is an external integration point. If MCP tools are not updated, the authorization change can be bypassed.

**Independent Test**: Can be tested by calling MCP tools via the API with different delegated user roles and verifying authorization responses.

**Acceptance Scenarios**:

1. **Given** an MCP client with User Delegation for a REQADMIN user, **When** they call create_release, **Then** the release is created successfully.
2. **Given** an MCP client with User Delegation for a REQADMIN user, **When** they call delete_release, **Then** the release is deleted successfully.
3. **Given** an MCP client with User Delegation for a RELEASE_MANAGER user (no ADMIN/REQADMIN), **When** they call create_release, **Then** they receive an authorization error.
4. **Given** an MCP client with User Delegation for a RELEASE_MANAGER user, **When** they call list_releases or compare_releases, **Then** the operation succeeds (read operations remain accessible).

---

### User Story 4 - Frontend UI respects REQADMIN role (Priority: P2)

The Release Management UI components are updated so that create and delete controls are only visible to users with ADMIN or REQADMIN roles. Users with RELEASE_MANAGER (but not ADMIN/REQADMIN) can still view releases and manage release status (alignment, activation) but cannot create or delete releases. The release selector, release list, and release detail pages reflect the appropriate permissions.

**Why this priority**: UI-level enforcement improves user experience by hiding unavailable actions, but the backend already prevents unauthorized operations.

**Independent Test**: Can be tested by logging in with different role combinations and verifying which buttons/actions are visible.

**Acceptance Scenarios**:

1. **Given** a user with REQADMIN role, **When** they view Release Management, **Then** they see "Create New Release" button and delete actions.
2. **Given** a user with RELEASE_MANAGER role (no ADMIN/REQADMIN), **When** they view Release Management, **Then** they do NOT see "Create New Release" or delete actions.
3. **Given** a user with RELEASE_MANAGER role, **When** they view a release in ALIGNMENT status, **Then** they still see alignment management controls (unchanged).

---

### User Story 5 - Comprehensive end-to-end test suite (Priority: P2)

A bash-based end-to-end test script validates the complete release lifecycle with REQADMIN role enforcement. The script uses environment variables (SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY) for authentication and tests: role-based authorization for create/delete, release lifecycle (PREPARATION -> ALIGNMENT -> ACTIVE -> ARCHIVED), MCP tool authorization, and export functionality per release.

**Why this priority**: Automated tests ensure the feature works correctly and prevents regressions.

**Independent Test**: The script itself is the test. Run `./scripts/release-e2e-test.sh` and verify all test cases pass.

**Acceptance Scenarios**:

1. **Given** valid credentials in environment variables, **When** the test script is executed, **Then** all test cases pass with clear pass/fail output.
2. **Given** the test script, **When** a REQADMIN user creates a release via API, **Then** the test verifies successful creation and correct release data.
3. **Given** the test script, **When** the full release lifecycle is executed, **Then** each status transition is verified.
4. **Given** the test script, **When** MCP tools are called with appropriate roles, **Then** authorization is correctly enforced.

---

### User Story 6 - MCP documentation updated (Priority: P3)

The MCP documentation (docs/MCP.md) is updated to reflect the new REQADMIN role requirement for create_release and delete_release tools. The documentation clearly states which tools require ADMIN or REQADMIN and which tools require ADMIN or RELEASE_MANAGER.

**Why this priority**: Documentation ensures external integrators understand the authorization model, but is not a runtime concern.

**Independent Test**: Review the MCP documentation and verify all role requirements match the implementation.

**Acceptance Scenarios**:

1. **Given** the updated MCP documentation, **When** a reader checks the create_release tool documentation, **Then** it states ADMIN or REQADMIN is required.
2. **Given** the updated MCP documentation, **When** a reader checks the delete_release tool documentation, **Then** it states ADMIN or REQADMIN is required.
3. **Given** the updated MCP documentation, **When** a reader checks list_releases, get_release, compare_releases, set_release_status tools, **Then** they state ADMIN or RELEASE_MANAGER is required (unchanged).

---

### Edge Cases

- What happens when a user has both REQADMIN and RELEASE_MANAGER roles? They should be able to perform all release operations (create, delete, and status management).
- What happens when a user has REQADMIN but not RELEASE_MANAGER? They can create/delete releases but cannot manage release status (alignment, activation) unless they also have ADMIN.
- What happens when the REQADMIN role is removed from a user while they have an active session? Their existing JWT token retains the old role until it expires; new API calls after token refresh reflect the updated roles.
- What happens when MCP tools are called without User Delegation? The tools return a DELEGATION_REQUIRED error regardless of role.
- What happens if the only REQADMIN user is deleted? The system does not prevent this; ADMIN users can still manage releases.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow users with ADMIN or REQADMIN role to create releases.
- **FR-002**: System MUST allow users with ADMIN or REQADMIN role to delete releases.
- **FR-003**: System MUST reject release creation and deletion requests from users who have neither ADMIN nor REQADMIN role.
- **FR-004**: System MUST allow users with ADMIN or RELEASE_MANAGER role to manage release status (alignment, activation, archival) -- this authorization is unchanged.
- **FR-005**: MCP tools create_release and delete_release MUST require ADMIN or REQADMIN role via User Delegation.
- **FR-006**: MCP tools list_releases, get_release, compare_releases, and set_release_status MUST continue to require ADMIN or RELEASE_MANAGER role via User Delegation.
- **FR-007**: Frontend Release Management UI MUST show create and delete controls only to users with ADMIN or REQADMIN role.
- **FR-008**: Frontend Release Management UI MUST continue to show status management controls to users with ADMIN or RELEASE_MANAGER role.
- **FR-009**: The REQADMIN role MUST be assignable to users through the existing user management interface.
- **FR-010**: MCP documentation MUST be updated to reflect the correct role requirements for each release tool.
- **FR-011**: An end-to-end test script MUST validate the complete release lifecycle including REQADMIN role authorization.
- **FR-012**: The test script MUST use SECMAN_USERNAME, SECMAN_PASSWORD, and SECMAN_API_KEY environment variables for authentication.
- **FR-013**: Read-only release endpoints (list, get details, compare) MUST remain accessible to all authenticated users regardless of role.

### Key Entities

- **User.Role.REQADMIN**: New role value in the existing Role enum. Grants permission to create and delete releases. Does not replace RELEASE_MANAGER (which retains status management authority).
- **Release**: Existing entity. No schema changes. Authorization rules for create/delete operations change from ADMIN/RELEASE_MANAGER to ADMIN/REQADMIN.

### Assumptions

- The REQADMIN role has already been added to the User.Role enum in a prior feature (078-release-rework). This feature ensures it is consistently enforced across all layers.
- RELEASE_MANAGER role continues to exist and retains authority over release status management (alignment, activation, archival) but loses create/delete authority.
- The existing alignment process endpoints retain their current ADMIN/RELEASE_MANAGER authorization since alignment is a status management operation, not a create/delete operation.
- The e2e test script will be placed at `scripts/release-e2e-test.sh`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users with REQADMIN role can successfully create and delete releases through both the UI and REST API with zero authorization errors.
- **SC-002**: Users without ADMIN or REQADMIN role receive authorization denials for create and delete release operations 100% of the time.
- **SC-003**: MCP tools enforce REQADMIN authorization for create/delete operations, verified by the e2e test suite.
- **SC-004**: The end-to-end test script passes all test cases, covering role authorization, release lifecycle, MCP tools, and export functionality.
- **SC-005**: MCP documentation accurately reflects the authorization requirements for all release-related tools.
- **SC-006**: No regression in existing functionality -- users with RELEASE_MANAGER role can still manage release status (alignment, activation) and view releases.
