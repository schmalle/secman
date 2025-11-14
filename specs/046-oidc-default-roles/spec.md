# Feature Specification: OIDC Default Roles

**Feature Branch**: `046-oidc-default-roles`
**Created**: 2025-11-14
**Status**: Draft
**Input**: User description: "ensure that all newly created users via OIDC have USER and VULN roles."

## Clarifications

### Session 2025-11-14

- Q: What happens if role assignment partially fails during user creation? → A: All-or-nothing: If role assignment fails, rollback entire user creation (user record is not created)
- Q: Should role assignment events be logged for security audit trails? → A: Yes, log each role assignment event with timestamp, user identity, assigned roles, and identity provider source
- Q: Should administrators be notified when a new OIDC user is created? → A: Yes, send real-time notification (email/in-app) to all administrators when a new OIDC user is created
- Q: What notification mechanism should be used for administrator notifications? → A: Email notification only (sent to administrator's registered email address)
- Q: What happens if email notification to administrators fails? → A: Allow user creation to succeed, log the notification failure, continue (email is best-effort)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - First-Time OIDC Login with Default Permissions (Priority: P1)

A new employee joins the organization and attempts to log in for the first time using their corporate identity provider (Google Workspace, Microsoft Entra ID, etc.). Upon successful authentication, they should immediately have access to view assets and vulnerabilities without requiring manual administrator intervention to assign roles.

**Why this priority**: This is the core functionality requested. Without this, new OIDC users cannot access the system's primary features (assets and vulnerabilities) after their first login, creating friction and requiring manual administrator intervention for every new user.

**Independent Test**: Can be fully tested by configuring an OIDC identity provider, authenticating as a brand new user (never seen before by the system), and verifying the user can immediately view assets and vulnerabilities after the first login completes.

**Acceptance Scenarios**:

1. **Given** an OIDC identity provider is configured with auto-provisioning enabled, **When** a user authenticates for the first time via OIDC, **Then** a new user account is created with both USER and VULN roles assigned
2. **Given** a newly created OIDC user has logged in, **When** they navigate to the assets page, **Then** they can view assets based on standard access control rules (workgroup membership, ownership, mappings)
3. **Given** a newly created OIDC user has logged in, **When** they navigate to the vulnerabilities page, **Then** they can view current vulnerabilities for accessible assets
4. **Given** a newly created OIDC user has logged in, **When** they attempt to access vulnerability exception management, **Then** they can create, view, and manage vulnerability exception requests as permitted by the VULN role
5. **Given** a new user is successfully created via OIDC auto-provisioning, **When** the user creation completes, **Then** all users with ADMIN role receive an email notification containing the new user's username, email, assigned roles, and identity provider

---

### User Story 2 - Consistent Role Assignment Across Identity Providers (Priority: P2)

An organization uses multiple identity providers (e.g., Google for contractors, Microsoft for employees). All new users, regardless of which OIDC provider they authenticate through, should receive the same default roles to ensure consistent access policies.

**Why this priority**: Ensures uniform security posture and user experience across different authentication methods, preventing confusion and access inconsistencies.

**Independent Test**: Can be tested by configuring two different OIDC providers, creating a new user through each provider, and verifying both users receive identical USER and VULN roles.

**Acceptance Scenarios**:

1. **Given** multiple OIDC identity providers are configured, **When** a new user authenticates via any configured OIDC provider, **Then** the user receives both USER and VULN roles regardless of which provider was used
2. **Given** different OIDC providers may return different claim structures, **When** auto-provisioning creates a user, **Then** role assignment is independent of provider-specific claims and applies the same default roles

---

### User Story 3 - Existing User Login (Priority: P3)

An existing user who was previously created via OIDC logs in again. Their roles should remain unchanged - the default role assignment only applies to new user creation, not subsequent logins.

**Why this priority**: Prevents unintended role changes to existing users and ensures administrators can customize roles after initial creation without those customizations being overwritten.

**Independent Test**: Can be tested by creating a user via OIDC, modifying their roles (e.g., adding ADMIN, removing VULN), then logging in again and verifying the roles remain as modified.

**Acceptance Scenarios**:

1. **Given** a user already exists in the system from a previous OIDC login, **When** they authenticate via OIDC again, **Then** their existing roles are preserved and not reset to defaults
2. **Given** an administrator has modified an OIDC user's roles, **When** that user logs in via OIDC, **Then** the custom role configuration remains unchanged

---

### Edge Cases

- What happens when auto-provisioning is disabled for an OIDC provider? (User creation should not occur, default roles are not relevant)
- How does the system handle role assignment if the identity provider's role mapping configuration conflicts with the default roles? (Default roles should be assigned first, then role mappings applied on top)
- What happens if an OIDC user is manually created by an administrator before the user's first OIDC login? (Existing user scenario applies - roles are not changed)
- How does the system handle OIDC authentication errors or token validation failures? (No user creation occurs, no role assignment)
- What happens if role assignment fails during user creation? (Entire user creation transaction is rolled back; no partial user account is created)
- What happens if administrator email notification fails? (User creation proceeds successfully; notification failure is logged for troubleshooting but does not block the operation)

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST assign the USER role to all newly created users during OIDC auto-provisioning
- **FR-002**: System MUST assign the VULN role to all newly created users during OIDC auto-provisioning
- **FR-003**: System MUST apply default role assignment only during initial user creation, not on subsequent OIDC logins
- **FR-004**: System MUST apply default roles before processing any identity provider role mapping configurations
- **FR-005**: System MUST apply the same default roles (USER and VULN) regardless of which OIDC identity provider is used for authentication
- **FR-006**: System MUST NOT modify roles of existing users when they authenticate via OIDC
- **FR-007**: System MUST only assign default roles when auto-provisioning is enabled for the identity provider
- **FR-008**: System MUST persist the assigned roles to the user entity so they are available for all subsequent authentication and authorization checks
- **FR-009**: System MUST treat user creation and role assignment as an atomic transaction; if role assignment fails, the entire user creation MUST be rolled back to prevent orphaned accounts with incomplete permissions
- **FR-010**: System MUST log each successful role assignment event including timestamp, user identifier (username/email), assigned roles (USER, VULN), and identity provider name for security audit and compliance purposes
- **FR-011**: System MUST send an email notification to all users with the ADMIN role when a new user is successfully created via OIDC auto-provisioning, including the new user's username, email, assigned roles, and identity provider used
- **FR-012**: System MUST NOT block user creation if administrator email notification fails; notification delivery is best-effort and failures MUST be logged for troubleshooting

### Key Entities

- **User**: Represents a system user account with username, email, passwordHash (nullable for OIDC users), and a collection of roles (USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION). Newly created OIDC users will have roles initialized to include USER and VULN.
- **IdentityProvider**: Represents an OIDC/SAML identity provider configuration with auto-provisioning settings. The autoProvision flag determines whether new users should be created automatically, triggering the default role assignment logic.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of new users created via OIDC auto-provisioning receive both USER and VULN roles
- **SC-002**: Zero manual administrator interventions required for new OIDC users to access assets and vulnerabilities after first login
- **SC-003**: New OIDC users can view assets and vulnerabilities within 5 seconds of completing their first authentication
- **SC-004**: Existing users' customized role configurations remain unchanged after OIDC re-authentication (0% unintended role modifications)
- **SC-005**: Role assignment is consistent across all configured OIDC providers (100% consistency rate)

## Non-Functional Requirements

### Observability

- **NFR-001**: All role assignment events MUST be logged with sufficient detail to support security audits, including user identification, timestamp (ISO 8601 format), roles assigned, and identity provider source
- **NFR-002**: Log entries MUST be retained according to the system's standard security event retention policy for compliance and forensic analysis

### Reliability

- **NFR-003**: Administrator email notification delivery MUST be implemented as a non-blocking operation that does not impact user creation transaction performance or success
- **NFR-004**: Email notification failures MUST be logged with error details to enable administrators to diagnose delivery issues and review missed notifications via audit logs

## Assumptions

- The existing OIDC auto-provisioning mechanism (Feature 041) is fully functional and creates user accounts upon first login
- The roles collection on the User entity supports multiple role assignments
- The USER role grants basic access to view assets (subject to access control rules)
- The VULN role grants access to vulnerability viewing and exception request management
- No regulatory or compliance requirements prohibit granting these default roles to all new users
- Administrators retain the ability to modify user roles after initial creation via the user management interface
- Identity provider role mapping configurations (if present) are intended to be additive to the default roles, not replacements
