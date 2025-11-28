# Feature Specification: MCP User Delegation

**Feature Branch**: `050-mcp-user-delegation`
**Created**: 2025-11-28
**Status**: Draft
**Input**: User description: "implement / extend the existing MCP server code, i want to ensure, that if a user is coming from another tool, which connects to secman, that the authenticated email address from the other tool can be passed through to secman and that all role based access is still working. Please also analyze existing documentation for MCP and extend where needed."

## Summary

Enable external tools that integrate with Secman via MCP to pass through an authenticated user's email address, allowing Secman to apply that user's existing roles and permissions. This extends the current API key-based authentication to support delegated user authentication where a trusted external tool vouches for a user's identity.

## Clarifications

### Session 2025-11-28

- Q: How should the system protect against email header tampering/impersonation by delegation-enabled keys? → A: Require domain restrictions on all delegation-enabled keys (cannot enable delegation without specifying at least one allowed domain)
- Q: What permissions should apply during delegated requests? → A: Use intersection of user roles AND API key permissions (both must allow the action)
- Q: Should the system generate security alerts for delegation failures beyond audit logging? → A: Alert administrators after N failed delegation attempts per API key within a time window (threshold-based alerting)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - External Tool Authenticates User and Accesses Secman (Priority: P1)

An administrator at an organization uses a third-party security dashboard that integrates with Secman via MCP. The dashboard has its own authentication (e.g., SSO with Microsoft Azure). When the administrator queries security requirements through the dashboard, their authenticated email is passed to Secman, and they see only the data their Secman roles allow them to access.

**Why this priority**: This is the core use case - enabling trusted external tools to make MCP requests on behalf of authenticated users while respecting Secman's existing RBAC.

**Independent Test**: Can be fully tested by configuring a trusted MCP API key with delegation enabled, sending a request with a user's email, and verifying the response reflects that user's permissions rather than the API key's permissions.

**Acceptance Scenarios**:

1. **Given** an MCP API key is configured with user delegation enabled, **When** an external tool sends a request with a valid user email in the `X-MCP-User-Email` header, **Then** the system applies the intersection of that user's roles and the API key's permissions to the request (both must allow the action).

2. **Given** an MCP API key is configured with user delegation enabled, **When** an external tool sends a request with an email for a user who exists in Secman, **Then** the user can access data permitted by both their roles AND the API key's permissions.

3. **Given** an MCP API key is configured with user delegation enabled, **When** an external tool sends a request with an email for a user who does NOT exist in Secman, **Then** the request is rejected with a clear error message indicating the user is not found.

---

### User Story 2 - Administrator Configures Trusted Delegation Keys (Priority: P1)

A Secman administrator creates an MCP API key specifically for a trusted internal tool. They enable "user delegation" mode on this key, which allows the key to pass through user emails. The administrator can also restrict which email domains are allowed for delegation.

**Why this priority**: Without the ability to configure delegation-enabled API keys, the feature cannot be used. This is a prerequisite for User Story 1.

**Independent Test**: Can be tested by creating an API key through the admin UI with delegation enabled and verifying the configuration is saved correctly.

**Acceptance Scenarios**:

1. **Given** an administrator is on the MCP API Keys management page, **When** they create a new API key, **Then** they see an option to enable "User Delegation" mode.

2. **Given** an administrator enables User Delegation mode, **When** they configure the key, **Then** they must specify at least one allowed email domain (e.g., "@company.com") before saving.

3. **Given** a delegation-enabled API key exists, **When** the administrator views the key details, **Then** they can see that delegation is enabled and any domain restrictions.

---

### User Story 3 - Audit Trail for Delegated Requests (Priority: P2)

When an external tool makes a delegated request on behalf of a user, both the API key and the delegated user's email are logged in the audit trail. Security teams can review which actions were taken by which users through which external tools.

**Why this priority**: Audit logging is critical for security and compliance, but the core functionality must work first.

**Independent Test**: Can be tested by making delegated requests and verifying the audit logs contain both the API key identifier and the delegated user email.

**Acceptance Scenarios**:

1. **Given** a delegated MCP request is made, **When** the system logs the action, **Then** the audit log includes: API key ID, delegated user email, tool called, timestamp, and result.

2. **Given** an administrator views the MCP audit logs, **When** they filter by delegated user, **Then** they can see all actions taken on behalf of that user across all API keys.

---

### User Story 4 - Fallback to API Key Permissions (Priority: P2)

When a delegation-enabled API key is used but no user email is provided (or the header is omitted), the system gracefully falls back to the API key's own permissions. This ensures backward compatibility with existing integrations.

**Why this priority**: Backward compatibility ensures existing integrations continue working after the feature is deployed.

**Independent Test**: Can be tested by using a delegation-enabled key without the user email header and verifying the API key's base permissions are used.

**Acceptance Scenarios**:

1. **Given** a delegation-enabled MCP API key, **When** a request is made without the `X-MCP-User-Email` header, **Then** the system uses the API key's own permissions.

2. **Given** a non-delegation MCP API key (legacy key), **When** a request includes the `X-MCP-User-Email` header, **Then** the header is ignored and the API key's permissions are used.

