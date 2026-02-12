# Feature Specification: Default User Roles on Creation

**Feature Branch**: `080-default-user-roles`
**Created**: 2026-02-12
**Status**: Draft
**Input**: User description: "Ensure that every new created user (via OIDC, via MCP) always has the roles VULN, USER, and REQ"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - OIDC User Gets Default Roles (Priority: P1)

A new employee logs into secman for the first time via their organization's OIDC identity provider. Upon successful authentication, the system automatically creates their account with the default role set: USER, VULN, and REQ. The employee can immediately access vulnerability data and requirements without needing an administrator to manually assign roles.

**Why this priority**: This is the most common user creation path. New employees currently only receive USER and VULN roles via OIDC, meaning they cannot access requirements until an admin manually grants REQ. This creates unnecessary onboarding friction and admin workload.

**Independent Test**: Can be fully tested by logging in with a new OIDC user and verifying their assigned roles include USER, VULN, and REQ.

**Acceptance Scenarios**:

1. **Given** a user does not yet exist in secman, **When** they authenticate via OIDC for the first time, **Then** their account is created with USER, VULN, and REQ roles assigned.
2. **Given** a user already exists in secman with custom roles, **When** they re-authenticate via OIDC, **Then** their existing roles are preserved unchanged.
3. **Given** a user does not yet exist in secman, **When** they authenticate via OIDC for the first time, **Then** the audit log records the role assignment including all three default roles.

---

### User Story 2 - Admin-Created User Gets Default Roles (Priority: P2)

An administrator creates a new user account manually through the admin interface without specifying roles. The system assigns the default role set (USER, VULN, REQ) instead of just USER, ensuring consistency with the OIDC creation path.

**Why this priority**: While less frequent than OIDC creation, manual user creation should follow the same default role policy to avoid inconsistency. Admins can still override roles when explicitly specified.

**Independent Test**: Can be fully tested by creating a user through the admin API without specifying roles and verifying the assigned defaults.

**Acceptance Scenarios**:

1. **Given** an administrator creates a new user without specifying roles, **When** the user is saved, **Then** the user receives USER, VULN, and REQ roles by default.
2. **Given** an administrator creates a new user with explicitly specified roles (e.g., only ADMIN), **When** the user is saved, **Then** the user receives only the explicitly specified roles, not the defaults.

---

### Edge Cases

- What happens when a user is created via OIDC and their identity provider claims include role information? The default roles should still be assigned as a minimum baseline; any additional role mapping from the IdP would be additive.
- What happens when an existing user's roles have been reduced by an admin (e.g., REQ removed) and they re-authenticate via OIDC? Their current roles must be preserved as-is; the defaults only apply at initial account creation.
- What happens when the MCP delegation targets a user that doesn't exist? MCP uses a delegation model that requires users to already exist, so the default roles would have been assigned at the user's original creation time (via OIDC or manual creation).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST assign the roles USER, VULN, and REQ to every newly created user account when no explicit roles are specified.
- **FR-002**: The default role assignment MUST apply to users created via OIDC/OAuth first-time login.
- **FR-003**: The default role assignment MUST apply to users created manually by administrators when no roles are explicitly provided.
- **FR-004**: When an administrator explicitly specifies roles during user creation, the system MUST use the specified roles instead of the defaults.
- **FR-005**: Existing users MUST NOT have their roles modified by this change, regardless of how they re-authenticate.
- **FR-006**: The audit log MUST record the default role assignment for newly created users, including all three assigned roles.

### Key Entities

- **User**: Account record with username, email, authentication source, and a set of assigned roles. The default role set changes from {USER, VULN} to {USER, VULN, REQ}.
- **Role**: A permission category that controls access to system features. The three default roles grant: basic access (USER), vulnerability data access (VULN), and requirements access (REQ).

## Assumptions

- "USERS" in the original request refers to the USER role (the basic authenticated user role).
- MCP does not create users directly; it delegates to existing users. Therefore, MCP-created users are not a distinct creation path. Users accessed via MCP will have received their default roles at their original creation time.
- The default role set is a system-wide policy, not configurable per identity provider.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of newly created users (via any creation path) receive the USER, VULN, and REQ roles when no explicit roles are specified.
- **SC-002**: Zero existing user accounts have their roles modified by this change.
- **SC-003**: Administrators can still override defaults by explicitly specifying roles during user creation, with 100% of explicit role specifications being honored.
- **SC-004**: New users can access both vulnerability data and requirements immediately after their first login without admin intervention.