---

### User Story 5 - Domain Restriction Enforcement (Priority: P3)

When a delegation-enabled API key has domain restrictions configured, the system rejects any delegated requests for users outside the allowed domains. This prevents misuse of delegation keys.

**Why this priority**: Domain restrictions add an extra security layer but are not required for the basic delegation flow.

**Independent Test**: Can be tested by configuring domain restrictions on an API key and attempting to delegate for a user outside the allowed domain.

**Acceptance Scenarios**:

1. **Given** a delegation-enabled key with domain restriction "@company.com", **When** a request passes email "user@company.com", **Then** the request is processed normally.

2. **Given** a delegation-enabled key with domain restriction "@company.com", **When** a request passes email "user@other.com", **Then** the request is rejected with error "Email domain not allowed for delegation".

---

### Edge Cases

- What happens when the delegated user email format is invalid? System rejects with validation error.
- What happens when the delegated user exists but has no roles? User is authenticated but has minimal permissions (same as if they logged into web UI with no roles).
- What happens when the delegated user account is disabled/inactive? Request is rejected with "User account is inactive" error.
- How does system handle empty string for the email header? Treated as if header is not present; falls back to API key permissions.
- What happens if the external tool sends multiple emails in the header? First valid email is used; behavior is documented.
- What happens when failed delegation attempts exceed the alert threshold? Administrators receive a security alert; the API key is NOT automatically disabled (manual intervention required).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST accept an optional `X-MCP-User-Email` header on MCP API requests when the API key has delegation enabled.
- **FR-002**: System MUST look up the user by email when the delegation header is provided and apply the intersection of that user's roles AND the API key's permissions to the request (both must allow the action for it to succeed).
- **FR-003**: System MUST reject delegated requests when the specified user does not exist in Secman.
- **FR-004**: System MUST allow administrators to enable/disable "User Delegation" mode when creating or editing MCP API keys.
- **FR-005**: System MUST require administrators to specify at least one allowed email domain when enabling delegation (e.g., "@company.com, @subsidiary.com"). Delegation cannot be enabled without domain restrictions.
- **FR-006**: System MUST enforce domain restrictions when configured, rejecting delegation requests for users outside allowed domains.
- **FR-007**: System MUST fall back to the API key's own permissions when no user email header is provided or when delegation is disabled for the key.
- **FR-008**: System MUST log all delegated requests with both the API key identifier and the delegated user email in audit logs.
- **FR-009**: System MUST reject delegation when the user account is disabled or inactive.
- **FR-010**: System MUST validate the email format before attempting user lookup.
- **FR-013**: System MUST alert administrators when failed delegation attempts exceed a configurable threshold per API key within a time window (e.g., 10 failures in 5 minutes).
- **FR-011**: System MUST continue to support existing non-delegation API keys without modification (backward compatibility).
- **FR-012**: System MUST update the MCP Integration documentation to describe the user delegation feature.

### Non-Functional Requirements

- **NFR-001**: User lookup by email MUST complete within 100ms to avoid impacting MCP request latency.
- **NFR-002**: Delegation feature MUST NOT require changes to the MCP protocol or existing tool integrations (uses standard HTTP headers).
- **NFR-003**: Delegation settings MUST be configurable without restarting the backend service.

### Key Entities

- **McpApiKey** (existing, extended): Add `delegationEnabled` flag and optional `allowedDelegationDomains` field to track which keys can perform user delegation and domain restrictions.
- **User** (existing): No changes required; existing email field and roles are used for delegation lookup.
- **McpAuditLog** (existing, extended): Add optional `delegatedUserEmail` field to track the user on whose behalf a request was made.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: External tools can successfully make MCP requests on behalf of authenticated users within 200ms total response time.
- **SC-002**: 100% of delegated requests are logged with both API key and delegated user information.
- **SC-003**: Administrators can configure delegation settings in under 2 minutes through the existing API key management UI.
- **SC-004**: Existing MCP integrations continue working without modification after feature deployment (zero breaking changes).
- **SC-005**: Invalid delegation attempts (non-existent user, wrong domain, inactive user) are rejected with clear, actionable error messages.

## Assumptions

- External tools are responsible for authenticating their users before passing email addresses to Secman.
- The MCP API key acts as a "service account" that vouches for the user's identity.
- Only tools with delegation-enabled API keys are trusted to perform user delegation.
- Users must already exist in Secman (no auto-provisioning through MCP delegation).
- The `X-MCP-User-Email` header name follows existing MCP header conventions (X-MCP-API-Key).

## Dependencies

- Existing MCP authentication infrastructure (McpAuthenticationService, McpApiKey entity).
- Existing user management (UserRepository, User entity with email and roles).
- Existing MCP audit logging (McpAuditService, McpAuditLog entity).
- Frontend MCP API Keys management page for UI configuration.

## Out of Scope

- OAuth2/OIDC token validation between external tools and Secman (external tools handle their own authentication).
- Auto-provisioning users through MCP delegation (users must pre-exist).
- Revoking individual user's access through the delegation key (use normal user management).
- Rate limiting per delegated user (rate limits remain per API key).
